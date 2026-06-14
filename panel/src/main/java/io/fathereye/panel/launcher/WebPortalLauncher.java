package io.fathereye.panel.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pnl-77b/Web-07b: runs the Father Eye Web Portal in-process inside the panel
 * JVM, tied to the managed server's lifecycle.
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
 * driven directly on a background thread. The portal opens only a localhost TCP
 * connection to the already-running bridge plus its own HTTP {@code ServerSocket},
 * both of which behave identically whether hosted in their own JVM or the panel's.
 *
 * <h2>Re-start across sessions</h2>
 * {@code WebPortalMain}'s bridge connection is single-shot (its reconnect
 * executor is shut down on stop and cannot be revived), so each
 * {@link #start()} constructs a FRESH {@code WebPortalMain} and {@link #stop()}
 * discards it. This keeps every server session backed by a clean portal with no
 * leaked reconnect threads or duplicated listeners.
 *
 * <p>All methods are synchronized; start/stop are idempotent.
 */
public final class WebPortalLauncher {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortalLauncher");

    /** Bind host: 0.0.0.0 so the Tailscale interface is reachable. */
    private static final String BIND_HOST = "0.0.0.0";
    /** Portal HTTP port (matches the WebPortalMain default). */
    private static final int PORT = 8765;

    /** The live portal instance, or null when stopped. Type-erased to Object
     *  so a reflective fallback could be slotted in later without API churn;
     *  in practice it is always a {@code WebPortalMain}. */
    private io.fathereye.webportal.WebPortalMain portal;

    /**
     * Start the portal if it is not already running. Idempotent. Failures are
     * logged and swallowed: the Minecraft server must keep running even if the
     * remote console cannot be brought up (e.g. port 8765 already bound by a
     * separately-launched portal).
     */
    public synchronized void start() {
        if (portal != null) {
            LOG.info("Web portal already running on {}:{}; ignoring start.", BIND_HOST, PORT);
            return;
        }
        try {
            io.fathereye.webportal.WebPortalMain p = new io.fathereye.webportal.WebPortalMain();
            p.startEmbedded(BIND_HOST, PORT);
            this.portal = p;
            LOG.info("Web portal started on {}:{}.", BIND_HOST, PORT);
        } catch (Throwable t) {
            // Most likely a BindException if something already holds 8765, or an
            // IOException creating the auth/config dir. Never let this abort the
            // server lifecycle; surface it in panel.log for diagnosis.
            LOG.warn("Failed to start web portal on {}:{}: {}", BIND_HOST, PORT, t.toString(), t);
            this.portal = null;
        }
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
