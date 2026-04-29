package io.fathereye.bridge.topic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wire payload for {@link Topics#CHUNKS}. */
public final class ChunksSnapshot {

    public long timestampMs;
    /** dimensionId → chunk-load metadata */
    public Map<String, DimChunks> byDim = new LinkedHashMap<>();

    public ChunksSnapshot() {}

    public static final class DimChunks {
        public int loadedChunks;
        public int forcedChunks;
        public int playerChunks;

        /**
         * Brg-14 (2026-04-26): list of currently-loaded chunk
         * coordinates as a flat int array, [cx0, cz0, cx1, cz1, ...].
         * The panel's MapPane uses this to fetch tile data for every
         * loaded chunk, not just the ones in the user's viewport.
         * Capped at {@link #MAX_CHUNKS_REPORTED} chunks per dim per
         * snapshot to bound payload size; the WorldStateCollector
         * picks the closest chunks to spawn / players first.
         *
         * <p>Format choice: flat int array (not int[][]) because
         * Jackson serialises that as a single JSON array of numbers,
         * about 6 bytes per chunk versus 18 bytes for `[[cx,cz]]`.
         * On a 1000-chunk modpack server that is 6 KB versus 18 KB
         * per snapshot at 1 Hz.
         */
        public int[] chunks = new int[0];

        public DimChunks() {}
    }

    /** Brg-14: per-dim coordinate-list cap. 4096 chunks at flat int
     *  encoding is ~48 KB JSON (8192 ints, average ~6 bytes per
     *  decimal int including comma), sustainable at 1 Hz over the
     *  IPC. The audit caught my earlier "32 KB" estimate which
     *  ignored each int needing its own ASCII representation; the
     *  actual on-wire size is ~50% larger. */
    public static final int MAX_CHUNKS_REPORTED = 4096;
}
