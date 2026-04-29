package io.fathereye.mapcore.api;

/**
 * Opaque handle to a texture uploaded to whatever graphics backend is in use
 * (Minecraft RenderSystem in the in-game admin map mod, JavaFX Canvas in the
 * Father Eye desktop panel).
 *
 * <p>Implementations are produced by {@link MapTextureLoader} and consumed by
 * {@link MapGraphics#drawTexture}. The renderer never inspects pixels directly.
 */
public interface MapTexture {

    int width();

    int height();

    /**
     * Stable, backend-agnostic identifier (e.g. "fathereye:marker_player").
     * Used for caching and debugging; not used for resolving GPU resources.
     */
    String identifier();
}
