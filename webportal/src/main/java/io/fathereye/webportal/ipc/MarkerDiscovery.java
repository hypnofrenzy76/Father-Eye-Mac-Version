package io.fathereye.webportal.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Scans the per-platform Father Eye bridges directory for live bridge
 * marker files. Copied from the panel ipc package; the only change is the
 * {@link PlatformPaths} reference now lives in this module's ipc package.
 */
public final class MarkerDiscovery {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MarkerDiscovery() {}

    public static Path bridgesDir() {
        return PlatformPaths.appDataDir().resolve("bridges");
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
                    if (m.pid > 0) {
                        java.util.Optional<ProcessHandle> ph =
                                ProcessHandle.of(m.pid);
                        if (!ph.isPresent() || !ph.get().isAlive()) {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            continue;
                        }
                    }
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
