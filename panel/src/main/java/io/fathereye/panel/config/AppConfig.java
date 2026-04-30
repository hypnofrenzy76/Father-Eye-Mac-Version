package io.fathereye.panel.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.fathereye.panel.util.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level configuration POJO. Persisted as JSON in the per-platform
 * Father Eye AppData directory (see {@link PlatformPaths#appDataDir()}).
 * Hot-reloadable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Config");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Display display = new Display();
    public Alerts alerts = new Alerts();
    public ServerRuntime serverRuntime = new ServerRuntime();
    public Bridge bridge = new Bridge();
    public History history = new History();
    public Backup backup = new Backup();

    public AppConfig() {}

    public static Path defaultPath() {
        return PlatformPaths.appDataDir().resolve("config.json");
    }

    public static AppConfig load(Path path) {
        AppConfig cfg;
        try {
            if (Files.exists(path)) {
                cfg = JSON.readValue(Files.readAllBytes(path), AppConfig.class);
            } else {
                cfg = new AppConfig();
                try { cfg.save(path); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            LOG.warn("config load failed, using defaults: {}", e.getMessage());
            cfg = new AppConfig();
        }

        // Pnl-34 (2026-04-26): per-server-folder operation. When the
        // panel is launched FROM inside a Forge server folder (cwd
        // contains mods/ + a Forge installer / server jar), override
        // serverRuntime.workingDir to that folder regardless of what the
        // shared %LOCALAPPDATA%/FatherEye/config.json says. This lets
        // the user drop the same Server-Start-with-Father-Eye.exe into
        // any number of server folders ("TomCraft-Server", "ModPack
        // Server Test", etc.) and each invocation manages the server in
        // its own folder. The shared config.json still drives display /
        // alerts / backup retention so user preferences carry across.
        try {
            Path cwd = Paths.get(System.getProperty("user.dir", "."));
            if (looksLikeForgeServer(cwd)) {
                String detected = cwd.toAbsolutePath().toString().replace('\\', '/');
                if (!detected.equalsIgnoreCase(cfg.serverRuntime.workingDir)) {
                    LOG.info("Auto-detected Forge server in cwd; overriding "
                            + "workingDir from config: {} -> {}",
                            cfg.serverRuntime.workingDir, detected);
                    cfg.serverRuntime.workingDir = detected;
                    // Default the backup dir to a sibling folder of the
                    // detected server so backups don't collide with the
                    // original server's backups.
                    if (cfg.backup.backupDir == null
                            || !cfg.backup.backupDir.startsWith(detected)) {
                        cfg.backup.backupDir = detected + "/backups";
                    }
                }
            }
        } catch (Throwable t) {
            LOG.debug("cwd detection skipped: {}", t.getMessage());
        }
        return cfg;
    }

    private static boolean looksLikeForgeServer(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        if (!Files.isDirectory(dir.resolve("mods"))) return false;
        // Any of these reliably signal "this is a Forge 1.16.5 server folder"
        // without false-positiving on a CurseForge instance or a generic
        // Java-app folder.
        return Files.isRegularFile(dir.resolve("eula.txt"))
                || Files.isRegularFile(dir.resolve("server.properties"))
                || Files.isRegularFile(dir.resolve("forge-1.16.5-36.2.39.jar"))
                || Files.isRegularFile(dir.resolve("minecraft_server.1.16.5.jar"));
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, JSON.writeValueAsBytes(this));
    }

    public static final class Display {
        public String theme = "dark";
        public int fontScale = 100;
        public int statsRefreshMs = 1000;
        public int playersRefreshMs = 1000;
        public int chartWindowMinutes = 5;
        public Display() {}
    }

    public static final class Alerts {
        public boolean enabled = true;
        // Pnl-41 (2026-04-26): default thresholds relaxed for heavy
        // modpack servers. The previous 18.0 / 10 s baseline tripped
        // on routine chunk-gen dips after every player login. The
        // user can still tune via the Config tab; existing config.json
        // files keep their stored values.
        public double tpsDropThreshold = 16.0;
        public int    tpsDropSustainSec = 30;
        public double heapPctThreshold = 85.0;
        public double msptThreshold = 80.0;
        public int playerOfflineMinutes = 10;
        public boolean desktopNotifications = true;
        public Alerts() {}
    }

    public static final class ServerRuntime {
        // Mac Pnl-Mac-1: defaults are placeholders that the Setup
        // wizard fills in on first run. The panel won't try to
        // auto-start with these placeholders (autoStart defaults to
        // false; the wizard flips it to true once the server is
        // installed). On Windows the upstream defaults are no longer
        // shipped — the wizard handles install on every platform.
        public String workingDir = defaultServerDir();
        public String javaPath = defaultJavaPath();
        // Mac fork (audit 7): align JVM args with ServerLaunchSpec.defaults()
        // and SetupApp.writePanelConfig — Aikar's tuning for Forge 1.16.5
        // on Java 8 with 12 GB heap. Drops the legacy -noverify flag
        // (deprecated in Java 9, removed in Java 13; harmless on 8 but
        // surfaces a deprecation warning in modern JVMs and confuses
        // future maintainers).
        public String jvmArgs = "-Xms8G -Xmx12G -XX:+UseG1GC -XX:+ParallelRefProcEnabled "
                + "-XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
                + "-XX:+DisableExplicitGC -XX:+AlwaysPreTouch "
                + "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 "
                + "-XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 "
                + "-XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 "
                + "-XX:InitiatingHeapOccupancyPercent=15 "
                + "-XX:G1MixedGCLiveThresholdPercent=90 "
                + "-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 "
                + "-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1";
        public String jarName = "forge-1.16.5-36.2.39.jar";
        public String mainArgs = "nogui";
        /** Whether the panel auto-starts the server on launch.
         *  Defaults to false on every platform — the Setup wizard sets
         *  this to true after a successful first-run server install.
         *  This avoids the previous behaviour where Windows users
         *  shipped a panel that tried to start a server at hardcoded
         *  Windows-specific paths that didn't exist on their machine. */
        public boolean autoStart = false;

        private static String defaultServerDir() {
            // Default to a "Minecraft Server" folder under the user's
            // home; the Setup wizard prompts for a different location.
            return java.nio.file.Paths.get(System.getProperty("user.home", "."),
                    "Minecraft Server").toString().replace('\\', '/');
        }

        private static String defaultJavaPath() {
            // Empty string means "use $JAVA_HOME/bin/java or the
            // first `java` on PATH". The Setup wizard resolves the
            // actual path and writes it back here. Hardcoding a
            // /usr/bin/java path on Mac would be wrong because Apple
            // hasn't shipped a system Java since 10.7 — the user
            // needs Temurin or Zulu in /Library/Java/...
            return "";
        }
        /** Pnl-43 (2026-04-26): when true (default), the panel shows the
         *  pre-boot configuration modal BEFORE auto-starting the server,
         *  so the user can review/edit RAM, JVM args and
         *  server.properties before each boot. Pnl-42 added the dialog
         *  but only as a manual button -- because autoStart fires
         *  immediately, the Configure button gates closed before the
         *  user can click it. With this flag the autoStart path opens
         *  the dialog and only boots when the user clicks Save & Start.
         *  Operators who prefer instant boot can set this to false in
         *  config.json or via the Config tab; autoStart=true alone
         *  preserves the original "click EXE, get server" UX. */
        public boolean showPreBootDialogOnStart = true;
        /** Pnl-44 (2026-04-26): how long the panel polls for the
         *  bridge marker file before giving up. The marker is written
         *  inside FMLServerStartedEvent on the server JVM; on a
         *  100+ mod modpack the JVM can take 5-10 minutes to reach
         *  that event because of mod scanning, registry resolution,
         *  worldgen pre-passes and structure indexing. The previous
         *  hardcoded 90 s deadline was way too short; the panel
         *  would give up before the bridge ever registered, leaving
         *  the user with a half-connected UI ("RUNNING" but no
         *  stats / players / map). 600 s = 10 minutes covers any
         *  reasonable modpack; larger values are fine, the polling
         *  just sleeps 2 s between marker-discovery attempts. */
        public long bridgeMarkerTimeoutMs = 600_000L;
        /**
         * When true, the watchdog will force-restart the server if no
         * tps_topic heartbeat arrives within {@code watchdogNoHeartbeatMs}
         * after warmup. Defaults to {@code false} per user directive
         * 2026-04-26: false-positive restarts during heavy mod load were
         * doing more harm than good. The watchdog still LOGS missed
         * heartbeats; the user kills + restarts manually if needed.
         */
        public boolean watchdogAutoRestart = false;
        public long watchdogNoHeartbeatMs = 90_000L;
        public long watchdogWarmupMs = 240_000L;
        public ServerRuntime() {}
    }

    public static final class Bridge {
        public String bindAddressOverride = null; // null = use marker file
        public int    publishRateHz = 1;
        public boolean profilerEnabled = true;
        public long   profilerThresholdNs = 1000;
        public Bridge() {}
    }

    public static final class History {
        public String dbPath = null; // null = default
        public int rawRetentionHours = 24;
        public int oneMinRetentionDays = 7;
        /** Pnl-59 (2026-04-27): drop event_log rows older than this
         *  many days. Default 30 d covers ~1 month of joins,
         *  leaves, chat, alerts for review without unbounded
         *  growth. Set to 0 to keep events forever (audit log
         *  mode). */
        public long eventLogRetentionDays = 30L;
        public History() {}
    }

    public static final class Backup {
        // Mac Pnl-Mac-1: backupDir defaults to <workingDir>/backups
        // resolved at first save. Empty here so the Setup wizard's
        // server-folder choice flows through. AppConfig.load() also
        // overrides this when cwd-auto-detection finds a Forge server.
        public String backupDir = "";
        /** Pnl-54 (audit fix, 2026-04-27): default 10 -> 0 (unlimited).
         *  Pre-Pnl-54 retainCount was the only retention rule and 10
         *  worked for pre-stop backups (one per server stop). With
         *  hourly backups + 14-day age retention, 10 silently
         *  truncated to the newest 10 (about 10 hours of coverage)
         *  contradicting the user's "delete when 2 weeks old"
         *  intent. retainCount=0 means "no count cap"; age alone
         *  governs. The user can still set a positive value as an
         *  upper bound. */
        public int retainCount = 0;
        public boolean includeConfigs = true;
        public boolean includeMods = false;
        /** Pnl-54 (2026-04-27): take a fresh world backup every
         *  {@link #hourlyIntervalMs} milliseconds while the server is
         *  RUNNING. Defaults to true so the user gets the
         *  user-requested behaviour out of the box. */
        public boolean hourlyBackups = true;
        /** Pnl-54: hourly cadence in ms. 60 * 60 * 1000 = 1 hour. */
        public long hourlyIntervalMs = 60L * 60L * 1000L;
        /** Pnl-54: age-based retention in days. Backups older than
         *  this are deleted on every backup completion. Defaults to
         *  14 days per user request. retainCount remains as a
         *  secondary cap (whichever rule keeps fewer wins). */
        public int retainDays = 14;
        public Backup() {}
    }
}
