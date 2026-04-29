package io.fathereye.bridge.ipc;

import java.io.IOException;

/**
 * {@link TransportServer} backed by the existing Windows-only
 * {@link NamedPipeServer}. Thin adapter so the lifecycle code can hold a
 * generic {@code TransportServer} reference.
 */
public final class NamedPipeTransportServer implements TransportServer {

    private final NamedPipeServer pipe;
    private final boolean firstInstance;

    public NamedPipeTransportServer(String pipeName, boolean firstInstance) {
        this.pipe = new NamedPipeServer(pipeName);
        this.firstInstance = firstInstance;
    }

    @Override public void accept() throws IOException {
        pipe.create(firstInstance);
        pipe.connect();
    }

    @Override public byte[] readExact(int n) throws IOException { return pipe.readExact(n); }
    @Override public void writeAll(byte[] bytes) throws IOException { pipe.writeAll(bytes); }
    @Override public void disconnect() { pipe.disconnect(); }
    @Override public void close() { pipe.close(); }
    @Override public boolean isClosed() { return pipe.isClosed(); }
    @Override public String address() { return pipe.pipeName(); }
    @Override public String kind() { return "named-pipe"; }
}
