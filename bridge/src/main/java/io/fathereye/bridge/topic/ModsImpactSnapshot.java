package io.fathereye.bridge.topic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wire payload for {@link Topics#MODS_IMPACT}. */
public final class ModsImpactSnapshot {

    public long timestampMs;
    /** dim -> mod -> impact slot */
    public Map<String, Map<String, ImpactSlot>> byDim = new LinkedHashMap<>();

    public ModsImpactSnapshot() {}

    public static final class ImpactSlot {
        /** Approximate ns/sec attributable to this mod's entities + tile entities in this dim. */
        public long attributedNanos;
        public int entityCount;
        public int tileEntityCount;

        public ImpactSlot() {}
    }
}
