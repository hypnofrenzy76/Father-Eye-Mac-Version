package io.fathereye.webportal.ipc;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Father Eye AppData root resolver. Copied from the panel's
 * {@code io.fathereye.panel.util.PlatformPaths} so the web portal resolves
 * the same {@code ~/Library/Application Support/FatherEye} location the
 * bridge writes its marker files into. Kept self-contained (no cross-module
 * dependency) mirroring the panel/bridge "copy per module" convention.
 */
public final class PlatformPaths {

    public static final String APP_NAME = "FatherEye";

    private static volatile Path cachedAppData;

    private PlatformPaths() {}

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
            return Paths.get(home, "AppData", "Local", APP_NAME);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, APP_NAME);
        }
        return Paths.get(home, ".local", "share", APP_NAME);
    }

    public static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.startsWith("mac") || os.contains("darwin") || os.contains("os x");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }
}
