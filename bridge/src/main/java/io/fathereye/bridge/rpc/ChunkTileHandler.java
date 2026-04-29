package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.mapcore.api.ChunkTile;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds a {@link ChunkTile} from the live world for the requested
 * dimension and chunk coordinates. Returns the mapcore POJO so panel and
 * in-game map render from identical bytes.
 *
 * <p>Brg-16 (2026-04-27): server-side {@link
 * io.fathereye.bridge.profiler.ChunkSurfaceCache} fronts the world
 * read. The user reported "i started flying around and the chunks
 * stopped loading in-game" -- 256 block reads + 256 biome reads per
 * chunk, repeated for thousands of chunks per chunks_topic snapshot,
 * was starving Forge's chunk-gen task. With caching the world is
 * read at most once per chunk lifetime; subsequent RPCs return from
 * a hash lookup in microseconds. There's a per-tick miss budget so
 * even the FIRST scan can't lock up the server.
 */
public final class ChunkTileHandler {

    private static final Logger LOG = LogManager.getLogger("FatherEye-ChunkTile");

    /** Brg-19 (2026-04-27): the level/status mismatch fix.
     *  Previously the bridge tried `getChunkSource().getChunk(cx, cz,
     *  status, false)` for FULL, then HEIGHTMAPS, then SURFACE, and
     *  sentinel-rejected when all three returned null. That left a
     *  large class of chunks unrenderable forever: any chunk at
     *  ticket-level 44 (LOAD) maps to ChunkLevel.getStatus()=EMPTY,
     *  so even the SURFACE check returns null even though the
     *  chunk's data is fully populated in memory. Symptom: the
     *  user's panel froze at 42 % loaded with the diagnostic log
     *  showing newFull=0 newPartial=0 null=16/sec for hours.
     *  <p>Fix: when getChunk returns null at every status, fall
     *  through to the ChunkHolder directly via reflection. The
     *  holder's getLastAvailable() walks the per-status future
     *  list from FULL down and returns whatever IChunk is held,
     *  regardless of effective ticket level. This is the same
     *  IChunk that the chunk-saver uses to write the .mca region
     *  file, so the data is by definition complete enough to
     *  render. SRG names verified against
     *  C:\Users\lukeo\.gradle\caches\forge_gradle\mcp_repo\
     *  de\oceanlabs\mcp\mcp_config\1.16.5-20210115.111550\
     *  joined\rename\config\joined.tsrg:
     *  <ul>
     *    <li>ChunkManager.func_219219_b(long) -> ChunkHolder
     *        (getVisibleChunkIfPresent — visible map matches the
     *        same iteration WorldStateCollector uses for
     *        chunks_topic, so we always find the holder)</li>
     *    <li>ChunkHolder.func_219287_e() -> IChunk
     *        (getLastAvailable)</li>
     *  </ul>
     *  Both reflected via Forge's ObfuscationReflectionHelper so
     *  the same code works in dev (Mojang names) and prod (SRG
     *  names). */
    private static final java.lang.reflect.Method CHUNK_MANAGER_GET_VISIBLE;
    private static final java.lang.reflect.Method CHUNK_HOLDER_GET_LAST_AVAILABLE;
    private static volatile boolean warnedHolderLookup = false;
    static {
        java.lang.reflect.Method m1 = null;
        java.lang.reflect.Method m2 = null;
        try {
            m1 = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findMethod(
                    net.minecraft.world.server.ChunkManager.class, "func_219219_b", long.class);
        } catch (Throwable t) {
            LOG.warn("ChunkManager.getVisibleChunkIfPresent reflective lookup failed; LOAD-level chunks will sentinel forever. {}", t.toString());
        }
        try {
            m2 = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findMethod(
                    net.minecraft.world.server.ChunkHolder.class, "func_219287_e");
        } catch (Throwable t) {
            LOG.warn("ChunkHolder.getLastAvailable reflective lookup failed; LOAD-level chunks will sentinel forever. {}", t.toString());
        }
        CHUNK_MANAGER_GET_VISIBLE = m1;
        CHUNK_HOLDER_GET_LAST_AVAILABLE = m2;
    }

    private ChunkTileHandler() {}

    /** Brg-16: max number of cache misses (i.e. world reads) we'll
     *  service per tick. The bridge serves chunk_tile RPCs on the
     *  tick thread; an unbounded burst of misses (e.g. when a
     *  fresh panel connects to a busy server) was eating the entire
     *  tick budget. With this cap, the panel sees throttled empty
     *  responses for now-misses-this-tick and re-requests later;
     *  cache hits are unlimited because they're free.
     *  Brg-18 (2026-04-27): bumped 4 -> 16 after the user reported
     *  the map "hanging" at 32 % loaded. With 4/tick the steady-state
     *  fill rate is 80 chunks/sec.
     *  <p>Brg-20 (2026-04-27): bumped 16 -> 64 after the user
     *  reported "chunk loading is now progressing but is going very
     *  slowly". Combined with the per-render optimisation (hoist
     *  biome registry + container lookups out of the 256-pixel
     *  inner loop), each miss now takes ~0.3-0.5 ms instead of
     *  1-2 ms, so 64/tick is ~20-32 ms of tick time worst case —
     *  comparable headroom to the previous 16/tick at 1-2 ms each.
     *  Steady-state fill rate jumps from 320 to 1280 chunks/sec, so
     *  a fresh panel hitting a 3600-chunk world converges in ~3 s
     *  instead of ~11 s. */
    private static final int MAX_MISSES_PER_TICK = 64;
    /** Brg-17 (2026-04-27): build a fresh 256-zero sentinel ChunkTile per
     *  call. We can't satisfy ChunkTile's "biomes/heights/surfaceArgb must
     *  be length 256" preconditions with shared length-0 arrays (that was
     *  the Brg-16 attempt and it spammed the server console at 43k+
     *  entries/sec), and we can't share static length-256 zero arrays
     *  either: ChunkTile stores the array references directly without
     *  defensive copy, so any in-process consumer that mutated one would
     *  contaminate every future sentinel. Currently the panel only sees
     *  the bytes after Jackson serialisation, but in-VM tests or a future
     *  embedded panel mode would cross the boundary. Allocating ~1 KiB
     *  three times per cache miss is cheaper than the JSON encode that
     *  follows, and keeps the sentinel immutable by construction.
     *  The panel detects all-zero surfaceArgb in
     *  MapPane.onChunkTileResponse and skips persistence / tileImages.put
     *  for those tiles, so the disk-cache-poisoning bug Brg-16 was meant
     *  to fix stays fixed without crashing on the constructor check. */
    private static ChunkTile emptySentinel(String dim, int cx, int cz) {
        return new ChunkTile(dim, cx, cz, new byte[256], new short[256], new int[256]);
    }
    /** Brg-18 (2026-04-27): monotonic nanos for the tick-budget reset.
     *  The previous System.currentTimeMillis() was vulnerable to NTP
     *  push-back: if the wall clock jumped forward and then was
     *  pulled back, currentTickMs would sit in the future and the
     *  budget would never reset, sentinelling every RPC until the
     *  clock caught up. nanoTime is guaranteed non-decreasing
     *  within a JVM so the reset always fires at the next tick. */
    private static volatile long currentTickNanos = 0L;
    private static volatile int missesThisTick = 0;

    /** Brg-18 (2026-04-27): 1 Hz diagnostic counters so the user can
     *  see in the server log exactly why the map is or is not
     *  filling. Without this, "the map is stuck at 32 %" was
     *  un-debuggable: cache hit, real tile, sentinel-budget,
     *  sentinel-status, sentinel-null all looked the same to the
     *  panel side. */
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong realTiles = new AtomicLong(0);
    private static final AtomicLong fallbackTiles = new AtomicLong(0);
    private static final AtomicLong sentinelBudget = new AtomicLong(0);
    private static final AtomicLong sentinelChunkNull = new AtomicLong(0);
    private static volatile long lastSummaryNanos = 0L;

    public static Object handle(JsonNode args, MinecraftServer server) {
        String dim = args.path("dim").asText("minecraft:overworld");
        int cx = args.path("cx").asInt();
        int cz = args.path("cz").asInt();

        // Brg-16: cache check FIRST. A hit returns in microseconds;
        // we never touch the world or the chunk source.
        io.fathereye.bridge.profiler.ChunkSurfaceCache cache =
                io.fathereye.bridge.profiler.ChunkSurfaceCache.global();
        ChunkTile cached = cache.get(dim, cx, cz);
        if (cached != null) {
            cacheHits.incrementAndGet();
            maybeLogSummary();
            return cached;
        }

        // Brg-16: cache miss -- this is where it gets expensive.
        // Throttle compute work so a flood of misses can't starve
        // the tick thread. Reset the counter every 50ms (one tick).
        // Brg-18: monotonic nanoTime, immune to wall-clock jumps.
        long nowNanos = System.nanoTime();
        if (nowNanos - currentTickNanos >= 50_000_000L) {
            currentTickNanos = nowNanos;
            missesThisTick = 0;
        }
        if (missesThisTick >= MAX_MISSES_PER_TICK) {
            // Return all-zero sentinel; the panel's onChunkTileResponse
            // detects all-zero surfaceArgb and removes from `requested`
            // without persisting. Next chunks_topic snapshot will
            // re-add the request and we'll try again.
            sentinelBudget.incrementAndGet();
            maybeLogSummary();
            return emptySentinel(dim, cx, cz);
        }
        missesThisTick++;

        ServerWorld world = server.getLevel(RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(dim)));
        if (world == null) throw new IllegalArgumentException("unknown dim: " + dim);

        // Brg-13 (2026-04-26): NON-BLOCKING chunk lookup. Calling
        // world.getChunk(cx, cz) on the tick thread forces SYNCHRONOUS
        // chunk generation for unloaded chunks; with the panel firing 64
        // chunk_tile RPCs per redraw on a zoom-out, cumulative gen time
        // exceeded Vanilla's 60-second tick-watchdog and hard-crashed the
        // server (crash report 2026-04-26 01:40:52). Use load=false so
        // we never trigger generation here.
        //
        // Brg-18 (2026-04-27): try ChunkStatus.FULL first, fall back
        // to HEIGHTMAPS, then SURFACE. WorldStateCollector reports
        // every ChunkHolder via ChunkManager.getChunks() regardless
        // of status, so chunks_topic includes chunks at LOAD/SPAWN/
        // HEIGHTMAPS that getChunk(FULL, false) returns null for.
        // Without the fallback, those chunks sentinel forever and
        // the panel sees the map "hanging" at the FULL-only subset
        // (the user reported 32 %, i.e. only the FULL-status chunks
        // around spawn rendered, the rest never filling). Fallback
        // priority: FULL has guaranteed heightmap + features; if
        // missing, HEIGHTMAPS still has computed heightmap; SURFACE
        // has block placement done but heightmap may be primed on
        // the first getHeight() call (256 reads, same cost as
        // rendering itself).
        boolean fellBack = false;
        IChunk chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.HEIGHTMAPS, false);
            if (chunk != null) fellBack = true;
        }
        if (chunk == null) {
            chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.SURFACE, false);
            if (chunk != null) fellBack = true;
        }
        // Brg-19 (2026-04-27): all three status-filtered lookups
        // return null for chunks at ticket-level >= 44 (LOAD), even
        // if the chunk's stored status is FULL. This was the
        // remaining hang case after Brg-18: the user's panel sat at
        // 42 % loaded for hours with the diagnostic log showing
        // newFull=0 newPartial=0 null=16/sec. Reach into the
        // ChunkHolder directly and pull the stored IChunk via
        // getLastAvailable, which ignores the level-to-status
        // gating that ChunkSource.getChunk applies.
        if (chunk == null && CHUNK_MANAGER_GET_VISIBLE != null && CHUNK_HOLDER_GET_LAST_AVAILABLE != null) {
            try {
                net.minecraft.world.server.ChunkManager cm = world.getChunkSource().chunkMap;
                long chunkKey = net.minecraft.util.math.ChunkPos.asLong(cx, cz);
                Object holder = CHUNK_MANAGER_GET_VISIBLE.invoke(cm, chunkKey);
                if (holder != null) {
                    Object obj = CHUNK_HOLDER_GET_LAST_AVAILABLE.invoke(holder);
                    if (obj instanceof IChunk) {
                        chunk = (IChunk) obj;
                        fellBack = true;
                    }
                }
            } catch (Throwable t) {
                // Brg-19 (audit fix): pass the Throwable as the last
                // SLF4J argument so log4j prints the full stack trace.
                // The previous t.toString() lost the failing frame,
                // which would have been needed to diagnose any future
                // SRG-name drift on a different MC version.
                if (!warnedHolderLookup) {
                    warnedHolderLookup = true;
                    LOG.warn("Holder-level chunk fallback failed; LOAD-level chunks will sentinel.", t);
                }
            }
        }
        if (chunk == null) {
            // Truly not loaded at any status; return sentinel.
            sentinelChunkNull.incrementAndGet();
            maybeLogSummary();
            return emptySentinel(dim, cx, cz);
        }

        byte[] biomes = new byte[256];
        short[] heights = new short[256];
        int[] surfaceArgb = new int[256];

        // Brg-20 (2026-04-27): hoist registry + biome-container lookups
        // out of the 256-iteration inner loop. The previous code called
        // `world.registryAccess().registryOrThrow(BIOME_REGISTRY)` and
        // `world.getBiome(pos)` per pixel; that's 512 expensive lookups
        // per chunk render where 1 + 256 cheap container reads suffice.
        // The chunk-local BiomeContainer is sampled with a 4x4x4 grid;
        // calling world.getBiome triggered an 8-corner noise blend per
        // pixel, which is wasted work on a flat 2 D map view. The
        // registry handle is invariant per dim and per registry-reload,
        // so reading it once per chunk is safe.
        net.minecraft.util.registry.Registry<Biome> biomeReg = null;
        try {
            biomeReg = world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        } catch (Throwable ignored) { /* fall back to per-pixel lookup */ }
        // Brg-23 (2026-04-27): guard chunk.getBiomes() with try/catch.
        // The Brg-19 holder fallback returns whatever IChunk is held,
        // including ProtoChunks during early gen where getBiomes()
        // can throw or return null. Without this guard the whole
        // handler aborted, the panel never got a response, and the
        // chunk stayed in `requested` forever.
        net.minecraft.world.biome.BiomeContainer biomeContainer;
        try {
            biomeContainer = chunk.getBiomes();
        } catch (Throwable ignored) {
            biomeContainer = null;
        }

        // Brg-21 (2026-04-27): track whether this render produced any
        // real surface data. The user reported black-rectangle
        // artefacts on the panel after Brg-19's holder-level fallback
        // started returning IChunks for LOAD-ticket-level chunks
        // whose in-memory section storage had been freed (the holder
        // ref survives but the underlying ChunkSection[] is
        // empty/null, so every getBlockState returns AIR =
        // MaterialColor.NONE = surfaceArgb 0xFF000000). The Pnl-56
        // all-zero filter at the panel checks `== 0`, not
        // `== 0xFF000000`, so the all-black tile passed and got
        // persisted to disk. Detect server-side: count pixels that
        // produced a usable, non-NONE colour. If ZERO, the chunk is
        // unrenderable in-memory and should sentinel like a missing
        // chunk; the panel's TileDiskCache keeps the previously-
        // rendered version alive across the LOAD downgrade.
        int nonAirPixels = 0;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int idx = dz * 16 + dx;
                int worldX = cx * 16 + dx;
                int worldZ = cz * 16 + dz;
                // Brg-23 (2026-04-27): wrap the per-pixel lookups in
                // try/catch so a single bad pixel can't abort the
                // whole render. Brg-19's holder fallback returns
                // chunks at any status; if the heightmap is null,
                // chunk.getHeight throws NPE; if ChunkSection[] is
                // null, getBlockState throws; if a modded
                // getMapColor recurses into a partially-loaded
                // neighbour, that throws too. The previous code
                // only caught getMapColor; the other two threw and
                // aborted the handler. Result: the future
                // completed exceptionally, IpcSession tried to
                // sendResponse with the error info, sendResponse
                // sometimes failed too (pipe back-pressure), the
                // IOException was swallowed at IpcSession.java:122,
                // and the panel's `requested` entry leaked forever.
                int y;
                BlockState state;
                try {
                    y = chunk.getHeight(Heightmap.Type.WORLD_SURFACE, dx, dz);
                    pos.set(worldX, Math.max(0, y - 1), worldZ);
                    state = chunk.getBlockState(pos);
                } catch (Throwable t) {
                    // Couldn't read this pixel; treat as AIR.
                    heights[idx] = 0;
                    surfaceArgb[idx] = 0xFF000000;
                    biomes[idx] = 0;
                    continue;
                }
                heights[idx] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
                MaterialColor color;
                try {
                    color = state.getMapColor(world, pos);
                } catch (Throwable t) {
                    color = MaterialColor.NONE;
                }
                int rgb = color == null ? 0x202020 : (color.col & 0xFFFFFF);
                surfaceArgb[idx] = 0xFF000000 | rgb;
                // Brg-21: NONE = vanilla material for AIR. col == 0.
                // A real surface tile has at least one non-AIR pixel
                // (water counts: WATER colour col != 0). If every
                // pixel is NONE, the chunk has no usable data.
                if (color != null && color != MaterialColor.NONE && color.col != 0) {
                    nonAirPixels++;
                }

                // Biome id: in 1.16.5 Biome.getRegistryName() always returns null
                // (Biome is vanilla-Registry-managed, not a ForgeRegistryEntry).
                // Brg-20: read the chunk's BiomeContainer directly (4x4x4
                // grid sampled by world coords; 1 array dereference per
                // pixel) instead of world.getBiome which does an 8-corner
                // BiomeManager blend. The bId we ship is a stable hash;
                // mapcore v0.3.0 will render biome overlays from a
                // separate palette so the exact biome value isn't
                // visible to the user yet.
                try {
                    Biome biome = biomeContainer == null
                            ? world.getBiome(pos)
                            : biomeContainer.getNoiseBiome(worldX >> 2, y >> 2, worldZ >> 2);
                    int bId = 0;
                    if (biome != null && biomeReg != null) {
                        ResourceLocation biomeId = biomeReg.getKey(biome);
                        bId = biomeId == null ? 0 : Math.abs(biomeId.toString().hashCode()) & 0xFF;
                    }
                    biomes[idx] = (byte) bId;
                } catch (Throwable t) {
                    biomes[idx] = 0;
                }
            }
        }

        // Brg-21 (2026-04-27): if the render produced zero usable
        // surface pixels, treat as sentinel. The chunk's holder is
        // in chunkMap (so chunks_topic listed it) but its in-memory
        // sections are empty — typical for chunks at LOAD ticket-
        // level whose ChunkSection[] was freed under memory
        // pressure. Without this guard the panel was persisting all-
        // 0xFF000000 tiles indefinitely, producing black rectangles
        // around the actually-rendered area.
        if (nonAirPixels == 0) {
            sentinelChunkNull.incrementAndGet();
            maybeLogSummary();
            return emptySentinel(dim, cx, cz);
        }

        ChunkTile tile = new ChunkTile(dim, cx, cz, biomes, heights, surfaceArgb);
        // Brg-16: cache the freshly-computed tile so subsequent
        // RPCs for the same chunk return without re-scanning the
        // world. The cache is per-dim LRU; entries evict only when
        // the cache is over the (large) cap.
        // Brg-18: don't cache fallback (non-FULL) tiles. They might
        // have a partial heightmap, so when the chunk later promotes
        // to FULL with features (trees, ores), we want to re-render.
        // FULL tiles are stable forever once cached.
        if (!fellBack) {
            cache.put(tile);
            realTiles.incrementAndGet();
        } else {
            fallbackTiles.incrementAndGet();
        }
        maybeLogSummary();
        return tile;
    }

    /**
     * Brg-18: emit a one-line summary every minute so the user can
     * verify chunk_tile throughput from the server log without
     * needing the panel-side debug log. Format:
     * <pre>
     *   ChunkTile 60s: hits=H newFull=F newPartial=P sentinel(budget=B,null=N)
     * </pre>
     * Brg-19 (2026-04-27): cadence stretched 1 s -> 60 s and level
     * dropped INFO -> DEBUG after the user reported "console is
     * being spammed by fathereye every second and i do not want
     * this". The panel's logback config keeps DEBUG out of the
     * in-app console by default; users who need the diagnostic
     * can drop the FatherEye-ChunkTile logger to DEBUG in the
     * panel logback config without touching the bridge.
     */
    private static void maybeLogSummary() {
        // Brg-19 (audit fix): bail out BEFORE touching the counters
        // when DEBUG is disabled. The previous version called
        // getAndSet(0) unconditionally, which zeroed the running
        // tallies every 60 s in production (where logback root is
        // INFO) and made any later flip to DEBUG report only the
        // window since the last reset rather than the cumulative
        // health of the run.
        if (!LOG.isDebugEnabled()) return;
        long now = System.nanoTime();
        long last = lastSummaryNanos;
        if (now - last < 60_000_000_000L) return;
        // Race: two callers may both pass the gate. Whichever wins
        // the CAS prints; the loser falls through. Idempotent for
        // the user even if it occasionally double-prints (rare).
        if (!compareAndSetLastSummary(last, now)) return;
        long h = cacheHits.getAndSet(0);
        long f = realTiles.getAndSet(0);
        long p = fallbackTiles.getAndSet(0);
        long b = sentinelBudget.getAndSet(0);
        long n = sentinelChunkNull.getAndSet(0);
        if (h + f + p + b + n > 0) {
            LOG.debug("ChunkTile 60s: hits={} newFull={} newPartial={} sentinel(budget={},null={})",
                    h, f, p, b, n);
        }
    }

    private static boolean compareAndSetLastSummary(long expect, long update) {
        // Cheap synchronized CAS over a volatile long. Avoids
        // pulling in VarHandle (Java 9+) so we stay Java-8 clean
        // even though bridge runs on JDK 17.
        synchronized (ChunkTileHandler.class) {
            if (lastSummaryNanos != expect) return false;
            lastSummaryNanos = update;
            return true;
        }
    }
}
