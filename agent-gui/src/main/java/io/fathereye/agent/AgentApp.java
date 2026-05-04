package io.fathereye.agent;

import io.fathereye.agent.agent.AgentService;
import io.fathereye.agent.agent.Tools;
import io.fathereye.agent.auth.Auth;
import io.fathereye.agent.git.GitOps;
import io.fathereye.agent.session.Conversation;
import io.fathereye.agent.session.ConversationStore;
import io.fathereye.agent.ui.ChatPane;
import io.fathereye.agent.ui.CloneDialog;
import io.fathereye.agent.ui.MessageBlock;
import io.fathereye.agent.ui.SettingsDialog;
import io.fathereye.agent.ui.Sidebar;
import io.fathereye.agent.ui.SignInScene;
import io.fathereye.agent.ui.ToolCallCard;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Main JavaFX Application.
 *
 * <p>Top-level scene graph:
 *
 * <pre>
 *   Stage
 *     SignInScene (if !auth.isLoggedIn)  -- transitions to mainScene on success
 *     mainScene
 *       BorderPane
 *         left:   Sidebar (conversation list, +New, ⚙Settings)
 *         center: BorderPane
 *           top:    workspaceBar (cwd + branch + Clone/Pull/Push, model, status)
 *           center: ChatPane (scrollable message column)
 *           bottom: input bar (TextArea + Send)
 * </pre>
 */
public final class AgentApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(AgentApp.class);
    private static final String DEFAULT_MODEL = "claude-opus-4-7";
    // Full Claude 4 family. Opus 4.7 is the default; Sonnet/Haiku
    // available for cheaper / faster sessions.
    private static final List<String> MODELS = List.of(
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5"
    );

    private Auth auth;
    private AgentService agent;
    private ConversationStore store;
    private io.fathereye.agent.prefs.AppPrefs prefs;
    private Conversation current;

    private Stage stage;
    private Sidebar sidebar;
    private ChatPane chat;
    private TextArea input;
    private Button sendButton;
    private Label statusLabel;
    private Label cwdLabel;
    private Label branchLabel;
    private Button pullBtn;
    private Button pushBtn;

    private MessageBlock currentAssistant;
    private final Deque<ToolCallCard> pendingTools = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Claude for High Sierra");
        stage.setMinWidth(820);
        stage.setMinHeight(580);
        stage.setOnCloseRequest(e -> { if (agent != null) agent.shutdown(); });

        String claudePath;
        try { claudePath = Auth.findClaude(); }
        catch (java.io.IOException e) {
            LOG.error("Claude Code CLI not found", e);
            stage.setScene(missingClaudeScene(e.getMessage()));
            stage.show();
            return;
        }
        this.auth = new Auth(claudePath);
        this.store = new ConversationStore();
        this.prefs = new io.fathereye.agent.prefs.AppPrefs();

        if (!auth.isLoggedIn()) {
            stage.setScene(SignInScene.build(auth, this::buildMainScene));
            stage.show();
            return;
        }
        buildMainScene();
        stage.show();
    }

    // ----------------------------------------------------------------- main scene

    private void buildMainScene() {
        Path cwd = Paths.get(System.getProperty("user.home")).toAbsolutePath();
        String startModel = System.getenv().getOrDefault("CLAUDE_MODEL", DEFAULT_MODEL);
        try { agent = new AgentService(cwd, startModel); }
        catch (java.io.IOException e) {
            LOG.error("could not start Claude Code subprocess", e);
            stage.setScene(missingClaudeScene(e.getMessage()));
            return;
        }
        current = store.create(cwd, startModel);

        chat = new ChatPane();
        VBox.setVgrow(chat, Priority.ALWAYS);

        sidebar = new Sidebar(store, this::loadConversation, this::newConversation, this::openSettings);
        sidebar.setSelected(current.id());
        sidebar.setTokenEstimate(prefs.tokenEstimate());
        sidebar.setUsage(agent.usageStats());

        BorderPane center = new BorderPane();
        center.setTop(buildWorkspaceBar(cwd));
        center.setCenter(chat);
        center.setBottom(buildInputBar());

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setLeft(sidebar);
        root.setCenter(center);

        Scene scene = new Scene(root, 1100, 760);
        scene.getStylesheets().add(getClass().getResource("/io/fathereye/agent/app.css").toExternalForm());
        stage.setScene(scene);
        Platform.runLater(() -> input.requestFocus());
    }

    private HBox buildWorkspaceBar(Path cwd) {
        cwdLabel = new Label(cwd.toString());
        cwdLabel.getStyleClass().add("cwd-label");
        cwdLabel.setOnMouseClicked(e -> pickDirectory());

        Button pickBtn = new Button("📁 Pick Folder");
        pickBtn.getStyleClass().add("ghost-button");
        pickBtn.setOnAction(e -> pickDirectory());

        Button cloneBtn = new Button("⤓ Clone Repo");
        cloneBtn.getStyleClass().add("ghost-button");
        cloneBtn.setOnAction(e -> {
            Path cloned = CloneDialog.show(stage);
            if (cloned != null) switchCwd(cloned);
        });

        branchLabel = new Label("");
        branchLabel.getStyleClass().add("branch-label");

        pullBtn = new Button("Pull");
        pullBtn.getStyleClass().add("ghost-button");
        pullBtn.setOnAction(e -> runGit(() -> GitOps.pull(agent.cwd()), "Pull"));

        pushBtn = new Button("Push");
        pushBtn.getStyleClass().add("ghost-button");
        pushBtn.setOnAction(e -> runGit(() -> GitOps.push(agent.cwd()), "Push"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("ready");
        statusLabel.getStyleClass().add("status-label");

        Label modelLabel = new Label(agent.getModel());
        modelLabel.getStyleClass().add("model-label");

        HBox bar = new HBox(10, pickBtn, cwdLabel, cloneBtn, branchLabel, pullBtn, pushBtn,
                spacer, statusLabel, modelLabel);
        bar.getStyleClass().add("workspace-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 18, 12, 18));
        refreshGitState();
        return bar;
    }

    private HBox buildInputBar() {
        input = new TextArea();
        input.setPromptText("Reply to Claude…  (Enter to send, Shift+Enter for newline)");
        input.getStyleClass().add("input-area");
        input.setWrapText(true);
        input.setPrefRowCount(2);
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onInputKey);
        HBox.setHgrow(input, Priority.ALWAYS);

        sendButton = new Button("Send");
        sendButton.getStyleClass().add("send-button");
        sendButton.setOnAction(e -> submit());
        sendButton.setDefaultButton(false);

        HBox bar = new HBox(10, input, sendButton);
        bar.getStyleClass().add("input-bar");
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(12, 20, 18, 20));
        return bar;
    }

    // ----------------------------------------------------------------- actions

    private void onInputKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER && !e.isShiftDown() && !e.isControlDown() && !e.isMetaDown()) {
            e.consume();
            submit();
        }
    }

    private void submit() {
        String text = input.getText();
        if (text == null || text.isBlank()) return;
        text = text.stripTrailing();
        input.clear();
        setBusy(true);

        MessageBlock userBlock = new MessageBlock(MessageBlock.Role.USER);
        userBlock.appendMarkdown(text);
        chat.addMessage(userBlock);
        current.addUser(text);
        store.save(current);
        sidebar.refresh();
        sidebar.setSelected(current.id());

        currentAssistant = null;
        pendingTools.clear();

        final String sent = text;
        agent.send(sent, new AgentService.Listener() {
            @Override public void onAssistantText(String md) {
                ensureAssistant();
                currentAssistant.appendMarkdown(md);
                current.addAssistant(md);
                store.save(current);
            }
            @Override public void onToolCall(String name, Map<String, Object> in) {
                ensureAssistant();
                ToolCallCard card = new ToolCallCard(name, in);
                currentAssistant.appendToolCall(card);
                pendingTools.add(card);
            }
            @Override public void onToolResult(String toolName, Tools.Result result) {
                ToolCallCard card = pendingTools.poll();
                if (card != null) card.setResult(result);
            }
            @Override public void onTurnComplete() {
                // Capture Claude Code's session id if it landed since the
                // last save. With it persisted, loadConversation can
                // --resume this conversation later with full agent state.
                String sid = agent.getCurrentSessionId();
                if (sid != null && !sid.equals(current.claudeSessionId())) {
                    current.setClaudeSessionId(sid);
                    store.save(current);
                }
                setBusy(false);
                input.requestFocus();
                sidebar.refresh();
                sidebar.setSelected(current.id());
                sidebar.setUsage(agent.usageStats());
            }
            @Override public void onError(Throwable t) {
                LOG.error("agent error", t);
                MessageBlock errBlock = new MessageBlock(MessageBlock.Role.ERROR);
                errBlock.appendPlain(t.getClass().getSimpleName() + ": " + t.getMessage());
                chat.addMessage(errBlock);
                current.addError(t.getMessage());
                store.save(current);
                setBusy(false);
                input.requestFocus();
            }
        });
    }

    private void ensureAssistant() {
        if (currentAssistant == null) {
            currentAssistant = new MessageBlock(MessageBlock.Role.ASSISTANT);
            chat.addMessage(currentAssistant);
        }
    }

    private void setBusy(boolean busy) {
        sendButton.setDisable(busy);
        input.setDisable(busy);
        statusLabel.setText(busy ? "thinking…" : "ready");
        if (busy) statusLabel.getStyleClass().add("status-busy");
        else statusLabel.getStyleClass().remove("status-busy");
    }

    private void newConversation() {
        agent.clearHistory();
        chat.clear();
        currentAssistant = null;
        pendingTools.clear();
        current = store.create(agent.cwd(), agent.getModel());
        sidebar.refresh();
        sidebar.setSelected(current.id());
    }

    private void loadConversation(Conversation c) {
        // Two paths:
        //   (a) c has a Claude Code session id we recorded earlier ->
        //       resume that session via `--resume <id>`. The next user
        //       message goes into a Claude Code process that already
        //       knows the prior turns, tool calls, and file state. This
        //       is the "real" continuation.
        //   (b) no session id -> visual review only. The chat shows the
        //       persisted messages but a new user message starts a fresh
        //       Claude Code session.
        chat.clear();
        currentAssistant = null;
        pendingTools.clear();
        for (Conversation.Message m : c.messages()) {
            MessageBlock.Role role = switch (m.role()) {
                case Conversation.Message.USER -> MessageBlock.Role.USER;
                case Conversation.Message.ERROR -> MessageBlock.Role.ERROR;
                default -> MessageBlock.Role.ASSISTANT;
            };
            MessageBlock b = new MessageBlock(role);
            if (role == MessageBlock.Role.ERROR) b.appendPlain(m.text());
            else b.appendMarkdown(m.text());
            chat.addMessage(b);
        }
        current = c;
        sidebar.setSelected(c.id());
        Path repoCwd = c.cwd().isBlank() ? agent.cwd() : Paths.get(c.cwd());
        // Apply cwd / model first (these respawn). resume() also respawns,
        // so order them so the final respawn carries --resume.
        if (!repoCwd.equals(agent.cwd())) agent.setCwd(repoCwd);
        if (!c.model().isBlank() && !c.model().equals(agent.getModel())) agent.setModel(c.model());
        if (c.claudeSessionId() != null && !c.claudeSessionId().isBlank()) {
            agent.resume(c.claudeSessionId());
            statusLabel.setText("resumed");
        } else {
            agent.clearHistory();
        }
        cwdLabel.setText(agent.cwd().toString());
        refreshGitState();
    }

    private void pickDirectory() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Working directory");
        dc.setInitialDirectory(agent.cwd().toFile());
        java.io.File picked = dc.showDialog(stage);
        if (picked != null) switchCwd(picked.toPath());
    }

    private void switchCwd(Path p) {
        agent.setCwd(p);
        current.setCwd(p.toString());
        store.save(current);
        cwdLabel.setText(p.toString());
        refreshGitState();
    }

    private void runGit(java.util.function.Supplier<GitOps.Result> op, String label) {
        String startStatus = statusLabel.getText();
        statusLabel.setText(label + "…");
        Thread t = new Thread(() -> {
            GitOps.Result r = op.get();
            Platform.runLater(() -> {
                MessageBlock b = new MessageBlock(r.ok() ? MessageBlock.Role.ASSISTANT : MessageBlock.Role.ERROR);
                String prefix = label + (r.ok() ? " succeeded:\n\n```\n" : " failed (exit " + r.exit() + "):\n\n```\n");
                b.appendMarkdown(prefix + r.output() + "\n```");
                chat.addMessage(b);
                statusLabel.setText(startStatus);
                refreshGitState();
            });
        }, "git-" + label.toLowerCase());
        t.setDaemon(true);
        t.start();
    }

    private void refreshGitState() {
        Path cwd = agent == null ? null : agent.cwd();
        if (cwd == null || !GitOps.isRepo(cwd)) {
            branchLabel.setText("");
            branchLabel.setVisible(false);
            pullBtn.setVisible(false);
            pushBtn.setVisible(false);
            return;
        }
        String branch = GitOps.currentBranch(cwd);
        branchLabel.setText("⎇ " + branch);
        branchLabel.setVisible(true);
        pullBtn.setVisible(true);
        pushBtn.setVisible(true);
    }

    private void openSettings() {
        SettingsDialog.Result r = SettingsDialog.show(
                stage, agent.getModel(), agent.cwd(), MODELS, auth,
                agent.usageStats(), prefs,
                model -> agent.setModel(model),
                cwd -> { switchCwd(cwd); },
                est -> { sidebar.setTokenEstimate(est); sidebar.setUsage(agent.usageStats()); }
        );
        if (r.signedOut()) {
            agent.shutdown();
            stage.setScene(SignInScene.build(auth, this::buildMainScene));
        }
    }

    // ----------------------------------------------------------------- error scene

    private Scene missingClaudeScene(String detail) {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.getStyleClass().add("root");

        Label title = new Label("Claude Code CLI not found");
        title.getStyleClass().add("welcome-title");

        Label body = new Label(
                "This app drives the Claude Code CLI as a subprocess so it can\n"
                        + "use your Claude.ai Pro or Max subscription instead of API credits.\n\n"
                        + "Install Claude Code, then log in:\n\n"
                        + "  npm install -g @anthropic-ai/claude-code\n"
                        + "  claude /login\n\n"
                        + "If Claude Code is installed at a non-standard path, set CLAUDE_PATH\n"
                        + "to its full location before launching the app.\n\n"
                        + (detail == null ? "" : "Details:\n" + detail));
        body.setWrapText(true);
        body.getStyleClass().add("missing-key-body");

        box.getChildren().addAll(title, body);
        Scene s = new Scene(box, 600, 460);
        s.getStylesheets().add(getClass().getResource("/io/fathereye/agent/app.css").toExternalForm());
        return s;
    }
}
