package io.fathereye.panel.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Once-daily restart at a configurable local time. Re-arms after each fire.
 */
public final class RestartScheduler {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-RestartScheduler");

    private final Runnable restartAction;
    private final ScheduledExecutorService scheduler;
    private volatile LocalTime restartAt;

    public RestartScheduler(Runnable restartAction) {
        this.restartAction = restartAction;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FatherEye-RestartScheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void scheduleDailyAt(LocalTime time) {
        this.restartAt = time;
        if (time == null) return;
        long nextDelaySec = secondsUntilNext(time);
        scheduler.schedule(this::fireAndRearm, nextDelaySec, TimeUnit.SECONDS);
        LOG.info("Daily restart scheduled at {} (next in {}s)", time, nextDelaySec);
    }

    public void stop() { scheduler.shutdownNow(); }

    private void fireAndRearm() {
        try {
            LOG.info("Scheduled daily restart firing.");
            restartAction.run();
        } catch (Throwable t) {
            LOG.error("Scheduled restart failed", t);
        }
        LocalTime t = restartAt;
        if (t != null) scheduleDailyAt(t);
    }

    private static long secondsUntilNext(LocalTime t) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime next = now.toLocalDate().atTime(t);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Math.max(1, Duration.between(now, next).getSeconds());
    }
}
