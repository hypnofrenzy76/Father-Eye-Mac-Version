package io.fathereye.bridge.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.bridge.rpc.RpcDispatcher;
import io.fathereye.bridge.util.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One panel connection. Owns the read/dispatch loop and exposes a
 * {@link #sendSnapshot} entry point for the {@link Publisher} to push
 * topic data. Writes are serialized through {@code synchronized(this)}
 * because multiple producer threads (publisher, future log appender) may
 * call sendXxx concurrently while the reader loop reads inbound frames.
 */
public final class IpcSession {

    private static final Logger LOG = LogManager.getLogger("FatherEye-IpcSession");

    private final TransportServer pipe;
    private final UUID instanceUuid;
    private final ServerInfoSupplier info;
    private final AtomicLong outboundIdSeq = new AtomicLong(0);
    private final Subscriptions subscriptions = new Subscriptions();
    private final Publisher publisher;
    private final RpcDispatcher rpc;

    private boolean handshakeComplete = false;

    public IpcSession(TransportServer pipe, UUID instanceUuid, ServerInfoSupplier info,
                      Publisher publisher, RpcDispatcher rpc) {
        this.pipe = pipe;
        this.instanceUuid = instanceUuid;
        this.info = info;
        this.publisher = publisher;
        this.rpc = rpc;
    }

    public Subscriptions subscriptions() { return subscriptions; }

    /** Drive the connection until pipe break. */
    public void run() {
        LOG.info("IPC session started on {}", pipe.address());
        publisher.bind(this);
        try {
            while (!pipe.isClosed()) {
                byte[] header = pipe.readExact(5);
                int len = Frame.decodeHeaderLength(header);
                byte codec = Frame.decodeHeaderCodec(header);
                byte[] payload = pipe.readExact(len);
                handleFrame(codec, payload);
            }
        } catch (IOException ioe) {
            LOG.info("IPC session ended: {}", ioe.getMessage());
        } catch (Throwable t) {
            LOG.error("IPC session crashed", t);
        } finally {
            publisher.unbind(this);
            subscriptions.clear();
        }
    }

    private void handleFrame(byte codec, byte[] payload) throws IOException {
        if (codec != Frame.CODEC_JSON) {
            sendError("bad_codec", "Control plane requires JSON codec; got " + codec, null);
            return;
        }
        IpcEnvelope env = Codecs.decodeJson(payload, IpcEnvelope.class);
        if (env.kind == null) {
            sendError("bad_kind", "missing kind", env.id);
            return;
        }
        switch (env.kind) {
            case "Hello":
                handleHello(env);
                break;
            case "Subscribe":
                if (!handshakeComplete) { sendError("not_handshaken", "must Hello first", env.id); return; }
                if (env.topic == null) { sendError("bad_topic", "Subscribe missing topic", env.id); return; }
                if (!Subscriptions.isKnownTopic(env.topic)) { sendError("unknown_topic", env.topic, env.id); return; }
                subscriptions.subscribe(env.topic);
                LOG.debug("Subscribed: {}", env.topic);
                break;
            case "Unsubscribe":
                if (!handshakeComplete) { sendError("not_handshaken", "must Hello first", env.id); return; }
                if (env.topic == null) { sendError("bad_topic", "Unsubscribe missing topic", env.id); return; }
                subscriptions.unsubscribe(env.topic);
                break;
            case "Request":
                if (!handshakeComplete) { sendError("not_handshaken", "must Hello first", env.id); return; }
                handleRequest(env);
                break;
            default:
                sendError("unknown_kind", "unknown kind " + env.kind, env.id);
        }
    }

    private void handleRequest(IpcEnvelope env) {
        final long requestId = env.id;
        String op = env.payload == null ? null : env.payload.path("op").asText(null);
        JsonNode args = env.payload == null ? null : env.payload.get("args");
        if (op == null) { sendError("bad_op", "Request missing op", requestId); return; }
        rpc.dispatch(op, args).whenComplete((result, err) -> {
            try {
                if (err != null) {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("ok", false);
                    resp.put("error", err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage());
                    sendResponse(requestId, resp);
                } else {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("ok", true);
                    resp.put("result", result);
                    sendResponse(requestId, resp);
                }
            } catch (IOException ioe) {
                // Brg-23 (2026-04-27): surface swallowed sendResponse
                // failures. The previous code silently dropped these,
                // which let stuck-pipe-write situations strand panel
                // futures forever (pendingResponses entry never
                // completes, panel's `requested` set leaks). Logging
                // at WARN with the requestId at least gives the user
                // a debug trail when the loading bar plateaus.
                LOG.warn("sendResponse failed for request id={}: {}", requestId, ioe.toString());
            }
        });
    }

    public synchronized void sendResponse(long correlationId, Object payload) throws IOException {
        JsonNode node = payload == null ? null : Codecs.JSON.valueToTree(payload);
        IpcEnvelope env = new IpcEnvelope(Constants.PROTOCOL_VERSION, correlationId, "Response", null, node);
        byte[] bytes = Codecs.encodeJson(env);
        pipe.writeAll(new Frame(Frame.CODEC_JSON, bytes).encode());
    }

    private void handleHello(IpcEnvelope env) throws IOException {
        IpcEnvelope.HelloPayload hello = env.payload == null
                ? new IpcEnvelope.HelloPayload()
                : Codecs.JSON.treeToValue(env.payload, IpcEnvelope.HelloPayload.class);
        if (hello.protocolVersion < Constants.PROTOCOL_VERSION) {
            sendError("protocol_too_old",
                    "panel protocolVersion " + hello.protocolVersion +
                            " < bridge " + Constants.PROTOCOL_VERSION, env.id);
            return;
        }
        IpcEnvelope.WelcomePayload w = new IpcEnvelope.WelcomePayload();
        w.protocolVersion = Constants.PROTOCOL_VERSION;
        w.bridgeVersion = Constants.BRIDGE_VERSION;
        w.mcVersion = info.mcVersion();
        w.forgeVersion = info.forgeVersion();
        w.instanceUuid = instanceUuid.toString();
        w.capabilities = info.capabilities();
        w.dimensions = info.dimensions();
        w.mods = info.mods();
        w.serverHeapMaxBytes = Runtime.getRuntime().maxMemory();
        sendJson("Welcome", null, w, nextId());
        handshakeComplete = true;
        LOG.info("Handshake complete (panel={} v={})", hello.panelVersion, hello.protocolVersion);
    }

    public synchronized void sendSnapshot(String topic, Object payload, long seq) throws IOException {
        // Snapshot wraps the topic data with seq for replay-from-cursor (M3 spec).
        java.util.Map<String, Object> w = new java.util.LinkedHashMap<>();
        w.put("seq", seq);
        w.put("data", payload);
        sendJson("Snapshot", topic, w, nextId());
    }

    public synchronized void sendEvent(String topic, Object payload) throws IOException {
        sendJson("Event", topic, payload, nextId());
    }

    private synchronized void sendJson(String kind, String topic, Object payload, long id) throws IOException {
        JsonNode node = payload == null ? null : Codecs.JSON.valueToTree(payload);
        IpcEnvelope env = new IpcEnvelope(Constants.PROTOCOL_VERSION, id, kind, topic, node);
        byte[] bytes = Codecs.encodeJson(env);
        pipe.writeAll(new Frame(Frame.CODEC_JSON, bytes).encode());
    }

    private void sendError(String code, String message, Long relatedId) {
        try {
            IpcEnvelope.ErrorPayload err = new IpcEnvelope.ErrorPayload(code, message);
            err.relatedId = relatedId;
            sendJson("Error", null, err, nextId());
        } catch (IOException ignored) {}
    }

    private long nextId() { return outboundIdSeq.incrementAndGet(); }

    public interface ServerInfoSupplier {
        String mcVersion();
        String forgeVersion();
        String[] capabilities();
        String[] dimensions();
        IpcEnvelope.ModInfo[] mods();
    }
}
