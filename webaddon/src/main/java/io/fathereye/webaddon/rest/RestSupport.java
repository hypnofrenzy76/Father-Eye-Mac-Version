package io.fathereye.webaddon.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.addon.PanelContext;
import io.fathereye.panel.ipc.PipeClient;
import io.javalin.http.Context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared helpers for REST controllers. Mostly thin wrappers around
 * {@link PipeClient#sendRequest(String, Map)} that surface a sensible
 * timeout, JSON response, and "bridge not connected" error to the
 * browser instead of stalling forever.
 */
final class RestSupport {

    /** RPCs typically complete in under 200 ms (running a Brigadier
     *  command synchronously on the MC thread). 8 s is a generous
     *  ceiling that catches "bridge wedged" without making the user
     *  feel the request hung. */
    static final long RPC_TIMEOUT_MS = 8_000L;

    private RestSupport() {}

    /** Get the live PipeClient or throw a structured 503 if the bridge
     *  isn't up yet. */
    static PipeClient pipe(PanelContext context) {
        PipeClient pc = context.pipeClient();
        if (pc == null || pc.isClosed()) {
            throw new BridgeUnavailableException();
        }
        return pc;
    }

    /** Run a bridge RPC and respond with the bridge's JSON payload
     *  verbatim (or {@code {"ok":true}} when the bridge returns null). */
    static void doRpc(Context ctx, PanelContext panelCtx, String op, Map<String, Object> args) {
        PipeClient pc = pipe(panelCtx);
        CompletableFuture<JsonNode> f;
        try {
            f = pc.sendRequest(op, args);
        } catch (java.io.IOException ioe) {
            throw new BridgeUnavailableException();
        }
        JsonNode result;
        try {
            result = f.get(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            ctx.status(504);
            ctx.json(Map.of("error", "Bridge RPC '" + op + "' timed out after "
                    + (RPC_TIMEOUT_MS / 1000) + " s."));
            return;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ctx.status(503);
            ctx.json(Map.of("error", "Interrupted while waiting for bridge RPC."));
            return;
        } catch (java.util.concurrent.ExecutionException ee) {
            ctx.status(502);
            ctx.json(Map.of("error", "Bridge RPC '" + op + "' failed: "
                    + (ee.getCause() == null ? ee.getMessage() : ee.getCause().getMessage())));
            return;
        }
        if (result == null) {
            ctx.json(Map.of("ok", true));
        } else {
            ctx.contentType("application/json");
            ctx.result(result.toString());
        }
    }

    /** Sentinel: maps to a 503 in the WebServer's exception handler. */
    static final class BridgeUnavailableException extends RuntimeException {
        BridgeUnavailableException() { super("Bridge is not connected. Start the server and wait for the handshake."); }
    }
}
