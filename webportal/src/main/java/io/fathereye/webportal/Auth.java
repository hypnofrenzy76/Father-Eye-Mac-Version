package io.fathereye.webportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.webportal.ipc.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authentication for the web portal: a single long operator password,
 * stored only as a salted PBKDF2 hash, plus opaque random session tokens.
 *
 * <p>The password is never stored in clear. On first run, if no credential
 * file exists, the portal reads the password from the {@code FATHEREYE_WEB_PASSWORD}
 * environment variable, or (if that is unset) generates a long random one and
 * prints it to the console exactly once. The hash is written to
 * {@code ~/Library/Application Support/FatherEye/webportal/auth.json}.
 *
 * <p>Login attempts are rate-limited per source address: after
 * {@link #MAX_FAILURES} failures the address is locked out for
 * {@link #LOCKOUT_MS}. All comparisons are constant-time.
 */
public final class Auth {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortal-Auth");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();

    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int GENERATED_PASSWORD_BYTES = 24; // ~32 base64url chars
    private static final int SESSION_TOKEN_BYTES = 32;      // 256-bit opaque token

    /** Session lifetime: 12 hours of inactivity before re-login is required. */
    static final long SESSION_TTL_MS = 12L * 60 * 60 * 1000;

    static final int MAX_FAILURES = 8;
    static final long LOCKOUT_MS = 15L * 60 * 1000;

    private final Path authFile;
    private volatile byte[] salt;
    private volatile byte[] hash;

    // token -> last-seen epoch ms (sliding expiry)
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    // source address -> failure tracker
    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();

    public Auth() {
        Path dir = PlatformPaths.appDataDir().resolve("webportal");
        this.authFile = dir.resolve("auth.json");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException("cannot create webportal config dir: " + dir, e);
        }
        loadOrInitialize();
    }

    private void loadOrInitialize() {
        try {
            if (Files.isRegularFile(authFile)) {
                JsonNode n = JSON.readTree(Files.readAllBytes(authFile));
                this.salt = Base64.getDecoder().decode(n.get("salt").asText());
                this.hash = Base64.getDecoder().decode(n.get("hash").asText());
                LOG.info("Loaded web portal credential from {}", authFile);
                return;
            }
        } catch (Exception e) {
            LOG.warn("Failed to read {}; regenerating credential", authFile, e);
        }
        // First run: establish a password.
        String pw = System.getenv("FATHEREYE_WEB_PASSWORD");
        boolean generated = false;
        if (pw == null || pw.trim().length() < 16) {
            pw = generatePassword();
            generated = true;
        }
        setPassword(pw);
        if (generated) {
            LOG.info("=================================================================");
            LOG.info(" Father Eye Web Portal: generated a new operator password.");
            LOG.info(" Save this now; it is shown only once and stored only as a hash:");
            LOG.info("");
            LOG.info("     {}", pw);
            LOG.info("");
            LOG.info(" To set your own instead, stop the portal, delete");
            LOG.info("     {}", authFile);
            LOG.info(" and restart with FATHEREYE_WEB_PASSWORD set, or use --set-password.");
            LOG.info("=================================================================");
        } else {
            LOG.info("Set web portal password from FATHEREYE_WEB_PASSWORD env var.");
        }
    }

    /** Overwrites the stored credential with a hash of {@code pw}. */
    public void setPassword(String pw) {
        byte[] newSalt = new byte[SALT_BYTES];
        RNG.nextBytes(newSalt);
        byte[] newHash = pbkdf2(pw.toCharArray(), newSalt);
        this.salt = newSalt;
        this.hash = newHash;
        persist();
    }

    private void persist() {
        try {
            ObjectNode n = JSON.createObjectNode();
            n.put("salt", Base64.getEncoder().encodeToString(salt));
            n.put("hash", Base64.getEncoder().encodeToString(hash));
            n.put("iterations", PBKDF2_ITERATIONS);
            n.put("algorithm", "PBKDF2WithHmacSHA256");
            Files.write(authFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(n));
            try {
                Files.setPosixFilePermissions(authFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX FS; best effort
            }
        } catch (Exception e) {
            throw new RuntimeException("cannot write " + authFile, e);
        }
    }

    private static String generatePassword() {
        byte[] b = new byte[GENERATED_PASSWORD_BYTES];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(pw, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 failure", e);
        }
    }

    /**
     * Verifies a login attempt. On success, returns a fresh session token; on
     * failure (or lockout) returns {@code null}.
     */
    public String login(String sourceAddr, String password) {
        if (isLockedOut(sourceAddr)) {
            LOG.warn("Login refused: source {} is locked out", sourceAddr);
            return null;
        }
        if (password == null) { recordFailure(sourceAddr); return null; }
        byte[] candidate = pbkdf2(password.toCharArray(), salt);
        if (constantTimeEquals(candidate, hash)) {
            failures.remove(sourceAddr);
            String token = newToken();
            sessions.put(token, System.currentTimeMillis());
            LOG.info("Login success from {}", sourceAddr);
            return token;
        }
        recordFailure(sourceAddr);
        LOG.warn("Login failure from {}", sourceAddr);
        return null;
    }

    /** True if the token is valid and not expired; refreshes its sliding expiry. */
    public boolean validate(String token) {
        if (token == null) return false;
        Long last = sessions.get(token);
        if (last == null) return false;
        long now = System.currentTimeMillis();
        if (now - last > SESSION_TTL_MS) {
            sessions.remove(token);
            return false;
        }
        sessions.put(token, now);
        return true;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    private String newToken() {
        byte[] b = new byte[SESSION_TOKEN_BYTES];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private boolean isLockedOut(String src) {
        Failures f = failures.get(src);
        if (f == null) return false;
        if (f.count.get() < MAX_FAILURES) return false;
        long since = System.currentTimeMillis() - f.lastMs;
        if (since > LOCKOUT_MS) {
            failures.remove(src);
            return false;
        }
        return true;
    }

    private void recordFailure(String src) {
        Failures f = failures.computeIfAbsent(src, k -> new Failures());
        f.count.incrementAndGet();
        f.lastMs = System.currentTimeMillis();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private static final class Failures {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long lastMs;
    }
}
