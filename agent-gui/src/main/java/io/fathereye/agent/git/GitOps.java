package io.fathereye.agent.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the {@code git} CLI for clone / pull / push /
 * status. We shell out rather than use a JGit binding so the user's
 * existing SSH/credential setup (ssh-agent, osxkeychain helper) handles
 * auth — no extra dialogs.
 */
public final class GitOps {

    private static final Logger LOG = LoggerFactory.getLogger(GitOps.class);
    private static final long TIMEOUT_SECONDS = 120;

    public record Result(int exit, String output) {
        public boolean ok() { return exit == 0; }
    }

    /** Returns true if {@code dir} is the working tree of a git repo. */
    public static boolean isRepo(Path dir) {
        Result r = run(dir, "rev-parse", "--is-inside-work-tree");
        return r.ok() && r.output.trim().equals("true");
    }

    public static Result clone(String url, Path dest) {
        // dest must not exist or must be empty for clone to succeed.
        try { Files.createDirectories(dest.getParent()); } catch (IOException ignored) {}
        return run(dest.getParent(), "clone", url, dest.getFileName().toString());
    }

    public static Result pull(Path dir) { return run(dir, "pull", "--ff-only"); }

    public static Result push(Path dir) { return run(dir, "push"); }

    public static Result status(Path dir) { return run(dir, "status", "--short", "--branch"); }

    /** Currently-checked-out branch name, or "(detached)". */
    public static String currentBranch(Path dir) {
        Result r = run(dir, "rev-parse", "--abbrev-ref", "HEAD");
        return r.ok() ? r.output.trim() : "(unknown)";
    }

    /** GitHub-flavored remote origin URL, if any. */
    public static String remoteOrigin(Path dir) {
        Result r = run(dir, "config", "--get", "remote.origin.url");
        return r.ok() ? r.output.trim() : "";
    }

    private static Result run(Path dir, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) out.append(buf, 0, n);
            }
            boolean done = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Result(124, out + "\n[timed out after " + TIMEOUT_SECONDS + "s]");
            }
            return new Result(p.exitValue(), out.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("git {} failed: {}", String.join(" ", args), e.toString());
            return new Result(-1, "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
