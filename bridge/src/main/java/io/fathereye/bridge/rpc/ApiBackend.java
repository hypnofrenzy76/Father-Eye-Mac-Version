package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fathereye.bridge.api.FatherEyeApi;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Brg-25 (2026-06-12): adapter between the pure-Java {@link FatherEyeApi}
 * registration surface and the bridge's {@link RpcDispatcher}. Decodes
 * each request's JsonNode args into plain collections for the third-party
 * handler and lets the normal response path re-serialise whatever it
 * returns.
 *
 * <p>Built-in ops always win: an external registration whose name
 * collides with one already present on the dispatcher (or another
 * external op) is rejected with a log error rather than silently
 * shadowing panel-critical functionality.
 */
public final class ApiBackend implements FatherEyeApi.Backend {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Rpc");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RpcDispatcher dispatcher;
    private final Set<String> reserved;
    private final Set<String> accepted = new HashSet<>();

    public ApiBackend(RpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.reserved = new HashSet<>(dispatcher.registeredOps());
    }

    @Override
    public void register(String op, FatherEyeApi.RpcHandler handler, boolean direct) {
        if (reserved.contains(op) || !accepted.add(op)) {
            LOG.error("Rejected external RPC '{}': op name already in use", op);
            return;
        }
        RpcDispatcher.Handler adapted = (args, server) -> handler.handle(toMap(args));
        if (direct) {
            dispatcher.registerDirect(op, adapted);
        } else {
            dispatcher.register(op, adapted);
        }
        LOG.info("External RPC registered: {} ({})", op, direct ? "direct" : "tick");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode args) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        if (!args.isObject()) {
            return Collections.singletonMap("value", JSON.convertValue(args, Object.class));
        }
        return JSON.convertValue(args, Map.class);
    }
}
