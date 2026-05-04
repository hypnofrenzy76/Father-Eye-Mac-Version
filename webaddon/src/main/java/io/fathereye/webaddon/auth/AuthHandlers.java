package io.fathereye.webaddon.auth;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP handlers for {@code POST /api/auth/login}, {@code POST /api/auth/logout}
 * and {@code GET /api/auth/me}. Also exposes
 * {@link #requireSession(Context, SessionStore)} for the WebServer's
 * before-filter to call on every protected route.
 *
 * <p>Cookie attributes: {@code HttpOnly} (no JS access), {@code Secure}
 * (HTTPS only), {@code SameSite=Strict} (no cross-site CSRF).
 */
public final class AuthHandlers {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-Auth");
    public static final String COOKIE_NAME = "fe_sess";

    private final CredentialStore credentials;
    private final SessionStore sessions;
    private final RateLimiter rateLimiter;

    public AuthHandlers(CredentialStore credentials, SessionStore sessions, RateLimiter rateLimiter) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    public void login(Context ctx) {
        String ip = clientIp(ctx);
        long lockedFor = rateLimiter.secondsLockedOut(ip);
        if (lockedFor > 0) {
            ctx.status(429);
            ctx.json(Map.of(
                    "error", "Too many failed attempts.",
                    "retryAfterSeconds", lockedFor));
            return;
        }
        if (!credentials.isConfigured()) {
            // Don't reveal that credentials are unset — the panel UI
            // tells the user to configure first; an unauthenticated
            // browser request gets a generic failure.
            ctx.status(401);
            ctx.json(Map.of("error", "Login is not available. Set credentials in the panel's Web Access tab."));
            return;
        }
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(Map.of("error", "Invalid request body."));
            return;
        }
        if (body == null) body = Map.of();
        String username = asString(body.get("username"));
        String password = asString(body.get("password"));
        if (username == null || password == null
                || username.isBlank() || password.isBlank()) {
            ctx.status(400);
            ctx.json(Map.of("error", "Username and password are required."));
            return;
        }
        char[] pwChars = password.toCharArray();
        boolean ok;
        try {
            ok = credentials.verify(username.trim(), pwChars);
        } finally {
            // CredentialStore.verify already wipes its arg; this is
            // belt-and-suspenders in case the impl ever changes.
            java.util.Arrays.fill(pwChars, '\0');
        }
        if (!ok) {
            rateLimiter.recordFailure(ip);
            LOG.info("Failed login from {} (user '{}')", ip, username);
            // 401 with a generic message; client distinguishes only
            // "wrong" from "locked out" via the 429 path above.
            ctx.status(401);
            ctx.json(Map.of("error", "Invalid username or password."));
            return;
        }
        rateLimiter.recordSuccess(ip);
        SessionStore.Session session = sessions.create(username.trim());
        Cookie cookie = new Cookie(COOKIE_NAME, session.token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setSameSite(SameSite.STRICT);
        cookie.setPath("/");
        // Make the cookie session-scoped (no Max-Age) so closing the
        // browser drops it; SessionStore enforces idle timeout
        // independently for long-lived browser windows.
        ctx.cookie(cookie);
        LOG.info("Successful login: user '{}' from {}", username, ip);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("username", session.username);
        resp.put("idleTimeoutMinutes", sessions.idleTimeoutMs() / 60000L);
        ctx.json(resp);
    }

    public void logout(Context ctx) {
        String token = readSessionCookie(ctx);
        if (token != null) sessions.invalidate(token);
        Cookie expire = new Cookie(COOKIE_NAME, "");
        expire.setHttpOnly(true);
        expire.setSecure(true);
        expire.setSameSite(SameSite.STRICT);
        expire.setPath("/");
        expire.setMaxAge(0);
        ctx.cookie(expire);
        ctx.json(Map.of("ok", true));
    }

    public void me(Context ctx) {
        String token = readSessionCookie(ctx);
        SessionStore.Session s = token == null ? null : sessions.touch(token);
        if (s == null) {
            ctx.status(401);
            ctx.json(Map.of("authenticated", false));
            return;
        }
        ctx.json(Map.of(
                "authenticated", true,
                "username", s.username,
                "createdAtMs", s.createdAtMs));
    }

    /**
     * Called by the WebServer's before-filter. Throws {@link AuthException}
     * with status 401 when the session is missing or expired; the
     * WebServer's exception mapper formats the JSON response.
     */
    public static void requireSession(Context ctx, SessionStore sessions) {
        String token = readSessionCookie(ctx);
        if (token == null) {
            throw new AuthException(401, "Not authenticated.");
        }
        SessionStore.Session s = sessions.touch(token);
        if (s == null) {
            throw new AuthException(401, "Session expired.");
        }
        ctx.attribute("session", s);
    }

    public static String readSessionCookie(Context ctx) {
        return ctx.cookie(COOKIE_NAME);
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /**
     * Best-effort client IP. Honors {@code X-Forwarded-For} when the
     * panel is run behind a reverse proxy (Caddy / Cloudflare Tunnel /
     * nginx); otherwise falls back to the remote socket. Operators
     * who want to disable header trust because they're exposing
     * Jetty directly should know that direct exposure is supported,
     * and the header is only used when present.
     */
    private static String clientIp(Context ctx) {
        String fwd = ctx.header("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return ctx.ip();
    }
}
