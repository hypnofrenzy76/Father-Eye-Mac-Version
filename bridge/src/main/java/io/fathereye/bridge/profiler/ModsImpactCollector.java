package io.fathereye.bridge.profiler;

import io.fathereye.bridge.topic.MobsSnapshot;
import io.fathereye.bridge.topic.ModsImpactSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a {@link ModsImpactSnapshot} by combining per-world tick wall time
 * (from {@link WorldTickProfiler}) with per-mod entity + TE counts (walked
 * fresh here). The attribution is proportional: a mod's share of dim tick
 * time = (mod entity count + mod TE count) / total of those.
 *
 * <p>Coarser than per-entity timing but cheap and free of Mixin compatibility
 * risk. The plan permits upgrading to Mixin in a follow-up.
 */
public final class ModsImpactCollector {

    private ModsImpactCollector() {}

    public static ModsImpactSnapshot collect() {
        ModsImpactSnapshot snap = new ModsImpactSnapshot();
        snap.timestampMs = System.currentTimeMillis();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return snap;

        for (ServerWorld world : server.getAllLevels()) {
            String dim = world.dimension().location().toString();
            long tickNanos = WorldTickProfiler.readAndReset(dim);

            Map<String, ModsImpactSnapshot.ImpactSlot> byMod = new LinkedHashMap<>();
            int totalParts = 0;

            try {
                for (Entity e : world.getAllEntities()) {
                    String ns = ns(e.getType().getRegistryName());
                    ModsImpactSnapshot.ImpactSlot slot = byMod.computeIfAbsent(ns, k -> new ModsImpactSnapshot.ImpactSlot());
                    slot.entityCount++;
                    totalParts++;
                }
            } catch (Throwable ignored) {}

            // 1.16.5: ServerWorld inherits World#blockEntityList (= loaded TEs).
            // Iterate a defensive copy because the server thread may mutate it.
            try {
                Iterator<TileEntity> it = new java.util.ArrayList<>(world.blockEntityList).iterator();
                while (it.hasNext()) {
                    TileEntity te = it.next();
                    if (te.getType() == null) continue;
                    String ns = ns(te.getType().getRegistryName());
                    ModsImpactSnapshot.ImpactSlot slot = byMod.computeIfAbsent(ns, k -> new ModsImpactSnapshot.ImpactSlot());
                    slot.tileEntityCount++;
                    totalParts++;
                }
            } catch (Throwable ignored) {}

            // Distribute attributed nanos proportionally to (entityCount + tileEntityCount).
            if (totalParts > 0) {
                for (ModsImpactSnapshot.ImpactSlot slot : byMod.values()) {
                    int parts = slot.entityCount + slot.tileEntityCount;
                    slot.attributedNanos = (long) (((double) parts / totalParts) * tickNanos);
                }
            }

            snap.byDim.put(dim, byMod);
        }
        return snap;
    }

    private static String ns(net.minecraft.util.ResourceLocation id) {
        return id == null ? "unknown" : id.getNamespace();
    }

    /** Witness so the import isn't pruned. */
    @SuppressWarnings("unused")
    private static void touchMob() { Class<?> ignored = MobsSnapshot.class; }
}
