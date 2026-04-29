package io.fathereye.panel.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon thread that reads inbound frames in a loop and forwards them to
 * the {@link TopicDispatcher}. Stops when the pipe closes or {@link #stop()}
 * is called.
 *
 * <p>Pnl-28: a {@link Runnable} {@code frameHook} is invoked on every
 * successfully-read frame, regardless of kind/topic. Used to feed the
 * watchdog so its heartbeat tracks <em>any</em> bridge activity, not just
 * the narrow {@code tps_topic} signal that previously drove it. If the
 * tps publisher specifically dies but other publishers keep running, the
 * watchdog now stays happy (which is correct: the bridge is alive).
 */
public final class PipeReader implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-PipeReader");

    private final PipeClient pipe;
    private final TopicDispatcher dispatcher;
    private final Runnable frameHook;
    private final Runnable onDisconnect;
    private volatile boolean stopped = false;
    private Thread thread;

    public PipeReader(PipeClient pipe, TopicDispatcher dispatcher) {
        this(pipe, dispatcher, null, null);
    }

    public PipeReader(PipeClient pipe, TopicDispatcher dispatcher, Runnable frameHook) {
        this(pipe, dispatcher, frameHook, null);
    }

    public PipeReader(PipeClient pipe, TopicDispatcher dispatcher,
                      Runnable frameHook, Runnable onDisconnect) {
        this.pipe = pipe;
        this.dispatcher = dispatcher;
        this.frameHook = frameHook;
        this.onDisconnect = onDisconnect;
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this, "FatherEye-PipeReader");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
    }

    @Override
    public void run() {
        try {
            while (!stopped && !pipe.isClosed()) {
                PipeEnvelope env = pipe.readEnvelope();
                if (frameHook != null) {
                    try { frameHook.run(); } catch (Throwable t) { /* heartbeat must not kill us */ }
                }
                dispatcher.dispatch(env);
            }
        } catch (Exception e) {
            if (!stopped) LOG.info("PipeReader exiting: {}", e.getMessage());
        } finally {
            // Pnl-33 (defensive): mark the pipe closed when the reader
            // exits for any reason. Without this, subsequent writes from
            // the FX thread (e.g. a chunk_tile sendRequest fired by a
            // user click on the map) would attempt a real WriteFile on
            // a dead socket — non-fatal, but ugly stack traces and a
            // perceptible UI hitch while the OS times out the write.
            // Pre-flighting via isClosed() now short-circuits cleanly.
            try { pipe.close(); } catch (Throwable ignored) {}
            if (onDisconnect != null) {
                try { onDisconnect.run(); } catch (Throwable t) { LOG.warn("disconnect hook failed", t); }
            }
        }
    }
}
