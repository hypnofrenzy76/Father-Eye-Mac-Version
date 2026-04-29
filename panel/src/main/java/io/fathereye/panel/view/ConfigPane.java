package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.fathereye.panel.config.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;

/**
 * v0.2 config UI: JSON editor + Save button + Reload button. A form-driven
 * UI per section is a follow-up; for now the JSON view exposes every knob
 * and the user edits in place. Hot-reloads via WatchService on the file
 * (handled in App).
 */
public final class ConfigPane {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final TextArea editor = new TextArea();
    private final Label statusLabel = new Label("");
    private final VBox root = new VBox(6);

    private AppConfig config;

    public ConfigPane() {
        editor.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        Button reload = new Button("Reload from disk");
        Button save = new Button("Save");
        save.setOnAction(e -> save());
        reload.setOnAction(e -> reload());

        HBox bar = new HBox(8, save, reload, statusLabel);
        bar.setPadding(new Insets(4));

        root.setPadding(new Insets(8));
        root.getChildren().addAll(new Label("Config (JSON)"), editor, bar);
        VBox.setVgrow(editor, Priority.ALWAYS);
    }

    public VBox root() { return root; }

    public void bindConfig(AppConfig cfg) {
        this.config = cfg;
        showCurrent();
    }

    private void showCurrent() {
        if (config == null) return;
        try {
            editor.setText(JSON.writeValueAsString(config));
            statusLabel.setText("");
        } catch (Exception e) {
            statusLabel.setText("show error: " + e.getMessage());
        }
    }

    private void save() {
        if (config == null) return;
        try {
            AppConfig parsed = JSON.readValue(editor.getText(), AppConfig.class);
            // Mutate the bound instance so other subsystems see new values.
            copyInto(parsed, config);
            config.save(AppConfig.defaultPath());
            statusLabel.setText("Saved.");
        } catch (Exception e) {
            statusLabel.setText("Save failed: " + e.getMessage());
        }
    }

    private void reload() {
        try {
            AppConfig parsed = JSON.readValue(Files.readAllBytes(AppConfig.defaultPath()), AppConfig.class);
            copyInto(parsed, config);
            showCurrent();
            statusLabel.setText("Reloaded.");
        } catch (IOException e) {
            statusLabel.setText("Reload failed: " + e.getMessage());
        }
    }

    private static void copyInto(AppConfig src, AppConfig dst) {
        dst.display = src.display;
        dst.alerts = src.alerts;
        dst.serverRuntime = src.serverRuntime;
        dst.bridge = src.bridge;
        dst.history = src.history;
        dst.backup = src.backup;
    }
}
