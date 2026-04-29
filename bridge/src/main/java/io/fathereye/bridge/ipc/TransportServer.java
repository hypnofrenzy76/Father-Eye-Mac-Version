package io.fathereye.bridge.ipc;

import java.io.IOException;

/**
 * Abstracts the wire used to host a single panel connection. Two
 * implementations:
 *   - {@link NamedPipeTransportServer}: Windows JNA named pipe.
 *   - {@link TcpTransportServer}: TCP socket bound to localhost, works
 *     on every JVM and lets a panel connect from another machine when
 *     bound to 0.0.0.0.
 *
 * <p>The bridge instantiates one server per session; the lifetime is one
 * accept-and-serve cycle. After the panel disconnects the server is
 * closed and the lifecycle code creates a fresh instance for the next
 * connection.
 */
public interface TransportServer {

    /** Block until a panel connects. */
    void accept() throws IOException;

    /** Read exactly {@code n} bytes from the panel; throws on short read. */
    byte[] readExact(int n) throws IOException;

    /** Write all of {@code bytes}; throws on partial write. */
    void writeAll(byte[] bytes) throws IOException;

    /** Drop the panel connection (idempotent; safe after close). */
    void disconnect();

    /** Close the underlying socket/pipe handle and release native resources. */
    void close();

    boolean isClosed();

    /** Human-readable address for logs and the marker file. */
    String address();

    /** Marker-file value for the transport kind ("named-pipe" or "tcp"). */
    String kind();
}
