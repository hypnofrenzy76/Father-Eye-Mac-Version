package io.fathereye.panel.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.ipc.PipeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Pnl-77b/Web-07b + Pnl-78/Web-08: runs the Father Eye Web Portal in-process
 * inside the panel JVM, tied to the managed server's lifecycle.
 *
 * <p>The portal is started when the server reaches RUNNING and stopped when the
 * server stops or crashes (and on panel exit), so the browser console at
 * {@code http://<this-mac-tailscale-name>:8765} is available exactly while a
 * server is up.
 *
 * <h2>Why in-process and not a child {@code java -jar}</h2>
 * The packaged {@code Father Eye.app} ships a jlink runtime that jpackage
 * strips of its {@code bin/java} launcher (only {@code libjli.dylib} survives),
 * so there is no java binary to fork. The webportal module is therefore added
 * as a panel dependency and its {@link io.fathereye.webportal.WebPortalMain} is
 * driven directly on a background thread.
 *
 * <h2>Pnl-78/Web-08: FED mode (reuse the panel's bridge connection)</h2>
 * The bridge accepts only ONE IPC client at a time (its accept loop services a
 * single session and only loops back to {@code accept()} after that session
 * ends). The panel already holds that one session. If the portal opened its own
 * second TCP connection the OS would complete the TCP handshake but the bridge
 * would never service it, so the portal's handshake would block forever and the
 * browser panels would stay empty. The portal is therefore started in FED mode:
 * it opens ONLY its HTTP {@code ServerSocket} and the panel drives it via
 * {@link #onBridgeConnected}, {@link #onTopic} and {@link #onBridgeDisconnected},
 * while browser RPCs are routed through the panel's live {@link PipeClient} by
 * the {@code rpcDelegate} supplied to {@link #start}.
 *
 * <h2>Re-start across sessions</h2>
 * Each {@link #start} constructs a FRESH {@code WebPortalMain} and {@link #stop()}
 * discards it. This keeps every server session backed by a clean portal with no
 * leaked listeners.
 *
 * <p>All methods are synchronized; start/stop are idempotent.
 */
public final class WebPortalLauncher {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortalLauncher");

    /** Bind host: 0.0.0.0 so the Tailscale interface is reachable. */
    private static final String BIND_HOST = "0.0.0.0";
    /** Portal HTTP port (matches the WebPortalMain default). */
    private static final int PORT = 8765;

    /** The live portal instance, or null when stopped. */
    private io.fathereye.webportal.WebPortalMain portal;

    /**
     * Start the portal in FED mode if it is not already running. Idempotent.
     * Failures are logged and swallowed: the Minecraft server must keep running
     * even if the remote console cannot be brought up (e.g. port 8765 already
     * bound by a separately-launched portal).
     *
     * @param clientSupplier yields the panel's CURRENT live {@link PipeClient}
     *        at call time. Read per-RPC so reconnections (which reassign the
     *        panel's client) are followed transparently. May yield {@code null}
     *        if the panel is momentarily disconnected; the portal then reports
     *        "bridge not connected" to the browser for that RPC.
     */
    public synchronized void start(Supplier<PipeClient> clientSupplier) {
        if (portal != null) {
            LOG.info("Web portal already running on {}:{}; ignoring start.", BIND_HOST, PORT);
            return;
        }
        if (clientSupplier == null) {
            LOG.warn("Web portal start called without a PipeClient supplier; not starting.");
            return;
        }
        try {
            io.fathereye.webportal.WebPortalMain p = new io.fathereye.webportal.WebPortalMain();
            // RPC delegate: route every browser RPC through the panel's live
            // client, resolved fresh each call so a reconnect is followed.
            io.fathereye.webportal.BridgeConnection.RpcDelegate delegate =
                    (op, args) -> routeRpc(clientSupplier, op, args);
            p.startEmbeddedFed(BIND_HOST, PORT, delegate);
            this.portal = p;
            LOG.info("Web portal started (fed mode) on {}:{}.", BIND_HOST, PORT);
        } catch (Throwable t) {
            LOG.warn("Failed to start web portal on {}:{}: {}", BIND_HOST, PORT, t.toString(), t);
            this.portal = null;
        }
    }

    /** Resolve the current panel client and forward the RPC, or fail fast. */
    private static CompletableFuture<JsonNode> routeRpc(Supplier<PipeClient> clientSupplier,
                                                        String op, Map<String, Object> args) {
        PipeClient pc = clientSupplier.get();
        if (pc == null || pc.isClosed()) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(new java.io.IOException("bridge not connected"));
            return f;
        }
        try {
            return pc.sendRequest(op, args);
        } catch (Throwable t) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(t);
            return f;
        }
    }

    /**
     * Publish the bridge handshake to the portal so the browser populates the
     * connection banner and welcome-dependent UI (dimensions, mod list, Arcanum
     * tab). Safe to call when the portal is not running (no-op).
     */
    public synchronized void onBridgeConnected(JsonNode welcome) {
        io.fathereye.webportal.WebPortalMain p = this.portal;
        if (p == null) return;
        try { p.feedBridgeConnected(welcome); }
        catch (Throwable t) { LOG.warn("feedBridgeConnected failed", t); }
    }

    /**
     * Forward one topic frame to the portal. {@code payload} is the RAW bridge
     * payload the panel's TopicDispatcher delivered (Snapshot/Delta wrapped as
     * {@code { seq, data }}); the portal unwraps it for the browser exactly as
     * its own reader path does. Safe to call when not running (no-op). Never
     * throws into the dispatcher.
     */
    public synchronized void onTopic(String kind, String topic, JsonNode payload) {
        io.fathereye.webportal.WebPortalMain p = this.portal;
        if (p == null) return;
        try { p.feedBridgeTopic(kind, topic, payload); }
        catch (Throwable t) { LOG.warn("feedBridgeTopic failed for {}", topic, t); }
    }

    /**
     * Publish a bridge disconnect to the portal (server stopped/crashed or the
     * reader exited). Safe to call when not running (no-op).
     */
    public synchronized void onBridgeDisconnected() {
        io.fathereye.webportal.WebPortalMain p = this.portal;
        if (p == null) return;
        try { p.feedBridgeDisconnected(); }
        catch (Throwable t) { LOG.warn("feedBridgeDisconnected failed", t); }
    }

    /**
     * Stop the portal if running. Idempotent; safe to call when never started.
     */
    public synchronized void stop() {
        io.fathereye.webportal.WebPortalMain p = this.portal;
        if (p == null) return;
        try {
            p.stop();
            LOG.info("Web portal stopped.");
        } catch (Throwable t) {
            LOG.warn("Error stopping web portal: {}", t.toString(), t);
        } finally {
            this.portal = null;
        }
    }

    /** True if the portal is currently running. */
    public synchronized boolean isRunning() {
        return portal != null;
    }
}
