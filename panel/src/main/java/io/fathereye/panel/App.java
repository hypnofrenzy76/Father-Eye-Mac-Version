package io.fathereye.panel;

import io.fathereye.panel.ipc.MarkerDiscovery;
import io.fathereye.panel.ipc.PipeClient;
import io.fathereye.panel.ipc.PipeReader;
import io.fathereye.panel.ipc.TopicDispatcher;
import io.fathereye.panel.launcher.ServerLaunchSpec;
import io.fathereye.panel.launcher.ServerLauncher;
import io.fathereye.panel.launcher.Watchdog;
import io.fathereye.panel.view.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class App extends Application {

    // Mac Pnl-Mac-1: set the per-platform Father Eye AppData root as a
    // system property BEFORE the SLF4J -> Logback binding happens. The
    // panel's logback.xml resolves ${FATHEREYE_APPDATA} via this
    // property, falling back to ${user.home}/FatherEye for safety. The
    // static block must precede the LOG field because Java initialises
    // static members in source order; if the field came first, LoggerFactory
    // would bind Logback (and parse logback.xml) before the property
    // existed, and the panel.log file would land in the upstream Windows
    // location rather than ~/Library/Application Support/FatherEye on Mac.
    static {
        try {
            java.nio.file.Path appData =
                    io.fathereye.panel.util.PlatformPaths.appDataDir();
            // Use forward slashes regardless of platform — logback's path
            // resolver handles them on Windows fine. Avoids an escape-char
            // tangle in the XML config.
            System.setProperty("FATHEREYE_APPDATA",
                    appData.toString().replace('\\', '/'));
        } catch (Throwable ignored) {
            // Logback's default fallback (${user.home}/FatherEye) will
            // catch this. The bootstrap shouldn't be able to throw, but
            // we don't want any exception during class loading to brick
            // the panel before the user sees anything.
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Panel");
    private static final String VERSION = "0.3.0-mac.1";

    /** Names of the topics the panel auto-subscribes to once handshaken. */
    private static final String[] AUTO_SUBSCRIBE = {
            "tps_topic",
            "console_log",
            "players_topic",
            "mobs_topic",
            "mods_impact_topic",
            // Pnl-52 (2026-04-26): subscribed so MapPane can fetch
            // tile data for EVERY currently-loaded chunk on the
            // server, not just the ones in the user's viewport. The
            // bridge's ChunksSnapshot.DimChunks now carries a
            // coordinate list (Brg-14).
            "chunks_topic"
    };

    private MainWindow mainWindow;
    private ExecutorService ipcExecutor;
    private PipeClient pipeClient;
    private PipeReader pipeReader;
    private final TopicDispatcher dispatcher = new TopicDispatcher();

    private ServerLauncher launcher;
    private Watchdog watchdog;
    private io.fathereye.panel.history.MetricsDb metricsDb;
    private io.fathereye.panel.config.AppConfig appConfig;
    private io.fathereye.panel.alerts.AlertEngine alertEngine;
    private io.fathereye.panel.launcher.RestartScheduler restartScheduler;
    /** Pnl-54 (2026-04-27): hourly world-backup scheduler. Fires
     *  BackupService.runBackup at the configured cadence whenever
     *  the launcher is in RUNNING state. Closed in stop() so the
     *  panel exits cleanly without the daemon hanging on. */
    private java.util.concurrent.ScheduledExecutorService hourlyBackupExecutor;
    private io.fathereye.panel.history.EvaluationGenerator evaluationGenerator;
    /** Pnl-36: ms-since-epoch of the most recent ServerLauncher.start();
     *  bookend for the session evaluation report on shutdown. */
    private volatile long sessionStartMs = 0L;

    @Override
    public void start(Stage stage) {
        // Whatever fails during boot, surface it to the user and the log
        // rather than letting the JavaFX runtime quietly close the window.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOG.error("Uncaught exception on thread {}", t.getName(), e);
        });
        try {
            startInternal(stage);
        } catch (Throwable t) {
            LOG.error("Father Eye panel failed during start()", t);
            try {
                // Mac fork (audit 2): show the platform-correct log
                // path so users on macOS aren't pointed at a Windows
                // env var.
                String logPath = io.fathereye.panel.util.PlatformPaths
                        .appDataDir().resolve("panel.log").toString();
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR,
                        "Startup failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
                                + "\n\nSee " + logPath + " for the full stack trace.");
                a.setHeaderText("Father Eye startup failed");
                a.showAndWait();
            } catch (Throwable inner) {
                LOG.error("Failed to show startup error dialog", inner);
            }
        }
    }

    private void startInternal(Stage stage) {
        LOG.info("Father Eye panel {} starting up", VERSION);
        LOG.info("Java: {} {}", System.getProperty("java.version"), System.getProperty("java.home"));
        LOG.info("OS: {} {} {}", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
        LOG.info("user.dir: {}", System.getProperty("user.dir"));
        LOG.info("LOCALAPPDATA: {}", System.getenv("LOCALAPPDATA"));

        // Pnl-18: orphaned-server defense. Register a JVM shutdown hook
        // that force-stops the child server JVM if the panel exits without
        // running App.stop() (i.e., the user kills the panel via Task
        // Manager "End task", Ctrl+C, SIGTERM, or any other route that
        // bypasses JavaFX's clean-shutdown path). Without this hook the
        // server JVM keeps running headless, holding world/session.lock
        // and chunk-region handles, which prevents the user from cleanly
        // restarting OR even deleting the world folder (their exact
        // complaint 2026-04-26).
        //
        // Caveat: the hook runs only when the JVM exits via a *signaled*
        // termination (most "End task" invocations, console close, normal
        // exit). A hard "End process tree" / TerminateProcess does NOT
        // trigger shutdown hooks; a Win32 Job Object would be required to
        // cover that path. Defer to a future Pnl-NN milestone if needed —
        // this hook covers the common case.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (launcher != null && (launcher.state() == ServerLauncher.State.RUNNING
                        || launcher.state() == ServerLauncher.State.STARTING)) {
                    LOG.info("Shutdown hook: stopping orphan server child (fast).");
                    // Fast variant — bounded 7s grace so we finish well
                    // inside Windows' ~10s JVM-shutdown allowance. Plain
                    // launcher.stop() would wait up to 30s and Windows
                    // would TerminateProcess us mid-stop, orphaning the
                    // server JVM (Pnl-33 user report).
                    launcher.stopFast();
                }
            } catch (Throwable t) {
                LOG.warn("shutdown hook stop failed: {}", t.getMessage());
            }
        }, "FatherEye-PanelShutdownHook"));

        mainWindow = new MainWindow();
        Scene scene = new Scene(mainWindow.root(), 1280, 800);
        stage.setTitle("Father Eye " + VERSION);
        stage.setScene(scene);
        stage.show();
        LOG.info("Main stage shown.");

        appConfig = io.fathereye.panel.config.AppConfig.load(io.fathereye.panel.config.AppConfig.defaultPath());
        mainWindow.configPane().bindConfig(appConfig);
        alertEngine = new io.fathereye.panel.alerts.AlertEngine(appConfig);
        // Pnl-41: route alerts through the in-window banner instead
        // of a modal Alert dialog. The dialog interrupted gameplay on
        // every transient TPS dip and rendered its title with
        // mojibake on Windows when the title contained an em-dash.
        alertEngine.bindMainWindow(mainWindow);

        try {
            metricsDb = new io.fathereye.panel.history.MetricsDb(io.fathereye.panel.history.MetricsDb.defaultPath());
            // Pnl-59: wire the user-configured event_log retention.
            metricsDb.setEventLogRetentionDays(appConfig.history.eventLogRetentionDays);
        } catch (Throwable ex) {
            LOG.warn("MetricsDb unavailable: {}", ex.getMessage());
        }

        try {
            wireLauncher();
        } catch (Throwable ex) {
            LOG.error("wireLauncher failed", ex);
            update("Launcher init failed: " + ex.getMessage());
        }
        try {
            wireDispatcher();
        } catch (Throwable ex) {
            LOG.error("wireDispatcher failed", ex);
        }

        ipcExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FatherEye-IPC");
            t.setDaemon(true);
            return t;
        });

        // "One executable bundled together": when autoStart is true AND no
        // live bridge is already running, kick off the server in the background
        // so the FX thread isn't blocked. If a marker IS live, skip the
        // launcher and just connect to the existing server. This handles:
        //   - Double-clicking the EXE while a server is already up (which
        //     would otherwise collide on world/session.lock).
        //   - User started the server manually via start.bat first and just
        //     wants the panel to attach.
        // Pnl-51 (audit fix, 2026-04-26): do NOT auto-kill orphans on
        // panel startup. Three parallel audits independently flagged
        // a CRITICAL multi-panel hostility bug: marker files contain
        // only the server PID, with no owner-identity, so panel B
        // starting up cannot distinguish "orphan from a dead panel"
        // from "child of a live sibling panel A". An auto-kill would
        // terminate panel A's healthy server. Instead: Job Object
        // protection (WindowsJobObject in ServerLauncher) prevents
        // ALL FUTURE orphans by reaping spawned JVMs on panel exit;
        // pre-existing orphans surface in the pre-boot dialog as
        // "external server detected" and are killed only via the
        // user's explicit "Stop existing & Start" click. Intentional
        // consent only -- no surprise inter-panel kills.
        if (appConfig.serverRuntime.autoStart) {
            ipcExecutor.submit(() -> {
                try {
                    boolean liveBridge = anyLiveBridge();
                    if (appConfig.serverRuntime.showPreBootDialogOnStart) {
                        // Pnl-49 (2026-04-26): always show the dialog
                        // when the user has requested it (including
                        // when an existing bridge is detected). The
                        // user reported "now the memory pop up isnt
                        // coming up when i open it"; the previous code
                        // skipped the dialog if anyLiveBridge() was
                        // true, but that meant the user saw NO
                        // configuration UI when re-attaching to a
                        // server they already had running. The dialog
                        // detects the live bridge separately (via
                        // launcher.state OR anyLiveBridge) and
                        // disables Save & Start when a server is
                        // already running, so showing the dialog with
                        // a live bridge is safe -- the user can still
                        // browse server.properties or edit JVM args
                        // for the next launch.
                        if (liveBridge) {
                            update("Existing bridge detected. Pre-boot dialog will show; Save & Start is disabled while a server is running.");
                        } else {
                            update("Pre-boot configuration dialog opened. Server will start when you click Save & Start.");
                        }
                        Platform.runLater(this::openPreBootDialog);
                    } else if (liveBridge) {
                        update("Existing bridge detected; skipping auto-start, connecting...");
                    } else {
                        launcher.start();
                        watchdog.arm();
                    }
                } catch (Throwable ex) {
                    LOG.error("auto-start failed", ex);
                    update("Auto-start failed: " + ex.getMessage());
                }
            });
        }

        // Pnl-45 (2026-04-26): tryConnect runs on its OWN dedicated
        // daemon thread, NOT on ipcExecutor. Pnl-43 introduced a
        // critical deadlock: when the pre-boot dialog gates autoStart,
        // openPreBootDialog submits launcher.start to ipcExecutor; but
        // tryConnect was also submitted to ipcExecutor (from this same
        // method) BEFORE the user had a chance to click Save & Start.
        // ipcExecutor is single-threaded, so tryConnect's
        // poll-and-Thread.sleep(2000) loop hogged the thread for the
        // full bridgeMarkerTimeoutMs (Pnl-44 bumped that to 600 s),
        // and launcher.start sat queued behind it -- the JVM never
        // spawned, no marker ever appeared, the user saw the panel
        // count "Xs elapsed" while the server they thought they had
        // launched simply did not exist. Putting tryConnect on its own
        // thread keeps ipcExecutor free for launcher.start, RPCs, etc.
        Thread connectThread = new Thread(this::tryConnect, "FatherEye-Connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * True if any bridge marker file points to a still-running Java process.
     * Stale markers (PID dead, OR PID alive but the executable isn't a JVM
     * — likely PID recycling) are deleted as a side effect so subsequent
     * runs start from a clean slate.
     */
    private boolean anyLiveBridge() {
        for (MarkerDiscovery.Marker m : MarkerDiscovery.discover()) {
            if (m.pid <= 0) continue;
            ProcessHandle ph = ProcessHandle.of(m.pid).orElse(null);
            if (ph != null && ph.isAlive()) {
                // PID-recycling guard: confirm the holding process actually
                // looks like a JVM. Without this a stale marker pointing at
                // PID 1234 (now reassigned to a browser tab) registers as
                // "live bridge" and we never auto-start.
                String cmd = ph.info().command().orElse("");
                if (cmd.contains("java") || cmd.endsWith("javaw.exe")) {
                    LOG.info("Found live bridge: instance {} pid {} cmd {} address {}",
                            m.instanceUuid, m.pid, cmd,
                            m.address != null ? m.address : m.pipeName);
                    return true;
                }
                LOG.info("Marker pid {} is alive but not a JVM ({}); treating as stale.",
                        m.pid, cmd);
            }
            // Stale marker; cull it.
            try {
                if (m.markerPath != null) {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(m.markerPath));
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void wireLauncher() {
        // Pnl-35: build the launch spec from the resolved AppConfig (which
        // by Pnl-34 has cwd-auto-detected workingDir if applicable),
        // NOT from the hardcoded TomCraft-Server defaults. Without this,
        // the panel correctly DETECTED its server folder and even logged
        // "overriding workingDir" but then the launcher silently used
        // the hardcoded path anyway. Bug surfaced when the user copied
        // the EXE into a second server folder and observed the wrong
        // mods loaded.
        evaluationGenerator = (metricsDb != null)
                ? new io.fathereye.panel.history.EvaluationGenerator(metricsDb) : null;
        final ServerLaunchSpec runSpec = ServerLaunchSpec.fromConfig(appConfig);
        launcher = new ServerLauncher(
                runSpec,
                line -> mainWindow.consolePane().onLogLine(asLogLineNode(line)),
                state -> {
                    Platform.runLater(() -> {
                        mainWindow.setServerState(state.name());
                        // Pnl-42 (audit fix): gate the Configure button
                        // to STOPPED/CRASHED states only. The dialog
                        // itself disables Save & Start mid-run, but
                        // gating the button up front matches the user's
                        // mental model ("configure BEFORE boot") and
                        // avoids the "Save partially applied because
                        // launcher.updateSpec refused" failure mode.
                        boolean idle = state == ServerLauncher.State.STOPPED
                                || state == ServerLauncher.State.CRASHED;
                        mainWindow.configureBtn().setDisable(!idle);
                    });
                    // Pnl-36: bookend session timestamps and write the
                    // evaluation report whenever a session ends.
                    if (state == ServerLauncher.State.STARTING) {
                        sessionStartMs = System.currentTimeMillis();
                        // Pnl-44: emit a synthetic console line so the
                        // user sees something happening during the
                        // 30-90 s of Forge classloader / mod scanning
                        // when the server JVM is alive but has not yet
                        // produced any stdout. Otherwise the panel
                        // looks frozen for that whole window.
                        mainWindow.consolePane().onLogLine(
                                asSyntheticLogLineNode("Server JVM spawned. Waiting for Forge classloader and mod scanning to begin emitting output (30-90 s on heavy modpacks)..."));
                    } else if (state == ServerLauncher.State.RUNNING) {
                        mainWindow.consolePane().onLogLine(
                                asSyntheticLogLineNode("Bridge handshake complete; server is now ready to accept connections."));
                    } else if ((state == ServerLauncher.State.STOPPED
                                || state == ServerLauncher.State.CRASHED)
                            && sessionStartMs > 0
                            && evaluationGenerator != null) {
                        long started = sessionStartMs;
                        sessionStartMs = 0L;
                        long ended = System.currentTimeMillis();
                        // Pnl-42 (audit fix): use the launcher's LIVE
                        // spec instead of the wireLauncher-time
                        // capture. Pnl-42 made spec mutable via
                        // updateSpec(); the lambda's old `runSpec`
                        // reference would still point at the original
                        // workingDir if the user ever changed it. The
                        // current dialog only edits jvmArgs but a
                        // future expansion shouldn't silently break
                        // evaluation report destinations.
                        java.nio.file.Path liveWorkingDir = launcher.spec().workingDir;
                        // Run off the launcher state-thread so a slow DB
                        // query doesn't stall state propagation.
                        Thread t = new Thread(() -> {
                            try {
                                evaluationGenerator.generate(
                                        liveWorkingDir, started, ended,
                                        state == ServerLauncher.State.CRASHED ? 1 : 0,
                                        state == ServerLauncher.State.CRASHED ? "crashed" : "graceful");
                            } catch (Throwable th) {
                                LOG.warn("evaluation generation failed", th);
                            }
                        }, "FatherEye-EvalWriter");
                        t.setDaemon(true);
                        t.start();
                    }
                }
        );

        // Watchdog defaults (configurable via AppConfig.serverRuntime):
        //   noHeartbeatMs = 90s, warmupMs = 240s. A heavily-modded Forge
        //   server (Thaumaturgy + 200 mods + structure-heavy world) routinely
        //   takes 2-3 minutes to reach FMLServerStartedEvent, where the
        //   bridge Publisher's 1 Hz tps_topic schedule starts firing. The
        //   previous 60/60 s defaults caused false-positive restarts on
        //   cold boot.
        //
        //   restartServer is gated behind watchdogAutoRestart (default
        //   FALSE). Per user directive 2026-04-26, the watchdog now logs
        //   missed heartbeats but does NOT auto-restart unless explicitly
        //   opted in via config. The user kills + restarts manually when
        //   the server actually misbehaves.
        long noHeartbeatMs = appConfig.serverRuntime.watchdogNoHeartbeatMs > 0
                ? appConfig.serverRuntime.watchdogNoHeartbeatMs : 90_000L;
        long warmupMs = appConfig.serverRuntime.watchdogWarmupMs > 0
                ? appConfig.serverRuntime.watchdogWarmupMs : 240_000L;
        watchdog = new Watchdog(noHeartbeatMs, warmupMs, new Watchdog.Action() {
            @Override public boolean processIsAlive() {
                return launcher.state() == ServerLauncher.State.RUNNING;
            }
            @Override public void restartServer() {
                if (!appConfig.serverRuntime.watchdogAutoRestart) {
                    LOG.warn("Watchdog detected no-heartbeat condition. Auto-restart is disabled "
                            + "(serverRuntime.watchdogAutoRestart=false). Kill and restart the server "
                            + "manually if it has truly hung; otherwise this is a false positive "
                            + "during heavy mod load and can be ignored.");
                    return;
                }
                try { launcher.restart(); }
                catch (IOException e) { LOG.error("watchdog restart failed", e); }
            }
        });
        watchdog.start();
        // Pnl-28: surface the heartbeat-age in the toolbar.
        mainWindow.bindHeartbeatProvider(watchdog::lastHeartbeatAgeMs);

        // Manual Start/Restart buttons run on the FX thread; ServerLauncher.start
        // can now block up to 15 s polling session.lock, so we MUST move the
        // work to a background thread or the entire UI freezes (auditors A#5,
        // B#A, C#3). Stop is already off-thread for the backup; preserve that.
        mainWindow.startBtn().setOnAction(e -> ipcExecutor.submit(() -> {
            try {
                launcher.start();
                watchdog.arm();
            } catch (IOException ex) {
                LOG.error("server start failed", ex);
                update("Server start failed: " + ex.getMessage());
                // If start threw, the watchdog must not stay armed in some
                // half-state (auditor A#9). It was never armed in this branch
                // anyway, but guard explicitly for symmetry.
                watchdog.disarm();
            } catch (Throwable ex) {
                LOG.error("server start failed (unexpected)", ex);
                update("Server start failed: " + ex.getMessage());
                watchdog.disarm();
            }
        }));
        mainWindow.stopBtn().setOnAction(e -> {
            watchdog.disarm();
            // Run pre-stop backup in background, then stop.
            new Thread(() -> {
                try {
                    io.fathereye.panel.launcher.BackupService.fromConfig(appConfig).runBackup();
                } catch (Exception ex) {
                    LOG.warn("pre-stop backup failed: {}", ex.getMessage());
                }
                launcher.stop();
            }, "FatherEye-StopWithBackup").start();
        });
        mainWindow.restartBtn().setOnAction(e -> ipcExecutor.submit(() -> {
            try {
                launcher.restart();
                watchdog.arm();
            } catch (IOException ex) {
                LOG.error("server restart failed", ex);
                update("Server restart failed: " + ex.getMessage());
                watchdog.disarm();
            } catch (Throwable ex) {
                LOG.error("server restart failed (unexpected)", ex);
                update("Server restart failed: " + ex.getMessage());
                watchdog.disarm();
            }
        }));

        // Pnl-42: pre-boot configuration dialog. Opens a modal with
        // RAM / JVM args / server.properties tabs. Save persists
        // changes to disk; Save & Start additionally launches the
        // server with the freshly applied spec. Disabled while the
        // server is RUNNING (the dialog handles that internally too).
        mainWindow.configureBtn().setOnAction(e -> openPreBootDialog());

        // Daily restart at 04:00 local time (configurable later via ConfigPane).
        // RestartScheduler invokes the callback on its own scheduler thread, so
        // it doesn't block the FX thread; safe to call launcher.restart directly.
        restartScheduler = new io.fathereye.panel.launcher.RestartScheduler(() -> {
            try { launcher.restart(); watchdog.arm(); }
            catch (IOException ex) { LOG.error("scheduled restart failed", ex); watchdog.disarm(); }
        });
        restartScheduler.scheduleDailyAt(java.time.LocalTime.of(4, 0));

        // Pnl-54 (2026-04-27): hourly world-backup scheduler. User
        // requested "i want hourly world backups, they should be
        // deleted when they are two weeks old". A daemon
        // ScheduledExecutorService fires at the configured cadence
        // ONLY when the launcher state is RUNNING (so we don't
        // backup a half-loaded server during STARTING, or a stopped
        // world that's already been pre-stop-backed up). Each run
        // applies age + count retention via BackupService so
        // 14-day-old backups are pruned automatically.
        if (appConfig.backup.hourlyBackups && appConfig.backup.hourlyIntervalMs > 0) {
            long intervalMs = appConfig.backup.hourlyIntervalMs;
            // Pnl-58 (2026-04-27): one-shot sweep of stale .partial
            // folders left from a previous-session crash mid-backup.
            // Runs on a daemon thread so panel UI startup isn't
            // blocked by the directory walk.
            new Thread(() -> {
                try { io.fathereye.panel.launcher.BackupService.fromConfig(appConfig).sweepStalePartials(); }
                catch (Throwable t) { LOG.warn("startup partial sweep failed", t); }
            }, "FatherEye-PartialSweep").start();
            hourlyBackupExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FatherEye-HourlyBackup");
                t.setDaemon(true);
                return t;
            });
            hourlyBackupExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (launcher == null
                            || launcher.state() != ServerLauncher.State.RUNNING) {
                        // Skip silently; we backup live worlds only.
                        return;
                    }
                    // Mac fork (audit 10 B1): early-return when
                    // backupDir is empty so the hourly executor doesn't
                    // throw / spam status updates every hour.
                    if (appConfig.backup.backupDir == null
                            || appConfig.backup.backupDir.isEmpty()) {
                        return;
                    }
                    update("Hourly backup running...");
                    io.fathereye.panel.launcher.BackupService.fromConfig(appConfig).runBackup();
                    update("Hourly backup complete.");
                } catch (Throwable t) {
                    LOG.warn("hourly backup failed", t);
                    update("Hourly backup failed: " + t.getMessage());
                }
            // Pnl-54 (audit fix): initial delay 5 min instead of full
            // intervalMs. Users who restart the panel every few hours
            // were getting zero coverage with a 1-hour initial delay.
            // 5 min is short enough to catch quick sessions but long
            // enough that the user has time to log in, configure,
            // and stabilize the server before the first backup.
            }, java.util.concurrent.TimeUnit.MINUTES.toMillis(5), intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            LOG.info("Hourly backup scheduler armed: every {} min, retention {} days / {} count.",
                    intervalMs / 60000L,
                    appConfig.backup.retainDays,
                    appConfig.backup.retainCount);
        }

        mainWindow.consolePane().setOnCommandSubmit(cmd -> {
            // Prefer bridge cmd_run when connected (so output and error paths
            // route through the unified RPC layer); fall back to direct stdin
            // if the pipe is closed.
            try {
                if (pipeClient != null && !pipeClient.isClosed()) {
                    java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
                    args.put("command", cmd);
                    pipeClient.sendRequest("cmd_run", args);
                } else {
                    launcher.sendCommand(cmd);
                }
            } catch (IOException ex) {
                LOG.warn("send command failed: {}", ex.getMessage());
            }
        });
    }

    /**
     * Pnl-51 (2026-04-26): scan the bridge marker directory for any
     * server JVMs that this panel did not spawn (or that survived a
     * previous panel that died without cleanup). For each live marker
     * with a non-zero PID, terminate the process via
     * {@link ProcessHandle} and delete its marker file. Idempotent;
     * safe to call when no orphans exist.
     *
     * <p>Used both at panel startup (clean slate) and on Save & Start
     * with an external bridge detected (replace orphan with fresh
     * launch). The user said "we have to be certain there are never
     * any orphans"; combined with {@link
     * io.fathereye.panel.launcher.WindowsJobObject} for new launches,
     * this closes both halves of the no-orphans guarantee.
     */
    private void killOrphanServers() {
        try {
            java.util.List<io.fathereye.panel.ipc.MarkerDiscovery.Marker> markers =
                    io.fathereye.panel.ipc.MarkerDiscovery.discover();
            for (io.fathereye.panel.ipc.MarkerDiscovery.Marker m : markers) {
                long pid = m.pid;
                if (pid <= 0) continue;
                java.util.Optional<ProcessHandle> ph = ProcessHandle.of(pid);
                if (ph.isPresent() && ph.get().isAlive()) {
                    try {
                        boolean killed = ph.get().destroyForcibly();
                        LOG.info("Killed orphan server pid={} (instance={}) destroyForcibly={}", pid, m.instanceUuid, killed);
                        update("Killed orphan server JVM pid=" + pid);
                    } catch (Throwable t) {
                        LOG.warn("Failed to kill orphan pid={}", pid, t);
                    }
                }
                // Pnl-51 (audit fix): delete via the marker's own
                // recorded path (m.markerPath, set by
                // MarkerDiscovery.discover line 42) instead of
                // recomputing the path. Avoids drift if the
                // bridges-dir resolution logic ever changes.
                try {
                    if (m.markerPath != null) {
                        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(m.markerPath));
                    }
                } catch (Throwable t) {
                    LOG.warn("Failed to delete marker for {}", m.instanceUuid, t);
                }
            }
        } catch (Throwable t) {
            LOG.warn("killOrphanServers scan failed", t);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode asLogLineNode(String line) {
        // Build a minimal JSON object for ConsolePane.onLogLine.
        com.fasterxml.jackson.databind.node.ObjectNode n = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        n.put("tsMs", System.currentTimeMillis());
        n.put("level", "INFO");
        n.put("logger", "server");
        n.put("thread", "stdout");
        n.put("msg", line);
        return n;
    }

    /**
     * Pnl-44 (2026-04-26): synthetic console line emitted by the panel
     * itself for state transitions and diagnostic messages. Tagged as
     * coming from the panel ("FatherEye-Panel") so the user can tell
     * it apart from genuine server output.
     */
    private com.fasterxml.jackson.databind.JsonNode asSyntheticLogLineNode(String line) {
        com.fasterxml.jackson.databind.node.ObjectNode n = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        n.put("tsMs", System.currentTimeMillis());
        n.put("level", "INFO");
        n.put("logger", "FatherEye");
        n.put("thread", "panel");
        n.put("msg", line);
        return n;
    }

    @Override
    public void stop() {
        LOG.info("Father Eye panel shutting down");
        if (restartScheduler != null) restartScheduler.stop();
        // Pnl-54 (audit fix): graceful shutdown ordering. shutdown()
        // first stops accepting new tasks but lets in-flight backup
        // complete; awaitTermination(15s) gives a multi-GB world
        // copy a chance to finish writing instead of leaving a
        // half-copied folder; shutdownNow() is a fallback only if
        // the backup is genuinely stuck. Three audits flagged the
        // previous shutdownNow-first-then-await as a bug.
        if (hourlyBackupExecutor != null) {
            hourlyBackupExecutor.shutdown();
            try {
                if (!hourlyBackupExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    LOG.warn("hourly backup did not finish within 15 s; forcing shutdown.");
                    hourlyBackupExecutor.shutdownNow();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                hourlyBackupExecutor.shutdownNow();
            }
        }
        if (watchdog != null) watchdog.stop();
        if (launcher != null && launcher.state() == ServerLauncher.State.RUNNING) {
            launcher.stop();
        }
        // Close the pipe FIRST: this fails any blocking ReadFile so the
        // PipeReader thread exits its loop, instead of holding readLock
        // when we tell it to stop.
        if (pipeClient != null) pipeClient.close();
        if (pipeReader != null) pipeReader.stop();
        if (metricsDb != null) metricsDb.close();
        if (ipcExecutor != null) {
            ipcExecutor.shutdownNow();
            try { ipcExecutor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    private void wireDispatcher() {
        dispatcher.onSnapshot("tps_topic", payload -> {
            if (watchdog != null) watchdog.heartbeat();
            mainWindow.statsPane().onTpsSnapshot(payload);
            if (metricsDb != null) metricsDb.writeMetric("tps_topic", payload);
            if (alertEngine != null) alertEngine.onTpsSnapshot(payload);
        });
        dispatcher.onEvent("console_log", payload -> {
            mainWindow.consolePane().onLogLine(payload);
            if (metricsDb != null) metricsDb.writeEvent("console_log", payload);
        });
        dispatcher.onSnapshot("players_topic", payload -> {
            mainWindow.playersPane().onSnapshot(payload);
            mainWindow.mapPane().onPlayersSnapshot(payload);
            if (metricsDb != null) metricsDb.writeMetric("players_topic", payload);
            // Pnl-41: detect player count increase and disarm alerts
            // for 30 s. Chunk-gen + entity wakeup on player join
            // routinely drops TPS below threshold for several
            // seconds; that is not a real performance problem.
            if (alertEngine != null) alertEngine.onPlayersSnapshot(payload);
        });
        dispatcher.onSnapshot("mobs_topic",    payload -> {
            mainWindow.mobsPane().onSnapshot(payload);
            if (metricsDb != null) metricsDb.writeMetric("mobs_topic", payload);
        });
        dispatcher.onSnapshot("mods_impact_topic", payload -> {
            mainWindow.modsPane().onSnapshot(payload);
            // Pnl-30: feed the same snapshot to StatsPane so the Server
            // Health Assessment can score the dominant mod's tick share.
            mainWindow.statsPane().onModsImpactSnapshot(payload);
            if (metricsDb != null) metricsDb.writeMetric("mods_impact_topic", payload);
        });
        // Pnl-52 (2026-04-26): chunks_topic now consumed -- the
        // bridge sends every loaded chunk's coordinates (capped at
        // 4096 per dim) so MapPane can pre-fetch tiles for chunks
        // outside the visible viewport. Fixes "map is not updating
        // correctly to show all loaded chunks".
        dispatcher.onSnapshot("chunks_topic", payload -> {
            mainWindow.mapPane().onChunksSnapshot(payload);
            if (metricsDb != null) metricsDb.writeMetric("chunks_topic", payload);
        });
    }

    private void tryConnect() {
        // Pnl-48 (2026-04-26): verbose-logging emit. Surface the
        // bridge-discovery start in the in-app console so the user
        // can see the connect attempt is actually running and not
        // silently waiting.
        Platform.runLater(() -> mainWindow.consolePane().onLogLine(
                asSyntheticLogLineNode("Bridge marker discovery started; will poll for handshake.")));
        try {
            // Pnl-44 (2026-04-26): the previous hardcoded 90 s
            // deadline was too short for heavy modpacks. The bridge
            // marker is written inside FMLServerStartedEvent on the
            // server JVM, which on a 100+ mod modpack can take 5-10
            // minutes to reach (mod scanning, registry resolution,
            // worldgen pre-pass, structure indexing). The user
            // reported "bridge has to wait longer than 90 seconds for
            // a server with so many mods". Configurable now via
            // AppConfig.serverRuntime.bridgeMarkerTimeoutMs (default
            // 600_000 = 10 minutes); larger is fine, polling sleeps
            // 2 s between attempts so the cost is just elapsed time.
            long timeoutMs = appConfig != null && appConfig.serverRuntime != null
                            && appConfig.serverRuntime.bridgeMarkerTimeoutMs > 0
                    ? appConfig.serverRuntime.bridgeMarkerTimeoutMs
                    : 600_000L;
            // Pnl-45 (2026-04-26): poll loop is now state-aware. The
            // deadline only "ticks" while the launcher is actively
            // trying to start (STARTING). When it's STOPPED/CRASHED
            // (e.g. user cancelled the pre-boot dialog, or hasn't
            // clicked Save & Start yet) the loop sleeps forever with a
            // clear "no server running" status. The previous version
            // counted elapsed seconds against an idle launcher, which
            // misled the user into thinking the server was hanging
            // when in fact no server had ever been launched.
            Optional<MarkerDiscovery.Marker> first = Optional.empty();
            long startedTryingMs = -1L;
            long limit = timeoutMs / 1000L;
            while (true) {
                first = MarkerDiscovery.discoverFirst();
                if (first.isPresent()) break;
                ServerLauncher.State s = launcher == null ? null : launcher.state();
                if (s == ServerLauncher.State.STOPPED) {
                    update("No server running. Use Configure or Start in the toolbar to launch.");
                    startedTryingMs = -1L;
                } else if (s == ServerLauncher.State.CRASHED) {
                    update("Previous server CRASHED. Use Configure or Start to relaunch (check console for errors).");
                    startedTryingMs = -1L;
                } else {
                    // STARTING / RUNNING / STOPPING / null: a launch is
                    // in flight (or we're attached to an external
                    // server). Start counting elapsed time the FIRST
                    // tick we see this state.
                    if (startedTryingMs < 0L) startedTryingMs = System.currentTimeMillis();
                    long elapsedMs = System.currentTimeMillis() - startedTryingMs;
                    long elapsedSec = elapsedMs / 1000L;
                    if (elapsedMs >= timeoutMs) {
                        update("No bridge marker found after " + limit + "s of starting. Server may have hung; check console for mod-load errors.");
                        // Keep looping — if user manually fixes/restarts,
                        // we'll pick up the new marker. Reset so the
                        // counter starts fresh.
                        startedTryingMs = -1L;
                    } else {
                        update("Waiting for bridge marker... " + elapsedSec + "s elapsed of " + limit + "s budget. Heavy modpacks normally take 2-5 minutes.");
                    }
                }
                try { Thread.sleep(2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            MarkerDiscovery.Marker m = first.get();
            String addr = m.address != null ? m.address : m.pipeName;
            update("Connecting to bridge " + m.bridgeVersion + " (" + (m.transport == null ? "named-pipe" : m.transport) + ") on " + addr);
            pipeClient = PipeClient.forMarker(m);
            pipeClient.connect();
            PipeClient.WelcomeInfo welcome = pipeClient.handshake(VERSION);
            // Pnl-67: populate the toolbar's Uptime badge from the
            // marker's startedAtEpochMs. The label updates once
            // per second from this anchor on the FX thread.
            Platform.runLater(() -> mainWindow.setServerStartedAtEpochMs(m.startedAtEpochMs));

            // Pnl-53 (2026-04-27): wire the persistent tile cache so
            // every chunk_tile response gets saved to disk and the
            // map fills in vanilla-map-style across panel restarts.
            // Cache is rooted at <serverDir>/fathereye-tiles so it
            // travels with the server data.
            try {
                if (m.serverDir != null) {
                    java.nio.file.Path serverDir = java.nio.file.Paths.get(m.serverDir);
                    // Pnl-63 (2026-04-27): pass the bridge's
                    // instanceUuid so TileDiskCache can detect a
                    // world regeneration (deleted+recreated world
                    // gets a fresh UUID file at
                    // world/serverconfig/fathereye-instance.uuid).
                    // The cache wipes its tiles on UUID mismatch
                    // so the new world isn't painted on top of the
                    // old world's terrain.
                    io.fathereye.panel.view.TileDiskCache cache =
                            io.fathereye.panel.view.TileDiskCache.open(serverDir, welcome.instanceUuid);
                    if (cache != null) {
                        mainWindow.mapPane().setTileCache(cache);
                        LOG.info("Tile disk cache attached at {}", cache.baseDir());
                    }
                } else {
                    LOG.warn("Marker has no serverDir; tile disk cache disabled (every chunk re-fetched on each panel start).");
                }
            } catch (Throwable t) {
                LOG.warn("Tile disk cache setup failed; running in-memory only: {}", t.getMessage());
            }
            // M18: protocol version mismatch UX.
            if (welcome.bridgeVersion != null && !VERSION.equals(welcome.bridgeVersion)) {
                LOG.warn("Bridge version {} differs from panel {} — proceed with caution.",
                        welcome.bridgeVersion, VERSION);
            }
            update("Connected. bridge=" + welcome.bridgeVersion
                    + " mc=" + welcome.mcVersion
                    + " forge=" + welcome.forgeVersion
                    + " mods=" + welcome.modCount
                    + " dims=" + welcome.dimensionCount);
            LOG.info("Handshake OK against bridge {} (instance {})", welcome.bridgeVersion, welcome.instanceUuid);
            // Pnl-48: surface a clear handshake-success banner in the
            // in-app console so the user sees the bridge is alive.
            mainWindow.consolePane().onLogLine(asSyntheticLogLineNode(
                    "Bridge handshake OK. mc=" + welcome.mcVersion
                            + " forge=" + welcome.forgeVersion
                            + " mods=" + welcome.modCount
                            + " dims=" + welcome.dimensionCount
                            + " bridge=" + welcome.bridgeVersion));

            // Pnl-44 (2026-04-26): bridge handshake is the real
            // "server is ready to serve" signal. Promote launcher
            // state from STARTING to RUNNING here. Previously the
            // launcher flipped to RUNNING the moment the JVM
            // process spawned, which mis-reported the server as
            // ready while it was still loading mods for many
            // minutes. The toolbar badge now stays "STARTING"
            // until the server is genuinely available.
            if (launcher != null) launcher.markRunning();
            // Pnl-44 (audit fix): seed the watchdog's heartbeat clock
            // here. The watchdog was armed at start time with a
            // warmup window; on long boots (5+ minutes) the warmup
            // expires before markRunning fires, and the FIRST tps
            // heartbeat arrives a moment after RUNNING. The watchdog
            // would otherwise compute "no heartbeat for 5 minutes" and
            // log a WARN even though it should be measuring from now.
            // (auto-restart is disabled by default, so this was just
            // a misleading log entry, but the user has been confused
            // by it before.)
            if (watchdog != null) watchdog.heartbeat();

            // Pnl-39: seed ModsPane with the full installed mod set so
            // every mod gets a row even before any mods_impact_topic
            // snapshot arrives, and so mods that never tick (no
            // entities or TEs) still appear with an Idle placeholder.
            mainWindow.modsPane().setKnownMods(welcome.modIds);

            // Order matters: start the PipeReader and subscribe to all
            // topics BEFORE binding the map. Pnl-17's bindPipeClient triggers
            // a redraw that fires up to 64 chunk_tile sendRequests on the FX
            // thread; running those in parallel with this thread's subscribe
            // sendJson loop creates writeLock contention that has caused
            // hangs in the wild. Get the handshake's full subscribe set down
            // first, then let the map start fetching tiles. The map will
            // also redraw on the first players_topic Snapshot anyway.
            // Pnl-28: feed every inbound frame to the watchdog so its
            // heartbeat tracks any bridge activity, not just tps_topic.
            // Pnl-33: surface a UI status update + schedule auto-reconnect
            // when the reader exits (server died / restarted etc.).
            pipeReader = new PipeReader(pipeClient, dispatcher,
                    () -> { if (watchdog != null) watchdog.heartbeat(); },
                    this::onBridgeDisconnect);
            pipeReader.start();

            for (String topic : AUTO_SUBSCRIBE) {
                pipeClient.sendJson("Subscribe", topic, null);
                LOG.info("Subscribed to {}", topic);
            }
            LOG.info("All subscribes sent; binding map.");

            // Surface known dimensions to the map dim picker (defaults to overworld
            // if the welcome carried none).
            String[] dims = (welcome.dimensions != null && welcome.dimensions.length > 0)
                    ? welcome.dimensions
                    : new String[] { "minecraft:overworld" };
            mainWindow.mapPane().setDimensions(dims);
            mainWindow.mapPane().bindPipeClient(pipeClient);
        } catch (Throwable t) {
            LOG.error("Connect failed", t);
            update("Connect failed: " + t.getMessage());
            // Pnl-57 (2026-04-27): close the transport on every
            // handshake/connect failure so the socket FD or named-
            // pipe handle does not leak. PipeClient.handshake has
            // five throw paths (Hello write IOException, Welcome
            // read IOException, Jackson treeToValue, protocol-too-
            // old Error, 16-attempt timeout); none were closing the
            // transport. On reconnect, pipeClient was reassigned,
            // dropping the only reference to the live FD until the
            // JVM exited. Ignore close-time exceptions: by this
            // point the transport is unhealthy anyway.
            PipeClient stale = pipeClient;
            if (stale != null) {
                try { stale.close(); } catch (Throwable ignored) {}
                pipeClient = null;
            }
        }
    }

    private void update(String msg) {
        Platform.runLater(() -> mainWindow.setStatus(msg));
    }

    /**
     * Pnl-42 (2026-04-26): show the pre-boot configuration modal and
     * apply whatever the user saved.
     *
     * <p>Save path: write the new {@code jvmArgs} into
     * {@link #appConfig}, persist the config JSON, write the mutated
     * server.properties back to disk, and (only when the user
     * pressed Save &amp; Start) install the new spec on the launcher
     * + start it. The launcher's {@code updateSpec} refuses while the
     * server is running, which mirrors the dialog's own
     * {@code serverIsRunning} guard, so we cannot get into a state
     * where new args silently apply mid-run.
     */
    private void openPreBootDialog() {
        try {
            // Pnl-51 (2026-04-26): differentiate panel-owned RUNNING
            // (truly disable Save & Start) from external-orphan
            // RUNNING (Save & Start enabled with kill-and-restart
            // semantics). The user said "we have to be certain there
            // are never any orphans"; combined with the Job Object
            // protection in WindowsJobObject, clicking Save & Start
            // when an orphan exists kills it via the marker's PID,
            // then launches fresh under the new Job Object.
            boolean launcherRunning = launcher != null
                    && launcher.state() != io.fathereye.panel.launcher.ServerLauncher.State.STOPPED
                    && launcher.state() != io.fathereye.panel.launcher.ServerLauncher.State.CRASHED;
            boolean externalBridge = anyLiveBridge() && !launcherRunning;
            // Resolve the working directory the same way the launcher
            // does (so the dialog reads the right server.properties).
            io.fathereye.panel.launcher.ServerLaunchSpec curSpec =
                    io.fathereye.panel.launcher.ServerLaunchSpec.fromConfig(appConfig);
            io.fathereye.panel.view.PreBootDialog dlg =
                    new io.fathereye.panel.view.PreBootDialog(appConfig, curSpec.workingDir, launcherRunning, externalBridge);
            java.util.Optional<io.fathereye.panel.view.PreBootDialog.Result> result = dlg.showAndWait();
            if (!result.isPresent()) return;
            io.fathereye.panel.view.PreBootDialog.Result r = result.get();

            // 1. Apply JVM args to the in-memory config and persist.
            appConfig.serverRuntime.jvmArgs = r.jvmArgs == null ? "" : r.jvmArgs;
            // Pnl-43: persist the "auto-show on start" toggle from
            // the dialog so the user's preference survives panel
            // restarts. Defaulting to true means new installs always
            // see the dialog before boot; clearing the checkbox
            // restores the original "click EXE, get server" UX.
            appConfig.serverRuntime.showPreBootDialogOnStart = r.showOnNextStart;
            try {
                appConfig.save(io.fathereye.panel.config.AppConfig.defaultPath());
            } catch (IOException ioe) {
                LOG.warn("config save failed: {}", ioe.getMessage());
                update("Config save failed: " + ioe.getMessage());
            }

            // 2. Write server.properties.
            try {
                dlg.saveServerProperties(r.serverProperties);
            } catch (IOException ioe) {
                LOG.warn("server.properties save failed: {}", ioe.getMessage());
                update("server.properties save failed: " + ioe.getMessage());
            }

            // 3. Refresh the launcher's spec so the next start uses
            // the new args. The dialog disables Save & Start while
            // running, but defensively check state anyway.
            try {
                launcher.updateSpec(io.fathereye.panel.launcher.ServerLaunchSpec.fromConfig(appConfig));
            } catch (IllegalStateException ise) {
                LOG.warn("launcher spec update refused: {}", ise.getMessage());
            }

            // 4. Optional auto-start.
            if (r.startAfterSave && !launcherRunning) {
                // Pnl-42 (audit fix): give the user immediate feedback
                // before the launcher starts. launcher.start() can
                // block up to 15 s polling session.lock and several
                // seconds spawning the JVM; without this status update
                // the dialog dismisses and the panel looks idle until
                // the state-sink fires.
                update("Launching server with new configuration...");
                ipcExecutor.submit(() -> {
                    try {
                        // Pnl-51: if an external (orphan) server is
                        // running, kill it BEFORE launching. Two-stage
                        // path: read every live marker, terminate the
                        // PID, wait briefly for session.lock to clear.
                        // Job Object protection only applies to JVMs
                        // spawned AFTER this point; existing orphans
                        // don't have it. session.lock retry inside
                        // ServerLauncher.start gives extra cushion.
                        if (anyLiveBridge()) {
                            update("Stopping existing server before launch...");
                            killOrphanServers();
                            // Brief pause to let the OS reap handles
                            // and free session.lock; ServerLauncher.start
                            // also polls the lock for up to 15 s.
                            try { Thread.sleep(2000L); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        launcher.start();
                        watchdog.arm();
                    } catch (Throwable th) {
                        LOG.error("Save & Start failed", th);
                        update("Save & Start failed: " + th.getMessage());
                        watchdog.disarm();
                    }
                });
            } else {
                update("Pre-boot configuration saved.");
            }
        } catch (Throwable t) {
            LOG.error("openPreBootDialog failed", t);
            update("Configure dialog failed: " + t.getMessage());
        }
    }

    /**
     * Pnl-33: invoked from the {@link PipeReader} thread when the bridge
     * connection drops (server crash, restart, network blip). Disarm the
     * watchdog so a stale heartbeat doesn't fire the threshold check, and
     * schedule a background polling reconnect attempt — the bridge writes
     * a fresh marker on every server start, so polling for a NEW marker
     * (or the same UUID with a fresh started-at) re-establishes the link
     * without user intervention.
     */
    private void onBridgeDisconnect() {
        update("Bridge disconnected. Watching for reconnect...");
        if (watchdog != null) watchdog.disarm();
        // Pnl-67: reset the uptime label so the user doesn't see a
        // stale ever-growing duration after the server stopped.
        Platform.runLater(() -> mainWindow.setServerStartedAtEpochMs(-1L));
        // Pnl-45 (2026-04-26): the reconnect watcher must NOT run on
        // ipcExecutor for the same reason tryConnect doesn't (see
        // startInternal). A Thread.sleep loop on the single IPC thread
        // would block any concurrent launcher.start / RPC call. Use a
        // dedicated daemon thread; the watcher only inspects
        // anyLiveBridge() and calls tryConnect() once a marker shows
        // up, both of which are thread-safe.
        Thread t = new Thread(() -> {
            // Wait briefly for any in-flight launcher.start() / restart to
            // produce a fresh marker, then loop until either the panel is
            // closed or a new bridge appears. Use anyLiveBridge() rather
            // than raw discoverFirst -- the latter would happily return a
            // stale marker (server killed but file still on disk) and we'd
            // try to TCP-connect to a dead port (Connection refused).
            long deadline = System.currentTimeMillis() + 5L * 60_000L; // 5 min
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                if (pipeClient != null && !pipeClient.isClosed()) return; // someone reconnected already
                if (anyLiveBridge()) {
                    update("Live bridge marker found; reconnecting...");
                    tryConnect();
                    return;
                }
            }
            update("Bridge did not return within 5 minutes; click Start to relaunch.");
        }, "FatherEye-Reconnect");
        t.setDaemon(true);
        t.start();
    }
}
