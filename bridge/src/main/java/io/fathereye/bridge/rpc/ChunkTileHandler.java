package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.bridge.profiler.ChunkSurfaceCache;
import io.fathereye.bridge.profiler.DiskChunkRenderer;
import io.fathereye.bridge.profiler.TickStateMirror;
import io.fathereye.mapcore.api.ChunkTile;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves chunk_tile RPCs. Returns the mapcore {@link ChunkTile} POJO so
 * panel and in-game map render from identical bytes.
 *
 * <p>Brg-24 (2026-06-12): this handler no longer touches the world AT
 * ALL. The spark profile IzzU6Fus2J showed the previous design (render
 * on the tick thread inside the RPC, budgeted at 64 misses/tick) plus
 * WorldStateCollector's off-thread getChunk filter were together
 * responsible for ~32.7% of server thread time. The new data flow:
 *
 * <pre>
 *   tick thread:  TickStateMirror -> ChunkTileRenderer -> ChunkSurfaceCache
 *   IPC thread:   chunk_tile RPC  -> ChunkSurfaceCache (hit: tile, miss:
 *                 sentinel + TickStateMirror.requestRender enqueue)
 * </pre>
 *
 * Because this handler only reads the thread-safe cache, it is
 * registered with {@link RpcDispatcher#registerDirect} and runs on the
 * IPC thread: a chunk_tile burst from a freshly-connected panel now
 * costs the tick thread NOTHING beyond the mirror's fixed 1.5 ms/tick
 * render budget. Cache hits return in microseconds; misses return the
 * all-zero sentinel the panel already retries on (Brg-17 semantics
 * unchanged), and the requested chunk jumps the mirror's priority
 * queue so what the user is looking at fills first.
 *
 * <p>History: Brg-13 non-blocking lookup, Brg-16 cache, Brg-17 sentinel
 * allocation, Brg-18 budget bump + diagnostics, Brg-19 holder fallback,
 * Brg-20 hoisted lookups, Brg-21 black-tile rejection, Brg-23 per-pixel
 * guards. The render-side logic (Brg-19/20/21/23) lives on unchanged in
 * {@link io.fathereye.bridge.profiler.ChunkTileRenderer}.
 */
public final class ChunkTileHandler {

    private static final Logger LOG = LogManager.getLogger("FatherEye-ChunkTile");

    private ChunkTileHandler() {}

    /** Brg-17: fresh 256-zero sentinel per call — ChunkTile keeps the
     *  array references without defensive copy, so shared static
     *  arrays would be mutable cross-consumer. ~1 KiB per miss is
     *  cheaper than the JSON encode that follows. The panel detects
     *  all-zero surfaceArgb and retries on the next chunks_topic
     *  snapshot without persisting. */
    private static ChunkTile emptySentinel(String dim, int cx, int cz) {
        return new ChunkTile(dim, cx, cz, new byte[256], new short[256], new int[256]);
    }

    /** Brg-18 diagnostics, kept: 60 s DEBUG summary of hit/miss flow. */
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong missEnqueued = new AtomicLong(0);
    private static volatile long lastSummaryNanos = 0L;

    /**
     * Cache-only lookup; safe on any thread. Registered DIRECT (IPC
     * thread) — never schedule this onto the tick thread.
     */
    public static Object handle(JsonNode args, MinecraftServer server) {
        String dim = args.path("dim").asText("minecraft:overworld");
        int cx = args.path("cx").asInt();
        int cz = args.path("cz").asInt();

        ChunkTile cached = ChunkSurfaceCache.global().get(dim, cx, cz);
        if (cached != null) {
            cacheHits.incrementAndGet();
            maybeLogSummary();
            return cached;
        }

        // Miss: hand the chunk to the tick-thread mirror's priority
        // queue so a live (loaded) chunk fills with up-to-date pixels.
        // The mirror dedupes, so request floods for the same chunk cost
        // one queue entry.
        TickStateMirror.requestRender(dim, cx, cz);
        missEnqueued.incrementAndGet();
        maybeLogSummary();

        // Map-02 / Brg-29: disk fallback for pre-generated chunks that
        // are NOT resident in the live world (e.g. a Chunky pre-gen of
        // millions of chunks). Reading the .mca off the IPC thread costs
        // the tick thread nothing. If disk has a usable tile, cache it
        // (so subsequent requests are microsecond cache hits) and return
        // it immediately instead of the "not yet" sentinel. A live render
        // for the same chunk, if it ever loads, will overwrite the cache
        // with fresher pixels via TickStateMirror.putVersioned.
        if (server != null) {
            try {
                ChunkTile disk = DiskChunkRenderer.renderFromDisk(server, dim, cx, cz);
                if (disk != null) {
                    ChunkSurfaceCache.global().put(disk);
                    return disk;
                }
            } catch (Throwable t) {
                // Degrade gracefully: a mapping/IO failure just falls
                // back to the sentinel + live-render path.
            }
        }
        return emptySentinel(dim, cx, cz);
    }

    /** Brg-18/19: 60 s DEBUG cadence, counters untouched unless DEBUG
     *  is enabled (audit fix preserved). */
    private static void maybeLogSummary() {
        if (!LOG.isDebugEnabled()) return;
        long now = System.nanoTime();
        long last = lastSummaryNanos;
        if (now - last < 60_000_000_000L) return;
        if (!compareAndSetLastSummary(last, now)) return;
        long h = cacheHits.getAndSet(0);
        long m = missEnqueued.getAndSet(0);
        if (h + m > 0) {
            LOG.debug("ChunkTile 60s: hits={} missEnqueued={} (cache size={} hits={} misses={} evictions={})",
                    h, m,
                    ChunkSurfaceCache.global().size(),
                    ChunkSurfaceCache.global().hits(),
                    ChunkSurfaceCache.global().misses(),
                    ChunkSurfaceCache.global().evictions());
        }
    }

    private static boolean compareAndSetLastSummary(long expect, long update) {
        synchronized (ChunkTileHandler.class) {
            if (lastSummaryNanos != expect) return false;
            lastSummaryNanos = update;
            return true;
        }
    }
}
