package io.fathereye.agent.ui;

import io.fathereye.agent.markdown.MarkdownRenderer;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * One conversation turn, rendered. Holds a role label ("YOU" / "CLAUDE")
 * and a body VBox that text + tool-call cards get appended to.
 */
public final class MessageBlock extends VBox {

    public enum Role { USER, ASSISTANT, ERROR }

    private final VBox body = new VBox();

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

        getChildren().addAll(label, body);
    }

    /** Append rendered markdown content to this message's body. */
    public void appendMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) return;
        body.getChildren().add(MarkdownRenderer.render(markdown));
    }

    /** Append a tool-call card. Returns it so the caller can fill in the result. */
    public ToolCallCard appendToolCall(ToolCallCard card) {
        body.getChildren().add(card);
        return card;
    }

    /** Append a plain (non-markdown) error string. */
    public void appendPlain(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("message-plain");
        l.setWrapText(true);
        body.getChildren().add(l);
    }
}
