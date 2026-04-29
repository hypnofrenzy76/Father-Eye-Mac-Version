package io.fathereye.bridge.ipc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accept loop on a daemon thread. Runs one IpcSession at a time
 * (single-user, localhost, single panel connection by design).
 */
public final class PipeAcceptLoop implements Runnable {

    private static final Logger LOG = LogManager.getLogger("FatherEye-PipeAccept");

    public interface TransportFactory {
        TransportServer create(boolean firstInstance) throws IOException;
        String address();
        String kind();
    }

    private final TransportFactory transportFactory;
    private final UUID instanceUuid;
    private final IpcSession.ServerInfoSupplier info;
    private final Publisher publisher;
    private final io.fathereye.bridge.rpc.RpcDispatcher rpc;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private TransportServer current;
    private Thread thread;

    public PipeAcceptLoop(TransportFactory transportFactory, UUID instanceUuid, IpcSession.ServerInfoSupplier info,
                          Publisher publisher, io.fathereye.bridge.rpc.RpcDispatcher rpc) {
        this.transportFactory = transportFactory;
        this.instanceUuid = instanceUuid;
        this.info = info;
        this.publisher = publisher;
        this.rpc = rpc;
    }

    public String transportAddress() { return transportFactory.address(); }
    public String transportKind() { return transportFactory.kind(); }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this, "FatherEye-PipeAccept");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        stopped.set(true);
        TransportServer p = current;
        if (p != null) p.close();
        if (thread != null) {
            try { thread.join(2_000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            thread = null;
        }
    }

    @Override
    public void run() {
        boolean firstInstance = true;
        while (!stopped.get()) {
            try {
                TransportServer transport = transportFactory.create(firstInstance);
                firstInstance = false;
                current = transport;
                LOG.info("Awaiting panel connection on {}", transport.address());
                transport.accept();
                if (stopped.get()) { transport.close(); break; }
                LOG.info("Panel connected.");
                IpcSession session = new IpcSession(transport, instanceUuid, info, publisher, rpc);
                try {
                    session.run();
                } finally {
                    transport.close();
                    current = null;
                    LOG.info("Panel disconnected; recycling transport.");
                }
            } catch (IOException ioe) {
                if (stopped.get()) break;
                LOG.warn("Accept iteration failed: {}", ioe.getMessage());
                try { Thread.sleep(500L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Throwable t) {
                LOG.error("Accept loop fatal", t);
                break;
            }
        }
        LOG.info("Accept loop exiting.");
    }
}
