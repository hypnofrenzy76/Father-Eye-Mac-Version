package io.fathereye.setup;

import javafx.application.Application;

/**
 * Non-Application bootstrap so jpackage can wire {@code Application.launch}
 * via a plain main class on JDK 9+.
 */
public final class SetupLauncher {

    private SetupLauncher() {}

    public static void main(String[] args) {
        // Set JavaFX rendering pipeline BEFORE Application.launch so the
        // HD 6750M (non-Metal) on the user's Mid 2011 iMac picks the
        // OpenGL pipeline (prism-es2) rather than failing on Metal
        // probe. Same flags as the panel and the jpackage --java-options
        // bake-in; setting them here too is belt-and-suspenders for
        // running the setup app from `gradle run`.
        System.setProperty("prism.order", "es2,sw");
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.allowhidpi", "false");
        Application.launch(SetupApp.class, args);
    }
}
