package io.fathereye.bridge.log;

import io.fathereye.bridge.topic.LogLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Custom Log4j2 appender that pushes every server log event into a sink. The
 * sink is the bridge {@link io.fathereye.bridge.ipc.Publisher}'s console_log
 * channel. Drops on overflow are tracked by the upstream publisher; this
 * appender itself is non-blocking.
 *
 * <p>Attached programmatically (rather than via @Plugin discovery) to keep the
 * mod self-contained — no log4j2.xml edits required for users.
 */
public final class IpcAppender extends AbstractAppender {

    private final Consumer<LogLine> sink;
    private final AtomicLong dropped = new AtomicLong(0);

    private IpcAppender(String name, Consumer<LogLine> sink) {
        super(name, (Filter) null, null, true, Property.EMPTY_ARRAY);
        this.sink = sink;
    }

    @Override
    public void append(LogEvent event) {
        try {
            LogLine line = new LogLine();
            line.tsMs = event.getTimeMillis();
            line.level = event.getLevel().toString();
            line.logger = event.getLoggerName();
            line.msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            line.thread = event.getThreadName();
            sink.accept(line);
        } catch (Throwable t) {
            // never fail the logging pipeline
            dropped.incrementAndGet();
        }
    }

    public long droppedCount() { return dropped.get(); }

    /**
     * Install the appender on the root logger. Returns it for later
     * removal.
     *
     * <p>Pnl-49 (2026-04-26): attached at {@link Level#ALL}, not
     * {@code Level.INFO} as before. The user requested verbose
     * logging and we observed that Forge's server-side root logger
     * actually emits DEBUG events (the {@code debug.log} file is
     * 2+ MiB / 30 min on a 100+ mod modpack), but our appender was
     * dropping all of them at INFO. With Level.ALL, our appender
     * forwards whatever the root logger passes through, so:
     * <ul>
     *   <li>If the user's {@code log4j2.xml} root level is INFO
     *       (Forge default for the console), they see the same INFO
     *       traffic as before, plus any mod-DEBUG that escapes via
     *       the same root pipeline.</li>
     *   <li>If they raise root level to DEBUG, our appender now
     *       forwards every DEBUG event live to the panel console.</li>
     * </ul>
     * Backpressure is handled upstream in {@link
     * io.fathereye.bridge.ipc.Publisher}'s ring buffer; the panel's
     * {@link io.fathereye.panel.view.ConsolePane} caps at 600 lines
     * with batched flush.
     */
    public static IpcAppender install(Consumer<LogLine> sink) {
        IpcAppender app = new IpcAppender("FatherEyeIpcAppender", sink);
        app.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.addAppender(app);
        config.getRootLogger().addAppender(app, Level.ALL, null);
        ctx.updateLoggers();
        return app;
    }

    /** Remove and stop. Safe to call multiple times. */
    public void uninstall() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            config.getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
        } finally {
            stop();
        }
    }
}
