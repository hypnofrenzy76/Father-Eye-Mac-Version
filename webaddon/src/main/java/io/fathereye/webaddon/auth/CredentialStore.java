package io.fathereye.webaddon.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fathereye.panel.util.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Single-user credential store for the web addon. Persists a username +
 * BCrypt password hash (cost 12) to {@code <appData>/webaddon.json} with
 * filesystem mode {@code 0600}. Plaintext passwords are never stored
 * and never logged.
 *
 * <p>The file is created on first {@link #set(String, char[])} call;
 * until then, {@link #isConfigured()} returns false and the web addon's
 * login endpoint refuses every attempt with a generic "credentials not
 * configured" message (never leaking the existence/non-existence of
 * the file).
 *
 * <p>Verification uses {@link BCrypt.Verifyer#verify(char[], String)}
 * which is constant-time over the hash bytes — a timing oracle on the
 * stored hash is not exploitable to learn the password.
 */
public final class CredentialStore {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-Auth");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BCRYPT_COST = 12;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Stored {
        public String username;
        public String passwordBcrypt; // $2a$... hash
        public long updatedAtEpochMs;
        public Stored() {}
    }

    private final Path file;
    private volatile Stored stored;

    public CredentialStore() {
        this.file = PlatformPaths.appDataDir().resolve("webaddon.json");
        load();
    }

    public CredentialStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                stored = JSON.readValue(Files.readAllBytes(file), Stored.class);
                if (stored != null && stored.username != null && stored.passwordBcrypt != null) {
                    LOG.info("Loaded web addon credentials for user '{}' from {}", stored.username, file);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not load web addon credential file {}: {}", file, e.getMessage());
        }
    }

    public boolean isConfigured() {
        Stored s = stored;
        return s != null && s.username != null && !s.username.isEmpty()
                && s.passwordBcrypt != null && !s.passwordBcrypt.isEmpty();
    }

    public String username() {
        Stored s = stored;
        return s == null ? null : s.username;
    }

    /**
     * Set or replace the credentials. {@code password} is overwritten
     * with zeros immediately after hashing so it does not linger in the
     * caller's heap. The file is written atomically (write to .tmp, rename).
     */
    public synchronized void set(String username, char[] password) throws IOException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (password == null || password.length < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
        String hash;
        try {
            hash = BCrypt.with(BCrypt.Version.VERSION_2A).hashToString(BCRYPT_COST, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        Stored next = new Stored();
        next.username = username;
        next.passwordBcrypt = hash;
        next.updatedAtEpochMs = System.currentTimeMillis();
        writeAtomic(next);
        this.stored = next;
        LOG.info("Web addon credentials updated for user '{}'", username);
    }

    /**
     * Verify a candidate username + password. Returns true only on a
     * full match. {@code password} is overwritten with zeros after
     * verification.
     */
    public boolean verify(String candidateUser, char[] candidatePassword) {
        try {
            Stored s = stored;
            if (s == null || s.username == null || s.passwordBcrypt == null) return false;
            if (candidateUser == null) return false;
            // Normalize both sides identically — no Unicode case folding
            // tricks (the user sets it once, types it the same way).
            if (!s.username.equals(candidateUser)) {
                // Still run a dummy bcrypt verify to keep timing
                // independent of whether the username matched.
                BCrypt.verifyer().verify("dummy".toCharArray(), s.passwordBcrypt);
                return false;
            }
            BCrypt.Result r = BCrypt.verifyer().verify(candidatePassword, s.passwordBcrypt);
            return r.verified;
        } finally {
            if (candidatePassword != null) java.util.Arrays.fill(candidatePassword, '\0');
        }
    }

    private void writeAtomic(Stored data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: ACL inherited from parent.
        }
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** For ConfigPane convenience: the path the panel can show in its
     *  "credentials stored at" hint. */
    public Path filePath() { return file; }
}
