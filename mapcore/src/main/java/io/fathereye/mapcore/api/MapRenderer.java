package io.fathereye.mapcore.api;

/**
 * Top-level entry point. Both the in-game admin map screen and the Father Eye
 * desktop panel call exactly this method per frame. Identical inputs produce
 * identical pixels — that is what makes the two surfaces visually indistinguishable.
 */
public interface MapRenderer {

    void render(MapGraphics graphics, MapViewState view, MapData data);
}
