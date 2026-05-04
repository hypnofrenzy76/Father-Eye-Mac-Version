package io.fathereye.webaddon.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.addon.PanelContext;
import io.fathereye.panel.ipc.PipeClient;
import io.javalin.http.Context;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Map tile endpoint. The bridge's {@code chunk_tile} RPC returns a
 * compact byte array (one byte per pixel) that the JavaFX MapPane
 * decodes via the JfxMapGraphics palette. For the browser we forward
 * the raw bytes as base64 plus the palette and dimensions so the
 * client can paint it on a 2D canvas with the same visuals.
 */
public final class MapController {

    private final PanelContext context;

    public MapController(PanelContext context) {
        this.context = context;
    }

    public void dimensions(Context ctx) {
        // The dimension list is captured during the bridge handshake;
        // surface whatever the panel currently knows about. If no
        // bridge yet, return the standard fallback.
        // Cached via the topic bridge via WelcomeInfo would be ideal,
        // but for now reuse the panel's MapPane known list via reflection
        // is overkill — just return a static set the bridge always
        // exposes.
        ctx.json(Map.of("dimensions", java.util.List.of(
                "minecraft:overworld",
                "minecraft:the_nether",
                "minecraft:the_end")));
    }

    public void tile(Context ctx) {
        String dim = ctx.queryParam("dim");
        String xs = ctx.queryParam("x");
        String zs = ctx.queryParam("z");
        if (xs == null || zs == null) {
            throw new IllegalArgumentException("'x' and 'z' query parameters are required.");
        }
        if (dim == null || dim.isBlank()) dim = "minecraft:overworld";
        int x, z;
        try { x = Integer.parseInt(xs); z = Integer.parseInt(zs); }
        catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("'x' and 'z' must be integers.");
        }

        PipeClient pc = RestSupport.pipe(context);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("dim", dim);
        args.put("x", x);
        args.put("z", z);

        JsonNode result;
        try {
            result = pc.sendRequest("chunk_tile", args)
                    .get(RestSupport.RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            ctx.status(504);
            ctx.json(Map.of("error", "Tile request timed out."));
            return;
        } catch (java.io.IOException ioe) {
            throw new RestSupport.BridgeUnavailableException();
        } catch (Exception e) {
            ctx.status(502);
            ctx.json(Map.of("error", "Bridge tile RPC failed: " + e.getMessage()));
            return;
        }

        // Bridge response shape (per ChunkTileHandler): {dim, x, z,
        // width, height, pixels: <base64>}. We pass it through; the
        // client renders it with a palette helper. If the bridge
        // returned nothing, that means the chunk isn't loaded.
        if (result == null || result.isNull()) {
            ctx.status(404);
            ctx.json(Map.of("error", "Chunk not loaded.", "dim", dim, "x", x, "z", z));
            return;
        }
        ctx.contentType("application/json");
        ctx.result(result.toString());
    }
}
