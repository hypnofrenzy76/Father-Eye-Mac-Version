package io.fathereye.bridge.profiler;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand Java Flight Recorder profiling. RPC ops:
 *   jfr_start { profile, durationSec? } -> { ok, recordingId }
 *   jfr_stop  { recordingId } -> { ok, fileRef }
 *
 * <p><b>Java 8 compatibility:</b> the bridge's compile target is Java 8 and
 * its target JVM may also be Java 8. {@code jdk.jfr.Recording} only exists
 * on JDK 11+. To keep the class loadable on JDK 8, every JFR symbol is
 * accessed through reflection — no direct {@code import jdk.jfr.*} that
 * would force class-init failure. {@link #isSupported()} surfaces the
 * feature flag; if false, the start handler returns a clean error instead
 * of crashing.
 */
public final class JfrController {

    private static final Logger LOG = LogManager.getLogger("FatherEye-JFR");
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private final Path dumpDir;
    private final ConcurrentHashMap<String, Object> active = new ConcurrentHashMap<>();
    private final Class<?> recordingClass;
    private final Constructor<?> ctor;
    private final Method enable;
    private final Method setDuration;
    private final Method start;
    private final Method dump;
    private final Method close;

    public JfrController(Path serverDir) {
        this.dumpDir = serverDir.resolve("jfr");
        Class<?> rc = null;
        Constructor<?> c = null;
        Method e = null, sd = null, st = null, d = null, cl = null;
        try {
            rc = Class.forName("jdk.jfr.Recording");
            c = rc.getConstructor();
            e = rc.getMethod("enable", String.class);
            sd = rc.getMethod("setDuration", java.time.Duration.class);
            st = rc.getMethod("start");
            d = rc.getMethod("dump", Path.class);
            cl = rc.getMethod("close");
        } catch (Throwable t) {
            LOG.info("JFR unavailable (likely JDK 8): {}", t.getMessage());
        }
        this.recordingClass = rc;
        this.ctor = c;
        this.enable = e;
        this.setDuration = sd;
        this.start = st;
        this.dump = d;
        this.close = cl;
    }

    public boolean isSupported() { return recordingClass != null; }

    public Object start(JsonNode args) {
        if (!isSupported()) throw new IllegalStateException("JFR not supported on this JVM");
        String profile = args == null ? "default" : args.path("profile").asText("default");
        int durationSec = args == null ? 0 : args.path("durationSec").asInt(0);

        try {
            Files.createDirectories(dumpDir);
            Object rec = ctor.newInstance();
            enable.invoke(rec, "jdk.ObjectAllocationSample");
            enable.invoke(rec, "jdk.ExecutionSample");
            enable.invoke(rec, "jdk.GCPhasePause");
            if (durationSec > 0) setDuration.invoke(rec, java.time.Duration.ofSeconds(durationSec));
            start.invoke(rec);

            String id = UUID.randomUUID().toString().substring(0, 8);
            active.put(id, rec);
            LOG.info("JFR recording started: id={} profile={} duration={}s", id, profile, durationSec);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("recordingId", id);
            return r;
        } catch (Throwable t) {
            throw new RuntimeException("jfr_start failed: " + t.getMessage(), t);
        }
    }

    public Object stop(JsonNode args) {
        if (!isSupported()) throw new IllegalStateException("JFR not supported on this JVM");
        String id = args == null ? null : args.path("recordingId").asText(null);
        if (id == null) throw new IllegalArgumentException("recordingId required");
        Object rec = active.remove(id);
        if (rec == null) throw new IllegalArgumentException("unknown recordingId: " + id);
        try {
            String stamp = TS.format(new Date());
            Path file = dumpDir.resolve(stamp + "-" + id + ".jfr");
            dump.invoke(rec, file);
            close.invoke(rec);
            LOG.info("JFR recording dumped: {}", file);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("fileRef", file.toString());
            return r;
        } catch (Throwable t) {
            throw new RuntimeException("jfr_stop failed: " + t.getMessage(), t);
        }
    }

    /** Stop and discard every active recording (called on bridge shutdown). */
    public void closeAll() {
        for (Map.Entry<String, Object> e : active.entrySet()) {
            try { close.invoke(e.getValue()); } catch (Throwable ignored) {}
        }
        active.clear();
    }
}
