package io.fathereye.panel.view;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;

/**
 * Skeleton main window. Tabs are wired in as their backing panes come online.
 *
 * <p>Pnl-40 (2026-04-26): the previous layout pinned ConsolePane and
 * ConfigPane into a horizontal SplitPane occupying the bottom 30% of
 * the window. The user reported "i want the log panel to be in its own
 * seperate tab from the map so that the log takes up more of the
 * window". Both Console and Config are now top-level tabs in the
 * single TabPane, and the bottom split is gone, so any selected tab
 * fills the entire main area.
 */
public final class MainWindow {

    private final BorderPane root = new BorderPane();
    private final Label statusLabel = new Label("Initialising…");
    private final TabPane mainTabs = new TabPane();

    private final StatsPane statsPane = new StatsPane();
    private final ConsolePane consolePane = new ConsolePane();
    private final PlayersPane playersPane = new PlayersPane();
    private final MobsPane mobsPane = new MobsPane();
    private final ModsPane modsPane = new ModsPane();
    private final MapPane mapPane = new MapPane();
    private final ConfigPane configPane = new ConfigPane();
    private final Button startBtn = new Button("Start");
    private final Button stopBtn = new Button("Stop");
    private final Button restartBtn = new Button("Restart");
    /** Pnl-42: pre-boot configuration dialog trigger. Opens a modal
     *  with RAM / JVM args / server.properties editor before launch. */
    private final Button configureBtn = new Button("Configure...");
    private final Label serverStateLabel = new Label("Server: STOPPED");
    /** Pnl-28: heartbeat-age badge fed by AnimationTimer. */
    private final Label heartbeatLabel = new Label("Heartbeat: --");
    private javafx.animation.AnimationTimer heartbeatTimer;
    private java.util.function.LongSupplier heartbeatAgeProvider = () -> -1L;
    /** Pnl-67 (2026-04-27): server-uptime badge. Displays how long
     *  the bridge / server has been up, computed from the marker's
     *  startedAtEpochMs. The user reported the heartbeat label
     *  "sitting at 0" which is correct (heartbeat just received)
     *  but didn't surface the uptime; this label answers "how
     *  long has the server been running". */
    private final Label uptimeLabel = new Label("Uptime: --");
    private javafx.animation.AnimationTimer uptimeTimer;
    private volatile long serverStartedAtEpochMs = -1L;
    /** Pnl-41: in-window alert banner. Replaces the previous modal
     *  {@link javafx.scene.control.Alert} dialog so a transient TPS
     *  dip during chunk-gen does not interrupt the operator with a
     *  click-to-dismiss pop-up. The banner overlays the top of the
     *  tab area (via a {@link javafx.scene.layout.StackPane} wrapper
     *  in {@link #buildBody()}), is hidden when there is no active
     *  alert, fades away after {@link #BANNER_AUTO_DISMISS_MS}
     *  milliseconds, and can be click-to-dismissed at any time.
     *  Overlaying instead of stacking above the tabs avoids the
     *  jarring layout shift the audit flagged. */
    private final Label alertBanner = new Label();
    private static final long BANNER_AUTO_DISMISS_MS = 12_000L;
    private final PauseTransition bannerHideTimer = new PauseTransition(Duration.millis(BANNER_AUTO_DISMISS_MS));

    public MainWindow() {
        root.setTop(buildToolbar());
        root.setCenter(buildBody());
        root.setBottom(buildStatusBar());

        // Pnl-40: Console and Config promoted to top-level tabs. The
        // bottom SplitPane is gone, so the active tab now fills the
        // entire window (modulo toolbar + status bar). The map remains
        // the default selection.
        mainTabs.getTabs().addAll(
                tab("Map", mapPane.root()),
                tab("Players", playersPane.root()),
                tab("Mobs", mobsPane.root()),
                tab("Mods", modsPane.root()),
                tab("Stats", statsPane.root()),
                tab("Console", consolePane.root()),
                tab("Config", configPane.root()),
                stubTab("Alerts (M15)")
        );
        mainTabs.getSelectionModel().select(0); // Map default
    }

    public StatsPane statsPane() { return statsPane; }
    public ConsolePane consolePane() { return consolePane; }
    public PlayersPane playersPane() { return playersPane; }
    public MobsPane mobsPane() { return mobsPane; }
    public ModsPane modsPane() { return modsPane; }
    public MapPane mapPane() { return mapPane; }
    public ConfigPane configPane() { return configPane; }
    public Button startBtn() { return startBtn; }
    public Button stopBtn() { return stopBtn; }
    public Button restartBtn() { return restartBtn; }
    public Button configureBtn() { return configureBtn; }
    public void setServerState(String s) { serverStateLabel.setText("Server: " + s); }

    /**
     * Pnl-67 (2026-04-27): set the wall-clock epoch ms when the
     * current bridge / server started. The toolbar shows
     * "Uptime: 1h 2m 3s" updated every second from this anchor.
     * Pass -1 to reset to "Uptime: --" (server stopped or not
     * yet connected).
     */
    public void setServerStartedAtEpochMs(long startedAtEpochMs) {
        this.serverStartedAtEpochMs = startedAtEpochMs;
        if (uptimeTimer == null) {
            uptimeTimer = new javafx.animation.AnimationTimer() {
                long lastTick = 0;
                @Override public void handle(long nowNanos) {
                    if (nowNanos - lastTick < 1_000_000_000L) return;
                    lastTick = nowNanos;
                    long started = serverStartedAtEpochMs;
                    if (started <= 0) {
                        uptimeLabel.setText("Uptime: --");
                        uptimeLabel.setStyle("-fx-text-fill: #888; -fx-padding: 0 12 0 0;");
                    } else {
                        long secs = Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
                        long hours = secs / 3600L;
                        long mins = (secs % 3600L) / 60L;
                        long s = secs % 60L;
                        StringBuilder sb = new StringBuilder("Uptime: ");
                        if (hours > 0) sb.append(hours).append("h ");
                        if (hours > 0 || mins > 0) sb.append(mins).append("m ");
                        sb.append(s).append("s");
                        uptimeLabel.setText(sb.toString());
                        uptimeLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 0 12 0 0;");
                    }
                }
            };
            uptimeTimer.start();
        }
    }

    /**
     * Pnl-28: bind a heartbeat-age supplier (typically
     * {@code Watchdog::lastHeartbeatAgeMs}). The toolbar updates the
     * badge once per second on the FX thread; -1 from the supplier means
     * "watchdog disarmed" and renders as "--".
     */
    public void bindHeartbeatProvider(java.util.function.LongSupplier provider) {
        this.heartbeatAgeProvider = provider == null ? (() -> -1L) : provider;
        if (heartbeatTimer == null) {
            heartbeatTimer = new javafx.animation.AnimationTimer() {
                long lastTick = 0;
                @Override public void handle(long nowNanos) {
                    if (nowNanos - lastTick < 1_000_000_000L) return; // 1 Hz
                    lastTick = nowNanos;
                    long ageMs = heartbeatAgeProvider.getAsLong();
                    if (ageMs < 0) {
                        heartbeatLabel.setText("Heartbeat: --");
                        heartbeatLabel.setStyle("-fx-text-fill: #888; -fx-padding: 0 12 0 0;");
                    } else {
                        long secs = ageMs / 1000L;
                        heartbeatLabel.setText("Heartbeat: " + secs + "s");
                        // Green under 5s, yellow under 30s, red over 30s.
                        String color = secs < 5 ? "#7ecf6f" : secs < 30 ? "#e0c060" : "#e07060";
                        heartbeatLabel.setStyle("-fx-text-fill: " + color + "; -fx-padding: 0 12 0 0;");
                    }
                }
            };
            heartbeatTimer.start();
        }
    }

    /**
     * Pnl-41: configure the alert banner once. The banner is added to
     * the StackPane overlay inside {@link #buildBody()}, where it sits
     * above the {@link #mainTabs} tab pane without taking layout space
     * when hidden. This avoids the jolt the audit flagged with
     * setManaged(true/false) inline.
     */
    private void configureAlertBanner() {
        alertBanner.setMaxWidth(Double.MAX_VALUE);
        alertBanner.setVisible(false);
        alertBanner.setManaged(false);
        alertBanner.setWrapText(true);
        // Pnl-41 (audit fix): warning-amber on dark background.
        // Reads as "advisory warning" in the dark-theme palette
        // instead of the previous leather-brown that registered as
        // decorative trim.
        alertBanner.setStyle("-fx-background-color: #7a5510; -fx-text-fill: #fff2c8;"
                + " -fx-padding: 10 16; -fx-font-weight: bold; -fx-font-size: 12px;"
                + " -fx-border-color: #b8860b; -fx-border-width: 0 0 2 0;");
        alertBanner.setOnMouseClicked(e -> hideAlertBanner());
        // PauseTransition runs on the FX thread; on completion we hide
        // the banner. setOnFinished is invoked after the configured
        // duration since the most recent playFromStart() call.
        bannerHideTimer.setOnFinished(e -> hideAlertBanner());
    }

    /**
     * Pnl-41: surface a transient alert (e.g. low TPS) without
     * interrupting the user. Safe to call from any thread; the
     * display work hops to the FX thread internally.
     *
     * @param title short label, used in bold prefix
     * @param body  detail text, e.g. "TPS 11.06 below 18.0 for 10 s"
     */
    public void showAlertBanner(String title, String body) {
        Platform.runLater(() -> {
            String safeTitle = title == null ? "Alert" : title;
            String safeBody = body == null ? "" : body;
            // Avoid em-dash etc. in the joined string; mojibake during
            // the previous modal dialog was traced to a UTF-8 em-dash
            // landing in a non-UTF-8-aware container. Use ASCII colon.
            alertBanner.setText(safeTitle + ": " + safeBody + "    (click to dismiss)");
            alertBanner.setVisible(true);
            alertBanner.setManaged(true);
            // Re-arm the auto-dismiss timer from now. playFromStart
            // resets the elapsed counter even if the banner was
            // already showing for a different alert.
            bannerHideTimer.playFromStart();
        });
    }

    /** Pnl-41: explicit dismiss, called by click handler and timer. */
    public void hideAlertBanner() {
        if (Platform.isFxApplicationThread()) {
            alertBanner.setVisible(false);
            alertBanner.setManaged(false);
            bannerHideTimer.stop();
        } else {
            Platform.runLater(this::hideAlertBanner);
        }
    }

    private HBox buildToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(6));
        Label title = new Label("Father Eye");
        title.setStyle("-fx-text-fill: #e0e0e0; -fx-font-weight: bold; -fx-padding: 0 12 0 0;");
        serverStateLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 0 12 0 0;");
        heartbeatLabel.setStyle("-fx-text-fill: #888; -fx-padding: 0 12 0 0;");
        // Pnl-42: Configure... button sits just before Start so the
        // operator can tweak RAM / JVM args / server.properties in one
        // place before clicking Start. The dialog also has its own
        // "Save & Start" so a single press configures and launches.
        uptimeLabel.setStyle("-fx-text-fill: #888; -fx-padding: 0 12 0 0;");
        bar.getChildren().addAll(title, configureBtn, startBtn, stopBtn, restartBtn,
                new javafx.scene.layout.Region(), uptimeLabel, heartbeatLabel, serverStateLabel);
        HBox.setHgrow(bar.getChildren().get(5), Priority.ALWAYS);
        bar.setStyle("-fx-background-color: #2b2b2b;");
        return bar;
    }

    private javafx.scene.layout.StackPane buildBody() {
        // Pnl-40: body is now a TabPane filling the main area. The
        // Console tab can host the full-height log; the Config tab
        // can host the full-height JSON tree.
        // Pnl-41: wrap in a StackPane so the alert banner can
        // overlay the top of the tabs without pushing them down. The
        // banner uses StackPane.alignment=TOP_CENTER so when it
        // becomes visible it floats over the tab strip area.
        configureAlertBanner();
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(mainTabs, alertBanner);
        javafx.scene.layout.StackPane.setAlignment(alertBanner, javafx.geometry.Pos.TOP_CENTER);
        // The banner shouldn't catch mouse events along the rest of
        // the stack; only when visible-and-managed does it matter,
        // and the click-to-dismiss handler is on the banner itself.
        return stack;
    }

    private Region buildStatusBar() {
        statusLabel.setFont(Font.font(11));
        statusLabel.setStyle("-fx-text-fill: #cfcfcf; -fx-padding: 4 8;");
        HBox bar = new HBox(statusLabel);
        bar.setStyle("-fx-background-color: #1f1f1f;");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        return bar;
    }

    private Tab stubTab(String name) {
        VBox box = new VBox(new Label(name + " — pending implementation"));
        box.setPadding(new Insets(16));
        Tab t = new Tab(name, box);
        t.setClosable(false);
        return t;
    }

    private Tab tab(String name, javafx.scene.Node body) {
        Tab t = new Tab(name, body);
        t.setClosable(false);
        return t;
    }

    private VBox stubPane(String title) {
        VBox v = new VBox(new Label(title + " — pending implementation"));
        v.setPadding(new Insets(8));
        v.setStyle("-fx-background-color: #2a2a2a;");
        return v;
    }

    public BorderPane root() { return root; }

    public void setStatus(String s) { statusLabel.setText(s); }
}
