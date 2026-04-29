package io.fathereye.panel.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches the server's tick heartbeat. If no Snapshot/Event has arrived
 * within {@link #noHeartbeatMs}, and the process is alive, the watchdog
 * logs and (optionally) triggers a restart through the supplied
 * {@link Action} callbacks.
 *
 * <p>Disarmed for the first {@link #warmupMs} after start (cold-boot spike
 * mitigation per the plan).
 *
 * <p><b>Pnl-28 (deferred items, 2026-04-26):</b>
 * <ul>
 *   <li><b>Clock skew</b>: timestamps use {@link System#nanoTime} instead
 *       of {@link System#currentTimeMillis} so an NTP correction or
 *       suspend/resume mid-session can't mis-fire the threshold.</li>
 *   <li><b>UI surface</b>: {@link #lastHeartbeatAgeMs} is exposed so the
 *       toolbar can show an "X s since last heartbeat" badge.</li>
 *   <li><b>Broader heartbeat signal</b>: {@link #heartbeat()} is now
 *       called from <em>any</em> inbound IPC frame (snapshot or event),
 *       not just {@code tps_topic}. The watchdog still represents
 *       "bridge is sending us bytes" — but the previous narrow signal
 *       (only TPS) would mis-fire if the bridge's tps publisher died
 *       while every other publisher still ran.</li>
 * </ul>
 */
public final class Watchdog {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Watchdog");
    private static final long NEVER_HEARTBEAT = Long.MIN_VALUE;

    public interface Action {
        boolean processIsAlive();
        void restartServer();
    }

    private final long noHeartbeatNanos;
    private final long warmupNanos;
    private final ScheduledExecutorService scheduler;
    private final Action action;

    /**
     * Stored as {@link System#nanoTime} reading. Sentinel
     * {@link #NEVER_HEARTBEAT} indicates "no heartbeat has ever arrived"
     * (post-construction, pre-arm).
     */
    private final AtomicLong lastHeartbeatNanos = new AtomicLong(NEVER_HEARTBEAT);
    private volatile long armedAfterNanos = Long.MAX_VALUE;
    private volatile boolean enabled = false;

    public Watchdog(long noHeartbeatMs, long warmupMs, Action action) {
        this.noHeartbeatNanos = noHeartbeatMs * 1_000_000L;
        this.warmupNanos = warmupMs * 1_000_000L;
        this.action = action;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FatherEye-Watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 5, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void heartbeat() {
        lastHeartbeatNanos.set(System.nanoTime());
    }

    /**
     * Milliseconds since the last received heartbeat. Returns -1 if no
     * heartbeat has ever been received OR the watchdog is currently
     * disarmed (the UI should treat -1 as "n/a"). Read concurrently from
     * the FX thread for the toolbar badge.
     */
    public long lastHeartbeatAgeMs() {
        if (!enabled) return -1L;
        long last = lastHeartbeatNanos.get();
        if (last == NEVER_HEARTBEAT) return -1L;
        return (System.nanoTime() - last) / 1_000_000L;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Arm the watchdog. Disarms automatically when the server is stopped.
     *
     * <p>Resets the heartbeat clock so the no-heartbeat window starts at
     * arm-time, not panel-construction-time. Write order matters: set the
     * timestamps FIRST, then publish {@code enabled = true} last so the
     * scheduler thread can't observe a fresh {@code enabled} with a stale
     * armed-after.
     */
    public void arm() {
        long now = System.nanoTime();
        lastHeartbeatNanos.set(now);
        armedAfterNanos = now + warmupNanos;
        enabled = true;
        LOG.info("Watchdog armed (warmup {} ms)", warmupNanos / 1_000_000L);
    }

    public void disarm() { enabled = false; }

    private void tick() {
        if (!enabled) return;
        long now = System.nanoTime();
        if (now < armedAfterNanos) return;
        if (!action.processIsAlive()) return;

        // Measure idle time from the LATER of (last heartbeat, end of
        // warmup). The warmup itself counts as a known-good window.
        long ref = Math.max(lastHeartbeatNanos.get(), armedAfterNanos);
        long sinceNanos = now - ref;
        if (sinceNanos > noHeartbeatNanos) {
            long sinceMs = sinceNanos / 1_000_000L;
            long thresholdMs = noHeartbeatNanos / 1_000_000L;
            LOG.warn("No bridge heartbeat for {} ms (threshold {} ms).", sinceMs, thresholdMs);
            try {
                action.restartServer();
            } catch (Throwable t) {
                LOG.error("Watchdog restart action failed", t);
            }
            // Reset both clocks for the post-restart window.
            long after = System.nanoTime();
            lastHeartbeatNanos.set(after);
            armedAfterNanos = after + warmupNanos;
        }
    }
}
