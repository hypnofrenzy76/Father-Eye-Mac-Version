package io.fathereye.webaddon.rest;

import io.fathereye.panel.addon.PanelContext;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * World-state controls: weather and time. Maps to the bridge's
 * {@code cmd_weather} (clear/rain/thunder) and {@code cmd_time}
 * (numeric tick or one of "day", "night", "noon", "midnight") RPCs.
 */
public final class WorldController {

    private static final java.util.Set<String> WEATHER_VALUES =
            java.util.Set.of("clear", "rain", "thunder");

    private final PanelContext context;

    public WorldController(PanelContext context) {
        this.context = context;
    }

    public void weather(Context ctx) {
        Map<String, Object> body = body(ctx);
        Object w = body.get("weather");
        if (!(w instanceof String) || !WEATHER_VALUES.contains(((String) w).toLowerCase())) {
            throw new IllegalArgumentException(
                    "'weather' must be one of: " + WEATHER_VALUES);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("weather", ((String) w).toLowerCase());
        Object durationTicks = body.get("durationTicks");
        if (durationTicks instanceof Number) {
            args.put("durationTicks", ((Number) durationTicks).longValue());
        }
        RestSupport.doRpc(ctx, context, "cmd_weather", args);
    }

    public void time(Context ctx) {
        Map<String, Object> body = body(ctx);
        Object t = body.get("time");
        Map<String, Object> args = new LinkedHashMap<>();
        if (t instanceof Number) {
            args.put("time", ((Number) t).longValue());
        } else if (t instanceof String) {
            String s = ((String) t).toLowerCase();
            switch (s) {
                case "day":
                case "night":
                case "noon":
                case "midnight":
                    args.put("time", s);
                    break;
                default:
                    try { args.put("time", Long.parseLong(s)); }
                    catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException(
                                "'time' must be a tick number or 'day'/'night'/'noon'/'midnight'.");
                    }
            }
        } else {
            throw new IllegalArgumentException("'time' is required.");
        }
        RestSupport.doRpc(ctx, context, "cmd_time", args);
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
}
