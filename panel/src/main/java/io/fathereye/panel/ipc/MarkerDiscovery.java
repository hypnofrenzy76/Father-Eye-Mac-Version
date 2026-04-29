package io.fathereye.panel.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Scans <code>%LOCALAPPDATA%/FatherEye/bridges/*.json</code> for live bridge
 * marker files. Each file represents one running server. For a single-user
 * setup we typically have at most one.
 */
public final class MarkerDiscovery {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MarkerDiscovery() {}

    public static Path bridgesDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            localAppData = System.getProperty("user.home", ".");
        }
        return Paths.get(localAppData, "FatherEye", "bridges");
    }

    public static List<Marker> discover() {
        Path dir = bridgesDir();
        List<Marker> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                try {
                    Marker m = JSON.readValue(Files.readAllBytes(p), Marker.class);
                    m.markerPath = p.toString();
                    out.add(m);
                } catch (IOException ignored) {
                    // stale or partially-written; skip
                }
            }
        } catch (IOException ignored) {
            // dir vanished mid-scan
        }
        out.sort(Comparator.comparingLong(m -> -m.startedAtEpochMs));
        return out;
    }

    public static Optional<Marker> discoverFirst() {
        List<Marker> all = discover();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public static final class Marker {
        public String instanceUuid;
        public String transport;        // "named-pipe" | "tcp" | null (legacy)
        public String pipeName;         // legacy: named-pipe path
        public String address;          // canonical: pipe path or host:port
        public int protocolVersion;
        public String bridgeVersion;
        public String mcVersion;
        public String forgeVersion;
        public String serverDir;
        public long pid;
        public long startedAtEpochMs;
        public transient String markerPath;
        public Marker() {}
    }
}
