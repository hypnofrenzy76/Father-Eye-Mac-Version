package io.fathereye.mapcore.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * Backend-specific factory for {@link MapTexture}. Implemented once per
 * graphics surface (Minecraft TextureManager, JavaFX Image upload).
 */
public interface MapTextureLoader {

    /**
     * Upload raw ARGB pixels. Pixel layout is row-major, top-left origin,
     * 32-bit ARGB packed (alpha in the high byte).
     */
    MapTexture load(String id, int[] argb, int width, int height);

    /**
     * Upload from a PNG stream. Caller retains ownership of the stream and
     * is responsible for closing it.
     */
    MapTexture load(String id, InputStream png) throws IOException;

    /**
     * Release a texture's GPU/native resources. Calling this on an already-
     * released texture is a no-op.
     */
    void release(MapTexture texture);
}
