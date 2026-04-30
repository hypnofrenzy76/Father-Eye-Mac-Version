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
        // Mac fork: set prism rendering flags BEFORE Application.launch so
        // JavaFX 17 picks the OpenGL pipeline (prism-es2) on the user's
        // Mid 2011 iMac with the AMD HD 6750M (non-Metal). The same
        // flags are also baked into Father Eye.app via jpackage's
        // --java-options, but setting them here covers `gradle run`
        // and any case where the .app's Info.plist Java options were
        // stripped (re-signing, Notarization tooling, etc).
        //
        // Caution: Do not call System.setProperty BEFORE the JavaFX
        // module's class loader has had a chance to load the Toolkit.
        // We're safely before any JavaFX touch here — Application.launch
        // is the first FX call, and it reads these properties when
        // Toolkit initialises Prism.
        if (System.getProperty("prism.order") == null) {
            System.setProperty("prism.order", "es2,sw");
        }
        if (System.getProperty("prism.lcdtext") == null) {
            System.setProperty("prism.lcdtext", "false");
        }
        if (System.getProperty("prism.allowhidpi") == null) {
            System.setProperty("prism.allowhidpi", "false");
        }
        // Mac fork (audit 5): cap GPU memory budget so JavaFX evicts
        // textures aggressively under the HD 6750M's 512 MB shared
        // VRAM. Numbers chosen to leave ~256 MB for macOS WindowServer
        // and any browser windows the user has open.
        if (System.getProperty("prism.maxvram") == null) {
            System.setProperty("prism.maxvram", "256m");
        }
        if (System.getProperty("prism.targetvram") == null) {
            System.setProperty("prism.targetvram", "192m");
        }
        if (System.getProperty("prism.disableRegionCaching") == null) {
            System.setProperty("prism.disableRegionCaching", "true");
        }
        Application.launch(App.class, args);
    }
}
