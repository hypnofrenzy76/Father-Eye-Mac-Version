package io.fathereye.webportal;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.webportal.ipc.PipeCodecs;
import io.fathereye.webportal.ipc.PlatformPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Read-only view over the shared Father Eye {@code config.json} (the same
 * file the JavaFX panel and bridge use, under the {@link PlatformPaths}
 * AppData root). The web portal only needs three things from it for the
 * backup/rollback subsystem:
 *
 * <ul>
 *   <li>{@code serverRuntime.workingDir} — the Minecraft server folder.</li>
 *   <li>{@code backup.externalBackupDir} — where compressed backups live
 *       (defaults to the external "Server Backups" volume).</li>
 *   <li>{@code backup.scheduleMinutes} / retention knobs — surfaced for the
 *       Backups tab, though the schedule itself is driven panel-side.</li>
 * </ul>
 *
 * <p>The config is re-read on demand (cheap, a few KB) so an operator can
 * edit it without restarting the portal. Missing keys fall back to the
 * documented defaults so a stripped-down config never breaks backups.
 */
public final class PortalConfig {

    /** Default external compressed-backup directory on this Mac. */
    public static final String DEFAULT_EXTERNAL_BACKUP_DIR =
            "/Volumes/Server Backups/backups";
    /** Default server folder if the config is absent. */
    public static final String DEFAULT_SERVER_DIR =
            System.getProperty("user.home", ".") + "/Desktop/Server";

    private PortalConfig() {}

    private static Path configPath() {
        return PlatformPaths.appDataDir().resolve("config.json");
    }

    private static JsonNode read() {
        try {
            Path p = configPath();
            if (!Files.isRegularFile(p)) return null;
            return PipeCodecs.JSON.readTree(Files.readAllBytes(p));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The Minecraft server working directory. Never null. */
    public static Path serverDir() {
        JsonNode root = read();
        if (root != null) {
            JsonNode v = root.path("serverRuntime").path("workingDir");
            if (v.isTextual() && !v.asText().isBlank()) {
                return Paths.get(v.asText());
            }
        }
        return Paths.get(DEFAULT_SERVER_DIR);
    }

    /**
     * The external compressed-backup directory. Prefers an explicit
     * {@code backup.externalBackupDir} key; otherwise the default external
     * volume path. Never null.
     */
    public static Path externalBackupDir() {
        JsonNode root = read();
        if (root != null) {
            JsonNode v = root.path("backup").path("externalBackupDir");
            if (v.isTextual() && !v.asText().isBlank()) {
                return Paths.get(v.asText());
            }
        }
        return Paths.get(DEFAULT_EXTERNAL_BACKUP_DIR);
    }

    /** Age-based retention in days (default 14, 0 = keep forever). */
    public static int retainDays() {
        JsonNode root = read();
        if (root != null) {
            JsonNode v = root.path("backup").path("retainDays");
            if (v.isInt() || v.isLong()) return Math.max(0, v.asInt());
        }
        return 14;
    }

    /** Minimum free GB to keep on the external drive (default 60). */
    public static int minFreeGb() {
        JsonNode root = read();
        if (root != null) {
            JsonNode v = root.path("backup").path("minFreeGb");
            if (v.isInt() || v.isLong()) return Math.max(0, v.asInt());
        }
        return 60;
    }
}
