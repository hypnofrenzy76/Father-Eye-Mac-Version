package io.fathereye.mapcore.api;

/**
 * Bitset of which map layers are currently visible. Stable bit indices —
 * panel and mod must agree on the meaning of each bit, so the assignments
 * are part of the {@code 0.x} API contract.
 */
public final class LayerToggleSet {

    public static final int BIT_PLAYERS         = 0;
    public static final int BIT_HOSTILE_MOBS    = 1;
    public static final int BIT_PASSIVE_MOBS    = 2;
    public static final int BIT_ITEMS           = 3;
    public static final int BIT_LOADED_CHUNKS   = 4;
    public static final int BIT_TE_HOTSPOTS     = 5;
    public static final int BIT_BIOMES          = 6;
    public static final int BIT_STRUCTURES      = 7;
    public static final int BIT_LIGHT_LEVELS    = 8;
    public static final int BIT_CLAIMS          = 9;
    public static final int BIT_AURA_NODES      = 10;
    public static final int BIT_CUSTOM_POIS     = 11;

    public static final long DEFAULT_MASK =
            (1L << BIT_PLAYERS) | (1L << BIT_HOSTILE_MOBS) | (1L << BIT_BIOMES);

    public final long mask;

    public LayerToggleSet(long mask) {
        this.mask = mask;
    }

    public boolean has(int bit) {
        return (mask & (1L << bit)) != 0L;
    }

    public LayerToggleSet with(int bit, boolean on) {
        return new LayerToggleSet(on ? (mask | (1L << bit)) : (mask & ~(1L << bit)));
    }

    public static LayerToggleSet defaults() {
        return new LayerToggleSet(DEFAULT_MASK);
    }
}
