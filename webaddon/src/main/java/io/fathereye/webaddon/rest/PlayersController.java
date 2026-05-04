package io.fathereye.webaddon.rest;

import io.fathereye.panel.addon.PanelContext;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Player-action endpoints. Each is a thin wrapper around the matching
 * bridge RPC ({@code cmd_kick}, {@code cmd_ban}, {@code cmd_op},
 * {@code cmd_whitelist}, {@code cmd_tp}) with the player name carried
 * in the URL path and any extra args (reason, dim, coords, op flag) in
 * the JSON body.
 *
 * <p>The list endpoint reads the panel's most-recent {@code players_topic}
 * snapshot via the topic bridge cache; clients that want live updates
 * subscribe over WebSocket instead.
 */
public final class PlayersController {

    private final PanelContext context;

    public PlayersController(PanelContext context) {
        this.context = context;
    }

    /** Returns the panel's last cached players_topic snapshot (or
     *  {@code {"players": []}} if none yet). The caller is encouraged
     *  to subscribe over WebSocket for live updates. */
    public void list(Context ctx) {
        com.fasterxml.jackson.databind.JsonNode last = io.fathereye.webaddon.ws.TopicBridge.lastSnapshot("players_topic");
        if (last == null) {
            ctx.json(Map.of("players", java.util.List.of(),
                    "note", "No players_topic snapshot received yet."));
        } else {
            ctx.contentType("application/json");
            ctx.result(last.toString());
        }
    }

    public void kick(Context ctx) {
        String name = requirePlayerName(ctx);
        Map<String, Object> body = body(ctx);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("reason", asString(body.get("reason"), ""));
        RestSupport.doRpc(ctx, context, "cmd_kick", args);
    }

    public void ban(Context ctx) {
        String name = requirePlayerName(ctx);
        Map<String, Object> body = body(ctx);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("reason", asString(body.get("reason"), ""));
        RestSupport.doRpc(ctx, context, "cmd_ban", args);
    }

    public void op(Context ctx) {
        String name = requirePlayerName(ctx);
        Map<String, Object> body = body(ctx);
        Object grant = body.getOrDefault("op", body.get("value"));
        boolean shouldOp = grant instanceof Boolean ? (Boolean) grant
                : !"false".equalsIgnoreCase(String.valueOf(grant));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("op", shouldOp);
        RestSupport.doRpc(ctx, context, "cmd_op", args);
    }

    public void whitelist(Context ctx) {
        String name = requirePlayerName(ctx);
        Map<String, Object> body = body(ctx);
        Object addObj = body.getOrDefault("add", body.get("value"));
        boolean add = addObj instanceof Boolean ? (Boolean) addObj
                : !"false".equalsIgnoreCase(String.valueOf(addObj));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("add", add);
        RestSupport.doRpc(ctx, context, "cmd_whitelist", args);
    }

    public void teleport(Context ctx) {
        String name = requirePlayerName(ctx);
        Map<String, Object> body = body(ctx);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("x", asDouble(body.get("x")));
        args.put("y", asDouble(body.get("y")));
        args.put("z", asDouble(body.get("z")));
        Object dim = body.get("dim");
        if (dim instanceof String && !((String) dim).isEmpty()) {
            args.put("dim", dim);
        }
        RestSupport.doRpc(ctx, context, "cmd_tp", args);
    }

    private static String requirePlayerName(Context ctx) {
        String name = ctx.pathParam("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Player name is required.");
        }
        return name.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Context ctx) {
        try {
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            return b == null ? Map.of() : b;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String asString(Object o, String fallback) {
        return o instanceof String ? (String) o : fallback;
    }

    private static double asDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); }
            catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid number: " + o);
            }
        }
        throw new IllegalArgumentException("Coordinate is required.");
    }
}
