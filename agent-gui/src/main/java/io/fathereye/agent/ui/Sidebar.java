package io.fathereye.agent.ui;

import io.fathereye.agent.session.Conversation;
import io.fathereye.agent.session.ConversationStore;
import io.fathereye.agent.usage.UsageStats;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Left-rail conversation list, modeled on the Claude.ai web app's
 * sidebar:
 * <ul>
 *   <li>Title at top</li>
 *   <li>"+ New chat" button</li>
 *   <li>Time-grouped conversation list (Today / Yesterday / Previous 7 Days /
 *       Older)</li>
 *   <li>Settings + sign-in/out + version footer</li>
 * </ul>
 */
public final class Sidebar extends VBox {

    private final ConversationStore store;
    private final VBox listBox = new VBox();
    private final Consumer<Conversation> onSelect;
    private final Runnable onNew;
    private final Runnable onSettings;
    private String selectedId;
    private final ProgressBar usageBar = new ProgressBar(0);
    private final Label usageLabel = new Label("");
    private int messageLimit = 50;

    public Sidebar(ConversationStore store,
                   Consumer<Conversation> onSelect,
                   Runnable onNew,
                   Runnable onSettings) {
        this.store = store;
        this.onSelect = onSelect;
        this.onNew = onNew;
        this.onSettings = onSettings;
        getStyleClass().add("sidebar");
        setMinWidth(240);
        setPrefWidth(260);
        setMaxWidth(280);
        build();
    }

    private void build() {
        Label title = new Label("Claude for High Sierra");
        title.getStyleClass().add("sidebar-title");

        Button newBtn = new Button("+ New chat");
        newBtn.getStyleClass().add("sidebar-new");
        newBtn.setMaxWidth(Double.MAX_VALUE);
        newBtn.setOnAction(e -> onNew.run());

        Label header = new Label("Recent");
        header.getStyleClass().add("sidebar-section-header");

        listBox.getStyleClass().add("sidebar-list");
        ScrollPane scroll = new ScrollPane(listBox);
        scroll.getStyleClass().add("sidebar-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.NEVER);

        Button settingsBtn = new Button("⚙  Settings");
        settingsBtn.getStyleClass().add("sidebar-settings");
        settingsBtn.setMaxWidth(Double.MAX_VALUE);
        settingsBtn.setOnAction(e -> onSettings.run());

        usageLabel.getStyleClass().add("sidebar-usage");
        usageLabel.setMaxWidth(Double.MAX_VALUE);
        usageLabel.setWrapText(true);
        usageBar.getStyleClass().add("sidebar-usage-bar");
        usageBar.setMaxWidth(Double.MAX_VALUE);
        usageBar.setPrefHeight(6);
        VBox usageBox = new VBox(4, usageBar, usageLabel);
        usageBox.getStyleClass().add("sidebar-usage-box");

        VBox top = new VBox(10, title, newBtn);
        top.setPadding(new Insets(20, 14, 12, 14));

        VBox middle = new VBox(8, header, scroll);
        middle.setPadding(new Insets(0, 14, 8, 14));
        VBox.setVgrow(middle, Priority.ALWAYS);

        VBox bottom = new VBox(8, usageBox, settingsBtn);
        bottom.setPadding(new Insets(8, 14, 14, 14));

        getChildren().addAll(top, middle, bottom);
        refresh();
    }

    public void refresh() { Platform.runLater(this::doRefresh); }

    /** Update the message limit the progress bar fills against. */
    public void setMessageLimit(int n) { this.messageLimit = Math.max(0, n); }

    /** Update the usage display from the latest UsageStats snapshot.
     *  Safe to call from any thread. The bar fills against
     *  {@code messages used / messageLimit} (i.e. direct usage), with
     *  the running cost shown in the label as supplemental context. */
    public void setUsage(UsageStats stats) {
        if (stats == null) return;
        long turns = stats.turns();
        double frac = messageLimit <= 0 ? 0 : Math.min(1.0, turns / (double) messageLimit);
        String text = messageLimit <= 0
                ? String.format("%d message%s · %s tokens · $%.2f",
                        turns, turns == 1 ? "" : "s", fmtTokens(stats.totalTokens()), stats.costUsd())
                : String.format("%d / %d messages · %s tokens · $%.2f",
                        turns, messageLimit, fmtTokens(stats.totalTokens()), stats.costUsd());
        boolean over = messageLimit > 0 && turns > messageLimit;
        Platform.runLater(() -> {
            usageBar.setProgress(messageLimit <= 0 ? 0 : frac);
            usageLabel.setText(text);
            if (over) usageBar.getStyleClass().add("sidebar-usage-bar-over");
            else usageBar.getStyleClass().remove("sidebar-usage-bar-over");
        });
    }

    private static String fmtTokens(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.2fM", n / 1_000_000.0);
    }

    private void doRefresh() {
        listBox.getChildren().clear();
        List<Conversation> all = store.list();
        Map<String, List<Conversation>> grouped = groupByDate(all);
        for (Map.Entry<String, List<Conversation>> e : grouped.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Label gh = new Label(e.getKey());
            gh.getStyleClass().add("sidebar-group-header");
            VBox.setMargin(gh, new Insets(8, 0, 4, 0));
            listBox.getChildren().add(gh);
            for (Conversation c : e.getValue()) {
                listBox.getChildren().add(makeRow(c));
            }
        }
        if (all.isEmpty()) {
            Label empty = new Label("No conversations yet.");
            empty.getStyleClass().add("sidebar-empty");
            listBox.getChildren().add(empty);
        }
    }

    public void setSelected(String id) {
        this.selectedId = id;
        for (var node : listBox.getChildren()) {
            if (!(node instanceof HBox row)) continue;
            String rid = (String) row.getProperties().get("conversationId");
            if (rid == null) continue;
            if (rid.equals(id)) row.getStyleClass().add("sidebar-row-selected");
            else row.getStyleClass().remove("sidebar-row-selected");
        }
    }

    private HBox makeRow(Conversation c) {
        Label title = new Label(c.title());
        title.getStyleClass().add("sidebar-row-title");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        Button del = new Button("×");
        del.getStyleClass().add("sidebar-row-delete");
        del.setVisible(false);
        del.setOnAction(e -> {
            store.delete(c.id());
            refresh();
        });

        HBox row = new HBox(6, title, del);
        row.getStyleClass().add("sidebar-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 10, 7, 10));
        row.getProperties().put("conversationId", c.id());
        if (c.id().equals(selectedId)) row.getStyleClass().add("sidebar-row-selected");
        row.setOnMouseEntered(e -> del.setVisible(true));
        row.setOnMouseExited(e -> del.setVisible(false));
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) onSelect.accept(c);
        });
        return row;
    }

    private static Map<String, List<Conversation>> groupByDate(List<Conversation> all) {
        Map<String, List<Conversation>> out = new LinkedHashMap<>();
        out.put("Today", new ArrayList<>());
        out.put("Yesterday", new ArrayList<>());
        out.put("Previous 7 days", new ArrayList<>());
        out.put("Previous 30 days", new ArrayList<>());
        out.put("Older", new ArrayList<>());
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM yyyy");
        for (Conversation c : all) {
            LocalDate d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(c.updatedAt()), ZoneId.systemDefault());
            long days = ChronoUnit.DAYS.between(d, today);
            String key;
            if (days == 0) key = "Today";
            else if (days == 1) key = "Yesterday";
            else if (days <= 7) key = "Previous 7 days";
            else if (days <= 30) key = "Previous 30 days";
            else key = d.format(fmt);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        return out;
    }
}
