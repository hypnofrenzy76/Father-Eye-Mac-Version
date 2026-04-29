package io.fathereye.bridge.topic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wire payload for {@link Topics#MOBS}. */
public final class MobsSnapshot {

    public long timestampMs;
    /** dimensionId → modId → counts */
    public Map<String, Map<String, ModEntityCounts>> byDim = new LinkedHashMap<>();

    public MobsSnapshot() {}

    public static final class ModEntityCounts {
        public int total;
        public int hostile;
        public int passive;
        public int items;
        public int tileEntities; // populated by M9 profiler; M8 leaves at 0

        public ModEntityCounts() {}
    }
}
