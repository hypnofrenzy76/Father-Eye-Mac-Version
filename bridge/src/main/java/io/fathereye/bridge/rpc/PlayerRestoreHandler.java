package io.fathereye.bridge.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.network.play.server.SPlayerAbilitiesPacket;
import net.minecraft.network.play.server.SSetExperiencePacket;
import net.minecraft.network.play.server.SUpdateHealthPacket;
import net.minecraft.network.play.server.SWindowItemsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Live player-state restore. Injects a player's full saved NBT (the exact
 * {@code <uuid>.dat} body from a Father Eye backup) into the running server,
 * whether that player is currently online or offline.
 *
 * <p><b>Online players</b> are restored without disconnecting them:
 * <ol>
 *   <li>The current live state is captured into a safety {@code .dat} so the
 *       operator can undo a bad restore.</li>
 *   <li>{@link ServerPlayerEntity#load(CompoundNBT)} (the same entry point
 *       vanilla's {@code PlayerList.load} uses on login) reads the backup
 *       NBT into the live entity: inventory, ender chest, XP, health, food,
 *       position, dimension, abilities, attributes.</li>
 *   <li>The new state is pushed to the client with the same packets the
 *       server sends on a normal respawn/login so the player's screen
 *       reflects the restored inventory, bars, and position immediately.</li>
 * </ol>
 *
 * <p><b>Offline players</b> have a safety copy of their existing
 * {@code playerdata/<uuid>.dat} taken, then the backup NBT is written over it
 * so the data loads the next time they log in.
 *
 * <p>All work runs on the server tick thread (the dispatcher marshals it
 * there), so touching live entity and world state here is safe. The handler
 * never extracts archives or touches the external backup volume: the web
 * portal already did that and hands over the decoded NBT as base64.
 */
public final class PlayerRestoreHandler {

    private static final Logger LOG = LogManager.getLogger("FatherEye-PlayerRestore");

    private PlayerRestoreHandler() {}

    public static void register(RpcDispatcher d) {
        d.register("player_restoreState", PlayerRestoreHandler::handle);
    }

    static Object handle(JsonNode args, MinecraftServer server) throws Exception {
        String uuidStr = args.path("playerUuid").asText(null);
        String nbtB64 = args.path("nbtBase64").asText(null);
        if (uuidStr == null || uuidStr.isEmpty()) throw new IllegalArgumentException("playerUuid required");
        if (nbtB64 == null || nbtB64.isEmpty()) throw new IllegalArgumentException("nbtBase64 required");

        final UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("playerUuid is not a valid UUID: " + uuidStr);
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(nbtB64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("nbtBase64 is not valid base64");
        }

        CompoundNBT backupNbt;
        try (ByteArrayInputStream in = new ByteArrayInputStream(raw)) {
            backupNbt = CompressedStreamTools.readCompressed(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("payload is not a valid gzip NBT player file: " + e.getMessage());
        }
        if (backupNbt == null || backupNbt.isEmpty()) {
            throw new IllegalArgumentException("decoded player NBT is empty");
        }

        Path playerDataDir = resolvePlayerDataDir(args, server);
        Path safety = writeSafetySnapshot(playerDataDir, uuid, server);

        ServerPlayerEntity online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            applyToLivePlayer(online, backupNbt, server);
            LOG.info("Restored LIVE player {} (safety={})", uuid, safety);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("mode", "online");
            r.put("player", uuid.toString());
            r.put("safetyFile", safety == null ? null : safety.toString());
            return r;
        }

        // Offline: write the backup NBT over the on-disk file.
        Path target = playerDataDir.resolve(uuid.toString() + ".dat");
        Path tmp = playerDataDir.resolve(uuid.toString() + ".dat.fe-tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            CompressedStreamTools.writeCompressed(backupNbt, out);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        LOG.info("Restored OFFLINE player {} -> {} (safety={})", uuid, target, safety);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("mode", "offline");
        r.put("player", uuid.toString());
        r.put("safetyFile", safety == null ? null : safety.toString());
        return r;
    }

    /**
     * Load the backup NBT into the live entity and resend the affected state
     * to the client. The {@code load} call mirrors what the server does on
     * login, so every persisted field (inventory, ender items, XP, health,
     * food, position, dimension, attributes, abilities) is applied at once.
     */
    private static void applyToLivePlayer(ServerPlayerEntity player, CompoundNBT nbt, MinecraftServer server) {
        // Snapshot the dimension the backup says the player belongs in; if it
        // differs from where they are now we move them after the load.
        ServerWorld targetWorld = dimensionFromNbt(nbt, server);
        boolean crossDimension = targetWorld != null && targetWorld != player.level;

        // Entity.load() reads "Dimension" and stuffs it into the entity's
        // dimension reference, but it does NOT move the entity between the
        // source and target ServerWorlds' entity/chunk registries. Loading a
        // foreign dimension string therefore leaves the live entity pointing
        // at a world it is not registered in, and the subsequent teleportTo()
        // tries to remove it from a world it was never added to (desync /
        // ghost entity). Strip "Dimension" from the copy we load so the
        // entity keeps its CURRENT, correctly-registered world; we then do an
        // explicit, fully-registered cross-dimension teleport below.
        CompoundNBT toLoad = nbt;
        if (crossDimension && nbt.contains("Dimension")) {
            toLoad = nbt.copy();
            toLoad.remove("Dimension");
        }

        player.load(toLoad);

        // ---- resync to client (no resetSentInfo() in 1.16.5) -------------
        // Position / dimension: if the loaded data placed the player in a
        // different world, do a real cross-dimension teleport so the client
        // loads the right chunks; otherwise reseat them at the loaded coords.
        double x = player.getX(), y = player.getY(), z = player.getZ();
        float yaw = player.yRot, pitch = player.xRot;
        if (crossDimension) {
            // changeDimension/teleportTo moves the entity between the source
            // and target ServerWorlds' registries the way vanilla does, so no
            // ghost is left behind in the old world.
            player.teleportTo(targetWorld, x, y, z, yaw, pitch);
        } else {
            // Same dimension: snap the client to the loaded coordinates.
            player.connection.teleport(x, y, z, yaw, pitch);
        }

        // Inventory: mark dirty and rebroadcast the open container (the
        // player's own inventory menu) so every slot is resent.
        player.inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        // Force-resend the survival inventory container so the client's
        // backpack/armor/offhand all update even if no GUI is open.
        player.connection.send(new SWindowItemsPacket(
                player.inventoryMenu.containerId,
                player.inventoryMenu.getItems()));

        // Health / food / saturation.
        player.connection.send(new SUpdateHealthPacket(
                player.getHealth(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel()));

        // Experience bar / level / total.
        player.connection.send(new SSetExperiencePacket(
                player.experienceProgress,
                player.totalExperience,
                player.experienceLevel));

        // Abilities (fly/instabuild flags loaded from NBT).
        player.connection.send(new SPlayerAbilitiesPacket(player.abilities));

        // Recompute hitbox/size in case pose-affecting state changed.
        player.refreshDimensions();
    }

    /** Resolve the dimension the backup NBT pins, or null if unparseable. */
    private static ServerWorld dimensionFromNbt(CompoundNBT nbt, MinecraftServer server) {
        try {
            if (!nbt.contains("Dimension")) return null;
            // 1.16.5 stores Dimension as a string resource location.
            String dim = nbt.getString("Dimension");
            if (dim == null || dim.isEmpty()) return null;
            RegistryKey<World> key = RegistryKey.create(
                    Registry.DIMENSION_REGISTRY, new ResourceLocation(dim));
            return server.getLevel(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Write the player's CURRENT state to a timestamped safety {@code .dat}
     * next to the playerdata dir, so a bad restore can be undone. For an
     * online player we serialise the live entity; for an offline player we
     * copy the existing on-disk file. Returns the safety path, or null if no
     * prior state existed (brand-new player) or the snapshot failed (which is
     * logged but never blocks the restore the operator asked for).
     */
    private static Path writeSafetySnapshot(Path playerDataDir, UUID uuid, MinecraftServer server) {
        try {
            Path safetyDir = playerDataDir.resolve("fe-pre-restore");
            Files.createDirectories(safetyDir);
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                    .format(new java.util.Date());
            Path safety = safetyDir.resolve(uuid + "-" + stamp + ".dat");

            ServerPlayerEntity online = server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                CompoundNBT cur = new CompoundNBT();
                online.save(cur);
                Path tmp = safetyDir.resolve(uuid + "-" + stamp + ".dat.tmp");
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    CompressedStreamTools.writeCompressed(cur, out);
                }
                Files.move(tmp, safety, StandardCopyOption.REPLACE_EXISTING);
                return safety;
            }

            Path existing = playerDataDir.resolve(uuid.toString() + ".dat");
            if (Files.isRegularFile(existing)) {
                Files.copy(existing, safety, StandardCopyOption.REPLACE_EXISTING);
                return safety;
            }
            return null; // nothing to snapshot
        } catch (IOException | RuntimeException e) {
            LOG.warn("safety snapshot for {} failed (restore continues): {}", uuid, e.toString());
            return null;
        }
    }

    /**
     * Resolve the live server's {@code playerdata} directory. Prefer the
     * world path the running server exposes; fall back to an explicit
     * {@code serverDir} arg + {@code level-name} if needed.
     */
    private static Path resolvePlayerDataDir(JsonNode args, MinecraftServer server) {
        // Primary: ask the running server for its world player-data folder.
        try {
            Path p = server.getWorldPath(
                    net.minecraft.world.storage.FolderName.PLAYER_DATA_DIR);
            if (p != null) return p;
        } catch (RuntimeException ignored) {
            // fall through to the explicit path
        }
        // Fallback: <serverDir>/<level-name>/playerdata.
        String serverDir = args.path("serverDir").asText(null);
        String levelName = args.path("levelName").asText("world");
        if (serverDir == null || serverDir.isEmpty()) {
            throw new IllegalStateException("cannot resolve playerdata dir: no world path and no serverDir arg");
        }
        return Paths.get(serverDir, levelName, "playerdata");
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> ok() { return Collections.singletonMap("ok", true); }
}
