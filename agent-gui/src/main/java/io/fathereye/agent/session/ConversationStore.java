package io.fathereye.agent.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persistent conversation history. Each conversation is a JSON file in
 * the app's data directory:
 *
 * <pre>
 * ~/Library/Application Support/Claude for High Sierra/conversations/
 *     20260503-103412-{uuid}.json
 * </pre>
 *
 * <p>Each file is {@code { id, title, createdAt, updatedAt, cwd, model,
 * messages: [ { role, text } ... ] }}. The messages array captures only
 * what we render (user text + assistant markdown), not the full Claude
 * Code event stream — this is for sidebar review, not for replaying tool
 * calls.
 */
public final class ConversationStore {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;

    public ConversationStore() {
        this.dir = appSupportDir().resolve("conversations");
        try { Files.createDirectories(dir); } catch (IOException e) { LOG.warn("mkdirs {}: {}", dir, e.toString()); }
    }

    /** macOS: ~/Library/Application Support/Claude for High Sierra. Linux: ~/.local/share/claude-for-high-sierra. */
    public static Path appSupportDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "Claude for High Sierra");
        }
        return Paths.get(home, ".local", "share", "claude-for-high-sierra");
    }

    public Path dir() { return dir; }

    /** All conversations, newest first. */
    public List<Conversation> list() {
        List<Conversation> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json"))
             .forEach(p -> {
                 try { out.add(read(p)); }
                 catch (Exception e) { LOG.warn("could not read {}: {}", p, e.toString()); }
             });
        } catch (IOException e) {
            LOG.warn("could not list {}: {}", dir, e.toString());
        }
        out.sort(Comparator.comparing(Conversation::updatedAt).reversed());
        return out;
    }

    public Conversation create(Path cwd, String model) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Conversation c = new Conversation(id, "New conversation", now, now,
                cwd == null ? "" : cwd.toString(), model, new ArrayList<>());
        save(c);
        return c;
    }

    public Conversation read(Path p) throws IOException {
        JsonNode n = MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8));
        List<Conversation.Message> msgs = new ArrayList<>();
        for (JsonNode m : n.path("messages")) {
            msgs.add(new Conversation.Message(
                    m.path("role").asText("user"),
                    m.path("text").asText("")));
        }
        String sid = n.path("claudeSessionId").asText("");
        return new Conversation(
                n.path("id").asText(),
                n.path("title").asText("(untitled)"),
                n.path("createdAt").asLong(System.currentTimeMillis()),
                n.path("updatedAt").asLong(System.currentTimeMillis()),
                n.path("cwd").asText(""),
                n.path("model").asText(""),
                sid.isEmpty() ? null : sid,
                msgs);
    }

    public void save(Conversation c) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", c.id());
        root.put("title", c.title());
        root.put("createdAt", c.createdAt());
        root.put("updatedAt", c.updatedAt());
        root.put("cwd", c.cwd());
        root.put("model", c.model());
        if (c.claudeSessionId() != null) root.put("claudeSessionId", c.claudeSessionId());
        ArrayNode arr = root.putArray("messages");
        for (Conversation.Message m : c.messages()) {
            ObjectNode mn = arr.addObject();
            mn.put("role", m.role());
            mn.put("text", m.text());
        }
        Path p = dir.resolve(c.id() + ".json");
        try {
            Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("could not save conversation {}: {}", c.id(), e.toString());
        }
    }

    public void delete(String id) {
        Path p = dir.resolve(id + ".json");
        try { Files.deleteIfExists(p); } catch (IOException e) { LOG.warn("delete {}: {}", p, e.toString()); }
    }
}
