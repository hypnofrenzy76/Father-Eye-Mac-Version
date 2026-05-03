package io.fathereye.agent.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GitHub authentication helper. Wraps the {@code gh} CLI if available
 * (most reliable path on macOS — handles OAuth via browser, stores token
 * in Keychain, configures git's credential helper). Falls back to
 * checking for SSH keys / git credential storage so the UI can tell the
 * user what's already wired up before sending them through a flow.
 */
public final class GitHubAuth {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubAuth.class);

    public enum State {
        /** {@code gh auth status} reports a logged-in user. */
        SIGNED_IN_GH,
        /** No {@code gh}, but an SSH key is present at {@code ~/.ssh/id_*}. */
        SSH_KEYS,
        /** {@code git config --global credential.helper} is set (osxkeychain or store). */
        CREDENTIAL_HELPER,
        /** None of the above. */
        UNCONFIGURED,
        /** {@code gh} is not installed. */
        NO_GH_CLI
    }

    public record Status(State state, String detail) {}

    /** Fast inspection — runs in the background. */
    public Status inspect() {
        // Prefer `gh` if installed.
        Result gh = run(5, "gh", "auth", "status", "-h", "github.com");
        if (gh.exit == 0 && gh.output.contains("Logged in to github.com")) {
            String account = parseGhAccount(gh.output);
            return new Status(State.SIGNED_IN_GH,
                    account == null ? "Signed in via gh CLI" : "Signed in as " + account);
        }
        if (gh.exit == 127 || gh.output.contains("command not found")) {
            // Try the next signal.
            return fallbackInspect(true);
        }
        return fallbackInspect(false);
    }

    private Status fallbackInspect(boolean noGh) {
        String home = System.getProperty("user.home");
        for (String key : new String[] { "id_ed25519.pub", "id_rsa.pub", "id_ecdsa.pub" }) {
            if (new java.io.File(home + "/.ssh/" + key).exists()) {
                return new Status(State.SSH_KEYS, "SSH key: ~/.ssh/" + key);
            }
        }
        Result helper = run(3, "git", "config", "--global", "credential.helper");
        if (helper.exit == 0 && !helper.output.trim().isEmpty()) {
            return new Status(State.CREDENTIAL_HELPER,
                    "git credential.helper = " + helper.output.trim());
        }
        return new Status(noGh ? State.NO_GH_CLI : State.UNCONFIGURED,
                noGh ? "GitHub CLI (`gh`) is not installed."
                     : "No SSH key or git credential helper found.");
    }

    /**
     * Run {@code gh auth login --web -h github.com -p https}. The {@code gh}
     * CLI prints a one-time code, opens the browser, and writes the token
     * into the macOS Keychain when the OAuth flow completes. Returns when
     * the subprocess exits.
     */
    public boolean runGhLogin() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "gh", "auth", "login", "--web", "--hostname", "github.com",
                "--git-protocol", "https");
        pb.redirectErrorStream(true);
        // Inherit stdin so gh's interactive prompts (like "Press Enter to
        // continue") are visible if a terminal is attached. From a
        // Finder-launched .app there's no terminal, so the prompt blocks
        // -- to avoid that, we rely on --web making the flow non-interactive
        // after the device-code page in the browser.
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[2048];
            int n;
            StringBuilder sb = new StringBuilder();
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() < 2048) LOG.info("[gh auth] {}", sb);
            }
        }
        boolean done = p.waitFor(300, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return false; }
        return p.exitValue() == 0;
    }

    public boolean signOut() {
        Result r = run(10, "gh", "auth", "logout", "-h", "github.com");
        return r.exit == 0;
    }

    // -------------------------------------------------------------------

    private record Result(int exit, String output) {}

    private static Result run(int timeoutSeconds, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return new Result(124, out); }
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            // Most often: 'gh' not installed -> file-not-found.
            return new Result(127, "command not found: " + args[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, e.toString());
        }
    }

    private static String parseGhAccount(String output) {
        // gh auth status output:
        //   ✓ Logged in to github.com as some-user (oauth_token)
        for (String line : output.split("\\R")) {
            int idx = line.indexOf(" as ");
            if (idx >= 0) {
                String tail = line.substring(idx + 4).trim();
                int paren = tail.indexOf(' ');
                return paren < 0 ? tail : tail.substring(0, paren);
            }
        }
        return null;
    }
}
