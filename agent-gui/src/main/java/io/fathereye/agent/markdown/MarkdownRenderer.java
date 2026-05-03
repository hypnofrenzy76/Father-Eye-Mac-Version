package io.fathereye.agent.markdown;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Converts a Markdown string to a {@link VBox} of JavaFX nodes.
 *
 * <p>No HTML, no WebView. Block elements are stacked in a VBox, inline
 * runs flow into a {@link TextFlow}. CSS classes attached here are styled
 * by {@code app.css}; the renderer only decides node structure and class
 * names, never colors or fonts.
 *
 * <p>Supported: paragraphs, headings, ordered/unordered lists,
 * blockquotes, fenced + indented code blocks, thematic breaks, inline
 * bold / italic / code / links / line breaks. Tables and HTML blocks
 * fall through as plain text — Claude rarely emits them in chat.
 */
public final class MarkdownRenderer {

    private static final Parser PARSER = Parser.builder().build();

    private MarkdownRenderer() {}

    public static VBox render(String markdown) {
        VBox body = new VBox();
        body.getStyleClass().add("md-body");
        Document doc = (Document) PARSER.parse(markdown);
        BlockBuilder builder = new BlockBuilder(body);
        doc.accept(builder);
        return body;
    }

    /** Builds inline text runs into a single {@link TextFlow}. */
    private static final class InlineBuilder extends AbstractVisitor {
        private final TextFlow flow = new TextFlow();
        // Style classes for nested emphasis are tracked on a stack so
        // **bold *italic*** correctly attaches both classes to the inner
        // run. Each new Text node copies the current stack's union.
        private final Deque<String> styleStack = new ArrayDeque<>();

        InlineBuilder() {
            flow.getStyleClass().add("md-inline");
        }

        TextFlow getFlow() { return flow; }

        private Text newText(String s) {
            Text t = new Text(s);
            for (String cls : styleStack) t.getStyleClass().add(cls);
            flow.getChildren().add(t);
            return t;
        }

        @Override public void visit(org.commonmark.node.Text node) { newText(node.getLiteral()); }
        @Override public void visit(SoftLineBreak n) { newText(" "); }
        @Override public void visit(HardLineBreak n) { newText("\n"); }

        @Override public void visit(Emphasis n) {
            styleStack.push("md-italic");
            visitChildren(n);
            styleStack.pop();
        }
        @Override public void visit(StrongEmphasis n) {
            styleStack.push("md-bold");
            visitChildren(n);
            styleStack.pop();
        }
        @Override public void visit(Code n) {
            Text t = newText(n.getLiteral());
            t.getStyleClass().add("md-inline-code");
        }
        @Override public void visit(Link n) {
            // No external open on the 2011 iMac — render the link text
            // in coral and append the URL in parens. User can copy/paste.
            styleStack.push("md-link");
            visitChildren(n);
            styleStack.pop();
            String dest = n.getDestination();
            if (dest != null && !dest.isEmpty()) {
                Text t = newText(" (" + dest + ")");
                t.getStyleClass().add("md-link-url");
            }
        }
    }

    /** Walks block-level nodes, appending each one to a parent VBox. */
    private static final class BlockBuilder extends AbstractVisitor {
        private final VBox parent;

        BlockBuilder(VBox parent) { this.parent = parent; }

        private TextFlow inline(org.commonmark.node.Node block) {
            InlineBuilder ib = new InlineBuilder();
            org.commonmark.node.Node child = block.getFirstChild();
            while (child != null) {
                child.accept(ib);
                child = child.getNext();
            }
            return ib.getFlow();
        }

        @Override public void visit(Paragraph n) {
            TextFlow tf = inline(n);
            tf.getStyleClass().add("md-paragraph");
            parent.getChildren().add(tf);
        }

        @Override public void visit(Heading n) {
            TextFlow tf = inline(n);
            tf.getStyleClass().add("md-heading");
            tf.getStyleClass().add("md-h" + n.getLevel());
            parent.getChildren().add(tf);
        }

        @Override public void visit(BlockQuote n) {
            VBox box = new VBox();
            box.getStyleClass().add("md-blockquote");
            BlockBuilder inner = new BlockBuilder(box);
            visitChildren(n, inner);
            parent.getChildren().add(box);
        }

        @Override public void visit(BulletList n) { renderList(n, false, 1); }
        @Override public void visit(OrderedList n) { renderList(n, true, n.getStartNumber()); }

        private void renderList(org.commonmark.node.Node listNode, boolean ordered, int start) {
            VBox box = new VBox();
            box.getStyleClass().add("md-list");
            int i = start;
            org.commonmark.node.Node child = listNode.getFirstChild();
            while (child != null) {
                if (child instanceof ListItem item) {
                    HBox row = new HBox();
                    row.getStyleClass().add("md-list-item");
                    Text bullet = new Text(ordered ? (i + ". ") : "• ");
                    bullet.getStyleClass().add("md-list-marker");
                    TextFlow markerFlow = new TextFlow(bullet);
                    markerFlow.getStyleClass().add("md-list-marker-flow");
                    VBox content = new VBox();
                    content.getStyleClass().add("md-list-content");
                    HBox.setHgrow(content, Priority.ALWAYS);
                    BlockBuilder inner = new BlockBuilder(content);
                    visitChildren(item, inner);
                    row.getChildren().addAll(markerFlow, content);
                    box.getChildren().add(row);
                    i++;
                }
                child = child.getNext();
            }
            parent.getChildren().add(box);
        }

        @Override public void visit(FencedCodeBlock n) {
            parent.getChildren().add(codeBlock(n.getLiteral(), n.getInfo()));
        }
        @Override public void visit(IndentedCodeBlock n) {
            parent.getChildren().add(codeBlock(n.getLiteral(), null));
        }
        private Node codeBlock(String literal, String lang) {
            VBox box = new VBox();
            box.getStyleClass().add("md-code-block");

            // Header row: optional language label on the left, Copy
            // button on the right. The button is the workaround for
            // JavaFX 17's TextFlow not supporting text selection.
            HBox header = new HBox();
            header.getStyleClass().add("md-code-lang-row");
            if (lang != null && !lang.isBlank()) {
                Text label = new Text(lang.trim());
                label.getStyleClass().add("md-code-lang");
                TextFlow labelFlow = new TextFlow(label);
                header.getChildren().add(labelFlow);
            }
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            header.getChildren().add(spacer);

            final String code = stripTrailingNewline(literal);
            Button copyBtn = new Button("Copy");
            copyBtn.getStyleClass().add("md-code-copy");
            copyBtn.setOnAction(e -> {
                Clipboard cb = Clipboard.getSystemClipboard();
                ClipboardContent cc = new ClipboardContent();
                cc.putString(code);
                cb.setContent(cc);
            });
            header.getChildren().add(copyBtn);
            box.getChildren().add(header);

            Text body = new Text(code);
            body.getStyleClass().add("md-code");
            TextFlow bodyFlow = new TextFlow(body);
            bodyFlow.getStyleClass().add("md-code-body");
            box.getChildren().add(bodyFlow);
            return box;
        }

        @Override public void visit(ThematicBreak n) {
            Region hr = new Region();
            hr.getStyleClass().add("md-hr");
            parent.getChildren().add(hr);
        }

        // The default visitChildren keeps the same visitor; we override
        // to let subtree builders run with their own VBox parent.
        private static void visitChildren(org.commonmark.node.Node parent, BlockBuilder visitor) {
            org.commonmark.node.Node child = parent.getFirstChild();
            while (child != null) {
                child.accept(visitor);
                child = child.getNext();
            }
        }

        private static String stripTrailingNewline(String s) {
            if (s == null) return "";
            if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
            return s;
        }
    }
}
