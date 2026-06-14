# Web-09 (2026-06-14): start the Web Portal on bridge handshake, not only on the launcher RUNNING transition

## Symptom
The user reported the **web panel showed no map visuals** and the Java panel
map "wasn't loading correctly." On investigation nothing was actually serving
the browser surface: `http://<tailscale>:8765` was dead even though the panel
was connected to the bridge and its own map was populating.

## Root cause
The in-process Web Portal (Pnl-77b/Web-08, `WebPortalLauncher`) was started in
exactly one place: the panel's `ServerLauncher` RUNNING state-sink. That sink
only fires when the panel OWNS the server (STARTING -> RUNNING transition).

In the normal **attach** flow — the panel discovers a bridge marker for a
server someone else started (e.g. `~/Desktop/Server/run.sh`) and connects to it
— the launcher is never told to start, so it stays `STOPPED`.
`ServerLauncher.markRunning()` is a deliberate no-op for non-STARTING states
(`ServerLauncher.java:227-232`), so the RUNNING state-sink never fired and the
portal was never started. Result: bridge connected, panel map fine, but the
entire browser surface (map included) stayed down in attach mode.

## Fix
`panel/.../App.java` (~line 1047): start the portal on the **bridge handshake
success** path, which is the true "bridge reachable" signal for BOTH the
panel-owned and attached cases:

```java
webPortalLauncher.start(() -> pipeClient);
webPortalLauncher.onBridgeConnected(webPortalWelcomeJson);
```

`webPortalWelcomeJson` is already built just above this point (before
`markRunning()`), so the banner/welcome-dependent UI is populated. The call is
safe because:
- `WebPortalLauncher.start(...)` is idempotent (`portal != null` -> logs and
  returns), so the panel-owned RUNNING state-sink's later `start()` is a
  harmless no-op.
- The RPC delegate resolves the panel's CURRENT live `PipeClient` per call, so
  reconnects are followed transparently (unchanged from Web-08).

## Audit + verification
Triple Opus audit: all three converged "safe to ship" (idempotent,
thread-safe, browser map RPC chain intact). Built `:panel:jpackageMacApp`,
deployed to `/Applications/Father Eye.app`. Verified against the live server
(bridge on 127.0.0.1:50462): panel attached, portal logged "Web portal started
(fed mode) on 0.0.0.0:8765", `curl http://127.0.0.1:8765/` returned HTTP 200,
and `chunks_topic` flowed (loadedChunks=2025). No version bump (additive fix).
