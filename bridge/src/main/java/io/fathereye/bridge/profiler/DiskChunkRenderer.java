package io.fathereye.bridge.profiler;

import io.fathereye.mapcore.api.ChunkTile;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EmptyBlockReader;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.storage.FolderName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Map-02 / Brg-29 (2026-06-14): off-thread Anvil region-file reader.
 *
 * <p>The live {@link ChunkTileRenderer} only ever sees chunks Forge has
 * resident in memory (capped at the visible-chunk map). A server whose
 * world was fully pre-generated with Chunky has ~2.25M chunks on disk
 * that are NOT loaded, so the map could never show them. This reader
 * opens the {@code .mca} region files directly, parses the chunk NBT,
 * and produces the SAME {@link ChunkTile} surface bytes the live
 * renderer does, WITHOUT loading anything into the world and WITHOUT
 * touching the server tick thread.
 *
 * <p>Safe on any thread: it only reads files and parses NBT. It never
 * calls into the live world, so {@link ChunkTileHandler} (IPC thread)
 * can fall back to it on a cache miss with zero tick-thread cost.
 *
 * <p>Region layout is resolved per dimension via
 * {@link DimensionType#getStorageFolder} so vanilla overworld
 * ({@code region/}), nether ({@code DIM-1/region/}), end
 * ({@code DIM1/region/}) and modded dimensions
 * ({@code dimensions/<ns>/<path>/region/}) all work.
 *
 * <p>Block-state colour resolution mirrors the live renderer: parse the
 * per-section palette with {@link NBTUtil#readBlockState}, then ask the
 * {@link BlockState} for its {@link MaterialColor} against
 * {@link EmptyBlockReader#INSTANCE} (a world-free reader). 1.16.5 packs
 * the section {@code BlockStates} long array WITHOUT spanning longs
 * across the 64-bit boundary, so the unpack uses
 * {@code valuesPerLong = 64 / bitsPerBlock}.
 */
public final class DiskChunkRenderer {

    private static final Logger LOG = LogManager.getLogger("FatherEye-DiskChunk");

    /** Cap a single region_index scan so a giant pre-gen world can't
     *  stall the IPC thread; the panel pages by viewport anyway. */
    public static final int MAX_INDEX_RESULTS = 8192;

    private DiskChunkRenderer() {}

    // ---- dimension -> region folder resolution ------------------------

    /** Resolve the {@code region/} folder for a dimension id, or null if
     *  the world/dimension folder can't be located. */
    public static File regionFolder(MinecraftServer server, String dimId) {
        if (server == null || dimId == null) return null;
        try {
            Path worldRoot = server.getWorldPath(FolderName.ROOT);
            if (worldRoot == null) return null;
            RegistryKey<World> dimKey = RegistryKey.create(
                    Registry.DIMENSION_REGISTRY, new ResourceLocation(dimId));
            File dimFolder = DimensionType.getStorageFolder(dimKey, worldRoot.toFile());
            if (dimFolder == null) return null;
            File region = new File(dimFolder, "region");
            return region;
        } catch (Throwable t) {
            LOG.debug("regionFolder failed for {}: {}", dimId, t.toString());
            return null;
        }
    }

    // ---- single tile from disk ----------------------------------------

    /**
     * Read and render one chunk from its region file. Returns null when
     * the chunk is absent on disk, not a {@code full}-status chunk, or
     * has no usable surface. Never loads the chunk into the world.
     */
    public static ChunkTile renderFromDisk(MinecraftServer server, String dimId, int cx, int cz) {
        File region = regionFolder(server, dimId);
        if (region == null || !region.isDirectory()) return null;
        int rx = cx >> 5;
        int rz = cz >> 5;
        File mca = new File(region, "r." + rx + "." + rz + ".mca");
        if (!mca.isFile()) return null;
        CompoundNBT chunkNbt = readChunkNbt(region, mca, new ChunkPos(cx, cz));
        if (chunkNbt == null) return null;
        return renderNbt(dimId, cx, cz, chunkNbt);
    }

    /** Open the region file for a chunk and read its raw NBT, or null. */
    private static CompoundNBT readChunkNbt(File regionDir, File mca, ChunkPos pos) {
        try (RegionFile rf = new RegionFile(mca, regionDir, true)) {
            if (!rf.hasChunk(pos)) return null;
            try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                if (in == null) return null;
                return CompressedStreamTools.read(in);
            }
        } catch (Throwable t) {
            LOG.debug("readChunkNbt {} failed: {}", mca.getName(), t.toString());
            return null;
        }
    }

    // ---- enumerate chunks present on disk within a chunk box -----------

    /** A chunk coordinate present on disk. */
    public static final class DiskChunk {
        public final int cx;
        public final int cz;
        DiskChunk(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    /**
     * List the chunk coordinates that exist on disk within an inclusive
     * chunk-coordinate box. Scans only the region files the box overlaps
     * and probes each chunk slot's header (cheap: no decompression).
     * Capped at {@link #MAX_INDEX_RESULTS}.
     */
    public static List<DiskChunk> indexRegion(MinecraftServer server, String dimId,
                                              int minCx, int minCz, int maxCx, int maxCz) {
        List<DiskChunk> out = new ArrayList<>();
        File region = regionFolder(server, dimId);
        if (region == null || !region.isDirectory()) return out;
        if (maxCx < minCx || maxCz < minCz) return out;

        int minRx = minCx >> 5, maxRx = maxCx >> 5;
        int minRz = minCz >> 5, maxRz = maxCz >> 5;
        for (int rx = minRx; rx <= maxRx && out.size() < MAX_INDEX_RESULTS; rx++) {
            for (int rz = minRz; rz <= maxRz && out.size() < MAX_INDEX_RESULTS; rz++) {
                File mca = new File(region, "r." + rx + "." + rz + ".mca");
                if (!mca.isFile()) continue;
                try (RegionFile rf = new RegionFile(mca, region, true)) {
                    int baseCx = rx << 5;
                    int baseCz = rz << 5;
                    for (int lx = 0; lx < 32 && out.size() < MAX_INDEX_RESULTS; lx++) {
                        for (int lz = 0; lz < 32 && out.size() < MAX_INDEX_RESULTS; lz++) {
                            int cx = baseCx + lx;
                            int cz = baseCz + lz;
                            if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;
                            ChunkPos cp = new ChunkPos(cx, cz);
                            if (rf.hasChunk(cp)) out.add(new DiskChunk(cx, cz));
                        }
                    }
                } catch (Throwable t) {
                    LOG.debug("indexRegion {} failed: {}", mca.getName(), t.toString());
                }
            }
        }
        return out;
    }

    // ---- NBT -> ChunkTile ---------------------------------------------

    /**
     * Render a parsed chunk NBT to a {@link ChunkTile}. Returns null for
     * non-full / empty chunks or when no usable surface pixel is found.
     */
    private static ChunkTile renderNbt(String dimId, int cx, int cz, CompoundNBT root) {
        CompoundNBT level = root.contains("Level", 10) ? root.getCompound("Level") : root;
        // Only render fully-generated chunks; partial/proto chunks have
        // no stable surface and would render as noise.
        String status = level.getString("Status");
        if (!status.isEmpty() && !"full".equals(status) && !status.endsWith(":full")
                && !"minecraft:full".equals(status)) {
            return null;
        }

        // Build a y-indexed view of sections so we can scan top-down.
        BlockState[][] sectionBlocks = new BlockState[16][]; // [sectionY][0..4095] or null
        ListNBT sections = level.getList("Sections", 10);
        for (int i = 0; i < sections.size(); i++) {
            CompoundNBT sec = sections.getCompound(i);
            int y = sec.getByte("Y");
            if (y < 0 || y > 15) continue;
            if (!sec.contains("Palette", 9) || !sec.contains("BlockStates", 12)) continue;
            BlockState[] blocks = decodeSection(sec);
            if (blocks != null) sectionBlocks[y] = blocks;
        }

        short[] heights = new short[256];
        int[] surfaceArgb = new int[256];
        byte[] biomes = new byte[256];

        long[] heightmap = readHeightmap(level);
        int[] biomeArr = level.contains("Biomes", 11) ? level.getIntArray("Biomes") : null;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        int nonAirPixels = 0;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int idx = dz * 16 + dx;
                int worldX = cx * 16 + dx;
                int worldZ = cz * 16 + dz;

                int surfaceY = heightFromMap(heightmap, dx, dz);
                BlockState state = null;
                int y = surfaceY;
                if (surfaceY > 0) {
                    // Heightmap stores the first empty Y above the surface;
                    // the surface block is at surfaceY - 1.
                    state = blockAt(sectionBlocks, dx, surfaceY - 1, dz);
                    if (state != null && !state.isAir()) {
                        y = surfaceY - 1;
                    } else {
                        // Heightmap absent or off: scan down for the first
                        // non-air block from the top.
                        state = null;
                        SurfaceHit scanned = scanDown(sectionBlocks, dx, dz);
                        if (scanned != null) {
                            state = scanned.state;
                            y = scanned.y;
                        }
                    }
                } else {
                    SurfaceHit scanned = scanDown(sectionBlocks, dx, dz);
                    if (scanned != null) {
                        state = scanned.state;
                        y = scanned.y;
                    }
                }

                heights[idx] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));

                int rgb;
                if (state == null) {
                    rgb = 0;
                } else {
                    MaterialColor color;
                    try {
                        pos.set(worldX, Math.max(0, y), worldZ);
                        color = state.getMapColor(EmptyBlockReader.INSTANCE, pos);
                    } catch (Throwable t) {
                        color = MaterialColor.NONE;
                    }
                    rgb = (color == null) ? 0x202020 : (color.col & 0xFFFFFF);
                    if (color != null && color != MaterialColor.NONE && color.col != 0) {
                        nonAirPixels++;
                    }
                }
                surfaceArgb[idx] = 0xFF000000 | rgb;

                int bId = 0;
                if (biomeArr != null && biomeArr.length >= 256) {
                    // 1.16.5 stores 1024 biome entries (4x4x4); fall back
                    // to the 256-flat layout where present. Hash to a byte
                    // the same way the live renderer does.
                    int bi = biomeArr.length == 1024
                            ? biomeArr[biomeIndex1024(dx, Math.max(0, Math.min(255, y)), dz)]
                            : biomeArr[(dz * 16 + dx) % biomeArr.length];
                    bId = Math.abs(Integer.toString(bi).hashCode()) & 0xFF;
                }
                biomes[idx] = (byte) bId;
            }
        }

        if (nonAirPixels == 0) return null;
        return new ChunkTile(dimId, cx, cz, biomes, heights, surfaceArgb);
    }

    private static final class SurfaceHit {
        final BlockState state;
        final int y;
        SurfaceHit(BlockState state, int y) { this.state = state; this.y = y; }
    }

    /** Top-down scan for the first non-air block in a column. */
    private static SurfaceHit scanDown(BlockState[][] sections, int dx, int dz) {
        for (int sy = 15; sy >= 0; sy--) {
            BlockState[] sec = sections[sy];
            if (sec == null) continue;
            for (int yy = 15; yy >= 0; yy--) {
                BlockState s = sec[(yy << 8) | (dz << 4) | dx];
                if (s != null && !s.isAir()) {
                    return new SurfaceHit(s, (sy << 4) + yy);
                }
            }
        }
        return null;
    }

    /** Block state at an absolute column Y, or null. */
    private static BlockState blockAt(BlockState[][] sections, int dx, int y, int dz) {
        if (y < 0 || y > 255) return null;
        BlockState[] sec = sections[y >> 4];
        if (sec == null) return null;
        return sec[((y & 15) << 8) | (dz << 4) | dx];
    }

    /** Decode a 1.16.5 section's 4096 block states from palette + packed
     *  long array. Returns null if the section can't be decoded. */
    private static BlockState[] decodeSection(CompoundNBT sec) {
        try {
            ListNBT palette = sec.getList("Palette", 10);
            int paletteSize = palette.size();
            if (paletteSize == 0) return null;
            BlockState[] states = new BlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                states[i] = NBTUtil.readBlockState(palette.getCompound(i));
            }
            long[] data = sec.getLongArray("BlockStates");
            if (data.length == 0) return null;

            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            int valuesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;

            BlockState[] out = new BlockState[4096];
            int idx = 0;
            for (int li = 0; li < data.length && idx < 4096; li++) {
                long word = data[li];
                for (int v = 0; v < valuesPerLong && idx < 4096; v++) {
                    int pi = (int) ((word >>> (v * bits)) & mask);
                    out[idx++] = (pi >= 0 && pi < paletteSize) ? states[pi] : null;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** WORLD_SURFACE heightmap (long-packed, 9 bits/entry, non-spanning
     *  in 1.16.5). Returns null if absent. */
    private static long[] readHeightmap(CompoundNBT level) {
        try {
            if (!level.contains("Heightmaps", 10)) return null;
            CompoundNBT hm = level.getCompound("Heightmaps");
            if (hm.contains("WORLD_SURFACE", 12)) return hm.getLongArray("WORLD_SURFACE");
            if (hm.contains("OCEAN_FLOOR", 12)) return hm.getLongArray("OCEAN_FLOOR");
            if (hm.contains("MOTION_BLOCKING", 12)) return hm.getLongArray("MOTION_BLOCKING");
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read one column's surface Y from the packed heightmap, or 0. */
    private static int heightFromMap(long[] hm, int dx, int dz) {
        if (hm == null) return 0;
        int bits = 9; // 1.16.5 heightmaps use 9 bits/entry, non-spanning
        int valuesPerLong = 64 / bits; // = 7
        long mask = (1L << bits) - 1L;
        int cell = dz * 16 + dx;
        int li = cell / valuesPerLong;
        if (li < 0 || li >= hm.length) return 0;
        int off = (cell % valuesPerLong) * bits;
        return (int) ((hm[li] >>> off) & mask);
    }

    /** 1.16.5 1024-entry biome index (4x4x4 cells). */
    private static int biomeIndex1024(int x, int y, int z) {
        int bx = (x >> 2) & 3;
        int bz = (z >> 2) & 3;
        int by = Math.max(0, Math.min(63, y >> 2));
        return (by << 4) | (bz << 2) | bx;
    }
}
