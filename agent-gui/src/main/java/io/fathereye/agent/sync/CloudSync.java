package io.fathereye.agent.sync;

import io.fathereye.agent.session.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * iCloud Drive based cross-machine sync.
 *
 * <p>Moves both <i>Claude Code's local session storage</i>
 * ({@code ~/.claude/projects}) and <i>this app's conversation list</i>
 * ({@code ~/Library/Application Support/Claude for High Sierra/conversations})
 * into a folder on iCloud Drive, then replaces the original paths with
 * symlinks pointing at the iCloud copy. Once iCloud has uploaded the
 * folder, the same files appear on every Mac signed into the same
 * iCloud account — so clicking an old conversation on a second Mac
 * actually finds the Claude Code session JSONL it needs to
 * {@code --resume}.
 *
 * <p>Caveats: relies on the user having iCloud Drive enabled. The
 * Claude Code project-hash directory layout includes a hash of the cwd
 * path, so a session started in {@code ~/dev/repo} on one Mac will
 * only resume on another Mac if the same path exists there. Concurrent
 * editing from two Macs running the app at the same time is undefined
 * (iCloud may produce {@code Conflicted Copy} files).
 */
public final class CloudSync {

    private static final Logger LOG = LoggerFactory.getLogger(CloudSync.class);
    private static final String ICLOUD_REL = "Library/Mobile Documents/com~apple~CloudDocs";
    private static final String SYNC_FOLDER = "Claude for High Sierra Sync";

    public enum State { ENABLED, DISABLED, NO_ICLOUD }

    public Path iCloudRoot() {
        return Paths.get(System.getProperty("user.home"), ICLOUD_REL);
    }

    public Path syncFolder() {
        return iCloudRoot().resolve(SYNC_FOLDER);
    }

    public boolean iCloudAvailable() {
        return Files.isDirectory(iCloudRoot());
    }

    public State state() {
        if (!iCloudAvailable()) return State.NO_ICLOUD;
        Path claudeProjects = Paths.get(System.getProperty("user.home"), ".claude/projects");
        if (!Files.isSymbolicLink(claudeProjects)) return State.DISABLED;
        try {
            Path target = Files.readSymbolicLink(claudeProjects);
            String t = target.toString();
            return t.contains(SYNC_FOLDER) ? State.ENABLED : State.DISABLED;
        } catch (IOException e) {
            return State.DISABLED;
        }
    }

    public Path claudeProjectsLocal() {
        return Paths.get(System.getProperty("user.home"), ".claude/projects");
    }

    public Path conversationsLocal() {
        return ConversationStore.appSupportDir().resolve("conversations");
    }

    /** Symlink the local Claude Code + app paths into iCloud. Idempotent. */
    public boolean enable(Consumer<String> progress) {
        if (!iCloudAvailable()) {
            progress.accept("iCloud Drive isn't available. Enable iCloud Drive in System Settings → Apple ID → iCloud, then try again.");
            return false;
        }
        try {
            Path sync = syncFolder();
            Files.createDirectories(sync);
            progress.accept("Sync folder: " + sync);

            migrate(claudeProjectsLocal(),
                    sync.resolve("claude-sessions"),
                    "Claude Code session history (~/.claude/projects)",
                    progress);
            migrate(conversationsLocal(),
                    sync.resolve("app-conversations"),
                    "App conversation list",
                    progress);

            progress.accept("Sync enabled. iCloud will upload over the next few minutes; once it finishes, the same sessions appear on any Mac signed into this iCloud account.");
            return true;
        } catch (IOException e) {
            LOG.error("enable sync failed", e);
            progress.accept("Failed: " + e.getMessage());
            return false;
        }
    }

    /** Reverse the symlink: copy iCloud content back to the local path,
     *  remove the symlink, restore a plain local directory. The iCloud
     *  copy is left in place so other Macs aren't affected. */
    public boolean disable(Consumer<String> progress) {
        try {
            unlink(claudeProjectsLocal(),
                    syncFolder().resolve("claude-sessions"),
                    "Claude Code session history",
                    progress);
            unlink(conversationsLocal(),
                    syncFolder().resolve("app-conversations"),
                    "App conversation list",
                    progress);
            progress.accept("Sync disabled. Local copies restored. The iCloud sync folder is preserved — re-enabling will pick it up.");
            return true;
        } catch (IOException e) {
            LOG.error("disable sync failed", e);
            progress.accept("Failed: " + e.getMessage());
            return false;
        }
    }

    // ----- internals ---------------------------------------------------

    private static void migrate(Path local, Path cloud, String label,
                                Consumer<String> progress) throws IOException {
        progress.accept("Migrating " + label + "…");
        if (Files.isSymbolicLink(local)) {
            Path target = Files.readSymbolicLink(local);
            if (target.toAbsolutePath().equals(cloud.toAbsolutePath())) {
                progress.accept("  already linked to iCloud — skipping.");
                return;
            }
            // Existing symlink points elsewhere — leave it alone, surface the conflict.
            progress.accept("  WARNING: " + local + " is already a symlink to " + target
                    + ". Skipping to avoid data loss.");
            return;
        }
        boolean cloudExists = Files.isDirectory(cloud);
        boolean localExists = Files.isDirectory(local);
        if (cloudExists && localExists) {
            progress.accept("  iCloud + local both have content — merging local into iCloud…");
            mergeInto(local, cloud);
            deleteTree(local);
        } else if (localExists) {
            progress.accept("  copying local → iCloud…");
            Files.createDirectories(cloud.getParent());
            copyTree(local, cloud);
            deleteTree(local);
        } else {
            // Nothing local; just make sure the iCloud folder exists.
            Files.createDirectories(cloud);
        }
        Files.createDirectories(local.getParent());
        Files.createSymbolicLink(local, cloud);
        progress.accept("  symlink: " + local + " → " + cloud);
    }

    private static void unlink(Path local, Path cloud, String label,
                               Consumer<String> progress) throws IOException {
        progress.accept("Restoring " + label + "…");
        if (!Files.isSymbolicLink(local)) {
            progress.accept("  not a sync symlink, leaving alone.");
            return;
        }
        // Replace the symlink with a real directory holding a copy of
        // iCloud's contents. Don't touch iCloud (other Macs may still
        // be using it).
        Files.delete(local);
        if (Files.isDirectory(cloud)) {
            copyTree(cloud, local);
        } else {
            Files.createDirectories(local);
        }
        progress.accept("  restored: " + local);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                Path target = dst.resolve(src.relativize(dir).toString());
                if (!Files.exists(target)) Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Path target = dst.resolve(src.relativize(file).toString());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void mergeInto(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                Path target = dst.resolve(src.relativize(dir).toString());
                if (!Files.exists(target)) Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Path target = dst.resolve(src.relativize(file).toString());
                if (!Files.exists(target)) {
                    Files.copy(file, target);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
