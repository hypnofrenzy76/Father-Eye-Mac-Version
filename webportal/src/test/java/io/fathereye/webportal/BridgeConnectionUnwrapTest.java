package io.fathereye.webportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.webportal.ipc.MarkerDiscovery;
import io.fathereye.webportal.ipc.PipeCodecs;
import io.fathereye.webportal.ipc.PipeEnvelope;
import io.fathereye.webportal.ipc.PipeFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression for the "empty web portal tabs" bug. A mock bridge
 * speaks the exact IPC wire format (TCP transport + marker file + Hello/Welcome
 * handshake) and pushes Snapshot frames whose payload is wrapped as
 * {@code { seq, data }} per ipc-protocol.md. The test asserts that
 * {@link BridgeConnection} unwraps the {@code data} object before caching and
 * before fanning out to listeners, so the browser JS (which reads topic fields
 * directly) sees populated tabs.
 */
final class BridgeConnectionUnwrapTest {

    private ServerSocket server;
    private Thread bridgeThread;
    private Path markerPath;
    private volatile boolean stop = false;

    @AfterEach
    void tearDown() throws IOException {
        stop = true;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        if (bridgeThread != null) bridgeThread.interrupt();
        if (markerPath != null) Files.deleteIfExists(markerPath);
    }

    @Test
    void unwrapsSeqDataWrapperForSnapshotsAndForwardsEventsRaw() throws Exception {
        UUID uuid = UUID.randomUUID();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        int port = server.getLocalPort();

        writeMarker(uuid, port);
        startMockBridge();

        BridgeConnection conn = new BridgeConnection();

        Map<String, JsonNode> received = new ConcurrentHashMap<>();
        AtomicReference<JsonNode> welcomeRef = new AtomicReference<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch tpsLatch = new CountDownLatch(1);
        CountDownLatch playersLatch = new CountDownLatch(1);
        CountDownLatch consoleLatch = new CountDownLatch(1);

        conn.addListener(new BridgeConnection.Listener() {
            @Override public void onTopic(String kind, String topic, JsonNode payload) {
                received.put(topic, payload);
                if ("tps_topic".equals(topic)) tpsLatch.countDown();
                if ("players_topic".equals(topic)) playersLatch.countDown();
                if ("console_log".equals(topic)) consoleLatch.countDown();
            }
            @Override public void onConnected(JsonNode welcomeJson) {
                welcomeRef.set(welcomeJson);
                connectedLatch.countDown();
            }
            @Override public void onDisconnected() {}
        });

        conn.start();
        try {
            // onConnected is dispatched before any topic frame is delivered, so
            // wait on it first. This avoids a race where the reader thread fans
            // out topics before the connect callback completes on its own thread.
            assertTrue(connectedLatch.await(15, TimeUnit.SECONDS), "onConnected never fired");
            assertTrue(tpsLatch.await(15, TimeUnit.SECONDS), "tps_topic never delivered");
            assertTrue(playersLatch.await(15, TimeUnit.SECONDS), "players_topic never delivered");
            assertTrue(consoleLatch.await(15, TimeUnit.SECONDS), "console_log never delivered");
            assertNotNull(welcomeRef.get(), "welcome payload missing");

            JsonNode tps = received.get("tps_topic");
            assertNotNull(tps);
            // The wrapper must be gone: no seq/data, and the topic fields are top-level.
            assertFalse(tps.has("data"), "tps payload still wrapped in {seq,data}: " + tps);
            assertFalse(tps.has("seq"), "tps payload still carries seq wrapper: " + tps);
            assertTrue(tps.has("tps20s"), "tps20s missing after unwrap: " + tps);
            assertEquals(20.0, tps.get("tps20s").asDouble(), 0.0001);
            assertEquals(123456789L, tps.get("heapUsedBytes").asLong());

            // Cached snapshot must also be unwrapped (new browsers fetch it on connect).
            JsonNode cached = conn.latest("tps_topic");
            assertNotNull(cached);
            assertFalse(cached.has("data"), "cached tps still wrapped: " + cached);
            assertTrue(cached.has("tps20s"), "cached tps missing tps20s: " + cached);

            JsonNode players = received.get("players_topic");
            assertNotNull(players);
            assertFalse(players.has("data"), "players payload still wrapped: " + players);
            assertTrue(players.has("players"), "players array missing after unwrap: " + players);
            assertEquals("Steve", players.get("players").get(0).get("name").asText());

            // Event topics are NOT wrapped on the wire; they must pass through verbatim.
            JsonNode console = received.get("console_log");
            assertNotNull(console);
            assertEquals("hello world", console.get("msg").asText());
        } finally {
            conn.stop();
        }
    }

    private void startMockBridge() {
        bridgeThread = new Thread(() -> {
            try (Socket client = server.accept()) {
                client.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(client.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream());

                // 1. Read Hello.
                PipeEnvelope hello = readEnvelope(in);
                assertEquals("Hello", hello.kind);

                // 2. Send Welcome.
                Map<String, Object> welcome = new LinkedHashMap<>();
                welcome.put("protocolVersion", 1);
                welcome.put("bridgeVersion", "0.3.3-mac.1");
                welcome.put("mcVersion", "1.16.5");
                welcome.put("forgeVersion", "36.2.39");
                welcome.put("instanceUuid", UUID.randomUUID().toString());
                welcome.put("capabilities", new String[]{"jfr", "chunkTile"});
                welcome.put("dimensions", new String[]{"minecraft:overworld"});
                welcome.put("mods", new Object[0]);
                welcome.put("serverHeapMaxBytes", 8L * 1024 * 1024 * 1024);
                sendFrame(out, "Welcome", null, welcome);

                // 3. Read the Subscribe frames, then push topic data. We do not
                //    require all six before pushing; push after the first to keep
                //    the test fast, then keep draining inbound frames.
                readEnvelope(in); // first Subscribe

                // tps_topic wrapped as { seq, data }.
                Map<String, Object> tpsData = new LinkedHashMap<>();
                tpsData.put("tps20s", 20.0);
                tpsData.put("tps1m", 19.9);
                tpsData.put("tps5m", 19.8);
                tpsData.put("msptAvg", 12.3);
                tpsData.put("heapUsedBytes", 123456789L);
                tpsData.put("heapMaxBytes", 8L * 1024 * 1024 * 1024);
                sendSnapshot(out, "tps_topic", 1L, tpsData);

                // players_topic wrapped as { seq, data }.
                Map<String, Object> p0 = new LinkedHashMap<>();
                p0.put("uuid", UUID.randomUUID().toString());
                p0.put("name", "Steve");
                p0.put("dimensionId", "minecraft:overworld");
                p0.put("x", 1.0); p0.put("y", 64.0); p0.put("z", 2.0);
                p0.put("health", 20); p0.put("food", 20); p0.put("pingMs", 5);
                p0.put("gameMode", "survival");
                Map<String, Object> playersData = new LinkedHashMap<>();
                playersData.put("players", new Object[]{p0});
                sendSnapshot(out, "players_topic", 1L, playersData);

                // console_log as an Event: payload is NOT wrapped.
                Map<String, Object> logLine = new LinkedHashMap<>();
                logLine.put("tsMs", System.currentTimeMillis());
                logLine.put("level", "INFO");
                logLine.put("logger", "Test");
                logLine.put("msg", "hello world");
                sendFrame(out, "Event", "console_log", logLine);

                // Keep the socket alive and drain remaining Subscribe frames so the
                // portal's reader does not see EOF (which would trip onDisconnect).
                while (!stop && !Thread.currentThread().isInterrupted()) {
                    try {
                        readEnvelope(in);
                    } catch (IOException eof) {
                        break;
                    }
                }
            } catch (Throwable e) {
                if (!stop) {
                    System.err.println("MOCK BRIDGE FAILED: " + e);
                    e.printStackTrace();
                }
            }
        }, "mock-bridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
    }

    private static PipeEnvelope readEnvelope(DataInputStream in) throws IOException {
        byte[] header = new byte[5];
        in.readFully(header);
        int len = PipeFrame.decodeHeaderLength(header);
        byte codec = PipeFrame.decodeHeaderCodec(header);
        byte[] payload = new byte[len];
        in.readFully(payload);
        if (codec != PipeFrame.CODEC_JSON) throw new IOException("expected JSON codec");
        return PipeCodecs.decodeJson(payload, PipeEnvelope.class);
    }

    private static void sendSnapshot(DataOutputStream out, String topic, long seq, Object data) throws IOException {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("seq", seq);
        wrapper.put("data", data);
        sendFrame(out, "Snapshot", topic, wrapper);
    }

    private static synchronized void sendFrame(DataOutputStream out, String kind, String topic, Object payload) throws IOException {
        JsonNode node = PipeCodecs.JSON.valueToTree(payload);
        PipeEnvelope env = new PipeEnvelope(1, 0, kind, topic, node);
        byte[] bytes = PipeCodecs.encodeJson(env);
        out.write(new PipeFrame(PipeFrame.CODEC_JSON, bytes).encode());
        out.flush();
    }

    private void writeMarker(UUID uuid, int port) throws IOException {
        Path dir = MarkerDiscovery.bridgesDir();
        Files.createDirectories(dir);
        markerPath = dir.resolve(uuid + ".json");
        // Belt-and-suspenders cleanup: the marker lives in the SHARED bridges
        // directory that a real bridge also uses, so make sure a crashed or
        // hard-killed test JVM never leaves a stale TCP marker behind (a real
        // portal would otherwise try to dial this dead test socket).
        final Path mp = markerPath;
        markerPath.toFile().deleteOnExit();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.deleteIfExists(mp); } catch (IOException ignored) {}
        }));
        ObjectNode m = PipeCodecs.JSON.createObjectNode();
        m.put("instanceUuid", uuid.toString());
        m.put("transport", "tcp");
        m.put("address", "127.0.0.1:" + port);
        m.put("protocolVersion", 1);
        m.put("bridgeVersion", "0.3.3-mac.1");
        m.put("mcVersion", "1.16.5");
        m.put("forgeVersion", "36.2.39");
        m.put("serverDir", "/tmp/test-server");
        m.put("pid", currentPid());
        m.put("startedAtEpochMs", System.currentTimeMillis());
        Files.write(markerPath, PipeCodecs.JSON.writeValueAsBytes(m));
    }

    private static long currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? Long.parseLong(name.substring(0, at)) : -1L;
    }
}
