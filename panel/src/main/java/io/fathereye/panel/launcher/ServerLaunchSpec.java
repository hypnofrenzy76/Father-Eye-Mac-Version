package io.fathereye.panel.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How to launch the server. Defaults match the user's TomCraft-Server
 * setup at {@code C:\Users\lukeo\Desktop\TomCraft-Server\}; M15 lets the
 * config UI override every field at runtime.
 */
public final class ServerLaunchSpec {

    public final Path workingDir;
    public final String javaPath;
    public final List<String> jvmArgs;
    public final String jarName;
    public final List<String> mainArgs;

    public ServerLaunchSpec(Path workingDir,
                            String javaPath,
                            List<String> jvmArgs,
                            String jarName,
                            List<String> mainArgs) {
        this.workingDir = workingDir;
        this.javaPath = javaPath;
        this.jvmArgs = Collections.unmodifiableList(new ArrayList<>(jvmArgs));
        this.jarName = jarName;
        this.mainArgs = Collections.unmodifiableList(new ArrayList<>(mainArgs));
    }

    /**
     * OS-aware defaults. On Windows, mirror TomCraft-Server/start.bat. On
     * macOS/Linux, return placeholder paths that won't crash autoStart —
     * the user must edit %LOCALAPPDATA%/FatherEye/config.json (or the
     * equivalent under {@code ~/FatherEye/config.json} on Mac/Linux)
     * before first launch. {@link io.fathereye.panel.config.AppConfig.ServerRuntime#autoStart}
     * also defaults to {@code false} on non-Windows so the panel doesn't
     * try to spawn a process at the placeholder java path.
     */
    public static ServerLaunchSpec defaults() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        if (windows) {
            return new ServerLaunchSpec(
                    Paths.get("C:\\Users\\lukeo\\Desktop\\TomCraft-Server"),
                    "C:\\Program Files\\Eclipse Adoptium\\jdk-8.0.482.8-hotspot\\bin\\java.exe",
                    Arrays.asList(
                            "-Xmx8G", "-Xms4G",
                            "-XX:+UseG1GC",
                            "-XX:+PrintGCDetails", "-XX:+PrintGCDateStamps", "-XX:+PrintGCTimeStamps",
                            "-Xloggc:logs/gc.log",
                            "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=crash-reports/",
                            "-XX:G1HeapRegionSize=4M",
                            "-XX:MaxGCPauseMillis=100",
                            "-XX:+ParallelRefProcEnabled",
                            "-Xbootclasspath/a:server-client-stubs.jar",
                            "-noverify"
                    ),
                    "forge-1.16.5-36.2.39.jar",
                    Collections.singletonList("nogui")
            );
        }
        // Non-Windows: placeholder paths. The launcher will refuse to run
        // these (process start fails with "no such file"), but the failure
        // is caught and shown in the panel rather than crashing autoStart.
        String home = System.getProperty("user.home", ".");
        return new ServerLaunchSpec(
                Paths.get(home, "TomCraft-Server"),
                "/usr/bin/java",
                Arrays.asList("-Xmx4G", "-Xms2G", "-XX:+UseG1GC"),
                "forge-1.16.5-36.2.39.jar",
                Collections.singletonList("nogui")
        );
    }

    public List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.addAll(mainArgs);
        return cmd;
    }

    /**
     * Pnl-35 (2026-04-26): build a ServerLaunchSpec from the panel's
     * resolved {@link io.fathereye.panel.config.AppConfig} so the launcher
     * actually uses the runtime-detected workingDir (Pnl-34 cwd auto-
     * detection) rather than the hardcoded {@link #defaults()}. Splits
     * the config's space-separated jvmArgs / mainArgs strings into a
     * proper list.
     */
    public static ServerLaunchSpec fromConfig(io.fathereye.panel.config.AppConfig cfg) {
        if (cfg == null || cfg.serverRuntime == null) return defaults();
        io.fathereye.panel.config.AppConfig.ServerRuntime sr = cfg.serverRuntime;
        return new ServerLaunchSpec(
                Paths.get(sr.workingDir),
                sr.javaPath,
                splitArgs(sr.jvmArgs),
                sr.jarName,
                splitArgs(sr.mainArgs));
    }

    private static List<String> splitArgs(String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        // Simple whitespace split — none of our default jvmArgs contain
        // spaces inside a single argument. If a future user adds a path
        // arg with spaces they can quote it; we just split unquoted ws.
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        return Arrays.asList(trimmed.split("\\s+"));
    }
}
