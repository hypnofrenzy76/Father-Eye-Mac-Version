package io.fathereye.mapcore.api;

import io.fathereye.mapcore.internal.BasicMapRenderer;
import io.fathereye.mapcore.internal.NoOpMapRenderer;

/**
 * Entry point and version constant for the mapcore library.
 */
public final class MapCore {

    /** Library version. Consumers pin to an exact value (no ranges). */
    public static final String VERSION = "0.2.0";

    /** Wire-protocol version for any data contracts that depend on this library. */
    public static final int CONTRACT_MAJOR = 0;
    public static final int CONTRACT_MINOR = 2;

    private MapCore() {}

    /** Stub renderer — debug background only. Useful for early integration. */
    public static MapRenderer noOpRenderer() {
        return new NoOpMapRenderer();
    }

    /**
     * v0.2.0 renderer: chunk surface ARGB blocks + entity/player/POI markers.
     * Pixel-identical for non-text geometry across both backends; text uses
     * the backend's native font until a bundled font atlas lands in v0.3.0.
     */
    public static MapRenderer basicRenderer() {
        return new BasicMapRenderer();
    }
}
