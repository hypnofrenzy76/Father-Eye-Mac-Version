package io.fathereye.webaddon.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fathereye.panel.addon.PanelContext;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.webaddon.auth.CredentialStore;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Read/write the panel's {@link AppConfig} from the browser. The PUT
 * accepts a partial config and merges it onto the current state via
 * Jackson's {@code readerForUpdating} so a single field can change
 * without resending the whole document.
 *
 * <p>The credential update endpoint sets/replaces the web addon's
 * username and password — the new password takes effect immediately
 * for new login attempts; existing sessions stay valid until they
 * idle out.
 */
public final class ConfigController {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-Config");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PanelContext context;
    private final CredentialStore credentials;

    public ConfigController(PanelContext context, CredentialStore credentials) {
        this.context = context;
        this.credentials = credentials;
    }

    public void get(Context ctx) {
        ctx.json(context.appConfig());
    }

    public void put(Context ctx) {
        try {
            JSON.readerForUpdating(context.appConfig()).readValue(ctx.body());
            context.saveConfig();
            ctx.json(Map.of("ok", true));
        } catch (Throwable t) {
            LOG.warn("config PUT failed: {}", t.getMessage());
            ctx.status(400);
            ctx.json(Map.of("error", "Invalid config: " + t.getMessage()));
        }
    }

    public void updateCredentials(Context ctx) {
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) {
            ctx.status(400);
            ctx.json(Map.of("error", "Invalid JSON body."));
            return;
        }
        if (body == null) body = Map.of();
        String username = asString(body.get("username"));
        String newPassword = asString(body.get("newPassword"));
        String currentPassword = asString(body.get("currentPassword"));
        if (username == null || newPassword == null
                || username.isBlank() || newPassword.length() < 8) {
            ctx.status(400);
            ctx.json(Map.of("error", "Username and newPassword (>=8 chars) required."));
            return;
        }
        // Require the current password unless this is the first-time
        // configuration (no credentials stored yet, in which case the
        // panel's Web Access tab is the only viable setter).
        if (credentials.isConfigured()) {
            if (currentPassword == null || currentPassword.isBlank()) {
                ctx.status(401);
                ctx.json(Map.of("error", "Current password is required to change credentials."));
                return;
            }
            char[] cp = currentPassword.toCharArray();
            boolean ok = credentials.verify(credentials.username(), cp);
            if (!ok) {
                ctx.status(401);
                ctx.json(Map.of("error", "Current password is incorrect."));
                return;
            }
        }
        try {
            credentials.set(username.trim(), newPassword.toCharArray());
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(Map.of("error", e.getMessage()));
            return;
        }
        ctx.json(Map.of("ok", true));
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
