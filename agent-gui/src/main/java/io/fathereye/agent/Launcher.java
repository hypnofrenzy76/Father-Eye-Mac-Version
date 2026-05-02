package io.fathereye.agent;

import javafx.application.Application;

/**
 * Non-Application entry point. Same pattern as panel/Launcher.java —
 * Java's launcher refuses to call main() on an Application subclass
 * via the classpath path, so the actual main() lives here.
 *
 * <p>All prism.* properties are also baked into Father Eye Agent.app
 * via jpackage's --java-options, but we re-set them here so {@code
 * gradle run} from a dev machine picks the same pipeline as the bundle.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        // Mid 2011 iMac with AMD HD 6750M (TeraScale 2, non-Metal). Force
        // OpenGL pipeline (prism-es2) with software fallback. Same flags
        // as panel/ — see panel/build.gradle for the per-flag rationale.
        if (System.getProperty("prism.order") == null) {
            System.setProperty("prism.order", "es2,sw");
        }
        if (System.getProperty("prism.lcdtext") == null) {
            System.setProperty("prism.lcdtext", "false");
        }
        if (System.getProperty("prism.allowhidpi") == null) {
            System.setProperty("prism.allowhidpi", "false");
        }
        if (System.getProperty("prism.maxvram") == null) {
            System.setProperty("prism.maxvram", "256m");
        }
        if (System.getProperty("prism.targetvram") == null) {
            System.setProperty("prism.targetvram", "192m");
        }
        if (System.getProperty("prism.disableRegionCaching") == null) {
            System.setProperty("prism.disableRegionCaching", "true");
        }
        Application.launch(AgentApp.class, args);
    }
}
