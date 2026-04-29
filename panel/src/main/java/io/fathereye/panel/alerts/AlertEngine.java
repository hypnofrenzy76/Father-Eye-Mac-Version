package io.fathereye.panel.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.panel.config.AppConfig;
import io.fathereye.panel.view.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Threshold-based alerts for TPS / heap / MSPT.
 *
 * <p>Pnl-41 (2026-04-26): three behaviour fixes after a user report
 * of a "Father Eye â€" Low TPS" pop-up appearing right after they
 * joined a heavy modpack server.
 * <ol>
 *   <li>The pop-up was a modal {@link javafx.scene.control.Alert}
 *       dialog; on a chunky modpack a transient TPS dip during
 *       chunk-gen turned into a click-to-dismiss interruption every
 *       login. Replaced with {@link MainWindow#showAlertBanner} so
 *       alerts surface as a non-blocking in-window banner that
 *       auto-dismisses.</li>
 *   <li>The dialog title was {@code "Father Eye " + EM_DASH + " Low TPS"}.
 *       The Alert dialog's native title was rendered through a non-UTF-8
 *       code path on Windows and showed mojibake (â€"). Standing
 *       Rule 12 already forbids em-dashes; the prefix is now removed
 *       entirely (the banner is obviously from the panel, since it's
 *       inside the panel window).</li>
 *   <li>The docstring promised "30s after a join-storm" suppression
 *       but the wiring was never implemented. {@link
 *       #onPlayersSnapshot(JsonNode)} now detects a player count
 *       increase and disarms alerts for 30 s, since chunk-gen +
 *       entity-wakeup TPS dips on player join are expected and not
 *       worth alerting on.</li>
 * </ol>
 */
public final class AlertEngine {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-Alerts");
    /** Pnl-41: post-join suppression window. After a player count
     *  increase, push {@link #armedAfterMs} forward this far. 30 s
     *  is enough to cover the chunk-gen + entity-wakeup spike on a
     *  100+ mod server without burying genuine sustained problems. */
    private static final long JOIN_DISARM_MS = 30_000L;

    private final AppConfig config;
    private final AtomicLong armedAfterMs = new AtomicLong(System.currentTimeMillis() + 60_000L);

    private long tpsBelowSinceMs = 0;
    private long lastTpsAlertMs = 0;
    private long lastHeapAlertMs = 0;
    private long lastMsptAlertMs = 0;
    private static final long ALERT_COOLDOWN_MS = 60_000L;

    /** Pnl-41: previous observed online player count, for join detection. */
    private int lastOnlineCount = -1;
    /** Pnl-41: optional sink for in-window banner notifications.
     *  Wired from {@code App.startInternal} after the main window is
     *  constructed; if null, alerts are still logged to the alerts
     *  log via {@link #LOG} but do not surface in the UI. */
    private volatile MainWindow mainWindow;

    public AlertEngine(AppConfig config) {
        this.config = config;
    }

    /** Pnl-41: install the in-window banner sink. */
    public void bindMainWindow(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    public void onTpsSnapshot(JsonNode payload) {
        if (!config.alerts.enabled) return;
        if (System.currentTimeMillis() < armedAfterMs.get()) return;
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;

        double tps = data.path("tps20s").asDouble(20.0);
        double mspt = data.path("msptAvg").asDouble(0);
        long heapUsed = data.path("heapUsedBytes").asLong(0);
        long heapMax = data.path("heapMaxBytes").asLong(1);
        double heapPct = heapMax > 0 ? (heapUsed * 100.0) / heapMax : 0;

        long now = System.currentTimeMillis();

        // TPS sustain check
        if (tps < config.alerts.tpsDropThreshold) {
            if (tpsBelowSinceMs == 0) tpsBelowSinceMs = now;
            else if (now - tpsBelowSinceMs >= config.alerts.tpsDropSustainSec * 1000L
                     && now - lastTpsAlertMs > ALERT_COOLDOWN_MS) {
                fire("Low TPS",
                        String.format("TPS %.2f below %.1f for %d s", tps, config.alerts.tpsDropThreshold, (now - tpsBelowSinceMs) / 1000));
                lastTpsAlertMs = now;
            }
        } else {
            tpsBelowSinceMs = 0;
        }

        if (heapPct >= config.alerts.heapPctThreshold && now - lastHeapAlertMs > ALERT_COOLDOWN_MS) {
            fire("High heap usage", String.format("Heap %.1f%% (threshold %.1f%%)", heapPct, config.alerts.heapPctThreshold));
            lastHeapAlertMs = now;
        }
        if (mspt >= config.alerts.msptThreshold && now - lastMsptAlertMs > ALERT_COOLDOWN_MS) {
            fire("High MSPT", String.format("MSPT %.2f ms (threshold %.1f ms)", mspt, config.alerts.msptThreshold));
            lastMsptAlertMs = now;
        }
    }

    /**
     * Pnl-41: detect a player count increase and disarm alerts so the
     * inevitable chunk-gen / entity-wakeup spike right after a login
     * doesn't trip the TPS threshold.
     *
     * <p>The bridge's {@code players_topic} payload carries
     * {@code data.players} (a JSON array). We compare its length
     * against the last observed count; an increase pushes the
     * armed-after timestamp forward.
     */
    public void onPlayersSnapshot(JsonNode payload) {
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;
        JsonNode players = data.get("players");
        int count = players != null && players.isArray() ? players.size() : 0;
        if (lastOnlineCount >= 0 && count > lastOnlineCount) {
            disarmFor(JOIN_DISARM_MS);
            // Reset the TPS sustain counter too: any pre-join below-
            // threshold accumulation would otherwise still count
            // toward the next post-disarm trip.
            tpsBelowSinceMs = 0;
            LOG.info("Player join detected ({} -> {}); alerts disarmed for {} ms",
                    lastOnlineCount, count, JOIN_DISARM_MS);
        }
        lastOnlineCount = count;
    }

    public void disarmFor(long ms) {
        long until = System.currentTimeMillis() + ms;
        if (until > armedAfterMs.get()) armedAfterMs.set(until);
    }

    private void fire(String title, String body) {
        LOG.warn("ALERT {}: {}", title, body);
        if (!config.alerts.desktopNotifications) return;
        // Pnl-41: route to the in-window banner sink. The previous
        // implementation popped a modal javafx.scene.control.Alert
        // which (a) blocked the UI behind a click-to-dismiss dialog
        // and (b) rendered its title with mojibake on Windows when
        // the title contained an em-dash.
        MainWindow mw = this.mainWindow;
        if (mw != null) {
            mw.showAlertBanner(title, body);
        }
    }
}
