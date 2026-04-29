package io.fathereye.panel.ipc;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Panel-side IPC client. Holds a {@link Transport} (named pipe on Windows,
 * TCP elsewhere) and exposes the same handshake / sendJson / sendRequest
 * API the rest of the panel was written against.
 *
 * <p>Locking model: separate read and write monitors so the reader thread
 * blocked in readExact doesn't starve writers.
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
        // Default to named-pipe when kind is absent (legacy markers).
        if (kind == null || kind.equalsIgnoreCase("named-pipe")) {
            return new WindowsPipeTransport(addr);
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
                // Pnl-39: surface the full mod ID list (not just a count) so
                // ModsPane can show every installed mod, including those with
                // no ticking entities/TEs and therefore no rows in the
                // mods_impact_topic snapshots.
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
        // Pre-flight: if the pipe has already been closed (server died, panel
        // shutting down, etc.), fail fast WITHOUT writing. Without this, a
        // panel that lost its server kept retrying writes on every redraw,
        // each one throwing SocketException — flooding the log and freezing
        // the FX thread.
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
            // Mark the client closed so subsequent calls fail fast instead
            // of repeatedly hitting the same broken socket.
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
        /** Pnl-39: full list of installed mod IDs from the bridge's
         *  ModList.get().getMods() walk. Empty (length 0), never null. */
        public String[] modIds = new String[0];
        public int modCount;
        public int dimensionCount;
        public long serverHeapMaxBytes;
    }
}
