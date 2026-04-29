package io.fathereye.mapcore.internal;

import io.fathereye.mapcore.api.ChunkTile;
import io.fathereye.mapcore.api.EntityMarker;
import io.fathereye.mapcore.api.LayerToggleSet;
import io.fathereye.mapcore.api.MapData;
import io.fathereye.mapcore.api.MapGraphics;
import io.fathereye.mapcore.api.MapRenderer;
import io.fathereye.mapcore.api.MapViewState;
import io.fathereye.mapcore.api.PlayerMarker;
import io.fathereye.mapcore.api.PointOfInterest;

import java.util.Map;

/**
 * v0.2.0 renderer. Draws chunk surface ARGB blocks at integer pixel
 * coordinates, then live entity / player / POI markers as colored squares.
 *
 * <p>Coordinate model: world (block) X/Z map to screen pixels via view.zoom
 * (px per block) and view center. Y in world space is unused for the 2D view.
 *
 * <p>Text uses {@link MapGraphics#drawText}, which both backends implement
 * with their native font. Pixel-identical text awaits the bundled font atlas
 * (planned for v0.3.0); for v0.2.x the atlas is intentionally absent so
 * each backend uses Minecraft FontRenderer (in-game) or JavaFX Font (panel).
 * Non-text geometry IS pixel-identical.
 */
public final class BasicMapRenderer implements MapRenderer {

    private static final int BG_ARGB             = 0xFF101010;
    private static final int CHUNK_BORDER_ARGB   = 0xFF202020;
    private static final int PLAYER_ARGB         = 0xFFFFE060;
    private static final int HOSTILE_ARGB        = 0xFFE03040;
    private static final int PASSIVE_ARGB        = 0xFF60D060;
    private static final int ITEM_ARGB           = 0xFFC0C0FF;
    private static final int POI_ARGB            = 0xFF80B0FF;

    public BasicMapRenderer() {}

    @Override
    public void render(MapGraphics g, MapViewState view, MapData data) {
        g.fillRect(0, 0, view.viewportWidth, view.viewportHeight, BG_ARGB);

        float blocksPerPx = 1.0f / Math.max(view.zoom, 0.0001f);
        int halfW = view.viewportWidth / 2;
        int halfH = view.viewportHeight / 2;

        // ---- chunk tiles --------------------------------------------------
        for (Map.Entry<Long, ChunkTile> e : data.tilesByChunkKey().entrySet()) {
            ChunkTile t = e.getValue();
            if (!t.dimensionId.equals(view.dimensionId)) continue;
            int chunkBaseX = t.chunkX * 16;
            int chunkBaseZ = t.chunkZ * 16;
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int worldX = chunkBaseX + dx;
                    int worldZ = chunkBaseZ + dz;
                    int sx = (int) ((worldX - view.centerBlockX) * view.zoom + halfW);
                    int sy = (int) ((worldZ - view.centerBlockZ) * view.zoom + halfH);
                    int sw = Math.max(1, (int) view.zoom);
                    int sh = Math.max(1, (int) view.zoom);
                    if (sx + sw < 0 || sx > view.viewportWidth || sy + sh < 0 || sy > view.viewportHeight) continue;
                    int argb = t.surfaceArgb == null ? 0xFF606060 : t.surfaceArgb[dz * 16 + dx];
                    g.fillRect(sx, sy, sw, sh, argb);
                }
            }
            // Chunk border for visual orientation (visible only at zoom>=2).
            if (view.zoom >= 2.0f) {
                int sx = (int) ((chunkBaseX - view.centerBlockX) * view.zoom + halfW);
                int sy = (int) ((chunkBaseZ - view.centerBlockZ) * view.zoom + halfH);
                int s = Math.max(1, (int) (16 * view.zoom));
                g.fillRect(sx, sy, s, 1, CHUNK_BORDER_ARGB);
                g.fillRect(sx, sy, 1, s, CHUNK_BORDER_ARGB);
            }
        }

        // ---- POIs ---------------------------------------------------------
        if (view.layers.has(LayerToggleSet.BIT_AURA_NODES) || view.layers.has(LayerToggleSet.BIT_CUSTOM_POIS)) {
            for (PointOfInterest p : data.pois()) {
                if (!p.dimensionId.equals(view.dimensionId)) continue;
                int sx = (int) ((p.x - view.centerBlockX) * view.zoom + halfW) - 3;
                int sy = (int) ((p.z - view.centerBlockZ) * view.zoom + halfH) - 3;
                g.fillRect(sx, sy, 6, 6, p.tintArgb == 0 ? POI_ARGB : p.tintArgb);
            }
        }

        // ---- entities -----------------------------------------------------
        for (EntityMarker em : data.entities()) {
            int color;
            switch (em.kind) {
                case HOSTILE:  if (!view.layers.has(LayerToggleSet.BIT_HOSTILE_MOBS)) continue; color = HOSTILE_ARGB; break;
                case PASSIVE:  if (!view.layers.has(LayerToggleSet.BIT_PASSIVE_MOBS)) continue; color = PASSIVE_ARGB; break;
                case ITEM:     if (!view.layers.has(LayerToggleSet.BIT_ITEMS))         continue; color = ITEM_ARGB;    break;
                case PLAYER:   continue; // players drawn from their own list
                case OTHER:
                default:       color = 0xFFC0C0C0; break;
            }
            int sx = (int) ((em.x - view.centerBlockX) * view.zoom + halfW) - 2;
            int sy = (int) ((em.z - view.centerBlockZ) * view.zoom + halfH) - 2;
            g.fillRect(sx, sy, 4, 4, color);
        }

        // ---- players ------------------------------------------------------
        if (view.layers.has(LayerToggleSet.BIT_PLAYERS)) {
            for (PlayerMarker p : data.players()) {
                if (!p.dimensionId.equals(view.dimensionId)) continue;
                int sx = (int) ((p.x - view.centerBlockX) * view.zoom + halfW);
                int sy = (int) ((p.z - view.centerBlockZ) * view.zoom + halfH);
                g.fillRect(sx - 4, sy - 4, 8, 8, PLAYER_ARGB);
                g.drawText(p.name, sx + 6, sy - 4, 0xFFFFFFFF, true);
            }
        }

        g.flush();
    }
}
