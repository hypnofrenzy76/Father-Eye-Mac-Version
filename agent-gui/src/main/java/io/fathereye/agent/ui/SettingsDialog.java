package io.fathereye.agent.ui;

import io.fathereye.agent.auth.Auth;
import io.fathereye.agent.git.GitHubAuth;
import io.fathereye.agent.prefs.AppPrefs;
import io.fathereye.agent.sync.CloudSync;
import io.fathereye.agent.usage.UsageStats;
import javafx.scene.control.TextField;
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
                              AppPrefs prefs,
                              Consumer<String> liveModelChange,
                              Consumer<Path> liveCwdChange,
                              java.util.function.LongConsumer liveEstimateChange) {
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
        // Token-estimate field. Drives the sidebar progress bar (used
        // tokens / estimate) so the user can see used + ~remaining.
        // Sensible defaults below match Anthropic's published rough
        // numbers for each subscription tier; users tune as needed.
        TextField estField = new TextField(prefs == null ? "1000000"
                : Long.toString(prefs.tokenEstimate()));
        estField.setPrefColumnCount(10);
        estField.getStyleClass().add("input-area");
        Runnable applyEst = () -> {
            try {
                long v = Long.parseLong(estField.getText().trim());
                if (v < 0) v = 0;
                if (prefs != null) prefs.setTokenEstimate(v);
                if (liveEstimateChange != null) liveEstimateChange.accept(v);
            } catch (NumberFormatException ignored) {}
        };
        estField.focusedProperty().addListener((obs, was, now) -> { if (!now) applyEst.run(); });
        estField.setOnAction(e -> applyEst.run());
        // Quick-pick tier buttons -- one click sets the estimate to a
        // sensible default for that subscription level.
        Button proBtn = new Button("Pro (300K)");
        proBtn.getStyleClass().add("ghost-button");
        proBtn.setOnAction(e -> { estField.setText("300000"); applyEst.run(); });
        Button max5Btn = new Button("Max 5× (1.5M)");
        max5Btn.getStyleClass().add("ghost-button");
        max5Btn.setOnAction(e -> { estField.setText("1500000"); applyEst.run(); });
        Button max20Btn = new Button("Max 20× (6M)");
        max20Btn.getStyleClass().add("ghost-button");
        max20Btn.setOnAction(e -> { estField.setText("6000000"); applyEst.run(); });
        HBox tierRow = new HBox(8, proBtn, max5Btn, max20Btn);
        tierRow.setAlignment(Pos.CENTER_LEFT);
        Label estHint = new Label(
                "Sidebar bar fills against tokens-used / this estimate so you can "
                + "eyeball how much of your subscription window you've burned. The "
                + "Claude.ai subscription quota isn't queryable through the Claude "
                + "Code CLI, so the numbers below are rough per-5-hour-window "
                + "estimates. Pick the tier that matches your plan, or type your own.\n"
                + "Set to 0 to hide the bar.");
        estHint.setWrapText(true);
        estHint.getStyleClass().add("settings-hint");
        VBox usageSection = section("Usage (this session)",
                row(usageLabel, null),
                row("Token estimate", estField),
                row(tierRow, null),
                row(estHint, null));

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
                "Required to push and pull from private repos over HTTPS. Sign-in "
                + "shows a one-time code, opens GitHub's authorization page in your "
                + "browser, and writes the OAuth token into your Mac's Keychain via "
                + "the GitHub CLI — this app never sees it. After sign-in,\n"
                + "git clone / pull / push for HTTPS URLs (https://github.com/...) "
                + "Just Works for both public and private repos. (SSH URLs — "
                + "git@github.com:owner/repo.git — go through your existing SSH "
                + "keys instead and don't need this.)");
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
                        // One button does it all: detect macOS version,
                        // download the matching gh release tarball, drop
                        // it in ~/.local/bin/gh, and -- on success --
                        // immediately open the sign-in dialog so the user
                        // doesn't have to click "Sign in" as a separate
                        // step. No admin password required.
                        ghSignInBtn.setText("Install GitHub CLI");
                        ghSignInBtn.setOnAction(ev -> {
                            boolean installed = InstallProgressDialog.show(st, ghAuth);
                            if (installed) {
                                ghStatus.setText("GitHub CLI installed. Starting sign-in…");
                                boolean justSignedIn = GitHubLoginDialog.show(st);
                                ghStatus.setText(justSignedIn
                                        ? "Signed in. Refreshing…"
                                        : "Sign-in cancelled. Click Sign in with GitHub when ready.");
                            } else {
                                ghStatus.setText("GitHub CLI was not installed. Try again, or download the .pkg from cli.github.com.");
                            }
                            refreshGhRef[0].run();
                        });
                    } else {
                        ghSignInBtn.setText("Sign in with GitHub");
                        ghSignInBtn.setOnAction(ev -> {
                            // Open the modal that captures gh's device-code
                            // output and shows it in our window. Without
                            // this, a Finder-launched .app has no terminal
                            // for gh to print the code into and the user
                            // sees nothing happen.
                            boolean ok = GitHubLoginDialog.show(st);
                            if (ok) ghStatus.setText("Signed in. Refreshing…");
                            refreshGhRef[0].run();
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

        // ----- iCloud Sync (cross-machine session resume)
        CloudSync cloud = new CloudSync();
        Label syncStatus = new Label("");
        syncStatus.setWrapText(true);
        syncStatus.getStyleClass().add("settings-hint");
        Button syncEnableBtn = new Button("Enable iCloud Sync");
        syncEnableBtn.getStyleClass().add("ghost-button");
        Button syncDisableBtn = new Button("Disable iCloud Sync");
        syncDisableBtn.getStyleClass().add("ghost-button-danger");
        Label syncHint = new Label(
                "Stores your conversation list and Claude Code's session files in "
                + "iCloud Drive instead of locally. Once iCloud finishes uploading, "
                + "any other Mac signed into this iCloud account that runs the app "
                + "sees the same conversations — and clicking one resumes the actual "
                + "Claude Code session there.\n\n"
                + "Caveats: needs iCloud Drive enabled. Concurrent edits from two "
                + "Macs at the same time may produce iCloud conflict files. The "
                + "session-id directories include a hash of the working directory "
                + "path, so resuming a session needs the same path on the other Mac.");
        syncHint.setWrapText(true);
        syncHint.getStyleClass().add("settings-hint");
        Runnable[] refreshSyncRef = new Runnable[1];
        refreshSyncRef[0] = () -> {
            CloudSync.State s = cloud.state();
            switch (s) {
                case ENABLED -> {
                    syncStatus.setText("✓ iCloud Sync is on. Sync folder: " + cloud.syncFolder());
                    syncEnableBtn.setVisible(false); syncEnableBtn.setManaged(false);
                    syncDisableBtn.setVisible(true);  syncDisableBtn.setManaged(true);
                }
                case DISABLED -> {
                    syncStatus.setText("Off. Sessions are stored locally only.");
                    syncEnableBtn.setVisible(true);  syncEnableBtn.setManaged(true);
                    syncDisableBtn.setVisible(false); syncDisableBtn.setManaged(false);
                }
                case NO_ICLOUD -> {
                    syncStatus.setText("iCloud Drive not detected. Enable it in System Settings → Apple ID → iCloud → iCloud Drive, then reopen Settings.");
                    syncEnableBtn.setVisible(false); syncEnableBtn.setManaged(false);
                    syncDisableBtn.setVisible(false); syncDisableBtn.setManaged(false);
                }
            }
        };
        syncEnableBtn.setOnAction(e -> {
            syncEnableBtn.setDisable(true);
            syncStatus.setText("Migrating files into iCloud Drive…");
            new Thread(() -> {
                boolean ok = cloud.enable(line -> Platform.runLater(() ->
                        syncStatus.setText(line)));
                Platform.runLater(() -> {
                    syncEnableBtn.setDisable(false);
                    refreshSyncRef[0].run();
                });
            }, "cloud-enable").start();
        });
        syncDisableBtn.setOnAction(e -> {
            syncDisableBtn.setDisable(true);
            syncStatus.setText("Restoring local copies…");
            new Thread(() -> {
                cloud.disable(line -> Platform.runLater(() ->
                        syncStatus.setText(line)));
                Platform.runLater(() -> {
                    syncDisableBtn.setDisable(false);
                    refreshSyncRef[0].run();
                });
            }, "cloud-disable").start();
        });
        refreshSyncRef[0].run();
        VBox syncSection = section("Cross-machine Sync (iCloud)",
                row(syncStatus, null),
                row(syncHint, null),
                row(null, syncEnableBtn),
                row(null, syncDisableBtn));

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
                githubSection, syncSection, cwdSection, aboutSection);
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
