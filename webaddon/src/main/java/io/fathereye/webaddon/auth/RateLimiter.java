package io.fathereye.webaddon.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP login attempt throttle. Sliding window of failed-attempt
 * timestamps; once {@link #maxAttempts} failures occur within
 * {@link #windowMs}, the IP is locked out for {@link #lockoutMs}.
 *
 * <p>Successful logins clear an IP's history. The store self-prunes
 * entries that have neither failed nor succeeded within the window
 * to keep memory bounded under attack scenarios where attackers
 * cycle source IPs.
 */
public final class RateLimiter {

    private static final class Entry {
        final Deque<Long> failures = new ArrayDeque<>();
        long lockedUntilMs = 0L;
    }

    private final ConcurrentHashMap<String, Entry> byIp = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long windowMs;
    private final long lockoutMs;

    public RateLimiter(int maxAttempts, long windowMs, long lockoutMs) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.lockoutMs = lockoutMs;
    }

    /** Returns 0 if the IP is allowed to attempt; otherwise, the
     *  number of seconds remaining on the lockout. */
    public synchronized long secondsLockedOut(String ip) {
        Entry e = byIp.get(ip);
        if (e == null) return 0L;
        long now = System.currentTimeMillis();
        if (e.lockedUntilMs > now) return (e.lockedUntilMs - now) / 1000L + 1L;
        return 0L;
    }

    public synchronized void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        Entry e = byIp.computeIfAbsent(ip, k -> new Entry());
        // Drop failures outside the sliding window.
        while (!e.failures.isEmpty() && now - e.failures.peekFirst() > windowMs) {
            e.failures.pollFirst();
        }
        e.failures.addLast(now);
        if (e.failures.size() >= maxAttempts) {
            e.lockedUntilMs = now + lockoutMs;
            // Reset the count so a subsequent unlock gives the user
            // a fresh window of attempts before the next lockout.
            e.failures.clear();
        }
    }

    public synchronized void recordSuccess(String ip) {
        byIp.remove(ip);
    }

    /** Periodic cleanup hook; safe to call from any thread. Removes
     *  entries that are out of lockout AND have no recent failures. */
    public synchronized int sweep() {
        long now = System.currentTimeMillis();
        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<String, Entry>> it = byIp.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            while (!e.failures.isEmpty() && now - e.failures.peekFirst() > windowMs) {
                e.failures.pollFirst();
            }
            if (e.failures.isEmpty() && e.lockedUntilMs <= now) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}
