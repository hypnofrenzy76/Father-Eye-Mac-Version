package io.fathereye.mapcore.api;

import java.util.Objects;

/**
 * What the camera is looking at. Pure data — no behavior.
 *
 * <p>Coordinates are in <i>world blocks</i>. Zoom is the number of screen
 * pixels per world block (e.g. {@code 1.0f} = 1 px/block; {@code 4.0f} =
 * each block drawn 4×4 px). The view rectangle is the current viewport size.
 */
public final class MapViewState {

    public final String dimensionId;
    public final double centerBlockX;
    public final double centerBlockZ;
    public final float zoom;
    public final int viewportWidth;
    public final int viewportHeight;
    public final LayerToggleSet layers;

    public MapViewState(String dimensionId,
                        double centerBlockX,
                        double centerBlockZ,
                        float zoom,
                        int viewportWidth,
                        int viewportHeight,
                        LayerToggleSet layers) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.centerBlockX = centerBlockX;
        this.centerBlockZ = centerBlockZ;
        this.zoom = zoom;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.layers = Objects.requireNonNull(layers, "layers");
    }
}
