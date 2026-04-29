package io.fathereye.mapcore.api;

import io.fathereye.mapcore.internal.NoOpMapRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapCoreTest {

    @Test
    void versionIsSemverPinned() {
        assertEquals("0.2.0", MapCore.VERSION);
        assertEquals(0, MapCore.CONTRACT_MAJOR);
        assertEquals(2, MapCore.CONTRACT_MINOR);
    }

    @Test
    void basicRendererReturnsInstance() {
        assertNotNull(MapCore.basicRenderer());
    }

    @Test
    void noOpRendererReturnsInstance() {
        MapRenderer r = MapCore.noOpRenderer();
        assertNotNull(r);
        assertTrue(r instanceof NoOpMapRenderer);
    }

    @Test
    void chunkKeyRoundTripsForExtremes() {
        long k = MapData.chunkKey(Integer.MAX_VALUE, Integer.MIN_VALUE);
        // Pack/unpack symmetry — high 32 = x, low 32 = z (sign-extended on read).
        int x = (int) (k >> 32);
        int z = (int) (k & 0xFFFFFFFFL);
        assertEquals(Integer.MAX_VALUE, x);
        assertEquals(Integer.MIN_VALUE, z);
    }

    @Test
    void layerToggleSetWithFlipsBitsImmutably() {
        LayerToggleSet base = LayerToggleSet.defaults();
        assertTrue(base.has(LayerToggleSet.BIT_PLAYERS));
        LayerToggleSet off = base.with(LayerToggleSet.BIT_PLAYERS, false);
        assertFalse(off.has(LayerToggleSet.BIT_PLAYERS));
        // Original untouched.
        assertTrue(base.has(LayerToggleSet.BIT_PLAYERS));
    }

    @Test
    void chunkTileRejectsWrongLengthArrays() {
        try {
            new ChunkTile("minecraft:overworld", 0, 0, new byte[10], new short[256], null);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("biomes"));
        }
    }

    @Test
    void mapDataEmptyIsUsable() {
        MapData empty = MapData.empty();
        assertEquals(0, empty.players().size());
        assertEquals(0, empty.entities().size());
        assertEquals(0, empty.pois().size());
        assertEquals(0, empty.tilesByChunkKey().size());
    }

    @Test
    void noOpRendererRunsAgainstStubGraphics() {
        MapRenderer r = MapCore.noOpRenderer();
        StubGraphics g = new StubGraphics();
        MapViewState view = new MapViewState("minecraft:overworld",
                0.0, 0.0, 1.0f, 800, 600, LayerToggleSet.defaults());
        r.render(g, view, MapData.empty());
        assertTrue(g.flushCount > 0, "renderer should flush at least once");
        assertTrue(g.fillRectCount > 0, "renderer should fill background");
    }

    @SuppressWarnings("unused")
    private static UUID someUuid() {
        return new UUID(0xDEADBEEF_DEADBEEFL, 0xCAFEBABE_CAFEBABEL);
    }

    /** Counts calls; used to assert the renderer actually drew something. */
    private static final class StubGraphics implements MapGraphics {
        int flushCount = 0;
        int fillRectCount = 0;
        @Override public void setClip(int x, int y, int w, int h) {}
        @Override public void clearClip() {}
        @Override public void fillRect(int x, int y, int w, int h, int argb) { fillRectCount++; }
        @Override public void drawTexture(MapTexture t, int dx, int dy, int dw, int dh,
                                          float u0, float v0, float u1, float v1, int tint) {}
        @Override public void drawText(String s, int x, int y, int argb, boolean shadow) {}
        @Override public void pushTransform(float tx, float ty, float scale) {}
        @Override public void popTransform() {}
        @Override public void flush() { flushCount++; }
    }
}
