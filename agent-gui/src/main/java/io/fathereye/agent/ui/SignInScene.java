package io.fathereye.agent.ui;

import io.fathereye.agent.auth.Auth;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Welcome / sign-in scene shown on first launch (or after sign-out).
 *
 * <p>The "Sign in" button runs {@code claude /login} as a subprocess —
 * which opens the user's browser for OAuth — and waits for it to
 * complete. On success, {@code onAuthenticated} is invoked on the FX
 * thread to transition to the main app.
 *
 * <p>The actual credentials never touch our process: Claude Code writes
 * them to {@code ~/.claude/credentials.json}. Each user's machine has
 * its own file. The .app bundle ships no secrets.
 */
public final class SignInScene {

    private static final Logger LOG = LoggerFactory.getLogger(SignInScene.class);

    public static Scene build(Auth auth, Runnable onAuthenticated) {
        Label title = new Label("Welcome to Claude for High Sierra");
        title.getStyleClass().add("welcome-title");

        Label subtitle = new Label("Sign in with your Claude.ai account to start chatting.\n"
                + "Your credentials stay on your Mac — this app never sees or stores them.");
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setMaxWidth(440);

        Label status = new Label("");
        status.getStyleClass().add("welcome-status");

        Button signInBtn = new Button("Sign in to Claude");
        signInBtn.getStyleClass().add("send-button");

        Button skipBtn = new Button("I'm already signed in");
        skipBtn.getStyleClass().add("ghost-button");
        skipBtn.setOnAction(e -> {
            if (auth.isLoggedIn()) onAuthenticated.run();
            else status.setText("Still couldn't find credentials at ~/.claude/credentials.json.");
        });

        signInBtn.setOnAction(e -> {
            signInBtn.setDisable(true);
            skipBtn.setDisable(true);
            status.setText("Opening browser… complete the sign-in there. This window will refresh when you're done.");
            Thread t = new Thread(() -> {
                boolean ok;
                try { ok = auth.runLogin(); }
                catch (Exception ex) { ok = false; LOG.error("login failed", ex); }
                final boolean done = ok;
                Platform.runLater(() -> {
                    if (done) {
                        status.setText("Signed in. Loading…");
                        onAuthenticated.run();
                    } else {
                        signInBtn.setDisable(false);
                        skipBtn.setDisable(false);
                        status.setText("Sign-in did not complete. Try again, or run `claude /login` in a terminal.");
                    }
                });
            }, "claude-login");
            t.setDaemon(true);
            t.start();
        });

        VBox box = new VBox(18, title, subtitle, signInBtn, skipBtn, status);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48));
        box.getStyleClass().add("root");

        Scene s = new Scene(box, 640, 460);
        s.getStylesheets().add(SignInScene.class.getResource("/io/fathereye/agent/app.css").toExternalForm());
        return s;
    }
}
