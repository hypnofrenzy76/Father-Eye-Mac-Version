package io.fathereye.panel.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.panel.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel-side twin of the web portal's {@code PlayerRestoreService}. Reads one
 * player's save data OUT of a structured Father Eye backup so the desktop
 * Backups tab can offer the same live "restore this player" action the
 * browser portal has (100% surface-parity rule).
 *
 * <p>It never touches the live world: it only inspects
 * {@code <dest>/<feId>/playerdata.tar.gz} on the external drive and hands the
 * extracted {@code <uuid>.dat} bytes to the caller, which base64-encodes them
 * and forwards to the bridge op {@code player_restoreState} for injection.
 *
 * <p>Archive access shells out to {@code tar}, matching the rest of the backup
 * subsystem. Inputs are tightly validated: the backup id must match
 * {@code fe-YYYYMMDD-HHMMSS} and the player id must be a canonical dashed
 * UUID, so neither can smuggle a path component into the {@code tar} arg list.
 */
public final class PanelPlayerRestore {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Panel-PlayerRestore");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A player .dat is normally a few KB; cap the extract to guard memory. */
    private static final int MAX_DAT_BYTES = 32 * 1024 * 1024;

    private static final Pattern FE_ID = Pattern.compile("^fe-(\\d{8})-(\\d{6})$");
    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern DAT_ENTRY = Pattern.compile(
            "^\\./?playerdata/([0-9a-fA-F-]{36})\\.dat$");

    private final AppConfig config;

    public PanelPlayerRestore(AppConfig config) {
        this.config = config;
    }

    public static boolean isValidBackupId(String id) {
        return id != null && FE_ID.matcher(id).matches();
    }

    public static boolean isValidUuid(String uuid) {
        return uuid != null && UUID_RE.matcher(uuid).matches();
    }

    private Path externalBackupDir() {
        String e = config.backup != null ? config.backup.externalBackupDir : null;
        if (e == null || e.isBlank()) {
            return Paths.get(BackupService.DEFAULT_EXTERNAL_DIR);
        }
        return Paths.get(e);
    }

    private Path serverDir() {
        String w = config.serverRuntime != null ? config.serverRuntime.workingDir : null;
        if (w == null || w.isBlank()) {
            return Paths.get(System.getProperty("user.home", "."), "Desktop", "Server");
        }
        return Paths.get(w);
    }

    public Path serverDirPublic() { return serverDir(); }

    private Path archiveFor(String backupId) {
        return externalBackupDir().resolve(backupId).resolve("playerdata.tar.gz");
    }

    /**
     * List the players whose data is present in {@code backupId}. Returns a
     * JSON object {@code { ok, players:[{uuid,name}], error? }}.
     */
    public JsonNode listPlayers(String backupId) {
        ObjectNode root = JSON.createObjectNode();
        if (!isValidBackupId(backupId)) {
            root.put("ok", false);
            root.put("error", "invalid backup id");
            return root;
        }
        Path archive = archiveFor(backupId);
        if (!Files.isRegularFile(archive)) {
            root.put("ok", false);
            root.put("error", "backup has no player data archive");
            return root;
        }

        Map<String, String> names = loadUserCache();
        ArrayNode players = JSON.createArrayNode();
        try {
            List<String> uuids = listDatUuids(archive);
            for (String uuid : uuids) {
                ObjectNode p = JSON.createObjectNode();
                p.put("uuid", uuid);
                String name = names.get(uuid.toLowerCase());
                if (name != null) p.put("name", name); else p.putNull("name");
                players.add(p);
            }
            root.put("ok", true);
            root.set("players", players);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("listPlayers({}) failed: {}", backupId, e.toString());
            root.put("ok", false);
            root.put("error", "could not read player archive: " + e.getMessage());
        }
        return root;
    }

    /**
     * Extract a single player's {@code .dat} bytes from the backup. Returns
     * null if the id/uuid is invalid, the archive is missing, or the entry
     * is not present.
     */
    public byte[] extractPlayerDat(String backupId, String uuid) {
        if (!isValidBackupId(backupId) || !isValidUuid(uuid)) return null;
        Path archive = archiveFor(backupId);
        if (!Files.isRegularFile(archive)) return null;

        byte[] bytes = tarExtractMember(archive, "./playerdata/" + uuid + ".dat");
        if (bytes == null || bytes.length == 0) {
            bytes = tarExtractMember(archive, "playerdata/" + uuid + ".dat");
        }
        return (bytes == null || bytes.length == 0) ? null : bytes;
    }

    // ---- tar helpers ---------------------------------------------------

    private List<String> listDatUuids(Path archive) throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("tar", "-tzf", archive.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = DAT_ENTRY.matcher(line.trim());
                if (m.matches()) out.add(m.group(1));
            }
        }
        int rc = proc.waitFor();
        if (rc != 0) throw new IOException("tar -tzf exit " + rc);
        return out;
    }

    private byte[] tarExtractMember(Path archive, String member) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "-xzO", "-f", archive.toString(), member);
            pb.redirectErrorStream(false);
            proc = pb.start();
            final Process fp = proc;
            Thread errDrainer = new Thread(() -> drain(fp.getErrorStream()),
                    "FatherEye-Panel-tar-stderr");
            errDrainer.setDaemon(true);
            errDrainer.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_DAT_BYTES) {
                        LOG.warn("player .dat in {} exceeds {} bytes; refusing", archive, MAX_DAT_BYTES);
                        proc.destroyForcibly();
                        return null;
                    }
                    bos.write(buf, 0, n);
                }
            }
            int rc = proc.waitFor();
            errDrainer.join(1000);
            if (rc != 0) return null;
            return bos.toByteArray();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("tar extract {} from {} failed: {}", member, archive, e.toString());
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
            return null;
        }
    }

    private static void drain(InputStream s) {
        try (InputStream in = s) {
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) { /* discard */ }
        } catch (IOException ignored) {}
    }

    private Map<String, String> loadUserCache() {
        Map<String, String> out = new LinkedHashMap<>();
        Path cache = serverDir().resolve("usercache.json");
        if (!Files.isRegularFile(cache)) return out;
        try {
            JsonNode arr = JSON.readTree(Files.readAllBytes(cache));
            if (arr.isArray()) {
                for (JsonNode e : arr) {
                    String uuid = e.path("uuid").asText(null);
                    String name = e.path("name").asText(null);
                    if (uuid != null && name != null) {
                        out.put(uuid.toLowerCase(), name);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("usercache read failed: {}", e.toString());
        }
        return out;
    }
}
