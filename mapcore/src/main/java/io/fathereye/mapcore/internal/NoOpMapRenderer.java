package io.fathereye.mapcore.internal;

import io.fathereye.mapcore.api.MapData;
import io.fathereye.mapcore.api.MapGraphics;
import io.fathereye.mapcore.api.MapRenderer;
import io.fathereye.mapcore.api.MapViewState;

/**
 * Stub renderer used by consumers (the {@code map} mod and the panel) until
 * the real renderer lands in milestone 12. Draws a single dim background plus
 * a placeholder text so wiring can be exercised end-to-end without committing
 * to the final visual design.
 *
 * <p>This class is in the internal package — consumers should construct it via
 * {@link io.fathereye.mapcore.api.MapCore#noOpRenderer()} rather than depend
 * on it directly. (Kept package-public for that factory; not part of the
 * versioned contract.)
 */
public final class NoOpMapRenderer implements MapRenderer {

    public NoOpMapRenderer() {}

    @Override
    public void render(MapGraphics g, MapViewState view, MapData data) {
        g.fillRect(0, 0, view.viewportWidth, view.viewportHeight, 0xFF1E1E1E);
        g.drawText("fathereye-mapcore noop renderer (M12 lands the real one)",
                8, 8, 0xFFE0E0E0, true);
        g.drawText("dim=" + view.dimensionId
                        + " center=(" + (long) view.centerBlockX + "," + (long) view.centerBlockZ + ")"
                        + " zoom=" + view.zoom
                        + " players=" + data.players().size()
                        + " entities=" + data.entities().size()
                        + " tiles=" + data.tilesByChunkKey().size(),
                8, 22, 0xFFA0A0A0, true);
        g.flush();
    }
}
