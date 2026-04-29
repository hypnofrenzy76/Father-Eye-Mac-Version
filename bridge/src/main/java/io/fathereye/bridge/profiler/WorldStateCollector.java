package io.fathereye.bridge.profiler;

import io.fathereye.bridge.topic.ChunksSnapshot;
import io.fathereye.bridge.topic.MobsSnapshot;
import io.fathereye.bridge.topic.PlayersSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Walks the running server's player list, entity lists, and chunk providers
 * to build immutable snapshots. Called from the publisher thread; reads
 * shared collections that the server thread mutates.
 *
 * <p><b>Threading caveat</b>: in 1.16.5, the server's player list and the
 * world's entity lists are NOT generally thread-safe to enumerate off-tick.
 * The publisher runs at 1 Hz and the iteration is short, so transient
 * concurrent-modification races are tolerable for monitoring data — we
 * catch and swallow them. M9's Mixin profiler moves to the tick-thread
 * snapshot pattern for accuracy where it matters.
 */
public final class WorldStateCollector {

    private WorldStateCollector() {}

    public static PlayersSnapshot collectPlayers() {
        PlayersSnapshot snap = new PlayersSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return snap;

        try {
            for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
                PlayersSnapshot.PlayerEntry e = new PlayersSnapshot.PlayerEntry();
                e.uuid = p.getUUID().toString();
                e.name = p.getName().getString();
                e.dimensionId = p.level.dimension().location().toString();
                e.x = p.getX();
                e.y = p.getY();
                e.z = p.getZ();
                e.yaw = p.yRot;
                e.health = (int) p.getHealth();
                e.food = p.getFoodData() == null ? 20 : p.getFoodData().getFoodLevel();
                e.pingMs = p.latency;
                e.onlineSinceEpochMs = 0L; // populated by JoinTracker (M8 follow-up)
                e.gameMode = p.gameMode.getGameModeForPlayer().getName();
                snap.players.add(e);
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return snap;
    }

    public static MobsSnapshot collectMobs() {
        MobsSnapshot snap = new MobsSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return snap;

        for (ServerWorld world : server.getAllLevels()) {
            String dim = world.dimension().location().toString();
            Map<String, MobsSnapshot.ModEntityCounts> byMod = new LinkedHashMap<>();
            try {
                for (Entity e : world.getAllEntities()) {
                    String ns = namespaceOf(e.getType());
                    MobsSnapshot.ModEntityCounts c = byMod.computeIfAbsent(ns, k -> new MobsSnapshot.ModEntityCounts());
                    c.total++;
                    if (e instanceof IMob)               c.hostile++;
                    else if (e instanceof AnimalEntity)  c.passive++;
                    else if (e instanceof ItemEntity)    c.items++;
                }
            } catch (Throwable ignored) {}
            snap.byDim.put(dim, byMod);
        }
        return snap;
    }

    public static ChunksSnapshot collectChunks() {
        ChunksSnapshot snap = new ChunksSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return snap;

        for (ServerWorld world : server.getAllLevels()) {
            String dim = world.dimension().location().toString();
            ChunksSnapshot.DimChunks d = new ChunksSnapshot.DimChunks();
            try {
                d.loadedChunks = world.getChunkSource().getLoadedChunksCount();
                d.forcedChunks = world.getForcedChunks().size();
                d.playerChunks = (int) world.players().stream().count();
                // Brg-14 (2026-04-26): include the actual chunk
                // coordinate list so the panel's MapPane can fetch
                // every loaded chunk's tile, not just the visible
                // viewport. Capped at MAX_CHUNKS_REPORTED to bound
                // payload; closest-to-spawn-or-player chunks win on
                // overflow. Iterate ChunkMap entries: in 1.16.5
                // ServerChunkProvider exposes getLoadedChunks via
                // distanceManager / chunkMap; the safe accessor on
                // ServerChunkProvider is .chunkMap.getChunks() which
                // returns Iterable<ChunkHolder>. We use reflection
                // since the field name varies across mappings.
                java.util.List<long[]> chunkPositions = collectChunkPositions(world);
                if (!chunkPositions.isEmpty()) {
                    int n = Math.min(chunkPositions.size(), ChunksSnapshot.MAX_CHUNKS_REPORTED);
                    int[] flat = new int[n * 2];
                    for (int i = 0; i < n; i++) {
                        long[] cp = chunkPositions.get(i);
                        flat[i * 2] = (int) cp[0];
                        flat[i * 2 + 1] = (int) cp[1];
                    }
                    d.chunks = flat;
                }
            } catch (Throwable ignored) {}
            snap.byDim.put(dim, d);
        }
        return snap;
    }

    /**
     * Brg-14 (2026-04-26): enumerate every loaded chunk's (cx, cz)
     * for one ServerWorld. Uses Forge 1.16.5 official mappings:
     * {@code ServerWorld.getChunkSource()} returns a
     * {@code ServerChunkProvider}; its {@code chunkMap} field exposes
     * a {@code Long2ObjectLinkedOpenHashMap<ChunkHolder>} of loaded
     * chunks via {@code getChunks()}. ChunkPos packs the (cx,cz)
     * into a long; we unpack it.
     *
     * <p>Errors swallowed at the call site -- if the reflective
     * access fails on a mapping mismatch, the panel still gets a
     * loadedChunks count and falls back to viewport-only fetching.
     */
    /**
     * Brg-14 (audit fix): cache the reflective Method handle once at
     * class-load time. The previous code re-resolved getDeclaredMethod
     * + setAccessible on every 1 Hz collect, which wasted lookup
     * cycles and obscured per-call setAccessible failures behind a
     * shared catch.
     *
     * <p>Brg-15 (2026-04-27): switched from {@code Class.getDeclaredMethod}
     * to {@link net.minecraftforge.fml.common.ObfuscationReflectionHelper#findMethod}.
     * The previous lookup against the Mojang name "getChunks" failed
     * in production with NoSuchMethodException because Forge 1.16.5
     * reobfuscates Mojang method names to SRG names ("func_223491_f")
     * at the .class file level; at runtime the method's visible name
     * is the SRG one. ObfuscationReflectionHelper.findMethod takes
     * the SRG name and resolves it correctly in BOTH dev (Mojang
     * names) and production (SRG names) environments. Confirmed via
     * the server log: {@code ChunkManager.getChunks reflective
     * lookup failed} -- the user's chunks_topic list was always
     * empty, defeating Pnl-52 entirely.
     */
    private static final java.lang.reflect.Method CHUNK_MANAGER_GET_CHUNKS;
    /** Brg-14: one-shot warn flag so a mapping mismatch logs once
     *  rather than spamming at 1 Hz. */
    private static volatile boolean warnedGetChunks = false;
    static {
        java.lang.reflect.Method m = null;
        try {
            // SRG name for ChunkManager.getChunks() in 1.16.5; the
            // Mojang name is "getChunks", but at runtime the method
            // is named via the SRG namespace. Forge's helper picks
            // the right one for dev vs production automatically.
            m = net.minecraftforge.fml.common.ObfuscationReflectionHelper
                    .findMethod(net.minecraft.world.server.ChunkManager.class, "func_223491_f");
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger("FatherEye-WorldState")
                    .warn("ChunkManager.getChunks reflective lookup failed; chunks_topic coordinate list will be empty. {}",
                            t.toString());
        }
        CHUNK_MANAGER_GET_CHUNKS = m;
    }

    private static java.util.List<long[]> collectChunkPositions(ServerWorld world) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        if (CHUNK_MANAGER_GET_CHUNKS == null) return out;
        try {
            // ServerChunkProvider.chunkMap is a public field in 1.16.5
            // official mappings. ChunkManager.getChunks() is
            // `protected`; the cached Method handle above does the
            // setAccessible once at class-load. The returned
            // Iterable<ChunkHolder> is read-only at this point in the
            // tick (we are inside Publisher's WORLD_TICK hook on the
            // main thread), so iteration is safe.
            net.minecraft.world.server.ServerChunkProvider scp = world.getChunkSource();
            net.minecraft.world.server.ChunkManager cm = scp.chunkMap;
            @SuppressWarnings("unchecked")
            Iterable<net.minecraft.world.server.ChunkHolder> chunks =
                    (Iterable<net.minecraft.world.server.ChunkHolder>) CHUNK_MANAGER_GET_CHUNKS.invoke(cm);
            // Brg-22 (2026-04-27): filter holders down to chunks the
            // bridge can actually render in-memory (effective status >=
            // FULL). User: "map panel is stopping loading and does not
            // continue loading no matter how long i wait" + "map has
            // to slowly load until it gets to 100 %, it can never
            // hang". Root cause: ChunkManager.getChunks() returns
            // every holder regardless of ticket level, including
            // LOAD-level chunks (level 44) whose ChunkSection[] has
            // been freed. ChunkTileHandler can't render those
            // (Brg-21 sentinels them rather than producing all-black
            // tiles), so the panel's loading counter would sit
            // partially filled forever — every chunks_topic snapshot
            // re-added the unrenderable chunks, the bridge re-
            // sentinel-rejected, the panel removed from `requested`,
            // and the cycle repeated indefinitely without progress.
            //
            // Filter via getChunk(FULL, false): returns null for
            // chunks at level > FULL (LOAD), non-null for FULL+
            // (BORDER, ENTITY_TICKING, FULL_TICKING). Cost: one
            // method call per holder, ~µs each, ~4 ms for 4 000
            // holders. The panel's TileDiskCache preserves
            // previously-rendered tiles even after the chunk
            // downgrades to LOAD, so the user's map keeps
            // accumulating coverage as they explore.
            for (net.minecraft.world.server.ChunkHolder holder : chunks) {
                net.minecraft.util.math.ChunkPos pos = holder.getPos();
                net.minecraft.world.chunk.IChunk renderable;
                try {
                    renderable = scp.getChunk(pos.x, pos.z,
                            net.minecraft.world.chunk.ChunkStatus.FULL, false);
                } catch (Throwable ignored) { renderable = null; }
                if (renderable != null) {
                    out.add(new long[] { pos.x, pos.z });
                }
            }
        } catch (Throwable t) {
            // Mapping mismatch / chunk map mid-mutation. Log once so
            // the user can diagnose "panel never gets the chunk list"
            // but don't spam.
            if (!warnedGetChunks) {
                warnedGetChunks = true;
                org.apache.logging.log4j.LogManager.getLogger("FatherEye-WorldState")
                        .warn("ChunkManager.getChunks() failed at runtime; chunks_topic coordinate list will be empty. {}", t.toString());
            }
        }
        return out;
    }

    private static String namespaceOf(EntityType<?> type) {
        try {
            return type.getRegistryName() == null ? "unknown" : type.getRegistryName().getNamespace();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Compile-time witness that MobEntity is on the classpath for our hostile/passive checks. */
    @SuppressWarnings("unused")
    private static void touchMobEntity() { Class<?> c = MobEntity.class; }
}
