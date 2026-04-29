package io.fathereye.panel.view;

import io.fathereye.panel.config.AppConfig;
import io.fathereye.panel.launcher.ServerProperties;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pnl-42 (2026-04-26): pre-boot configuration modal.
 *
 * <p>User request: "i want to add a pop up that comes up so you can set
 * server ram usages and special arguments, server properties, etc.
 * before server boot". This dialog gathers everything the operator
 * typically wants to tune in one place so they do not have to hand-edit
 * {@code config.json} or {@code server.properties} on disk.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>JVM &amp; Memory</b> tab: dedicated fields for Xmx / Xms /
 *       common G1GC flags, plus a free-form "Other JVM args" textarea
 *       for everything we don't have a checkbox for.</li>
 *   <li><b>server.properties</b> tab: a {@link TableView} with one row
 *       per key, edit-in-place. Common keys (max-players, motd, ...)
 *       are surfaced first; everything else follows in file order.
 *       Comments and ordering in the on-disk file are preserved by
 *       {@link ServerProperties}.</li>
 * </ul>
 *
 * <p>Footer: Cancel / Save / Save &amp; Start. The first two leave the
 * launcher state untouched; the third calls a callback the panel uses
 * to spin up the server with the freshly-saved spec.
 */
public final class PreBootDialog {

    /** Result of an OK / Save & Start press; null if cancelled. */
    public static final class Result {
        public final String jvmArgs;
        public final Map<String, String> serverProperties;
        public final boolean startAfterSave;
        /** Pnl-43 (2026-04-26): user's preference for whether the
         *  panel should auto-show this dialog on every launch. The
         *  caller persists this into AppConfig.serverRuntime
         *  alongside jvmArgs. */
        public final boolean showOnNextStart;
        public Result(String jvmArgs, Map<String, String> serverProperties,
                      boolean startAfterSave, boolean showOnNextStart) {
            this.jvmArgs = jvmArgs;
            this.serverProperties = serverProperties;
            this.startAfterSave = startAfterSave;
            this.showOnNextStart = showOnNextStart;
        }
    }

    /** Common keys we surface at the top of the server.properties table.
     *  Anything else in the file follows underneath in file order. */
    private static final List<String> COMMON_KEYS = Arrays.asList(
            "motd",
            "max-players",
            "view-distance",
            "simulation-distance",
            "online-mode",
            "white-list",
            "pvp",
            "difficulty",
            "gamemode",
            "hardcore",
            "allow-flight",
            "allow-nether",
            "level-name",
            "level-seed",
            "level-type",
            "server-port",
            "server-ip",
            "spawn-protection"
    );

    private static final Pattern XMX_PAT = Pattern.compile("(?i)-Xmx([0-9]+)([gGmMkK])?");
    private static final Pattern XMS_PAT = Pattern.compile("(?i)-Xms([0-9]+)([gGmMkK])?");
    /** Pnl-42 (audit fix #3): validate the user's typed Xmx/Xms. A
     *  digit run with an optional G/M/K suffix is what the JVM accepts;
     *  anything else surfaces a dialog and aborts the Save. */
    private static final Pattern HEAP_SIZE_PAT = Pattern.compile("[0-9]+[gGmMkK]?");
    private static final Pattern POSITIVE_INT_PAT = Pattern.compile("[0-9]+");

    private final Dialog<Result> dialog = new Dialog<>();
    private final TextField xmxField = new TextField();
    private final TextField xmsField = new TextField();
    /** Pnl-43: user-toggled "auto-show this dialog before each
     *  auto-start" preference. Default tracks
     *  AppConfig.serverRuntime.showPreBootDialogOnStart at
     *  construction time. */
    private final CheckBox showOnStartChk = new CheckBox(
            "Show this dialog automatically before every server start");
    private final CheckBox useG1Chk = new CheckBox("Use G1GC (-XX:+UseG1GC)");
    private final CheckBox parallelRefChk = new CheckBox("Parallel ref proc (-XX:+ParallelRefProcEnabled)");
    private final CheckBox heapDumpOomChk = new CheckBox("Heap dump on OOM (-XX:+HeapDumpOnOutOfMemoryError)");
    private final CheckBox noVerifyChk = new CheckBox("Disable bytecode verification (-noverify)");
    private final TextField gcPauseField = new TextField();
    private final TextArea otherArgsArea = new TextArea();
    private final TableView<PropRow> propsTable = new TableView<>();
    private final ObservableList<PropRow> propRows = FXCollections.observableArrayList();
    private final ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    /** Pnl-46: ButtonType label is rendered verbatim by the JavaFX
     *  Dialog skin -- there is NO Win32/Swing-style "&&" escape for a
     *  literal ampersand here. The previous "Save && Start" displayed
     *  exactly that. Use a single ampersand.
     *  Pnl-51: label is "Stop existing & Start" when externalBridgeDetected,
     *  to communicate that the click will replace an orphan server. */
    private ButtonType saveAndStartType = new ButtonType("Save & Start", ButtonBar.ButtonData.APPLY);

    private final Path serverPropertiesPath;
    private ServerProperties propertiesOnDisk;
    /** Pnl-42 (audit fix): when the on-disk file is unreadable we show
     *  this warning in the properties tab instead of silently routing
     *  saves to a tmpdir. */
    private String propertiesLoadError;

    /**
     * @param appConfig             the live config; this dialog reads
     *                              jvmArgs but does NOT mutate the config
     *                              directly. The caller applies the
     *                              {@link Result} after OK.
     * @param workingDir            server folder (so we can find
     *                              {@code server.properties}).
     * @param serverIsRunning       true iff a server is currently
     *                              running and the user cannot start
     *                              another one. With Pnl-51 this is
     *                              ONLY true when the panel itself
     *                              owns the running server (launcher
     *                              state RUNNING). For an external
     *                              server (orphan or manually-started),
     *                              pass false here and pass true to
     *                              {@code externalBridgeDetected}; the
     *                              Save & Start button stays enabled
     *                              and is relabelled "Stop existing
     *                              & Start" so the user can replace
     *                              the orphan with a fresh launch.
     * @param externalBridgeDetected true iff a server is running that
     *                              this panel did not start. Save &
     *                              Start label changes; on click the
     *                              caller MUST kill the external
     *                              server before launcher.start.
     */
    public PreBootDialog(AppConfig appConfig, Path workingDir,
                         boolean serverIsRunning, boolean externalBridgeDetected) {
        this.serverPropertiesPath = workingDir.resolve("server.properties");
        // Pnl-43: seed the "auto-show on start" toggle from current
        // config so the dialog reflects whatever the user previously
        // chose (or the default true on first install).
        this.showOnStartChk.setSelected(appConfig.serverRuntime.showPreBootDialogOnStart);
        try {
            this.propertiesOnDisk = ServerProperties.load(serverPropertiesPath);
        } catch (IOException ioe) {
            // Pnl-42 (audit fix): the previous fallback re-loaded from a
            // tmpdir path so saves silently went to %TEMP%, which the
            // user could not see. Fail loud instead: leave
            // propertiesOnDisk null, render a warning label in the
            // properties tab, and refuse to write back to the wrong
            // file. The JVM tab still works.
            this.propertiesOnDisk = null;
            this.propertiesLoadError = "Could not read " + serverPropertiesPath
                    + ": " + ioe.getMessage();
        }

        // Pnl-51: relabel Save & Start when the click will need to
        // stop an orphan first.
        if (externalBridgeDetected && !serverIsRunning) {
            saveAndStartType = new ButtonType("Stop existing & Start", ButtonBar.ButtonData.APPLY);
        }
        dialog.setTitle("Server Pre-Boot Configuration");
        dialog.setHeaderText(buildHeader(workingDir, serverIsRunning, externalBridgeDetected));
        dialog.setResizable(true);

        TabPane tabs = new TabPane();
        tabs.getTabs().add(buildJvmTab(appConfig));
        tabs.getTabs().add(buildPropertiesTab());
        // Disable closing tabs.
        for (Tab t : tabs.getTabs()) t.setClosable(false);

        // Pnl-43: footer toggle for "show before each start". Sits
        // above the button row so it is visible regardless of which
        // tab is active.
        showOnStartChk.setStyle("-fx-padding: 4 0 0 4;");
        VBox content = new VBox(8, tabs, showOnStartChk);
        content.setPadding(new Insets(10));
        content.setPrefSize(720, 540);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);

        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, saveType, saveAndStartType);
        // Disable Save & Start if the server is already running so the
        // user can't pretend to "apply" runtime changes that JVM args
        // require a relaunch for.
        Node startBtn = dialog.getDialogPane().lookupButton(saveAndStartType);
        if (startBtn instanceof Button) {
            startBtn.setDisable(serverIsRunning);
            if (serverIsRunning) {
                ((Button) startBtn).setTooltip(new javafx.scene.control.Tooltip(
                        "Server is already running. Stop it first."));
            }
        }

        // Pnl-42 (audit fix #7): force any in-flight TableView edit to
        // commit before the result converter runs. Without this, a user
        // who types a value and clicks Save without pressing Enter
        // first would silently lose the edit. addEventFilter on the
        // ACTION event runs before the dialog's own button handler, so
        // we get the commit in before the result converter snapshots
        // the rows.
        // Pnl-42 (audit fix #8): validate Xmx/Xms/gcPause format on
        // Save. An invalid value would otherwise produce
        // "-Xmxgarbage" and the JVM would refuse to start with a
        // cryptic message buried in the console.
        Node saveBtnNode = dialog.getDialogPane().lookupButton(saveType);
        if (saveBtnNode instanceof Button) {
            ((Button) saveBtnNode).addEventFilter(ActionEvent.ACTION, this::onSaveClicked);
        }
        if (startBtn instanceof Button) {
            ((Button) startBtn).addEventFilter(ActionEvent.ACTION, this::onSaveClicked);
        }

        dialog.setResultConverter(buttonType -> {
            if (buttonType == saveType || buttonType == saveAndStartType) {
                return new Result(
                        buildJvmArgs(),
                        collectPropertyRows(),
                        buttonType == saveAndStartType,
                        showOnStartChk.isSelected());
            }
            return null;
        });
    }

    /** Show modal, block, return the user's choice (or null on Cancel). */
    public java.util.Optional<Result> showAndWait() {
        return dialog.showAndWait();
    }

    /**
     * Pnl-42 (audit fix): runs on the ACTION event of Save and Save &
     * Start before the dialog's own handler. Forces any pending
     * TableView edit to commit, then validates the JVM size fields.
     * Consumes the event (preventing dialog close) if validation
     * fails so the user can fix the input.
     */
    private void onSaveClicked(ActionEvent ev) {
        // Force commit of any in-flight cell edit. Calling edit(-1, null)
        // on an editable TableView cancels active edit mode; the JavaFX
        // skin commits the current value before exiting edit mode.
        propsTable.edit(-1, null);
        String xmx = xmxField.getText() == null ? "" : xmxField.getText().trim();
        String xms = xmsField.getText() == null ? "" : xmsField.getText().trim();
        String gc = gcPauseField.getText() == null ? "" : gcPauseField.getText().trim();
        if (!xmx.isEmpty() && !HEAP_SIZE_PAT.matcher(xmx).matches()) {
            failValidation(ev, "Max heap (-Xmx)", xmx, "Examples: 8G, 4096M, 1024K");
            return;
        }
        if (!xms.isEmpty() && !HEAP_SIZE_PAT.matcher(xms).matches()) {
            failValidation(ev, "Initial heap (-Xms)", xms, "Examples: 4G, 2048M");
            return;
        }
        if (!gc.isEmpty() && !POSITIVE_INT_PAT.matcher(gc).matches()) {
            failValidation(ev, "Max GC pause (ms)", gc, "Must be a positive integer (e.g. 100), or empty for no -XX:MaxGCPauseMillis");
            return;
        }
    }

    private void failValidation(ActionEvent ev, String fieldName, String badValue, String hint) {
        ev.consume();
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle("Invalid value");
        a.setHeaderText("Bad input in '" + fieldName + "'");
        a.setContentText(fieldName + " = '" + badValue + "' is not a valid JVM size or value.\n"
                + hint);
        a.initOwner(dialog.getDialogPane().getScene().getWindow());
        a.showAndWait();
    }

    /**
     * After {@link #showAndWait()} returns OK, persist the
     * server.properties side by writing the (possibly mutated)
     * {@link ServerProperties} loaded in the constructor back to disk.
     * Caller passes the same map returned in {@link Result}.
     */
    public void saveServerProperties(Map<String, String> values) throws IOException {
        if (propertiesOnDisk == null) return;
        for (Map.Entry<String, String> e : values.entrySet()) {
            propertiesOnDisk.set(e.getKey(), e.getValue());
        }
        propertiesOnDisk.save();
    }

    private Tab buildJvmTab(AppConfig appConfig) {
        // Parse current Xmx / Xms and individual flags out of the
        // existing jvmArgs string. Anything we don't recognise lands
        // in the "Other args" textarea so the user can keep tuning
        // through a single field.
        String existing = appConfig.serverRuntime.jvmArgs == null ? "" : appConfig.serverRuntime.jvmArgs;
        List<String> tokens = new ArrayList<>();
        if (!existing.trim().isEmpty()) tokens.addAll(Arrays.asList(existing.trim().split("\\s+")));

        String xmx = "8G", xms = "4G";
        boolean useG1 = false, parallelRef = false, heapDumpOom = false, noVerify = false;
        String gcPause = "";
        List<String> other = new ArrayList<>();
        for (String tok : tokens) {
            Matcher xmxMatch = XMX_PAT.matcher(tok);
            Matcher xmsMatch = XMS_PAT.matcher(tok);
            if (xmxMatch.matches()) {
                xmx = xmxMatch.group(1) + (xmxMatch.group(2) == null ? "" : xmxMatch.group(2).toUpperCase());
            } else if (xmsMatch.matches()) {
                xms = xmsMatch.group(1) + (xmsMatch.group(2) == null ? "" : xmsMatch.group(2).toUpperCase());
            } else if (tok.equalsIgnoreCase("-XX:+UseG1GC")) {
                useG1 = true;
            } else if (tok.equalsIgnoreCase("-XX:+ParallelRefProcEnabled")) {
                parallelRef = true;
            } else if (tok.equalsIgnoreCase("-XX:+HeapDumpOnOutOfMemoryError")) {
                heapDumpOom = true;
            } else if (tok.equalsIgnoreCase("-noverify")) {
                noVerify = true;
            } else if (tok.toLowerCase().startsWith("-xx:maxgcpausemillis=")) {
                gcPause = tok.substring(tok.indexOf('=') + 1);
            } else {
                other.add(tok);
            }
        }

        xmxField.setText(xmx);
        xmsField.setText(xms);
        xmxField.setPromptText("e.g. 8G");
        xmsField.setPromptText("e.g. 4G");
        xmxField.setPrefColumnCount(8);
        xmsField.setPrefColumnCount(8);
        useG1Chk.setSelected(useG1);
        parallelRefChk.setSelected(parallelRef);
        heapDumpOomChk.setSelected(heapDumpOom);
        noVerifyChk.setSelected(noVerify);
        gcPauseField.setText(gcPause);
        gcPauseField.setPromptText("ms (e.g. 100)");
        gcPauseField.setPrefColumnCount(8);
        otherArgsArea.setText(String.join(" ", other));
        otherArgsArea.setPromptText("Space-separated extra JVM args, e.g. -XX:+AlwaysPreTouch -Dlog4j.formatMsgNoLookups=true");
        otherArgsArea.setPrefRowCount(4);
        otherArgsArea.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(new Label("Max heap (-Xmx):"), 0, row);
        grid.add(xmxField, 1, row);
        grid.add(new Label("e.g. 8G, 4096M"), 2, row);
        row++;
        grid.add(new Label("Initial heap (-Xms):"), 0, row);
        grid.add(xmsField, 1, row);
        grid.add(new Label("e.g. 4G"), 2, row);
        row++;
        grid.add(useG1Chk, 0, row, 3, 1); row++;
        grid.add(parallelRefChk, 0, row, 3, 1); row++;
        grid.add(heapDumpOomChk, 0, row, 3, 1); row++;
        grid.add(noVerifyChk, 0, row, 3, 1); row++;
        grid.add(new Label("Max GC pause (ms):"), 0, row);
        grid.add(gcPauseField, 1, row);
        grid.add(new Label("Empty = no -XX:MaxGCPauseMillis"), 2, row);
        row++;
        grid.add(new Label("Other JVM args:"), 0, row, 3, 1); row++;
        grid.add(otherArgsArea, 0, row, 3, 1);
        GridPane.setHgrow(otherArgsArea, Priority.ALWAYS);
        GridPane.setVgrow(otherArgsArea, Priority.ALWAYS);

        Tab t = new Tab("JVM & Memory", grid);
        return t;
    }

    private Tab buildPropertiesTab() {
        // Build rows: common keys first (in COMMON_KEYS order), then
        // remaining file-order keys, then a hint that empty new keys
        // can be added (omitted for now to keep the UI simple).
        Map<String, String> onDisk = propertiesOnDisk == null
                ? new LinkedHashMap<>() : propertiesOnDisk.asOrderedMap();
        Set<String> seen = new LinkedHashSet<>();
        for (String k : COMMON_KEYS) {
            // Surface common keys even if not currently in the file
            // (e.g. fresh server folder without a server.properties).
            String v = onDisk.getOrDefault(k, "");
            propRows.add(new PropRow(k, v));
            seen.add(k);
        }
        for (Map.Entry<String, String> e : onDisk.entrySet()) {
            if (seen.add(e.getKey())) {
                propRows.add(new PropRow(e.getKey(), e.getValue()));
            }
        }

        propsTable.setItems(propRows);
        propsTable.setEditable(true);

        TableColumn<PropRow, String> kCol = new TableColumn<>("Key");
        kCol.setCellValueFactory(new PropertyValueFactory<>("key"));
        kCol.setPrefWidth(220);

        TableColumn<PropRow, String> vCol = new TableColumn<>("Value");
        vCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        // Use TextFieldTableCell for in-place editing.
        vCol.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        vCol.setOnEditCommit(ev -> {
            ev.getRowValue().setValue(ev.getNewValue());
        });
        vCol.setPrefWidth(420);
        vCol.setEditable(true);

        propsTable.getColumns().add(kCol);
        propsTable.getColumns().add(vCol);

        Label hint = new Label("Double-click any value to edit. Comments and ordering in server.properties are preserved on save.");
        hint.setStyle("-fx-text-fill: #888; -fx-padding: 4 8;");

        VBox box = new VBox(4);
        box.setPadding(new Insets(6));
        // Pnl-42 (audit fix): visible warning if the on-disk file
        // could not be loaded. Saves are NO-OPs while in this state
        // (saveServerProperties guards on propertiesOnDisk == null), so
        // the user is not silently writing to %TEMP%.
        if (propertiesLoadError != null) {
            Label warn = new Label("WARNING: " + propertiesLoadError
                    + "\nServer.properties saves are disabled until the file is readable.");
            warn.setWrapText(true);
            warn.setStyle("-fx-background-color: #7a2510; -fx-text-fill: #ffeed0;"
                    + " -fx-padding: 8 12; -fx-font-weight: bold;");
            box.getChildren().add(warn);
        }
        box.getChildren().addAll(hint, propsTable);
        VBox.setVgrow(propsTable, Priority.ALWAYS);

        Tab t = new Tab("server.properties", box);
        return t;
    }

    private String buildHeader(Path workingDir, boolean serverIsRunning, boolean externalBridgeDetected) {
        String state;
        if (serverIsRunning) {
            state = "  [server is RUNNING (panel-owned) -- Save & Start disabled]";
        } else if (externalBridgeDetected) {
            state = "  [external server detected -- 'Stop existing & Start' will kill it first]";
        } else {
            state = "";
        }
        return "Configure JVM and server.properties before launch.\n"
                + "Server folder: " + workingDir.toString() + state;
    }

    private String buildJvmArgs() {
        List<String> out = new ArrayList<>();
        String xmx = xmxField.getText().trim();
        String xms = xmsField.getText().trim();
        if (!xmx.isEmpty()) out.add("-Xmx" + xmx);
        if (!xms.isEmpty()) out.add("-Xms" + xms);
        if (useG1Chk.isSelected()) out.add("-XX:+UseG1GC");
        if (parallelRefChk.isSelected()) out.add("-XX:+ParallelRefProcEnabled");
        if (heapDumpOomChk.isSelected()) out.add("-XX:+HeapDumpOnOutOfMemoryError");
        if (noVerifyChk.isSelected()) out.add("-noverify");
        String gc = gcPauseField.getText().trim();
        if (!gc.isEmpty()) out.add("-XX:MaxGCPauseMillis=" + gc);
        String other = otherArgsArea.getText() == null ? "" : otherArgsArea.getText().trim();
        if (!other.isEmpty()) {
            for (String tok : other.split("\\s+")) {
                if (!tok.isEmpty()) out.add(tok);
            }
        }
        return String.join(" ", out);
    }

    private Map<String, String> collectPropertyRows() {
        Map<String, String> out = new LinkedHashMap<>();
        for (PropRow r : propRows) {
            String k = r.getKey();
            String v = r.getValue();
            // Don't write back rows that started empty AND are still
            // empty (avoids littering server.properties with blank
            // common keys when they weren't in the file before).
            if (v == null) v = "";
            // Always write known-existing keys; only write surfaced
            // common keys if the user actually filled them in.
            boolean exists = propertiesOnDisk != null && propertiesOnDisk.get(k) != null;
            if (exists || !v.isEmpty()) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** Row model for the server.properties TableView. */
    public static final class PropRow {
        private final SimpleStringProperty key;
        private final SimpleStringProperty value;
        public PropRow(String key, String value) {
            this.key = new SimpleStringProperty(key);
            this.value = new SimpleStringProperty(value);
        }
        public String getKey()   { return key.get(); }
        public String getValue() { return value.get(); }
        public void setValue(String v) { value.set(v); }
        public SimpleStringProperty keyProperty()   { return key; }
        public SimpleStringProperty valueProperty() { return value; }
    }
}
