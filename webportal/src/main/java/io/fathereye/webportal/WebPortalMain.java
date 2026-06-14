package io.fathereye.webportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.webportal.ipc.PipeCodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point for the Father Eye Web Portal. Wires the HTTP router, the
 * single-password authentication, the WebSocket sessions, and the bridge
 * connection together. Designed to run on the same Mac as the bridge and be
 * reached remotely over a Tailscale tailnet.
 *
 * <p>Flags:
 * <ul>
 *   <li>{@code --port=N} (default 8765)</li>
 *   <li>{@code --host=ADDR} bind address (default 0.0.0.0 so the Tailscale
 *       interface is reachable; set to 127.0.0.1 to restrict to loopback)</li>
 *   <li>{@code --set-password=PW} write a new credential hash and exit</li>
 * </ul>
 */
public final class WebPortalMain {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortal");

    private final Auth auth = new Auth();
    private final BridgeConnection bridge = new BridgeConnection();
    private final BackupManager backups = new BackupManager();
    private final PlayerRestoreService playerRestore = new PlayerRestoreService();
    private final CopyOnWriteArrayList<WsSession> sessions = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        int port = 8765;
        String host = "0.0.0.0";
        String setPassword = null;
        for (String a : args) {
            if (a.startsWith("--port=")) port = Integer.parseInt(a.substring(7).trim());
            else if (a.startsWith("--host=")) host = a.substring(7).trim();
            else if (a.startsWith("--set-password=")) setPassword = a.substring(15);
        }
        if (setPassword != null) {
            if (setPassword.length() < 16) {
                System.err.println("Refusing to set a password shorter than 16 characters.");
                System.exit(2);
            }
            new Auth().setPassword(setPassword);
            System.out.println("Web portal password updated.");
            return;
        }
        new WebPortalMain().run(host, port);
    }

    private void run(String host, int port) throws IOException {
        bridge.addListener(new BridgeConnection.Listener() {
            @Override public void onTopic(String kind, String topic, JsonNode payload) {
                broadcastTopic(kind, topic, payload);
            }
            @Override public void onConnected(JsonNode welcomeJson) {
                broadcastWelcome(welcomeJson);
            }
            @Override public void onDisconnected() {
                broadcastDisconnected();
            }
        });
        bridge.start();

        HttpServerCore server = new HttpServerCore(host, port, this::route);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            bridge.stop();
        }));

        LOG.info("Father Eye Web Portal ready. Open it over Tailscale at http://<this-mac-tailscale-name>:{}", port);
        LOG.info("Bind host {} (use --host=127.0.0.1 to restrict to loopback).", host);

        // Park the main thread.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- HTTP routing ----

    private boolean route(HttpServerCore.Request req, HttpServerCore.Response resp) throws IOException {
        String path = req.path;
        switch (path) {
            case "/":
                if (isAuthed(req)) resp.redirect("/app");
                else resp.html(WebPortalAssets.LOGIN_HTML);
                return false;
            case "/login":
                handleLogin(req, resp);
                return false;
            case "/logout":
                handleLogout(req, resp);
                return false;
            case "/app":
                if (!isAuthed(req)) { resp.redirect("/"); return false; }
                resp.html(WebPortalAssets.APP_HTML);
                return false;
            case "/app.js":
                if (!isAuthed(req)) { resp.text(401, "unauthorized"); return false; }
                resp.header("Content-Type", "application/javascript; charset=utf-8");
                resp.body = WebPortalAssets.APP_JS.getBytes(StandardCharsets.UTF_8);
                return false;
            case "/app.css":
                resp.header("Content-Type", "text/css; charset=utf-8");
                resp.body = WebPortalAssets.APP_CSS.getBytes(StandardCharsets.UTF_8);
                return false;
            case "/ws":
                return handleWebSocketUpgrade(req, resp);
            case "/api/backups":
                handleApiBackups(req, resp);
                return false;
            case "/api/backup/run":
                handleApiBackupRun(req, resp);
                return false;
            case "/api/rollback":
                handleApiRollback(req, resp);
                return false;
            case "/api/region-rollback":
                handleApiRegionRollback(req, resp);
                return false;
            case "/api/job":
                handleApiJob(req, resp);
                return false;
            case "/api/backup/players":
                handleApiBackupPlayers(req, resp);
                return false;
            case "/api/player/restore":
                handleApiPlayerRestore(req, resp);
                return false;
            default:
                resp.text(404, "not found");
                return false;
        }
    }

    // ---- backup / rollback HTTP API ----

    private void handleApiBackups(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        resp.json(200, backups.list().toString());
    }

    private void handleApiBackupRun(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        if (!"POST".equalsIgnoreCase(req.method)) { resp.text(400, "POST only"); return; }
        String label = null;
        try {
            JsonNode b = PipeCodecs.JSON.readTree(req.body == null || req.body.length == 0 ? "{}"
                    : new String(req.body, StandardCharsets.UTF_8));
            label = text(b, "label");
        } catch (Exception ignored) {}
        boolean started = backups.startBackup(label);
        String body = started
                ? "{\"started\":true}"
                : "{\"started\":false,\"error\":\"a job is already running\"}";
        resp.json(started ? 200 : 409, body);
    }

    private void handleApiRollback(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        if (!"POST".equalsIgnoreCase(req.method)) { resp.text(400, "POST only"); return; }
        // Defence in depth: refuse rollback while the server is up. The
        // bridge stays connected only while the Minecraft server runs, so a
        // live bridge means the world is in use.
        if (bridge.isConnected()) {
            resp.json(409, "{\"started\":false,\"error\":\"server is running; stop it before rolling back\"}");
            return;
        }
        String id, scope;
        try {
            JsonNode b = PipeCodecs.JSON.readTree(req.body == null || req.body.length == 0 ? "{}"
                    : new String(req.body, StandardCharsets.UTF_8));
            id = text(b, "id");
            scope = text(b, "scope");
        } catch (Exception e) {
            resp.json(400, "{\"started\":false,\"error\":\"bad request body\"}");
            return;
        }
        boolean started = backups.startRollback(id, scope);
        String body = started
                ? "{\"started\":true}"
                : "{\"started\":false,\"error\":\"rejected (job running or invalid input)\"}";
        resp.json(started ? 200 : 409, body);
    }

    /**
     * Region-selective rollback: restore only the operator-selected Anvil
     * region files of one dimension from a backup. Same stopped-only gate
     * as the whole-world rollback. Body: {@code {id, dim, regions:["rx,rz",...]}}.
     */
    private void handleApiRegionRollback(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        if (!"POST".equalsIgnoreCase(req.method)) { resp.text(400, "POST only"); return; }
        if (bridge.isConnected()) {
            resp.json(409, "{\"started\":false,\"error\":\"server is running; stop it before rolling back\"}");
            return;
        }
        String id, dim;
        java.util.List<String> regions = new java.util.ArrayList<>();
        try {
            JsonNode b = PipeCodecs.JSON.readTree(req.body == null || req.body.length == 0 ? "{}"
                    : new String(req.body, StandardCharsets.UTF_8));
            id = text(b, "id");
            dim = text(b, "dim");
            JsonNode arr = b.path("regions");
            if (arr.isArray()) {
                for (JsonNode r : arr) regions.add(r.asText(""));
            }
        } catch (Exception e) {
            resp.json(400, "{\"started\":false,\"error\":\"bad request body\"}");
            return;
        }
        boolean started = backups.startRegionRollback(id, dim, regions);
        String body = started
                ? "{\"started\":true}"
                : "{\"started\":false,\"error\":\"rejected (job running or invalid input)\"}";
        resp.json(started ? 200 : 409, body);
    }

    private void handleApiJob(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        resp.json(200, backups.jobStatus().toString());
    }

    // ---- live player-state restore ----

    /** List the players whose data is stored in a given backup. */
    private void handleApiBackupPlayers(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        String id = queryParam(req.query, "id");
        resp.json(200, playerRestore.listPlayers(id).toString());
    }

    /**
     * Restore one player's full saved state from a backup into the running
     * server. The web portal extracts the {@code <uuid>.dat} from the backup
     * archive on the external drive, base64s it, and forwards it to the
     * bridge's {@code player_restoreState} op. The bridge injects it into the
     * live player (online) or writes it to disk (offline), taking its own
     * pre-restore safety snapshot either way.
     *
     * <p>Requires the bridge (and therefore the server) to be up: a live
     * inventory injection only makes sense while the world is running, and
     * the bridge owns the playerdata directory for the offline write too.
     */
    private void handleApiPlayerRestore(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return; }
        if (!"POST".equalsIgnoreCase(req.method)) { resp.text(400, "POST only"); return; }
        if (!bridge.isConnected()) {
            resp.json(409, "{\"ok\":false,\"error\":\"server is not running; start it to restore live player state\"}");
            return;
        }
        String id, uuid;
        try {
            JsonNode b = PipeCodecs.JSON.readTree(req.body == null || req.body.length == 0 ? "{}"
                    : new String(req.body, StandardCharsets.UTF_8));
            id = text(b, "id");
            uuid = text(b, "uuid");
        } catch (Exception e) {
            resp.json(400, "{\"ok\":false,\"error\":\"bad request body\"}");
            return;
        }
        if (!PlayerRestoreService.isValidBackupId(id)) {
            resp.json(400, "{\"ok\":false,\"error\":\"invalid backup id\"}");
            return;
        }
        if (!PlayerRestoreService.isValidUuid(uuid)) {
            resp.json(400, "{\"ok\":false,\"error\":\"invalid player uuid\"}");
            return;
        }

        byte[] dat = playerRestore.extractPlayerDat(id, uuid);
        if (dat == null) {
            resp.json(404, "{\"ok\":false,\"error\":\"player not found in this backup\"}");
            return;
        }

        Map<String, Object> rpcArgs = new LinkedHashMap<>();
        rpcArgs.put("playerUuid", uuid);
        rpcArgs.put("nbtBase64", java.util.Base64.getEncoder().encodeToString(dat));
        // Fallbacks the bridge uses only if the live server can't report its
        // own world path; harmless to always include.
        rpcArgs.put("serverDir", PortalConfig.serverDir().toString());

        try {
            JsonNode result = bridge.sendRequest("player_restoreState", rpcArgs)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            ObjectNode out = PipeCodecs.JSON.createObjectNode();
            out.put("ok", true);
            out.set("result", result);
            resp.json(200, out.toString());
        } catch (java.util.concurrent.TimeoutException e) {
            resp.json(504, "{\"ok\":false,\"error\":\"bridge timed out restoring player\"}");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            ObjectNode out = PipeCodecs.JSON.createObjectNode();
            out.put("ok", false);
            out.put("error", "restore failed: " + cause.getMessage());
            resp.json(502, out.toString());
        }
    }

    /** Extract a single decoded query parameter by name, or null. */
    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            try {
                if (URLDecoder.decode(k, "UTF-8").equals(name)) {
                    return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean isAuthed(HttpServerCore.Request req) {
        return auth.validate(req.cookie("fe_session"));
    }

    private void handleLogin(HttpServerCore.Request req, HttpServerCore.Response resp) {
        if (!"POST".equalsIgnoreCase(req.method)) { resp.text(400, "POST only"); return; }
        Map<String, String> form = parseForm(new String(req.body, StandardCharsets.UTF_8));
        String pw = form.get("password");
        String token = auth.login(req.remoteAddr, pw);
        if (token == null) {
            resp.status = 401; resp.statusText = "Unauthorized";
            resp.header("Content-Type", "text/html; charset=utf-8");
            resp.body = WebPortalAssets.loginHtmlWithError().getBytes(StandardCharsets.UTF_8);
            return;
        }
        // HttpOnly + SameSite=Strict cookie. Tailscale provides the transport
        // encryption; the cookie is not marked Secure so plain-HTTP-over-
        // tailnet works without a TLS cert.
        resp.addCookie("fe_session=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age="
                + (Auth.SESSION_TTL_MS / 1000));
        resp.redirect("/app");
    }

    private void handleLogout(HttpServerCore.Request req, HttpServerCore.Response resp) {
        auth.logout(req.cookie("fe_session"));
        resp.addCookie("fe_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
        resp.redirect("/");
    }

    // ---- WebSocket ----

    private boolean handleWebSocketUpgrade(HttpServerCore.Request req, HttpServerCore.Response resp) throws IOException {
        if (!isAuthed(req)) { resp.text(401, "unauthorized"); return false; }
        String upgrade = req.header("upgrade");
        String key = req.header("sec-websocket-key");
        if (upgrade == null || !upgrade.toLowerCase().contains("websocket") || key == null) {
            resp.text(400, "expected websocket upgrade");
            return false;
        }
        String accept = WebSocketConnection.acceptKey(key);
        String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        req.out.write(handshake.getBytes(StandardCharsets.ISO_8859_1));
        req.out.flush();

        WebSocketConnection ws = new WebSocketConnection(req.socket, req.in, req.out);
        WsSession session = new WsSession(ws, req.cookie("fe_session"));
        sessions.add(session);
        LOG.info("WebSocket client connected from {} ({} live)", req.remoteAddr, sessions.size());

        // Send current state immediately so the browser populates without
        // waiting for the next 1 Hz snapshot.
        try {
            if (bridge.isConnected() && bridge.welcome() != null) {
                session.send(wrapStatus("connected"));
                session.send(wrap("welcome", bridge.welcome()));
            } else {
                session.send(wrapStatus("disconnected"));
            }
            for (Map.Entry<String, JsonNode> e : bridge.allLatest().entrySet()) {
                session.send(wrapTopic("Snapshot", e.getKey(), e.getValue()));
            }
        } catch (IOException ignored) {}

        // Read loop for inbound RPC requests from this browser.
        Thread reader = new Thread(() -> wsReadLoop(session), "FatherEye-WebPortal-WsRead");
        reader.setDaemon(true);
        reader.start();
        return true; // hijacked
    }

    private void wsReadLoop(WsSession session) {
        try {
            String msg;
            while ((msg = session.ws.readText()) != null) {
                if (!auth.validate(session.token)) { // session may expire mid-stream
                    session.ws.sendClose();
                    break;
                }
                handleClientMessage(session, msg);
            }
        } catch (IOException e) {
            // client gone
        } finally {
            sessions.remove(session);
            session.ws.sendClose();
            LOG.info("WebSocket client disconnected ({} live)", sessions.size());
        }
    }

    /**
     * Client message shape: {"type":"rpc","reqId":N,"op":"cmd_run","args":{...}}.
     * The portal forwards the op+args straight to the bridge and ships the
     * response back tagged with the same reqId.
     */
    private void handleClientMessage(WsSession session, String msg) {
        try {
            JsonNode root = PipeCodecs.JSON.readTree(msg);
            String type = text(root, "type");
            if ("rpc".equals(type)) {
                long reqId = root.has("reqId") ? root.get("reqId").asLong() : 0L;
                String op = text(root, "op");
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode an = root.get("args");
                if (an != null && an.isObject()) {
                    args = PipeCodecs.JSON.convertValue(an, Map.class);
                }
                bridge.sendRequest(op, args).whenComplete((result, err) -> {
                    try {
                        ObjectNode out = PipeCodecs.JSON.createObjectNode();
                        out.put("type", "rpcResult");
                        out.put("reqId", reqId);
                        out.put("op", op);
                        if (err != null) {
                            out.put("ok", false);
                            out.put("error", err.getMessage());
                        } else {
                            out.put("ok", true);
                            out.set("result", result);
                        }
                        session.send(PipeCodecs.JSON.writeValueAsString(out));
                    } catch (IOException ignored) {}
                });
            } else if ("ping".equals(type)) {
                session.send("{\"type\":\"pong\"}");
            }
        } catch (Exception e) {
            LOG.debug("bad client message: {}", e.getMessage());
        }
    }

    // ---- broadcast ----

    private void broadcastTopic(String kind, String topic, JsonNode payload) {
        String frame = wrapTopic(kind, topic, payload);
        broadcast(frame);
    }

    private void broadcastWelcome(JsonNode welcome) {
        broadcast(wrapStatus("connected"));
        broadcast(wrap("welcome", welcome));
    }

    private void broadcastDisconnected() {
        broadcast(wrapStatus("disconnected"));
    }

    private void broadcast(String frame) {
        for (WsSession s : sessions) {
            try { s.send(frame); }
            catch (IOException e) {
                sessions.remove(s);
                s.ws.sendClose();
            }
        }
    }

    private String wrapTopic(String kind, String topic, JsonNode payload) {
        ObjectNode n = PipeCodecs.JSON.createObjectNode();
        n.put("type", "topic");
        n.put("kind", kind);
        n.put("topic", topic);
        n.set("payload", payload);
        return n.toString();
    }

    private String wrap(String type, JsonNode payload) {
        ObjectNode n = PipeCodecs.JSON.createObjectNode();
        n.put("type", type);
        n.set("payload", payload);
        return n.toString();
    }

    private String wrapStatus(String status) {
        ObjectNode n = PipeCodecs.JSON.createObjectNode();
        n.put("type", "status");
        n.put("status", status);
        return n.toString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    out.put(k, v);
                } else {
                    out.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** One connected browser. {@link #send} is serialized via the WS write lock. */
    private static final class WsSession {
        final WebSocketConnection ws;
        final String token;
        WsSession(WebSocketConnection ws, String token) { this.ws = ws; this.token = token; }
        void send(String s) throws IOException { ws.sendText(s); }
    }
}
