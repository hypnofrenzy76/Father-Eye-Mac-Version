package io.fathereye.panel.launcher;

import io.fathereye.panel.util.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Compressed external backup driver.
 *
 * <p>Web-03/Pnl-74 (2026-06-14): rewritten. Previously this class copied the
 * world directory uncompressed into {@code <backupDir>/world-<ts>/} on the
 * internal disk. That produced multi-GB uncompressed copies (an 18 GB one
 * was found in the wild) and was the source of the disk-fill problem.
 *
 * <p>It now shells out to the bundled {@code fe-backup.sh} script, which
 * streams {@code tar | gzip} directly to the external drive as three
 * separate archives (world, playerdata, server) plus a {@code manifest.json},
 * with an RCON save-off / save-all flush / save-on bracket and built-in
 * age + free-space retention. No uncompressed intermediate is ever written.
 * The same script powers the web portal's manual backups and rollback list,
 * so both surfaces produce an identical, interchangeable backup format.
 *
 * <p>The public API ({@link #runBackup()}, {@link #sweepStalePartials()},
 * {@link #fromConfig}) is unchanged so the existing App.java wiring
 * (pre-stop, hourly scheduler) keeps working with no call-site changes.
 */
public final class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Backup");

    /** Default external destination if none configured. */
    public static final String DEFAULT_EXTERNAL_DIR = "/Volumes/Server Backups/backups";

    private final Path serverDir;
    private final Path externalBackupDir;
    private final int retainDays;
    private final int minFreeGb;

    public BackupService(Path serverDir, Path externalBackupDir, int retainDays, int minFreeGb) {
        this.serverDir = serverDir;
        this.externalBackupDir = externalBackupDir;
        this.retainDays = retainDays;
        this.minFreeGb = minFreeGb;
    }

    /**
     * Create one compressed backup on the external drive. Returns the
     * backup directory path on success, or {@code null} if it was skipped
     * (no destination configured / external volume not mounted).
     *
     * @throws IOException if the backup script fails (non-zero exit).
     */
    public Path runBackup() throws IOException {
        return runBackup(null);
    }

    /** As {@link #runBackup()} with an optional human label. */
    public Path runBackup(String label) throws IOException {
        if (externalBackupDir == null || externalBackupDir.toString().isEmpty()) {
            LOG.warn("backup skipped: externalBackupDir is not configured");
            return null;
        }
        // The external volume must be mounted: its parent (the volume mount
        // point) must exist as a directory. Writing to an unmounted-volume
        // stub on the internal disk is exactly what we are eliminating.
        Path mountPoint = externalBackupDir.getParent();
        if (mountPoint != null && !Files.isDirectory(mountPoint)) {
            LOG.warn("backup skipped: external volume not mounted ({})", mountPoint);
            return null;
        }
        Path script = ensureScript();
        List<String> cmd = new ArrayList<>(List.of(
                "/bin/bash", script.toString(),
                "--server", serverDir.toString(),
                "--dest", externalBackupDir.toString(),
                "--retain-days", String.valueOf(retainDays),
                "--min-free-gb", String.valueOf(minFreeGb)));
        if (label != null && !label.isBlank()) { cmd.add("--label"); cmd.add(label); }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String lastLine = "";
        String backupDir = null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastLine = line;
                // The script emits the result path with an unambiguous
                // marker; don't rely on the last line, since stderr (merged
                // in via redirectErrorStream) can interleave after it.
                if (line.startsWith("FE_BACKUP_DIR=")) {
                    backupDir = line.substring("FE_BACKUP_DIR=".length()).trim();
                }
                LOG.debug("[fe-backup] {}", line);
            }
        } finally {
            // Always reap the child even if reading threw, so we never leak
            // a zombie process or leave waitFor unreachable.
            try {
                int rc = p.waitFor();
                if (rc != 0) {
                    throw new IOException("backup script exit " + rc + " (" + lastLine + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new IOException("backup interrupted");
            }
        }
        LOG.info("Backup completed: {}", backupDir != null ? backupDir : lastLine);
        return backupDir != null ? Paths.get(backupDir) : null;
    }

    /**
     * Remove any {@code *.partial} directories the script may have left on
     * the external drive after an interrupted run. The script cleans up its
     * own partial on failure, so this is a defensive backstop only.
     */
    public void sweepStalePartials() {
        if (externalBackupDir == null || !Files.isDirectory(externalBackupDir)) return;
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        try (var kids = Files.list(externalBackupDir)) {
            kids.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith(".partial"))
                    .filter(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis() < cutoff; }
                        catch (IOException e) { return false; }
                    })
                    .forEach(p -> {
                        try { deleteTree(p); LOG.info("Removed stale partial backup: {}", p.getFileName()); }
                        catch (IOException e) { LOG.warn("partial cleanup {} failed: {}", p, e.getMessage()); }
                    });
        } catch (IOException e) {
            LOG.warn("partial sweep failed: {}", e.getMessage());
        }
    }

    // ---- script extraction --------------------------------------------

    private static volatile Path cachedScript;

    private Path ensureScript() throws IOException {
        Path c = cachedScript;
        if (c != null && Files.isRegularFile(c)) return c;
        synchronized (BackupService.class) {
            if (cachedScript != null && Files.isRegularFile(cachedScript)) return cachedScript;
            Path dir = PlatformPaths.appDataDir().resolve("scripts");
            Files.createDirectories(dir);
            Path target = dir.resolve("fe-backup.sh");
            try (InputStream in = BackupService.class.getResourceAsStream("/scripts/fe-backup.sh")) {
                if (in == null) throw new IOException("bundled fe-backup.sh missing from panel jar");
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ);
                Files.setPosixFilePermissions(target, perms);
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX FS; bash is invoked explicitly so +x is optional.
            }
            cachedScript = target;
            return target;
        }
    }

    private void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(
                    Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    public static BackupService fromConfig(io.fathereye.panel.config.AppConfig cfg) {
        String ext = cfg.backup.externalBackupDir;
        if (ext == null || ext.isEmpty()) ext = DEFAULT_EXTERNAL_DIR;
        return new BackupService(
                Paths.get(cfg.serverRuntime.workingDir),
                Paths.get(ext),
                cfg.backup.retainDays,
                cfg.backup.minFreeGb);
    }
}
