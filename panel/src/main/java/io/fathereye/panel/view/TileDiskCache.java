package io.fathereye.panel.view;

import io.fathereye.mapcore.api.ChunkTile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pnl-53 (2026-04-27): on-disk persistence for chunk tiles so every
 * chunk ever discovered by any player stays on the map across panel
 * restarts. The user requested "i want the map to fill out like a
 * vanilla map as they walk forward so that i can see a dynamic view
 * of new chunks being rendered on the map" -- vanilla maps in-game
 * persist the discovered area to disk. We replicate that behaviour:
 * every {@link ChunkTile} the panel receives via the {@code chunk_tile}
 * RPC is also persisted; on next panel launch (or dim switch) every
 * cached tile reloads into {@link MapPane}'s in-memory cache so the
 * full explored area renders immediately.
 *
 * <p>Layout: {@code <workingDir>/fathereye-tiles/<dim-safe>/<cx>_<cz>.tile}
 * One small binary file per chunk so partial writes from a hard kill
 * don't corrupt the whole cache; a corrupt file just gets skipped on
 * load and re-fetched live.
 *
 * <p>Format (little-endian, fixed 1048 bytes per file):
 * <pre>
 *   0..3    magic "FETI" (0x46 0x45 0x54 0x49)
 *   4..7    version (int = 1)
 *   8..11   cx (int)
 *   12..15  cz (int)
 *   16..23  writtenAtMs (long)
 *   24..1047 surfaceArgb (256 ints, 1024 bytes)
 * </pre>
 *
 * <p>Biome and height arrays are NOT persisted -- they're only used
 * for click-info lookups, and stale heightmaps would mislead the
 * user once chunks change. A live re-fetch on click is cheap.
 */
public final class TileDiskCache {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-TileDiskCache");
    private static final int MAGIC = 0x46455449; // "FETI"
    private static final int VERSION = 1;
    private static final int FILE_SIZE = 1048;
    private static final Pattern TILE_NAME = Pattern.compile("(-?\\d+)_(-?\\d+)\\.tile");

    private final Path baseDir;
    private final ExecutorService writer;

    private TileDiskCache(Path baseDir, ExecutorService writer) {
        this.baseDir = baseDir;
        this.writer = writer;
    }

    /**
     * Open (or create) a tile cache rooted at
     * {@code <workingDir>/fathereye-tiles}. Returns null if the
     * working dir is inaccessible.
     *
     * <p>Pnl-63 (2026-04-27): when {@code instanceUuid} is provided
     * (the bridge's per-world UUID persisted at
     * {@code world/serverconfig/fathereye-instance.uuid}), the open
     * compares it to the stamp written into
     * {@code <fathereye-tiles>/.world-id} and wipes every cached
     * tile under the base dir if they differ. The user reported
     * "map ... doesn't reset when i delete the world and generate a
     * new one"; without this stamp the panel kept rendering the
     * old world's tiles on top of the new world's chunks because
     * the {@code TileDiskCache} key never depended on world identity
     * (only on serverDir, which doesn't change when you regenerate
     * the world inside the same server folder). New worlds get a
     * fresh UUID file so the cache invalidates automatically.
     */
    public static TileDiskCache open(Path workingDir) {
        return open(workingDir, null);
    }

    public static TileDiskCache open(Path workingDir, String instanceUuid) {
        if (workingDir == null) return null;
        Path base = workingDir.resolve("fathereye-tiles");
        try {
            Files.createDirectories(base);
        } catch (IOException ioe) {
            LOG.warn("tile cache open failed at {}: {}", base, ioe.getMessage());
            return null;
        }
        // Pnl-63 / Pnl-64 / Pnl-65: world-identity check.
        //
        // Pnl-63 (UUID mismatch): if the stored stamp doesn't match
        // the bridge's instanceUuid, the user deleted + regenerated
        // the world; wipe the cache.
        //
        // Pnl-65 (level.dat creationTime, runs ALWAYS): the UUID
        // check alone wasn't enough — Pnl-64 wrote a fresh stamp
        // matching the current world UUID on first run with the
        // stamp feature, so a user who had an existing v0.2.x
        // install, deleted the world, then upgraded ended up with
        // a stamp that "validated" the now-stale tile cache. The
        // user reported "new chunks are loading fine but the old
        // world chunks are still persisting" on v0.2.5. Run a
        // SECOND check unconditionally: compare the world's
        // level.dat creationTime against the oldest tile in the
        // cache. If the world was created AFTER every tile, those
        // tiles must be from a previous world. We use creationTime
        // (not lastModifiedTime, which the server touches every
        // few minutes during normal play and would wipe valid
        // caches on every relaunch).
        if (instanceUuid != null && !instanceUuid.isEmpty()) {
            Path stampFile = base.resolve(".world-id");
            String stored = null;
            try {
                if (Files.exists(stampFile)) {
                    stored = new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8).trim();
                }
            } catch (IOException ignored) {}
            boolean wipe = false;
            if (stored != null && !stored.equals(instanceUuid)) {
                LOG.info("tile cache world-id changed ({} -> {}); wiping {}",
                        stored, instanceUuid, base);
                wipe = true;
            }
            if (!wipe) {
                Path levelDat = workingDir.resolve("world").resolve("level.dat");
                long worldCreatedMs = -1L;
                try {
                    if (Files.exists(levelDat)) {
                        java.nio.file.attribute.BasicFileAttributes attrs =
                                Files.readAttributes(levelDat, java.nio.file.attribute.BasicFileAttributes.class);
                        // creationTime() is reliable on NTFS / APFS. On
                        // filesystems that don't track creation time it
                        // returns the same value as lastModifiedTime,
                        // which would cause spurious wipes after the
                        // server's periodic save touches level.dat. To
                        // be safe, only trust creationTime when it's
                        // strictly older than the file's
                        // lastModifiedTime (i.e. the FS distinguishes
                        // them). If they're equal, skip the heuristic
                        // — better a stale cache than to wipe the
                        // user's tiles every relaunch.
                        long ct = attrs.creationTime().toMillis();
                        long mt = attrs.lastModifiedTime().toMillis();
                        if (ct > 0 && ct < mt) {
                            worldCreatedMs = ct;
                        }
                    }
                } catch (IOException ignored) {}
                if (worldCreatedMs > 0) {
                    long oldestTileMs = oldestTileMtime(base);
                    if (oldestTileMs != Long.MAX_VALUE && oldestTileMs < worldCreatedMs) {
                        LOG.info("tile cache has tiles older than world creation (oldest tile mtime={}, level.dat creationTime={}); wiping {}",
                                oldestTileMs, worldCreatedMs, base);
                        wipe = true;
                    }
                }
            }
            if (wipe) wipeTileFiles(base);
            try {
                Files.write(stampFile, instanceUuid.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ioe) {
                LOG.warn("tile cache stamp write failed at {}: {}", stampFile, ioe.getMessage());
            }
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "FatherEye-TileWriter");
            t.setDaemon(true);
            return t;
        };
        ExecutorService es = Executors.newSingleThreadExecutor(tf);
        LOG.info("tile cache opened at {} (worldId={})", base, instanceUuid);
        return new TileDiskCache(base, es);
    }

    /** Pnl-64: walk every {@code *.tile} under {@code base} and
     *  return the smallest lastModifiedTime in milliseconds.
     *  Returns {@link Long#MAX_VALUE} if no tiles exist (so the
     *  caller's "world is newer than every tile" comparison is
     *  vacuously false). */
    private static long oldestTileMtime(Path base) {
        long[] oldest = new long[] { Long.MAX_VALUE };
        try (java.util.stream.Stream<Path> stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".tile"))
                    .forEach(p -> {
                        try {
                            long m = Files.getLastModifiedTime(p).toMillis();
                            if (m < oldest[0]) oldest[0] = m;
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return oldest[0];
    }

    /**
     * Pnl-66 (2026-04-27): runtime wipe entry point used when content
     * verification (sample-and-compare against the live bridge)
     * detects that the cached tiles don't match the current world.
     * Calls the same {@link #wipeTileFiles} helper used at open
     * time, so {@code .world-id} stamp and any non-tile siblings are
     * preserved. Synchronously deletes (called from a daemon
     * thread, not the FX thread).
     */
    public void wipeAll() {
        LOG.info("tile cache runtime wipe requested at {}", baseDir);
        wipeTileFiles(baseDir);
    }

    /** Pnl-63: best-effort recursive delete of every {@code *.tile}
     *  under {@code base}. Keeps the dir itself (we just wrote the
     *  new {@code .world-id} stamp into it) and only touches files
     *  matching the tile pattern, so a user who keeps unrelated
     *  files alongside the cache by accident won't lose them. */
    private static void wipeTileFiles(Path base) {
        try {
            Files.walk(base)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".tile"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
        } catch (IOException ioe) {
            LOG.warn("tile cache wipe failed at {}: {}", base, ioe.getMessage());
        }
    }

    /**
     * Persist a tile asynchronously so the IPC reader thread doesn't
     * block on disk I/O. Idempotent; overwrites any existing file
     * for the same (dim, cx, cz).
     */
    public void save(ChunkTile tile) {
        if (tile == null || tile.surfaceArgb == null || tile.surfaceArgb.length != 256) return;
        final String dim = tile.dimensionId == null ? "minecraft_overworld" : sanitiseDim(tile.dimensionId);
        final int cx = tile.chunkX;
        final int cz = tile.chunkZ;
        final int[] argb = tile.surfaceArgb;
        writer.submit(() -> {
            try {
                Path dir = baseDir.resolve(dim);
                Files.createDirectories(dir);
                Path file = dir.resolve(cx + "_" + cz + ".tile");
                ByteBuffer buf = ByteBuffer.allocate(FILE_SIZE);
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                buf.putInt(MAGIC);
                buf.putInt(VERSION);
                buf.putInt(cx);
                buf.putInt(cz);
                buf.putLong(System.currentTimeMillis());
                for (int p : argb) buf.putInt(p);
                Files.write(file, buf.array());
            } catch (IOException ioe) {
                // Best-effort persistence; a transient disk error
                // shouldn't crash the panel. The chunk stays in the
                // in-memory cache and will re-attempt persistence on
                // next chunk_tile response.
                LOG.warn("tile save failed for {}/{}_{}: {}", dim, cx, cz, ioe.getMessage());
            }
        });
    }

    /**
     * Load every cached tile for the given dim. Used on dim switch
     * and on initial bind to populate the in-memory tile cache so
     * the explored area renders immediately. Quietly skips corrupt
     * or wrong-version files; they'll be re-fetched live.
     */
    public List<ChunkTile> loadAllFor(String dimensionId) {
        List<ChunkTile> out = new ArrayList<>();
        if (dimensionId == null) return out;
        Path dir = baseDir.resolve(sanitiseDim(dimensionId));
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tile")) {
            for (Path p : stream) {
                Matcher m = TILE_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                ChunkTile t = loadOne(p, dimensionId);
                if (t != null) out.add(t);
            }
        } catch (IOException ioe) {
            LOG.warn("tile cache list failed for {}: {}", dimensionId, ioe.getMessage());
        }
        return out;
    }

    private static ChunkTile loadOne(Path file, String dim) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != FILE_SIZE) return null;
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt();
            int version = buf.getInt();
            if (magic != MAGIC || version != VERSION) return null;
            int cx = buf.getInt();
            int cz = buf.getInt();
            buf.getLong(); // writtenAtMs, ignored on load
            int[] argb = new int[256];
            boolean anyNonZero = false;
            int blackPixels = 0;
            for (int i = 0; i < 256; i++) {
                argb[i] = buf.getInt();
                if (argb[i] != 0) anyNonZero = true;
                if (argb[i] == 0xFF000000) blackPixels++;
            }
            // Brg-16 (audit fix, 2026-04-27): reject all-zero tiles.
            // Earlier versions of the bridge returned 256-zero
            // surfaceArgb arrays for "chunk not loaded" / "miss
            // budget exhausted" responses; the panel persisted
            // those to disk as valid tiles, blocking future
            // re-fetch. Treating them as corrupt on load and
            // returning null lets the panel re-request the chunk
            // on the next chunks_topic snapshot.
            // Pnl-61 (2026-04-27): also reject mostly-black tiles
            // (>= 252 of 256 pixels at 0xFF000000). These came from
            // Brg-19's holder-level fallback hitting LOAD-ticket-
            // level chunks whose ChunkSection[] was freed —
            // every getBlockState returned AIR -> NONE -> 0xFF000000.
            // The user reported black-rectangle artefacts on the
            // map after Brg-19 deployed; those tiles are now in
            // their disk cache. Auto-delete on warm so the cleanup
            // is invisible — the chunk gets re-fetched and Brg-21's
            // server-side guard prevents new black tiles from being
            // produced.
            if (!anyNonZero || blackPixels >= 252) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                return null;
            }
            // biome bytes and height shorts aren't persisted; pass
            // sentinels so the in-memory ChunkTile is well-formed.
            byte[] biomes = new byte[256];
            short[] heights = new short[256];
            return new ChunkTile(dim, cx, cz, biomes, heights, argb);
        } catch (IOException ioe) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * "minecraft:overworld" -> "minecraft_overworld". Also strips
     * anything outside [A-Za-z0-9_] to keep the dim folder portable
     * across filesystems. Modded dims like
     * "thaumicapprentice:pocket_dimension" become
     * "thaumicapprentice_pocket_dimension".
     */
    private static String sanitiseDim(String dim) {
        if (dim == null) return "_unknown";
        StringBuilder sb = new StringBuilder(dim.length());
        for (int i = 0; i < dim.length(); i++) {
            char c = dim.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "_unknown" : sb.toString();
    }

    public Path baseDir() { return baseDir; }

    /**
     * Pnl-53 (audit fix, 2026-04-27): shut down the writer
     * executor. Called when MapPane.setTileCache replaces the
     * current cache (e.g. on bridge reconnect). Without this, every
     * reconnect leaks one FatherEye-TileWriter daemon thread that
     * stays alive for the rest of the panel JVM. Idempotent and
     * safe to call multiple times.
     */
    public void close() {
        try {
            writer.shutdown();
        } catch (Throwable ignored) {
            // best-effort; daemon thread will die on JVM exit anyway
        }
    }
}
