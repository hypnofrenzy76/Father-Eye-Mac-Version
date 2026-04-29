package io.fathereye.bridge.jmx;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic 1 Hz JMX sampling: heap, non-heap, GC pause delta, process CPU,
 * thread CPU per server thread. Runs on the {@link Schedulers} pool.
 */
public final class JmxSampler {

    private final MemoryMXBean memMx = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean osMx = ManagementFactory.getOperatingSystemMXBean();

    private final AtomicLong gcCollectionCountPrev = new AtomicLong(0);
    private final AtomicLong gcCollectionTimePrev = new AtomicLong(0);

    public JmxSampler() {
        if (threadMx.isThreadCpuTimeSupported() && !threadMx.isThreadCpuTimeEnabled()) {
            try { threadMx.setThreadCpuTimeEnabled(true); } catch (SecurityException ignored) {}
        }
        // Prime baselines.
        long count = 0, time = 0;
        for (GarbageCollectorMXBean g : gcBeans) {
            count += Math.max(0, g.getCollectionCount());
            time  += Math.max(0, g.getCollectionTime());
        }
        gcCollectionCountPrev.set(count);
        gcCollectionTimePrev.set(time);
    }

    /** Fill the JMX-derived fields of {@code snap}. */
    public void fill(io.fathereye.bridge.topic.TpsSnapshot snap) {
        MemoryUsage heap = memMx.getHeapMemoryUsage();
        MemoryUsage nh = memMx.getNonHeapMemoryUsage();
        snap.heapUsedBytes = heap.getUsed();
        snap.heapMaxBytes = heap.getMax();
        snap.nonHeapUsedBytes = nh.getUsed();

        long count = 0, time = 0;
        for (GarbageCollectorMXBean g : gcBeans) {
            count += Math.max(0, g.getCollectionCount());
            time  += Math.max(0, g.getCollectionTime());
        }
        long prevCount = gcCollectionCountPrev.getAndSet(count);
        long prevTime  = gcCollectionTimePrev.getAndSet(time);
        snap.gcPauseMsLastSec = Math.max(0, time - prevTime);
        snap.gcCountLastSec = (int) Math.max(0, count - prevCount);

        snap.processCpuLoad = readProcessCpuLoad();
        snap.liveThreadCount = threadMx.getThreadCount();

        Map<String, Long> tcpu = new LinkedHashMap<>();
        long[] ids = threadMx.getAllThreadIds();
        for (long id : ids) {
            java.lang.management.ThreadInfo info = threadMx.getThreadInfo(id);
            if (info == null) continue;
            String name = info.getThreadName();
            if (name == null) continue;
            // Only include threads we care about: server tick + bridge.
            if (!name.startsWith("Server ") && !name.startsWith("FatherEye")
                    && !name.equals("Server thread") && !name.startsWith("ChunkProcessor")) continue;
            long cpu = threadMx.getThreadCpuTime(id);
            if (cpu < 0) continue;
            tcpu.put(name, cpu);
        }
        snap.threadCpuNanosByName = tcpu;
    }

    private double readProcessCpuLoad() {
        // OperatingSystemMXBean has getProcessCpuLoad() only on the Sun/Oracle subclass.
        try {
            java.lang.reflect.Method m = osMx.getClass().getMethod("getProcessCpuLoad");
            m.setAccessible(true);
            Object v = m.invoke(osMx);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}
        return Double.NaN;
    }
}
