package io.fathereye.webaddon.rest;

import io.fathereye.panel.addon.PanelContext;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mob/entity actions: clear hostile mobs in a rectangular area, or
 * remove a single entity by id. Mirrors {@code cmd_clearMobsInArea}
 * and {@code cmd_killEntity} on the bridge.
 */
public final class MobsController {

    private final PanelContext context;

    public MobsController(PanelContext context) {
        this.context = context;
    }

    public void clearArea(Context ctx) {
        Map<String, Object> body = body(ctx);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("x1", asNumber(body.get("x1"), "x1"));
        args.put("z1", asNumber(body.get("z1"), "z1"));
        args.put("x2", asNumber(body.get("x2"), "x2"));
        args.put("z2", asNumber(body.get("z2"), "z2"));
        Object dim = body.get("dim");
        if (dim instanceof String && !((String) dim).isEmpty()) {
            args.put("dim", dim);
        }
        Object hostileOnly = body.get("hostileOnly");
        args.put("hostileOnly", hostileOnly instanceof Boolean ? hostileOnly : true);
        RestSupport.doRpc(ctx, context, "cmd_clearMobsInArea", args);
    }

    public void killEntity(Context ctx) {
        String idStr = ctx.pathParam("id");
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            args.put("entityId", Integer.parseInt(idStr));
        } catch (NumberFormatException nfe) {
            // The bridge accepts either a numeric id or a UUID string;
            // pass through whatever the client gave us.
            args.put("entityId", idStr);
        }
        RestSupport.doRpc(ctx, context, "cmd_killEntity", args);
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

    private static double asNumber(Object o, String name) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); }
            catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                        "'" + name + "' must be a number.");
            }
        }
        throw new IllegalArgumentException("'" + name + "' is required.");
    }
}
