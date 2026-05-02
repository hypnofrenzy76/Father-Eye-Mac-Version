package io.fathereye.agent;

import io.fathereye.agent.agent.AgentService;
import io.fathereye.agent.agent.Tools;
import io.fathereye.agent.ui.ChatPane;
import io.fathereye.agent.ui.MessageBlock;
import io.fathereye.agent.ui.ToolCallCard;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
 * JavaFX Application for the Claude for High Sierra. Self-contained — no
 * WebView, no embedded browser. Uses {@link MessageBlock} +
 * {@link io.fathereye.agent.markdown.MarkdownRenderer} to render
 * conversation turns as native FX nodes.
 */
public final class AgentApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(AgentApp.class);
    private static final String DEFAULT_MODEL = "claude-opus-4-7";
    private static final List<String> MODELS = List.of(
            "claude-opus-4-7",
            "claude-sonnet-4-6",
            "claude-haiku-4-5"
    );

    private AgentService agent;
    private ChatPane chat;
    private TextArea input;
    private Button sendButton;
    private Label statusLabel;

    /** Most-recent assistant block; new text + tool cards land here. */
    private MessageBlock currentAssistant;
    /** Tool cards awaiting their result, in dispatch order. */
    private final Deque<ToolCallCard> pendingTools = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        Path cwd = Paths.get(System.getProperty("user.home")).toAbsolutePath();
        String startModel = System.getenv().getOrDefault("CLAUDE_MODEL", DEFAULT_MODEL);

        // Fail fast and visibly if the API key is missing — otherwise the
        // SDK throws on first request and the user sees a stack trace
        // dialog with no obvious remediation.
        if (System.getenv("ANTHROPIC_API_KEY") == null
                || System.getenv("ANTHROPIC_API_KEY").isBlank()) {
            stage.setScene(missingKeyScene());
            stage.setTitle("Claude for High Sierra");
            stage.show();
            return;
        }

        agent = new AgentService(cwd, startModel);

        chat = new ChatPane();
        VBox.setVgrow(chat, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setTop(buildTopBar(cwd));
        root.setCenter(chat);
        root.setBottom(buildInputBar());

        Scene scene = new Scene(root, 880, 720);
        scene.getStylesheets().add(
                getClass().getResource("/io/fathereye/agent/app.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Claude for High Sierra");
        stage.setMinWidth(560);
        stage.setMinHeight(420);
        stage.setOnCloseRequest(e -> {
            if (agent != null) agent.shutdown();
        });
        stage.show();

        Platform.runLater(() -> input.requestFocus());
    }

    private HBox buildTopBar(Path cwd) {
        Label title = new Label("Claude for High Sierra");
        title.getStyleClass().add("app-title");

        Label cwdLabel = new Label(cwd.toString());
        cwdLabel.getStyleClass().add("cwd-label");

        ChoiceBox<String> modelPicker = new ChoiceBox<>();
        modelPicker.getItems().addAll(MODELS);
        modelPicker.setValue(agent.getModel());
        modelPicker.getStyleClass().add("model-picker");
        modelPicker.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) agent.setModel(newV);
        });

        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().add("ghost-button");
        clearBtn.setOnAction(e -> {
            chat.clear();
            agent.clearHistory();
            currentAssistant = null;
            pendingTools.clear();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("ready");
        statusLabel.getStyleClass().add("status-label");

        HBox bar = new HBox(12, title, cwdLabel, spacer, statusLabel, modelPicker, clearBtn);
        bar.getStyleClass().add("top-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 20, 14, 20));
        return bar;
    }

    private HBox buildInputBar() {
        input = new TextArea();
        input.setPromptText("Message Claude…  (Enter to send, Shift+Enter for newline)");
        input.getStyleClass().add("input-area");
        input.setWrapText(true);
        input.setPrefRowCount(2);
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onInputKey);
        HBox.setHgrow(input, Priority.ALWAYS);

        sendButton = new Button("Send");
        sendButton.getStyleClass().add("send-button");
        sendButton.setOnAction(e -> submit());
        sendButton.setDefaultButton(false); // we handle Enter ourselves

        HBox bar = new HBox(10, input, sendButton);
        bar.getStyleClass().add("input-bar");
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(12, 20, 18, 20));
        return bar;
    }

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

        currentAssistant = null;
        pendingTools.clear();

        agent.send(text, new AgentService.Listener() {
            @Override public void onAssistantText(String markdown) {
                ensureAssistant();
                currentAssistant.appendMarkdown(markdown);
            }
            @Override public void onToolCall(String name, Map<String, Object> input) {
                ensureAssistant();
                ToolCallCard card = new ToolCallCard(name, input);
                currentAssistant.appendToolCall(card);
                pendingTools.add(card);
            }
            @Override public void onToolResult(String toolName, Tools.Result result) {
                ToolCallCard card = pendingTools.poll();
                if (card != null) card.setResult(result);
            }
            @Override public void onTurnComplete() {
                setBusy(false);
                input.requestFocus();
            }
            @Override public void onError(Throwable t) {
                LOG.error("agent error", t);
                MessageBlock errBlock = new MessageBlock(MessageBlock.Role.ERROR);
                errBlock.appendPlain(t.getClass().getSimpleName() + ": " + t.getMessage());
                chat.addMessage(errBlock);
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

    private Scene missingKeyScene() {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.getStyleClass().add("root");

        Label title = new Label("ANTHROPIC_API_KEY not set");
        title.getStyleClass().add("app-title");

        Label body = new Label(
                "Set your Anthropic API key before launching:\n\n"
                        + "  export ANTHROPIC_API_KEY=sk-ant-...\n"
                        + "  open \"Claude for High Sierra.app\"\n\n"
                        + "Or run the app from a terminal that already has the key in its environment.\n\n"
                        + "Get a key at https://console.anthropic.com/settings/keys");
        body.setWrapText(true);
        body.getStyleClass().add("missing-key-body");

        box.getChildren().addAll(title, body);
        Scene s = new Scene(box, 560, 360);
        s.getStylesheets().add(
                getClass().getResource("/io/fathereye/agent/app.css").toExternalForm());
        return s;
    }
}
