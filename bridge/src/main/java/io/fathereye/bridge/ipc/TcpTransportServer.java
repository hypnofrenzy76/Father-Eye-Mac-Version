package io.fathereye.bridge.ipc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-platform TCP {@link TransportServer}. The {@link ServerSocket} is
 * owned externally — the constructor takes a pre-bound server and this
 * class only manages the per-connection client socket. That avoids
 * binding the same port twice (Audit B1: probe-vs-cycle race) and keeps
 * the named-pipe-style "fresh resource per accept cycle" semantics
 * coexisting cleanly: each cycle creates a new {@code TcpTransportServer}
 * wrapping the shared server, calls {@link #accept()}, services one
 * client, then {@link #close()}-s only the client socket.
 *
 * <p>Bind defaults to 127.0.0.1 (localhost). The single-user security
 * posture matches the named-pipe path; remote panels are not yet
 * authenticated and require an explicit config opt-in (future).
 */
public final class TcpTransportServer implements TransportServer {

    private final ServerSocket serverSocket;
    private final boolean ownsServerSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Socket clientSocket;
    private DataInputStream clientIn;
    private DataOutputStream clientOut;

    /** Wrap an externally-managed, pre-bound ServerSocket. */
    public TcpTransportServer(ServerSocket sharedServerSocket) {
        this.serverSocket = sharedServerSocket;
        this.ownsServerSocket = false;
    }

    /** Bind a fresh ServerSocket and own its lifecycle. */
    public TcpTransportServer(String bindAddress, int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        this.serverSocket = s;
        this.ownsServerSocket = true;
    }

    @Override public void accept() throws IOException {
        clientSocket = serverSocket.accept();
        clientSocket.setTcpNoDelay(true);
        clientIn = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());
    }

    @Override public byte[] readExact(int n) throws IOException {
        byte[] out = new byte[n];
        clientIn.readFully(out);
        return out;
    }

    @Override public void writeAll(byte[] bytes) throws IOException {
        clientOut.write(bytes);
        clientOut.flush();
    }

    @Override public void disconnect() {
        // Audit B2: do NOT null clientSocket / streams. Closing the socket
        // here while a writer is mid-write would NPE if we set the field
        // to null. Just close — Socket.close() unblocks any blocking I/O
        // with a clean SocketException, which the IpcSession loop handles.
        Socket s = clientSocket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            disconnect();
            if (ownsServerSocket) {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    @Override public boolean isClosed() { return closed.get(); }

    @Override public String address() {
        return serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    public int port() { return serverSocket.getLocalPort(); }
    @Override public String kind() { return "tcp"; }
}
