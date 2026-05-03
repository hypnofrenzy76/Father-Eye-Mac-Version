package io.fathereye.agent.ui;

import io.fathereye.agent.git.GitHubAuth;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal that runs {@link GitHubAuth#autoInstall} and streams its log
 * lines into a scrolling TextArea so the user can see what's happening
 * while Homebrew (and then gh) is installed. macOS will pop up its
 * native admin-password sheet during the Homebrew bootstrap — that
 * comes from the underlying osascript {@code do shell script ... with
 * administrator privileges} call, not from this dialog.
 */
public final class InstallProgressDialog {

    private static final Logger LOG = LoggerFactory.getLogger(InstallProgressDialog.class);

    /** Returns true if gh is available after the install. */
    public static boolean show(Window owner, GitHubAuth auth) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Install GitHub CLI");

        Label title = new Label("Installing GitHub CLI");
        title.getStyleClass().add("welcome-title");

        Label subtitle = new Label(
                "Setting up Homebrew and the GitHub CLI so you can sign in.\n"
                + "macOS will prompt for your account password to authorize Homebrew.");
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("welcome-subtitle");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("install-log");
        logArea.setPrefHeight(220);

        ScrollPane sp = new ScrollPane(logArea);
        sp.setFitToWidth(true);
        VBox.setVgrow(sp, Priority.ALWAYS);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(18, 18);
        Label status = new Label("Starting…");
        status.getStyleClass().add("welcome-status");
        status.setWrapText(true);
        HBox.setHgrow(status, Priority.ALWAYS);
        status.setMaxWidth(Double.MAX_VALUE);

        Button closeBtn = new Button("Cancel");
        closeBtn.getStyleClass().add("ghost-button");

        HBox bottom = new HBox(8, spinner, status, closeBtn);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(14, title, subtitle, logArea, bottom);
        box.setPadding(new Insets(28));
        box.getStyleClass().add("root");

        Scene scene = new Scene(box, 640, 480);
        scene.getStylesheets().add(InstallProgressDialog.class.getResource("/io/fathereye/agent/app.css").toExternalForm());
        st.setScene(scene);

        boolean[] success = { false };
        Thread[] worker = { null };

        closeBtn.setOnAction(e -> {
            if (worker[0] != null) worker[0].interrupt();
            st.close();
        });

        worker[0] = new Thread(() -> {
            boolean ok;
            try {
                ok = auth.autoInstall(line -> Platform.runLater(() -> {
                    logArea.appendText(line + "\n");
                    // Keep the most recent line as the status line too.
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) status.setText(abbreviate(trimmed, 80));
                }));
            } catch (Exception ex) {
                LOG.error("autoInstall failed", ex);
                Platform.runLater(() -> logArea.appendText("\nERROR: " + ex.getMessage() + "\n"));
                ok = false;
            }
            final boolean done = ok;
            success[0] = done;
            Platform.runLater(() -> {
                spinner.setVisible(false);
                if (done) {
                    status.setText("All set. You can sign in now.");
                    closeBtn.setText("Continue");
                } else {
                    status.setText("Install did not complete. See the log above.");
                    closeBtn.setText("Close");
                }
            });
        }, "gh-auto-install");
        worker[0].setDaemon(true);
        worker[0].start();

        st.showAndWait();
        if (worker[0] != null && worker[0].isAlive()) worker[0].interrupt();
        return success[0];
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
