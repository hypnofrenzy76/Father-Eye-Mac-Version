package io.fathereye.webaddon.rest;

import io.fathereye.panel.addon.PanelContext;
import io.fathereye.panel.launcher.ServerLauncher;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server lifecycle endpoints. Each delegates to the panel's existing
 * {@link ServerLauncher} so a request from the browser produces the
 * exact same effect as clicking the matching button in the JavaFX
 * panel.
 */
public final class ServerController {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-Server");

    private final PanelContext context;

    public ServerController(PanelContext context) {
        this.context = context;
    }

    public void state(Context ctx) {
        ServerLauncher l = context.launcher();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", l.state().name());
        out.put("bridgeConnected", context.pipeClient() != null && !context.pipeClient().isClosed());
        ctx.json(out);
    }

    public void start(Context ctx) {
        ServerLauncher l = context.launcher();
        if (l.state() == ServerLauncher.State.RUNNING || l.state() == ServerLauncher.State.STARTING) {
            ctx.status(409);
            ctx.json(Map.of("error", "Server is already " + l.state().name() + "."));
            return;
        }
        // Run on a worker thread; ServerLauncher.start can block up to
        // 15 s polling session.lock (see panel/.../ServerLauncher.java).
        Thread t = new Thread(() -> {
            try {
                l.start();
                context.status("Web addon: server start requested.");
            } catch (Throwable th) {
                LOG.warn("REST start failed", th);
                context.status("Web addon: server start failed: " + th.getMessage());
            }
        }, "FatherEye-WebAddon-RestStart");
        t.setDaemon(true);
        t.start();
        ctx.json(Map.of("ok", true, "state", "STARTING"));
    }

    public void stop(Context ctx) {
        ServerLauncher l = context.launcher();
        if (l.state() == ServerLauncher.State.STOPPED || l.state() == ServerLauncher.State.CRASHED) {
            ctx.status(409);
            ctx.json(Map.of("error", "Server is already " + l.state().name() + "."));
            return;
        }
        Thread t = new Thread(() -> {
            try {
                l.stop();
                context.status("Web addon: server stopped.");
            } catch (Throwable th) {
                LOG.warn("REST stop failed", th);
                context.status("Web addon: server stop failed: " + th.getMessage());
            }
        }, "FatherEye-WebAddon-RestStop");
        t.setDaemon(true);
        t.start();
        ctx.json(Map.of("ok", true, "state", "STOPPING"));
    }

    public void restart(Context ctx) {
        ServerLauncher l = context.launcher();
        Thread t = new Thread(() -> {
            try {
                l.restart();
                context.status("Web addon: server restart requested.");
            } catch (Throwable th) {
                LOG.warn("REST restart failed", th);
                context.status("Web addon: server restart failed: " + th.getMessage());
            }
        }, "FatherEye-WebAddon-RestRestart");
        t.setDaemon(true);
        t.start();
        ctx.json(Map.of("ok", true, "state", "RESTARTING"));
    }

    /**
     * Send a console command. Mirrors the panel's own console behavior:
     * if the bridge is up, route through {@code cmd_run} so the result
     * flows through unified RPC; otherwise fall back to writing to the
     * server JVM's stdin.
     */
    public void sendCommand(Context ctx) {
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) {
            ctx.status(400);
            ctx.json(Map.of("error", "Invalid JSON body."));
            return;
        }
        if (body == null) body = Map.of();
        Object raw = body.get("command");
        if (!(raw instanceof String) || ((String) raw).isBlank()) {
            ctx.status(400);
            ctx.json(Map.of("error", "'command' must be a non-empty string."));
            return;
        }
        String command = ((String) raw).trim();

        try {
            if (context.pipeClient() != null && !context.pipeClient().isClosed()) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("command", command);
                RestSupport.doRpc(ctx, context, "cmd_run", args);
            } else {
                context.launcher().sendCommand(command);
                ctx.json(Map.of("ok", true, "via", "stdin"));
            }
        } catch (java.io.IOException ioe) {
            ctx.status(500);
            ctx.json(Map.of("error", "Send command failed: " + ioe.getMessage()));
        }
    }
}
