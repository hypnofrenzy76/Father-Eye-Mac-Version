package io.fathereye.webaddon.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory session store. Sessions are opaque random tokens (32 bytes,
 * base64url-encoded) keyed by themselves; values are
 * {@link Session} structs holding the username, creation time, and last-
 * activity time.
 *
 * <p>Sessions live in process memory only — on panel restart every
 * client must log in again, which is fine for a single-user admin
 * panel and avoids the need for a persistent session table.
 *
 * <p>{@link #sweep()} is invoked on a timer by {@link io.fathereye.webaddon.WebServer}
 * to evict sessions that have been idle longer than
 * {@link #idleTimeoutMs}.
 */
public final class SessionStore {

    public static final class Session {
        public final String token;
        public final String username;
        public final long createdAtMs;
        volatile long lastSeenMs;
        Session(String token, String username, long now) {
            this.token = token;
            this.username = username;
            this.createdAtMs = now;
            this.lastSeenMs = now;
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final long idleTimeoutMs;

    public SessionStore(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public Session create(String username) {
        byte[] buf = new byte[32];
        rng.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        Session s = new Session(token, username, System.currentTimeMillis());
        sessions.put(token, s);
        return s;
    }

    /**
     * Look up a session by token, refreshing its last-seen time on
     * every successful lookup so active users don't get evicted.
     * Returns null for unknown OR idle-expired tokens (idle tokens
     * are removed as a side effect).
     */
    public Session touch(String token) {
        if (token == null || token.isEmpty()) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now - s.lastSeenMs > idleTimeoutMs) {
            sessions.remove(token, s);
            return null;
        }
        s.lastSeenMs = now;
        return s;
    }

    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    public int sweep() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (java.util.Map.Entry<String, Session> e : sessions.entrySet()) {
            if (now - e.getValue().lastSeenMs > idleTimeoutMs) {
                sessions.remove(e.getKey(), e.getValue());
                removed++;
            }
        }
        return removed;
    }

    public int size() { return sessions.size(); }
    public long idleTimeoutMs() { return idleTimeoutMs; }
}
