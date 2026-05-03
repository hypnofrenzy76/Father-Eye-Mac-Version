package io.fathereye.agent.ui;

import io.fathereye.agent.git.GitHubAuth;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modal dialog that runs {@code gh auth login --web} and surfaces the
 * device-flow one-time code in our own window.
 *
 * <p>Why a custom dialog: {@code gh} prints the code to stdout and
 * expects the user to read it from the terminal before pasting into
 * GitHub's verification page. From a Finder-launched .app there is no
 * terminal — so without this dialog the user sees nothing happen for 30
 * seconds and then a silent failure.
 *
 * <p>Flow:
 * <ol>
 *   <li>Spawn {@code gh auth login --web --hostname github.com
 *       --git-protocol https} with stdin piped so we can answer optional
 *       prompts ("upload SSH key?" → "n").</li>
 *   <li>Tail the merged stdout/stderr stream. When a line matches the
 *       device-code pattern (e.g. {@code XXXX-XXXX}), display it
 *       prominently with a "Copy" button and an "Open GitHub in browser"
 *       button.</li>
 *   <li>The user pastes the code on https://github.com/login/device, gh
 *       detects the OAuth completion, exits 0; we close the dialog and
 *       call back to the caller.</li>
 * </ol>
 */
public final class GitHubLoginDialog {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubLoginDialog.class);
    private static final Pattern DEVICE_CODE = Pattern.compile("([A-Z0-9]{4}-[A-Z0-9]{4})");

    /** Returns true if sign-in completed successfully. */
    public static boolean show(Window owner) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Sign in with GitHub");

        Label title = new Label("Sign in with GitHub");
        title.getStyleClass().add("welcome-title");

        Label step1 = new Label("Starting GitHub CLI…");
        step1.setWrapText(true);
        step1.getStyleClass().add("welcome-subtitle");

        Label codeLabel = new Label("");
        codeLabel.getStyleClass().add("github-code");
        codeLabel.setVisible(false);

        Button copyBtn = new Button("Copy code");
        copyBtn.getStyleClass().add("ghost-button");
        copyBtn.setVisible(false);

        Button openBtn = new Button("Open github.com/login/device");
        openBtn.getStyleClass().add("send-button");
        openBtn.setVisible(false);

        Label status = new Label("");
        status.setWrapText(true);
        status.getStyleClass().add("welcome-status");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(20, 20);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("ghost-button");

        HBox actions = new HBox(8, copyBtn, openBtn);
        actions.setAlignment(Pos.CENTER);

        HBox bottom = new HBox(8, spinner, status, cancelBtn);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(16, title, step1, codeLabel, actions, bottom);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(32));
        box.getStyleClass().add("root");

        Scene scene = new Scene(box, 520, 360);
        scene.getStylesheets().add(GitHubLoginDialog.class.getResource("/io/fathereye/agent/app.css").toExternalForm());
        st.setScene(scene);

        boolean[] success = { false };
        Thread[] worker = { null };

        cancelBtn.setOnAction(e -> {
            if (worker[0] != null) worker[0].interrupt();
            st.close();
        });

        worker[0] = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "gh", "auth", "login",
                        "--web",
                        "--hostname", "github.com",
                        "--git-protocol", "https");
                pb.redirectErrorStream(true);
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                Process p = pb.start();

                // Skip optional "Upload SSH key?" prompt with "n".
                try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                    w.write("n\n");
                    w.flush();
                } catch (IOException ignored) { /* stdin may already be closed */ }

                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                StringBuilder buf = new StringBuilder();
                while ((line = r.readLine()) != null) {
                    LOG.info("[gh auth login] {}", line);
                    buf.append(line).append('\n');
                    final String fl = line;
                    Platform.runLater(() -> step1.setText(fl.isBlank() ? step1.getText() : fl));
                    Matcher m = DEVICE_CODE.matcher(line);
                    if (m.find()) {
                        final String code = m.group(1);
                        Platform.runLater(() -> {
                            step1.setText("Copy this one-time code, then paste it on the GitHub page that opens.");
                            codeLabel.setText(code);
                            codeLabel.setVisible(true);
                            copyBtn.setVisible(true);
                            openBtn.setVisible(true);
                            status.setText("Waiting for GitHub authorization…");
                            copyBtn.setOnAction(e -> {
                                Clipboard cb = Clipboard.getSystemClipboard();
                                ClipboardContent cc = new ClipboardContent();
                                cc.putString(code);
                                cb.setContent(cc);
                                copyBtn.setText("Copied ✓");
                            });
                            openBtn.setOnAction(e ->
                                    GitHubAuth.openInBrowser("https://github.com/login/device"));
                            // Auto-open the browser the first time we see
                            // a code; users overwhelmingly want this.
                            GitHubAuth.openInBrowser("https://github.com/login/device");
                        });
                    }
                }

                boolean done = p.waitFor(300, TimeUnit.SECONDS);
                if (!done) p.destroyForcibly();
                final boolean ok = done && p.exitValue() == 0;

                // gh auth setup-git wires git's credential.helper through
                // gh, which is what makes `git clone https://...private.git`
                // / `git push` work without prompting for a PAT every time.
                // gh auth login already runs this internally on most paths,
                // but rerun explicitly so the wiring is unambiguous.
                if (ok) {
                    try {
                        ProcessBuilder setupPb = new ProcessBuilder("gh", "auth", "setup-git");
                        setupPb.redirectErrorStream(true);
                        Process sp = setupPb.start();
                        sp.waitFor(15, TimeUnit.SECONDS);
                        if (sp.isAlive()) sp.destroyForcibly();
                    } catch (Exception e) {
                        LOG.warn("gh auth setup-git failed (non-fatal): {}", e.toString());
                    }
                }

                success[0] = ok;
                Platform.runLater(() -> {
                    if (ok) {
                        status.setText("Signed in. ✓");
                        st.close();
                    } else {
                        status.setText("Sign-in did not complete. Output:\n" + buf);
                        spinner.setVisible(false);
                    }
                });
            } catch (IOException e) {
                LOG.error("gh auth login failed", e);
                Platform.runLater(() -> {
                    status.setText("Could not run `gh auth login`: " + e.getMessage());
                    spinner.setVisible(false);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "gh-auth-login");
        worker[0].setDaemon(true);
        worker[0].start();

        st.showAndWait();
        if (worker[0] != null && worker[0].isAlive()) worker[0].interrupt();
        return success[0];
    }
}
