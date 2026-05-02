package io.fathereye.agent.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the Claude agent loop on a background thread and routes events
 * back to the FX thread via a {@link Listener} so the UI can render them.
 *
 * <p>Manual tool-use loop, not the BetaToolRunner. We need fine-grained
 * control over per-block UI rendering (showing each tool call as a
 * collapsible card, separating user messages from tool_results) and the
 * runner abstracts that away.
 *
 * <p>Conversation history lives here as the source of truth for the API
 * (a {@code List<MessageParam>}). The UI maintains its own parallel
 * representation for display; the two never share objects.
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

    private static final long MAX_TOKENS = 32_000L;
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a coding assistant running in a desktop app on macOS.
            Working directory: %s

            You have these tools:
              - read_file(path)              read a text file
              - write_file(path, content)    create or overwrite a file
              - edit_file(path, old, new)    replace exact unique text in a file
              - list_dir(path)               list directory contents
              - bash(command, description)   run a shell command in the cwd

            Guidelines:
              - Read a file before editing it.
              - Prefer edit_file over write_file for existing files.
              - Use bash for git, build, test, grep, find — not for file edits.
              - Format responses with Markdown. Use code fences for code, **bold** for emphasis.
              - Keep responses concise; the user is reading them in a small chat window.
            """;

    private final AnthropicClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-loop");
        t.setDaemon(true);
        return t;
    });

    private final List<MessageParam> history = new ArrayList<>();
    private final Path cwd;
    private volatile String model;

    public AgentService(Path cwd, String model) {
        this.cwd = cwd;
        this.model = model;
        this.client = AnthropicOkHttpClient.fromEnv();
    }

    public void setModel(String model) { this.model = model; }
    public String getModel() { return model; }
    public Path cwd() { return cwd; }

    public void clearHistory() {
        synchronized (history) { history.clear(); }
    }

    /** Submits a user message. Returns immediately; events stream via the listener. */
    public void send(String userText, Listener listener) {
        synchronized (history) {
            // Wrap the user text in a TextBlockParam so every history
            // entry is uniformly contentOfBlockParams. Avoids relying on
            // a String overload whose exact builder method name varies
            // across SDK minor versions.
            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(List.of(
                            ContentBlockParam.ofText(
                                    TextBlockParam.builder().text(userText).build())))
                    .build());
        }
        exec.submit(() -> runLoop(listener));
    }

    private void runLoop(Listener listener) {
        try {
            while (true) {
                // Snapshot history under the lock, then release before
                // the API call. Holding the lock through the network
                // round-trip would block the FX thread if it ever tried
                // to add another user message (it can't today because
                // the input is disabled while busy, but the lock-holding
                // pattern is fragile to that invariant changing).
                List<MessageParam> snapshot;
                synchronized (history) {
                    snapshot = new ArrayList<>(history);
                }

                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(MAX_TOKENS)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(String.format(SYSTEM_PROMPT_TEMPLATE, cwd))
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .messages(snapshot);
                // Tools added one at a time — the .addTool(Tool) overload
                // wraps in ToolUnion automatically, so we don't have to
                // construct ToolUnions ourselves.
                for (var tool : ToolSchemas.all()) {
                    builder.addTool(tool);
                }
                Message response = client.messages().create(builder.build());

                // Append the assistant turn to history. Convert response
                // ContentBlocks back to ContentBlockParams so they roundtrip.
                List<ContentBlockParam> assistantParams = toParams(response.content());
                synchronized (history) {
                    history.add(MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(assistantParams)
                            .build());
                }

                // Render text blocks first, then tool calls.
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(t -> {
                        if (text.length() > 0) text.append("\n\n");
                        text.append(t.text());
                    });
                }
                if (text.length() > 0) {
                    final String md = text.toString();
                    Platform.runLater(() -> listener.onAssistantText(md));
                }

                StopReason stop = response.stopReason().orElse(null);
                if (stop != StopReason.TOOL_USE) {
                    Platform.runLater(listener::onTurnComplete);
                    return;
                }

                // Execute every tool_use in the response, then append all
                // the tool_results in a single user message (the API
                // requires one tool_result per tool_use, all in the same
                // message). Capture name/input/result in final locals so
                // the FX-thread lambdas see snapshot values.
                List<ContentBlockParam> resultBlocks = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.toolUse().isEmpty()) continue;
                    var tu = block.toolUse().get();
                    final String toolName = tu.name();
                    final String toolUseId = tu.id();
                    final Map<String, Object> input = parseInput(tu._input());
                    Platform.runLater(() -> listener.onToolCall(toolName, input));
                    final Tools.Result r = Tools.execute(toolName, input, cwd);
                    Platform.runLater(() -> listener.onToolResult(toolName, r));
                    resultBlocks.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUseId)
                                    .content(r.content())
                                    .isError(r.error())
                                    .build()));
                }
                synchronized (history) {
                    history.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(resultBlocks)
                            .build());
                }
                // Loop: ask Claude what to do with the tool results.
            }
        } catch (Throwable t) {
            LOG.error("agent loop failed", t);
            Platform.runLater(() -> listener.onError(t));
        }
    }

    private List<ContentBlockParam> toParams(List<ContentBlock> content) {
        List<ContentBlockParam> out = new ArrayList<>();
        for (ContentBlock block : content) {
            block.text().ifPresent(t ->
                    out.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(t.text()).build())));
            block.toolUse().ifPresent(tu ->
                    out.add(ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                    .id(tu.id())
                                    .name(tu.name())
                                    .input(tu._input())
                                    .build())));
        }
        return out;
    }

    private Map<String, Object> parseInput(JsonValue json) {
        try {
            // JsonValue serializes to JSON via toString() on this SDK. If
            // a future SDK version changes that, switch to writeValueAsString.
            String s = json.toString();
            return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warn("could not parse tool input: {}", json, e);
            return Map.of();
        }
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
