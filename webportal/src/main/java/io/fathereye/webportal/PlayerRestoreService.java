package io.fathereye.webportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.webportal.ipc.PipeCodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads player save data OUT of a structured Father Eye backup so the web
 * portal can offer a live "restore this player" action. It never touches the
 * live world: it only inspects {@code <dest>/<feId>/playerdata.tar.gz} on the
 * external drive and hands the extracted {@code <uuid>.dat} (base64) to the
 * caller, which forwards it to the bridge for injection.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #listPlayers(String)} enumerates the {@code <uuid>.dat}
 *       entries inside a backup's {@code playerdata.tar.gz}, resolving names
 *       from the live server's {@code usercache.json} where possible.</li>
 *   <li>{@link #extractPlayerDat(String, String)} streams a single player's
 *       {@code .dat} out of the archive and returns its raw bytes.</li>
 * </ul>
 *
 * <p>All archive access shells out to {@code tar}, matching the rest of the
 * backup subsystem (no extra Java tar dependency). Inputs are tightly
 * validated: the backup id must match {@code fe-YYYYMMDD-HHMMSS} and the
 * player id must be a canonical dashed UUID, so neither can smuggle a path
 * component into the {@code tar} argument list.
 */
public final class PlayerRestoreService {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebPortal-PlayerRestore");

    /** A player .dat is normally a few KB; cap the extract to guard memory. */
    private static final int MAX_DAT_BYTES = 32 * 1024 * 1024;

    private static final Pattern FE_ID = Pattern.compile("^fe-(\\d{8})-(\\d{6})$");
    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    // Tar lists entries either as "./playerdata/<uuid>.dat" or
    // "playerdata/<uuid>.dat" depending on how they were added. Capture the
    // uuid and ignore the *_old rotation files vanilla keeps.
    private static final Pattern DAT_ENTRY = Pattern.compile(
            "^\\./?playerdata/([0-9a-fA-F-]{36})\\.dat$");

    /** Is this a syntactically valid structured backup id? */
    public static boolean isValidBackupId(String id) {
        return id != null && FE_ID.matcher(id).matches();
    }

    /** Is this a canonical dashed UUID? */
    public static boolean isValidUuid(String uuid) {
        return uuid != null && UUID_RE.matcher(uuid).matches();
    }

    private Path archiveFor(String backupId) {
        Path dir = PortalConfig.externalBackupDir().resolve(backupId);
        return dir.resolve("playerdata.tar.gz");
    }

    /**
     * List the players whose data is present in {@code backupId}. Returns a
     * JSON object {@code { ok, players:[{uuid,name}], error? }}. Names are a
     * best-effort lookup against the live server's usercache; a player with
     * no cached name still appears (name=null) so they can be restored by id.
     */
    public JsonNode listPlayers(String backupId) {
        ObjectNode root = PipeCodecs.JSON.createObjectNode();
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
        ArrayNode players = PipeCodecs.JSON.createArrayNode();
        try {
            List<String> uuids = listDatUuids(archive);
            for (String uuid : uuids) {
                ObjectNode p = PipeCodecs.JSON.createObjectNode();
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
     * is not present. The bytes are the exact gzip-compressed NBT vanilla
     * wrote, ready to base64 and hand to the bridge.
     */
    public byte[] extractPlayerDat(String backupId, String uuid) {
        if (!isValidBackupId(backupId) || !isValidUuid(uuid)) return null;
        Path archive = archiveFor(backupId);
        if (!Files.isRegularFile(archive)) return null;

        // Try both possible internal paths. tar -x with a non-matching member
        // exits non-zero, so we attempt the leading-./ form first, then the
        // bare form, and accept whichever yields bytes.
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
        // Merge stderr into stdout: this is a text listing, so any tar
        // diagnostics interleave harmlessly (they won't match DAT_ENTRY) and
        // a single drained stream can never pipe-deadlock the child.
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

    /**
     * Extract exactly one member to stdout. Returns the bytes, or null on a
     * non-zero exit (member absent). {@code -O} writes to stdout; the member
     * name is passed as a single literal arg (no shell), so the validated
     * uuid cannot escape into another option.
     */
    private byte[] tarExtractMember(Path archive, String member) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "-xzO", "-f", archive.toString(), member);
            // Do NOT merge stderr here: stdout carries the raw binary .dat,
            // so tar diagnostics on stderr must stay separate or they would
            // corrupt the extracted bytes. Drain stderr on its own thread so
            // a chatty tar can never fill the stderr pipe and deadlock while
            // we are blocked reading stdout (classic ProcessBuilder trap).
            pb.redirectErrorStream(false);
            proc = pb.start();
            final Process fp = proc;
            Thread errDrainer = new Thread(() -> drain(fp.getErrorStream()),
                    "FatherEye-tar-stderr");
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

    /**
     * Best-effort UUID->name map from the live server's usercache.json.
     * Returns an empty map if the file is missing or malformed. Keys are
     * lowercased UUIDs.
     */
    private Map<String, String> loadUserCache() {
        Map<String, String> out = new LinkedHashMap<>();
        Path cache = PortalConfig.serverDir().resolve("usercache.json");
        if (!Files.isRegularFile(cache)) return out;
        try {
            JsonNode arr = PipeCodecs.JSON.readTree(Files.readAllBytes(cache));
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
