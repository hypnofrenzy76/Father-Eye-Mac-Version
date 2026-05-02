package io.fathereye.agent.ui;

import io.fathereye.agent.agent.Tools;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * One collapsible tool-call entry inside an assistant message body.
 * <p>
 * Header shows {@code name(args)} in monospace. Click the header (or the
 * disclosure arrow) to expand the body, which holds the captured tool
 * output. Errors render with a red bar on the left.
 * <p>
 * No {@link javafx.scene.control.TitledPane} — its default styling adds
 * a heavy box that fights the chat aesthetic, and disabling it via CSS
 * is brittle across JavaFX versions. Custom toggle is ~20 lines and
 * looks right out of the box.
 */
public final class ToolCallCard extends VBox {

    private final VBox bodyBox = new VBox();
    private boolean expanded = false;

    public ToolCallCard(String toolName, Map<String, Object> input) {
        getStyleClass().add("tool-call");

        Text arrow = new Text("▸");
        arrow.getStyleClass().add("tool-call-arrow");

        Text title = new Text(formatHeader(toolName, input));
        title.getStyleClass().add("tool-call-title");

        TextFlow header = new TextFlow(arrow, new Text("  "), title);
        header.getStyleClass().add("tool-call-header");
        header.setOnMouseClicked(e -> toggle(arrow));

        bodyBox.getStyleClass().add("tool-call-body");
        bodyBox.setManaged(false);
        bodyBox.setVisible(false);

        getChildren().addAll(header, bodyBox);
    }

    private static String formatHeader(String name, Map<String, Object> input) {
        if (input == null || input.isEmpty()) return name + "()";
        // Show a compact preview of the arguments. Truncate long string
        // values so the header stays one line.
        String args = input.entrySet().stream()
                .map(e -> e.getKey() + "=" + abbreviate(String.valueOf(e.getValue()), 60))
                .collect(Collectors.joining(", "));
        return name + "(" + args + ")";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "null";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= max) return oneLine;
        return oneLine.substring(0, max - 1) + "…";
    }

    /** Called by AgentService.Listener#onToolResult once the tool returns. */
    public void setResult(Tools.Result result) {
        bodyBox.getChildren().clear();

        ScrollPane scroll = new ScrollPane();
        scroll.getStyleClass().add("tool-call-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMaxHeight(280);

        Text out = new Text(result.content() == null || result.content().isEmpty()
                ? "(no output)" : result.content());
        out.getStyleClass().add("tool-call-output");
        TextFlow outFlow = new TextFlow(out);
        outFlow.getStyleClass().add("tool-call-output-flow");
        scroll.setContent(outFlow);

        bodyBox.getChildren().add(scroll);

        if (result.error()) {
            getStyleClass().add("tool-call-error");
        } else {
            getStyleClass().remove("tool-call-error");
        }
    }

    private void toggle(Text arrow) {
        expanded = !expanded;
        bodyBox.setManaged(expanded);
        bodyBox.setVisible(expanded);
        arrow.setText(expanded ? "▾" : "▸");
    }
}
