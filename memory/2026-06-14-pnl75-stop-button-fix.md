# Pnl-75: Panel Stop button regression (bounded pre-stop backup)

Date: 2026-06-14
Module: `panel`
Version: 0.3.3-mac.1 (no version bump; panel-only behavior fix)

## Symptom

The user reported "server stop button is no longer responding". Clicking
Stop in the panel appeared to do nothing: the status badge stayed RUNNING
and the Minecraft server kept running.

## Root cause

The Bkp-01 backup overhaul changed the panel's pre-stop backup from a
fast local uncompressed world copy into a compressed `tar | gzip` stream
of the whole world to the external "Server Backups" volume, fronted by an
RCON `save-off` / `save-all flush` / `save-on` bracket. On a large world
that legitimately runs for minutes.

The Stop handler in `App.java` ran that backup inline on its worker
thread with no UI feedback before calling `launcher.stop()`:

```java
new Thread(() -> {
    try {
        BackupService.fromConfig(appConfig).runBackup();   // minutes, silent
    } catch (Exception ex) { ... }
    launcher.stop();
}, "FatherEye-StopWithBackup").start();
```

So Stop looked dead for the entire backup duration, and a slow or
unmounted external drive could strand the stop indefinitely.

## Fix (panel `App.java` only)

1. Immediate feedback: `update("Pre-stop backup running, then
   stopping...")` runs the instant Stop is pressed.
2. Bounded wait: new `PRE_STOP_BACKUP_TIMEOUT_MS = 90_000`. New
   `runPreStopBackupBounded(timeoutMs)` submits the backup to a dedicated
   daemon `ExecutorService` (`FatherEye-PreStopBackup`) and waits on
   `Future.get(timeout)`. On `TimeoutException` it logs, surfaces "backup
   continues in background", and returns so the caller proceeds to stop.
   The worker is `shutdown()` (NOT `shutdownNow()`), so an overrunning
   backup finishes its own RCON save-on / archive cleanup rather than
   leaving a half-written archive or a world with saving disabled.
3. Guaranteed stop: the Stop handler wraps the bounded backup in
   `try/finally` so `launcher.stop()` is always reached. The helper
   catches `TimeoutException`, `ExecutionException`, `InterruptedException`,
   and `RuntimeException` (e.g. `RejectedExecutionException` from
   `submit()`, or an unchecked path error from `fromConfig()`) so it can
   never propagate and skip the stop.

## Data-safety fix (audit, MC-78635)

If the bounded wait times out, the backup's `save-off` bracket may still
be open (autosave disabled) when `/stop` is sent. A Minecraft server
stopped with autosave off can skip its final chunk flush and lose recent
changes (MC-78635). The `finally` block therefore sends `save-on` on the
live server's own stdin before `launcher.stop()`:

```java
try {
    launcher.sendCommand("save-on");
} catch (Exception ignored) {
    // server stdin unavailable (already stopped); nothing to do.
}
update("Stopping server...");
launcher.stop();
```

Best effort: a missing stdin means the server is already down, so there
is nothing to save.

## Triple-audit (rule 9)

Three parallel Opus reviewers. Adopted fixes: the MC-78635 `save-on`
before stop, and the `RuntimeException` guard around `submit()` /
`fromConfig()` so a scheduler or path failure can never skip the stop.
Pre-existing em-dash style nits in `App.java` (lines 39, 151, 295, 810,
861, 1140) are all in code untouched by this change and were left for the
user to decide on rather than churning unrelated lines.

## Build

`./gradlew :bridge:compileJava :webportal:compileJava :panel:compileJava
:webportal:test` — all UP-TO-DATE / passing, BUILD SUCCESSFUL.

## Files touched

- `panel/src/main/java/io/fathereye/panel/App.java`
- `CHANGELOG.md`, `coordination/CLAIMED.md`, `memory/MEMORY.md`, this entry.
