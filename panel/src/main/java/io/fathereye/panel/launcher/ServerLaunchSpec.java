package io.fathereye.panel.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How to launch the server. Mac fork: defaults are placeholders that
 * the Setup wizard fills in on first run. The pre-boot Configure
 * dialog lets the user edit every field at runtime.
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
     * Cross-platform defaults. Mac fork: returns placeholder paths
     * everywhere — the Setup wizard fills the real values into
     * AppConfig on first run. The panel's autoStart is OFF until the
     * Setup wizard succeeds, so we never try to launch an empty path.
     *
     * <p>JVM args mirror Aikar's recommended Forge 1.16.5 G1GC tuning
     * (https://aikar.co/mcflags.html), adapted for a 32 GB Mac with
     * a 12 GB heap. The Setup wizard rewrites these into
     * {@code <serverDir>/user_jvm_args.txt} so Forge's
     * {@code run.sh} picks them up.
     */
    public static ServerLaunchSpec defaults() {
        String home = System.getProperty("user.home", ".");
        return new ServerLaunchSpec(
                Paths.get(home, "Minecraft Server"),
                "",  // empty -> use $JAVA_HOME/bin/java or PATH
                Arrays.asList(
                        "-Xms8G", "-Xmx12G",
                        "-XX:+UseG1GC",
                        "-XX:+ParallelRefProcEnabled",
                        "-XX:MaxGCPauseMillis=200",
                        "-XX:+UnlockExperimentalVMOptions",
                        "-XX:+DisableExplicitGC",
                        "-XX:+AlwaysPreTouch",
                        "-XX:G1NewSizePercent=30",
                        "-XX:G1MaxNewSizePercent=40",
                        "-XX:G1HeapRegionSize=8M",
                        "-XX:G1ReservePercent=20",
                        "-XX:G1HeapWastePercent=5",
                        "-XX:G1MixedGCCountTarget=4",
                        "-XX:InitiatingHeapOccupancyPercent=15",
                        "-XX:G1MixedGCLiveThresholdPercent=90",
                        "-XX:G1RSetUpdatingPauseTimePercent=5",
                        "-XX:SurvivorRatio=32",
                        "-XX:+PerfDisableSharedMem",
                        "-XX:MaxTenuringThreshold=1"
                ),
                "forge-1.16.5-36.2.39.jar",
                Collections.singletonList("nogui")
        );
    }

    public List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaPath());
        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.addAll(mainArgs);
        return cmd;
    }

    /**
     * Resolve the Java binary used to launch the server. If {@link #javaPath}
     * is non-empty AND points at an existing file, use it verbatim. Otherwise
     * fall back through a sequence of standard Mac/Linux/Windows locations.
     *
     * <p>The Setup wizard writes an absolute path into the config on first
     * run, so this fallback is only exercised when the user clears the field
     * or hand-edits the config to an invalid path. Returning {@code "java"}
     * as last resort lets the OS PATH resolver have a shot — the launcher's
     * existing exception handling will surface a clean error if even that
     * fails.
     */
    private String resolveJavaPath() {
        if (javaPath != null && !javaPath.isEmpty()) {
            java.nio.file.Path explicit = Paths.get(javaPath);
            if (java.nio.file.Files.isExecutable(explicit)) return javaPath;
        }
        // $JAVA_HOME (set by Adoptium installer, brew, asdf, jenv).
        String jhome = System.getenv("JAVA_HOME");
        if (jhome != null && !jhome.isEmpty()) {
            String suffix = isWindows() ? "java.exe" : "java";
            java.nio.file.Path p = Paths.get(jhome, "bin", suffix);
            if (java.nio.file.Files.isExecutable(p)) return p.toString();
        }
        // Mac fork (audit 6 BUG 3): scan /Library/Java/JavaVirtualMachines
        // for a JDK 8 install — Forge 1.16.5 requires Java 8 specifically.
        // Returning the first JDK regardless of major version (upstream
        // behaviour) led to a Forge UnsupportedClassVersionError when
        // the user only had Temurin 17 installed. Now we read each
        // candidate's `release` file and only accept JAVA_VERSION="1.8.x".
        if (isMac()) {
            java.nio.file.Path jvms = Paths.get("/Library/Java/JavaVirtualMachines");
            if (java.nio.file.Files.isDirectory(jvms)) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                             java.nio.file.Files.newDirectoryStream(jvms)) {
                    for (java.nio.file.Path entry : stream) {
                        java.nio.file.Path home = entry.resolve("Contents/Home");
                        java.nio.file.Path candidate = home.resolve("bin/java");
                        if (java.nio.file.Files.isExecutable(candidate)
                                && readReleaseMajor(home) == 8) {
                            return candidate.toString();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        // Linux: try /usr/lib/jvm/*/bin/java with the same JDK 8 filter.
        if (isLinux()) {
            java.nio.file.Path jvms = Paths.get("/usr/lib/jvm");
            if (java.nio.file.Files.isDirectory(jvms)) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                             java.nio.file.Files.newDirectoryStream(jvms)) {
                    for (java.nio.file.Path entry : stream) {
                        java.nio.file.Path candidate = entry.resolve("bin/java");
                        if (java.nio.file.Files.isExecutable(candidate)
                                && readReleaseMajor(entry) == 8) {
                            return candidate.toString();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        // Last resort: bare "java", relies on PATH.
        return isWindows() ? "java.exe" : "java";
    }

    /** Mac fork (audit 6): parse the JDK home's `release` file for
     *  JAVA_VERSION and return the major version (8, 11, 17, ...).
     *  Returns -1 on any failure so the caller skips this candidate.
     *  The release file is text:
     *    JAVA_VERSION="1.8.0_402"   (Java 8)
     *    JAVA_VERSION="17.0.13"     (Java 11+)
     */
    private static int readReleaseMajor(java.nio.file.Path home) {
        java.nio.file.Path rel = home.resolve("release");
        if (!java.nio.file.Files.isReadable(rel)) return -1;
        try {
            for (String line : java.nio.file.Files.readAllLines(rel,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.startsWith("JAVA_VERSION=")) continue;
                String v = line.substring("JAVA_VERSION=".length()).trim();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                if (v.startsWith("1.")) {
                    int dot = v.indexOf('.', 2);
                    return Integer.parseInt(v.substring(2, dot < 0 ? v.length() : dot));
                }
                int dot = v.indexOf('.');
                return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.startsWith("mac") || os.contains("darwin") || os.contains("os x");
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
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
