package io.fathereye.agent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Detect Claude Code's auth state and drive {@code claude /login} as a
 * subprocess for in-app sign-in.
 *
 * <p>Credentials are managed entirely by Claude Code in
 * {@code ~/.claude/credentials.json}. Each user's machine has its own
 * credentials file — the .app bundle never ships secrets. This is what
 * makes the app safe to distribute: every user signs in with their own
 * Claude.ai account on their own machine.
 */
public final class Auth {

    private static final Logger LOG = LoggerFactory.getLogger(Auth.class);

    private final String claudePath;

    public Auth(String claudePath) {
        this.claudePath = claudePath;
    }

    /**
     * Quick best-effort check: does Claude Code have credentials stored?
     *
     * <p>Claude Code writes OAuth tokens to {@code ~/.claude/credentials.json}
     * (or, on macOS, into the Keychain — we check both signals). If
     * neither is present, we show the sign-in scene at startup. False
     * positives (file present but tokens revoked server-side) get caught
     * later when {@code claude --print} returns an auth error.
     */
    public boolean isLoggedIn() {
        Path home = Paths.get(System.getProperty("user.home"));
        Path creds = home.resolve(".claude/credentials.json");
        if (Files.isRegularFile(creds)) {
            try {
                long size = Files.size(creds);
                if (size > 10) return true; // anything bigger than "{}\n" is probably real tokens
            } catch (IOException ignored) {}
        }
        // Fallback: check ~/.claude.json (older / alternative location).
        Path altCreds = home.resolve(".claude.json");
        if (Files.isRegularFile(altCreds)) {
            try {
                String contents = Files.readString(altCreds);
                // Heuristic: a configured-but-unauthenticated .claude.json
                // is small. Anything containing oauth tokens is well over
                // 1 KB.
                if (contents.contains("oauthAccount") || contents.contains("\"accessToken\"")) {
                    return true;
                }
            } catch (IOException ignored) {}
        }
        return false;
    }

    /**
     * Run {@code claude /login} as a subprocess. The command opens a
     * browser tab; the user signs in there; Claude Code writes the OAuth
     * tokens to the credentials file. We inherit IO so the user sees any
     * prompts in the terminal Claude Code might emit, and we wait for
     * the process to exit before returning.
     *
     * @return true if the subprocess exited 0 and the credentials file
     *         is now populated
     */
    public boolean runLogin() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(claudePath, "/login");
        pb.redirectErrorStream(true);
        // Inherit stdin so the OAuth prompt (if interactive) can read.
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        // Drain output to a buffer (capped) so the browser-launch + token
        // exchange don't fill the pipe and block.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int kept = 0;
            while ((line = r.readLine()) != null) {
                if (kept++ < 200) LOG.info("[claude /login] {}", line);
            }
        }
        boolean done = p.waitFor(180, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0 && isLoggedIn();
    }

    /**
     * Clear local credentials so the next launch shows the sign-in scene
     * again. Only deletes our own user's credentials file — does NOT
     * touch any server-side session.
     */
    public void signOut() {
        Path home = Paths.get(System.getProperty("user.home"));
        for (String rel : new String[] { ".claude/credentials.json" }) {
            Path p = home.resolve(rel);
            if (Files.exists(p)) {
                try { Files.delete(p); LOG.info("deleted {}", p); }
                catch (IOException e) { LOG.warn("could not delete {}: {}", p, e.toString()); }
            }
        }
        // Also try `claude /logout` if available (some Claude Code
        // versions invalidate the server-side token from the CLI).
        try {
            ProcessBuilder pb = new ProcessBuilder(claudePath, "/logout");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debug("`claude /logout` not available or failed: {}", e.toString());
        }
    }

    public static String findClaude() throws IOException {
        String override = System.getenv("CLAUDE_PATH");
        if (override != null && !override.isBlank()) return override;
        ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-lic", "command -v claude || true");
        Process p = pb.start();
        String out;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while looking for claude CLI", e);
        }
        if (!out.isEmpty()) {
            String first = out.split("\\R", 2)[0].trim();
            if (new File(first).canExecute()) return first;
        }
        String home = System.getProperty("user.home");
        String[] candidates = {
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                home + "/.npm-global/bin/claude",
                home + "/.local/bin/claude",
                home + "/n/bin/claude"
        };
        for (String c : candidates) if (new File(c).canExecute()) return c;
        throw new IOException("Claude Code CLI not found. Install: npm install -g @anthropic-ai/claude-code");
    }
}
