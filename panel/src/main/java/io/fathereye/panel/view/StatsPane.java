package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.fx.ChartViewer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

/**
 * TPS / MSPT / heap charts and current-value readouts. Updated from the
 * IPC reader thread via {@link #onTpsSnapshot}.
 */
public final class StatsPane {

    private final Label lblTps20s = makeValue();
    private final Label lblTps1m  = makeValue();
    private final Label lblTps5m  = makeValue();
    private final Label lblMsptAvg = makeValue();
    private final Label lblMsptP95 = makeValue();
    private final Label lblHeapText = makeValue();
    private final Label lblGc = makeValue();
    private final Label lblThreads = makeValue();
    private final ProgressBar heapBar = new ProgressBar(0);

    // Pnl-29 (2026-04-26): Server Health Assessment.
    // A composite 0-20 score with a descriptor and per-load-factor bars
    // so the operator can see what's eating budget at a glance. The score
    // starts at 20 and subtracts deduction points for each load factor;
    // the bar percentages show the relative pressure each factor is under.
    private final Label lblHealthScore = new Label("--/20");
    private final Label lblHealthDescriptor = new Label("--");
    private final ProgressBar barTps   = new ProgressBar(0);
    private final ProgressBar barMspt  = new ProgressBar(0);
    private final ProgressBar barHeap  = new ProgressBar(0);
    private final ProgressBar barGc    = new ProgressBar(0);
    private final ProgressBar barCpu   = new ProgressBar(0);
    private final ProgressBar barTopMod = new ProgressBar(0);
    private final Label lblBarTps  = new Label("--");
    private final Label lblBarMspt = new Label("--");
    private final Label lblBarHeap = new Label("--");
    private final Label lblBarGc   = new Label("--");
    private final Label lblBarCpu  = new Label("--");
    private final Label lblBarTopMod = new Label("--");
    private final Label lblTopModRowName = new Label("Top mod");

    /**
     * Latest top-mod metrics from mods_impact_topic. Updated on the IPC
     * thread, read on the FX thread when {@link #updateHealth} runs.
     * volatile read+write — both threads only see whole snapshots, never
     * a half-updated pair.
     */
    private volatile String topModName = "";
    private volatile double topModPctTickBudget = 0.0; // 0.0 .. 100.0

    private final TimeSeries tpsSeries     = new TimeSeries("TPS (20s)");
    private final TimeSeries msptSeries    = new TimeSeries("MSPT avg");
    private final TimeSeries heapSeries    = new TimeSeries("Heap %");
    private final TimeSeries gcMsSeries    = new TimeSeries("GC ms / sec");
    private final TimeSeries threadsSeries = new TimeSeries("Live threads");
    private final TimeSeries cpuSeries     = new TimeSeries("Process CPU %");

    private final ChartViewer tpsChart;
    private final ChartViewer msptChart;
    private final ChartViewer heapChart;
    private final ChartViewer gcChart;
    private final ChartViewer threadsChart;
    private final ChartViewer cpuChart;

    private final VBox root = new VBox(10);

    public StatsPane() {
        TimeSeriesCollection tpsDs     = new TimeSeriesCollection(tpsSeries);
        TimeSeriesCollection msptDs    = new TimeSeriesCollection(msptSeries);
        TimeSeriesCollection heapDs    = new TimeSeriesCollection(heapSeries);
        TimeSeriesCollection gcDs      = new TimeSeriesCollection(gcMsSeries);
        TimeSeriesCollection threadsDs = new TimeSeriesCollection(threadsSeries);
        TimeSeriesCollection cpuDs     = new TimeSeriesCollection(cpuSeries);

        JFreeChart c1 = ChartFactory.createTimeSeriesChart("Server TPS", "Time", "TPS", tpsDs, false, false, false);
        JFreeChart c2 = ChartFactory.createTimeSeriesChart("MSPT (ms per tick)", "Time", "ms", msptDs, false, false, false);
        JFreeChart c3 = ChartFactory.createTimeSeriesChart("Heap usage", "Time", "%", heapDs, false, false, false);
        JFreeChart c4 = ChartFactory.createTimeSeriesChart("GC pause (ms / sec)", "Time", "ms", gcDs, false, false, false);
        JFreeChart c5 = ChartFactory.createTimeSeriesChart("Live threads", "Time", "count", threadsDs, false, false, false);
        JFreeChart c6 = ChartFactory.createTimeSeriesChart("Process CPU", "Time", "%", cpuDs, false, false, false);
        // Mac fork (audit 5): trimmed 900 -> 300 to match the documented
        // 5-min @ 1 Hz rolling window (300 points). 900 was an upstream
        // overshoot that wasted heap on a 4-thread Sandy Bridge.
        tpsSeries.setMaximumItemCount(300);
        msptSeries.setMaximumItemCount(300);
        heapSeries.setMaximumItemCount(300);
        gcMsSeries.setMaximumItemCount(300);
        threadsSeries.setMaximumItemCount(300);
        cpuSeries.setMaximumItemCount(300);

        tpsChart     = new ChartViewer(c1);
        msptChart    = new ChartViewer(c2);
        heapChart    = new ChartViewer(c3);
        gcChart      = new ChartViewer(c4);
        threadsChart = new ChartViewer(c5);
        cpuChart     = new ChartViewer(c6);

        GridPane currentValues = new GridPane();
        currentValues.setHgap(20);
        currentValues.setVgap(6);
        currentValues.setPadding(new Insets(8));
        addRow(currentValues, 0, "TPS 20s",  lblTps20s);
        addRow(currentValues, 1, "TPS 1m",   lblTps1m);
        addRow(currentValues, 2, "TPS 5m",   lblTps5m);
        addRow(currentValues, 3, "MSPT avg", lblMsptAvg);
        addRow(currentValues, 4, "MSPT p95", lblMsptP95);
        addRow(currentValues, 5, "Heap",     lblHeapText);
        addRow(currentValues, 6, "GC",       lblGc);
        addRow(currentValues, 7, "Threads",  lblThreads);

        heapBar.setMaxWidth(Double.MAX_VALUE);

        // Build the Server Health Assessment block.
        VBox healthBlock = buildHealthBlock();

        HBox topRow = new HBox(20, currentValues, new VBox(new Label("Heap"), heapBar));
        HBox.setHgrow(topRow.getChildren().get(0), Priority.NEVER);
        HBox.setHgrow(topRow.getChildren().get(1), Priority.ALWAYS);

        // Row 1: TPS / MSPT / Heap (the headline charts).
        HBox row1 = new HBox(8, tpsChart, msptChart, heapChart);
        HBox.setHgrow(tpsChart, Priority.ALWAYS);
        HBox.setHgrow(msptChart, Priority.ALWAYS);
        HBox.setHgrow(heapChart, Priority.ALWAYS);
        // Row 2: GC / Threads / CPU (deeper diagnostics).
        HBox row2 = new HBox(8, gcChart, threadsChart, cpuChart);
        HBox.setHgrow(gcChart, Priority.ALWAYS);
        HBox.setHgrow(threadsChart, Priority.ALWAYS);
        HBox.setHgrow(cpuChart, Priority.ALWAYS);

        VBox.setVgrow(row1, Priority.ALWAYS);
        VBox.setVgrow(row2, Priority.ALWAYS);

        root.setPadding(new Insets(8));
        root.getChildren().addAll(healthBlock, topRow, row1, row2);
    }

    private VBox buildHealthBlock() {
        // Big rating + descriptor on the left; load-factor bars on the right.
        lblHealthScore.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: #cfcfcf;");
        lblHealthDescriptor.setStyle("-fx-font-size: 14px; -fx-text-fill: #aaa;");
        VBox ratingBox = new VBox(2, new Label("Server Health"), lblHealthScore, lblHealthDescriptor);
        ratingBox.setPadding(new Insets(4, 16, 4, 4));
        ((Label) ratingBox.getChildren().get(0)).setStyle("-fx-font-weight: bold; -fx-text-fill: #cfcfcf;");

        GridPane bars = new GridPane();
        bars.setHgap(8);
        bars.setVgap(4);
        addBarRow(bars, 0, "TPS deficit",  barTps,  lblBarTps);
        addBarRow(bars, 1, "MSPT load",    barMspt, lblBarMspt);
        addBarRow(bars, 2, "Heap pressure",barHeap, lblBarHeap);
        addBarRow(bars, 3, "GC pressure",  barGc,   lblBarGc);
        addBarRow(bars, 4, "CPU load",     barCpu,  lblBarCpu);
        // Pnl-30 top-mod row: dynamic name (whichever mod is currently
        // dominating tick budget) instead of the fixed label.
        lblTopModRowName.setStyle("-fx-text-fill: #cfcfcf;");
        barTopMod.setMaxWidth(Double.MAX_VALUE);
        barTopMod.setProgress(0);
        lblBarTopMod.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #cfcfcf;");
        bars.add(lblTopModRowName, 0, 5);
        bars.add(barTopMod, 1, 5);
        bars.add(lblBarTopMod, 2, 5);
        // Make the bar columns expand to fill width.
        javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints();
        c0.setMinWidth(110);
        c1.setHgrow(Priority.ALWAYS);
        c2.setMinWidth(70);
        bars.getColumnConstraints().setAll(c0, c1, c2);

        HBox row = new HBox(20, ratingBox, bars);
        HBox.setHgrow(bars, Priority.ALWAYS);
        VBox block = new VBox(row);
        block.setPadding(new Insets(6, 6, 10, 6));
        block.setStyle("-fx-background-color: #232323; -fx-background-radius: 4;");
        return block;
    }

    private static void addBarRow(GridPane g, int row, String name, ProgressBar bar, Label valueLabel) {
        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-text-fill: #cfcfcf;");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setProgress(0);
        valueLabel.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #cfcfcf;");
        g.add(nameLbl, 0, row);
        g.add(bar, 1, row);
        g.add(valueLabel, 2, row);
    }

    public VBox root() { return root; }

    /** Called from the IPC reader thread. Re-marshals onto the UI thread. */
    public void onTpsSnapshot(JsonNode payload) {
        if (payload == null) return;
        // payload is the Snapshot wrapper: { seq, data }
        JsonNode data = payload.get("data");
        if (data == null) return;

        double tps20s   = data.path("tps20s").asDouble(0);
        double tps1m    = data.path("tps1m").asDouble(0);
        double tps5m    = data.path("tps5m").asDouble(0);
        double msptAvg  = data.path("msptAvg").asDouble(0);
        double msptP50  = data.path("msptP50").asDouble(0);
        double msptP95  = data.path("msptP95").asDouble(0);
        double msptP99  = data.path("msptP99").asDouble(0);
        long heapUsed   = data.path("heapUsedBytes").asLong(0);
        long heapMax    = data.path("heapMaxBytes").asLong(1);
        long gcMs       = data.path("gcPauseMsLastSec").asLong(0);
        int gcCount     = data.path("gcCountLastSec").asInt(0);
        int threadCount = data.path("liveThreadCount").asInt(0);
        double cpuPct   = data.path("processCpuPct").asDouble(0);

        double heapPct = heapMax > 0 ? ((double) heapUsed / heapMax) * 100.0 : 0;

        Platform.runLater(() -> {
            lblTps20s.setText(String.format("%.2f", tps20s));
            lblTps1m.setText(String.format("%.2f", tps1m));
            lblTps5m.setText(String.format("%.2f", tps5m));
            lblMsptAvg.setText(String.format("%.2f ms", msptAvg));
            lblMsptP95.setText(String.format("p50 %.2f / p95 %.2f / p99 %.2f", msptP50, msptP95, msptP99));
            lblHeapText.setText(String.format("%s / %s (%.1f%%)", humanBytes(heapUsed), humanBytes(heapMax), heapPct));
            lblGc.setText(gcMs + " ms / " + gcCount + " collections");
            lblThreads.setText(Integer.toString(threadCount));
            heapBar.setProgress(heapPct / 100.0);

            Millisecond now = new Millisecond();
            tpsSeries.addOrUpdate(now, tps20s);
            msptSeries.addOrUpdate(now, msptAvg);
            heapSeries.addOrUpdate(now, heapPct);
            gcMsSeries.addOrUpdate(now, gcMs);
            threadsSeries.addOrUpdate(now, threadCount);
            cpuSeries.addOrUpdate(now, cpuPct);

            updateHealth(tps20s, msptAvg, heapPct, gcMs, cpuPct);
        });
    }

    /**
     * Pnl-30: receive the latest mods_impact_topic Snapshot and update
     * the top-mod load factor. Called from the IPC reader thread; we just
     * stash the values, the FX thread reads them on the next health pass.
     *
     * <p>Top mod is the mod whose summed-across-dims attributedNanos is
     * largest. The publish cadence is 1 Hz, so attributedNanos is "ns of
     * tick time consumed by this mod over the last second". 1 second of
     * server time = 1e9 ns at full health (20 TPS); &gt; 1e9 ns means the
     * mod was eating more than the full real-time budget, which only
     * happens when TPS is already below 20 (mods can keep adding work
     * even past wall-clock). We clip the percentage at 100.
     */
    public void onModsImpactSnapshot(com.fasterxml.jackson.databind.JsonNode payload) {
        if (payload == null) return;
        com.fasterxml.jackson.databind.JsonNode data = payload.get("data");
        if (data == null) return;
        com.fasterxml.jackson.databind.JsonNode byDim = data.get("byDim");
        if (byDim == null) return;

        java.util.HashMap<String, Long> sumByMod = new java.util.HashMap<>();
        java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> dims = byDim.fields();
        while (dims.hasNext()) {
            java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> dimEntry = dims.next();
            com.fasterxml.jackson.databind.JsonNode mods = dimEntry.getValue();
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> mit = mods.fields();
            while (mit.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> me = mit.next();
                long ns = me.getValue().path("attributedNanos").asLong(0);
                sumByMod.merge(me.getKey(), ns, Long::sum);
            }
        }
        String topName = "";
        long topNanos = 0L;
        for (java.util.Map.Entry<String, Long> e : sumByMod.entrySet()) {
            if (e.getValue() > topNanos) {
                topNanos = e.getValue();
                topName = e.getKey();
            }
        }
        // Convert ns/sec to % of one full real-time second (1e9 ns = 100%).
        double pct = Math.min(100.0, topNanos / 10_000_000.0);
        this.topModName = topName == null ? "" : topName;
        this.topModPctTickBudget = pct;
    }

    /**
     * Compute and render the Server Health Assessment.
     *
     * <p>Score starts at 20.0 and subtracts deductions per load factor.
     * Each factor's bar shows its share of the deduction budget so the
     * operator can immediately see what is hurting the server.
     *
     * <ul>
     *   <li>TPS deficit: 0.5 pt per TPS below 20, capped at 5.0
     *       (TPS &lt;= 10 → max deduction).</li>
     *   <li>MSPT load: above 30 ms costs 0.1 pt/ms, capped at 5.0
     *       (MSPT &gt;= 80 ms → max deduction).</li>
     *   <li>Heap pressure: above 75% costs 0.16 pt/%, capped at 4.0
     *       (heap = 100% → max deduction).</li>
     *   <li>GC pressure: above 50 ms/sec pause costs 0.06 pt/ms,
     *       capped at 3.0 (gc &gt;= 100 ms/sec → max deduction).</li>
     *   <li>CPU load: above 70% costs 0.10 pt/%, capped at 3.0
     *       (CPU = 100% → max deduction).</li>
     * </ul>
     */
    private void updateHealth(double tps, double mspt, double heapPct, long gcMs, double cpuPct) {
        double dTps  = clamp((20.0 - tps) * 0.5, 0.0, 5.0);
        double dMspt = clamp((mspt - 30.0) * 0.1, 0.0, 5.0);
        double dHeap = clamp((heapPct - 75.0) * 0.16, 0.0, 4.0);
        double dGc   = clamp((gcMs - 50.0) * 0.06, 0.0, 3.0);
        double dCpu  = clamp((cpuPct - 70.0) * 0.10, 0.0, 3.0);
        // Top mod: 5% of tick budget = ignored, then 0.0667 pt/% up to
        // cap 3.0 at 50%+ tick share. Mods routinely use 1-3% on a busy
        // server; 10%+ is a clear signal one mod dominates.
        double topModPct = topModPctTickBudget;
        double dMod  = clamp((topModPct - 5.0) * 0.0667, 0.0, 3.0);
        double score = clamp(20.0 - dTps - dMspt - dHeap - dGc - dCpu - dMod, 0.0, 20.0);

        lblHealthScore.setText(String.format("%.1f/20", score));
        String descriptor;
        String color;
        if (score >= 19.0)      { descriptor = "Excellent"; color = "#7ecf6f"; }
        else if (score >= 16.0) { descriptor = "Good";      color = "#a8d36a"; }
        else if (score >= 12.0) { descriptor = "Fair";      color = "#e0c060"; }
        else if (score >= 8.0)  { descriptor = "Poor";      color = "#e0a060"; }
        else                    { descriptor = "Critical";  color = "#e07060"; }
        lblHealthScore.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        lblHealthDescriptor.setText(descriptor);
        lblHealthDescriptor.setStyle("-fx-font-size: 14px; -fx-text-fill: " + color + ";");

        // Each bar's progress is its deduction divided by its cap, so a
        // factor that's hitting the cap reads as 100% pressure.
        setBar(barTps,  lblBarTps,  dTps  / 5.0, String.format("%.2f TPS",  tps));
        setBar(barMspt, lblBarMspt, dMspt / 5.0, String.format("%.1f ms",   mspt));
        setBar(barHeap, lblBarHeap, dHeap / 4.0, String.format("%.0f%%",    heapPct));
        setBar(barGc,   lblBarGc,   dGc   / 3.0, gcMs + " ms/s");
        setBar(barCpu,  lblBarCpu,  dCpu  / 3.0, String.format("%.0f%%",    cpuPct));
        // Top-mod row: dynamic label, percentage, and value text.
        String modShown = topModName.isEmpty() ? "Top mod" : ("Top mod (" + topModName + ")");
        lblTopModRowName.setText(modShown);
        setBar(barTopMod, lblBarTopMod, dMod / 3.0, String.format("%.1f%%", topModPct));
    }

    private static void setBar(ProgressBar bar, Label label, double pressure, String text) {
        double p = Math.max(0, Math.min(1, pressure));
        bar.setProgress(p);
        // Recolour the bar based on pressure: green / yellow / red.
        String color = p < 0.25 ? "#3a8a3a" : p < 0.6 ? "#a89028" : "#a83838";
        bar.setStyle("-fx-accent: " + color + ";");
        label.setText(text);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String humanBytes(long b) {
        if (b < 1024L) return b + " B";
        double k = b / 1024.0;
        if (k < 1024.0) return String.format("%.1f KiB", k);
        double m = k / 1024.0;
        if (m < 1024.0) return String.format("%.1f MiB", m);
        return String.format("%.2f GiB", m / 1024.0);
    }

    private static Label makeValue() {
        Label l = new Label("--");
        l.setStyle("-fx-font-family: 'Consolas', monospace;");
        return l;
    }

    private static void addRow(GridPane g, int row, String name, Label value) {
        Label n = new Label(name);
        n.setStyle("-fx-font-weight: bold;");
        g.add(n, 0, row);
        g.add(value, 1, row);
    }
}
