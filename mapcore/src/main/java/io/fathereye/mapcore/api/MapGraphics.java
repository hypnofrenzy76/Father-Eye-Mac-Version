package io.fathereye.mapcore.api;

/**
 * Immediate-mode 2D drawing surface. Backend implementations adapt this to
 * Minecraft's {@code MatrixStack + Tessellator + RenderSystem} or to JavaFX's
 * {@code Canvas.GraphicsContext}.
 *
 * <p>All coordinates are integer pixels in screen space, top-left origin.
 * Colors are 32-bit ARGB. The renderer never assumes anti-aliasing; both
 * backends MUST disable it for pixel parity.
 *
 * <p>Transform stack uses translate + uniform scale only; no rotation, no
 * skew. This restriction keeps the in-game backend (which uses Minecraft's
 * 2D GUI matrix path) trivially compatible.
 */
public interface MapGraphics {

    void setClip(int x, int y, int width, int height);

    void clearClip();

    void fillRect(int x, int y, int width, int height, int argb);

    void drawTexture(MapTexture texture,
                     int dstX, int dstY, int dstWidth, int dstHeight,
                     float u0, float v0, float u1, float v1,
                     int tintArgb);

    void drawText(String text, int x, int y, int argb, boolean shadow);

    void pushTransform(float translateX, float translateY, float uniformScale);

    void popTransform();

    /**
     * Flush any batched draw calls. Both backends should call this at the
     * end of a frame; calling mid-frame is permitted but may hurt batching.
     */
    void flush();
}
