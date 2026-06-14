package io.fathereye.panel.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.panel.util.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel-side backup/rollback engine. This is the desktop-app twin of the web
 * portal's {@code BackupManager}: same external-only backup format, same
 * {@code fe-backup.sh} / {@code fe-rollback.sh} scripts, same single-slot job
 * model, so the Backups/Rollback tabs in the JavaFX panel and the browser
 * portal drive an identical, interchangeable backup store. Keeping the two in
 * lockstep is the 100% surface-parity standing rule.
 *
 * <p>All work happens on this Mac where both the server folder and the
 * external "Server Backups" volume are reachable; the bridge is never asked
 * to extract a multi-GB tarball over a live world. Only one long-running job
 * (backup or rollback) runs at a time; a second request while a job is active
 * is refused. Job state is an immutable {@link Job} snapshot the UI polls.
 *
 * <p>The existing {@link BackupService} (pre-stop + hourly scheduler) is left
 * intact and unchanged; this class is the interactive, listing-aware surface
 * the Backups/Rollback tabs call.
 */
public final class BackupOps {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-BackupOps");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern FE_ID = Pattern.compile("^fe-(\\d{8})-(\\d{6})$");
    private static final Pattern LEGACY = Pattern.compile("^(.+?)-(\\d{8}-\\d{6})\\.tar\\.gz$");
    /** Determinate-progress marker emitted by fe-backup.sh / fe-rollback.sh. */
    private static final Pattern PROGRESS = Pattern.compile("FE_PROGRESS=(\\d{1,3})");

    private final AppConfig config;
    private final AtomicReference<Job> job = new AtomicReference<>(Job.idle());

    private volatile Path backupScript;
    private volatile Path rollbackScript;
    private volatile Path regionRollbackScript;

    /** Region token "rx,rz" guard, mirrors fe-region-rollback.sh parsing:
     *  signed integers only, comma-separated, so no token can widen the
     *  script's tar member-selection glob. */
    private static final Pattern REGION_TOKEN = Pattern.compile("^-?\\d+,-?\\d+$");

    public BackupOps(AppConfig config) {
        this.config = config;
        try {
            ensureScripts();
        } catch (IOException e) {
            LOG.error("Could not extract backup scripts: {}", e.getMessage());
        }
    }

    // ---- config views (mirror PortalConfig) ----------------------------

    private Path serverDir() {
        String w = config.serverRuntime != null ? config.serverRuntime.workingDir : null;
        if (w == null || w.isBlank()) {
            return Paths.get(System.getProperty("user.home", "."), "Desktop", "Server");
        }
        return Paths.get(w);
    }

    private Path externalBackupDir() {
        String e = config.backup != null ? config.backup.externalBackupDir : null;
        if (e == null || e.isBlank()) {
            return Paths.get(BackupService.DEFAULT_EXTERNAL_DIR);
        }
        return Paths.get(e);
    }

    private int retainDays() { return config.backup != null ? config.backup.retainDays : 14; }
    private int minFreeGb()  { return config.backup != null ? config.backup.minFreeGb : 60; }

    // ---- scripts -------------------------------------------------------

    private synchronized void ensureScripts() throws IOException {
        Path dir = PlatformPaths.appDataDir().resolve("scripts");
        Files.createDirectories(dir);
        backupScript = extract(dir, "fe-backup.sh");
        rollbackScript = extract(dir, "fe-rollback.sh");
        regionRollbackScript = extract(dir, "fe-region-rollback.sh");
    }

    private Path extract(Path dir, String name) throws IOException {
        Path target = dir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/scripts/" + name)) {
            if (in == null) throw new IOException("bundled script missing: " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX FS; bash is invoked explicitly so +x is not required.
        }
        return target;
    }

    // ---- listing -------------------------------------------------------

    /**
     * Enumerate backups on the external drive: structured {@code fe-*}
     * directories first, then legacy whole-bundle tarballs. Newest first.
     * Identical JSON shape to the web portal's {@code /api/backups}.
     */
    public JsonNode list() {
        ArrayNode out = JSON.createArrayNode();
        Path dest = externalBackupDir();
        boolean mounted = Files.isDirectory(dest.getParent() == null ? dest : dest.getParent());

        ObjectNode meta = JSON.createObjectNode();
        meta.put("externalDir", dest.toString());
        meta.put("mounted", mounted);
        meta.put("freeBytes", freeBytes(dest));

        if (Files.isDirectory(dest)) {
            List<Path> entries = new ArrayList<>();
            try (var s = Files.list(dest)) {
                s.forEach(entries::add);
            } catch (IOException e) {
                LOG.warn("list backups failed: {}", e.getMessage());
            }
            entries.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            for (Path p : entries) {
                String name = p.getFileName().toString();
                Matcher fe = FE_ID.matcher(name);
                if (Files.isDirectory(p) && fe.matches()) {
                    out.add(describeStructured(p, name));
                } else if (Files.isRegularFile(p) && LEGACY.matcher(name).matches()) {
                    out.add(describeLegacy(p, name));
                }
            }
        }

        ObjectNode root = JSON.createObjectNode();
        root.set("meta", meta);
        root.set("backups", out);
        return root;
    }

    private ObjectNode describeStructured(Path dir, String id) {
        ObjectNode n = JSON.createObjectNode();
        Path manifest = dir.resolve("manifest.json");
        n.put("id", id);
        n.put("kind", "structured");
        boolean hasWorld = Files.isRegularFile(dir.resolve("world.tar.gz"));
        boolean hasPlayer = Files.isRegularFile(dir.resolve("playerdata.tar.gz"));
        boolean hasServer = Files.isRegularFile(dir.resolve("server.tar.gz"));
        n.put("hasWorld", hasWorld);
        n.put("hasPlayerData", hasPlayer);
        n.put("hasServer", hasServer);
        long total = 0;
        if (Files.isRegularFile(manifest)) {
            try {
                JsonNode m = JSON.readTree(Files.readAllBytes(manifest));
                n.put("createdIso", m.path("createdIso").asText(""));
                n.put("createdEpoch", m.path("createdEpoch").asLong(0));
                n.put("label", m.path("label").asText(""));
                n.put("worldName", m.path("worldName").asText(""));
                total = m.path("totalBytes").asLong(0);
            } catch (IOException e) {
                LOG.debug("manifest read failed for {}: {}", id, e.getMessage());
            }
        }
        if (total <= 0) total = dirSize(dir);
        n.put("totalBytes", total);
        if (!n.has("createdEpoch") || n.path("createdEpoch").asLong(0) == 0) {
            n.put("createdEpoch", epochFromFeId(id));
        }
        return n;
    }

    private ObjectNode describeLegacy(Path file, String name) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", name);
        n.put("kind", "legacy");
        n.put("hasWorld", true);
        n.put("hasPlayerData", false);
        n.put("hasServer", false);
        n.put("label", "legacy whole-bundle");
        long bytes = 0, epoch = 0;
        try {
            bytes = Files.size(file);
            epoch = Files.getLastModifiedTime(file).toMillis() / 1000;
        } catch (IOException ignored) {}
        n.put("totalBytes", bytes);
        n.put("createdEpoch", epoch);
        return n;
    }

    // ---- run backup ----------------------------------------------------

    /** Start a manual backup. Returns false if a job is already running. */
    public boolean startBackup(String label) {
        if (backupScript == null) { failNow("backup script unavailable"); return false; }
        if (!claim("backup", "Starting backup...")) return false;
        try {
            Path server = serverDir();
            Path dest = externalBackupDir();
            List<String> cmd = new ArrayList<>(List.of(
                    "/bin/bash", backupScript.toString(),
                    "--server", server.toString(),
                    "--dest", dest.toString(),
                    "--retain-days", String.valueOf(retainDays()),
                    "--min-free-gb", String.valueOf(minFreeGb())));
            if (label != null && !label.isBlank()) { cmd.add("--label"); cmd.add(label); }
            runAsync("backup", cmd, "Backup");
            return true;
        } catch (RuntimeException e) {
            failNow("backup could not start: " + e.getMessage());
            LOG.error("startBackup failed after claim", e);
            return false;
        }
    }

    // ---- rollback ------------------------------------------------------

    /**
     * Start a whole-component rollback of {@code id} with the given scope.
     * The caller must have already verified the server is stopped; the script
     * re-checks via RCON. Returns false if a job is running or inputs invalid.
     */
    public boolean startRollback(String id, String scope) {
        if (rollbackScript == null) { failNow("rollback script unavailable"); return false; }
        if (id == null || !FE_ID.matcher(id).matches()) { failNow("invalid backup id"); return false; }
        if (!("world".equals(scope) || "playerdata".equals(scope) || "both".equals(scope))) {
            failNow("invalid scope"); return false;
        }
        if (!claim("rollback", "Starting rollback (" + scope + ")...")) return false;
        try {
            Path server = serverDir();
            Path dest = externalBackupDir();
            List<String> cmd = List.of(
                    "/bin/bash", rollbackScript.toString(),
                    "--id", id, "--scope", scope,
                    "--server", server.toString(),
                    "--dest", dest.toString());
            runAsync("rollback", cmd, "Rollback (" + scope + ")");
            return true;
        } catch (RuntimeException e) {
            failNow("rollback could not start: " + e.getMessage());
            LOG.error("startRollback failed after claim", e);
            return false;
        }
    }

    /**
     * Start a region-selective rollback: restore ONLY the chosen Anvil
     * region files ({@code .mca}) of one dimension from backup {@code id},
     * leaving the rest of the live world untouched. {@code regions} is a
     * list of {@code "rx,rz"} region coordinates (NOT chunk or block).
     * The caller must have verified the server is stopped; the script
     * re-checks via RCON. Returns false if a job is running or the inputs
     * are invalid.
     */
    public boolean startRegionRollback(String id, String dim, List<String> regions) {
        if (regionRollbackScript == null) { failNow("region rollback script unavailable"); return false; }
        if (id == null || !FE_ID.matcher(id).matches()) { failNow("invalid backup id"); return false; }
        if (dim == null || dim.isBlank()) { failNow("invalid dimension"); return false; }
        if (regions == null || regions.isEmpty()) { failNow("no regions selected"); return false; }
        StringBuilder joined = new StringBuilder();
        for (String r : regions) {
            if (r == null || !REGION_TOKEN.matcher(r.trim()).matches()) {
                failNow("invalid region token: " + r); return false;
            }
            if (joined.length() > 0) joined.append(' ');
            joined.append(r.trim());
        }
        String regionArg = joined.toString();
        int count = regions.size();
        if (!claim("rollback", "Starting region rollback (" + count + " region"
                + (count == 1 ? "" : "s") + ")...")) return false;
        try {
            Path server = serverDir();
            Path dest = externalBackupDir();
            List<String> cmd = List.of(
                    "/bin/bash", regionRollbackScript.toString(),
                    "--id", id, "--dim", dim, "--regions", regionArg,
                    "--server", server.toString(),
                    "--dest", dest.toString());
            runAsync("rollback", cmd, "Region rollback (" + count + " region"
                    + (count == 1 ? "" : "s") + ")");
            return true;
        } catch (RuntimeException e) {
            failNow("region rollback could not start: " + e.getMessage());
            LOG.error("startRegionRollback failed after claim", e);
            return false;
        }
    }

    // ---- job model -----------------------------------------------------

    public JsonNode jobStatus() {
        return job.get().toJson();
    }

    public boolean isJobRunning() {
        return job.get().running;
    }

    private boolean claim(String type, String message) {
        Job cur = job.get();
        if (cur.running) return false;
        return job.compareAndSet(cur, Job.running(type, message));
    }

    private void runAsync(String type, List<String> cmd, String human) {
        Thread t = new Thread(() -> {
            StringBuilder tail = new StringBuilder();
            Process p = null;
            int rc;
            int pct = 0;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                p = pb.start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Matcher pm = PROGRESS.matcher(line);
                        if (pm.find()) {
                            int v = Integer.parseInt(pm.group(1));
                            // Monotonic, clamped: a stage band can never move the
                            // bar backwards even if a stale marker arrives late.
                            if (v >= 0 && v <= 100 && v > pct) pct = v;
                            // The FE_PROGRESS line itself is machine noise; keep it
                            // out of the human log tail.
                            continue;
                        }
                        appendTail(tail, line);
                        job.set(Job.running(type, human + " in progress...", pct, tail.toString()));
                    }
                }
                rc = p.waitFor();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (p != null && p.isAlive()) p.destroyForcibly();
                job.set(Job.done(type, false, human + " failed: " + e.getMessage(), pct, tail.toString()));
                LOG.error("{} failed: {}", human, e.getMessage());
                return;
            }
            boolean ok = rc == 0;
            String msg = ok ? human + " completed." : human + " failed (exit " + rc + ").";
            job.set(Job.done(type, ok, msg, ok ? 100 : pct, tail.toString()));
            LOG.info("{} finished rc={}", human, rc);
        }, "FatherEye-Panel-" + type);
        t.setDaemon(true);
        t.start();
    }

    private void failNow(String message) {
        job.set(Job.done("none", false, message, ""));
    }

    private static void appendTail(StringBuilder tail, String line) {
        tail.append(line).append('\n');
        if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
    }

    // ---- helpers -------------------------------------------------------

    private static long epochFromFeId(String id) {
        Matcher m = FE_ID.matcher(id);
        if (!m.matches()) return 0;
        try {
            String d = m.group(1), t = m.group(2);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                    d + t,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            return ldt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long freeBytes(Path dest) {
        try {
            Path probe = Files.isDirectory(dest) ? dest
                    : (dest.getParent() != null ? dest.getParent() : dest);
            if (!Files.exists(probe)) return -1;
            return Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private static long dirSize(Path dir) {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Immutable job snapshot. */
    private static final class Job {
        final String type;       // backup | rollback | none
        final boolean running;
        final Boolean ok;        // null while running
        final String message;
        final int percent;       // 0-100 determinate progress
        final String log;
        final long updatedMs;

        private Job(String type, boolean running, Boolean ok, String message, int percent, String log) {
            this.type = type; this.running = running; this.ok = ok;
            this.message = message; this.percent = percent;
            this.log = log; this.updatedMs = System.currentTimeMillis();
        }

        static Job idle() { return new Job("none", false, null, "idle", 0, ""); }
        static Job running(String type, String msg) { return new Job(type, true, null, msg, 0, ""); }
        static Job running(String type, String msg, int percent, String log) {
            return new Job(type, true, null, msg, percent, log);
        }
        static Job done(String type, boolean ok, String msg, String log) {
            return new Job(type, false, ok, msg, ok ? 100 : 0, log);
        }
        static Job done(String type, boolean ok, String msg, int percent, String log) {
            return new Job(type, false, ok, msg, percent, log);
        }

        JsonNode toJson() {
            ObjectNode n = JSON.createObjectNode();
            n.put("type", type);
            n.put("running", running);
            if (ok == null) n.putNull("ok"); else n.put("ok", ok);
            n.put("message", message);
            n.put("percent", percent);
            n.put("log", log);
            n.put("updatedMs", updatedMs);
            return n;
        }
    }
}
