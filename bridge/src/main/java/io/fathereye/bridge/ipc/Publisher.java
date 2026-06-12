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
 *
 * <p>Brg-24 (2026-06-12): players/mobs/chunks no longer collect world
 * state from this thread. Spark profile IzzU6Fus2J traced 32.7% of
 * server-thread time to WorldStateCollector.collectChunks()'s
 * off-thread getChunk calls (each one marshals onto the tick thread
 * via supplyAsync(..).join() and can block in managedBlock during
 * generation). These topics now serialise immutable snapshots that
 * {@link io.fathereye.bridge.profiler.TickStateMirror} rebuilds once
 * per second ON the tick thread under an explicit budget; this thread
 * does JSON encoding and socket I/O only.
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
            // Brg-24: snapshot built on the tick thread by TickStateMirror;
            // null until the first rebuild (~1 s after server start).
            Object snap = io.fathereye.bridge.profiler.TickStateMirror.playersSnapshot();
            if (snap == null) return;
            s.sendSnapshot(Topics.PLAYERS, snap, playersSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.PLAYERS, t.toString());
        }
    }

    private void tickMobs() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.MOBS)) return;
        try {
            Object snap = io.fathereye.bridge.profiler.TickStateMirror.mobsSnapshot();
            if (snap == null) return;
            s.sendSnapshot(Topics.MOBS, snap, mobsSeq.incrementAndGet());
        } catch (Throwable t) {
            LOG.warn("publish {} failed: {}", Topics.MOBS, t.toString());
        }
    }

    private void tickChunks() {
        IpcSession s = activeSession;
        if (s == null || !s.subscriptions().isSubscribed(Topics.CHUNKS)) return;
        try {
            Object snap = io.fathereye.bridge.profiler.TickStateMirror.chunksSnapshot();
            if (snap == null) return;
            s.sendSnapshot(Topics.CHUNKS, snap, chunksSeq.incrementAndGet());
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
