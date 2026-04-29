package io.fathereye.panel;

import javafx.application.Application;

/**
 * Non-Application entry point so {@link App#main(String[])} can be invoked
 * by Java's launcher without requiring the application class to be on the
 * module path. JavaFX 9+ otherwise refuses to launch a class that extends
 * {@link Application} via its own main when not modular.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
