package io.fathereye.setup;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Setup-side copy of {@code io.fathereye.panel.util.PlatformPaths}.
 * The setup module deliberately depends on nothing from panel/ or
 * bridge/ so it can ship as a small standalone .app, hence the
 * duplication.
 */
public final class SetupPaths {

    public static final String APP_NAME = "FatherEye";

    private SetupPaths() {}

    public static Path appDataDir() {
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
}
