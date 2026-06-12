package io.fathereye.bridge.profiler;

import io.fathereye.bridge.topic.MobsSnapshot;
import io.fathereye.bridge.topic.PlayersSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Walks the running server's player list and entity lists to build
 * immutable snapshots.
 *
 * <p>Brg-24 (2026-06-12): now called ONLY from the server tick thread
 * (via {@link TickStateMirror#install()}'s 1 Hz snapshot rebuild), so
 * the iteration is fully safe. The old off-thread "races are
 * tolerable" caveat is gone, and so is {@code collectChunks()}: spark
 * profile IzzU6Fus2J showed its per-holder {@code getChunk(FULL,false)}
 * filter — running on the Publisher thread — marshalled every call
 * onto the tick thread ({@code supplyAsync(..).join()}) and accounted
 * for 32.7% of total server-thread time. Chunk enumeration now lives
 * in {@link TickStateMirror#chunksSnapshot()} using
 * {@link ChunkTileRenderer#loadedHolders}/{@code lastAvailable}, which
 * never trigger load/gen and never leave the tick thread.
 */
public final class WorldStateCollector {

    private WorldStateCollector() {}

    public static PlayersSnapshot collectPlayers() {
        PlayersSnapshot snap = new PlayersSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return snap;

        try {
            for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
                PlayersSnapshot.PlayerEntry e = new PlayersSnapshot.PlayerEntry();
                e.uuid = p.getUUID().toString();
                e.name = p.getName().getString();
                e.dimensionId = p.level.dimension().location().toString();
                e.x = p.getX();
                e.y = p.getY();
                e.z = p.getZ();
                e.yaw = p.yRot;
                e.health = (int) p.getHealth();
                e.food = p.getFoodData() == null ? 20 : p.getFoodData().getFoodLevel();
                e.pingMs = p.latency;
                e.onlineSinceEpochMs = 0L; // populated by JoinTracker (M8 follow-up)
                e.gameMode = p.gameMode.getGameModeForPlayer().getName();
                snap.players.add(e);
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return snap;
    }

    public static MobsSnapshot collectMobs() {
        MobsSnapshot snap = new MobsSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return snap;

        for (ServerWorld world : server.getAllLevels()) {
            String dim = world.dimension().location().toString();
            Map<String, MobsSnapshot.ModEntityCounts> byMod = new LinkedHashMap<>();
            try {
                for (Entity e : world.getAllEntities()) {
                    String ns = namespaceOf(e.getType());
                    MobsSnapshot.ModEntityCounts c = byMod.computeIfAbsent(ns, k -> new MobsSnapshot.ModEntityCounts());
                    c.total++;
                    if (e instanceof IMob)               c.hostile++;
                    else if (e instanceof AnimalEntity)  c.passive++;
                    else if (e instanceof ItemEntity)    c.items++;
                }
            } catch (Throwable ignored) {}
            snap.byDim.put(dim, byMod);
        }
        return snap;
    }

    private static String namespaceOf(EntityType<?> type) {
        try {
            return type.getRegistryName() == null ? "unknown" : type.getRegistryName().getNamespace();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Compile-time witness that MobEntity is on the classpath for our hostile/passive checks. */
    @SuppressWarnings("unused")
    private static void touchMobEntity() { Class<?> c = MobEntity.class; }
}
