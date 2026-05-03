package io.fathereye.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Drives a Claude Code CLI subprocess as the agent backend.
 *
 * <p>Pipes user messages in as JSONL on stdin
 * ({@code --input-format stream-json}) and reads the agent's JSONL event
 * stream out ({@code --output-format stream-json --verbose}), then
 * dispatches each event to the {@link Listener} on the FX thread.
 *
 * <p>Why a subprocess: by delegating to Claude Code, the app uses
 * whichever auth Claude Code is logged in with, so a Claude.ai Pro/Max
 * subscription works (no API credits required) and we get Claude Code's
 * full tool surface (Read/Write/Edit/Bash/Glob/Grep) instead of the five
 * tools we re-implemented locally.
 *
 * <p>Tool execution happens inside Claude Code; this class only renders
 * the resulting events to the UI. The Anthropic Java SDK is no longer a
 * dependency.
 */
public final class AgentService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentService.class);

    /** Callbacks fired on the FX thread. */
    public interface Listener {
        void onAssistantText(String markdown);
        void onToolCall(String name, Map<String, Object> input);
        void onToolResult(String toolName, Tools.Result result);
        void onTurnComplete();
        void onError(Throwable t);
    }

    private final Path defaultCwd;
    private volatile Path cwd;
    private volatile String model;
    /** Claude Code's session_id from the most recent {@code system.init} event. */
    private volatile String currentSessionId;
    /** If non-null, the next spawn passes {@code --resume <id>} to continue an old session. */
    private volatile String pendingResumeId;
    private final String claudePath;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Map tool_use_id -> tool_name so tool_result events can be tagged. */
    private final Map<String, String> pendingToolNames = new HashMap<>();

    private final ExecutorService readerThread = daemon("claude-reader");
    private final ExecutorService stderrThread = daemon("claude-stderr");

    /** Guarded by {@code this} (see {@link #spawn}, {@link #respawn}, {@link #send}). */
    private Process process;
    /** Guarded by {@code this}. */
    private BufferedWriter stdin;
    private volatile Listener currentListener;

    public AgentService(Path cwd, String model) throws IOException {
        this.defaultCwd = cwd;
        this.cwd = cwd;
        this.model = model;
        this.claudePath = findClaude();
        spawn();
    }

    public synchronized void setModel(String model) {
        if (model == null || model.equals(this.model)) return;
        this.model = model;
        // Respawn so the new model takes effect on the next user turn.
        // This also clears the conversation history — Claude Code does not
        // expose a model-switch verb mid-session, so a clean restart is
        // the cleanest semantics. The user already had to click the model
        // picker explicitly to land here.
        respawn();
    }
    public synchronized void setCwd(Path cwd) {
        if (cwd == null || cwd.equals(this.cwd)) return;
        this.cwd = cwd;
        respawn();
    }
    /**
     * Resume an old Claude Code session by id. The next spawn will pass
     * {@code --resume <sessionId>} which continues the prior conversation
     * with full agent context (tool history, prior thinking, file state
     * the model has seen). If the resume fails (session not found on the
     * user's machine, expired), Claude Code falls back to a fresh
     * session — we don't try to detect that here.
     */
    public synchronized void resume(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        this.pendingResumeId = sessionId;
        respawn();
    }
    public String getCurrentSessionId() { return currentSessionId; }
    public String getModel() { return model; }
    public Path cwd() { return cwd; }

    public synchronized void clearHistory() {
        respawn();
    }

    /** Submits a user message. Returns immediately; events stream via the listener. */
    public synchronized void send(String userText, Listener listener) {
        this.currentListener = listener;
        if (process == null || !process.isAlive()) {
            try { spawn(); } catch (IOException e) {
                Platform.runLater(() -> listener.onError(e));
                return;
            }
        }
        try {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("type", "user");
            ObjectNode message = msg.putObject("message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", userText);
            stdin.write(mapper.writeValueAsString(msg));
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            LOG.error("write to claude stdin failed", e);
            Platform.runLater(() -> listener.onError(e));
        }
    }

    public synchronized void shutdown() {
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {}
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        readerThread.shutdownNow();
        stderrThread.shutdownNow();
    }

    // -------------------------------------------------------------------
    // Subprocess management
    // -------------------------------------------------------------------

    /**
     * Locate the {@code claude} CLI on disk.
     *
     * <p>We can't rely on {@code PATH} because a Finder-launched .app
     * inherits a minimal launchd PATH ({@code /usr/bin:/bin:/usr/sbin:/sbin})
     * that does not include {@code /usr/local/bin}, {@code /opt/homebrew/bin},
     * or any user-shell exports. Run a login+interactive zsh and ask it
     * to resolve {@code claude}; that picks up whatever the user has in
     * their {@code .zshrc} / {@code .zprofile}. Allow {@code CLAUDE_PATH}
     * as an explicit override for non-standard installs.
     */
    private static String findClaude() throws IOException {
        String override = System.getenv("CLAUDE_PATH");
        if (override != null && !override.isBlank()) return override;

        // -lic: login + interactive + run command. Login picks up
        // /etc/zprofile + ~/.zprofile + ~/.zlogin; interactive picks up
        // ~/.zshrc. Most users export their npm/homebrew bin from one of
        // those.
        ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-lic", "command -v claude || true");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while looking for claude CLI", e);
        }
        if (!out.isEmpty() && new File(out.split("\\R", 2)[0].trim()).canExecute()) {
            return out.split("\\R", 2)[0].trim();
        }
        // Fallback: scan common install locations.
        String home = System.getProperty("user.home");
        String[] candidates = {
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                home + "/.npm-global/bin/claude",
                home + "/.local/bin/claude",
                home + "/n/bin/claude"
        };
        for (String c : candidates) {
            if (new File(c).canExecute()) return c;
        }
        throw new IOException(
                "Claude Code CLI not found on PATH or in any of "
                        + String.join(", ", candidates) + ". "
                        + "Install with `npm install -g @anthropic-ai/claude-code`, "
                        + "then run `claude /login` to authenticate with your Pro/Max "
                        + "subscription. Set CLAUDE_PATH to override.");
    }

    private synchronized void spawn() throws IOException {
        // Build the argv. --resume is opt-in for the next spawn only;
        // consumed and cleared here so a subsequent respawn (e.g. setModel
        // mid-session) doesn't try to re-resume an outdated id.
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(claudePath);
        argv.add("--print");
        argv.add("--input-format"); argv.add("stream-json");
        argv.add("--output-format"); argv.add("stream-json");
        argv.add("--verbose");
        argv.add("--model"); argv.add(model);
        if (pendingResumeId != null && !pendingResumeId.isBlank()) {
            argv.add("--resume"); argv.add(pendingResumeId);
            pendingResumeId = null;
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        // Reset session id; readLoop will set it from the next init event.
        currentSessionId = null;
        // Surface PATH so npm-installed tools the agent calls (node, etc.)
        // resolve. We inherit our own env, which already contains our
        // (login-shell-augmented) PATH if AgentApp was launched correctly,
        // or the launchd minimal PATH otherwise — neither breaks claude
        // itself, but the agent's bash tool will see whatever we pass.
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader stdoutR = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stderrR = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        readerThread.submit(() -> readLoop(stdoutR));
        stderrThread.submit(() -> stderrLoop(stderrR));
        synchronized (pendingToolNames) { pendingToolNames.clear(); }
    }

    private synchronized void respawn() {
        try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            spawn();
        } catch (IOException e) {
            LOG.error("respawn failed", e);
            Listener l = currentListener;
            if (l != null) Platform.runLater(() -> l.onError(e));
        }
    }

    // -------------------------------------------------------------------
    // Event dispatch
    // -------------------------------------------------------------------

    private void readLoop(BufferedReader stdout) {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) continue;
                Listener l = currentListener;
                if (l == null) continue;
                try {
                    JsonNode event = mapper.readTree(line);
                    dispatch(event, l);
                } catch (Exception e) {
                    LOG.warn("could not parse claude event: {}", line, e);
                }
            }
        } catch (IOException e) {
            LOG.debug("claude stdout closed: {}", e.getMessage());
        }
    }

    private void stderrLoop(BufferedReader stderr) {
        try {
            String line;
            while ((line = stderr.readLine()) != null) {
                LOG.warn("[claude stderr] {}", line);
            }
        } catch (IOException e) {
            LOG.debug("claude stderr closed: {}", e.getMessage());
        }
    }

    private void dispatch(JsonNode event, Listener l) {
        String type = event.path("type").asText();
        switch (type) {
            case "system":
                // {type: "system", subtype: "init", session_id, model, tools, ...}
                // Capture session_id so the caller can persist it and later
                // pass it to resume() to continue this conversation across
                // launches with full agent context.
                if ("init".equals(event.path("subtype").asText())) {
                    String sid = event.path("session_id").asText("");
                    if (!sid.isEmpty()) currentSessionId = sid;
                }
                LOG.debug("system event subtype={} session_id={}",
                        event.path("subtype").asText(), currentSessionId);
                break;
            case "assistant":
                handleAssistant(event, l);
                break;
            case "user":
                handleUser(event, l);
                break;
            case "result":
                handleResult(event, l);
                break;
            default:
                LOG.debug("unhandled event type: {}", type);
        }
    }

    private void handleAssistant(JsonNode event, Listener l) {
        JsonNode content = event.path("message").path("content");
        if (!content.isArray()) return;
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            String btype = block.path("type").asText();
            if ("text".equals(btype)) {
                if (text.length() > 0) text.append("\n\n");
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(btype)) {
                // Flush any text before this tool_use so on-screen order
                // matches the assistant turn's block order.
                if (text.length() > 0) {
                    final String md = text.toString();
                    text.setLength(0);
                    Platform.runLater(() -> l.onAssistantText(md));
                }
                final String name = block.path("name").asText();
                final String id = block.path("id").asText();
                final Map<String, Object> input = parseInput(block.path("input"));
                synchronized (pendingToolNames) { pendingToolNames.put(id, name); }
                Platform.runLater(() -> l.onToolCall(name, input));
            }
        }
        if (text.length() > 0) {
            final String md = text.toString();
            Platform.runLater(() -> l.onAssistantText(md));
        }
    }

    private void handleUser(JsonNode event, Listener l) {
        // Echo of the agent's tool_result feedback to the model. We render
        // these as the result of the matching ToolCallCard.
        JsonNode content = event.path("message").path("content");
        if (!content.isArray()) return;
        for (JsonNode block : content) {
            if (!"tool_result".equals(block.path("type").asText())) continue;
            String id = block.path("tool_use_id").asText();
            final String name;
            synchronized (pendingToolNames) {
                name = pendingToolNames.getOrDefault(id, "tool");
            }
            final String text = extractToolResultText(block.path("content"));
            final boolean isError = block.path("is_error").asBoolean(false);
            final Tools.Result r = new Tools.Result(text, isError);
            Platform.runLater(() -> l.onToolResult(name, r));
        }
    }

    private void handleResult(JsonNode event, Listener l) {
        boolean isError = event.path("is_error").asBoolean(false);
        String subtype = event.path("subtype").asText("");
        if (isError || (!subtype.isEmpty() && !"success".equals(subtype))) {
            String msg = event.path("error").path("message").asText("");
            if (msg.isEmpty()) msg = event.path("result").asText("agent error: " + subtype);
            final String fmsg = msg;
            Platform.runLater(() -> l.onError(new RuntimeException(fmsg)));
        } else {
            Platform.runLater(l::onTurnComplete);
        }
    }

    private String extractToolResultText(JsonNode content) {
        if (content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonNode rb : content) {
                if ("text".equals(rb.path("type").asText())) {
                    if (b.length() > 0) b.append("\n");
                    b.append(rb.path("text").asText());
                }
            }
            return b.toString();
        }
        return content.toString();
    }

    private Map<String, Object> parseInput(JsonNode input) {
        if (input.isMissingNode() || input.isNull()) return Map.of();
        try {
            return mapper.convertValue(input, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warn("could not parse tool input: {}", input, e);
            return Map.of();
        }
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}
