package io.fathereye.bridge.lifecycle;

import io.fathereye.bridge.ipc.IpcEnvelope;
import io.fathereye.bridge.ipc.IpcSession;
import io.fathereye.bridge.ipc.MarkerFile;
import io.fathereye.bridge.ipc.NamedPipeTransportServer;
import io.fathereye.bridge.ipc.PipeAcceptLoop;
import io.fathereye.bridge.ipc.Publisher;
import io.fathereye.bridge.ipc.TcpTransportServer;
import io.fathereye.bridge.ipc.TransportServer;
import io.fathereye.bridge.util.Constants;
import io.fathereye.bridge.util.InstanceUuid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.versions.forge.ForgeVersion;
import net.minecraftforge.versions.mcp.MCPVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BridgeLifecycle {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Bridge");

    private static MarkerFile marker;
    private static PipeAcceptLoop accept;
    private static Publisher publisher;
    private static io.fathereye.bridge.log.IpcAppender logAppender;
    private static io.fathereye.bridge.rpc.RpcDispatcher rpc;
    private static io.fathereye.bridge.profiler.JfrController jfr;
    private static java.net.ServerSocket sharedServerSocket;

    private BridgeLifecycle() {}

    @SubscribeEvent
    public static void onServerStarting(FMLServerStartingEvent event) {
        try {
            MinecraftServer server = event.getServer();
            Path worldRoot = server.getWorldPath(FolderName.ROOT);
            Path serverConfigDir = worldRoot.resolve("serverconfig");
            UUID uuid = InstanceUuid.loadOrCreate(serverConfigDir);

            IpcSession.ServerInfoSupplier info = buildServerInfo(server);

            publisher = new Publisher();
            publisher.start();

            // Log appender installed AFTER publisher exists; closure captures it.
            final Publisher pubRef = publisher;
            logAppender = io.fathereye.bridge.log.IpcAppender.install(pubRef::publishLogLine);

            rpc = new io.fathereye.bridge.rpc.RpcDispatcher();
            io.fathereye.bridge.rpc.RpcHandlers.registerAll(rpc);
            jfr = new io.fathereye.bridge.profiler.JfrController(server.getServerDirectory().toPath());
            io.fathereye.bridge.rpc.RpcHandlers.registerJfr(rpc, jfr);

            // Pnl-22 / Brg-12: transport is now ALWAYS TCP localhost on an
            // ephemeral port — Windows included. The original Windows named-pipe
            // path had an intractable read+write deadlock on synchronous
            // pipe handles, and overlapped I/O via JNA Structure auto-write
            // semantics produced 0-byte reads even after several iterations
            // of fixes. TCP localhost is well-tested, has no kernel-level
            // serialisation between concurrent reads and writes, and the
            // Mac/Linux path was already using it cleanly. Single transport
            // simplifies maintenance.
            //
            // Bind ONE persistent ServerSocket; share it across accept cycles.
            // Avoids the bind-and-rebind race where another process could grab
            // the port between probe.close() and the first cycle's create()
            // (Audit B1).
            final java.net.ServerSocket sharedServer = new java.net.ServerSocket();
            sharedServer.setReuseAddress(true);
            sharedServer.bind(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), 0));
            final int actualPort = sharedServer.getLocalPort();
            sharedServerSocket = sharedServer;
            String transportKind = "tcp";
            String transportAddress = "127.0.0.1:" + actualPort;
            PipeAcceptLoop.TransportFactory factory = new PipeAcceptLoop.TransportFactory() {
                @Override public TransportServer create(boolean firstInstance) {
                    return new TcpTransportServer(sharedServer);
                }
                @Override public String address() { return "127.0.0.1:" + actualPort; }
                @Override public String kind() { return "tcp"; }
            };

            accept = new PipeAcceptLoop(factory, uuid, info, publisher, rpc);
            accept.start();

            marker = new MarkerFile(uuid);
            marker.write(transportKind, transportAddress, MCPVersion.getMCVersion(),
                    ForgeVersion.getVersion(), server.getServerDirectory().toString());

            LOG.info("Father Eye Bridge {} ready on {} ({})",
                    Constants.BRIDGE_VERSION, transportAddress, transportKind);
        } catch (Throwable t) {
            LOG.error("Father Eye Bridge failed to start", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(FMLServerStoppingEvent event) {
        try {
            if (logAppender != null) logAppender.uninstall();
            if (accept != null) accept.stop();
            if (publisher != null) publisher.stop();
            if (jfr != null) jfr.closeAll();
            if (sharedServerSocket != null) {
                try { sharedServerSocket.close(); } catch (java.io.IOException ignored) {}
            }
            if (marker != null) marker.delete();
            LOG.info("Father Eye Bridge stopped.");
        } catch (Throwable t) {
            LOG.error("Father Eye Bridge shutdown error", t);
        } finally {
            logAppender = null;
            accept = null;
            publisher = null;
            jfr = null;
            rpc = null;
            sharedServerSocket = null;
            marker = null;
        }
    }

    private static IpcSession.ServerInfoSupplier buildServerInfo(MinecraftServer server) {
        final String mc = MCPVersion.getMCVersion();
        final String forge = ForgeVersion.getVersion();
        final String[] caps = new String[] { "tps", "console_log", "players", "mods_impact", "chunk_tile", "jfr" };

        List<String> dims = new ArrayList<>();
        server.levelKeys().forEach(k -> dims.add(k.location().toString()));
        final String[] dimsArr = dims.toArray(new String[0]);

        List<IpcEnvelope.ModInfo> mods = new ArrayList<>();
        ModList.get().getMods().forEach(m -> mods.add(new IpcEnvelope.ModInfo(m.getModId(), m.getVersion().toString())));
        final IpcEnvelope.ModInfo[] modsArr = mods.toArray(new IpcEnvelope.ModInfo[0]);

        return new IpcSession.ServerInfoSupplier() {
            @Override public String mcVersion()       { return mc; }
            @Override public String forgeVersion()    { return forge; }
            @Override public String[] capabilities()  { return caps; }
            @Override public String[] dimensions()    { return dimsArr; }
            @Override public IpcEnvelope.ModInfo[] mods() { return modsArr; }
        };
    }
}
