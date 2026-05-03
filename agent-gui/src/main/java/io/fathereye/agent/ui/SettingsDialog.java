package io.fathereye.agent.ui;

import io.fathereye.agent.auth.Auth;
import io.fathereye.agent.git.GitHubAuth;
import io.fathereye.agent.usage.UsageStats;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import java.util.Map;
import java.util.function.Consumer;

/**
 * Modal Settings dialog. Sections in order: Account (Claude.ai sign in/out),
 * Usage, Model, GitHub, Working Directory, About.
 */
public final class SettingsDialog {

    public record Result(String model, Path cwd, boolean signedOut) {}

    private static final Map<String, String> MODEL_DESCRIPTIONS = Map.of(
            "claude-opus-4-7",   "Most capable. Best for hard coding, agentic, and reasoning tasks.",
            "claude-opus-4-6",   "Previous-generation Opus. Adaptive thinking, 128K output.",
            "claude-sonnet-4-6", "Best speed/intelligence balance. Fastest for everyday work.",
            "claude-haiku-4-5",  "Fastest and cheapest. Good for simple tasks.");

    public static Result show(Window owner, String currentModel, Path currentCwd,
                              List<String> models, Auth auth, UsageStats usage,
                              Consumer<String> liveModelChange,
                              Consumer<Path> liveCwdChange) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Settings");
        st.setMinWidth(620);
        st.setMinHeight(560);

        // ----- Account
        Button claudeSignOutBtn = new Button("Sign out of Claude");
        claudeSignOutBtn.getStyleClass().add("ghost-button-danger");
        boolean[] signedOut = { false };
        claudeSignOutBtn.setOnAction(e -> {
            auth.signOut();
            signedOut[0] = true;
            st.close();
        });
        VBox accountSection = section("Account",
                row("Authenticated via Claude Code CLI (claude /login)", null),
                row(null, claudeSignOutBtn));

        // ----- Usage
        Label usageLabel = new Label(usage == null ? "" : usage.detailedDisplay());
        usageLabel.getStyleClass().add("settings-usage");
        VBox usageSection = section("Usage (this session)", row(usageLabel, null));

        // ----- Model
        ChoiceBox<String> modelPicker = new ChoiceBox<>();
        modelPicker.getItems().addAll(models);
        modelPicker.setValue(currentModel);
        modelPicker.getStyleClass().add("model-picker");
        Label modelDesc = new Label(MODEL_DESCRIPTIONS.getOrDefault(currentModel, ""));
        modelDesc.setWrapText(true);
        modelDesc.getStyleClass().add("settings-hint");
        modelPicker.valueProperty().addListener((obs, o, n) -> {
            if (n != null && liveModelChange != null) liveModelChange.accept(n);
            modelDesc.setText(MODEL_DESCRIPTIONS.getOrDefault(n, ""));
        });
        VBox modelSection = section("Model",
                row("Default model", modelPicker),
                row(modelDesc, null));

        // ----- GitHub
        Label ghStatus = new Label("Checking…");
        ghStatus.setWrapText(true);
        ghStatus.getStyleClass().add("settings-hint");
        Button ghSignInBtn = new Button("Sign in with GitHub");
        ghSignInBtn.getStyleClass().add("ghost-button");
        Button ghSignOutBtn = new Button("Sign out of GitHub");
        ghSignOutBtn.getStyleClass().add("ghost-button-danger");
        Label ghHint = new Label(
                "Required to push and pull from private repos. Sign-in opens your "
                + "browser through the GitHub CLI (gh) and stores the token in your "
                + "Mac's Keychain — this app never sees it.");
        ghHint.setWrapText(true);
        ghHint.getStyleClass().add("settings-hint");

        GitHubAuth ghAuth = new GitHubAuth();
        // Recursive Runnable pattern: hold the lambda in a 1-element array
        // so it can re-invoke itself inside its own callback chain.
        Runnable[] refreshGhRef = new Runnable[1];
        refreshGhRef[0] = () -> {
            ghStatus.setText("Checking…");
            new Thread(() -> {
                GitHubAuth.Status s = ghAuth.inspect();
                Platform.runLater(() -> {
                    ghStatus.setText(formatGhStatus(s));
                    boolean signedIn = s.state() == GitHubAuth.State.SIGNED_IN_GH;
                    ghSignInBtn.setVisible(!signedIn);
                    ghSignInBtn.setManaged(!signedIn);
                    ghSignOutBtn.setVisible(signedIn);
                    ghSignOutBtn.setManaged(signedIn);
                    if (s.state() == GitHubAuth.State.NO_GH_CLI) {
                        ghSignInBtn.setText("Install GitHub CLI…");
                        ghSignInBtn.setOnAction(ev -> ghStatus.setText(
                                "Install with `brew install gh`, then reopen Settings."));
                    } else {
                        ghSignInBtn.setText("Sign in with GitHub");
                        ghSignInBtn.setOnAction(ev -> {
                            ghSignInBtn.setDisable(true);
                            ghStatus.setText("Opening browser. Complete sign-in there…");
                            new Thread(() -> {
                                boolean ok;
                                try { ok = ghAuth.runGhLogin(); }
                                catch (Exception ex) { ok = false; }
                                final boolean done = ok;
                                Platform.runLater(() -> {
                                    ghSignInBtn.setDisable(false);
                                    if (done) ghStatus.setText("Signed in. Refreshing…");
                                    refreshGhRef[0].run();
                                });
                            }, "gh-login").start();
                        });
                    }
                });
            }, "gh-inspect").start();
        };
        ghSignOutBtn.setOnAction(e -> {
            ghAuth.signOut();
            refreshGhRef[0].run();
        });
        refreshGhRef[0].run();
        VBox githubSection = section("GitHub",
                row(ghStatus, null),
                row(ghHint, null),
                row(null, ghSignInBtn),
                row(null, ghSignOutBtn));

        // ----- Working Directory
        Label cwdLabel = new Label(currentCwd.toString());
        cwdLabel.getStyleClass().add("settings-cwd");
        cwdLabel.setWrapText(true);
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
        VBox cwdSection = section("Working Directory",
                row("Where Claude reads, writes, and runs commands", null),
                row(cwdLabel, cwdBtn));

        // ----- About
        Label about = new Label("Claude for High Sierra\n"
                + "A JavaFX desktop client for Claude. Drives the Claude Code CLI as a\n"
                + "subprocess so you can use your Claude.ai Pro or Max subscription.\n\n"
                + "Designed for macOS 10.13.6 (High Sierra) and later.");
        about.setWrapText(true);
        about.getStyleClass().add("settings-about");
        VBox aboutSection = section("About", row(about, null));

        VBox content = new VBox(18,
                accountSection, usageSection, modelSection,
                githubSection, cwdSection, aboutSection);
        content.setPadding(new Insets(28, 28, 28, 28));
        content.getStyleClass().add("root");

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("settings-scroll");

        Scene scene = new Scene(sp, 660, 600);
        scene.getStylesheets().add(SettingsDialog.class.getResource("/io/fathereye/agent/app.css").toExternalForm());
        st.setScene(scene);
        st.showAndWait();
        return new Result(modelPicker.getValue(), cwdHolder[0], signedOut[0]);
    }

    private static String formatGhStatus(GitHubAuth.Status s) {
        return switch (s.state()) {
            case SIGNED_IN_GH -> "✓ " + s.detail();
            case SSH_KEYS -> "Using " + s.detail() + " for SSH push/pull. Sign in with the GitHub CLI to also enable HTTPS.";
            case CREDENTIAL_HELPER -> "Using " + s.detail() + ". Sign in with the GitHub CLI to refresh your token.";
            case NO_GH_CLI -> "Not signed in. " + s.detail() + " Install with `brew install gh`.";
            case UNCONFIGURED -> "Not signed in. " + s.detail();
        };
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

    private static HBox row(Object left, Node right) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        if (left instanceof Node n) {
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
