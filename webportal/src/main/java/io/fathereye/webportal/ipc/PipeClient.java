package io.fathereye.webportal.ipc;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IPC client. Copied from the panel's {@code PipeClient}; the only change is
 * {@link #chooseTransport} drops the Windows named-pipe branch. The web
 * portal runs on the same Mac as the bridge and the bridge always advertises
 * {@code transport="tcp"} (Brg-12), so TCP is the only path that ever runs.
 */
public final class PipeClient {

    private static final int PROTOCOL_VERSION = 1;

    private final Transport transport;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong outboundIdSeq = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
    private final Object readLock = new Object();
    private final Object writeLock = new Object();

    public PipeClient(Transport transport) {
        this.transport = transport;
    }

    /** Convenience: pick a transport based on a discovered marker. */
    public static PipeClient forMarker(MarkerDiscovery.Marker marker) {
        Transport t = chooseTransport(marker);
        return new PipeClient(t);
    }

    private static Transport chooseTransport(MarkerDiscovery.Marker marker) {
        String kind = marker.transport;
        String addr = marker.address != null ? marker.address : marker.pipeName;
        if (addr == null || addr.isEmpty()) {
            throw new IllegalArgumentException("marker has no address/pipeName");
        }
        if (kind == null || kind.equalsIgnoreCase("named-pipe")) {
            throw new IllegalStateException(
                    "named-pipe transport is not supported by the web portal; the " +
                    "bridge must advertise transport=\"tcp\" in its marker file. " +
                    "Stale Windows marker?");
        }
        if (kind.equalsIgnoreCase("tcp")) {
            int colon = addr.lastIndexOf(':');
            if (colon < 0) throw new IllegalArgumentException("tcp address missing port: " + addr);
            String host = addr.substring(0, colon);
            int port = Integer.parseInt(addr.substring(colon + 1));
            return new TcpTransport(host, port);
        }
        throw new IllegalArgumentException("unknown transport: " + kind);
    }

    public void connect() throws IOException { transport.connect(); }

    public WelcomeInfo handshake(String panelVersion) throws IOException {
        PipeEnvelope.HelloPayload hello = new PipeEnvelope.HelloPayload();
        hello.protocolVersion = PROTOCOL_VERSION;
        hello.panelVersion = panelVersion;
        hello.capabilities = new String[] { "jfr", "chunkTile" };
        sendJson("Hello", null, hello);

        for (int i = 0; i < 16; i++) {
            PipeEnvelope reply = readEnvelope();
            if ("Welcome".equals(reply.kind)) {
                PipeEnvelope.WelcomePayload w = PipeCodecs.JSON.treeToValue(reply.payload, PipeEnvelope.WelcomePayload.class);
                WelcomeInfo info = new WelcomeInfo();
                info.bridgeVersion = w.bridgeVersion;
                info.mcVersion = w.mcVersion;
                info.forgeVersion = w.forgeVersion;
                info.instanceUuid = w.instanceUuid;
                info.dimensions = w.dimensions == null ? new String[0] : w.dimensions;
                if (w.mods == null) {
                    info.modIds = new String[0];
                    info.modCount = 0;
                } else {
                    info.modIds = new String[w.mods.length];
                    for (int j = 0; j < w.mods.length; j++) {
                        info.modIds[j] = w.mods[j] == null ? "" : w.mods[j].id;
                    }
                    info.modCount = w.mods.length;
                }
                info.dimensionCount = info.dimensions.length;
                info.serverHeapMaxBytes = w.serverHeapMaxBytes;
                info.capabilities = w.capabilities == null ? new String[0] : w.capabilities;
                return info;
            }
            if ("Error".equals(reply.kind)) {
                String detail = reply.payload == null ? "<no payload>" : reply.payload.toString();
                throw new IOException("handshake error: " + detail);
            }
        }
        throw new IOException("handshake timed out: never received Welcome");
    }

    public PipeEnvelope readEnvelope() throws IOException {
        synchronized (readLock) {
            byte[] header = transport.readExact(5);
            int len = PipeFrame.decodeHeaderLength(header);
            byte codec = PipeFrame.decodeHeaderCodec(header);
            byte[] payload = transport.readExact(len);
            if (codec != PipeFrame.CODEC_JSON) {
                throw new IOException("expected JSON frame for control plane, got codec=" + codec);
            }
            PipeEnvelope env = PipeCodecs.decodeJson(payload, PipeEnvelope.class);
            if ("Response".equals(env.kind) || "Error".equals(env.kind)) {
                CompletableFuture<JsonNode> f = pendingResponses.remove(env.id);
                if (f != null) f.complete(env.payload);
            }
            return env;
        }
    }

    public CompletableFuture<JsonNode> sendRequest(String op, Map<String, Object> args) throws IOException {
        if (isClosed()) {
            IOException ioe = new IOException("transport closed");
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(ioe);
            return f;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("op", op);
        p.put("args", args == null ? new LinkedHashMap<String, Object>() : args);
        long id = outboundIdSeq.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pendingResponses.put(id, f);
        try {
            JsonNode node = PipeCodecs.JSON.valueToTree(p);
            PipeEnvelope env = new PipeEnvelope(PROTOCOL_VERSION, id, "Request", null, node);
            byte[] bytes = PipeCodecs.encodeJson(env);
            synchronized (writeLock) {
                transport.writeAll(new PipeFrame(PipeFrame.CODEC_JSON, bytes).encode());
            }
        } catch (IOException ioe) {
            close();
            pendingResponses.remove(id);
            f.completeExceptionally(ioe);
            throw ioe;
        }
        return f;
    }

    public void sendJson(String kind, String topic, Object payload) throws IOException {
        if (isClosed()) throw new IOException("transport closed");
        JsonNode node = payload == null ? null : PipeCodecs.JSON.valueToTree(payload);
        PipeEnvelope env = new PipeEnvelope(PROTOCOL_VERSION, outboundIdSeq.incrementAndGet(), kind, topic, node);
        byte[] bytes = PipeCodecs.encodeJson(env);
        try {
            synchronized (writeLock) {
                transport.writeAll(new PipeFrame(PipeFrame.CODEC_JSON, bytes).encode());
            }
        } catch (IOException ioe) {
            close();
            throw ioe;
        }
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            IOException cause = new IOException("transport closed");
            for (Map.Entry<Long, CompletableFuture<JsonNode>> e : pendingResponses.entrySet()) {
                e.getValue().completeExceptionally(cause);
            }
            pendingResponses.clear();
            transport.close();
        }
    }

    public boolean isClosed() { return closed.get() || transport.isClosed(); }

    public String address() { return transport.address(); }

    public static final class WelcomeInfo {
        public String bridgeVersion;
        public String mcVersion;
        public String forgeVersion;
        public String instanceUuid;
        public String[] dimensions;
        public String[] modIds = new String[0];
        public int modCount;
        public int dimensionCount;
        public long serverHeapMaxBytes;
        public String[] capabilities = new String[0];
    }
}
