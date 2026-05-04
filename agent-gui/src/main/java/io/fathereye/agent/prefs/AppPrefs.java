package io.fathereye.agent.prefs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fathereye.agent.session.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny key-value preferences store backed by a JSON file in the app
 * support dir. Single-process, single-thread (always called from the FX
 * thread). Defaults are baked in here so a missing file is fine.
 */
public final class AppPrefs {

    private static final Logger LOG = LoggerFactory.getLogger(AppPrefs.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private ObjectNode root;

    public AppPrefs() {
        Path dir = ConversationStore.appSupportDir();
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        this.file = dir.resolve("prefs.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) { root = MAPPER.createObjectNode(); return; }
        try {
            JsonNode n = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            root = n.isObject() ? (ObjectNode) n : MAPPER.createObjectNode();
        } catch (Exception e) {
            LOG.warn("could not read prefs at {}: {}", file, e.toString());
            root = MAPPER.createObjectNode();
        }
    }

    private void save() {
        try {
            Files.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("could not save prefs to {}: {}", file, e.toString());
        }
    }

    /** Total token estimate per 5-hour subscription window. Drives the
     *  sidebar progress bar — bar fills against tokens used / this
     *  estimate. Default 300K, which is the rough ballpark for Claude
     *  Pro's per-5-hour quota; Max 5x is ~1.5M and Max 20x is ~6M.
     *  Settings has one-click buttons for each tier.
     *
     *  <p>(Claude.ai's real quota isn't queryable through the Claude
     *  Code CLI, so this stays a self-set estimate rather than a true
     *  gauge.) Set to 0 to hide the bar. */
    public long tokenEstimate() {
        return root.path("tokenEstimate").asLong(300_000L);
    }

    public void setTokenEstimate(long v) {
        root.put("tokenEstimate", Math.max(0, v));
        save();
    }
}
