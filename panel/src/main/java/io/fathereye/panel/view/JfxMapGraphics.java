package io.fathereye.panel.view;

import io.fathereye.mapcore.api.MapGraphics;
import io.fathereye.mapcore.api.MapTexture;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * MapGraphics adapter over a JavaFX Canvas's {@link GraphicsContext}.
 * Anti-aliasing is disabled at the GC level so geometry matches the in-game
 * Minecraft rendering pixel-for-pixel; text uses a fixed monospace font and
 * will be replaced by an atlas-driven implementation when mapcore v0.3.0
 * lands the bundled font.
 */
public final class JfxMapGraphics implements MapGraphics {

    private final GraphicsContext g;
    private final Deque<float[]> transforms = new ArrayDeque<>();

    public JfxMapGraphics(GraphicsContext g) {
        this.g = g;
        g.setImageSmoothing(false);
        g.setFont(Font.font("Consolas", 11));
    }

    @Override
    public void setClip(int x, int y, int w, int h) {
        g.save();
        g.beginPath();
        g.rect(x, y, w, h);
        g.clip();
    }

    @Override
    public void clearClip() {
        g.restore();
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        g.setFill(toColor(argb));
        g.fillRect(x, y, w, h);
    }

    @Override
    public void drawTexture(MapTexture t, int dx, int dy, int dw, int dh,
                            float u0, float v0, float u1, float v1, int tint) {
        // mapcore 0.2.x ships no bundled atlas; the panel doesn't yet keep
        // an Image cache here. When bundled textures arrive, swap to:
        //   g.drawImage(image, srcX, srcY, srcW, srcH, dx, dy, dw, dh);
        // For now, fall back to a tinted placeholder so callers don't NPE.
        g.setFill(toColor(tint == 0 ? 0xFFFFFFFF : tint));
        g.fillRect(dx, dy, dw, dh);
    }

    @Override
    public void drawText(String s, int x, int y, int argb, boolean shadow) {
        if (shadow) {
            g.setFill(Color.rgb(0, 0, 0, 0.6));
            g.fillText(s, x + 1, y + 11);
        }
        g.setFill(toColor(argb));
        g.fillText(s, x, y + 10);
    }

    @Override
    public void pushTransform(float tx, float ty, float scale) {
        g.save();
        g.translate(tx, ty);
        g.scale(scale, scale);
        transforms.push(new float[] { tx, ty, scale });
    }

    @Override
    public void popTransform() {
        if (!transforms.isEmpty()) {
            transforms.pop();
            g.restore();
        }
    }

    @Override
    public void flush() {
        // GraphicsContext flushes implicitly on next render frame.
    }

    private static Color toColor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int gr = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return Color.rgb(r, gr, b, a / 255.0);
    }
}
