package io.fathereye.panel.addon;

/**
 * Service-loader interface for optional panel addons. The panel discovers
 * implementations via {@link java.util.ServiceLoader} at boot, after the
 * main launcher / IPC / config wiring is complete, and invokes
 * {@link #start(PanelContext)} on each. Addons are expected to spin up
 * any background threads they need and return promptly.
 *
 * <p>The web addon ({@code webaddon} module) is the first consumer of
 * this interface. Keeping the SPI in the panel module avoids the panel
 * having a compile-time dependency on optional addons; the addon module
 * depends on panel (for {@link PanelContext}) but panel only loads
 * addons reflectively, so disabling an addon is a matter of removing
 * its jar from the install lib directory.
 */
public interface PanelAddon {

    /** Short stable identifier for logs / status messages, e.g. {@code "webaddon"}. */
    String id();

    /**
     * Start the addon. Called once on the panel's IPC executor after the
     * launcher, dispatcher, AppConfig and PipeClient handshake path are
     * wired (PipeClient itself may not yet be connected; the addon
     * should defer any bridge calls until {@link PanelContext#pipeClient()}
     * returns non-null). Implementations must not block; spawn daemon
     * threads if long-running work is required.
     */
    void start(PanelContext context) throws Exception;

    /**
     * Stop the addon. Called from the panel's {@link javafx.application.Application#stop()}
     * hook. Should release sockets, close threads and return within a
     * couple of seconds so panel shutdown stays prompt.
     */
    void stop();
}
