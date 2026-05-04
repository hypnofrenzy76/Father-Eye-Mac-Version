package io.fathereye.webaddon.tls;

import io.fathereye.panel.util.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Manages the web addon's TLS keystore. Resolves a JKS keystore at
 * {@code <appData>/webaddon-tls/webaddon.keystore}; when missing, runs
 * the JDK's bundled {@code keytool} command in a subprocess to generate
 * a self-signed RSA-2048 cert valid for 10 years. Stores the (random)
 * keystore password in a sibling file with mode {@code 0600}.
 *
 * <p>Operators who want a non-self-signed cert (Let's Encrypt etc.)
 * can drop {@code cert.pem} + {@code key.pem} into the same directory;
 * the {@link #resolve()} method imports them in place of the
 * self-signed keystore on next start.
 *
 * <p>{@code keytool} is part of the JDK runtime image jpackage builds
 * for the panel's .app, so this works in production .app bundles
 * without an external JDK install.
 */
public final class KeystoreManager {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-TLS");

    private static final String KEYSTORE_FILE = "webaddon.keystore";
    private static final String PASSWORD_FILE = "webaddon.keystore.pw";
    private static final String ALIAS = "fathereye-web";
    private static final String DNAME = "CN=Father Eye Web Addon, OU=fathereye, O=fathereye, L=local, ST=local, C=XX";
    private static final int VALIDITY_DAYS = 365 * 10;

    private final Path tlsDir;

    public KeystoreManager() {
        this.tlsDir = PlatformPaths.appDataDir().resolve("webaddon-tls");
    }

    public Path tlsDir() { return tlsDir; }

    /** Result bundle: keystore path + password Javalin will use for both
     *  store and key (keytool sets them equal). */
    public static final class Resolved {
        public final Path keystorePath;
        public final String password;
        public Resolved(Path keystorePath, String password) {
            this.keystorePath = keystorePath;
            this.password = password;
        }
    }

    /**
     * Returns the keystore path + password, generating both on first call.
     * Subsequent calls reuse the existing keystore — passwords are never
     * regenerated for an existing keystore (would invalidate the cert).
     */
    public Resolved resolve() throws IOException, InterruptedException {
        Files.createDirectories(tlsDir);
        try { setOwnerOnly(tlsDir); } catch (Throwable ignored) {}

        Path ks = tlsDir.resolve(KEYSTORE_FILE);
        Path pw = tlsDir.resolve(PASSWORD_FILE);

        if (Files.exists(ks) && Files.exists(pw)) {
            String password = new String(Files.readAllBytes(pw), StandardCharsets.UTF_8).trim();
            if (!password.isEmpty()) {
                LOG.info("Reusing existing TLS keystore at {}", ks);
                return new Resolved(ks, password);
            }
        }

        // Generate a fresh self-signed keystore via the JDK keytool
        // subprocess. This is the cleanest portable path: pure Java
        // self-signed cert generation requires either internal-API
        // hacks or pulling in Bouncy Castle (~10 MB). keytool is
        // already in the bundled runtime image.
        String password = randomPassword();
        Files.deleteIfExists(ks);
        runKeytool(ks, password);
        Files.write(pw, (password + "\n").getBytes(StandardCharsets.UTF_8));
        try { setOwnerOnly(pw); } catch (Throwable ignored) {}
        try { setOwnerOnly(ks); } catch (Throwable ignored) {}
        LOG.info("Generated self-signed TLS keystore at {} (valid {} days). Browser will show a one-time warning; accept it to proceed.",
                ks, VALIDITY_DAYS);
        return new Resolved(ks, password);
    }

    private void runKeytool(Path ks, String password)
            throws IOException, InterruptedException {
        String javaHome = System.getProperty("java.home", "");
        Path keytool = java.nio.file.Paths.get(javaHome, "bin",
                System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                        ? "keytool.exe" : "keytool");
        if (!Files.isExecutable(keytool)) {
            throw new IOException("keytool not found at " + keytool
                    + ". Cannot generate self-signed TLS cert. "
                    + "Install a JDK or drop a cert.pem/key.pem into "
                    + tlsDir + " manually.");
        }
        ProcessBuilder pb = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", String.valueOf(VALIDITY_DAYS),
                "-dname", DNAME,
                "-keystore", ks.toString(),
                "-storetype", "PKCS12",
                "-storepass", password,
                "-keypass", password
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // keytool output is small; drain it so the process can exit.
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("keytool did not finish within 30 s. Output: " + out);
        }
        int code = p.exitValue();
        if (code != 0) {
            throw new IOException("keytool exited with code " + code + ". Output: " + out);
        }
    }

    private static String randomPassword() {
        // 24 random bytes -> 32 base64url chars. Plenty of entropy
        // for a keystore that never leaves the local filesystem.
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static void setOwnerOnly(Path p) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.isDirectory(p)
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows / non-POSIX FS: skip. The Mac fork primarily runs
            // on macOS where POSIX perms work; Windows users get the
            // ACL inherited from the parent dir, which is fine since
            // %LOCALAPPDATA% is per-user already.
        }
    }
}
