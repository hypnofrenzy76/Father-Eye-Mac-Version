package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Routes incoming Request frames to op-specific handlers running on the
 * Minecraft server tick thread (via {@link MinecraftServer#execute}). The
 * resulting CompletableFuture completes when the handler runs.
 *
 * <p>Brg-24 (2026-06-12): added {@link #registerDirect}. Handlers that
 * never touch world state (chunk_tile is now a pure cache read) run
 * inline on the calling IPC thread instead of being marshalled onto
 * the tick thread. Before this, every chunk_tile RPC cost a
 * server.execute() hop: queue pressure on the tick thread plus a full
 * tick of added latency per tile, multiplied by thousands of tiles
 * when a panel connects.
 */
public final class RpcDispatcher {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Rpc");

    public interface Handler {
        Object handle(JsonNode args, MinecraftServer server) throws Exception;
    }

    private final Map<String, Handler> handlers = new HashMap<>();
    /** Brg-24: ops served inline on the IPC thread. MUST NOT touch
     *  world/entity state — thread-safe bridge structures only. */
    private final Map<String, Handler> directHandlers = new HashMap<>();

    public void register(String op, Handler h) { handlers.put(op, h); }

    public void registerDirect(String op, Handler h) { directHandlers.put(op, h); }

    public CompletableFuture<Object> dispatch(String op, JsonNode args) {
        Handler direct = directHandlers.get(op);
        Handler h = direct != null ? direct : handlers.get(op);
        if (h == null) {
            CompletableFuture<Object> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("unknown op: " + op));
            return f;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            CompletableFuture<Object> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("server not running"));
            return f;
        }
        if (direct != null) {
            CompletableFuture<Object> f = new CompletableFuture<>();
            try {
                Object result = direct.handle(args, server);
                f.complete(result == null ? java.util.Collections.singletonMap("ok", true) : result);
            } catch (Throwable t) {
                LOG.warn("RPC {} (direct) failed: {}", op, t.toString());
                f.completeExceptionally(t);
            }
            return f;
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    Object result = h.handle(args, server);
                    future.complete(result == null ? java.util.Collections.singletonMap("ok", true) : result);
                } catch (Throwable t) {
                    LOG.warn("RPC {} failed: {}", op, t.toString());
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }
}
