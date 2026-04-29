package io.fathereye.bridge.profiler;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import io.fathereye.bridge.util.Constants;

import java.util.Arrays;

/**
 * Tracks tick durations on the server tick event. Maintains rolling windows
 * for 20 s / 1 min / 5 min averages and computes percentiles from the most
 * recent 1200 ticks (1 minute) of MSPT samples.
 *
 * <p>Single-thread access: all writes happen on the server tick thread; reads
 * for snapshot happen on the publisher thread. Snapshots are therefore best-
 * effort consistent (small races acceptable).
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TpsCollector {

    private static final int WINDOW_TICKS = 1200; // 60s @ 20 Hz

    private static final long[] tickNanos = new long[WINDOW_TICKS];
    private static int tickIdx = 0;
    private static int tickCount = 0;
    private static long lastTickStartNanos = 0L;

    // Wall-clock counters for TPS-by-window:
    private static long bucket20sStart = 0;
    private static int bucket20sCount = 0;
    private static long bucket1mStart = 0;
    private static int bucket1mCount = 0;
    private static long bucket5mStart = 0;
    private static int bucket5mCount = 0;

    private TpsCollector() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        long now = System.nanoTime();
        if (lastTickStartNanos != 0L) {
            long delta = now - lastTickStartNanos;
            tickNanos[tickIdx] = delta;
            tickIdx = (tickIdx + 1) % WINDOW_TICKS;
            if (tickCount < WINDOW_TICKS) tickCount++;
        }
        lastTickStartNanos = now;

        long nowMs = System.currentTimeMillis();
        if (bucket20sStart == 0) {
            bucket20sStart = bucket1mStart = bucket5mStart = nowMs;
        }
        bucket20sCount++;
        bucket1mCount++;
        bucket5mCount++;
        rotateBuckets(nowMs);
    }

    private static void rotateBuckets(long nowMs) {
        // Keep the divisor sane: capture and reset only when the window
        // has elapsed; otherwise the snapshot uses the current partial.
        if (nowMs - bucket20sStart >= 20_000) {
            cachedTps20s = (double) bucket20sCount * 1000.0 / (nowMs - bucket20sStart);
            bucket20sStart = nowMs;
            bucket20sCount = 0;
        }
        if (nowMs - bucket1mStart >= 60_000) {
            cachedTps1m = (double) bucket1mCount * 1000.0 / (nowMs - bucket1mStart);
            bucket1mStart = nowMs;
            bucket1mCount = 0;
        }
        if (nowMs - bucket5mStart >= 300_000) {
            cachedTps5m = (double) bucket5mCount * 1000.0 / (nowMs - bucket5mStart);
            bucket5mStart = nowMs;
            bucket5mCount = 0;
        }
    }

    private static volatile double cachedTps20s = 20.0;
    private static volatile double cachedTps1m  = 20.0;
    private static volatile double cachedTps5m  = 20.0;

    /** Fill TPS/MSPT fields of the snapshot. Called from publisher thread. */
    public static void fill(io.fathereye.bridge.topic.TpsSnapshot snap) {
        long nowMs = System.currentTimeMillis();
        // Live partial: fall back to instantaneous measurement using the
        // most recent partial bucket if a full window hasn't elapsed yet.
        snap.tps20s = liveTps(cachedTps20s, bucket20sStart, bucket20sCount, nowMs);
        snap.tps1m  = liveTps(cachedTps1m,  bucket1mStart,  bucket1mCount,  nowMs);
        snap.tps5m  = liveTps(cachedTps5m,  bucket5mStart,  bucket5mCount,  nowMs);

        int n = tickCount;
        if (n == 0) {
            snap.msptAvg = snap.msptP50 = snap.msptP95 = snap.msptP99 = 0.0;
            return;
        }
        long[] copy = Arrays.copyOf(tickNanos, n);
        Arrays.sort(copy);
        long sum = 0;
        for (long v : copy) sum += v;
        snap.msptAvg = (sum / (double) n) / 1_000_000.0;
        snap.msptP50 = copy[(int) (n * 0.50)] / 1_000_000.0;
        snap.msptP95 = copy[(int) (n * 0.95)] / 1_000_000.0;
        snap.msptP99 = copy[Math.max(0, (int) (n * 0.99) - 1)] / 1_000_000.0;
    }

    private static double liveTps(double cached, long bucketStart, int bucketCount, long nowMs) {
        long elapsed = Math.max(1, nowMs - bucketStart);
        double partial = (double) bucketCount * 1000.0 / elapsed;
        // If we have any data in the partial bucket, prefer it (fresher).
        return bucketCount > 0 ? partial : cached;
    }
}
