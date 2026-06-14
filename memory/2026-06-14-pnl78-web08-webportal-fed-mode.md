# Pnl-78/Web-08 — Web Portal reuses the panel's bridge connection ("fed mode")

**Date:** 2026-06-14
**Version:** 0.3.3-mac.1 (no bump; additive behavior fix)

## Symptom

With the server running, the in-process Web Portal served its HTTP shell at
`http://<tailscale-name>:8765`, but every live browser tab
(Stats / Players / Mobs / Mods / Map / Console) stayed blank.

## Root cause: the bridge is single-client

The bridge's IPC accept loop (`PipeAcceptLoop`) services exactly ONE session at
a time — it only loops back to `accept()` after the current session ends. The
**panel** already holds that one session. When Pnl-77b/Web-07b started the
portal in-process, the portal's own `BridgeConnection.start()` opened a SECOND
TCP socket. The OS completed the TCP handshake, but the bridge never serviced
the second connection, so the portal's protocol handshake blocked forever and
no topic data ever reached the browser. (Console is an Event topic that the
portal could not show either, since the portal never connected at all.)

## Fix: "fed mode" (Option A — no bridge change)

The portal no longer opens any IPC socket. It is started via
`WebPortalMain.startEmbeddedFed(host, port, rpcDelegate)`, which opens ONLY the
HTTP `ServerSocket`. The panel — which owns the single live bridge session —
drives the portal:

1. **Topic data fan-out.** New `TopicDispatcher.setFrameTap(FrameTap)` is called
   for EVERY inbound Snapshot/Delta/Event frame (the raw bridge payload), in
   addition to the panel's own per-topic UI handlers. `App.wireDispatcher`
   installs a tap that forwards each frame to `WebPortalLauncher.onTopic(kind,
   topic, rawPayload)`. The portal feeds it to the SAME `handleTopic` path its
   self-owned reader used, so the `{seq,data}` unwrap and `latestSnapshots`
   backfill (for late-joining browsers) are byte-identical to standalone mode.
   Tap errors are swallowed in the dispatcher so a portal hiccup can never break
   panel UI dispatch.

2. **Welcome / handshake.** `App.tryConnect` translates the panel's
   `PipeClient.WelcomeInfo` plus the marker's `serverDir` / `startedAtEpochMs`
   into the EXACT JSON shape the browser expects (mirroring the webportal's
   `BridgeConnection.buildWelcomeJson`) and feeds it via
   `WebPortalLauncher.onBridgeConnected`. This is done BEFORE
   `launcher.markRunning()` so the launcher's RUNNING state-sink (which starts
   the portal) sees a populated `webPortalWelcomeJson`.

3. **Browser RPCs.** `WebPortalLauncher.start(Supplier<PipeClient>)` builds a
   `BridgeConnection.RpcDelegate` of the form `(op, args) -> panel's CURRENT
   live PipeClient.sendRequest(...)`. The client is resolved per-RPC, so a panel
   reconnect that reassigns `pipeClient` is followed transparently; a
   null/closed client yields a clean "bridge not connected" error to the
   browser.

4. **Disconnect.** `App.onBridgeDisconnect` nulls the welcome and calls
   `WebPortalLauncher.onBridgeDisconnected`. The portal stays UP (its HTTP
   server keeps serving) so the operator can still browse; it re-banners when
   the panel reconnects and feeds a fresh welcome. On server STOPPED/CRASHED the
   existing state-sink fully stops the portal.

## Safety / correctness

- **No second IPC client is ever created.** `BridgeConnection.startFed` shares
  the existing `started` `AtomicBoolean` with the self-owned `start()`, and a
  `fedMode` flag makes `ensureConnected`/`tryMarker`/the reconnect loop
  unreachable, so the two paths can never both arm.
- **Thread-safety.** `App.pipeClient` is now `volatile` (read by the portal's
  WebSocket RPC threads via the supplier). The welcome volatile write
  happens-before `markRunning()`, which is what triggers the state-sink thread
  that starts the portal.
- **Standalone portal unchanged.** `WebPortalMain.main()` → `run()` →
  `startEmbedded` (non-fed) still opens its own socket and reconnect loop for
  anyone running the portal as a separate process.

## Audit

Triple Opus audit. The lone "CRITICAL" (welcome built after `markRunning`) was a
FALSE POSITIVE: the code already builds and publishes the welcome before
`markRunning`, exactly the fix the auditors recommended. All other findings were
CLEAN/LOW — the panel already depends on `:webportal`, every referenced API is
public, no callers were broken, and the standalone entrypoint is intact.

## Build & deploy

`:panel:compileJava` + `:webportal:compileJava` green. `:panel:jpackageMacApp`
rebuilt with the webportal jar bundled and deployed to
`/Applications/Father Eye.app`.

**Pending (interactive, needs a running server):** confirm the browser panels
populate live and `panel.log` shows fed-mode topic flow.

## Files

- `panel/src/main/java/io/fathereye/panel/App.java`
- `panel/src/main/java/io/fathereye/panel/ipc/TopicDispatcher.java`
- `panel/src/main/java/io/fathereye/panel/launcher/WebPortalLauncher.java`
- `webportal/src/main/java/io/fathereye/webportal/BridgeConnection.java`
- `webportal/src/main/java/io/fathereye/webportal/WebPortalMain.java`
- `CHANGELOG.md`, `coordination/CLAIMED.md`, `memory/MEMORY.md`
