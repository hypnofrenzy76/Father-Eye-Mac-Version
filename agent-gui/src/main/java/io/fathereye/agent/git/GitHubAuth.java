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
        // Prefer `gh` if installed. Resolve via login shell (so freshly
        // installed binaries in ~/.local/bin from autoInstall are picked
        // up without needing an app restart) before invoking.
        String gh = findGh();
        if (gh == null) {
            return fallbackInspect(true);
        }
        Result r = run(5, gh, "auth", "status", "-h", "github.com");
        if (r.exit == 0 && r.output.contains("Logged in to github.com")) {
            String account = parseGhAccount(r.output);
            return new Status(State.SIGNED_IN_GH,
                    account == null ? "Signed in via gh CLI" : "Signed in as " + account);
        }
        return fallbackInspect(false);
    }

    /**
     * Resolve {@code gh}'s full path, looking through the user's login-shell
     * PATH and a small set of known install locations (including
     * {@code ~/.local/bin/gh} where {@link #autoInstall} drops it).
     * Returns null if not installed.
     */
    public static String findGh() {
        // 1. Login shell PATH lookup.
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-lic", "command -v gh || true");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            if (!out.isEmpty()) {
                String first = out.split("\\R", 2)[0].trim();
                if (new java.io.File(first).canExecute()) return first;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        // 2. Known install locations.
        String home = System.getProperty("user.home");
        String[] candidates = {
                home + "/.local/bin/gh",
                "/opt/homebrew/bin/gh",
                "/usr/local/bin/gh",
                home + "/.npm-global/bin/gh"
        };
        for (String c : candidates) {
            if (new java.io.File(c).canExecute()) return c;
        }
        return null;
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
        String gh = findGh();
        if (gh == null) return false;
        Result r = run(10, gh, "auth", "logout", "-h", "github.com");
        return r.exit == 0;
    }

    /** True if Homebrew is on the user's PATH. Used by the Settings UI to
     *  pick between {@code brew install gh} and opening cli.github.com. */
    public boolean brewAvailable() {
        Result r = run(3, "/bin/zsh", "-lic", "command -v brew");
        return r.exit == 0 && !r.output.isBlank();
    }

    /** Run {@code brew install gh}. Streams output to a consumer for live
     *  progress display. Returns true on success. */
    public boolean brewInstallGh(java.util.function.Consumer<String> lineSink)
            throws IOException, InterruptedException {
        // -lic so brew's PATH overrides land. Avoids "brew: command not found"
        // from a Finder-launched .app's minimal launchd PATH.
        ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-lic", "brew install gh");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (lineSink != null) lineSink.accept(line);
                LOG.info("[brew install gh] {}", line);
            }
        }
        boolean done = p.waitFor(600, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return false; }
        return p.exitValue() == 0;
    }

    /** Open a URL in the default browser via macOS's {@code open} command. */
    public static void openInBrowser(String url) {
        try {
            new ProcessBuilder("open", url).start();
        } catch (IOException e) {
            LOG.warn("could not open {}: {}", url, e.toString());
        }
    }

    /**
     * One-shot installer: makes sure {@code gh} is on the user's PATH.
     * Streams progress lines to {@code progress} so the caller can
     * render a live install log.
     *
     * <p>Strategy: download the upstream gh release tarball directly to
     * {@code ~/.local/bin/gh}. No Homebrew dependency, no admin
     * password, works on every macOS version we care about — including
     * 10.13.6 High Sierra, which modern Homebrew refuses to install on
     * and modern gh releases (2.45+) refuse to launch on. We pick a
     * pinned gh version per macOS major version:
     * <ul>
     *   <li>10.13 (High Sierra): gh 2.0.0 — last release built with Go
     *       1.17, which still supported 10.13 syscalls.</li>
     *   <li>10.14 (Mojave): gh 2.20.0</li>
     *   <li>10.15 (Catalina): gh 2.40.0</li>
     *   <li>11+ (Big Sur and later): gh 2.60.0 — current stable.</li>
     * </ul>
     *
     * <p>Tarball name pattern (per github.com/cli/cli releases):
     * {@code gh_<ver>_macOS_<arch>.tar.gz} where {@code <arch>} is
     * {@code amd64} for Intel and {@code arm64} for Apple Silicon. High
     * Sierra is Intel-only by definition.
     *
     * @return true if {@code gh} is available after the run
     */
    public boolean autoInstall(java.util.function.Consumer<String> progress)
            throws IOException, InterruptedException {
        if (commandExists("gh")) {
            progress.accept("GitHub CLI is already installed.");
            return true;
        }
        String macVer = detectMacOSVersion();
        String arch = detectArch();
        String ghVer = pickGhVersion(macVer);
        String archLabel = arch.equals("arm64") ? "arm64" : "amd64";
        progress.accept("Detected macOS " + macVer + " (" + archLabel + ").");
        progress.accept("Picking gh " + ghVer + " — the most recent release that runs on this macOS.");

        String url = String.format(
                "https://github.com/cli/cli/releases/download/v%s/gh_%s_macOS_%s.tar.gz",
                ghVer, ghVer, archLabel);
        String home = System.getProperty("user.home");
        java.nio.file.Path workDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "claude-hs-gh-install");
        java.nio.file.Path tarFile = workDir.resolve("gh.tar.gz");
        java.nio.file.Path extractDir = workDir.resolve("extracted");
        java.nio.file.Path localBin = java.nio.file.Paths.get(home, ".local", "bin");
        java.nio.file.Path destBin = localBin.resolve("gh");

        progress.accept("Downloading " + url + " …");
        java.nio.file.Files.createDirectories(workDir);
        java.nio.file.Files.createDirectories(localBin);
        if (java.nio.file.Files.exists(tarFile)) java.nio.file.Files.delete(tarFile);
        boolean dl = streamProcess(progress, 600, "curl", "-fsSL", "-o", tarFile.toString(), url);
        if (!dl) {
            progress.accept("Download failed. Check your internet connection or the version pin.");
            return false;
        }
        progress.accept("Extracting…");
        if (java.nio.file.Files.exists(extractDir)) {
            streamProcess(progress, 30, "rm", "-rf", extractDir.toString());
        }
        java.nio.file.Files.createDirectories(extractDir);
        boolean tar = streamProcess(progress, 60, "tar", "-xzf", tarFile.toString(), "-C", extractDir.toString());
        if (!tar) {
            progress.accept("Extract failed.");
            return false;
        }
        // Tarball layout: gh_<ver>_macOS_<arch>/bin/gh
        String topDir = String.format("gh_%s_macOS_%s", ghVer, archLabel);
        java.nio.file.Path srcGh = extractDir.resolve(topDir).resolve("bin").resolve("gh");
        if (!java.nio.file.Files.isRegularFile(srcGh)) {
            // Fallback: scan the extract dir for a file named "gh".
            try (var s = java.nio.file.Files.walk(extractDir)) {
                srcGh = s.filter(p -> p.getFileName().toString().equals("gh")
                                && java.nio.file.Files.isRegularFile(p))
                        .findFirst().orElse(null);
            }
            if (srcGh == null) {
                progress.accept("Could not find gh binary in extracted archive.");
                return false;
            }
        }
        progress.accept("Installing to " + destBin + " …");
        if (java.nio.file.Files.exists(destBin)) java.nio.file.Files.delete(destBin);
        java.nio.file.Files.copy(srcGh, destBin);
        streamProcess(progress, 5, "chmod", "+x", destBin.toString());

        // Make sure ~/.local/bin is on the user's login PATH so a
        // Finder-launched .app can find gh next time it probes via
        // /bin/zsh -lic. Append once; idempotent.
        ensureLocalBinOnPath(progress, localBin);

        progress.accept("Installed gh " + ghVer + " at " + destBin);
        progress.accept("GitHub CLI installed. ✓");
        return java.nio.file.Files.isRegularFile(destBin) && java.nio.file.Files.isExecutable(destBin);
    }

    private static String detectMacOSVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sw_vers", "-productVersion");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return out.isEmpty() ? "11.0" : out;
        } catch (Exception e) {
            return "11.0";
        }
    }

    private static String detectArch() {
        try {
            ProcessBuilder pb = new ProcessBuilder("uname", "-m");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return "arm64".equals(out) ? "arm64" : "amd64";
        } catch (Exception e) {
            return "amd64";
        }
    }

    private static String pickGhVersion(String macVer) {
        // Parse leading two segments: "10.13.6" -> [10, 13]; "11.7.10" -> [11, 7].
        String[] parts = macVer.split("\\.");
        int major = parts.length > 0 ? safeInt(parts[0], 11) : 11;
        int minor = parts.length > 1 ? safeInt(parts[1], 0) : 0;
        if (major == 10) {
            if (minor <= 13) return "2.0.0";   // High Sierra
            if (minor == 14) return "2.20.0";  // Mojave
            return "2.40.0";                   // Catalina
        }
        // 11 (Big Sur) and later — current stable. Bump as new releases land.
        return "2.60.0";
    }

    private static int safeInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static void ensureLocalBinOnPath(java.util.function.Consumer<String> progress,
                                             java.nio.file.Path localBin) {
        String home = System.getProperty("user.home");
        java.nio.file.Path zprofile = java.nio.file.Paths.get(home, ".zprofile");
        String marker = "# claude-for-high-sierra: add ~/.local/bin to PATH";
        try {
            String existing = java.nio.file.Files.exists(zprofile)
                    ? java.nio.file.Files.readString(zprofile)
                    : "";
            if (existing.contains(marker)) return;
            String append = "\n" + marker + "\nexport PATH=\"" + localBin + ":$PATH\"\n";
            java.nio.file.Files.writeString(zprofile, append,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            progress.accept("Added " + localBin + " to ~/.zprofile PATH.");
        } catch (IOException e) {
            LOG.warn("could not update {}: {}", zprofile, e.toString());
        }
    }

    private static boolean commandExists(String binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-lic", "command -v " + binary);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0 && !out.isBlank() && new java.io.File(out.split("\\R", 2)[0]).canExecute();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean streamProcess(java.util.function.Consumer<String> progress,
                                         int timeoutSeconds, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (progress != null) progress.accept(line);
                LOG.info("[install] {}", line);
            }
        }
        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return false; }
        return p.exitValue() == 0;
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
