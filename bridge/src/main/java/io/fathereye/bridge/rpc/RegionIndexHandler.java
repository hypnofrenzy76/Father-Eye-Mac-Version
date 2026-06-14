package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.bridge.profiler.DiskChunkRenderer;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Map-02 / Brg-29 (2026-06-14): serves the {@code region_index} RPC.
 *
 * <p>Given a viewport in chunk coordinates ({@code dim, minCx, minCz,
 * maxCx, maxCz}), it scans the dimension's {@code .mca} region files on
 * disk and returns the flat coordinate list of every chunk that exists
 * there, in the SAME {@code [cx0, cz0, cx1, cz1, ...]} layout the
 * {@code chunks_topic} snapshot uses. The client (panel MapPane / web
 * map) then issues normal {@code chunk_tile} RPCs for each coord, which
 * {@link ChunkTileHandler} fills from the live cache or, on a miss, the
 * {@link DiskChunkRenderer} disk fallback.
 *
 * <p>This is what lets a fully Chunky-pre-generated world (millions of
 * chunks that are NOT resident in the live world) appear on the map:
 * {@code chunks_topic} only ever reports loaded chunks, so without a
 * disk enumeration the panel would never know the pre-gen chunks are
 * there to request.
 *
 * <p>Registered DIRECT (IPC thread): {@link DiskChunkRenderer#indexRegion}
 * only reads files and probes region-file headers (no decompression, no
 * world access), so it never touches the tick thread. The scan is capped
 * at {@link DiskChunkRenderer#MAX_INDEX_RESULTS} so a huge viewport over
 * a giant pre-gen can't stall the IPC thread; the client pages by
 * viewport as the user pans.
 */
public final class RegionIndexHandler {

    private RegionIndexHandler() {}

    /** Cache-free disk enumeration; safe on any thread. Registered
     *  DIRECT (IPC thread) — never schedule this onto the tick thread. */
    public static Object handle(JsonNode args, MinecraftServer server) {
        String dim = args.path("dim").asText("minecraft:overworld");
        int minCx = args.path("minCx").asInt();
        int minCz = args.path("minCz").asInt();
        int maxCx = args.path("maxCx").asInt();
        int maxCz = args.path("maxCz").asInt();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dim", dim);

        if (server == null) {
            out.put("chunks", new int[0]);
            out.put("truncated", false);
            return out;
        }

        List<DiskChunkRenderer.DiskChunk> found =
                DiskChunkRenderer.indexRegion(server, dim, minCx, minCz, maxCx, maxCz);

        int[] flat = new int[found.size() * 2];
        int i = 0;
        for (DiskChunkRenderer.DiskChunk c : found) {
            flat[i++] = c.cx;
            flat[i++] = c.cz;
        }
        out.put("chunks", flat);
        // True when the scan hit the cap, so the client knows the
        // viewport holds more chunks than were returned and can narrow
        // its request box (zoom in) to enumerate the remainder.
        out.put("truncated", found.size() >= DiskChunkRenderer.MAX_INDEX_RESULTS);
        return out;
    }
}
