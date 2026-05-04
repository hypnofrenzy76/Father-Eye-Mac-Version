package io.fathereye.webaddon.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fathereye.panel.addon.PanelContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Live data plane for the web addon. Subscribes (via the panel's
 * {@link io.fathereye.panel.ipc.TopicDispatcher}) as a non-destructive
 * tap on every topic the JavaFX panel itself receives, caches the most
 * recent snapshot per topic, and forwards JSON frames to every
 * connected browser WebSocket.
 *
 * <p>Wire protocol (server -> browser):
 * <pre>{@code
 *   { "type": "snapshot", "topic": "tps_topic", "payload": {...} }
 *   { "type": "event",    "topic": "console_log", "payload": {...} }
 *   { "type": "hello",    "subscribed": ["tps_topic", ...] }
 *   { "type": "pong" }
 * }</pre>
 *
 * <p>Wire protocol (browser -> server):
 * <pre>{@code
 *   { "type": "ping" }
 *   { "type": "subscribe", "topics": ["tps_topic", ...] }   // optional filter
 * }</pre>
 *
 * <p>By default a connecting browser receives every cached snapshot on
 * connect (so the UI fills in immediately) and every subsequent live
 * frame. Filtering is opt-in: send a "subscribe" message with the
 * desired topic set.
 */
public final class TopicBridge {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-WS");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Snapshot/Delta topics the panel auto-subscribes to (mirrors
     *  {@code App.AUTO_SUBSCRIBE}). */
    private static final String[] SNAPSHOT_TOPICS = {
            "tps_topic", "console_log", "players_topic",
            "mobs_topic", "mods_impact_topic", "chunks_topic"
    };

    /** Event topics worth surfacing in the browser UI. */
    private static final String[] EVENT_TOPICS = {
            "console_log",
            "event_chat", "event_join", "event_leave",
            "event_crash", "event_alert", "event_shutdown"
    };

    /** Last seen snapshot per topic, surfaced via {@link #lastSnapshot(String)}
     *  for REST endpoints (e.g. GET /api/players) that want a single-shot
     *  read without forcing the client to use WebSocket. */
    private static final ConcurrentHashMap<String, JsonNode> LAST_SNAPSHOT =
            new ConcurrentHashMap<>();

    private final PanelContext context;
    private final CopyOnWriteArraySet<WsConnectContext> sessions = new CopyOnWriteArraySet<>();
    /** Per-session topic filter; absent means "all". */
    private final ConcurrentHashMap<String, Set<String>> filters = new ConcurrentHashMap<>();

    public TopicBridge(PanelContext context) {
        this.context = context;
    }

    /** Public accessor for REST: the most recent snapshot the bridge
     *  has emitted for {@code topic}, or {@code null} if none yet. */
    public static JsonNode lastSnapshot(String topic) {
        return LAST_SNAPSHOT.get(topic);
    }

    /**
     * Register snapshot/event taps on the panel's topic dispatcher.
     * Each tap caches the latest payload and broadcasts it to all
     * currently-connected WebSocket sessions. Called once during
     * {@link io.fathereye.webaddon.WebServer#start()}.
     */
    public void installTaps() {
        for (String t : SNAPSHOT_TOPICS) {
            final String topic = t;
            context.onTopicSnapshot(topic, payload -> {
                LAST_SNAPSHOT.put(topic, payload);
                broadcast("snapshot", topic, payload);
            });
        }
        for (String t : EVENT_TOPICS) {
            final String topic = t;
            context.onTopicEvent(topic, payload -> broadcast("event", topic, payload));
        }
        LOG.info("TopicBridge taps installed for {} snapshot topics, {} event topics.",
                SNAPSHOT_TOPICS.length, EVENT_TOPICS.length);
    }

    public void onConnect(WsConnectContext ctx) {
        // Auth was checked by the WebServer's before-filter on the
        // /ws upgrade request; the session cookie is honored at the
        // HTTP handshake. Once the WS is open, the connection is
        // authorised for its lifetime.
        ctx.enableAutomaticPings(20, java.util.concurrent.TimeUnit.SECONDS);
        sessions.add(ctx);
        LOG.info("WS client connected from {} (now {} live sessions)",
                ctx.session.getRemoteAddress(), sessions.size());
        // Hello + replay last snapshot per topic so the client paints
        // immediately rather than waiting up to 1s for the next push.
        try {
            ctx.send(JSON.writeValueAsString(Map.of(
                    "type", "hello",
                    "subscribed", java.util.Arrays.asList(SNAPSHOT_TOPICS))));
        } catch (Throwable t) {
            LOG.warn("WS hello send failed: {}", t.getMessage());
        }
        for (Map.Entry<String, JsonNode> e : LAST_SNAPSHOT.entrySet()) {
            sendOne(ctx, "snapshot", e.getKey(), e.getValue());
        }
    }

    public void onClose(WsCloseContext ctx) {
        sessions.remove(ctx);
        filters.remove(ctx.sessionId());
        LOG.debug("WS client disconnected ({} live sessions)", sessions.size());
    }

    public void onMessage(WsMessageContext ctx) {
        try {
            JsonNode m = JSON.readTree(ctx.message());
            String type = m.path("type").asText("");
            switch (type) {
                case "ping":
                    ctx.send(JSON.writeValueAsString(Map.of("type", "pong")));
                    break;
                case "subscribe":
                    JsonNode topics = m.path("topics");
                    if (topics.isArray()) {
                        Set<String> set = new java.util.HashSet<>();
                        topics.forEach(node -> set.add(node.asText("")));
                        set.remove("");
                        if (set.isEmpty()) filters.remove(ctx.sessionId());
                        else filters.put(ctx.sessionId(), set);
                    }
                    break;
                case "unsubscribe_all":
                    filters.remove(ctx.sessionId());
                    break;
                default:
                    // ignore
            }
        } catch (Throwable t) {
            LOG.debug("WS bad message: {}", t.getMessage());
        }
    }

    public void shutdown() {
        for (WsConnectContext c : sessions) {
            try { c.session.close(1001, "Server shutting down"); }
            catch (Throwable ignored) {}
        }
        sessions.clear();
        filters.clear();
    }

    private void broadcast(String type, String topic, JsonNode payload) {
        if (sessions.isEmpty()) return;
        String json;
        try {
            json = JSON.writeValueAsString(Map.of(
                    "type", type,
                    "topic", topic,
                    "payload", payload));
        } catch (Throwable t) {
            LOG.debug("WS broadcast serialize failed for {}: {}", topic, t.getMessage());
            return;
        }
        for (WsConnectContext c : sessions) {
            Set<String> filter = filters.get(c.sessionId());
            if (filter != null && !filter.contains(topic)) continue;
            try { c.send(json); }
            catch (Throwable t) {
                // Client likely disconnected mid-write; let onClose
                // remove it on the next heartbeat.
                LOG.debug("WS send failed: {}", t.getMessage());
            }
        }
    }

    private void sendOne(WsConnectContext c, String type, String topic, JsonNode payload) {
        try {
            c.send(JSON.writeValueAsString(Map.of(
                    "type", type,
                    "topic", topic,
                    "payload", payload)));
        } catch (Throwable t) {
            LOG.debug("WS replay send failed: {}", t.getMessage());
        }
    }
}
