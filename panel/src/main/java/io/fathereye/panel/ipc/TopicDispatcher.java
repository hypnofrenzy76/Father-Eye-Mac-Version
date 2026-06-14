package io.fathereye.panel.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Routes incoming Snapshot/Delta/Event frames by topic to registered
 * consumers. Consumers are typically panel UI components that update via
 * {@code Platform.runLater}.
 */
public final class TopicDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Dispatcher");

    private final Map<String, Consumer<JsonNode>> snapshotHandlers = new HashMap<>();
    private final Map<String, Consumer<JsonNode>> eventHandlers = new HashMap<>();

    /**
     * Pnl-78/Web-08: optional out-of-band tap invoked for EVERY inbound
     * Snapshot/Delta/Event frame, in addition to the per-topic handler.
     * Receives {@code (kind, topic, rawPayload)} where rawPayload is the
     * unmodified bridge payload (Snapshot/Delta still wrapped as
     * {@code { seq, data }}). Used to fan the panel's single live bridge
     * feed out to the in-process Web Portal without the portal opening a
     * second IPC client. Null when no portal is attached. Failures in the
     * tap are caught here so they can never disrupt panel UI dispatch.
     */
    private volatile FrameTap frameTap;

    /** Receives every inbound topic frame: {@code (kind, topic, rawPayload)}. */
    public interface FrameTap {
        void onFrame(String kind, String topic, JsonNode rawPayload);
    }

    public void onSnapshot(String topic, Consumer<JsonNode> handler) { snapshotHandlers.put(topic, handler); }
    public void onEvent(String topic, Consumer<JsonNode> handler) { eventHandlers.put(topic, handler); }

    /** Pnl-78/Web-08: install (or clear, with null) the all-frames tap. */
    public void setFrameTap(FrameTap tap) { this.frameTap = tap; }

    public void dispatch(PipeEnvelope env) {
        if (env == null || env.kind == null) return;
        switch (env.kind) {
            case "Snapshot":
            case "Delta":
                Consumer<JsonNode> sh = snapshotHandlers.get(env.topic);
                if (sh != null) {
                    try { sh.accept(env.payload); } catch (Throwable t) { LOG.warn("snapshot handler error for {}", env.topic, t); }
                }
                tap(env.kind, env.topic, env.payload);
                break;
            case "Event":
                Consumer<JsonNode> eh = eventHandlers.get(env.topic);
                if (eh != null) {
                    try { eh.accept(env.payload); } catch (Throwable t) { LOG.warn("event handler error for {}", env.topic, t); }
                }
                tap(env.kind, env.topic, env.payload);
                break;
            case "Welcome":
            case "Response":
            case "Error":
                // Handled by the requester via correlation id; not here.
                break;
            default:
                LOG.debug("unhandled inbound kind: {}", env.kind);
        }
    }

    /** Forward one frame to the all-frames tap, swallowing any tap error so
     *  a misbehaving portal feed can never break panel UI dispatch. */
    private void tap(String kind, String topic, JsonNode rawPayload) {
        FrameTap t = this.frameTap;
        if (t == null) return;
        try { t.onFrame(kind, topic, rawPayload); }
        catch (Throwable th) { LOG.warn("frame tap error for {}", topic, th); }
    }
}
