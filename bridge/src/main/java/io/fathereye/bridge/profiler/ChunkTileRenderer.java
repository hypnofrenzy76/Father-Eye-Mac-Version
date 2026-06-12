package io.fathereye.bridge.profiler;

import io.fathereye.mapcore.api.ChunkTile;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;

/**
 * Brg-24 (2026-06-12): tick-thread-only chunk tile renderer, extracted
 * from {@link io.fathereye.bridge.rpc.ChunkTileHandler} so that ALL
 * world reads now happen inside {@link TickStateMirror}'s budgeted
 * tick hook. The RPC handler itself no longer touches the world at
 * all (it is served directly off the IPC thread from
 * {@link ChunkSurfaceCache}), which removes the 20-32 ms worst-case
 * tick cost the 64-miss budget used to allow.
 *
 * <p>Render semantics are unchanged from Brg-18/19/20/21/23:
 * <ul>
 *   <li>status fallback FULL -&gt; HEIGHTMAPS -&gt; SURFACE -&gt;
 *       holder.getLastAvailable (reflective)</li>
 *   <li>hoisted biome registry + container lookups</li>
 *   <li>per-pixel try/catch so one bad pixel can't abort a tile</li>
 *   <li>zero-usable-pixel tiles are rejected (black-rectangle fix)</li>
 * </ul>
 *
 * <p>MUST only be called from the server tick thread.
 */
public final class ChunkTileRenderer {

    private static final Logger LOG = LogManager.getLogger("FatherEye-ChunkTile");

    /** Brg-19: SRG-reflective handles, verified against
     *  1.16.5-20210115.111550 joined.tsrg. See ChunkTileHandler's
     *  original Brg-19 note for the full rationale. */
    private static final java.lang.reflect.Method CHUNK_MANAGER_GET_CHUNKS;
    private static final java.lang.reflect.Method CHUNK_MANAGER_GET_VISIBLE;
    private static final java.lang.reflect.Method CHUNK_HOLDER_GET_LAST_AVAILABLE;
    private static volatile boolean warnedHolderLookup = false;
    static {
        java.lang.reflect.Method m0 = null;
        java.lang.reflect.Method m1 = null;
        java.lang.reflect.Method m2 = null;
        try {
            m0 = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findMethod(
                    ChunkManager.class, "func_223491_f");
        } catch (Throwable t) {
            LOG.warn("ChunkManager.getChunks reflective lookup failed; chunk enumeration will be empty. {}", t.toString());
        }
        try {
            m1 = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findMethod(
                    ChunkManager.class, "func_219219_b", long.class);
        } catch (Throwable t) {
            LOG.warn("ChunkManager.getVisibleChunkIfPresent reflective lookup failed. {}", t.toString());
        }
        try {
            m2 = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findMethod(
                    ChunkHolder.class, "func_219287_e");
        } catch (Throwable t) {
            LOG.warn("ChunkHolder.getLastAvailable reflective lookup failed. {}", t.toString());
        }
        CHUNK_MANAGER_GET_CHUNKS = m0;
        CHUNK_MANAGER_GET_VISIBLE = m1;
        CHUNK_HOLDER_GET_LAST_AVAILABLE = m2;
    }

    private ChunkTileRenderer() {}

    /** A rendered tile plus whether it came from a FULL-status chunk
     *  (FULL tiles are stable; non-FULL ones are re-rendered later by
     *  TickStateMirror's refresh rotation). */
    public static final class Result {
        public final ChunkTile tile;
        public final boolean full;
        Result(ChunkTile tile, boolean full) { this.tile = tile; this.full = full; }
    }

    /** Enumerate every ChunkHolder in the world's visible chunk map.
     *  Tick thread only. Empty iterable on reflective failure. */
    @SuppressWarnings("unchecked")
    public static Iterable<ChunkHolder> loadedHolders(ServerWorld world) {
        if (CHUNK_MANAGER_GET_CHUNKS == null) return Collections.emptyList();
        try {
            ChunkManager cm = world.getChunkSource().chunkMap;
            return (Iterable<ChunkHolder>) CHUNK_MANAGER_GET_CHUNKS.invoke(cm);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** The holder's last available IChunk regardless of ticket-level
     *  gating (the Brg-19 fix). Null if the holder carries nothing. */
    public static IChunk lastAvailable(ChunkHolder holder) {
        if (CHUNK_HOLDER_GET_LAST_AVAILABLE == null) return null;
        try {
            Object obj = CHUNK_HOLDER_GET_LAST_AVAILABLE.invoke(holder);
            return obj instanceof IChunk ? (IChunk) obj : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Render one chunk into a {@link ChunkTile}. Returns null when the
     * chunk is not loaded at any status or has no usable in-memory
     * surface data (Brg-21). Never triggers chunk load/gen.
     */
    public static Result render(ServerWorld world, int cx, int cz) {
        boolean fellBack = false;
        IChunk chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.HEIGHTMAPS, false);
            if (chunk != null) fellBack = true;
        }
        if (chunk == null) {
            chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.SURFACE, false);
            if (chunk != null) fellBack = true;
        }
        if (chunk == null && CHUNK_MANAGER_GET_VISIBLE != null) {
            try {
                ChunkManager cm = world.getChunkSource().chunkMap;
                long chunkKey = net.minecraft.util.math.ChunkPos.asLong(cx, cz);
                Object holder = CHUNK_MANAGER_GET_VISIBLE.invoke(cm, chunkKey);
                if (holder != null) {
                    IChunk c = lastAvailable((ChunkHolder) holder);
                    if (c != null) { chunk = c; fellBack = true; }
                }
            } catch (Throwable t) {
                if (!warnedHolderLookup) {
                    warnedHolderLookup = true;
                    LOG.warn("Holder-level chunk fallback failed; LOAD-level chunks will be skipped.", t);
                }
            }
        }
        if (chunk == null) return null;

        String dim = world.dimension().location().toString();
        byte[] biomes = new byte[256];
        short[] heights = new short[256];
        int[] surfaceArgb = new int[256];

        Registry<Biome> biomeReg = null;
        try {
            biomeReg = world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        } catch (Throwable ignored) { /* fall back to per-pixel lookup */ }
        net.minecraft.world.biome.BiomeContainer biomeContainer;
        try {
            biomeContainer = chunk.getBiomes();
        } catch (Throwable ignored) {
            biomeContainer = null;
        }

        int nonAirPixels = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int idx = dz * 16 + dx;
                int worldX = cx * 16 + dx;
                int worldZ = cz * 16 + dz;
                int y;
                BlockState state;
                try {
                    y = chunk.getHeight(Heightmap.Type.WORLD_SURFACE, dx, dz);
                    pos.set(worldX, Math.max(0, y - 1), worldZ);
                    state = chunk.getBlockState(pos);
                } catch (Throwable t) {
                    heights[idx] = 0;
                    surfaceArgb[idx] = 0xFF000000;
                    biomes[idx] = 0;
                    continue;
                }
                heights[idx] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
                MaterialColor color;
                try {
                    color = state.getMapColor(world, pos);
                } catch (Throwable t) {
                    color = MaterialColor.NONE;
                }
                int rgb = color == null ? 0x202020 : (color.col & 0xFFFFFF);
                surfaceArgb[idx] = 0xFF000000 | rgb;
                if (color != null && color != MaterialColor.NONE && color.col != 0) {
                    nonAirPixels++;
                }
                try {
                    Biome biome = biomeContainer == null
                            ? world.getBiome(pos)
                            : biomeContainer.getNoiseBiome(worldX >> 2, y >> 2, worldZ >> 2);
                    int bId = 0;
                    if (biome != null && biomeReg != null) {
                        ResourceLocation biomeId = biomeReg.getKey(biome);
                        bId = biomeId == null ? 0 : Math.abs(biomeId.toString().hashCode()) & 0xFF;
                    }
                    biomes[idx] = (byte) bId;
                } catch (Throwable t) {
                    biomes[idx] = 0;
                }
            }
        }

        if (nonAirPixels == 0) return null;
        return new Result(new ChunkTile(dim, cx, cz, biomes, heights, surfaceArgb), !fellBack);
    }
}
