package io.fathereye.bridge.ipc;

import io.fathereye.bridge.jmx.JmxSampler;
import io.fathereye.bridge.profiler.TpsCollector;
import io.fathereye.bridge.topic.Topics;
import io.fathereye.bridge.topic.TpsSnapshot;
import io.fathereye.bridge.util.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic publisher: scheduled at multiple cadences, drains live state and
 * sends Snapshot frames to whichever {@link IpcSession} is currently
 * connected. M5 wires only the {@link Topics#TPS} cadence.
 */
public final class Publisher {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Publisher");

    private final ScheduledExecutorService scheduler;
    private final JmxSampler jmx;
    private final AtomicLong tpsSeq = new AtomicLong(0);
    private final AtomicLong playersSeq = new AtomicLong(0);
    private final AtomicLong mobsSeq = new AtomicLong(0);
    private final AtomicLong chunksSeq = new AtomicLong(0);
    private final AtomicLong modsImpactSeq = new AtomicLong(0);
    private volatile IpcSession activeSession;

    public Publisher() {
        this.jmx = new JmxSampler();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FatherEye-Publisher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tickTps, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::tickPlayers, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::tickMobs, 2, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::tickChunks, 2, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::tickModsImpact, 2, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void bind(IpcSession session) { this.activeSession = session; }
    public void unbind(IpcSession session) {
        if (this.activeSession == session) this.activeSession = null;
    }

    /** Push a log line as an Event on the console_log topic. */
    public void publishLogLine(io.fathereye.bridge.topic.LogLine line) {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.CONSOLE_LOG)) return;
        try {
            s.sendEvent(Topics.CONSOLE_LOG, line);
        } catch (Throwable ignored) {
            // best-effort; pipe may have broken between check and send
        }
    }

    private void tickTps() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.TPS)) return;
        try {
            TpsSnapshot snap = new TpsSnapshot();
            snap.timestampMs = System.currentTimeMillis();
            TpsCollector.fill(snap);
            jmx.fill(snap);
            s.sendSnapshot(Topics.TPS, snap, tpsSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.TPS, t.toString());
        }
    }

    private void tickPlayers() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.PLAYERS)) return;
        try {
            s.sendSnapshot(Topics.PLAYERS,
                    io.fathereye.bridge.profiler.WorldStateCollector.collectPlayers(),
                    playersSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.PLAYERS, t.toString());
        }
    }

    private void tickMobs() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.MOBS)) return;
        try {
            s.sendSnapshot(Topics.MOBS,
                    io.fathereye.bridge.profiler.WorldStateCollector.collectMobs(),
                    mobsSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.MOBS, t.toString());
        }
    }

    private void tickChunks() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.CHUNKS)) return;
        try {
            s.sendSnapshot(Topics.CHUNKS,
                    io.fathereye.bridge.profiler.WorldStateCollector.collectChunks(),
                    chunksSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.CHUNKS, t.toString());
        }
    }

    private void tickModsImpact() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.MODS_IMPACT)) return;
        try {
            s.sendSnapshot(Topics.MODS_IMPACT,
                    io.fathereye.bridge.profiler.ModsImpactCollector.collect(),
                    modsImpactSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.MODS_IMPACT, t.toString());
        }
    }

    /** Convenience constants for callers. */
    public static int protocolVersion() { return Constants.PROTOCOL_VERSION; }
}
