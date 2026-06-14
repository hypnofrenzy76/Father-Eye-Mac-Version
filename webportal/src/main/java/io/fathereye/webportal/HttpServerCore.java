package io.fathereye.webportal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal dependency-free HTTP/1.1 server built directly on a
 * {@link ServerSocket}. A raw socket server (rather than the JDK
 * {@code com.sun.net.httpserver.HttpServer}) is used so the WebSocket
 * upgrade can take full ownership of the connection socket on the same port.
 *
 * <p>Supports just what the portal needs: GET/POST with header parsing, a
 * bounded request body read, simple routing, and a WebSocket upgrade hook.
 * It is not a general-purpose web server.
 */
public final class HttpServerCore {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortal-Http");

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    public interface Handler {
        /**
         * Handle a request. Return true if the connection was hijacked (e.g.
         * upgraded to WebSocket) and the server must not touch the socket
         * further; return false for a normal completed HTTP response.
         */
        boolean handle(Request req, Response resp) throws IOException;
    }

    public static final class Request {
        public String method;
        public String path;
        public String query;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        public Socket socket;
        public InputStream in;
        public OutputStream out;
        public String remoteAddr;

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public String cookie(String name) {
            String c = header("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).trim().equals(name)) {
                    return p.substring(eq + 1).trim();
                }
            }
            return null;
        }
    }

    public static final class Response {
        public int status = 200;
        public String statusText = "OK";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final List<String> cookies = new ArrayList<>();
        public byte[] body = new byte[0];
        private boolean sent = false;

        public Response header(String k, String v) { headers.put(k, v); return this; }
        public Response addCookie(String c) { cookies.add(c); return this; }

        public void text(int code, String s) {
            this.status = code; this.statusText = reason(code);
            headers.put("Content-Type", "text/plain; charset=utf-8");
            this.body = s.getBytes(StandardCharsets.UTF_8);
        }
        public void html(String s) {
            this.status = 200; this.statusText = "OK";
            headers.put("Content-Type", "text/html; charset=utf-8");
            this.body = s.getBytes(StandardCharsets.UTF_8);
        }
        public void json(int code, String s) {
            this.status = code; this.statusText = reason(code);
            headers.put("Content-Type", "application/json; charset=utf-8");
            this.body = s.getBytes(StandardCharsets.UTF_8);
        }
        public void redirect(String location) {
            this.status = 303; this.statusText = "See Other";
            headers.put("Location", location);
            this.body = new byte[0];
        }
        boolean isSent() { return sent; }
        void markSent() { sent = true; }
    }

    private final String bindHost;
    private final int port;
    private final Handler handler;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FatherEye-WebPortal-Conn");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HttpServerCore(String bindHost, int port, Handler handler) {
        this.bindHost = bindHost;
        this.port = port;
        this.handler = handler;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bindHost, port));
        this.serverSocket = ss;
        running.set(true);
        Thread accept = new Thread(this::acceptLoop, "FatherEye-WebPortal-Accept");
        accept.setDaemon(true);
        accept.start();
        LOG.info("HTTP server listening on {}:{}", bindHost, port);
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = serverSocket.accept();
                pool.submit(() -> handleConnection(s));
            } catch (IOException e) {
                if (running.get()) LOG.warn("accept failed", e);
            }
        }
    }

    private void handleConnection(Socket s) {
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            Request req = parseRequest(s, in, out);
            if (req == null) { closeQuietly(s); return; }
            Response resp = new Response();
            boolean hijacked = handler.handle(req, resp);
            if (hijacked) {
                return; // socket now owned by the WebSocket layer
            }
            writeResponse(out, resp);
            // HTTP/1.0-style: close after each response (simple and safe).
            closeQuietly(s);
        } catch (IOException e) {
            closeQuietly(s);
        } catch (Throwable t) {
            LOG.warn("connection handler error", t);
            closeQuietly(s);
        }
    }

    private Request parseRequest(Socket s, InputStream in, OutputStream out) throws IOException {
        // Read header block up to CRLFCRLF.
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int state = 0; // matching \r\n\r\n
        int b;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if (headerBuf.size() > MAX_HEADER_BYTES) throw new IOException("header too large");
            if (b == '\r') { if (state == 0 || state == 2) state++; else state = 1; }
            else if (b == '\n') { if (state == 1 || state == 3) state++; else state = 0; }
            else state = 0;
            if (state == 4) break;
        }
        if (b == -1 && headerBuf.size() == 0) return null;

        String headerText = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) return null;

        Request req = new Request();
        req.socket = s;
        req.in = in;
        req.out = out;
        req.remoteAddr = s.getInetAddress() == null ? "unknown" : s.getInetAddress().getHostAddress();

        String[] reqLine = lines[0].split(" ");
        if (reqLine.length < 2) return null;
        req.method = reqLine[0];
        String target = reqLine[1];
        int q = target.indexOf('?');
        if (q >= 0) { req.path = target.substring(0, q); req.query = target.substring(q + 1); }
        else { req.path = target; req.query = ""; }

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                String k = line.substring(0, colon).trim().toLowerCase();
                String v = line.substring(colon + 1).trim();
                req.headers.put(k, v);
            }
        }

        String cl = req.headers.get("content-length");
        if (cl != null) {
            int len;
            try { len = Integer.parseInt(cl.trim()); } catch (NumberFormatException e) { len = 0; }
            if (len > MAX_BODY_BYTES) throw new IOException("body too large");
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(body, off, len - off);
                if (r < 0) break;
                off += r;
            }
            req.body = body;
        }
        return req;
    }

    private void writeResponse(OutputStream out, Response resp) throws IOException {
        if (resp.isSent()) return;
        resp.markSent();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(resp.statusText).append("\r\n");
        if (!resp.headers.containsKey("Content-Length")) {
            resp.headers.put("Content-Length", String.valueOf(resp.body.length));
        }
        resp.headers.putIfAbsent("Connection", "close");
        // Conservative security headers for the operator console.
        resp.headers.putIfAbsent("X-Content-Type-Options", "nosniff");
        resp.headers.putIfAbsent("X-Frame-Options", "DENY");
        resp.headers.putIfAbsent("Referrer-Policy", "no-referrer");
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        for (String c : resp.cookies) {
            sb.append("Set-Cookie: ").append(c).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(resp.body);
        out.flush();
    }

    public static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
