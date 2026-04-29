package io.fathereye.panel.history;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Pnl-36 (2026-04-26): generates a Markdown post-mortem report for each
 * server session. The MetricsDb already stores every {@code tps_topic}
 * snapshot, every {@code mods_impact_topic} snapshot, and every event
 * (alerts, joins, etc.) keyed by ts_ms; this class queries the
 * session-range slice and aggregates it into an "Evaluations/" report.
 *
 * <p>Pnl-47 (2026-04-26): user requested "much more in depth and show
 * all info, i need specific heap info, tps. mspt load, everything".
 * The report now covers, for every numeric metric: avg / min / max /
 * p10 / p25 / p50 / p75 / p90 / p95 / p99 plus a small ASCII sparkline
 * of the time series; dedicated Heap, GC, CPU and Threads sections
 * with pressure-window percentages; auto-detected lag-spike windows
 * (consecutive ticks below configurable thresholds); per-dimension
 * breakdown of mod impact; per-player session times reconstructed
 * from the players_topic snapshots; full alert list (no cap); and
 * crash analysis (last console lines + error patterns) when the
 * session ended with a non-zero exit.
 *
 * <p>Triggered by {@code App} when {@code ServerLauncher} transitions
 * from RUNNING to STOPPED or CRASHED.
 */
public final class EvaluationGenerator {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Eval");
    private static final SimpleDateFormat FILE_TS = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat HUMAN_TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
    private static final SimpleDateFormat CLOCK_TS = new SimpleDateFormat("HH:mm:ss");
    /** Pnl-50 (audit fix): unambiguous ISO 8601 with milliseconds and
     *  numeric offset for the CSV / console archive companions.
     *  Excel and pandas parse this without locale gymnastics, and a
     *  multi-day session keeps full date context that
     *  {@link #CLOCK_TS} drops. */
    private static final SimpleDateFormat ISO_TS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    static {
        FILE_TS.setTimeZone(TimeZone.getDefault());
        HUMAN_TS.setTimeZone(TimeZone.getDefault());
        CLOCK_TS.setTimeZone(TimeZone.getDefault());
        ISO_TS.setTimeZone(TimeZone.getDefault());
    }

    /** Pnl-47: ASCII sparkline blocks (8 levels). */
    private static final char[] SPARK_CHARS = { '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█' };
    /** Pnl-47: target sparkline width in characters. */
    private static final int SPARK_WIDTH = 60;

    private final MetricsDb db;

    public EvaluationGenerator(MetricsDb db) { this.db = db; }

    /**
     * Generate an evaluation file for the session window {@code
     * [sessionStartMs, sessionEndMs]} and write it to
     * {@code <serverDir>/Evaluations/evaluation-<timestamp>.md}.
     * Returns the written file path, or null on failure.
     */
    public Path generate(Path serverDir, long sessionStartMs, long sessionEndMs,
                         int exitCode, String exitDescriptor) {
        if (db == null || serverDir == null) return null;
        long durMs = Math.max(0, sessionEndMs - sessionStartMs);

        // Pull session data.
        List<JsonNode> tps = db.querySnapshots("tps_topic", sessionStartMs, sessionEndMs);
        List<JsonNode> modsImpact = db.querySnapshots("mods_impact_topic", sessionStartMs, sessionEndMs);
        List<JsonNode> players = db.querySnapshots("players_topic", sessionStartMs, sessionEndMs);
        List<JsonNode> mobs = db.querySnapshots("mobs_topic", sessionStartMs, sessionEndMs);
        List<JsonNode> alerts = db.queryEvents("event_alert", sessionStartMs, sessionEndMs);
        List<JsonNode> consoleLogs = db.queryEvents("console_log", sessionStartMs, sessionEndMs);

        StringBuilder md = new StringBuilder(32768);
        md.append("# Server Session Evaluation\n\n");
        md.append("- **Server folder**: `").append(serverDir).append("`\n");
        md.append("- **Started**: ").append(HUMAN_TS.format(new Date(sessionStartMs))).append("\n");
        md.append("- **Stopped**: ").append(HUMAN_TS.format(new Date(sessionEndMs))).append("\n");
        md.append("- **Duration**: ").append(humanDuration(durMs)).append("\n");
        md.append("- **Exit**: ").append(exitCode).append(" (").append(exitDescriptor).append(")\n");
        md.append("- **Snapshots collected**: ")
                .append(tps.size()).append(" tps, ")
                .append(modsImpact.size()).append(" mods, ")
                .append(players.size()).append(" players, ")
                .append(mobs.size()).append(" mobs\n");
        md.append("- **Events recorded**: ")
                .append(alerts.size()).append(" alerts, ")
                .append(consoleLogs.size()).append(" console lines\n\n");

        appendPerformance(md, tps);
        appendHealthScore(md, tps);
        appendHeapMemory(md, tps);
        appendGarbageCollection(md, tps);
        appendCpuAndThreads(md, tps);
        appendLagSpikes(md, tps);
        appendModImpact(md, modsImpact);
        appendModImpactByDim(md, modsImpact);
        appendPlayerSessions(md, players, sessionStartMs, sessionEndMs);
        appendMobsTrend(md, mobs);
        appendAlertsTimeline(md, alerts);
        appendConsoleHighlights(md, consoleLogs);
        if (exitCode != 0) {
            appendCrashAnalysis(md, consoleLogs);
        }
        appendRecommendations(md, tps, modsImpact);

        // Pnl-50 (2026-04-26): write the markdown report PLUS
        // companion artifacts (raw console log, metrics CSV) so the
        // Evaluations folder is a self-contained snapshot of the
        // session. The user requested "i need the outputs in
        // evaluations folder".
        try {
            Path evalDir = serverDir.resolve("Evaluations");
            Files.createDirectories(evalDir);
            String stamp = FILE_TS.format(new Date(sessionEndMs));
            Path mdOut = evalDir.resolve("evaluation-" + stamp + ".md");
            Files.write(mdOut, md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LOG.info("Evaluation written: {}", mdOut);

            // Companion 1: full console log of the session, raw, in
            // chronological order. One line per console_log event.
            try {
                Path consoleOut = evalDir.resolve("console-" + stamp + ".log");
                writeConsoleArchive(consoleOut, consoleLogs);
                LOG.info("Console archive written: {}", consoleOut);
            } catch (IOException ioe) {
                LOG.warn("console archive write failed: {}", ioe.getMessage());
            }
            // Companion 2: metrics CSV of every tps_topic snapshot.
            try {
                Path csvOut = evalDir.resolve("metrics-" + stamp + ".csv");
                writeMetricsCsv(csvOut, tps);
                LOG.info("Metrics CSV written: {}", csvOut);
            } catch (IOException ioe) {
                LOG.warn("metrics csv write failed: {}", ioe.getMessage());
            }
            // Companion 3: alerts CSV (one row per alert).
            try {
                Path alertsOut = evalDir.resolve("alerts-" + stamp + ".csv");
                writeAlertsCsv(alertsOut, alerts);
                LOG.info("Alerts CSV written: {}", alertsOut);
            } catch (IOException ioe) {
                LOG.warn("alerts csv write failed: {}", ioe.getMessage());
            }
            return mdOut;
        } catch (IOException ioe) {
            LOG.warn("evaluation write failed: {}", ioe.getMessage());
            return null;
        }
    }

    /**
     * Pnl-50: dump every captured console line into a plain-text
     * file (chronological, one line per event). Format mirrors the
     * panel's in-app console: {@code HH:mm:ss [LEVEL ] [thread] (logger) msg}.
     */
    private static void writeConsoleArchive(Path out, List<JsonNode> consoleLogs) throws IOException {
        StringBuilder sb = new StringBuilder(consoleLogs.size() * 128);
        sb.append("# Father Eye console archive\n");
        sb.append("# Total lines: ").append(consoleLogs.size()).append("\n\n");
        for (JsonNode log : consoleLogs) {
            long tsMs = log.path("tsMs").asLong(0);
            String level = log.path("level").asText("INFO");
            String logger = log.path("logger").asText("?");
            String thread = log.path("thread").asText("");
            String msg = log.path("msg").asText("");
            // Pnl-50 (audit fix): full ISO 8601 timestamp, not just
            // HH:mm:ss; multi-day sessions otherwise lose date
            // context.
            sb.append(ISO_TS.format(new Date(tsMs)))
                    .append(" [").append(String.format(Locale.US, "%-5s", level)).append("] ")
                    .append("[").append(thread).append("] ")
                    .append("(").append(logger).append(") ")
                    .append(msg).append("\n");
        }
        Files.write(out, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Pnl-50: emit a CSV of per-second metrics so the operator can
     * load the session into Excel / pandas / a charting tool. One row
     * per tps_topic snapshot.
     */
    private static void writeMetricsCsv(Path out, List<JsonNode> tps) throws IOException {
        StringBuilder sb = new StringBuilder(tps.size() * 96);
        sb.append("ts_iso,ts_ms,tps20s,tps1m,tps5m,msptAvg,msptP95,msptP99,heapUsedBytes,heapMaxBytes,heapPct,gcPauseMsLastSec,processCpuPct,liveThreadCount\n");
        for (JsonNode rec : tps) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            long ts = rec.path("tsMs").asLong(0);
            long heapU = data.path("heapUsedBytes").asLong(0);
            long heapM = data.path("heapMaxBytes").asLong(0);
            double heapPct = heapM > 0 ? 100.0 * heapU / heapM : 0;
            // Pnl-50 (audit fix): ISO 8601 instead of "yyyy-MM-dd
            // HH:mm:ss z". Excel and pandas parse the former
            // without locale gymnastics; the trailing timezone
            // abbreviation in HUMAN_TS broke automated tooling.
            sb.append(ISO_TS.format(new Date(ts))).append(",")
                    .append(ts).append(",")
                    .append(data.path("tps20s").asDouble(0)).append(",")
                    .append(data.path("tps1m").asDouble(0)).append(",")
                    .append(data.path("tps5m").asDouble(0)).append(",")
                    .append(data.path("msptAvg").asDouble(0)).append(",")
                    .append(data.path("msptP95").asDouble(0)).append(",")
                    .append(data.path("msptP99").asDouble(0)).append(",")
                    .append(heapU).append(",")
                    .append(heapM).append(",")
                    .append(String.format(Locale.US, "%.2f", heapPct)).append(",")
                    .append(data.path("gcPauseMsLastSec").asLong(0)).append(",")
                    .append(data.path("processCpuPct").asDouble(0)).append(",")
                    .append(data.path("liveThreadCount").asInt(0))
                    .append("\n");
        }
        Files.write(out, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Pnl-50: emit a CSV of every alert fired during the session.
     * Empty file (header only) if no alerts.
     */
    private static void writeAlertsCsv(Path out, List<JsonNode> alerts) throws IOException {
        StringBuilder sb = new StringBuilder(alerts.size() * 96 + 64);
        sb.append("ts_iso,ts_ms,severity,kind,msg\n");
        for (JsonNode a : alerts) {
            long ts = a.path("tsMs").asLong(0);
            String severity = a.path("severity").asText("");
            String kind = a.path("kind").asText("");
            String msg = a.path("msg").asText("");
            sb.append(ISO_TS.format(new Date(ts))).append(",")
                    .append(ts).append(",")
                    .append(csvField(severity)).append(",")
                    .append(csvField(kind)).append(",")
                    .append(csvField(msg))
                    .append("\n");
        }
        Files.write(out, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Pnl-50: minimal CSV escape -- quote if the field contains a
     * comma, quote, newline, OR carriage return.
     *
     * <p>Pnl-50 (audit fix): an unquoted CR (\r) inside a value
     * corrupts row boundaries on Windows-CRLF parsers (Excel reads
     * the bare CR as an inner record terminator). Adding \r to the
     * quoting bypass closes that.
     */
    private static String csvField(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0
                && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ---- sections -----------------------------------------------------

    private static void appendPerformance(StringBuilder md, List<JsonNode> tps) {
        md.append("## Performance Overview\n\n");
        if (tps.isEmpty()) {
            md.append("_No TPS samples recorded._\n\n");
            return;
        }
        ExtStats sTps20s    = collectExt(tps, "tps20s");
        ExtStats sTps1m     = collectExt(tps, "tps1m");
        ExtStats sTps5m     = collectExt(tps, "tps5m");
        ExtStats sMsptAvg   = collectExt(tps, "msptAvg");
        ExtStats sMsptP95   = collectExt(tps, "msptP95");
        ExtStats sMsptP99   = collectExt(tps, "msptP99");

        md.append("Statistical distribution across the entire session.\n\n");
        md.append("| Metric | Avg | Min | p10 | p50 | p90 | p95 | p99 | Max |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        appendDistRow(md, "TPS (20 s)",    sTps20s,    "%.2f", "");
        appendDistRow(md, "TPS (1 m)",     sTps1m,     "%.2f", "");
        appendDistRow(md, "TPS (5 m)",     sTps5m,     "%.2f", "");
        appendDistRow(md, "MSPT avg",      sMsptAvg,   "%.1f", " ms");
        appendDistRow(md, "MSPT p95 (live)", sMsptP95, "%.1f", " ms");
        appendDistRow(md, "MSPT p99 (live)", sMsptP99, "%.1f", " ms");
        md.append("\n");

        // Time-at-target stats.
        int totalSamples = sTps20s.count();
        int laggedSamples = countAtMost(tps, "tps20s", 18.0);
        int badSamples = countAtMost(tps, "tps20s", 10.0);
        int below15 = countAtMost(tps, "tps20s", 15.0);
        if (totalSamples > 0) {
            md.append("**Time-at-target (TPS 20 s)**\n\n");
            md.append("- At full TPS (>= 18): **").append(fmt1(100.0 * (totalSamples - laggedSamples) / totalSamples)).append(" %** of session\n");
            md.append("- Below TPS 15: ").append(fmt1(100.0 * below15 / totalSamples)).append(" % of session\n");
            md.append("- Severe lag (TPS < 10): ").append(fmt1(100.0 * badSamples / totalSamples)).append(" % of session\n\n");
        }

        // TPS sparkline.
        md.append("**TPS sparkline** (full session, ").append(SPARK_WIDTH).append(" buckets):\n\n");
        md.append("```\n");
        md.append(sparkline(sTps20s.values, 0.0, 20.0, SPARK_WIDTH)).append("\n");
        md.append("(scale: 0 - 20 TPS)\n");
        md.append("```\n\n");

        // MSPT sparkline.
        md.append("**MSPT (avg) sparkline**:\n\n");
        md.append("```\n");
        md.append(sparkline(sMsptAvg.values, 0.0, Math.max(100.0, sMsptAvg.max), SPARK_WIDTH)).append("\n");
        md.append("(scale: 0 - ").append(fmt0(Math.max(100.0, sMsptAvg.max))).append(" ms)\n");
        md.append("```\n\n");
    }

    private static void appendDistRow(StringBuilder md, String name, ExtStats s, String fmt, String unit) {
        if (s.count() == 0) {
            md.append("| ").append(name).append(" | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |\n");
            return;
        }
        md.append("| ").append(name)
                .append(" | ").append(String.format(Locale.US, fmt, s.avg)).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.min)).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.percentile(10))).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.percentile(50))).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.percentile(90))).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.percentile(95))).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.percentile(99))).append(unit)
                .append(" | ").append(String.format(Locale.US, fmt, s.max)).append(unit)
                .append(" |\n");
    }

    private static void appendHealthScore(StringBuilder md, List<JsonNode> tps) {
        if (tps.isEmpty()) return;
        // Mirror StatsPane.updateHealth weights so the offline report
        // matches the live "Server Health" widget.
        double sum = 0; double min = 20; double max = 0; int n = 0;
        long worstAt = 0;
        for (JsonNode rec : tps) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            double tps20s = data.path("tps20s").asDouble(20);
            double mspt   = data.path("msptAvg").asDouble(0);
            long u = data.path("heapUsedBytes").asLong(0);
            long m = data.path("heapMaxBytes").asLong(0);
            double heapPct = m > 0 ? 100.0 * u / m : 0;
            long gc = data.path("gcPauseMsLastSec").asLong(0);
            double cpu = data.path("processCpuPct").asDouble(0);
            double dTps  = clamp((20.0 - tps20s) * 0.5, 0.0, 5.0);
            double dMspt = clamp((mspt - 30.0) * 0.1, 0.0, 5.0);
            double dHeap = clamp((heapPct - 75.0) * 0.16, 0.0, 4.0);
            double dGc   = clamp((gc - 50.0) * 0.06, 0.0, 3.0);
            double dCpu  = clamp((cpu - 70.0) * 0.10, 0.0, 3.0);
            double score = clamp(20.0 - dTps - dMspt - dHeap - dGc - dCpu, 0.0, 20.0);
            sum += score;
            if (score < min) {
                min = score;
                worstAt = rec.path("tsMs").asLong(0);
            }
            if (score > max) max = score;
            n++;
        }
        if (n == 0) return;
        double avg = sum / n;
        md.append("## Health Score\n\n");
        md.append("- **Average**: ").append(fmt1(avg)).append(" / 20 (").append(descriptor(avg)).append(")\n");
        md.append("- **Best**:    ").append(fmt1(max)).append(" / 20 (").append(descriptor(max)).append(")\n");
        md.append("- **Worst**:   ").append(fmt1(min)).append(" / 20 (").append(descriptor(min)).append(")");
        if (worstAt > 0) md.append(" at ").append(HUMAN_TS.format(new Date(worstAt)));
        md.append("\n\n");
    }

    private static void appendHeapMemory(StringBuilder md, List<JsonNode> tps) {
        md.append("## Heap & Memory\n\n");
        if (tps.isEmpty()) {
            md.append("_No heap samples recorded._\n\n");
            return;
        }
        ExtStats sHeapUsed = new ExtStats();
        ExtStats sHeapPct = new ExtStats();
        long heapMaxBytes = 0;
        long heapPeakUsed = 0;
        long heapPeakAt = 0;
        for (JsonNode n : tps) {
            JsonNode data = n.get("data");
            if (data == null) continue;
            long u = data.path("heapUsedBytes").asLong(0);
            long m = data.path("heapMaxBytes").asLong(0);
            long ts = n.path("tsMs").asLong(0);
            if (u > 0) sHeapUsed.add(u);
            if (m > 0) {
                sHeapPct.add(100.0 * u / m);
                if (m > heapMaxBytes) heapMaxBytes = m;
            }
            if (u > heapPeakUsed) {
                heapPeakUsed = u;
                heapPeakAt = ts;
            }
        }

        md.append("**Configured heap max** (-Xmx): ").append(humanBytes(heapMaxBytes)).append("\n\n");

        md.append("| Metric | Avg | Min | p10 | p50 | p90 | p95 | p99 | Max |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        appendDistRow(md, "Heap used (%)",    sHeapPct,  "%.1f", " %");
        appendDistRowBytes(md, "Heap used (B)", sHeapUsed);
        md.append("\n");

        // Pressure windows.
        if (sHeapPct.count() > 0) {
            int total = sHeapPct.count();
            int over75 = countAbove(sHeapPct.values, 75.0);
            int over85 = countAbove(sHeapPct.values, 85.0);
            int over90 = countAbove(sHeapPct.values, 90.0);
            int over95 = countAbove(sHeapPct.values, 95.0);
            md.append("**Pressure windows** (% of session at or above threshold):\n\n");
            md.append("- Heap >= 75 %: ").append(fmt1(100.0 * over75 / total)).append(" %\n");
            md.append("- Heap >= 85 %: ").append(fmt1(100.0 * over85 / total)).append(" %\n");
            md.append("- Heap >= 90 %: ").append(fmt1(100.0 * over90 / total)).append(" %\n");
            md.append("- Heap >= 95 %: ").append(fmt1(100.0 * over95 / total)).append(" %\n\n");
        }

        if (heapPeakUsed > 0 && heapPeakAt > 0) {
            md.append("**Peak heap used**: ").append(humanBytes(heapPeakUsed));
            if (heapMaxBytes > 0) {
                md.append(" (").append(fmt1(100.0 * heapPeakUsed / heapMaxBytes)).append(" %)");
            }
            md.append(" at ").append(HUMAN_TS.format(new Date(heapPeakAt))).append("\n\n");
        }

        // Sparkline.
        md.append("**Heap usage sparkline** (% of -Xmx):\n\n");
        md.append("```\n");
        md.append(sparkline(sHeapPct.values, 0.0, 100.0, SPARK_WIDTH)).append("\n");
        md.append("(scale: 0 - 100 % heap)\n");
        md.append("```\n\n");
    }

    private static void appendDistRowBytes(StringBuilder md, String name, ExtStats s) {
        if (s.count() == 0) {
            md.append("| ").append(name).append(" | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |\n");
            return;
        }
        md.append("| ").append(name)
                .append(" | ").append(humanBytes((long) s.avg))
                .append(" | ").append(humanBytes((long) s.min))
                .append(" | ").append(humanBytes((long) s.percentile(10)))
                .append(" | ").append(humanBytes((long) s.percentile(50)))
                .append(" | ").append(humanBytes((long) s.percentile(90)))
                .append(" | ").append(humanBytes((long) s.percentile(95)))
                .append(" | ").append(humanBytes((long) s.percentile(99)))
                .append(" | ").append(humanBytes((long) s.max))
                .append(" |\n");
    }

    private static void appendGarbageCollection(StringBuilder md, List<JsonNode> tps) {
        md.append("## Garbage Collection\n\n");
        if (tps.isEmpty()) {
            md.append("_No GC samples recorded._\n\n");
            return;
        }
        ExtStats sGc = collectExt(tps, "gcPauseMsLastSec");
        long totalGcMs = 0;
        int gcEvents = 0;
        long maxGcAt = 0;
        double maxGc = 0;
        for (JsonNode n : tps) {
            JsonNode data = n.get("data");
            if (data == null) continue;
            long ms = data.path("gcPauseMsLastSec").asLong(0);
            if (ms > 0) {
                totalGcMs += ms;
                gcEvents++;
                if (ms > maxGc) {
                    maxGc = ms;
                    maxGcAt = n.path("tsMs").asLong(0);
                }
            }
        }
        long sessionMs = (long) tps.size() * 1000L;
        double overheadPct = sessionMs > 0 ? 100.0 * totalGcMs / sessionMs : 0;

        md.append("- **Total GC time**: ").append(humanDuration(totalGcMs)).append("\n");
        md.append("- **Seconds with GC activity**: ").append(gcEvents)
                .append(" of ").append(tps.size())
                .append(" (").append(fmt1(100.0 * gcEvents / Math.max(1, tps.size()))).append(" %)\n");
        md.append("- **GC overhead**: ").append(fmt2(overheadPct)).append(" % of session wall time\n");
        md.append("- **Max single-second GC pause**: ").append(fmt0(maxGc)).append(" ms");
        if (maxGcAt > 0) md.append(" at ").append(HUMAN_TS.format(new Date(maxGcAt)));
        md.append("\n\n");

        md.append("| Metric | Avg | Min | p50 | p90 | p95 | p99 | Max |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        if (sGc.count() == 0) {
            md.append("| GC ms / sec | n/a | n/a | n/a | n/a | n/a | n/a | n/a |\n");
        } else {
            md.append("| GC ms / sec ")
                    .append(" | ").append(fmt0(sGc.avg))
                    .append(" | ").append(fmt0(sGc.min))
                    .append(" | ").append(fmt0(sGc.percentile(50)))
                    .append(" | ").append(fmt0(sGc.percentile(90)))
                    .append(" | ").append(fmt0(sGc.percentile(95)))
                    .append(" | ").append(fmt0(sGc.percentile(99)))
                    .append(" | ").append(fmt0(sGc.max))
                    .append(" |\n");
        }
        md.append("\n");

        if (overheadPct > 5.0) {
            md.append("> **NOTE**: GC overhead above 5 % is a strong sign of heap pressure or allocation churn. ")
              .append("Consider raising -Xmx, profiling allocations with JFR, or auditing high-impact mods.\n\n");
        }
    }

    private static void appendCpuAndThreads(StringBuilder md, List<JsonNode> tps) {
        md.append("## CPU & Threads\n\n");
        if (tps.isEmpty()) {
            md.append("_No CPU/thread samples recorded._\n\n");
            return;
        }
        ExtStats sCpu = collectExt(tps, "processCpuPct");
        ExtStats sThr = collectExt(tps, "liveThreadCount");

        md.append("| Metric | Avg | Min | p10 | p50 | p90 | p95 | p99 | Max |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        appendDistRow(md, "Process CPU", sCpu, "%.0f", " %");
        appendDistRow(md, "Live threads", sThr, "%.0f", "");
        md.append("\n");

        // Time at high CPU.
        if (sCpu.count() > 0) {
            int over70 = countAbove(sCpu.values, 70.0);
            int over85 = countAbove(sCpu.values, 85.0);
            int over95 = countAbove(sCpu.values, 95.0);
            int total = sCpu.count();
            md.append("**CPU pressure windows**:\n\n");
            md.append("- CPU > 70 %: ").append(fmt1(100.0 * over70 / total)).append(" % of session\n");
            md.append("- CPU > 85 %: ").append(fmt1(100.0 * over85 / total)).append(" % of session\n");
            md.append("- CPU > 95 %: ").append(fmt1(100.0 * over95 / total)).append(" % of session\n\n");
        }

        // Thread growth (simple: last - first).
        if (sThr.values.size() >= 2) {
            int first = sThr.values.get(0).intValue();
            int last = sThr.values.get(sThr.values.size() - 1).intValue();
            md.append("**Thread count drift**: started ").append(first)
                    .append(", ended ").append(last)
                    .append(" (delta ").append(last - first >= 0 ? "+" : "").append(last - first)
                    .append("). ");
            if (last > first + 20) {
                md.append("Possible thread leak; investigate mod thread management.");
            } else {
                md.append("No anomaly.");
            }
            md.append("\n\n");
        }

        // CPU sparkline.
        md.append("**CPU sparkline**:\n\n");
        md.append("```\n");
        md.append(sparkline(sCpu.values, 0.0, 100.0, SPARK_WIDTH)).append("\n");
        md.append("(scale: 0 - 100 % CPU)\n");
        md.append("```\n\n");
    }

    private static void appendLagSpikes(StringBuilder md, List<JsonNode> tps) {
        md.append("## Lag-Spike Analysis\n\n");
        if (tps.isEmpty()) {
            md.append("_No samples to scan._\n\n");
            return;
        }
        // Detect contiguous runs where tps20s < 18 (configurable in
        // the future). For each run, record start/end ts, min TPS,
        // max MSPT.
        final double TPS_THRESHOLD = 18.0;
        List<Spike> spikes = new ArrayList<>();
        Spike current = null;
        for (JsonNode rec : tps) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            long ts = rec.path("tsMs").asLong(0);
            double tps20s = data.path("tps20s").asDouble(20);
            double mspt = data.path("msptAvg").asDouble(0);
            if (tps20s < TPS_THRESHOLD) {
                if (current == null) {
                    current = new Spike();
                    current.startMs = ts;
                    current.minTps = tps20s;
                    current.maxMspt = mspt;
                }
                current.endMs = ts;
                current.minTps = Math.min(current.minTps, tps20s);
                current.maxMspt = Math.max(current.maxMspt, mspt);
                current.sampleCount++;
            } else {
                if (current != null && current.sampleCount >= 2) {
                    spikes.add(current);
                }
                current = null;
            }
        }
        if (current != null && current.sampleCount >= 2) spikes.add(current);

        if (spikes.isEmpty()) {
            md.append("No sustained TPS dips below ").append(TPS_THRESHOLD).append(" detected.\n\n");
            return;
        }

        md.append("Detected ").append(spikes.size()).append(" sustained dips below TPS ")
                .append(TPS_THRESHOLD).append(" (samples >= 2 consecutive).\n\n");
        md.append("| # | Start | End | Duration | Min TPS | Max MSPT |\n");
        md.append("|---|---|---|---|---|---|\n");
        // Sort by severity (lowest min TPS first).
        spikes.sort(Comparator.comparingDouble((Spike s) -> s.minTps));
        int rank = 1;
        for (Spike s : spikes) {
            if (rank > 25) {
                md.append("| ... | ... | ... | ... | ... | (").append(spikes.size() - 25).append(" more) |\n");
                break;
            }
            long dur = s.endMs - s.startMs;
            md.append("| ").append(rank++)
                    .append(" | ").append(CLOCK_TS.format(new Date(s.startMs)))
                    .append(" | ").append(CLOCK_TS.format(new Date(s.endMs)))
                    .append(" | ").append(humanDuration(dur))
                    .append(" | ").append(fmt2(s.minTps))
                    .append(" | ").append(fmt0(s.maxMspt)).append(" ms")
                    .append(" |\n");
        }
        md.append("\n");
    }

    private static String descriptor(double s) {
        if (s >= 19.0) return "Excellent";
        if (s >= 16.0) return "Good";
        if (s >= 12.0) return "Fair";
        if (s >= 8.0)  return "Poor";
        return "Critical";
    }

    private static void appendModImpact(StringBuilder md, List<JsonNode> snapshots) {
        md.append("## Mod Impact (session totals)\n\n");
        if (snapshots.isEmpty()) {
            md.append("_No mod-impact samples recorded._\n\n");
            return;
        }
        // Sum nanos per mod across the whole session.
        Map<String, ModAgg> totals = new HashMap<>();
        for (JsonNode rec : snapshots) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            JsonNode byDim = data.get("byDim");
            if (byDim == null) continue;
            Iterator<Map.Entry<String, JsonNode>> dims = byDim.fields();
            while (dims.hasNext()) {
                Map.Entry<String, JsonNode> dimEntry = dims.next();
                Iterator<Map.Entry<String, JsonNode>> mit = dimEntry.getValue().fields();
                while (mit.hasNext()) {
                    Map.Entry<String, JsonNode> me = mit.next();
                    String mod = me.getKey();
                    JsonNode slot = me.getValue();
                    long ns = slot.path("attributedNanos").asLong(0);
                    int ec = slot.path("entityCount").asInt(0);
                    int tc = slot.path("tileEntityCount").asInt(0);
                    ModAgg agg = totals.computeIfAbsent(mod, k -> new ModAgg());
                    agg.totalNanos += ns;
                    agg.entityPeak = Math.max(agg.entityPeak, ec);
                    agg.tilePeak   = Math.max(agg.tilePeak, tc);
                    agg.sampleCount++;
                }
            }
        }
        if (totals.isEmpty()) {
            md.append("_All mod slots empty._\n\n");
            return;
        }
        long totalAllMods = 0;
        for (ModAgg a : totals.values()) totalAllMods += a.totalNanos;
        long sessionNanos = (long) snapshots.size() * 1_000_000_000L;

        List<Map.Entry<String, ModAgg>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, ModAgg>>comparingLong(e -> e.getValue().totalNanos).reversed());

        md.append("Sorted by total tick time consumed across the session.\n\n");
        md.append("| Rank | Mod | Total tick time | % of session | % of mod work | Peak entities | Peak TEs | Impact |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        int rank = 1;
        for (Map.Entry<String, ModAgg> e : sorted) {
            ModAgg a = e.getValue();
            double pct = sessionNanos > 0 ? 100.0 * a.totalNanos / sessionNanos : 0;
            double sumPct = totalAllMods > 0 ? 100.0 * a.totalNanos / totalAllMods : 0;
            md.append("| ").append(rank++).append(" | `").append(e.getKey()).append("`")
                    .append(" | ").append(humanDuration(a.totalNanos / 1_000_000L))
                    .append(" | ").append(fmt2(pct)).append(" %")
                    .append(" | ").append(fmt2(sumPct)).append(" %")
                    .append(" | ").append(a.entityPeak)
                    .append(" | ").append(a.tilePeak)
                    .append(" | ").append(impactDescriptor(pct)).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendModImpactByDim(StringBuilder md, List<JsonNode> snapshots) {
        md.append("## Mod Impact by Dimension\n\n");
        if (snapshots.isEmpty()) {
            md.append("_No mod-impact samples recorded._\n\n");
            return;
        }
        // Map<dim, Map<mod, totalNanos>>
        Map<String, Map<String, Long>> byDim = new TreeMap<>();
        for (JsonNode rec : snapshots) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            JsonNode bd = data.get("byDim");
            if (bd == null) continue;
            Iterator<Map.Entry<String, JsonNode>> dims = bd.fields();
            while (dims.hasNext()) {
                Map.Entry<String, JsonNode> dimEntry = dims.next();
                String dim = dimEntry.getKey();
                Map<String, Long> dimMap = byDim.computeIfAbsent(dim, k -> new HashMap<>());
                Iterator<Map.Entry<String, JsonNode>> mit = dimEntry.getValue().fields();
                while (mit.hasNext()) {
                    Map.Entry<String, JsonNode> me = mit.next();
                    long ns = me.getValue().path("attributedNanos").asLong(0);
                    dimMap.merge(me.getKey(), ns, Long::sum);
                }
            }
        }
        if (byDim.isEmpty()) {
            md.append("_No per-dim data._\n\n");
            return;
        }
        for (Map.Entry<String, Map<String, Long>> e : byDim.entrySet()) {
            String dim = e.getKey();
            Map<String, Long> mods = e.getValue();
            long dimTotal = mods.values().stream().mapToLong(Long::longValue).sum();
            md.append("### `").append(dim).append("` (").append(humanDuration(dimTotal / 1_000_000L)).append(" total)\n\n");
            md.append("| Mod | Tick time | % of dim |\n");
            md.append("|---|---|---|\n");
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(mods.entrySet());
            sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            int shown = 0;
            for (Map.Entry<String, Long> me : sorted) {
                if (shown++ >= 15) {
                    md.append("| ... | ").append(sorted.size() - 15).append(" more | |\n");
                    break;
                }
                double pct = dimTotal > 0 ? 100.0 * me.getValue() / dimTotal : 0;
                md.append("| `").append(me.getKey()).append("`")
                        .append(" | ").append(humanDuration(me.getValue() / 1_000_000L))
                        .append(" | ").append(fmt1(pct)).append(" %")
                        .append(" |\n");
            }
            md.append("\n");
        }
    }

    private static String impactDescriptor(double pct) {
        if (pct < 1.0)  return "Negligible";
        if (pct < 5.0)  return "Low";
        if (pct < 15.0) return "Moderate";
        if (pct < 35.0) return "High";
        return "Severe";
    }

    private static void appendPlayerSessions(StringBuilder md, List<JsonNode> snapshots,
                                             long sessionStart, long sessionEnd) {
        md.append("## Players\n\n");
        if (snapshots.isEmpty()) {
            md.append("_No player snapshots recorded._\n\n");
            return;
        }
        // Reconstruct per-player presence from snapshots. For each
        // player UUID, accumulate the seconds of presence (number of
        // snapshots that included them, multiplied by sample interval).
        // Sample interval defaults to 2 seconds (players_topic 2 Hz).
        Map<String, PlayerAgg> agg = new HashMap<>();
        int peak = 0;
        long peakAt = 0;
        long lastTs = 0;
        for (JsonNode rec : snapshots) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            JsonNode arr = data.get("players");
            if (arr == null || !arr.isArray()) continue;
            long ts = rec.path("tsMs").asLong(0);
            long delta = lastTs > 0 ? ts - lastTs : 2000L;
            if (delta < 0 || delta > 10_000L) delta = 2000L; // sane fallback
            lastTs = ts;
            if (arr.size() > peak) {
                peak = arr.size();
                peakAt = ts;
            }
            for (JsonNode p : arr) {
                String uuid = p.path("uuid").asText("");
                String name = p.path("name").asText("?");
                PlayerAgg pa = agg.computeIfAbsent(uuid, k -> new PlayerAgg(name));
                pa.lastSeen = name; // latest name wins
                pa.presenceMs += delta;
                if (pa.firstSeenMs == 0) pa.firstSeenMs = ts;
                pa.lastSeenMs = ts;
            }
        }
        md.append("- **Peak online**: ").append(peak)
                .append(peakAt > 0 ? " at " + HUMAN_TS.format(new Date(peakAt)) : "").append("\n");
        md.append("- **Unique players seen**: ").append(agg.size()).append("\n\n");

        if (agg.isEmpty()) return;

        md.append("| Player | First seen | Last seen | Time online |\n");
        md.append("|---|---|---|---|\n");
        List<Map.Entry<String, PlayerAgg>> sorted = new ArrayList<>(agg.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, PlayerAgg> e) -> e.getValue().presenceMs).reversed());
        for (Map.Entry<String, PlayerAgg> e : sorted) {
            PlayerAgg pa = e.getValue();
            md.append("| `").append(pa.lastSeen).append("`")
                    .append(" | ").append(CLOCK_TS.format(new Date(pa.firstSeenMs)))
                    .append(" | ").append(CLOCK_TS.format(new Date(pa.lastSeenMs)))
                    .append(" | ").append(humanDuration(pa.presenceMs))
                    .append(" |\n");
        }
        md.append("\n");
    }

    private static void appendMobsTrend(StringBuilder md, List<JsonNode> snapshots) {
        md.append("## Mobs\n\n");
        if (snapshots.isEmpty()) {
            md.append("_No mob snapshots recorded._\n\n");
            return;
        }
        int totalPeak = 0;
        long peakAt = 0;
        Map<String, Integer> peakByDim = new TreeMap<>();
        ExtStats sTotal = new ExtStats();
        for (JsonNode rec : snapshots) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            int total = data.path("grandTotal").asInt(0);
            if (total == 0) {
                JsonNode byDim = data.get("byDim");
                if (byDim != null) {
                    Iterator<Map.Entry<String, JsonNode>> dims = byDim.fields();
                    while (dims.hasNext()) {
                        Map.Entry<String, JsonNode> de = dims.next();
                        int c = de.getValue().path("count").asInt(0);
                        total += c;
                        peakByDim.merge(de.getKey(), c, Math::max);
                    }
                }
            } else {
                // grandTotal already present; still walk by-dim for peak
                JsonNode byDim = data.get("byDim");
                if (byDim != null) {
                    Iterator<Map.Entry<String, JsonNode>> dims = byDim.fields();
                    while (dims.hasNext()) {
                        Map.Entry<String, JsonNode> de = dims.next();
                        int c = de.getValue().path("count").asInt(0);
                        peakByDim.merge(de.getKey(), c, Math::max);
                    }
                }
            }
            if (total > totalPeak) {
                totalPeak = total;
                peakAt = rec.path("tsMs").asLong(0);
            }
            sTotal.add(total);
        }
        md.append("- **Avg total mobs**: ").append(fmt0(sTotal.avg)).append("\n");
        md.append("- **Peak total mobs**: ").append(totalPeak)
                .append(peakAt > 0 ? " at " + HUMAN_TS.format(new Date(peakAt)) : "").append("\n");
        if (!peakByDim.isEmpty()) {
            md.append("- **Peak per dimension**:\n");
            for (Map.Entry<String, Integer> e : peakByDim.entrySet()) {
                md.append("    - `").append(e.getKey()).append("`: ").append(e.getValue()).append("\n");
            }
        }
        md.append("\n");
    }

    private static void appendAlertsTimeline(StringBuilder md, List<JsonNode> alerts) {
        md.append("## Alerts Timeline\n\n");
        if (alerts.isEmpty()) {
            md.append("_No alerts fired during this session._\n\n");
            return;
        }
        md.append("Total alerts: **").append(alerts.size()).append("**.\n\n");
        md.append("| Time | Severity | Kind | Message |\n");
        md.append("|---|---|---|---|\n");
        for (JsonNode a : alerts) {
            long tsMs = a.path("tsMs").asLong(0);
            String kind = a.path("kind").asText("");
            String severity = a.path("severity").asText("");
            String msg = a.path("msg").asText("");
            md.append("| ").append(HUMAN_TS.format(new Date(tsMs)))
                    .append(" | ").append(severity)
                    .append(" | ").append(kind)
                    .append(" | ").append(escMd(msg))
                    .append(" |\n");
        }
        md.append("\n");
    }

    private static void appendConsoleHighlights(StringBuilder md, List<JsonNode> consoleLogs) {
        md.append("## Console Highlights\n\n");
        if (consoleLogs.isEmpty()) {
            md.append("_No console lines captured._\n\n");
            return;
        }
        int errs = 0, warns = 0;
        Map<String, Integer> warnSrc = new HashMap<>();
        Map<String, Integer> errSrc = new HashMap<>();
        for (JsonNode log : consoleLogs) {
            String level = log.path("level").asText("");
            String logger = log.path("logger").asText("?");
            if (level.equalsIgnoreCase("ERROR")) {
                errs++;
                errSrc.merge(logger, 1, Integer::sum);
            } else if (level.equalsIgnoreCase("WARN")) {
                warns++;
                warnSrc.merge(logger, 1, Integer::sum);
            }
        }
        md.append("- Total lines: ").append(consoleLogs.size()).append("\n");
        md.append("- WARN: ").append(warns).append("\n");
        md.append("- ERROR: ").append(errs).append("\n\n");

        if (!warnSrc.isEmpty()) {
            md.append("**Top WARN sources**:\n\n");
            warnSrc.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> md.append("- `").append(e.getKey()).append("`: ").append(e.getValue()).append("\n"));
            md.append("\n");
        }
        if (!errSrc.isEmpty()) {
            md.append("**Top ERROR sources**:\n\n");
            errSrc.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> md.append("- `").append(e.getKey()).append("`: ").append(e.getValue()).append("\n"));
            md.append("\n");
        }
    }

    private static void appendCrashAnalysis(StringBuilder md, List<JsonNode> consoleLogs) {
        md.append("## Crash Analysis\n\n");
        md.append("Server exited with non-zero code. Below are the last 50 console lines (chronological), ")
                .append("any line in the session containing `Exception` or `Caused by:`, and the highest-volume ERROR sources.\n\n");

        // Last 50 lines
        md.append("### Last 50 console lines\n\n");
        md.append("```\n");
        int from = Math.max(0, consoleLogs.size() - 50);
        for (int i = from; i < consoleLogs.size(); i++) {
            JsonNode log = consoleLogs.get(i);
            String level = log.path("level").asText("");
            String msg = log.path("msg").asText("");
            String logger = log.path("logger").asText("");
            md.append("[").append(level).append("] [").append(logger).append("] ").append(msg).append("\n");
        }
        md.append("```\n\n");

        // Exception / Caused by lines
        md.append("### Exception lines\n\n");
        int shown = 0;
        StringBuilder excerpt = new StringBuilder();
        for (JsonNode log : consoleLogs) {
            String msg = log.path("msg").asText("");
            if (msg.contains("Exception") || msg.contains("Caused by:") || msg.startsWith("\tat ")) {
                if (shown++ >= 100) break;
                excerpt.append(msg).append("\n");
            }
        }
        if (excerpt.length() == 0) {
            md.append("_No `Exception`/`Caused by:` strings found in console output._\n\n");
        } else {
            md.append("```\n").append(excerpt).append("```\n\n");
        }
    }

    private static void appendRecommendations(StringBuilder md, List<JsonNode> tps, List<JsonNode> modsImpact) {
        md.append("## Recommendations\n\n");
        boolean any = false;
        if (!tps.isEmpty()) {
            ExtStats sTps20s   = collectExt(tps, "tps20s");
            ExtStats sMsptAvg  = collectExt(tps, "msptAvg");
            ExtStats sCpu      = collectExt(tps, "processCpuPct");
            // Heap %.
            ExtStats sHeapPct = new ExtStats();
            for (JsonNode n : tps) {
                JsonNode data = n.get("data");
                if (data == null) continue;
                long u = data.path("heapUsedBytes").asLong(0);
                long m = data.path("heapMaxBytes").asLong(0);
                if (m > 0) sHeapPct.add(100.0 * u / m);
            }
            // GC overhead.
            long totalGcMs = 0;
            for (JsonNode n : tps) {
                JsonNode data = n.get("data");
                if (data == null) continue;
                totalGcMs += data.path("gcPauseMsLastSec").asLong(0);
            }
            double gcOverhead = tps.size() > 0 ? 100.0 * totalGcMs / (tps.size() * 1000L) : 0;

            if (sTps20s.avg < 18.0) {
                md.append("- TPS averaged ").append(fmt1(sTps20s.avg))
                        .append(" -- server can't keep up. Reduce mob/chunk load or profile heaviest mods.\n");
                any = true;
            }
            if (sMsptAvg.max > 70.0) {
                md.append("- MSPT spiked to ").append(fmt0(sMsptAvg.max))
                        .append(" ms -- investigate the corresponding tick (check console_log entries near that timestamp).\n");
                any = true;
            }
            if (sHeapPct.count() > 0 && sHeapPct.max > 90.0) {
                md.append("- Heap peaked at ").append(fmt0(sHeapPct.max))
                        .append(" % -- increase `-Xmx` to give the JVM more headroom.\n");
                any = true;
            }
            if (sHeapPct.count() > 0 && sHeapPct.percentile(95) > 80.0) {
                md.append("- 95th-percentile heap was ").append(fmt0(sHeapPct.percentile(95)))
                        .append(" % -- chronic high heap, even between GCs. Consider larger -Xmx or fewer chunks loaded.\n");
                any = true;
            }
            if (gcOverhead > 5.0) {
                md.append("- GC overhead was ").append(fmt2(gcOverhead))
                        .append(" % of session wall time -- significant. Profile allocation churn (JFR or async-profiler).\n");
                any = true;
            }
            if (sCpu.avg > 80.0) {
                md.append("- Process CPU averaged ").append(fmt0(sCpu.avg))
                        .append(" % -- single core saturation likely. Consider lighter mods or more JVM threads.\n");
                any = true;
            }
        }
        if (!modsImpact.isEmpty()) {
            // Top mod >= 10 % of session = call it out.
            Map<String, Long> totals = new HashMap<>();
            long sessionNanos = (long) modsImpact.size() * 1_000_000_000L;
            for (JsonNode rec : modsImpact) {
                JsonNode byDim = rec.path("data").path("byDim");
                if (!byDim.isObject()) continue;
                Iterator<Map.Entry<String, JsonNode>> dims = byDim.fields();
                while (dims.hasNext()) {
                    Iterator<Map.Entry<String, JsonNode>> mit = dims.next().getValue().fields();
                    while (mit.hasNext()) {
                        Map.Entry<String, JsonNode> me = mit.next();
                        totals.merge(me.getKey(), me.getValue().path("attributedNanos").asLong(0), Long::sum);
                    }
                }
            }
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                double pct = sessionNanos > 0 ? 100.0 * e.getValue() / sessionNanos : 0;
                if (pct >= 10.0) {
                    md.append("- Mod `").append(e.getKey())
                            .append("` consumed ").append(fmt1(pct))
                            .append(" % of session tick time -- profile its config or consider an alternative.\n");
                    any = true;
                }
            }
        }
        if (!any) md.append("- No issues flagged. Server performance looks healthy.\n");
        md.append("\n_Generated by Father Eye Panel._\n");
    }

    // ---- helpers ------------------------------------------------------

    private static ExtStats collectExt(List<JsonNode> snapshots, String field) {
        ExtStats s = new ExtStats();
        for (JsonNode n : snapshots) {
            JsonNode data = n.get("data");
            if (data == null) continue;
            JsonNode v = data.get(field);
            if (v == null || !v.isNumber()) continue;
            s.add(v.asDouble());
        }
        return s;
    }

    private static int countAtMost(List<JsonNode> snapshots, String field, double threshold) {
        int n = 0;
        for (JsonNode rec : snapshots) {
            JsonNode data = rec.get("data");
            if (data == null) continue;
            double v = data.path(field).asDouble(Double.MAX_VALUE);
            if (v <= threshold) n++;
        }
        return n;
    }

    private static int countAbove(List<Double> values, double threshold) {
        int c = 0;
        for (Double v : values) if (v > threshold) c++;
        return c;
    }

    /**
     * Pnl-47: ASCII sparkline rendering. Buckets the values across
     * SPARK_WIDTH columns (averaging samples in each bucket) and
     * scales each value into one of the 8 Unicode block characters.
     * Returns a single-line string.
     */
    private static String sparkline(List<Double> values, double scaleMin, double scaleMax, int width) {
        if (values.isEmpty()) return "(no data)";
        if (scaleMax <= scaleMin) scaleMax = scaleMin + 1.0;
        double[] buckets = new double[Math.max(1, width)];
        int[] counts = new int[buckets.length];
        for (int i = 0; i < values.size(); i++) {
            int b = (int) ((long) i * buckets.length / Math.max(1, values.size()));
            if (b >= buckets.length) b = buckets.length - 1;
            buckets[b] += values.get(i);
            counts[b]++;
        }
        StringBuilder sb = new StringBuilder(buckets.length);
        for (int i = 0; i < buckets.length; i++) {
            if (counts[i] == 0) {
                sb.append(' ');
                continue;
            }
            double avg = buckets[i] / counts[i];
            double norm = (avg - scaleMin) / (scaleMax - scaleMin);
            if (norm < 0) norm = 0;
            if (norm > 1) norm = 1;
            int idx = (int) Math.round(norm * (SPARK_CHARS.length - 1));
            sb.append(SPARK_CHARS[idx]);
        }
        return sb.toString();
    }

    /** Pnl-47: minimal markdown escaping for table cells. */
    private static String escMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt0(double v) { return String.format(Locale.US, "%.0f", v); }
    private static String fmt1(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String fmt2(double v) { return String.format(Locale.US, "%.2f", v); }

    private static String humanBytes(long b) {
        if (b < 1024L) return b + " B";
        double k = b / 1024.0;
        if (k < 1024.0) return String.format(Locale.US, "%.1f KiB", k);
        double m = k / 1024.0;
        if (m < 1024.0) return String.format(Locale.US, "%.1f MiB", m);
        return String.format(Locale.US, "%.2f GiB", m / 1024.0);
    }

    private static String humanDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append("h ");
        if (h > 0 || m > 0) b.append(m).append("m ");
        b.append(sec).append("s");
        return b.toString();
    }

    /**
     * Pnl-47: extended Stats that retains all values to compute
     * percentiles. avg/min/max/count/sum are kept incrementally; the
     * values list backs percentile() with linear interpolation.
     */
    private static class ExtStats {
        final List<Double> values = new ArrayList<>();
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double avg = 0;

        int count() { return values.size(); }
        void add(double v) {
            values.add(v);
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            avg = sum / values.size();
        }
        /** p in [0,100]. Returns interpolated percentile, or 0 if empty. */
        double percentile(double p) {
            int n = values.size();
            if (n == 0) return 0.0;
            if (n == 1) return values.get(0);
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            double rank = (p / 100.0) * (n - 1);
            int lo = (int) Math.floor(rank);
            int hi = (int) Math.ceil(rank);
            if (lo == hi) return sorted.get(lo);
            double frac = rank - lo;
            return sorted.get(lo) * (1.0 - frac) + sorted.get(hi) * frac;
        }
    }

    private static class ModAgg {
        long totalNanos = 0;
        int entityPeak = 0;
        int tilePeak = 0;
        int sampleCount = 0;
    }

    private static class PlayerAgg {
        String lastSeen;
        long firstSeenMs = 0;
        long lastSeenMs = 0;
        long presenceMs = 0;
        PlayerAgg(String name) { this.lastSeen = name; }
    }

    private static class Spike {
        long startMs = 0;
        long endMs = 0;
        double minTps = 20.0;
        double maxMspt = 0.0;
        int sampleCount = 0;
    }
}
