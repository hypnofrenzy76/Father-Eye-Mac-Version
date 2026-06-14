package io.fathereye.webportal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-contained RFC 6455 server-side WebSocket over a raw {@link Socket}.
 * No external library: implements the handshake accept key, masked client
 * frame decode, server (unmasked) frame encode, ping/pong, and close. Only
 * text and control frames are needed by the portal protocol.
 *
 * <p>One instance wraps one upgraded connection. {@link #sendText} is
 * synchronized so the bridge broadcast thread and the per-connection read
 * loop can both write safely.
 */
public final class WebSocketConnection {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortal-WS");

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public WebSocketConnection(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /** Computes the Sec-WebSocket-Accept response value for a client key. */
    public static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey + MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Sends a text frame (server frames are never masked). */
    public void sendText(String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            writeFrame(OP_TEXT, payload);
        }
    }

    private void sendPong(byte[] appData) throws IOException {
        synchronized (writeLock) {
            writeFrame(OP_PONG, appData);
        }
    }

    public void sendClose() {
        if (closed.compareAndSet(false, true)) {
            try {
                synchronized (writeLock) {
                    writeFrame(OP_CLOSE, new byte[0]);
                }
            } catch (IOException ignored) {
            } finally {
                closeQuietly();
            }
        }
    }

    public boolean isClosed() {
        return closed.get() || socket.isClosed();
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        out.write(0x80 | (opcode & 0x0F)); // FIN + opcode
        if (len < 126) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) ((((long) len) >>> (8 * i)) & 0xFF));
        }
        out.write(payload);
        out.flush();
    }

    /**
     * Blocks reading the next text message from the client. Handles ping
     * (auto-pong), pong (ignored), and close (returns null) transparently.
     * Returns the decoded UTF-8 text, or null when the connection closes.
     */
    public String readText() throws IOException {
        java.io.ByteArrayOutputStream assembled = new java.io.ByteArrayOutputStream();
        int messageOpcode = -1;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) { return null; }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;

            int b1 = in.read();
            if (b1 < 0) { return null; }
            boolean masked = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7F;
            if (payloadLen == 126) {
                payloadLen = ((long) readByte() << 8) | readByte();
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | readByte();
            }
            // Per RFC 6455 all client->server frames MUST be masked.
            byte[] mask = new byte[4];
            if (masked) {
                readFully(mask, 4);
            }
            if (payloadLen > 16 * 1024 * 1024) {
                throw new IOException("ws frame too large: " + payloadLen);
            }
            byte[] payload = new byte[(int) payloadLen];
            readFully(payload, payload.length);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case OP_CLOSE:
                    sendClose();
                    return null;
                case OP_PING:
                    sendPong(payload);
                    continue;
                case OP_PONG:
                    continue;
                case OP_TEXT:
                case OP_BINARY:
                    messageOpcode = opcode;
                    assembled.write(payload);
                    break;
                case OP_CONTINUATION:
                    assembled.write(payload);
                    break;
                default:
                    // unknown opcode; ignore
                    continue;
            }
            if (fin && (messageOpcode == OP_TEXT || messageOpcode == OP_BINARY
                    || opcode == OP_CONTINUATION)) {
                return new String(assembled.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b & 0xFF;
    }

    private void readFully(byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
    }

    private void closeQuietly() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
