package io.fathereye.webportal.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon thread that reads inbound frames in a loop and forwards them to the
 * {@link TopicDispatcher}. Copied verbatim from the panel ipc package.
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
            try { pipe.close(); } catch (Throwable ignored) {}
            if (onDisconnect != null) {
                try { onDisconnect.run(); } catch (Throwable t) { LOG.warn("disconnect hook failed", t); }
            }
        }
    }
}
