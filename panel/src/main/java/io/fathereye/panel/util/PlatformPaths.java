package io.fathereye.panel.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Cross-platform Father Eye AppData / config root resolver.
 *
 * <p>Replaces the dozens of inline {@code System.getenv("LOCALAPPDATA")}
 * lookups that hardcoded the upstream Windows convention. On macOS we
 * follow Apple's File System Programming Guide and put per-user
 * application support data under
 * {@code ~/Library/Application Support/<appName>}; on Linux we follow
 * the XDG Base Directory spec; on Windows we keep the existing
 * {@code %LOCALAPPDATA%} convention so a Mac panel and a Windows panel
 * sharing the same source tree both land in the OS-correct location.
 *
 * <p>This is the panel-side copy. The bridge has a parallel
 * {@code io.fathereye.bridge.util.PlatformPaths} with the same logic;
 * keeping a copy in each module avoids cross-module classpath surgery
 * (the panel can't see bridge classes and vice versa, since they ship
 * as independent jars).
 */
public final class PlatformPaths {

    /** Application root directory name. Constant across platforms. */
    public static final String APP_NAME = "FatherEye";

    /** Cached {@link #appDataDir()} result; resolved once per JVM. */
    private static volatile Path cachedAppData;

    private PlatformPaths() {}

    /**
     * Returns the Father Eye AppData root.
     * <ul>
     *   <li><b>macOS:</b> {@code ~/Library/Application Support/FatherEye}</li>
     *   <li><b>Windows:</b> {@code %LOCALAPPDATA%/FatherEye}, or
     *       {@code <user.home>/AppData/Local/FatherEye} if the env var
     *       is missing (rare; happens in some cmd-line spawns)</li>
     *   <li><b>Linux / other:</b> {@code $XDG_DATA_HOME/FatherEye} if
     *       set, otherwise {@code ~/.local/share/FatherEye}</li>
     * </ul>
     * The directory is NOT created here; callers do their own
     * {@code Files.createDirectories} when they actually write into it.
     */
    public static Path appDataDir() {
        Path p = cachedAppData;
        if (p != null) return p;
        synchronized (PlatformPaths.class) {
            if (cachedAppData != null) return cachedAppData;
            cachedAppData = computeAppDataDir();
            return cachedAppData;
        }
    }

    private static Path computeAppDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.startsWith("mac") || os.contains("darwin") || os.contains("os x")) {
            return Paths.get(home, "Library", "Application Support", APP_NAME);
        }
        if (os.startsWith("windows")) {
            String env = System.getenv("LOCALAPPDATA");
            if (env != null && !env.isEmpty()) {
                return Paths.get(env, APP_NAME);
            }
            // Rare fallback: cmd shells without LOCALAPPDATA inherited.
            return Paths.get(home, "AppData", "Local", APP_NAME);
        }
        // Linux / *BSD / unknown: respect XDG, fall back to ~/.local/share.
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, APP_NAME);
        }
        return Paths.get(home, ".local", "share", APP_NAME);
    }

    /** True when the current JVM is running on macOS. */
    public static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.startsWith("mac") || os.contains("darwin") || os.contains("os x");
    }

    /** True when the current JVM is running on Windows. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /** True when the current JVM is running on Linux / a Linux-like Unix. */
    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }
}
