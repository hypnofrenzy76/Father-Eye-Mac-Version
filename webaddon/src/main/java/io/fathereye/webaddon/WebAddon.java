package io.fathereye.webaddon;

import io.fathereye.panel.addon.PanelAddon;
import io.fathereye.panel.addon.PanelContext;
import io.fathereye.webaddon.auth.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service-loader entry point for the web addon. Reads
 * {@link io.fathereye.panel.config.AppConfig.WebAddon} and either:
 *
 * <ul>
 *   <li>logs an "addon disabled" notice and returns (when
 *       {@code enabled = false}, the default), or</li>
 *   <li>boots a {@link WebServer} bound on the configured port with
 *       TLS, single-user auth and the full REST + WebSocket API.</li>
 * </ul>
 *
 * <p>The addon never throws out of {@link #start(PanelContext)} — any
 * boot failure is logged and surfaced via {@link PanelContext#status(String)}
 * so the panel keeps running with no remote access rather than
 * crashing on startup. {@link #stop()} is idempotent and short.
 */
public final class WebAddon implements PanelAddon {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon");

    private volatile WebServer server;

    @Override
    public String id() { return "webaddon"; }

    @Override
    public void start(PanelContext context) {
        if (context == null) {
            LOG.warn("PanelContext is null; web addon will not start.");
            return;
        }
        try {
            io.fathereye.panel.config.AppConfig.WebAddon cfg = context.appConfig().webAddon;
            if (cfg == null || !cfg.enabled) {
                LOG.info("Web addon is disabled in AppConfig.webAddon (set webAddon.enabled=true in config.json to enable). Recommended deployment: Tailscale (no port-forward, no public exposure).");
                return;
            }
            // First-run credential bootstrap: when enabled with no
            // credentials yet stored, generate a random one-time
            // password the user can sign in with, then change via the
            // web Config tab. This avoids the chicken-and-egg of
            // "addon enabled but no way to log in".
            CredentialStore creds = new CredentialStore();
            if (!creds.isConfigured()) {
                String tempPassword = randomPassword();
                creds.set("admin", tempPassword.toCharArray());
                String banner =
                        "First-run web addon credentials generated:\n"
                                + "    username: admin\n"
                                + "    password: " + tempPassword + "\n"
                                + "Change them in the web Config tab after first sign-in. "
                                + "Stored hash at " + creds.filePath();
                LOG.info("\n========\n{}\n========", banner);
                context.log("Web addon: first-run credentials -> admin / " + tempPassword + " (change after sign-in).");
            }
            WebServer s = new WebServer(context);
            s.start();
            this.server = s;
            String url = "https://" + (cfg.bindAddress.equals("0.0.0.0") ? "<this-mac>" : cfg.bindAddress)
                    + ":" + cfg.port;
            context.status("Web addon listening on " + url);
            context.log("Web addon started on " + url + " (login required). Recommended access: via Tailscale tailnet IP.");
            LOG.info("Web addon started: bind={} port={}", cfg.bindAddress, cfg.port);
        } catch (Throwable t) {
            LOG.error("Web addon failed to start", t);
            try { context.status("Web addon failed to start: " + t.getMessage()); }
            catch (Throwable ignored) {}
        }
    }

    @Override
    public void stop() {
        WebServer s = server;
        if (s == null) return;
        server = null;
        try { s.stop(); }
        catch (Throwable t) { LOG.warn("Web addon stop failed: {}", t.getMessage()); }
    }

    private static String randomPassword() {
        // 12 random bytes -> 16 base64url chars. Memorable enough for a
        // one-time password the user types from the panel banner.
        byte[] buf = new byte[12];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
