package io.fathereye.agent.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/**
 * Scrollable list of {@link MessageBlock}s. Auto-scrolls to the bottom
 * whenever the content height grows so the latest message stays visible.
 */
public final class ChatPane extends ScrollPane {

    private final VBox column = new VBox();

    public ChatPane() {
        getStyleClass().add("chat-pane");
        column.getStyleClass().add("chat-column");
        column.setAlignment(Pos.TOP_CENTER);
        setContent(column);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);

        // Auto-scroll: defer to the next pulse so layout has measured the
        // newly added content before we set vvalue. setVvalue(1.0) before
        // layout updates leaves the scrollbar one screen short.
        column.heightProperty().addListener((obs, oldH, newH) ->
                Platform.runLater(() -> setVvalue(1.0)));
    }

    public void addMessage(MessageBlock block) {
        column.getChildren().add(block);
    }

    public void clear() {
        column.getChildren().clear();
    }
}
