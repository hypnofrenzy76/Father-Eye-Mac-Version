package io.fathereye.bridge.profiler;

import io.fathereye.bridge.util.Constants;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks per-world tick wall time using {@link TickEvent.WorldTickEvent}
 * START/END hooks. The accumulated nanoseconds are read by {@link
 * ModsImpactCollector} and reset on snapshot publish.
 *
 * <p>Note: this is not the Mixin-based per-entity-tick profiler from the
 * design doc — that's a separate future milestone. This event-based hook
 * gives <i>per-dimension</i> tick wall time, which the collector then
 * attributes to mods proportionally to entity/TE counts. The result is a
 * useful approximation suitable for "which mod is making the world tick
 * slow?" without the Mixin-vs-coremod compatibility risks.
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldTickProfiler {

    private static final ConcurrentHashMap<String, LongAdder> tickNanosByDim = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> tickStartNanos = new ThreadLocal<>();

    private WorldTickProfiler() {}

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (!(event.world instanceof net.minecraft.world.server.ServerWorld)) return;
        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos.set(System.nanoTime());
        } else {
            Long start = tickStartNanos.get();
            if (start == null) return;
            long delta = System.nanoTime() - start;
            tickStartNanos.remove();
            String dim = dimIdOf(event.world);
            tickNanosByDim.computeIfAbsent(dim, k -> new LongAdder()).add(delta);
        }
    }

    public static long readAndReset(String dim) {
        LongAdder a = tickNanosByDim.get(dim);
        if (a == null) return 0L;
        long v = a.sumThenReset();
        return v;
    }

    private static String dimIdOf(World world) {
        RegistryKey<World> key = world.dimension();
        return key == null || key.location() == null ? "unknown" : key.location().toString();
    }
}
