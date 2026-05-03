package io.fathereye.agent.ui;

import io.fathereye.agent.auth.Auth;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modal Settings window. Mirrors the layout of Claude.ai's settings panel:
 * left list of sections, right detail panel.
 *
 * <p>Sections: Account (sign in/out), Model, Working directory, About.
 */
public final class SettingsDialog {

    public record Result(String model, Path cwd, boolean signedOut) {}

    public static Result show(Window owner, String currentModel, Path currentCwd,
                              List<String> models, Auth auth, Consumer<String> liveModelChange,
                              Consumer<Path> liveCwdChange) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Settings");
        st.setMinWidth(560);
        st.setMinHeight(420);

        ChoiceBox<String> modelPicker = new ChoiceBox<>();
        modelPicker.getItems().addAll(models);
        modelPicker.setValue(currentModel);
        modelPicker.getStyleClass().add("model-picker");
        modelPicker.valueProperty().addListener((obs, o, n) -> {
            if (n != null && liveModelChange != null) liveModelChange.accept(n);
        });

        Label cwdLabel = new Label(currentCwd.toString());
        cwdLabel.getStyleClass().add("settings-cwd");
        Button cwdBtn = new Button("Choose…");
        cwdBtn.getStyleClass().add("ghost-button");
        Path[] cwdHolder = { currentCwd };
        cwdBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Working directory");
            dc.setInitialDirectory(cwdHolder[0].toFile());
            File picked = dc.showDialog(st);
            if (picked != null) {
                cwdHolder[0] = picked.toPath();
                cwdLabel.setText(picked.toString());
                if (liveCwdChange != null) liveCwdChange.accept(picked.toPath());
            }
        });

        Button signOutBtn = new Button("Sign out");
        signOutBtn.getStyleClass().add("ghost-button-danger");
        boolean[] signedOut = { false };
        signOutBtn.setOnAction(e -> {
            auth.signOut();
            signedOut[0] = true;
            st.close();
        });

        Label about = new Label("Claude for High Sierra\nA JavaFX desktop client for Claude. Drives the\nClaude Code CLI as a subprocess so you can use\nyour Claude.ai Pro or Max subscription.\n\nDesigned for macOS 10.13.6 (High Sierra) and later.");
        about.setWrapText(true);
        about.getStyleClass().add("settings-about");

        VBox accountSection = section("Account",
                row("Authenticated via Claude Code CLI", null),
                row(null, signOutBtn));
        VBox modelSection = section("Model", row("Default model", modelPicker));
        VBox cwdSection = section("Working Directory",
                row("Where Claude reads, writes, and runs commands", null),
                row(cwdLabel, cwdBtn));
        VBox aboutSection = section("About", row(about, null));

        VBox content = new VBox(18, accountSection, modelSection, cwdSection, aboutSection);
        content.setPadding(new Insets(28, 28, 28, 28));
        content.getStyleClass().add("root");

        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("settings-scroll");

        Scene scene = new Scene(sp, 600, 480);
        scene.getStylesheets().add(SettingsDialog.class.getResource("/io/fathereye/agent/app.css").toExternalForm());
        st.setScene(scene);
        st.showAndWait();
        return new Result(modelPicker.getValue(), cwdHolder[0], signedOut[0]);
    }

    private static VBox section(String title, HBox... rows) {
        Label h = new Label(title);
        h.getStyleClass().add("settings-section-header");
        VBox box = new VBox(10);
        box.getStyleClass().add("settings-section");
        box.getChildren().add(h);
        for (HBox r : rows) box.getChildren().add(r);
        return box;
    }

    private static HBox row(Object left, javafx.scene.Node right) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        if (left instanceof javafx.scene.Node n) {
            HBox.setHgrow(n, Priority.ALWAYS);
            row.getChildren().add(n);
        } else if (left instanceof String s) {
            Label l = new Label(s);
            l.getStyleClass().add("settings-label");
            HBox.setHgrow(l, Priority.ALWAYS);
            l.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(l);
        }
        if (right != null) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().add(spacer);
            row.getChildren().add(right);
        }
        return row;
    }
}
