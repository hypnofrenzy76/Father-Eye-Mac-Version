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
 */
public final class RpcDispatcher {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Rpc");

    public interface Handler {
        Object handle(JsonNode args, MinecraftServer server) throws Exception;
    }

    private final Map<String, Handler> handlers = new HashMap<>();

    public void register(String op, Handler h) { handlers.put(op, h); }

    public CompletableFuture<Object> dispatch(String op, JsonNode args) {
        Handler h = handlers.get(op);
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
