package io.fathereye.panel.ipc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-platform TCP {@link Transport}. */
public final class TcpTransport implements Transport {

    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TcpTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override public void connect() throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 10_000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
    }

    @Override public byte[] readExact(int n) throws IOException {
        byte[] buf = new byte[n];
        in.readFully(buf);
        return buf;
    }

    @Override public void writeAll(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }
    }

    @Override public boolean isClosed() { return closed.get(); }
    @Override public String address() { return host + ":" + port; }
}
