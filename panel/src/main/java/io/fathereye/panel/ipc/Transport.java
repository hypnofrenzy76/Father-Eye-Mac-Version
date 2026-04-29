package io.fathereye.panel.ipc;

import java.io.IOException;

/**
 * Panel-side wire abstraction. Two implementations:
 *   - {@link WindowsPipeTransport}: Windows JNA named pipe (used when the
 *     bridge runs on Windows).
 *   - {@link TcpTransport}: TCP socket — used when the bridge runs on
 *     macOS/Linux/anywhere named pipes aren't available, and as the
 *     primary path for cross-machine setups.
 *
 * The marker file's {@code transport} field tells the panel which one to
 * instantiate; legacy markers without that field are assumed named-pipe.
 */
public interface Transport {

    void connect() throws IOException;

    byte[] readExact(int n) throws IOException;

    void writeAll(byte[] bytes) throws IOException;

    void close();

    boolean isClosed();

    String address();
}
