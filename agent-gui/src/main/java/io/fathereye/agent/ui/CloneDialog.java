package io.fathereye.agent.ui;

import io.fathereye.agent.git.GitOps;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Modal dialog: clone a GitHub (or any git) repo into a chosen folder. */
public final class CloneDialog {

    /** Returns the cloned-into directory on success, or null on cancel/failure. */
    public static Path show(Window owner) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Clone repository");

        Label title = new Label("Clone a repository");
        title.getStyleClass().add("welcome-title");

        TextField urlField = new TextField();
        urlField.setPromptText("https://github.com/owner/repo.git or git@github.com:owner/repo.git");
        urlField.getStyleClass().add("input-area");

        Path defaultParent = Paths.get(System.getProperty("user.home"), "Documents");
        if (!defaultParent.toFile().exists()) defaultParent = Paths.get(System.getProperty("user.home"));
        Path[] parentHolder = { defaultParent };
        Label parentLabel = new Label(defaultParent.toString());
        parentLabel.getStyleClass().add("settings-cwd");
        Button parentBtn = new Button("Choose…");
        parentBtn.getStyleClass().add("ghost-button");
        parentBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Clone into…");
            dc.setInitialDirectory(parentHolder[0].toFile());
            File picked = dc.showDialog(st);
            if (picked != null) {
                parentHolder[0] = picked.toPath();
                parentLabel.setText(picked.toString());
            }
        });
        HBox parentRow = new HBox(8, parentLabel, parentBtn);
        HBox.setHgrow(parentLabel, Priority.ALWAYS);
        parentLabel.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(20, 20);
        spinner.setVisible(false);
        Label status = new Label("");
        status.setWrapText(true);
        status.getStyleClass().add("welcome-status");
        HBox statusRow = new HBox(8, spinner, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Path[] resultHolder = { null };

        Button cloneBtn = new Button("Clone");
        cloneBtn.getStyleClass().add("send-button");
        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("ghost-button");
        cancelBtn.setOnAction(e -> st.close());

        cloneBtn.setOnAction(e -> {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isEmpty()) { status.setText("Enter a repo URL."); return; }
            Path parent = parentHolder[0];
            String name = repoName(url);
            Path dest = parent.resolve(name);
            if (dest.toFile().exists()) {
                status.setText(name + " already exists in " + parent + ". Pick a different folder.");
                return;
            }
            cloneBtn.setDisable(true);
            cancelBtn.setDisable(true);
            spinner.setVisible(true);
            status.setText("Cloning " + url + " …");
            Thread t = new Thread(() -> {
                GitOps.Result r = GitOps.clone(url, dest);
                Platform.runLater(() -> {
                    spinner.setVisible(false);
                    if (r.ok()) {
                        resultHolder[0] = dest;
                        st.close();
                    } else {
                        cloneBtn.setDisable(false);
                        cancelBtn.setDisable(false);
                        status.setText("Clone failed (exit " + r.exit() + "):\n" + r.output());
                    }
                });
            }, "git-clone");
            t.setDaemon(true);
            t.start();
        });

        HBox actions = new HBox(8, cancelBtn, cloneBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(14,
                title,
                new Label("Repository URL"),
                urlField,
                new Label("Clone into"),
                parentRow,
                statusRow,
                actions);
        box.getStyleClass().add("root");
        box.setPadding(new Insets(28));

        Scene s = new Scene(box, 560, 380);
        s.getStylesheets().add(CloneDialog.class.getResource("/io/fathereye/agent/app.css").toExternalForm());
        st.setScene(s);
        st.showAndWait();
        return resultHolder[0];
    }

    private static String repoName(String url) {
        // Strip ".git" + take the last path/component.
        String u = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf(':'));
        return slash < 0 ? u : u.substring(slash + 1);
    }
}
