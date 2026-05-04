package io.fathereye.webaddon.rest;

import io.fathereye.panel.addon.PanelContext;
import io.fathereye.panel.launcher.BackupService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Manual backup trigger. Identical to the panel's "backup now" path:
 * runs {@link BackupService#runBackup()} on a worker thread so the
 * HTTP request returns immediately while a multi-GB world copy
 * proceeds in the background.
 */
public final class BackupController {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-WebAddon-Backup");

    private final PanelContext context;

    public BackupController(PanelContext context) {
        this.context = context;
    }

    public void runNow(Context ctx) {
        if (context.appConfig().backup.backupDir == null
                || context.appConfig().backup.backupDir.isEmpty()) {
            ctx.status(409);
            ctx.json(Map.of("error", "Backup directory is not configured. Set it in the panel's Config tab first."));
            return;
        }
        Thread t = new Thread(() -> {
            try {
                context.status("Web addon: backup running...");
                BackupService.fromConfig(context.appConfig()).runBackup();
                context.status("Web addon: backup complete.");
            } catch (Throwable th) {
                LOG.warn("manual backup failed", th);
                context.status("Web addon: backup failed: " + th.getMessage());
            }
        }, "FatherEye-WebAddon-Backup");
        t.setDaemon(true);
        t.start();
        ctx.json(Map.of("ok", true, "started", true));
    }
}
