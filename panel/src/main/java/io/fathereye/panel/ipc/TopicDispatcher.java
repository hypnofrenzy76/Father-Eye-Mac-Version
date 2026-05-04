package io.fathereye.panel.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Routes incoming Snapshot/Delta/Event frames by topic to registered
 * consumers. Consumers are typically panel UI components that update via
 * {@code Platform.runLater}.
 *
 * <p>Each topic has at most one <em>primary</em> handler (set via
 * {@link #onSnapshot(String, Consumer)} / {@link #onEvent(String, Consumer)})
 * plus any number of <em>taps</em> (added via
 * {@link #addSnapshotTap(String, Consumer)} /
 * {@link #addEventTap(String, Consumer)}). Taps fire after the primary
 * handler so an addon (e.g. the web addon's WebSocket bridge) can
 * observe the same payloads the JavaFX UI receives without having to
 * replace the primary handler. A tap exception is caught and logged so
 * one misbehaving consumer cannot starve others.
 */
public final class TopicDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Dispatcher");

    private final Map<String, Consumer<JsonNode>> snapshotHandlers = new HashMap<>();
    private final Map<String, Consumer<JsonNode>> eventHandlers = new HashMap<>();
    private final Map<String, List<Consumer<JsonNode>>> snapshotTaps = new HashMap<>();
    private final Map<String, List<Consumer<JsonNode>>> eventTaps = new HashMap<>();

    public void onSnapshot(String topic, Consumer<JsonNode> handler) { snapshotHandlers.put(topic, handler); }
    public void onEvent(String topic, Consumer<JsonNode> handler) { eventHandlers.put(topic, handler); }

    /** Add a non-destructive snapshot/delta listener. Multiple taps
     *  per topic are allowed; they fire after the primary handler in
     *  the order they were added. */
    public synchronized void addSnapshotTap(String topic, Consumer<JsonNode> tap) {
        snapshotTaps.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(tap);
    }

    /** Add a non-destructive event listener. */
    public synchronized void addEventTap(String topic, Consumer<JsonNode> tap) {
        eventTaps.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(tap);
    }

    public void dispatch(PipeEnvelope env) {
        if (env == null || env.kind == null) return;
        switch (env.kind) {
            case "Snapshot":
            case "Delta":
                Consumer<JsonNode> sh = snapshotHandlers.get(env.topic);
                if (sh != null) {
                    try { sh.accept(env.payload); } catch (Throwable t) { LOG.warn("snapshot handler error for {}", env.topic, t); }
                }
                fireTaps(snapshotTaps.get(env.topic), env.topic, env.payload, "snapshot");
                break;
            case "Event":
                Consumer<JsonNode> eh = eventHandlers.get(env.topic);
                if (eh != null) {
                    try { eh.accept(env.payload); } catch (Throwable t) { LOG.warn("event handler error for {}", env.topic, t); }
                }
                fireTaps(eventTaps.get(env.topic), env.topic, env.payload, "event");
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

    private static void fireTaps(List<Consumer<JsonNode>> taps,
                                 String topic, JsonNode payload, String kind) {
        if (taps == null || taps.isEmpty()) return;
        for (Consumer<JsonNode> t : taps) {
            try { t.accept(payload); }
            catch (Throwable th) { LOG.warn("{} tap error for {}", kind, topic, th); }
        }
    }
}
