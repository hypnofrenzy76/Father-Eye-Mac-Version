package io.fathereye.webportal.ipc;

import java.io.IOException;

/**
 * Wire abstraction. The web portal only ever runs on the same Mac as the
 * bridge and connects over localhost TCP, so {@link TcpTransport} is the
 * sole implementation here (the panel's Windows named-pipe transport is
 * intentionally not copied, since it pulls in JNA and never applies).
 */
public interface Transport {

    void connect() throws IOException;

    byte[] readExact(int n) throws IOException;

    void writeAll(byte[] bytes) throws IOException;

    void close();

    boolean isClosed();

    String address();
}
