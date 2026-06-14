package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.panel.launcher.BackupOps;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Panel Rollback tab. Desktop twin of the web portal's rollback view, kept in
 * lockstep under the 100% surface-parity standing rule: same external-only
 * {@code fe-*} store, same {@code world|playerdata|both} scope, same
 * stopped-only safety gate, same {@code fe-rollback.sh} script (which takes a
 * pre-rollback safety snapshot before swapping anything).
 *
 * <p>Listing and the rollback run share the single-slot {@link BackupOps} job
 * model with {@link BackupsPane}, so only one backup/rollback runs at a time
 * across both tabs and the determinate progress bar here reflects the same
 * {@code FE_PROGRESS} stream the script emits.
 *
 * <p>Rollback is refused while the server is running. The pane is gated on a
 * {@link BooleanSupplier} fed from the launcher state ({@code STOPPED} or
 * {@code CRASHED}); the script re-checks via RCON as defence in depth.
 */
public final class RollbackPane {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-RollbackPane");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label metaLabel = new Label("External drive: --");
    private final Label serverStateLabel = new Label("");
    private final ComboBox<String> scopeBox = new ComboBox<>();
    private final Button refreshBtn = new Button("Refresh");
    private final Button rollbackBtn = new Button("Roll back selected...");
    private final Label jobLabel = new Label("Idle.");
    private final ProgressBar jobProgress = new ProgressBar(0);
    private final Label jobPercentLabel = new Label("");
    private final TextArea jobLog = new TextArea();
    private final VBox root = new VBox(8);

    private BackupOps ops;

    /** True when the server is stopped/crashed and rollback is permitted. */
    private BooleanSupplier serverStopped = () -> false;

    private javafx.animation.AnimationTimer jobPoller;

    public RollbackPane() {
        col("Created", r -> r.created);
        col("ID", r -> r.id);
        col("Label", r -> r.label);
        col("World", r -> r.hasWorld);
        col("Players", r -> r.hasPlayers);
        col("Size", r -> r.size);

        scopeBox.getItems().addAll("both", "world", "playerdata");
        scopeBox.getSelectionModel().select("both");

        metaLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 2 0;");
        serverStateLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 2 0;");
        jobLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 2 0;");
        jobProgress.setMaxWidth(Double.MAX_VALUE);
        jobProgress.setVisible(false);
        jobProgress.setManaged(false);
        jobPercentLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-font-family: 'Consolas', monospace;");
        jobLog.setEditable(false);
        jobLog.setPrefRowCount(5);
        jobLog.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        refreshBtn.setOnAction(e -> refresh());
        rollbackBtn.setOnAction(e -> rollbackSelected());

        HBox bar = new HBox(8,
                new Label("Scope:"), scopeBox, rollbackBtn, refreshBtn);
        bar.setPadding(new Insets(4, 0, 4, 0));

        HBox progressRow = new HBox(8, jobProgress, jobPercentLabel);
        progressRow.setPadding(new Insets(2, 0, 2, 0));
        HBox.setHgrow(jobProgress, Priority.ALWAYS);

        Label warn = new Label("Rollback restores a backup over the live save and is only "
                + "allowed while the server is STOPPED. A pre-rollback safety snapshot is taken automatically.");
        warn.setWrapText(true);
        warn.setStyle("-fx-text-fill: #e0c060; -fx-padding: 2 0;");

        root.setPadding(new Insets(8));
        root.getChildren().addAll(
                new Label("Rollback (restore from external drive)"),
                metaLabel,
                serverStateLabel,
                warn,
                bar,
                table,
                new Label("Last job"),
                jobLabel,
                progressRow,
                jobLog);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    public VBox root() { return root; }

    /** Bind to the same backup engine instance used by the Backups tab. */
    public void bind(AppConfig cfg, BackupOps sharedOps) {
        this.ops = sharedOps;
        startJobPoller();
        refresh();
    }

    /** Provide the stopped-server gate (launcher STOPPED/CRASHED). */
    public void setServerStoppedSupplier(BooleanSupplier supplier) {
        this.serverStopped = supplier == null ? () -> false : supplier;
    }

    // ---- listing -------------------------------------------------------

    public void refresh() {
        if (ops == null) return;
        new Thread(() -> {
            JsonNode res = ops.list();
            List<Row> newRows = new ArrayList<>();
            JsonNode arr = res.path("backups");
            if (arr.isArray()) {
                for (JsonNode b : arr) {
                    // Only structured fe-* backups support scoped rollback.
                    if (!"structured".equals(b.path("kind").asText(""))) continue;
                    Row r = new Row();
                    r.id = b.path("id").asText("");
                    r.label = b.path("label").asText("");
                    r.hasWorld = b.path("hasWorld").asBoolean(false) ? "yes" : "";
                    r.hasPlayers = b.path("hasPlayerData").asBoolean(false) ? "yes" : "";
                    r.size = human(b.path("totalBytes").asLong(0));
                    long epoch = b.path("createdEpoch").asLong(0);
                    r.created = epoch > 0 ? STAMP.format(Instant.ofEpochSecond(epoch)) : "";
                    newRows.add(r);
                }
            }
            JsonNode meta = res.path("meta");
            boolean mounted = meta.path("mounted").asBoolean(false);
            long free = meta.path("freeBytes").asLong(-1);
            String dir = meta.path("externalDir").asText("");
            String metaText = "External drive: " + dir
                    + (mounted ? "  (mounted" : "  (NOT MOUNTED")
                    + (free >= 0 ? ", " + human(free) + " free)" : ")");
            Platform.runLater(() -> {
                rows.setAll(newRows);
                metaLabel.setText(metaText);
                metaLabel.setStyle(mounted
                        ? "-fx-text-fill: #cfcfcf; -fx-padding: 2 0;"
                        : "-fx-text-fill: #e07060; -fx-padding: 2 0;");
                refreshGate();
            });
        }, "FatherEye-RollbackRefresh").start();
    }

    /** Reflect the stopped-server gate in the button + label. */
    private void refreshGate() {
        boolean stopped = serverStopped.getAsBoolean();
        boolean jobRunning = ops != null && ops.isJobRunning();
        rollbackBtn.setDisable(!stopped || jobRunning);
        if (jobRunning) {
            serverStateLabel.setText("A backup/rollback job is running...");
            serverStateLabel.setStyle("-fx-text-fill: #e0c060; -fx-padding: 2 0;");
        } else if (stopped) {
            serverStateLabel.setText("Server is STOPPED - rollback available.");
            serverStateLabel.setStyle("-fx-text-fill: #7ecf6f; -fx-padding: 2 0;");
        } else {
            serverStateLabel.setText("Server is RUNNING - stop it to roll back.");
            serverStateLabel.setStyle("-fx-text-fill: #e07060; -fx-padding: 2 0;");
        }
    }

    // ---- rollback ------------------------------------------------------

    private void rollbackSelected() {
        if (ops == null) return;
        if (!serverStopped.getAsBoolean()) {
            info("The server must be STOPPED before rolling back. Stop it, then retry.");
            return;
        }
        if (ops.isJobRunning()) {
            info("A backup or rollback is already running.");
            return;
        }
        Row sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { info("Select a backup to roll back to."); return; }
        String scope = scopeBox.getSelectionModel().getSelectedItem();
        if (scope == null) scope = "both";

        String human = scope.equals("both") ? "world + player data"
                : scope.equals("world") ? "world (terrain)" : "player data";
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "Roll back " + human + " to backup " + sel.id + "?\n\n"
                        + "This OVERWRITES the current save on disk. The script takes a "
                        + "pre-rollback safety snapshot first, but this cannot be undone "
                        + "from the UI. The server must stay stopped during the rollback.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Confirm rollback");
        confirm.setHeaderText(null);
        final String fScope = scope;
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            boolean started = ops.startRollback(sel.id, fScope);
            if (!started) {
                info("Could not start rollback (a job may be running or inputs invalid).");
            }
            refreshGate();
        });
    }

    // ---- job polling ---------------------------------------------------

    private void startJobPoller() {
        if (jobPoller != null) return;
        jobPoller = new javafx.animation.AnimationTimer() {
            long lastTick = 0;
            long lastUpdated = -1;
            @Override public void handle(long nowNanos) {
                if (nowNanos - lastTick < 500_000_000L) return; // 2 Hz
                lastTick = nowNanos;
                if (ops == null) return;
                refreshGate();
                JsonNode j = ops.jobStatus();
                long updated = j.path("updatedMs").asLong(0);
                boolean running = j.path("running").asBoolean(false);
                if (updated != lastUpdated) {
                    lastUpdated = updated;
                    String type = j.path("type").asText("");
                    setJob(j.path("message").asText(""), j.path("log").asText(""),
                            j.path("percent").asInt(0), running, type);
                    // When a rollback job finishes, refresh the listing
                    // (a pre-rollback snapshot may have been created).
                    if (!running && "rollback".equals(type)) refresh();
                }
            }
        };
        jobPoller.start();
    }

    private void setJob(String message, String log, int percent, boolean running, String type) {
        // Only react to rollback jobs here; a backup started from the
        // Backups tab still flows through the shared job model, but the
        // rollback bar should only animate for rollbacks.
        boolean isRollback = "rollback".equals(type);
        jobLabel.setText(message == null ? "" : message);
        jobLog.setText(log == null ? "" : log);
        jobLog.positionCaret(jobLog.getText().length());

        boolean showBar = running && isRollback && percent >= 0;
        jobProgress.setVisible(showBar);
        jobProgress.setManaged(showBar);
        if (showBar) {
            jobProgress.setProgress(Math.max(0, Math.min(100, percent)) / 100.0);
            jobPercentLabel.setText(percent + "%");
        } else if (isRollback && !running && percent >= 0) {
            jobPercentLabel.setText(percent + "%");
        } else {
            jobPercentLabel.setText("");
        }
    }

    // ---- helpers -------------------------------------------------------

    private void col(String name, java.util.function.Function<Row, String> getter) {
        TableColumn<Row, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    private static String human(long bytes) {
        if (bytes <= 0) return "--";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    public static final class Row {
        public String id = "", label = "", created = "",
                hasWorld = "", hasPlayers = "", size = "";
    }
}
