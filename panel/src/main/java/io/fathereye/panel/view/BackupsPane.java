package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.panel.ipc.PipeClient;
import io.fathereye.panel.launcher.BackupOps;
import io.fathereye.panel.launcher.PanelPlayerRestore;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel Backups tab. Desktop twin of the web portal's Backups view, kept in
 * lockstep under the 100% surface-parity standing rule: same external-only
 * compressed store, same {@code fe-*} listing, same manual-backup trigger,
 * same per-player live-restore action.
 *
 * <p>Listing, manual backup, and player-archive reads run through
 * {@link BackupOps} / {@link PanelPlayerRestore}. The live player restore
 * forwards the extracted {@code .dat} to the bridge op
 * {@code player_restoreState}, exactly as the web portal does, and is only
 * offered while the bridge (server) is connected.
 */
public final class BackupsPane {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-BackupsPane");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label metaLabel = new Label("External drive: --");
    private final Label jobLabel = new Label("Idle.");
    private final ProgressBar jobProgress = new ProgressBar(0);
    private final Label jobPercentLabel = new Label("");
    private final TextArea jobLog = new TextArea();
    private final Button refreshBtn = new Button("Refresh");
    private final Button backupNowBtn = new Button("Backup now...");
    private final Button restorePlayerBtn = new Button("Restore player from backup...");
    private final VBox root = new VBox(8);

    private BackupOps ops;
    private PanelPlayerRestore playerRestore;
    private AppConfig config;

    /** Supplies the live bridge client, or null when the server is stopped. */
    private java.util.function.Supplier<PipeClient> pipeSupplier = () -> null;

    private javafx.animation.AnimationTimer jobPoller;

    public BackupsPane() {
        col("Created", r -> r.created);
        col("ID", r -> r.id);
        col("Label", r -> r.label);
        col("Kind", r -> r.kind);
        col("World", r -> r.hasWorld);
        col("Players", r -> r.hasPlayers);
        col("Server", r -> r.hasServer);
        col("Size", r -> r.size);

        metaLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 2 0;");
        jobLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 2 0;");
        jobProgress.setMaxWidth(Double.MAX_VALUE);
        jobProgress.setVisible(false);
        jobProgress.setManaged(false);
        jobPercentLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-font-family: 'Consolas', monospace;");
        jobLog.setEditable(false);
        jobLog.setPrefRowCount(5);
        jobLog.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        refreshBtn.setOnAction(e -> refresh());
        backupNowBtn.setOnAction(e -> backupNow());
        restorePlayerBtn.setOnAction(e -> restorePlayer());

        HBox bar = new HBox(8, backupNowBtn, restorePlayerBtn, refreshBtn);
        bar.setPadding(new Insets(4, 0, 4, 0));

        HBox progressRow = new HBox(8, jobProgress, jobPercentLabel);
        progressRow.setPadding(new Insets(2, 0, 2, 0));
        HBox.setHgrow(jobProgress, Priority.ALWAYS);

        root.setPadding(new Insets(8));
        root.getChildren().addAll(
                new Label("Backups (external drive)"),
                metaLabel,
                bar,
                table,
                new Label("Last job"),
                jobLabel,
                progressRow,
                jobLog);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    public VBox root() { return root; }

    /** Bind backup services once config is loaded (called from App). */
    public void bind(AppConfig cfg) {
        this.config = cfg;
        this.ops = new BackupOps(cfg);
        this.playerRestore = new PanelPlayerRestore(cfg);
        startJobPoller();
        refresh();
    }

    /** Provide the live-bridge accessor used to gate player restore. */
    public void setPipeSupplier(java.util.function.Supplier<PipeClient> supplier) {
        this.pipeSupplier = supplier == null ? () -> null : supplier;
    }

    /**
     * The shared backup engine, so the Rollback tab drives the SAME
     * single-slot job model (only one backup/rollback at a time across both
     * tabs). Null until {@link #bind(AppConfig)} has run.
     */
    public BackupOps ops() { return ops; }

    // ---- listing -------------------------------------------------------

    public void refresh() {
        if (ops == null) return;
        new Thread(() -> {
            JsonNode res = ops.list();
            List<Row> newRows = new ArrayList<>();
            JsonNode arr = res.path("backups");
            if (arr.isArray()) {
                for (JsonNode b : arr) {
                    Row r = new Row();
                    r.id = b.path("id").asText("");
                    r.kind = b.path("kind").asText("");
                    r.label = b.path("label").asText("");
                    r.hasWorld = b.path("hasWorld").asBoolean(false) ? "yes" : "";
                    r.hasPlayers = b.path("hasPlayerData").asBoolean(false) ? "yes" : "";
                    r.hasServer = b.path("hasServer").asBoolean(false) ? "yes" : "";
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
            });
        }, "FatherEye-BackupsRefresh").start();
    }

    // ---- manual backup -------------------------------------------------

    private void backupNow() {
        if (ops == null) return;
        if (ops.isJobRunning()) {
            info("A backup or rollback is already running.");
            return;
        }
        TextInputDialog dlg = new TextInputDialog("");
        dlg.setTitle("Backup now");
        dlg.setHeaderText("Create a compressed backup on the external drive.");
        dlg.setContentText("Optional label:");
        dlg.showAndWait().ifPresent(label -> {
            boolean started = ops.startBackup(label == null ? "" : label.trim());
            if (!started) info("Could not start backup (a job may already be running).");
        });
    }

    // ---- live player restore ------------------------------------------

    private void restorePlayer() {
        if (ops == null || playerRestore == null) return;
        Row sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { info("Select a backup first."); return; }
        if (!PanelPlayerRestore.isValidBackupId(sel.id)) {
            info("Player restore is only available for structured fe-* backups.");
            return;
        }
        PipeClient client = pipeSupplier.get();
        if (client == null || client.isClosed()) {
            info("Player restore needs the server running. Start the server, then retry.");
            return;
        }

        new Thread(() -> {
            JsonNode listing = playerRestore.listPlayers(sel.id);
            if (!listing.path("ok").asBoolean(false)) {
                Platform.runLater(() -> info("Cannot read players from this backup: "
                        + listing.path("error").asText("unknown error")));
                return;
            }
            List<String> labels = new ArrayList<>();
            Map<String, String> labelToUuid = new LinkedHashMap<>();
            for (JsonNode p : listing.path("players")) {
                String uuid = p.path("uuid").asText("");
                String name = p.path("name").isNull() ? null : p.path("name").asText(null);
                String label = (name != null ? name : "(unknown)") + "  " + uuid;
                labels.add(label);
                labelToUuid.put(label, uuid);
            }
            Platform.runLater(() -> {
                if (labels.isEmpty()) { info("This backup contains no player data."); return; }
                ChoiceDialog<String> picker = new ChoiceDialog<>(labels.get(0), labels);
                picker.setTitle("Restore player");
                picker.setHeaderText("Restore a player's saved state from " + sel.id
                        + "\ninto the RUNNING server. A pre-restore safety snapshot is taken by the bridge.");
                picker.setContentText("Player:");
                picker.showAndWait().ifPresent(choice -> {
                    String uuid = labelToUuid.get(choice);
                    if (uuid != null) confirmAndRestore(sel.id, uuid, choice);
                });
            });
        }, "FatherEye-BackupPlayers").start();
    }

    private void confirmAndRestore(String backupId, String uuid, String label) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Restore " + label + " from " + backupId + "?\n\n"
                        + "This overwrites the player's current inventory, position, stats and "
                        + "advancements on the live server. The bridge takes a safety snapshot first.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Confirm player restore");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            doRestore(backupId, uuid, label);
        });
    }

    private void doRestore(String backupId, String uuid, String label) {
        PipeClient client = pipeSupplier.get();
        if (client == null || client.isClosed()) {
            info("Server is no longer connected; restore aborted.");
            return;
        }
        setJob("Restoring " + label + "...", "");
        new Thread(() -> {
            byte[] dat = playerRestore.extractPlayerDat(backupId, uuid);
            if (dat == null) {
                Platform.runLater(() -> {
                    info("Player not found in this backup.");
                    setJob("Player restore failed: not found in backup.", "");
                });
                return;
            }
            try {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("playerUuid", uuid);
                args.put("nbtBase64", java.util.Base64.getEncoder().encodeToString(dat));
                args.put("serverDir", playerRestore.serverDirPublic().toString());
                JsonNode result = client.sendRequest("player_restoreState", args)
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
                Platform.runLater(() -> {
                    setJob("Player restore complete: " + label, result.toString());
                    info("Restored " + label + " from " + backupId + ".");
                });
            } catch (java.util.concurrent.TimeoutException te) {
                Platform.runLater(() -> {
                    setJob("Player restore timed out.", "");
                    info("Bridge timed out restoring the player.");
                });
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                LOG.warn("player restore failed: {}", cause.toString());
                Platform.runLater(() -> {
                    setJob("Player restore failed: " + cause.getMessage(), "");
                    info("Restore failed: " + cause.getMessage());
                });
            }
        }, "FatherEye-PlayerRestore").start();
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
                JsonNode j = ops.jobStatus();
                long updated = j.path("updatedMs").asLong(0);
                boolean running = j.path("running").asBoolean(false);
                if (updated != lastUpdated) {
                    lastUpdated = updated;
                    setJob(j.path("message").asText(""), j.path("log").asText(""),
                            j.path("percent").asInt(0), running, j.path("type").asText(""));
                    // When a backup job finishes, refresh the listing.
                    if (!running && "backup".equals(j.path("type").asText(""))) {
                        refresh();
                    }
                }
            }
        };
        jobPoller.start();
    }

    private void setJob(String message, String log) {
        // Player-restore path: no determinate byte progress, so leave the
        // bar hidden and just surface the message + log.
        setJob(message, log, -1, false, "");
    }

    private void setJob(String message, String log, int percent, boolean running, String type) {
        jobLabel.setText(message == null ? "" : message);
        jobLog.setText(log == null ? "" : log);
        jobLog.positionCaret(jobLog.getText().length());

        boolean showBar = running && ("backup".equals(type) || "rollback".equals(type)) && percent >= 0;
        jobProgress.setVisible(showBar);
        jobProgress.setManaged(showBar);
        if (showBar) {
            jobProgress.setProgress(Math.max(0, Math.min(100, percent)) / 100.0);
            jobPercentLabel.setText(percent + "%");
        } else if (percent >= 0 && !running && ("backup".equals(type) || "rollback".equals(type))) {
            // Finished: briefly show the final percentage text next to the
            // (now-hidden) bar so the operator sees it completed at 100%.
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
        public String id = "", kind = "", label = "", created = "",
                hasWorld = "", hasPlayers = "", hasServer = "", size = "";
    }
}
