package io.fathereye.panel.addon;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.panel.ipc.PipeClient;
import io.fathereye.panel.ipc.TopicDispatcher;
import io.fathereye.panel.launcher.ServerLauncher;

import java.util.function.Consumer;

/**
 * Read-only(ish) handle the panel passes to each loaded {@link PanelAddon}.
 * Exposes the panel's live {@link ServerLauncher}, {@link AppConfig},
 * {@link TopicDispatcher} and (lazily) the connected {@link PipeClient},
 * plus a small set of callbacks so an addon can publish status updates
 * back to the JavaFX UI without depending on JavaFX directly.
 *
 * <p>The web addon uses this to:
 * <ul>
 *   <li>Translate REST calls into {@link ServerLauncher#start()} /
 *       {@link ServerLauncher#stop()} / {@link ServerLauncher#restart()} /
 *       {@link ServerLauncher#sendCommand(String)} and bridge RPCs via
 *       {@link PipeClient#sendRequest(String, java.util.Map)}.</li>
 *   <li>Forward bridge topic snapshots to connected browsers by
 *       registering tap handlers via {@link #onTopicSnapshot(String, Consumer)}
 *       and {@link #onTopicEvent(String, Consumer)}.</li>
 *   <li>Read and persist user settings via {@link #appConfig()}.</li>
 * </ul>
 */
public interface PanelContext {

    /** The currently-loaded application config. Mutating fields and
     *  calling {@link #saveConfig()} persists them. */
    AppConfig appConfig();

    /** Persist the current {@link #appConfig()} to disk. */
    void saveConfig();

    /** The panel's server launcher (server start/stop/restart, send
     *  console commands). Always non-null. */
    ServerLauncher launcher();

    /** The active bridge IPC client, or {@code null} when the panel has
     *  not yet handshaken with the bridge (server stopped, marker
     *  not found, etc.). The addon must null-check on every use. */
    PipeClient pipeClient();

    /** The panel's topic dispatcher. Use
     *  {@link #onTopicSnapshot(String, Consumer)} to add a non-destructive
     *  tap rather than calling
     *  {@link TopicDispatcher#onSnapshot(String, Consumer)} directly,
     *  which would replace the panel's UI handler. */
    TopicDispatcher dispatcher();

    /** Register an additional snapshot/delta listener for {@code topic}.
     *  The panel's UI handler continues to fire; this listener fires
     *  alongside it. */
    void onTopicSnapshot(String topic, Consumer<JsonNode> tap);

    /** Same as {@link #onTopicSnapshot(String, Consumer)} but for Event
     *  frames (chat, join, leave, crash, alert, shutdown). */
    void onTopicEvent(String topic, Consumer<JsonNode> tap);

    /** Push a status string into the panel's bottom status bar.
     *  Marshalled to the FX thread internally; safe from any thread. */
    void status(String message);

    /** Push a one-line synthetic message into the in-app console.
     *  Marshalled to the FX thread internally. */
    void log(String message);
}
