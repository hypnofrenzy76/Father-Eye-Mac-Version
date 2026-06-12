package io.fathereye.bridge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Public registration point for third-party Forge mods that want to expose
 * RPC methods to the Father Eye desktop panel through the bridge mod.
 *
 * <p><b>Pure Java by design.</b> This class references no Forge, Minecraft
 * or Jackson types, so a consumer mod can ship with a {@code compileOnly}
 * dependency on {@code io.fathereye:bridge-api} and resolve the class at
 * runtime from the bridge's jar. If the bridge mod is not installed the
 * class is absent; consumers should gate their integration behind
 * {@code ModList.get().isLoaded("fathereye_bridge")} (or catch
 * {@link NoClassDefFoundError}) in a dedicated integration class.
 *
 * <p><b>Registration order is decoupled from bridge startup.</b> Mods may
 * register at any point (mod construction, common setup, server starting);
 * registrations made before the bridge installs its backend are queued and
 * flushed on install. The bridge re-installs a fresh backend on every
 * server start, and ALL registrations made so far are replayed into it, so
 * a consumer only ever needs to register once.
 *
 * <p><b>Threading contract for handlers:</b>
 * <ul>
 *   <li>{@link #registerRpc}: the handler runs on the <i>server tick
 *       thread</i>. It may freely touch world, player and entity state.
 *       Keep it fast — it shares the tick budget with gameplay.</li>
 *   <li>{@link #registerDirectRpc}: the handler runs inline on the
 *       bridge's IPC thread. It MUST NOT touch world/entity state; use it
 *       only for reads of your own thread-safe structures.</li>
 * </ul>
 *
 * <p><b>Payloads.</b> Handler input is the request's JSON arguments
 * decoded to plain Java collections ({@code Map<String,Object>},
 * {@code List<Object>}, {@code String}, {@code Number}, {@code Boolean},
 * {@code null}). The returned object is serialised back to JSON the same
 * way; return {@code null} for a generic {@code {"ok":true}}.
 *
 * <p><b>Naming.</b> Ops must match {@code [a-z][a-z0-9_]*} and should be
 * prefixed with your mod id (e.g. {@code arcanum_getRanks}) to avoid
 * collisions; the bridge refuses to overwrite an op that is already
 * registered.
 */
public final class FatherEyeApi {

    /** A panel-callable RPC method. See class javadoc for the threading
     *  and payload contract. */
    public interface RpcHandler {
        Object handle(Map<String, Object> args) throws Exception;
    }

    /**
     * Installed by the bridge mod. Third-party code never implements or
     * calls this; it exists so this module stays free of bridge internals.
     */
    public interface Backend {
        void register(String op, RpcHandler handler, boolean direct);
    }

    // Lowercase start, then camelCase/underscores — matches both the
    // bridge's built-in style (cmd_run, chunk_tile) and consumer style
    // (arcanum_getConfig). The first release of this pattern rejected
    // uppercase entirely and broke every camelCase registration at boot.
    private static final Pattern OP_NAME = Pattern.compile("[a-z][a-zA-Z0-9_]*");

    private static final Object LOCK = new Object();
    private static final Map<String, Registration> registrations = new LinkedHashMap<>();
    private static final Set<String> extraCapabilities = new LinkedHashSet<>();
    private static Backend backend;

    private FatherEyeApi() {}

    /** Register a tick-thread RPC handler (may access world state). */
    public static void registerRpc(String op, RpcHandler handler) {
        register(op, handler, false);
    }

    /** Register an IPC-thread RPC handler (must NOT access world state). */
    public static void registerDirectRpc(String op, RpcHandler handler) {
        register(op, handler, true);
    }

    /**
     * Advertise a capability string in the bridge's session hello so the
     * panel can detect the integration (e.g. {@code "arcanum"}) and show
     * or hide its pane accordingly.
     */
    public static void addCapability(String capability) {
        if (capability == null || capability.isEmpty()) {
            throw new IllegalArgumentException("capability must be non-empty");
        }
        synchronized (LOCK) {
            extraCapabilities.add(capability);
        }
    }

    /** @return true once the bridge has installed its backend (server
     *  running with the bridge mod present). */
    public static boolean isConnected() {
        synchronized (LOCK) {
            return backend != null;
        }
    }

    // ------------------------------------------------------------------
    // Bridge-internal wiring (stable for the bridge, not for consumers)

    /** Bridge-internal: install the live backend and replay every
     *  registration made so far (in registration order). */
    public static void installBackend(Backend b) {
        List<Registration> replay;
        synchronized (LOCK) {
            backend = b;
            replay = new ArrayList<>(registrations.values());
        }
        for (Registration r : replay) {
            b.register(r.op, r.handler, r.direct);
        }
    }

    /** Bridge-internal: detach the backend on server stop. Registrations
     *  are retained and replayed into the next backend. */
    public static void uninstallBackend() {
        synchronized (LOCK) {
            backend = null;
        }
    }

    /** Bridge-internal: capabilities contributed by third-party mods. */
    public static String[] capabilities() {
        synchronized (LOCK) {
            return extraCapabilities.toArray(new String[0]);
        }
    }

    private static void register(String op, RpcHandler handler, boolean direct) {
        if (op == null || !OP_NAME.matcher(op).matches()) {
            throw new IllegalArgumentException("invalid rpc op name: " + op);
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        Backend b;
        synchronized (LOCK) {
            if (registrations.containsKey(op)) {
                throw new IllegalStateException("rpc op already registered: " + op);
            }
            registrations.put(op, new Registration(op, handler, direct));
            b = backend;
        }
        if (b != null) {
            b.register(op, handler, direct);
        }
    }

    private static final class Registration {
        final String op;
        final RpcHandler handler;
        final boolean direct;

        Registration(String op, RpcHandler handler, boolean direct) {
            this.op = op;
            this.handler = handler;
            this.direct = direct;
        }
    }
}
