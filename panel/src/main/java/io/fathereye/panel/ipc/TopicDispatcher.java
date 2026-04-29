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

    public void onSnapshot(String topic, Consumer<JsonNode> handler) { snapshotHandlers.put(topic, handler); }
    public void onEvent(String topic, Consumer<JsonNode> handler) { eventHandlers.put(topic, handler); }

    public void dispatch(PipeEnvelope env) {
        if (env == null || env.kind == null) return;
        switch (env.kind) {
            case "Snapshot":
            case "Delta":
                Consumer<JsonNode> sh = snapshotHandlers.get(env.topic);
                if (sh != null) {
                    try { sh.accept(env.payload); } catch (Throwable t) { LOG.warn("snapshot handler error for {}", env.topic, t); }
                }
                break;
            case "Event":
                Consumer<JsonNode> eh = eventHandlers.get(env.topic);
                if (eh != null) {
                    try { eh.accept(env.payload); } catch (Throwable t) { LOG.warn("event handler error for {}", env.topic, t); }
                }
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
}
