package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-mod tick attribution rendered as a sortable table.
 *
 * <p>Pnl-39 (2026-04-26): the previous implementation only displayed
 * mods that the bridge had attributed tick time to in the last second.
 * On a 100+ mod modpack the user observed only six rows (the mods that
 * happened to have ticking entities or tile entities at that moment),
 * even though the bridge's Welcome message reports the full installed
 * mod set. The fix surfaces the known-mod list from
 * {@code WelcomeInfo.modIds} via {@link #setKnownMods(String[])} and
 * merges it with each impact snapshot: every installed mod gets a row,
 * with a synthesized "all dims" placeholder row carrying an "Idle"
 * descriptor when the mod has no entities or TEs. Mods with active
 * impact still rank by attributed nanos descending; idle rows sink to
 * the bottom and sort alphabetically among themselves.
 */
public final class ModsPane {

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label header = new Label("Mod impact (proportional, approx)");
    private final VBox root = new VBox(6);

    /** Pnl-39: full mod ID set from the bridge handshake. Empty until
     *  {@link #setKnownMods(String[])} is called. Read on the IPC
     *  thread inside {@link #onSnapshot(JsonNode)} and on the FX
     *  thread inside {@link #renderFromCache()}. */
    private volatile Set<String> knownMods = new LinkedHashSet<>();
    /** Pnl-39: most recent impact payload, retained so a late
     *  {@link #setKnownMods(String[])} call can re-render the table
     *  with placeholder rows merged in even when no new snapshot has
     *  arrived. */
    private volatile JsonNode lastPayload = null;

    public ModsPane() {
        addCol("Dimension", r -> r.dim);
        addCol("Mod", r -> r.mod);
        addCol("Entities", r -> r.entityCount);
        addCol("Tile Entities", r -> r.tileEntityCount);
        addCol("Tick share (ms/s)", r -> r.attributedMsPerSec);
        addCol("Tick %", r -> r.tickPctText);
        addImpactColumn();

        header.setStyle("-fx-padding: 4; -fx-font-weight: bold;");
        Label note = new Label("Approximation: per-dim tick wall time distributed proportionally "
                + "to (entityCount+TECount). Mods with no ticking entities or TEs show as Idle. "
                + "Mixin-based per-entity timing is a future bridge upgrade.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #888; -fx-padding: 0 8 4 8;");

        root.setPadding(new Insets(8));
        root.getChildren().addAll(header, note, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    public VBox root() { return root; }

    /**
     * Pnl-39: seed the table with the full installed mod set. Called
     * once after the bridge handshake from {@code App.tryConnect}.
     * Invoking before any snapshot has arrived populates the table
     * with all-Idle placeholder rows so the user sees mod coverage
     * immediately. Safe to call from any thread.
     */
    public void setKnownMods(String[] modIds) {
        Set<String> set = new LinkedHashSet<>();
        if (modIds != null) {
            for (String id : modIds) {
                if (id != null && !id.isEmpty()) set.add(id);
            }
        }
        this.knownMods = set;
        // Pnl-39 (audit fix): clear cached impact payload too. On
        // reconnect to a different server, retaining the previous
        // session's lastPayload would render impact rows for mods
        // that don't exist in the new mod set until the first fresh
        // snapshot arrives. Drop it so the table goes through an
        // all-Idle frame on reconnect.
        this.lastPayload = null;
        // Re-render so the table reflects the new known-mod set even
        // if no impact snapshot has arrived yet.
        renderFromCache();
    }

    public void onSnapshot(JsonNode payload) {
        if (payload == null) return;
        this.lastPayload = payload;
        renderFromCache();
    }

    /**
     * Build rows from {@link #lastPayload} merged with
     * {@link #knownMods}, then publish to the FX thread. Called from
     * either the IPC thread (via {@link #onSnapshot(JsonNode)}) or
     * any thread (via {@link #setKnownMods(String[])}); the only
     * FX-thread interaction is the final {@code rows.setAll}.
     */
    private void renderFromCache() {
        List<Row> newRows = new ArrayList<>();
        Set<String> seenMods = new HashSet<>();
        JsonNode payload = lastPayload;
        if (payload != null) {
            JsonNode data = payload.get("data");
            if (data != null) {
                JsonNode byDim = data.get("byDim");
                if (byDim != null) {
                    Iterator<Map.Entry<String, JsonNode>> dims = byDim.fields();
                    while (dims.hasNext()) {
                        Map.Entry<String, JsonNode> dimEntry = dims.next();
                        String dim = dimEntry.getKey();
                        JsonNode mods = dimEntry.getValue();
                        Iterator<Map.Entry<String, JsonNode>> modIt = mods.fields();
                        while (modIt.hasNext()) {
                            Map.Entry<String, JsonNode> me = modIt.next();
                            Row r = buildImpactRow(dim, me.getKey(), me.getValue());
                            seenMods.add(me.getKey());
                            newRows.add(r);
                        }
                    }
                }
            }
        }

        // Pnl-39: append placeholder rows for installed mods with no
        // entries in the impact snapshot. They sort to the bottom
        // because their sortKey is 0.
        int idleCount = 0;
        for (String modId : knownMods) {
            if (!seenMods.contains(modId)) {
                newRows.add(buildIdleRow(modId));
                idleCount++;
            }
        }
        final int activeCount = newRows.size() - idleCount;
        final int finalIdle = idleCount;

        // Pnl-39 (audit fix): primary sort by impact descending
        // (sortKey is attributedNanos for active rows, 0 for idle so
        // they sink). Secondary sort alphabetically by mod ID so the
        // ~108 idle rows on a typical modpack render in a predictable
        // order. LinkedHashSet preserves Forge load order, which is
        // not what an operator wants to scan visually. ArrayList.sort
        // is stable, so equal-sortKey rows respect the secondary
        // comparator.
        newRows.sort(Comparator.comparingLong((Row r) -> r.sortKey).reversed()
                .thenComparing(r -> r.mod == null ? "" : r.mod));

        Platform.runLater(() -> {
            rows.setAll(newRows);
            // Pnl-39 (audit fix): show the operator how many mods
            // contributed tick time vs. how many are loaded but idle
            // this second.
            header.setText(String.format(
                    "Mod impact (proportional, approx): %d active, %d idle",
                    activeCount, finalIdle));
        });
    }

    private Row buildImpactRow(String dim, String mod, JsonNode slot) {
        Row r = new Row();
        r.dim = dim;
        r.mod = mod;
        long ns = slot.path("attributedNanos").asLong(0);
        r.entityCount = String.valueOf(slot.path("entityCount").asInt(0));
        r.tileEntityCount = String.valueOf(slot.path("tileEntityCount").asInt(0));
        r.attributedMsPerSec = String.format("%.2f", ns / 1_000_000.0);
        // Pnl-31: per-mod impact rating. The publish cadence is 1 Hz
        // so attributedNanos == ns of tick time consumed over the
        // last second; 1e9 ns = 100% of one real-time second. Bucket
        // into descriptors for the Impact column.
        double pct = Math.min(100.0, ns / 10_000_000.0);
        r.tickPct = pct;
        r.tickPctText = String.format("%.2f %%", pct);
        r.impactDescriptor = impactBucket(pct);
        r.impactColor = impactColor(pct);
        r.sortKey = ns;
        return r;
    }

    /**
     * Pnl-39: synthesize a placeholder row for a mod that the bridge
     * reports as installed but which has no ticking entities or TEs
     * in the current snapshot. Sorts to the bottom (sortKey 0).
     */
    private Row buildIdleRow(String modId) {
        Row r = new Row();
        // Pnl-39 (audit fix): the Dimension column should hold a dim,
        // not a status. The Impact column already conveys "Idle" via
        // its grey descriptor pill. "all dims" reads as "this mod's
        // tick share is zero across every loaded dim", which matches
        // what the bridge actually reported.
        r.dim = "all dims";
        r.mod = modId;
        r.entityCount = "0";
        r.tileEntityCount = "0";
        r.attributedMsPerSec = "0.00";
        r.tickPct = 0.0;
        r.tickPctText = "0.00 %";
        r.impactDescriptor = "Idle";
        r.impactColor = "#888888";
        r.sortKey = 0L;
        return r;
    }

    private void addCol(String name, java.util.function.Function<Row, String> getter) {
        TableColumn<Row, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    /**
     * Pnl-31: per-mod impact column. Renders the descriptor (Negligible,
     * Low, Moderate, High, Severe) with a coloured pill plus a thin
     * inline progress bar showing the mod's share of one full real-time
     * second.
     */
    private void addImpactColumn() {
        TableColumn<Row, Row> c = new TableColumn<>("Impact");
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        c.setCellFactory(col -> new javafx.scene.control.TableCell<Row, Row>() {
            private final javafx.scene.control.Label badge = new javafx.scene.control.Label();
            private final javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
            private final VBox box = new VBox(2, badge, bar);
            {
                bar.setMaxWidth(Double.MAX_VALUE);
                badge.setStyle("-fx-font-weight: bold;");
            }
            @Override protected void updateItem(Row r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setGraphic(null);
                } else {
                    badge.setText(r.impactDescriptor + "  " + String.format("(%.1f%%)", r.tickPct));
                    badge.setStyle("-fx-font-weight: bold; -fx-text-fill: " + r.impactColor + ";");
                    bar.setProgress(Math.max(0, Math.min(1, r.tickPct / 100.0)));
                    bar.setStyle("-fx-accent: " + r.impactColor + ";");
                    setGraphic(box);
                }
            }
        });
        c.setPrefWidth(180);
        table.getColumns().add(c);
    }

    private static String impactBucket(double pct) {
        if (pct < 1.0)   return "Negligible";
        if (pct < 5.0)   return "Low";
        if (pct < 15.0)  return "Moderate";
        if (pct < 35.0)  return "High";
        return "Severe";
    }

    private static String impactColor(double pct) {
        if (pct < 1.0)   return "#7ecf6f";
        if (pct < 5.0)   return "#a8d36a";
        if (pct < 15.0)  return "#e0c060";
        if (pct < 35.0)  return "#e0a060";
        return "#e07060";
    }

    public static final class Row {
        public String dim, mod, entityCount, tileEntityCount, attributedMsPerSec, tickPctText;
        public String impactDescriptor, impactColor;
        public double tickPct;
        long sortKey;
    }
}
