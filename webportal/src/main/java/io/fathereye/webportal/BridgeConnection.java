package io.fathereye.webportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.webportal.ipc.MarkerDiscovery;
import io.fathereye.webportal.ipc.PipeClient;
import io.fathereye.webportal.ipc.PipeCodecs;
import io.fathereye.webportal.ipc.PipeReader;
import io.fathereye.webportal.ipc.TopicDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Maintains the localhost TCP connection to the Father Eye bridge, mirroring
 * the panel's connect/handshake/subscribe sequence. It caches the latest
 * payload per topic so a newly-connected browser gets immediate state, fans
 * every snapshot/event out to registered listeners (the WebSocket sessions),
 * exposes {@link #sendRequest} for RPC pass-through, and auto-reconnects when
 * the bridge goes away.
 */
public final class BridgeConnection {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortal-Bridge");

    private static final String PORTAL_VERSION = "0.3.3-mac.1";

    // Same subscription set + delivery kinds the panel uses (App.java).
    // console_log is delivered as an Event; the rest as Snapshots.
    private static final String[] SNAPSHOT_TOPICS = {
            "tps_topic", "players_topic", "mobs_topic", "mods_impact_topic", "chunks_topic"
    };
    private static final String[] EVENT_TOPICS = {
            "console_log"
    };
    private static final String[] ALL_SUBSCRIBE = {
            "tps_topic", "console_log", "players_topic", "mobs_topic",
            "mods_impact_topic", "chunks_topic"
    };

    /** Listener receives (topic, payloadJsonString) for each snapshot/event. */
    public interface Listener {
        void onTopic(String kind, String topic, JsonNode payload);
        void onConnected(JsonNode welcomeJson);
        void onDisconnected();
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, JsonNode> latestSnapshots = new ConcurrentHashMap<>();

    private volatile PipeClient pipeClient;
    private volatile PipeReader pipeReader;
    private volatile JsonNode welcomeJson;
    private volatile boolean connected = false;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final java.util.concurrent.ScheduledExecutorService reconnect =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FatherEye-WebPortal-Reconnect");
                t.setDaemon(true);
                return t;
            });

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isConnected() { return connected; }
    public JsonNode welcome() { return welcomeJson; }

    /** Snapshot of the latest payload for a topic, or null if none yet. */
    public JsonNode latest(String topic) { return latestSnapshots.get(topic); }
    public Map<String, JsonNode> allLatest() { return new java.util.HashMap<>(latestSnapshots); }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        reconnect.scheduleWithFixedDelay(this::ensureConnected, 0, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        reconnect.shutdownNow();
        PipeClient pc = pipeClient;
        if (pc != null) pc.close();
    }

    private synchronized void ensureConnected() {
        if (connected) return;
        // Try EVERY live marker newest-first, not just the single newest one.
        // A marker whose PID is still alive but whose TCP socket is dead (e.g. a
        // server mid-shutdown, or a second instance that crashed without cleanup)
        // must NOT block us from reaching a healthy bridge listed in another
        // marker. Each failed attempt prunes the offending marker file so a
        // stale entry can never wedge the portal on subsequent ticks.
        List<MarkerDiscovery.Marker> markers = MarkerDiscovery.discover();
        if (markers.isEmpty()) {
            return; // no bridge running yet; try again on the next tick
        }
        for (MarkerDiscovery.Marker marker : markers) {
            if (tryMarker(marker)) {
                return; // connected; stop trying remaining markers
            }
        }
    }

    /**
     * Attempt a full connect+handshake+subscribe against one marker. Returns
     * true on success (state published, listeners notified). On any failure the
     * partial client is closed and the unreachable marker file is pruned so it
     * stops shadowing healthier markers on later reconnect ticks.
     */
    private boolean tryMarker(MarkerDiscovery.Marker marker) {
        // Defensively tear down any prior client/reader before opening a new one.
        // ensureConnected() only calls us while disconnected, but a reader thread
        // that exited via onDisconnect() may still be finishing; closing the old
        // handles here guarantees we never leak a socket or orphan a reader thread
        // when we publish the new connection below.
        PipeReader oldReader = this.pipeReader;
        PipeClient oldClient = this.pipeClient;
        if (oldReader != null) { try { oldReader.stop(); } catch (Throwable ignored) {} }
        if (oldClient != null) { try { oldClient.close(); } catch (Throwable ignored) {} }
        this.pipeReader = null;
        this.pipeClient = null;

        PipeClient pc = null;
        try {
            pc = PipeClient.forMarker(marker);
            pc.connect();
            PipeClient.WelcomeInfo info = pc.handshake(PORTAL_VERSION);

            TopicDispatcher dispatcher = new TopicDispatcher();
            for (String topic : SNAPSHOT_TOPICS) {
                final String t = topic;
                dispatcher.onSnapshot(t, payload -> handleTopic("Snapshot", t, payload));
            }
            for (String topic : EVENT_TOPICS) {
                final String t = topic;
                dispatcher.onEvent(t, payload -> handleTopic("Event", t, payload));
            }

            PipeReader reader = new PipeReader(pc, dispatcher, null, this::onDisconnect);
            reader.start();

            for (String topic : ALL_SUBSCRIBE) {
                pc.sendJson("Subscribe", topic, null);
            }

            this.pipeClient = pc;
            this.pipeReader = reader;
            this.welcomeJson = buildWelcomeJson(info, marker);
            this.connected = true;
            LOG.info("Connected to bridge {} (mc {}, forge {}) at {}",
                    info.bridgeVersion, info.mcVersion, info.forgeVersion, pc.address());
            for (Listener l : listeners) {
                try { l.onConnected(welcomeJson); } catch (Throwable t) { LOG.warn("listener onConnected failed", t); }
            }
            return true;
        } catch (Exception e) {
            String addr = marker.address != null ? marker.address : marker.pipeName;
            LOG.info("Bridge connect attempt to marker {} ({}) failed: {}; pruning marker",
                    addr, marker.markerPath, e.getMessage());
            if (pc != null) { pc.close(); }
            pruneMarker(marker);
            return false;
        }
    }

    /**
     * Delete an unreachable marker file, but ONLY when its advertising process is
     * no longer alive. This is the precise fix for the original wedge: a marker
     * whose PID died without cleanup (or whose socket is dead) used to shadow a
     * healthy bridge listed in another marker. We never prune a marker whose PID
     * is still running, so a genuinely-live-but-momentarily-busy bridge is left
     * intact and simply retried on the next 5 s tick. Best effort; a failure is
     * logged at debug and retried next tick. A live bridge that we prune by race
     * re-advertises on its next marker write, so even an over-eager prune self-heals.
     */
    private void pruneMarker(MarkerDiscovery.Marker marker) {
        if (marker.markerPath == null) return;
        if (marker.pid > 0) {
            java.util.Optional<ProcessHandle> ph = ProcessHandle.of(marker.pid);
            if (ph.isPresent() && ph.get().isAlive()) {
                // Process is alive but we could not complete the handshake right now
                // (e.g. server still starting, GC pause). Keep the marker and retry.
                LOG.debug("not pruning marker {}: pid {} still alive; will retry",
                        marker.markerPath, marker.pid);
                return;
            }
        }
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(marker.markerPath));
            LOG.debug("pruned dead marker {}", marker.markerPath);
        } catch (Exception ex) {
            LOG.debug("could not prune marker {}: {}", marker.markerPath, ex.getMessage());
        }
    }

    private void handleTopic(String kind, String topic, JsonNode payload) {
        // Snapshot/Delta payloads arrive wrapped as { seq, data } (ipc-protocol.md);
        // the panel reads payload.get("data") before touching topic fields. The
        // browser JS expects the unwrapped topic object directly, so unwrap here
        // once. Event payloads (e.g. console_log) are NOT wrapped: forward as-is.
        JsonNode out = payload;
        if (("Snapshot".equals(kind) || "Delta".equals(kind))
                && payload != null && payload.has("data")) {
            out = payload.get("data");
        }
        if (out != null) latestSnapshots.put(topic, out);
        for (Listener l : listeners) {
            try { l.onTopic(kind, topic, out); } catch (Throwable t) { LOG.warn("listener onTopic failed", t); }
        }
    }

    private void onDisconnect() {
        connected = false;
        latestSnapshots.clear();
        welcomeJson = null;
        LOG.info("Bridge disconnected; will reconnect.");
        for (Listener l : listeners) {
            try { l.onDisconnected(); } catch (Throwable t) { LOG.warn("listener onDisconnected failed", t); }
        }
    }

    /** RPC pass-through to the bridge. Returns the response payload future. */
    public CompletableFuture<JsonNode> sendRequest(String op, Map<String, Object> args) {
        PipeClient pc = pipeClient;
        if (pc == null || pc.isClosed()) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(new IOException("bridge not connected"));
            return f;
        }
        try {
            return pc.sendRequest(op, args);
        } catch (IOException e) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    private JsonNode buildWelcomeJson(PipeClient.WelcomeInfo info, MarkerDiscovery.Marker marker) {
        ObjectNode n = PipeCodecs.JSON.createObjectNode();
        n.put("bridgeVersion", info.bridgeVersion);
        n.put("mcVersion", info.mcVersion);
        n.put("forgeVersion", info.forgeVersion);
        n.put("instanceUuid", info.instanceUuid);
        n.put("serverHeapMaxBytes", info.serverHeapMaxBytes);
        n.put("serverDir", marker.serverDir);
        n.put("startedAtEpochMs", marker.startedAtEpochMs);
        n.set("dimensions", PipeCodecs.JSON.valueToTree(
                info.dimensions != null ? info.dimensions : new String[0]));
        n.set("modIds", PipeCodecs.JSON.valueToTree(
                info.modIds != null ? info.modIds : new String[0]));
        n.set("capabilities", PipeCodecs.JSON.valueToTree(
                info.capabilities != null ? info.capabilities : new String[0]));
        boolean arcanum = false;
        if (info.capabilities != null) {
            for (String c : info.capabilities) if ("arcanum".equals(c)) arcanum = true;
        }
        n.put("hasArcanum", arcanum);
        return n;
    }
}
