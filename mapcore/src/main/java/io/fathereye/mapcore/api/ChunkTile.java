package io.fathereye.mapcore.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * A 16×16 chunk's worth of map data. Compact byte/short arrays — this struct
 * crosses the IPC boundary and is held in memory for every loaded chunk.
 *
 * <p>Indexing convention: {@code index = z * 16 + x}. {@code x} and {@code z}
 * are intra-chunk block coords (0..15).
 */
public final class ChunkTile {

    public final String dimensionId;
    public final int chunkX;
    public final int chunkZ;
    /** 256 bytes; biome id per column. */
    public final byte[] biomes;
    /** 256 shorts; surface heightmap (block Y, signed). */
    public final short[] heightMap;
    /** 256 ints; surface block ARGB color (palette-resolved by the renderer). May be null if unresolved. */
    public final int[] surfaceArgb;

    public ChunkTile(String dimensionId, int chunkX, int chunkZ,
                     byte[] biomes, short[] heightMap, int[] surfaceArgb) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.biomes = required(biomes, 256, "biomes");
        this.heightMap = requiredShort(heightMap, 256, "heightMap");
        this.surfaceArgb = surfaceArgb == null ? null : requiredInt(surfaceArgb, 256, "surfaceArgb");
    }

    private static byte[] required(byte[] a, int len, String name) {
        Objects.requireNonNull(a, name);
        if (a.length != len) throw new IllegalArgumentException(name + " must be length " + len);
        return a;
    }

    private static short[] requiredShort(short[] a, int len, String name) {
        Objects.requireNonNull(a, name);
        if (a.length != len) throw new IllegalArgumentException(name + " must be length " + len);
        return a;
    }

    private static int[] requiredInt(int[] a, int len, String name) {
        Objects.requireNonNull(a, name);
        if (a.length != len) throw new IllegalArgumentException(name + " must be length " + len);
        return a;
    }

    public int biomeAt(int xInChunk, int zInChunk) {
        return biomes[zInChunk * 16 + xInChunk] & 0xFF;
    }

    public int heightAt(int xInChunk, int zInChunk) {
        return heightMap[zInChunk * 16 + xInChunk];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkTile)) return false;
        ChunkTile c = (ChunkTile) o;
        return chunkX == c.chunkX && chunkZ == c.chunkZ
                && dimensionId.equals(c.dimensionId)
                && Arrays.equals(biomes, c.biomes)
                && Arrays.equals(heightMap, c.heightMap)
                && Arrays.equals(surfaceArgb, c.surfaceArgb);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(dimensionId, chunkX, chunkZ);
        h = 31 * h + Arrays.hashCode(biomes);
        h = 31 * h + Arrays.hashCode(heightMap);
        h = 31 * h + Arrays.hashCode(surfaceArgb);
        return h;
    }
}
