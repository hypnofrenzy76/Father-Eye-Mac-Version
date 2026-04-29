package io.fathereye.bridge.profiler;

import io.fathereye.mapcore.api.ChunkTile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Brg-16 (2026-04-27): server-side LRU cache of chunk surface
 * tiles, indexed by (dim, cx, cz).
 *
 * <p>Why: the user reported "i started flying around and the chunks
 * stopped loading in-game and they stopped loading on the map.
 * approach for the map loading needs to have no impact on server
 * performance and still load all loaded chunks on map".
 *
 * <p>Root cause: {@link io.fathereye.bridge.rpc.ChunkTileHandler#handle}
 * reads 256 block states + 256 biomes from the live world for every
 * chunk_tile RPC, all on the server tick thread. With Pnl-52's
 * chunks_topic reporting every loaded chunk and the panel requesting
 * a tile for each, a 4000-chunk modpack server got hit with ~1M block
 * reads per second on the tick thread, starving Forge's chunk-gen
 * task and causing "chunks stopped loading" mid-flight.
 *
 * <p>Fix: cache the per-chunk ARGB after first compute. Cache hits
 * return in microseconds. Cache misses are bounded by Forge's normal
 * chunk-load cadence (one compute per chunk per panel session). The
 * panel's disk cache (Pnl-53 TileDiskCache) provides cross-session
 * persistence.
 *
 * <p>Memory bound: {@link #DEFAULT_MAX_ENTRIES} = 16384 chunks per
 * dim, ~1 KB per tile = 16 MB per dim worst case. LRU evicts the
 * least-recently-accessed when full. Per-dim sub-maps so eviction
 * within one dim doesn't penalise others.
 */
public final class ChunkSurfaceCache {

    public static final int DEFAULT_MAX_ENTRIES = 16384;

    private static final ChunkSurfaceCache GLOBAL = new ChunkSurfaceCache(DEFAULT_MAX_ENTRIES);
    public static ChunkSurfaceCache global() { return GLOBAL; }

    private final int maxEntries;
    /** dim -> LRU<chunkKey, tile>. Outer concurrent so dim creation
     *  is lock-free; inner LinkedHashMap synchronised for thread-
     *  safe access-order updates. */
    private final Map<String, LinkedHashMap<Long, ChunkTile>> byDim = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public ChunkSurfaceCache(int maxEntries) {
        this.maxEntries = Math.max(64, maxEntries);
    }

    /**
     * Cache key for a chunk position. Packs (cx, cz) as 32-bit each
     * into a long. Different dims live in different sub-maps so the
     * dim isn't part of the key.
     */
    private static long packKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private LinkedHashMap<Long, ChunkTile> dimMap(String dim) {
        return byDim.computeIfAbsent(dim, k -> new LinkedHashMap<Long, ChunkTile>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkTile> eldest) {
                if (size() > maxEntries) {
                    evictions.incrementAndGet();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Look up a cached tile. Returns null on miss. Updates LRU
     * recency on hit so frequently-touched chunks stay resident.
     */
    public ChunkTile get(String dim, int cx, int cz) {
        LinkedHashMap<Long, ChunkTile> m = byDim.get(dim);
        if (m == null) {
            misses.incrementAndGet();
            return null;
        }
        ChunkTile t;
        synchronized (m) {
            t = m.get(packKey(cx, cz));
        }
        if (t == null) misses.incrementAndGet();
        else hits.incrementAndGet();
        return t;
    }

    /**
     * Store a freshly-computed tile. Evicts the LRU if the per-dim
     * cache is over the size cap. Idempotent overwrite.
     */
    public void put(ChunkTile tile) {
        if (tile == null || tile.dimensionId == null) return;
        // Brg-16 (audit fix): never cache "empty" sentinel tiles.
        // ChunkTileHandler returns zero-length arrays for
        // not-loaded / over-budget responses; caching those would
        // poison the cache and the panel's disk cache for the
        // chunk's lifetime. Real tiles always have 256-element
        // surfaceArgb (16 x 16 surface).
        if (tile.surfaceArgb == null || tile.surfaceArgb.length != 256) return;
        LinkedHashMap<Long, ChunkTile> m = dimMap(tile.dimensionId);
        synchronized (m) {
            m.put(packKey(tile.chunkX, tile.chunkZ), tile);
        }
    }

    /**
     * Remove a cached tile (e.g. when terrain changes invalidate
     * it). Currently unused but exposed for future
     * ChunkEvent.Unload + ChunkChangedEvent integration.
     */
    public void invalidate(String dim, int cx, int cz) {
        LinkedHashMap<Long, ChunkTile> m = byDim.get(dim);
        if (m == null) return;
        synchronized (m) {
            m.remove(packKey(cx, cz));
        }
    }

    public long hits()       { return hits.get(); }
    public long misses()     { return misses.get(); }
    public long evictions()  { return evictions.get(); }

    /** Total cached entries across all dims. */
    public int size() {
        int n = 0;
        for (LinkedHashMap<Long, ChunkTile> m : byDim.values()) {
            synchronized (m) { n += m.size(); }
        }
        return n;
    }
}
