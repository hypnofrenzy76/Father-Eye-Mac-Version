package io.fathereye.bridge.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Cross-platform Father Eye AppData root resolver (bridge-side copy).
 *
 * <p>Mirrors the panel's {@code io.fathereye.panel.util.PlatformPaths}
 * because bridge and panel ship as independent jars (the bridge runs
 * inside the Forge mod classloader, the panel runs as a JavaFX app)
 * and cannot share classes.
 *
 * <p>Returns:
 * <ul>
 *   <li><b>macOS:</b> {@code ~/Library/Application Support/FatherEye}</li>
 *   <li><b>Windows:</b> {@code %LOCALAPPDATA%/FatherEye} (or
 *       {@code <user.home>/AppData/Local/FatherEye} if unset)</li>
 *   <li><b>Linux / other:</b> {@code $XDG_DATA_HOME/FatherEye} or
 *       {@code ~/.local/share/FatherEye}</li>
 * </ul>
 *
 * <p>The bridge writes a marker file under
 * {@code <appDataDir>/bridges/<instanceUuid>.json} so the panel's
 * MarkerDiscovery can find it. Both sides MUST resolve to the same
 * directory or the panel sees no markers and waits forever.
 */
public final class PlatformPaths {

    public static final String APP_NAME = "FatherEye";

    private static volatile Path cached;

    private PlatformPaths() {}

    public static Path appDataDir() {
        Path p = cached;
        if (p != null) return p;
        synchronized (PlatformPaths.class) {
            if (cached != null) return cached;
            cached = compute();
            return cached;
        }
    }

    private static Path compute() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.startsWith("mac") || os.contains("darwin") || os.contains("os x")) {
            return Paths.get(home, "Library", "Application Support", APP_NAME);
        }
        if (os.startsWith("windows")) {
            String env = System.getenv("LOCALAPPDATA");
            if (env != null && !env.isEmpty()) return Paths.get(env, APP_NAME);
            return Paths.get(home, "AppData", "Local", APP_NAME);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) return Paths.get(xdg, APP_NAME);
        return Paths.get(home, ".local", "share", APP_NAME);
    }

    public static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("mac") || os.contains("darwin") || os.contains("os x");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }
}
