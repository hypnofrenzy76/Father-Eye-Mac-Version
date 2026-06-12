package io.fathereye.bridge.profiler;

import io.fathereye.bridge.topic.ChunksSnapshot;
import io.fathereye.bridge.topic.MobsSnapshot;
import io.fathereye.bridge.topic.PlayersSnapshot;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Brg-24 (2026-06-12): the tick-thread state mirror. This is the heart
 * of the "map shows 100% of loaded chunks with near-zero tick impact"
 * rework.
 *
 * <p>Why: spark profile IzzU6Fus2J showed 32.7% of the entire server
 * thread parked inside ServerChunkProvider.getChunk. Root cause: the
 * Publisher thread called {@code scp.getChunk(FULL,false)} once per
 * ChunkHolder per second (Brg-22's renderable filter). Off the tick
 * thread, vanilla marshals EVERY such call onto the main thread via
 * {@code CompletableFuture.supplyAsync(..).join()}, and for in-flight
 * chunks the main-thread execution then blocks in managedBlock until
 * generation completes. Thousands of holders x 1 Hz x 9 dims = the
 * 300-800 ms tick spikes the user saw.
 *
 * <p>The fix inverts the data flow: everything that needs the world is
 * computed HERE, on the tick thread, under explicit budgets; everything
 * the IPC/Publisher threads need is read from immutable volatile
 * snapshots or the thread-safe {@link ChunkSurfaceCache}. Specifically:
 * <ul>
 *   <li><b>players/mobs/chunks topics</b>: rebuilt once per second on
 *       the tick thread (cheap, bounded) and stored in volatile fields;
 *       {@link io.fathereye.bridge.ipc.Publisher} just serialises the
 *       latest snapshot. No more off-thread entity/holder iteration.</li>
 *   <li><b>tile pre-rendering</b>: a budgeted queue (max
 *       {@value #MAX_RENDERS_PER_TICK}/tick AND
 *       {@code RENDER_BUDGET_NANOS} per tick, whichever ends first,
 *       with an MSPT back-off) renders loaded chunks into
 *       ChunkSurfaceCache. ChunkEvent.Load feeds it incrementally; the
 *       1 Hz chunk sweep catches anything missed.</li>
 *   <li><b>chunk_tile RPC</b>: served entirely from the cache on the
 *       IPC thread ({@link io.fathereye.bridge.rpc.RpcDispatcher}
 *       direct dispatch); a miss enqueues a priority render here and
 *       returns a sentinel the panel already knows to retry.</li>
 *   <li><b>freshness</b>: tiles carry a monotonically increasing
 *       {@code version} (assigned by
 *       {@link ChunkSurfaceCache#putVersioned}); chunks_topic ships
 *       per-chunk versions so the panel re-requests only tiles whose
 *       content actually changed. Non-FULL tiles and chunks around
 *       players are re-rendered by a slow rotation so terrain edits
 *       surface within ~30 s without any block-event bookkeeping.</li>
 * </ul>
 *
 * <p>Worst-case tick cost: ~1.5 ms while a fresh panel fills its map,
 * 0 ms steady-state (queues empty, snapshot rebuild ~0.3 ms once per
 * second, skipped entirely when the previous tick ran long).
 */
public final class TickStateMirror {

    private static final Logger LOG = LogManager.getLogger("FatherEye-TickMirror");

    private static volatile TickStateMirror ACTIVE;

    /** Per-tick render budget. 1.5 ms keeps even initial panel fill
     *  invisible next to a 50 ms tick; with ~0.3-0.5 ms per render the
     *  effective fill rate is 60-100 chunks/s, and the count cap below
     *  bounds the degenerate all-cache-hit case. */
    private static final long RENDER_BUDGET_NANOS = 1_500_000L;
    private static final int MAX_RENDERS_PER_TICK = 32;
    /** Back-off: if the previous tick exceeded this, skip ALL mirror
     *  work for the current tick (render queue and snapshots can wait;
     *  a struggling server must never pay for the map). */
    private static final double BACKOFF_MSPT = 45.0;
    private static final int SNAPSHOT_PERIOD_TICKS = 20;       // 1 s
    private static final int REFRESH_PERIOD_TICKS = 20 * 30;   // 30 s
    private static final int PLAYER_REFRESH_RADIUS = 2;        // 5x5 chunks

    private volatile PlayersSnapshot players;
    private volatile MobsSnapshot mobs;
    private volatile ChunksSnapshot chunks;

    /** RPC-missed chunks jump the queue so an interactively panning
     *  panel fills what the user is looking at first. */
    private final Queue<QueuedChunk> priorityQueue = new ConcurrentLinkedQueue<>();
    private final Queue<QueuedChunk> fillQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> pendingByKey = ConcurrentHashMap.newKeySet();
    /** Tiles rendered from non-FULL chunks; re-attempted by the
     *  refresh rotation until a FULL render sticks. */
    private final Map<Long, QueuedChunk> partialTiles = new ConcurrentHashMap<>();
    /** Chunks whose render returned null (sections freed / all-air);
     *  excluded from the 1 Hz version-0 re-enqueue so they don't burn
     *  render budget every second. Cleared by the 30 s refresh
     *  rotation so promotions are still picked up. */
    private final Set<Long> unrenderable = ConcurrentHashMap.newKeySet();

    private long tickCounter;

    private static final class QueuedChunk {
        final String dim;
        final int cx;
        final int cz;
        final long key;
        QueuedChunk(String dim, int cx, int cz) {
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.key = mixKey(dim, cx, cz);
        }
    }

    /** Dedupe key: dim hash folded with packed chunk coords. A cross-dim
     *  collision merely dedupes one extra enqueue; harmless. */
    private static long mixKey(String dim, int cx, int cz) {
        long packed = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        return packed * 31 + dim.hashCode();
    }

    private TickStateMirror() {}

    public static TickStateMirror install() {
        TickStateMirror m = new TickStateMirror();
        MinecraftForge.EVENT_BUS.register(m);
        ACTIVE = m;
        LOG.info("TickStateMirror installed (renderBudget={}us/tick, maxRenders={}/tick, backoff>{}ms)",
                RENDER_BUDGET_NANOS / 1000, MAX_RENDERS_PER_TICK, (int) BACKOFF_MSPT);
        return m;
    }

    public void uninstall() {
        MinecraftForge.EVENT_BUS.unregister(this);
        if (ACTIVE == this) ACTIVE = null;
    }

    // ----- cross-thread read API ---------------------------------------

    public static PlayersSnapshot playersSnapshot() {
        TickStateMirror m = ACTIVE;
        return m == null ? null : m.players;
    }

    public static MobsSnapshot mobsSnapshot() {
        TickStateMirror m = ACTIVE;
        return m == null ? null : m.mobs;
    }

    public static ChunksSnapshot chunksSnapshot() {
        TickStateMirror m = ACTIVE;
        return m == null ? null : m.chunks;
    }

    /** Called from the IPC thread on a chunk_tile cache miss.
     *  Brg-26: known-unrenderable chunks are NOT re-enqueued. Before
     *  this guard, the panel's 5 s rejected-retry loop re-requested
     *  every unrenderable chunk forever (789 chunks on the user's
     *  world), each retry re-rendering a null tile on the tick thread
     *  and saturating the 1.5 ms/tick budget indefinitely. The 30 s
     *  refresh rotation clears the set, so promotions still render. */
    public static void requestRender(String dim, int cx, int cz) {
        TickStateMirror m = ACTIVE;
        if (m == null) return;
        if (m.unrenderable.contains(mixKey(dim, cx, cz))) return;
        m.enqueue(m.priorityQueue, dim, cx, cz);
    }

    // ----- tick-thread work --------------------------------------------

    private void enqueue(Queue<QueuedChunk> q, String dim, int cx, int cz) {
        QueuedChunk qc = new QueuedChunk(dim, cx, cz);
        if (pendingByKey.add(qc.key)) q.add(qc);
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getWorld() instanceof ServerWorld)) return;
        ServerWorld world = (ServerWorld) event.getWorld();
        enqueue(fillQueue, world.dimension().location().toString(),
                event.getChunk().getPos().x, event.getChunk().getPos().z);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        tickCounter++;
        if (TpsCollector.lastTickMs() > BACKOFF_MSPT) return;
        try {
            drainRenderQueues(server);
            if (tickCounter % SNAPSHOT_PERIOD_TICKS == 0) rebuildSnapshots(server);
            if (tickCounter % REFRESH_PERIOD_TICKS == 0) scheduleRefresh(server);
        } catch (Throwable t) {
            LOG.warn("mirror tick failed: {}", t.toString());
        }
    }

    private void drainRenderQueues(MinecraftServer server) {
        long deadline = System.nanoTime() + RENDER_BUDGET_NANOS;
        int rendered = 0;
        ChunkSurfaceCache cache = ChunkSurfaceCache.global();
        while (rendered < MAX_RENDERS_PER_TICK && System.nanoTime() < deadline) {
            QueuedChunk qc = priorityQueue.poll();
            if (qc == null) qc = fillQueue.poll();
            if (qc == null) break;
            pendingByKey.remove(qc.key);
            ServerWorld world = worldFor(server, qc.dim);
            if (world == null) continue;
            rendered++;
            ChunkTileRenderer.Result r = ChunkTileRenderer.render(world, qc.cx, qc.cz);
            if (r == null) {
                partialTiles.remove(qc.key);
                unrenderable.add(qc.key);
                continue;
            }
            unrenderable.remove(qc.key);
            cache.putVersioned(r.tile);
            if (r.full) partialTiles.remove(qc.key);
            else partialTiles.put(qc.key, qc);
        }
    }

    private void rebuildSnapshots(MinecraftServer server) {
        // WorldStateCollector logic now runs HERE, on the tick thread,
        // where the player list / entity lists are safe to iterate.
        players = WorldStateCollector.collectPlayers();
        mobs = WorldStateCollector.collectMobs();
        chunks = buildChunksSnapshot(server);
    }

    private ChunksSnapshot buildChunksSnapshot(MinecraftServer server) {
        ChunksSnapshot snap = new ChunksSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        ChunkSurfaceCache cache = ChunkSurfaceCache.global();
        for (ServerWorld world : server.getAllLevels()) {
            String dim = world.dimension().location().toString();
            ChunksSnapshot.DimChunks d = new ChunksSnapshot.DimChunks();
            try {
                d.loadedChunks = world.getChunkSource().getLoadedChunksCount();
                d.forcedChunks = world.getForcedChunks().size();
                d.playerChunks = world.players().size();
                int cap = ChunksSnapshot.MAX_CHUNKS_REPORTED;
                int[] flat = new int[Math.min(cap, Math.max(16, d.loadedChunks)) * 2];
                int[] vers = new int[flat.length / 2];
                int n = 0;
                for (ChunkHolder holder : ChunkTileRenderer.loadedHolders(world)) {
                    if (n >= cap) break;
                    IChunk c = ChunkTileRenderer.lastAvailable(holder);
                    if (c == null) continue;
                    int cx = holder.getPos().x;
                    int cz = holder.getPos().z;
                    if (n * 2 + 1 >= flat.length) {
                        flat = java.util.Arrays.copyOf(flat, Math.min(cap, n * 2) * 2 + 32);
                        vers = java.util.Arrays.copyOf(vers, flat.length / 2);
                    }
                    flat[n * 2] = cx;
                    flat[n * 2 + 1] = cz;
                    long v = cache.peekVersion(dim, cx, cz);
                    boolean unr = unrenderable.contains(mixKey(dim, cx, cz));
                    // Brg-26: version -1 tells the panel "this holder has
                    // no renderable content right now" so it counts the
                    // chunk as handled (Pnl-71) instead of retrying every
                    // 5 s. 0 keeps its "not rendered yet" meaning.
                    // Deliberate: an unrenderable chunk whose cache still
                    // holds an older tile (v > 0) reports that REAL
                    // version, so a fresh panel session still fetches the
                    // cached tile (pure cache hit, no tick-thread work)
                    // instead of leaving a permanent hole.
                    vers[n] = unr && v == 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, v);
                    if (v == 0 && !unr) {
                        enqueue(fillQueue, dim, cx, cz);
                    }
                    n++;
                }
                d.chunks = java.util.Arrays.copyOf(flat, n * 2);
                d.chunkVersions = java.util.Arrays.copyOf(vers, n);
            } catch (Throwable ignored) {}
            snap.byDim.put(dim, d);
        }
        return snap;
    }

    /** Every 30 s: retry partial tiles (they may have promoted to FULL)
     *  and re-render the chunks around each player so terrain edits
     *  show up without per-block-event bookkeeping. putVersioned only
     *  bumps the version when content actually changed, so unchanged
     *  chunks cost one render and zero panel traffic. */
    private void scheduleRefresh(MinecraftServer server) {
        unrenderable.clear();
        for (QueuedChunk qc : partialTiles.values()) {
            if (pendingByKey.add(qc.key)) fillQueue.add(qc);
        }
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
            String dim = p.level.dimension().location().toString();
            int pcx = ((int) Math.floor(p.getX())) >> 4;
            int pcz = ((int) Math.floor(p.getZ())) >> 4;
            for (int dx = -PLAYER_REFRESH_RADIUS; dx <= PLAYER_REFRESH_RADIUS; dx++) {
                for (int dz = -PLAYER_REFRESH_RADIUS; dz <= PLAYER_REFRESH_RADIUS; dz++) {
                    enqueue(fillQueue, dim, pcx + dx, pcz + dz);
                }
            }
        }
    }

    private static ServerWorld worldFor(MinecraftServer server, String dim) {
        try {
            return server.getLevel(RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(dim)));
        } catch (Throwable t) {
            return null;
        }
    }
}
