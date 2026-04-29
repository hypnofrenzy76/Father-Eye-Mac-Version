package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Concrete implementations of every RPC op. All run on the server thread. */
public final class RpcHandlers {

    private RpcHandlers() {}

    public static void registerAll(RpcDispatcher d) {
        d.register("cmd_run", RpcHandlers::cmdRun);
        d.register("cmd_tp", RpcHandlers::cmdTp);
        d.register("cmd_kick", RpcHandlers::cmdKick);
        d.register("cmd_ban", RpcHandlers::cmdBan);
        d.register("cmd_op", RpcHandlers::cmdOp);
        d.register("cmd_whitelist", RpcHandlers::cmdWhitelist);
        d.register("cmd_killEntity", RpcHandlers::cmdKillEntity);
        d.register("cmd_clearMobsInArea", RpcHandlers::cmdClearMobsInArea);
        d.register("cmd_weather", RpcHandlers::cmdWeather);
        d.register("cmd_time", RpcHandlers::cmdTime);
        d.register("srv_stop", RpcHandlers::srvStop);
        d.register("srv_restart", RpcHandlers::srvRestart);
        d.register("chunk_tile", io.fathereye.bridge.rpc.ChunkTileHandler::handle);
    }

    public static void registerJfr(RpcDispatcher d, io.fathereye.bridge.profiler.JfrController jfr) {
        d.register("jfr_start", (args, server) -> jfr.start(args));
        d.register("jfr_stop",  (args, server) -> jfr.stop(args));
    }

    // ----- handlers ----------------------------------------------------

    private static Object cmdRun(JsonNode args, MinecraftServer server) throws CommandSyntaxException {
        String command = args.path("command").asText("");
        if (command.isEmpty()) throw new IllegalArgumentException("command required");
        CommandSource src = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        int rc = server.getCommands().getDispatcher().execute(command, src);
        return ok("rc", rc);
    }

    private static Object cmdTp(JsonNode args, MinecraftServer server) {
        String uuidStr = args.path("playerUuid").asText(null);
        ServerPlayerEntity player = uuidStr == null ? null : server.getPlayerList().getPlayer(UUID.fromString(uuidStr));
        if (player == null) throw new IllegalArgumentException("player not online: " + uuidStr);
        double x = args.path("x").asDouble(player.getX());
        double y = args.path("y").asDouble(player.getY());
        double z = args.path("z").asDouble(player.getZ());
        String dim = args.path("dim").asText(null);
        if (dim != null && !dim.isEmpty()) {
            ServerWorld target = server.getLevel(RegistryKey.create(net.minecraft.util.registry.Registry.DIMENSION_REGISTRY, new ResourceLocation(dim)));
            if (target != null && target != player.level) {
                player.teleportTo(target, x, y, z, player.yRot, player.xRot);
                return ok();
            }
        }
        player.teleportTo(x, y, z);
        return ok();
    }

    // Moderation wrappers — issue the equivalent vanilla commands via the
    // dispatcher rather than touching mapped player-list internals (the
    // exact class names shift between Forge releases). Resilient to mapping drift.
    private static Object cmdKick(JsonNode args, MinecraftServer server) throws CommandSyntaxException {
        String name = lookupName(server, args.path("uuid").asText());
        String reason = args.path("reason").asText("Disconnected.");
        return runVanilla(server, "kick " + name + " " + reason);
    }

    private static Object cmdBan(JsonNode args, MinecraftServer server) throws CommandSyntaxException {
        String name = lookupName(server, args.path("uuid").asText());
        String reason = args.path("reason").asText("Banned.");
        return runVanilla(server, "ban " + name + " " + reason);
    }

    private static Object cmdOp(JsonNode args, MinecraftServer server) throws CommandSyntaxException {
        String name = lookupName(server, args.path("uuid").asText());
        boolean on = args.path("on").asBoolean(true);
        return runVanilla(server, (on ? "op " : "deop ") + name);
    }

    private static Object cmdWhitelist(JsonNode args, MinecraftServer server) throws CommandSyntaxException {
        String name = lookupName(server, args.path("uuid").asText());
        boolean on = args.path("on").asBoolean(true);
        return runVanilla(server, "whitelist " + (on ? "add " : "remove ") + name);
    }

    private static String lookupName(MinecraftServer server, String uuidStr) {
        UUID id = UUID.fromString(uuidStr);
        ServerPlayerEntity online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getName().getString();
        // 1.16.5 GameProfileCache#get takes a name, not a UUID — UUID->name
        // lookup landed in 1.18+. For offline players we don't have a clean
        // path; require the online list. The panel always knows the name
        // (PlayersSnapshot.PlayerEntry.name) and could pass it directly,
        // which is the recommended path for moderation in v0.x.
        throw new IllegalArgumentException("player offline; name-based lookup not available pre-1.18: " + uuidStr);
    }

    private static Object runVanilla(MinecraftServer server, String command) throws CommandSyntaxException {
        CommandSource src = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        int rc = server.getCommands().getDispatcher().execute(command, src);
        return ok("rc", rc);
    }

    private static Object cmdKillEntity(JsonNode args, MinecraftServer server) {
        int entityId = args.path("entityId").asInt();
        for (ServerWorld world : server.getAllLevels()) {
            Entity e = world.getEntity(entityId);
            if (e != null) { e.remove(); return ok(); }
        }
        throw new IllegalArgumentException("entity not found: " + entityId);
    }

    private static Object cmdClearMobsInArea(JsonNode args, MinecraftServer server) {
        String dim = args.path("dim").asText(null);
        ServerWorld world = dim == null ? server.overworld() : server.getLevel(RegistryKey.create(
                net.minecraft.util.registry.Registry.DIMENSION_REGISTRY, new ResourceLocation(dim)));
        if (world == null) throw new IllegalArgumentException("unknown dim: " + dim);
        double x1 = args.path("x1").asDouble(); double z1 = args.path("z1").asDouble();
        double x2 = args.path("x2").asDouble(); double z2 = args.path("z2").asDouble();
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int killed = 0;
        java.util.List<Entity> targets = new java.util.ArrayList<>();
        for (Entity e : world.getAllEntities()) {
            if (e instanceof ServerPlayerEntity) continue;
            if (e.getX() >= minX && e.getX() <= maxX && e.getZ() >= minZ && e.getZ() <= maxZ
                    && e instanceof net.minecraft.entity.monster.IMob) {
                targets.add(e);
            }
        }
        for (Entity e : targets) { e.remove(); killed++; }
        return ok("killed", killed);
    }

    private static Object cmdWeather(JsonNode args, MinecraftServer server) {
        String kind = args.path("kind").asText("clear");
        int duration = args.path("durationTicks").asInt(20 * 60 * 5);
        ServerWorld world = server.overworld();
        switch (kind) {
            case "rain":    world.setWeatherParameters(0, duration, true, false); break;
            case "thunder": world.setWeatherParameters(0, duration, true, true);  break;
            case "clear":
            default:        world.setWeatherParameters(duration, 0, false, false); break;
        }
        return ok();
    }

    private static Object cmdTime(JsonNode args, MinecraftServer server) {
        long value = args.path("value").asLong(0);
        for (ServerWorld w : server.getAllLevels()) {
            w.setDayTime(value);
        }
        return ok();
    }

    private static Object srvStop(JsonNode args, MinecraftServer server) {
        new Thread(() -> { try { server.halt(false); } catch (Throwable ignored) {} }, "FatherEye-StopRequest").start();
        return ok();
    }

    private static Object srvRestart(JsonNode args, MinecraftServer server) {
        // 1.16.5 doesn't expose a restart() so we /stop and rely on the panel
        // launcher to re-start. The panel side knows how to do this.
        return srvStop(args, server);
    }

    // ----- helpers -----------------------------------------------------

    private static Map<String, Object> ok() {
        return Collections.singletonMap("ok", true);
    }
    private static Map<String, Object> ok(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put(key, value);
        return m;
    }

    /** witness so the import isn't pruned. */
    @SuppressWarnings("unused")
    private static void touchTypes() { Class<?> w = World.class; Class<?> g = GameType.class; }
}
