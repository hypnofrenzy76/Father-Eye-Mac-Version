# Pnl-77b / Web-07b: panel auto-starts the Web Portal with the server

Date: 2026-06-14
Version: 0.3.3-mac.1 (no version bump; additive panel behavior + portal lifecycle refactor)

## Problem

The Web Portal (browser control surface, served on port 8765) was never started.
There was no launch code in the panel, and the packaged `Father Eye.app` did not
even bundle the webportal jar (`Contents/app/` held only panel + mapcore + deps),
so `http://<tailscale-name>:8765` was unreachable. The user expected "the panel
starts the Web Portal every time the server starts."

## Why in-process and not a child `java -jar`

The jpackage runtime inside `Father Eye.app` is a jlink image that jpackage
strips of its `bin/java` launcher (only `libjli.dylib` remains; the only
executable in the runtime is `jspawnhelper`). There is therefore no java binary
to fork a child portal JVM. The fix runs the portal in-process inside the panel
JVM instead.

## Changes

- **`webportal/.../WebPortalMain.java`** — refactored the old blocking `run()`
  into:
  - `public synchronized void startEmbedded(String host, int port)` — adds the
    bridge listener, `bridge.start()`, creates + starts `HttpServerCore`, stores
    it in a new `volatile HttpServerCore httpServer`. Does NOT park the thread
    and does NOT register a JVM shutdown hook. Returns immediately (the HTTP
    accept loop and bridge reconnect run on their own daemon threads). Idempotent
    via the `httpServer != null` guard.
  - `public synchronized void stop()` — closes every live `WsSession`
    (`sendClose()`), clears the session list, stops the HTTP server, stops the
    bridge. Safe when never started.
  - `run()` (standalone `main`/`bin/webportal` path) now = `startEmbedded(...)`
    + a shutdown hook calling `stop()` + park. Behavior of the standalone
    launcher is unchanged.
- **`panel/.../launcher/WebPortalLauncher.java`** (NEW) — owns the portal for
  the panel. `start()` constructs a FRESH `WebPortalMain` and calls
  `startEmbedded("0.0.0.0", 8765)`; `stop()` calls `stop()` and drops the ref.
  Both synchronized + idempotent. `start()` swallows failures (logged), so a
  port-8765 `BindException` or any error never aborts the Minecraft server
  lifecycle.
- **`panel/.../App.java`** — new `final WebPortalLauncher webPortalLauncher`
  field; `start()` in the launcher stateSink RUNNING branch (bridge handshake
  done => a live marker exists for the portal to dial); `stop()` in a NEW
  unconditional STOPPED/CRASHED block (kept separate from the eval branch, which
  is gated on `sessionStartMs`/`evaluationGenerator`); `stop()` also in
  `App.stop()` and the panel shutdown hook (portal torn down before the
  launcher/pipe).
- **`panel/build.gradle`** — `implementation project(':webportal')`. `installDist`
  copies `fathereye-webportal-0.3.3-mac.1.jar` into `install/panel/lib`, which
  jpackage bundles via `--input`. The portal's transitive deps (jackson, slf4j,
  logback) are already panel deps at identical versions, and the three
  `scripts/fe-*.sh` resources are byte-identical between the two jars, so there
  is no duplicate-version or resource conflict.

## Key design constraint: fresh instance per start

`BridgeConnection.start()` is single-shot (`started.compareAndSet(false,true)`)
and `stop()` calls `reconnect.shutdownNow()` on a single-thread scheduled
executor that is never recreated, so a `WebPortalMain` instance is NOT
re-startable. `WebPortalLauncher` therefore constructs a new `WebPortalMain` on
every `start()`. (Also, `bridge.addListener` is additive, so reusing an instance
would double-register listeners.) All `WebPortalMain` state is per-instance (no
statics), so concurrent/successive instances do not conflict.

## Triple Opus audit (rule 9)

Three parallel Opus agents reviewed the diff:
- **Build/packaging agent:** clean. No version mismatch, no missing jlink module
  (portal only needs `java.base` for its raw `ServerSocket` + `javax.crypto`
  PBKDF2), script-resource overlap benign (byte-identical).
- **Convergent finding (2 of 3 agents): WebSocket session leak on `stop()`** —
  the original `stop()` closed the HTTP server + bridge but left open
  `WsSession` sockets and their daemon read threads lingering until the next
  failed write. FIXED: `stop()` now iterates `sessions`, calls idempotent
  `ws.sendClose()` (which closes the socket and unblocks the parked
  `readText()` so each `wsReadLoop` exits), and clears the list.
- **Dismissed:** a claimed TOCTOU between the null-check and assignment in
  `startEmbedded` — both `startEmbedded` and `stop` are `synchronized` on the
  same monitor, so no interleaving is possible.
- **Reviewed and deliberately kept:** `HttpServerCore.stop()`'s `shutdownNow()`
  with no `awaitTermination` — request-handler pool threads are daemon and
  short-lived, and hijacked WebSocket reads run on their own (now-closed)
  threads, so an await would only delay shutdown.

## Verification

- `:webportal:test` green; `:panel:jpackageMacApp` green.
- Confirmed `fathereye-webportal-0.3.3-mac.1.jar` is inside
  `dist/Father Eye.app/Contents/app/` and that `WebPortalMain.startEmbedded` +
  `stop` and `WebPortalLauncher.start/stop/isRunning` are present (via `javap`).
- In-process E2E harness against the bundled lib classpath:
  - port 8765 closed before start -> open after start (`isRunning()=true`)
  - duplicate `start()` is a no-op (still one bind)
  - after `stop()` the port is released (`isRunning()=false`)
  - `start()` again (fresh instance) re-binds -> confirms the single-shot
    design is handled
  - `GET /` returns HTTP 200
- Deployed the rebuilt bundle to `/Applications/Father Eye.app`.
- Operator password rotated via `webportal --set-password` (PBKDF2 hash in
  `~/Library/Application Support/FatherEye/webportal/auth.json`).

## Files

webportal/src/main/java/io/fathereye/webportal/WebPortalMain.java,
panel/src/main/java/io/fathereye/panel/launcher/WebPortalLauncher.java (new),
panel/src/main/java/io/fathereye/panel/App.java,
panel/build.gradle, CHANGELOG.md, memory/MEMORY.md + this entry.
