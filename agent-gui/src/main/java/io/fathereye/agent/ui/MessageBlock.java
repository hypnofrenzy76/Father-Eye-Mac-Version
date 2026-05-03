package io.fathereye.agent.ui;

import io.fathereye.agent.markdown.MarkdownRenderer;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One conversation turn, rendered. Holds a role label ("YOU" / "CLAUDE")
 * and a body VBox that text + tool-call cards get appended to. Tracks
 * the raw markdown internally so the Copy button (and right-click
 * "Copy message" context menu) can put the original text back on the
 * clipboard — JavaFX 17's TextFlow doesn't support text selection
 * natively, so click-to-copy fills that gap.
 */
public final class MessageBlock extends VBox {

    public enum Role { USER, ASSISTANT, ERROR }

    private final VBox body = new VBox();
    private final HBox footer = new HBox();
    private final StringBuilder rawText = new StringBuilder();

    public MessageBlock(Role role) {
        getStyleClass().add("message");
        getStyleClass().add(switch (role) {
            case USER -> "message-user";
            case ASSISTANT -> "message-assistant";
            case ERROR -> "message-error";
        });

        Label label = new Label(switch (role) {
            case USER -> "YOU";
            case ASSISTANT -> "CLAUDE";
            case ERROR -> "ERROR";
        });
        label.getStyleClass().add("message-role");

        body.getStyleClass().add("message-body");

        // Footer stays empty until something is appended — that way an
        // empty assistant block (which can briefly exist between the
        // first event arriving and the first text/tool block landing)
        // doesn't show a "Copy" button with nothing to copy.
        footer.getStyleClass().add("message-footer");
        footer.setVisible(false);
        footer.setManaged(false);

        getChildren().addAll(label, body, footer);

        // Right-click menu. Works as a fallback for users who don't
        // notice the Copy button, and matches macOS expectations.
        ContextMenu menu = new ContextMenu();
        MenuItem copyItem = new MenuItem("Copy message");
        copyItem.setOnAction(e -> copyToClipboard(rawText.toString()));
        menu.getItems().add(copyItem);
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu.show(this, e.getScreenX(), e.getScreenY());
            }
        });
    }

    /** Append rendered markdown content to this message's body. */
    public void appendMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) return;
        if (rawText.length() > 0) rawText.append("\n\n");
        rawText.append(markdown);
        body.getChildren().add(MarkdownRenderer.render(markdown));
        ensureFooter();
    }

    /** Append a tool-call card. Returns it so the caller can fill in the result. */
    public ToolCallCard appendToolCall(ToolCallCard card) {
        body.getChildren().add(card);
        return card;
    }

    /** Append a plain (non-markdown) error string. */
    public void appendPlain(String text) {
        if (text == null) return;
        if (rawText.length() > 0) rawText.append("\n");
        rawText.append(text);
        Label l = new Label(text);
        l.getStyleClass().add("message-plain");
        l.setWrapText(true);
        body.getChildren().add(l);
        ensureFooter();
    }

    private void ensureFooter() {
        if (footer.isManaged()) return;
        Button copyBtn = new Button("Copy");
        copyBtn.getStyleClass().add("message-action");
        copyBtn.setOnAction(e -> copyToClipboard(rawText.toString()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().setAll(spacer, copyBtn);
        footer.setVisible(true);
        footer.setManaged(true);
    }

    static void copyToClipboard(String s) {
        if (s == null) return;
        Clipboard cb = Clipboard.getSystemClipboard();
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        cb.setContent(cc);
    }
}
