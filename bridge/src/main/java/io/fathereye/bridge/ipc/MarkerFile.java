package io.fathereye.bridge.ipc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fathereye.bridge.util.Constants;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Writes <code>%LOCALAPPDATA%/FatherEye/bridges/&lt;instanceUuid&gt;.json</code>
 * so the panel can discover the live bridge. Deleted on shutdown.
 */
public final class MarkerFile {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path path;
    private final UUID instanceUuid;

    public MarkerFile(UUID instanceUuid) {
        this.instanceUuid = instanceUuid;
        this.path = markerPath(instanceUuid);
    }

    public Path path() { return path; }

    public void write(String transportKind, String address,
                      String mcVersion, String forgeVersion, String serverDir) throws IOException {
        Files.createDirectories(path.getParent());
        Marker m = new Marker();
        m.instanceUuid = instanceUuid.toString();
        m.transport = transportKind;
        // Backward-compat alias: panels older than v0.2 only know `pipeName`.
        // For TCP transport we still write an `address` field that newer
        // panels prefer.
        m.pipeName = address;
        m.address = address;
        m.protocolVersion = Constants.PROTOCOL_VERSION;
        m.bridgeVersion = Constants.BRIDGE_VERSION;
        m.mcVersion = mcVersion;
        m.forgeVersion = forgeVersion;
        m.serverDir = serverDir;
        m.pid = currentPid();
        m.startedAtEpochMs = System.currentTimeMillis();
        // Mac fork (audit 9 BUG 3): atomic write so the panel's
        // MarkerDiscovery never reads a partially-written marker.
        // Write to <uuid>.json.tmp then ATOMIC_MOVE to the final
        // path. APFS, HFS+, and NTFS all support atomic rename;
        // ext4 too. On any FS that doesn't (FAT32 etc.) the
        // ATOMIC_MOVE option throws AtomicMoveNotSupportedException
        // and we retry with a non-atomic move — better than the
        // upstream's torn-write window.
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(tmp, JSON.writeValueAsBytes(m));
        try {
            Files.move(tmp, path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ame) {
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    public static Path markerPath(UUID instanceUuid) {
        // Mac fork: route through PlatformPaths so the bridge writes
        // its marker to the same per-OS directory the panel scans:
        //   macOS   -> ~/Library/Application Support/FatherEye/bridges/
        //   Windows -> %LOCALAPPDATA%/FatherEye/bridges/
        //   Linux   -> $XDG_DATA_HOME/FatherEye/bridges/
        // Both sides MUST agree on this path or the panel never finds
        // the marker and the connection hangs at "Awaiting bridge..."
        return io.fathereye.bridge.util.PlatformPaths.appDataDir()
                .resolve("bridges")
                .resolve(instanceUuid + ".json");
    }

    private static long currentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? Long.parseLong(name.substring(0, at)) : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Marker {
        public String instanceUuid;
        public String transport;     // "named-pipe" | "tcp"; absent on legacy markers
        public String pipeName;      // legacy: named-pipe path (kept for v0.1 compat)
        public String address;       // canonical: pipe path OR host:port for tcp
        public int protocolVersion;
        public String bridgeVersion;
        public String mcVersion;
        public String forgeVersion;
        public String serverDir;
        public long pid;
        public long startedAtEpochMs;
        public Marker() {}
    }
}
