package io.fathereye.bridge.topic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wire payload for {@link Topics#TPS}. */
public final class TpsSnapshot {

    public long timestampMs;
    public double tps20s;
    public double tps1m;
    public double tps5m;
    public double msptAvg;
    public double msptP50;
    public double msptP95;
    public double msptP99;
    public long heapUsedBytes;
    public long heapMaxBytes;
    public long nonHeapUsedBytes;
    public long gcPauseMsLastSec;
    public int gcCountLastSec;
    public double processCpuLoad;
    public int liveThreadCount;
    public Map<String, Long> threadCpuNanosByName = new LinkedHashMap<>();

    public TpsSnapshot() {}
}
