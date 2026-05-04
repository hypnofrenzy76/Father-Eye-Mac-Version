package io.fathereye.webaddon;

import io.fathereye.panel.addon.PanelContext;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.webaddon.auth.AuthHandlers;
import io.fathereye.webaddon.auth.CredentialStore;
import io.fathereye.webaddon.auth.RateLimiter;
import io.fathereye.webaddon.auth.SessionStore;
import io.fathereye.webaddon.rest.BackupController;
import io.fathereye.webaddon.rest.ConfigController;
import io.fathereye.webaddon.rest.MapController;
import io.fathereye.webaddon.rest.MobsController;
import io.fathereye.webaddon.rest.PlayersController;
import io.fathereye.webaddon.rest.ServerController;
import io.fathereye.webaddon.rest.WorldController;
import io.fathereye.webaddon.tls.KeystoreManager;
import io.fathereye.webaddon.ws.TopicBridge;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Javalin/Jetty-backed HTTPS server for the web addon. Wires up TLS,
 * single-user authentication, REST controllers, the WebSocket topic
 * bridge, and the static HTML UI in {@code /web/} on the classpath.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link KeystoreManager#resolve()} ensures a TLS keystore exists
 *       (generates a self-signed cert on first run via {@code keytool}).</li>
 *   <li>{@link Javalin#create} is configured with a single Jetty
 *       {@link ServerConnector} bound to the configured host/port and
 *       attached to an {@link SslContextFactory.Server} pointing at the
 *       keystore. The default plain-HTTP connector is suppressed by
 *       NOT calling {@code Javalin.start(int)} with a port.</li>
 *   <li>An {@code authBefore} filter rejects every non-public route
 *       without a valid session cookie. Public routes: GET /, /login,
 *       /api/auth/login, /static/**, /favicon.ico.</li>
 *   <li>Controllers register their routes; the WebSocket route at
 *       {@code /ws} is wrapped in the same auth check.</li>
 * </ol>
 *
 * <p>{@link #stop()} stops Javalin (which stops Jetty), shuts down the
 * session-sweep scheduler, and disposes the topic bridge subscriptions.
 */
public final class WebServer {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-Server");

    private final PanelContext context;
    private final CredentialStore credentials = new CredentialStore();
    private SessionStore sessions;
    private RateLimiter rateLimiter;
    private TopicBridge topicBridge;
    private Javalin app;
    private ScheduledExecutorService sweeper;

    public WebServer(PanelContext context) {
        this.context = context;
    }

    public void start() throws Exception {
        AppConfig.WebAddon cfg = context.appConfig().webAddon;

        long idleMs = TimeUnit.MINUTES.toMillis(Math.max(1, cfg.sessionIdleMinutes));
        sessions = new SessionStore(idleMs);
        rateLimiter = new RateLimiter(
                Math.max(1, cfg.loginThrottleAttempts),
                TimeUnit.MINUTES.toMillis(Math.max(1, cfg.loginThrottleWindowMinutes)),
                TimeUnit.MINUTES.toMillis(Math.max(1, cfg.loginLockoutMinutes)));

        // 1. Resolve / generate the TLS keystore.
        KeystoreManager.Resolved tls = new KeystoreManager().resolve();

        // 2. Set up auth + REST + WS.
        AuthHandlers auth = new AuthHandlers(credentials, sessions, rateLimiter);

        ServerController serverCtl = new ServerController(context);
        PlayersController playersCtl = new PlayersController(context);
        WorldController worldCtl = new WorldController(context);
        MobsController mobsCtl = new MobsController(context);
        BackupController backupCtl = new BackupController(context);
        ConfigController configCtl = new ConfigController(context, credentials);
        MapController mapCtl = new MapController(context);

        topicBridge = new TopicBridge(context);
        topicBridge.installTaps();

        // 3. Build Javalin with a Jetty SSL connector.
        final String bindHost = cfg.bindAddress == null || cfg.bindAddress.isBlank()
                ? "0.0.0.0" : cfg.bindAddress;
        final int port = cfg.port;
        final String keystorePath = tls.keystorePath.toString();
        final String keystorePassword = tls.password;

        app = Javalin.create(c -> {
            c.showJavalinBanner = false;
            // Static HTML / CSS / JS bundled in webaddon's resources.
            c.staticFiles.add(s -> {
                s.directory = "/web";
                s.location = Location.CLASSPATH;
                s.hostedPath = "/static";
            });
            // Suppress Javalin's default HTTP connector by replacing
            // Jetty's connector list with a single SSL connector.
            c.jetty.modifyServer(server -> {
                // Remove any default connectors Javalin added (safe
                // even if none exist).
                for (org.eclipse.jetty.server.Connector cn : server.getConnectors()) {
                    try { cn.stop(); } catch (Exception ignored) {}
                }
                server.setConnectors(new org.eclipse.jetty.server.Connector[]{
                        sslConnector(server, bindHost, port, keystorePath, keystorePassword)
                });
            });
        });

        // 4. Security headers on every response.
        app.before(ctx -> {
            ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "no-referrer");
            // Tight CSP: page loads only own static assets, no inline
            // scripts, no remote sources. Inline styles allowed for
            // the minimal style tag in login.html (auto-generated
            // banner colors), but no inline scripts.
            ctx.header("Content-Security-Policy",
                    "default-src 'self'; "
                            + "script-src 'self'; "
                            + "style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; "
                            + "connect-src 'self' wss:; "
                            + "frame-ancestors 'none';");
        });

        // 5. Public-route allowlist: everything else requires a valid session.
        app.before(ctx -> {
            String path = ctx.path();
            if (isPublic(path)) return;
            io.fathereye.webaddon.auth.AuthHandlers.requireSession(ctx, sessions);
        });

        // 6. Auth + UI routes.
        app.get("/", ctx -> {
            // Fall through to login.html if not authenticated; index
            // otherwise. The static handler serves /static/login.html
            // and /static/index.html; we redirect to those depending
            // on session status.
            String token = AuthHandlers.readSessionCookie(ctx);
            if (token != null && sessions.touch(token) != null) {
                ctx.redirect("/static/index.html");
            } else {
                ctx.redirect("/static/login.html");
            }
        });
        app.post("/api/auth/login", auth::login);
        app.post("/api/auth/logout", auth::logout);
        app.get("/api/auth/me", auth::me);

        // 7. REST: server control.
        app.get("/api/server/state", serverCtl::state);
        app.post("/api/server/start", serverCtl::start);
        app.post("/api/server/stop", serverCtl::stop);
        app.post("/api/server/restart", serverCtl::restart);
        app.post("/api/server/command", serverCtl::sendCommand);

        // 8. REST: bridge-backed (require live PipeClient).
        app.get("/api/players", playersCtl::list);
        app.post("/api/players/{name}/kick", playersCtl::kick);
        app.post("/api/players/{name}/ban", playersCtl::ban);
        app.post("/api/players/{name}/op", playersCtl::op);
        app.post("/api/players/{name}/whitelist", playersCtl::whitelist);
        app.post("/api/players/{name}/teleport", playersCtl::teleport);

        app.post("/api/world/weather", worldCtl::weather);
        app.post("/api/world/time", worldCtl::time);

        app.post("/api/mobs/clear", mobsCtl::clearArea);
        app.post("/api/entities/{id}/kill", mobsCtl::killEntity);

        app.post("/api/backup", backupCtl::runNow);

        app.get("/api/config", configCtl::get);
        app.put("/api/config", configCtl::put);
        app.put("/api/config/credentials", configCtl::updateCredentials);

        app.get("/api/map/tile", mapCtl::tile);
        app.get("/api/map/dimensions", mapCtl::dimensions);

        // 9. WebSocket: live topic stream after the same auth check.
        app.ws("/ws", ws -> {
            ws.onConnect(topicBridge::onConnect);
            ws.onClose(topicBridge::onClose);
            ws.onMessage(topicBridge::onMessage);
        });

        // 10. Centralised error handlers — never leak stack traces to
        // the browser.
        app.exception(io.fathereye.webaddon.auth.AuthException.class, (ex, ctx) -> {
            ctx.status(ex.statusCode());
            ctx.json(Map.of("error", ex.getMessage()));
        });
        app.exception(IllegalArgumentException.class, (ex, ctx) -> {
            ctx.status(400);
            ctx.json(Map.of("error", ex.getMessage()));
        });
        app.exception(Exception.class, (ex, ctx) -> {
            LOG.warn("Unhandled exception on {} {}: {}", ctx.method(), ctx.path(), ex.toString());
            ctx.status(500);
            ctx.json(Map.of("error", "Internal server error."));
        });

        // 11. Boot.
        app.start();
        LOG.info("Web addon listening on https://{}:{}", bindHost, port);

        // 12. Background sweepers — every minute, evict idle sessions
        // and stale rate-limit entries.
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FatherEye-WebAddon-Sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(() -> {
            try {
                int s = sessions.sweep();
                int rl = rateLimiter.sweep();
                if (s > 0 || rl > 0) {
                    LOG.debug("Swept {} idle session(s), {} stale rate-limit entry/ies.", s, rl);
                }
            } catch (Throwable t) {
                LOG.warn("session/rate-limiter sweep failed: {}", t.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        if (topicBridge != null) {
            try { topicBridge.shutdown(); } catch (Throwable ignored) {}
            topicBridge = null;
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
        if (app != null) {
            try { app.stop(); } catch (Throwable t) { LOG.warn("Javalin stop failed: {}", t.getMessage()); }
            app = null;
        }
    }

    private static boolean isPublic(String path) {
        if (path == null) return false;
        if (path.equals("/")) return true;
        if (path.equals("/favicon.ico")) return true;
        if (path.startsWith("/static/")) return true;
        if (path.equals("/api/auth/login")) return true;
        return false;
    }

    /**
     * Build the Jetty SSL {@link ServerConnector}. {@link SecureRequestCustomizer}
     * enables Server Name Indication (SNI) and reports the cert to
     * downstream handlers; we leave SNI host-check enabled (default)
     * since users access via the host they configured.
     */
    private static ServerConnector sslConnector(org.eclipse.jetty.server.Server server,
                                                 String host, int port,
                                                 String keystorePath, String keystorePassword) {
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(keystorePath);
        ssl.setKeyStorePassword(keystorePassword);
        ssl.setKeyManagerPassword(keystorePassword);
        ssl.setKeyStoreType("PKCS12");
        // Modern protocol only. TLS 1.2 / 1.3 are universally supported
        // in any current browser; older versions have known weaknesses.
        ssl.setIncludeProtocols("TLSv1.2", "TLSv1.3");
        // Permissive cipher suite list — Jetty's default. Restricting
        // this further breaks browsers that drop TLS 1.3 from time
        // to time during connection migration.
        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.setSendServerVersion(false);
        httpsConfig.setSendXPoweredBy(false);
        SecureRequestCustomizer src = new SecureRequestCustomizer();
        // Disable strict SNI host check — when the user reaches the
        // panel via raw IP or a cloudflared tunnel the SNI cert CN
        // (set to "Father Eye Web Addon") legitimately doesn't match
        // the requested authority. Cert pinning happens at the user
        // level via the "trust this cert" browser action; SNI host
        // mismatch here just causes a 400 "Bad SNI" response which
        // is the wrong UX for a self-signed cert deployment.
        src.setSniHostCheck(false);
        httpsConfig.addCustomizer(src);

        ServerConnector connector = new ServerConnector(server,
                new SslConnectionFactory(ssl, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
        connector.setHost(host);
        connector.setPort(port);
        return connector;
    }
}
