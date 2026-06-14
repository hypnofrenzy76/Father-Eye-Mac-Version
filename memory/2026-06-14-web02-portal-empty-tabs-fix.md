# Web-02: Web portal dashboard tabs were empty (seq/data unwrap)

Date: 2026-06-14
Module: `webportal`
Version: 0.3.3-mac.1 (no version bump; portal-only behavior fix)

## Symptom

Connecting to the browser web portal while the Minecraft server was
running showed every dashboard tab empty (Stats, Players, Mobs, Mods,
Map). The Console tab worked normally.

## Root cause

The bridge wraps every `Snapshot` and `Delta` topic payload on the wire
as `{ "seq": N, "data": { ...topic fields... } }` (documented in
`docs/ipc-protocol.md`, "Snapshot/Delta payload wrapper"). The JavaFX
panel, which works, always unwraps `payload.get("data")` before reading
topic fields (for example `StatsPane.onTpsSnapshot`, `PlayersPane`,
`MobsPane`, `ModsPane`, `MapPane.onChunksSnapshot`).

The web portal did not. `BridgeConnection.handleTopic` cached the raw
wrapper into `latestSnapshots` and fanned the raw wrapper out to the
WebSocket listeners. The browser JavaScript (in `WebPortalAssets.java`)
reads topic fields directly off the payload object (`p.tps20s`,
`p.players`, `byDim`, and so on), so it found those fields nested one
level too deep under `data` and rendered nothing. The Console tab uses
the `console_log` Event topic, and Events are not wrapped, so Console was
the only populated tab. This was the diagnostic tell.

Confirmed live: the bridge marker directory
`~/Library/Application Support/FatherEye/bridges/` was empty only because
the server was stopped at the time (the bridge writes its TCP marker
while running and deletes it on shutdown). The server log confirmed the
bridge binds `127.0.0.1:<port>` and the portal connects fine, so the WS
layer was healthy; the data shape was the problem.

## Fix

`webportal/.../BridgeConnection.java`, method `handleTopic`: for kind
`Snapshot` or `Delta` with a present `data` field, cache and forward
`payload.get("data")` instead of the raw `{seq,data}` wrapper. `Event`
payloads (console_log) pass through unchanged. The unwrap is done once,
centrally, so both delivery paths are consistent:

- live push: `handleTopic` -> listeners -> WebSocket broadcast
- initial state on browser connect: `bridge.allLatest()` reads the
  already-unwrapped `latestSnapshots`

Field names were verified to match after unwrap (for example tps_topic
exposes `tps20s`, `heapUsedBytes`, `heapMaxBytes`, `processCpuLoad`,
`liveThreadCount`; players_topic exposes `players[]`; mobs/chunks/mods
expose `byDim`). The browser JS reads no `seq` or `data` field anywhere,
so unwrapping does not break it.

## Test

New `webportal/src/test/java/.../BridgeConnectionUnwrapTest.java`:
end-to-end regression. It binds a real localhost `ServerSocket`, writes a
TCP marker into the bridges directory, and runs a mock bridge that speaks
the exact IPC framing (`Hello`/`Welcome` handshake, then wrapped
`{seq,data}` Snapshots for tps_topic and players_topic, plus a raw
console_log Event). It drives the real `BridgeConnection.start()` path
(marker discovery, TCP connect, handshake, subscribe, reader) and
asserts:

- tps_topic and players_topic arrive with NO `seq`/`data` wrapper and
  with topic fields at top level, on both the listener path and the
  cached `conn.latest(topic)` path;
- console_log arrives raw (unchanged);
- the fix would fail the test if reverted (the `assertFalse(has("data"))`
  and `assertTrue(has("tps20s"))` assertions depend on the unwrap).

The test waits on a dedicated connected-latch before topic latches to
avoid a connect-vs-topic ordering race in the assertions. Stable across
repeated `--rerun-tasks` runs. Marker cleanup is hardened with a JVM
shutdown hook plus `deleteOnExit` since the bridges directory is shared
with a real bridge.

## Triple-audit (rule 9)

Three parallel Opus reviewers: consensus PASS on correctness and
completeness, no regressions, no remaining field mismatches. Two latent
items were flagged and intentionally NOT changed in this fix (would alter
connection core logic, rule 7):

1. In `ensureConnected`, the reader starts and Subscribes are sent before
   `onConnected` is dispatched, so a topic frame can reach a listener
   before the welcome callback. Benign in production: the browser session
   reads `welcome()` and `allLatest()` on attach rather than relying
   solely on the `onConnected` ordering.
2. The Map tab still depends on `chunk_tile` RPC responses, and the
   Arcanum tab on its capability, so those can appear blank briefly for
   reasons unrelated to this fix.

## Files touched

- `webportal/src/main/java/io/fathereye/webportal/BridgeConnection.java`
  (handleTopic unwrap)
- `webportal/src/test/java/io/fathereye/webportal/BridgeConnectionUnwrapTest.java`
  (new regression test)
- `CHANGELOG.md`, `memory/MEMORY.md`, this entry.
