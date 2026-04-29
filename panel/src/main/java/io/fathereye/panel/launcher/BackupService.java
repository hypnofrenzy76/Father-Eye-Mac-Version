package io.fathereye.panel.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pre-stop world backup. Copies the world directory (and optionally configs +
 * mods) to a timestamped folder under the configured backup root, then
 * applies retention to keep at most N copies.
 */
public final class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Backup");
    /** Pnl-54 (audit fix, 2026-04-27): switched from SimpleDateFormat
     *  (mutable, not thread-safe) to DateTimeFormatter (immutable,
     *  thread-safe). Pre-Pnl-54 the only caller was the pre-stop path
     *  so the race was latent; Pnl-54 introduced an hourly executor
     *  that could collide with a manual Stop. Three audits flagged
     *  the race independently. Millisecond precision in the folder
     *  name disambiguates same-second collisions between two panel
     *  instances (or hourly + pre-stop firing within the same
     *  second). */
    private static final DateTimeFormatter TS_FOLDER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    /** Pnl-54: parsing variant that tolerates the legacy
     *  "yyyyMMdd-HHmmss" format too, so retention sweeps work on
     *  pre-Pnl-54 backup folders. */
    private static final DateTimeFormatter TS_LEGACY =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path serverDir;
    private final Path backupRoot;
    private final int retainCount;
    private final boolean includeConfigs;
    private final boolean includeMods;
    /** Pnl-54 (2026-04-27): age-based retention in days. Backups
     *  older than this many days are deleted after every backup.
     *  Zero or negative disables age-based retention; only
     *  retainCount applies. The user requested 14-day retention. */
    private final int retainDays;

    public BackupService(Path serverDir, Path backupRoot, int retainCount,
                         boolean includeConfigs, boolean includeMods) {
        this(serverDir, backupRoot, retainCount, includeConfigs, includeMods, 0);
    }

    public BackupService(Path serverDir, Path backupRoot, int retainCount,
                         boolean includeConfigs, boolean includeMods, int retainDays) {
        this.serverDir = serverDir;
        this.backupRoot = backupRoot;
        this.retainCount = retainCount;
        this.includeConfigs = includeConfigs;
        this.includeMods = includeMods;
        this.retainDays = retainDays;
    }

    public Path runBackup() throws IOException {
        Files.createDirectories(backupRoot);
        // Pnl-54 (audit fix): DateTimeFormatter is immutable / thread-
        // safe so concurrent hourly + pre-stop runs cannot corrupt
        // the format buffer. Millisecond precision so two backups
        // firing within the same second produce distinct folder
        // names (multi-panel + edge case where hourly tick coincides
        // with a manual Stop click).
        String stamp = TS_FOLDER.format(Instant.now());
        Path target = backupRoot.resolve("world-" + stamp);
        // Pnl-58 (2026-04-27): copy into a "world-<ts>.partial" folder
        // and atomically rename to the final name only after every
        // copyTree succeeds. Without this, a panel crash mid-copy
        // (OOM, power loss, java.exe terminated) left a half-
        // populated "world-<ts>" folder indistinguishable from a
        // valid backup; the next retention sweep enrolled it for
        // retention and a user restoring from it would get half a
        // world. The .partial suffix is filtered from retention and
        // from the timestamp parser so partials never count.
        Path partial = backupRoot.resolve("world-" + stamp + ".partial");
        Files.createDirectories(partial);

        copyTree(serverDir.resolve("world"), partial.resolve("world"));
        if (includeConfigs) copyTree(serverDir.resolve("config"), partial.resolve("config"));
        if (includeMods)    copyTree(serverDir.resolve("mods"), partial.resolve("mods"));

        // Atomic rename within the same filesystem. backupRoot is
        // always one volume per panel session, so ATOMIC_MOVE is
        // safe. The final-name folder is timestamp-unique (ms
        // precision), so a name collision would itself be a bug.
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);

        applyRetention();
        LOG.info("Backup completed: {}", target);
        return target;
    }

    /**
     * Pnl-58 (2026-04-27): delete any "world-*.partial" folders
     * older than 1 hour. Called once per panel start so a partial
     * left from a previous-session crash gets cleaned up. The 1 h
     * grace window ensures we don't delete a partial that another
     * concurrent panel session is currently writing.
     */
    public void sweepStalePartials() {
        if (!Files.isDirectory(backupRoot)) return;
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        try (Stream<Path> kids = Files.list(backupRoot)) {
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

    // (deleteTree already defined later in this class for retention sweeps;
    // sweepStalePartials reuses that helper.)

    private void copyTree(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) return;
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path target = dst.resolve(src.relativize(file).toString());
                String name = file.getFileName().toString();
                // Skip locked / per-session files that can't be safely
                // copied while the server is still running. session.lock is
                // held by Forge for the world's lifetime; *.tmp / *.lock /
                // sqlite WAL files can be torn or in-flight. Levelling all
                // exclusions here lets the rest of the world copy
                // succeed instead of aborting on the first failure.
                if (name.equals("session.lock")
                        || name.endsWith(".lock")
                        || name.endsWith(".tmp")
                        || name.endsWith("-shm")
                        || name.endsWith("-wal")) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException ioe) {
                    // Best-effort: log and continue. A single locked file
                    // shouldn't kill the backup of every other file.
                    LOG.warn("backup skip {} ({})", file, ioe.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.warn("backup visit failed {} ({})", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void applyRetention() {
        try (Stream<Path> kids = Files.list(backupRoot)) {
            List<Path> backups = new ArrayList<>();
            kids.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("world-"))
                    .filter(p -> !p.getFileName().toString().endsWith(".partial"))
                    .forEach(backups::add);
            // Sort newest-first so retainCount keeps the latest N.
            backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());

            // Pnl-54 (2026-04-27): age-based retention. Anything
            // older than retainDays gets deleted regardless of
            // retainCount. The two rules combine: a backup must
            // survive BOTH (be one of the most recent retainCount
            // AND be younger than retainDays) to be kept.
            long ageCutoffMs = retainDays > 0
                    ? System.currentTimeMillis() - (long) retainDays * 24L * 60L * 60L * 1000L
                    : Long.MIN_VALUE; // disabled
            int kept = 0;
            int deletedByCount = 0, deletedByAge = 0;
            for (Path backup : backups) {
                long mtimeMs = backupTimestamp(backup);
                boolean tooOld = retainDays > 0 && mtimeMs < ageCutoffMs;
                boolean overCount = retainCount > 0 && kept >= retainCount;
                if (tooOld || overCount) {
                    try {
                        deleteTree(backup);
                        if (tooOld) deletedByAge++;
                        else deletedByCount++;
                    } catch (IOException ioe) {
                        LOG.warn("retention delete {} failed: {}", backup.getFileName(), ioe.getMessage());
                    }
                } else {
                    kept++;
                }
            }
            if (deletedByAge > 0 || deletedByCount > 0) {
                LOG.info("Retention applied: kept {} backups, deleted {} by age (>{} days), {} by count (>{}).",
                        kept, deletedByAge, retainDays, deletedByCount, retainCount);
            }
        } catch (IOException e) {
            LOG.warn("retention failed: {}", e.getMessage());
        }
    }

    /**
     * Pnl-54 (2026-04-27): parse the timestamp out of a backup
     * folder name "world-yyyyMMdd-HHmmss". Falls back to the
     * folder's mtime on parse failure (e.g. user renamed it). The
     * filename-derived timestamp is preferred because it survives
     * file copies / rsync that would reset mtime.
     */
    private static long backupTimestamp(Path backup) {
        String name = backup.getFileName().toString();
        if (name.startsWith("world-")) {
            String stamp = name.substring("world-".length());
            // Pnl-54 (audit fix): try the new ms-precision format
            // first, then fall back to the legacy "yyyyMMdd-HHmmss"
            // for pre-Pnl-54 folders. DateTimeFormatter is
            // thread-safe so no synchronization required.
            try {
                LocalDateTime ldt = LocalDateTime.parse(stamp, TS_FOLDER);
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignoredNew) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(stamp, TS_LEGACY);
                    return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (Exception ignoredLegacy) {
                    // fall through to mtime
                }
            }
        }
        try {
            return Files.getLastModifiedTime(backup).toMillis();
        } catch (IOException ioe) {
            return 0L;
        }
    }

    private void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    public static BackupService fromConfig(io.fathereye.panel.config.AppConfig cfg) {
        return new BackupService(
                Paths.get(cfg.serverRuntime.workingDir),
                Paths.get(cfg.backup.backupDir),
                cfg.backup.retainCount,
                cfg.backup.includeConfigs,
                cfg.backup.includeMods,
                // Pnl-54: pass the new age-based retention.
                cfg.backup.retainDays);
    }
}
