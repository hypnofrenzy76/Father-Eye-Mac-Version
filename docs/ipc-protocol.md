# Father Eye IPC Protocol — v1

Bridge ↔ Panel wire format over a Windows named pipe.

> Protocol version: **1**.
> Transport: Windows named pipe `\\.\pipe\fathereye-<instanceUuid>`, OR TCP `127.0.0.1:<ephemeral>` on macOS/Linux.
> Marker file: `%LOCALAPPDATA%\FatherEye\bridges\<instanceUuid>.json` on Windows, `~/FatherEye/bridges/<instanceUuid>.json` elsewhere.

## Marker schema

```json
{
  "instanceUuid": "f3bfbe50-90a3-4a52-af6d-862395e9210b",
  "transport": "named-pipe",
  "address": "\\\\.\\pipe\\fathereye-f3bfbe50-90a3-4a52-af6d-862395e9210b",
  "pipeName": "\\\\.\\pipe\\fathereye-f3bfbe50-90a3-4a52-af6d-862395e9210b",
  "protocolVersion": 1,
  "bridgeVersion": "0.1.0",
  "mcVersion": "1.16.5",
  "forgeVersion": "36.2.39",
  "serverDir": "C:/Users/lukeo/Desktop/TomCraft-Server",
  "pid": 15072,
  "startedAtEpochMs": 1745563000000
}
```

- `transport` is `"named-pipe"` (Windows) or `"tcp"` (Mac/Linux). Absent on legacy pre-v0.2 markers — assume named-pipe.
- `address` is the canonical connection string: pipe path for named-pipe, `host:port` for TCP.
- `pipeName` is the legacy alias; only populated for named-pipe transport (TCP markers omit it so v0.1 panels fail fast on the missing field rather than feeding `127.0.0.1:54321` to JNA `CreateFile`).
- `pid` lets the panel detect already-running bridges and skip a redundant auto-start. PID-recycling is mitigated by also verifying the holding process is a JVM.

## Framing

Every frame on the wire:

```
[u32 BE length][u8 codec][payload of `length` bytes]
```

- `length` includes neither itself nor the codec byte.
- `codec`:
  - `0x00` — JSON, UTF-8. Used for control plane.
  - `0x01` — CBOR. Used for bulk responses (chunk tiles, JFR file chunks).
- Length cap: **16 MiB** per frame. Larger payloads chunk at the application layer with `Ack` flow control.

## Envelope (JSON, codec=0)

```json
{ "v": 1, "id": 42, "kind": "Subscribe", "topic": "tps_topic", "payload": { ... } }
```

- `v` — protocol version. Both sides advertise in `Hello`/`Welcome`. v0.x policy: bridge **rejects** Hello with `protocolVersion < bridge.PROTOCOL_VERSION` and emits an `Error` (kind=`Error`, code=`protocol_too_old`); session stays open so panel can read the Error and disconnect.
- `id` — monotonic per direction. Used to correlate `Request`/`Response`.
- `kind` — one of: `Hello`, `Welcome`, `Subscribe`, `Unsubscribe`, `Snapshot`, `Delta`, `Request`, `Response`, `Event`, `Error`.
- `topic` — present for `Subscribe`/`Unsubscribe`/`Snapshot`/`Delta`/`Event`.
- `payload` — kind-specific.

### Snapshot/Delta payload wrapper

Every `Snapshot` and `Delta` payload is wrapped as `{ seq, data }` where `seq` is the per-topic monotonic sequence number and `data` is the topic-specific schema documented below. Panels treat any unrecognised wrapper field as forward-compat noise and ignore it.

## Handshake

1. Panel connects to the pipe.
2. Panel sends `Hello { protocolVersion: 1, panelVersion: "0.1.0", capabilities: ["jfr","chunkTile"] }`.
3. Bridge replies `Welcome { protocolVersion: 1, bridgeVersion: "0.1.0", mcVersion: "1.16.5", forgeVersion: "36.2.39", mods: [{id,version}], dimensions: ["minecraft:overworld",...], capabilities: ["jfr","chunkTile","modsImpact"], serverHeapMaxBytes: ..., instanceUuid: "..." }`.
4. Either side sends `Error { code, message }` if version negotiation fails; pipe is then closed.

## Topics

| Topic | Cadence | Coalesce | Schema |
|-------|---------|---------|--------|
| `tps_topic` | 1 Hz | yes | `{ tps20s, tps1m, tps5m, msptAvg, msptP50, msptP95, msptP99, heapUsed, heapMax, gcPauseMsLastSec, threadCpuNanosByName: {...} }` |
| `mods_impact_topic` | 1 Hz, on subscription only | yes | `{ dim → { modId → { teTickNanos, entTickNanos, teCount, entCount, cheapTicks } } }` |
| `players_topic` | 2 Hz movement coalesced | yes | `{ uuid → PlayerMarker (mapcore POJO) }` |
| `mobs_topic` | 1 Hz | yes | `{ dim → { modId → { entityCount, tileCount, hostileCount, passiveCount } } }` |
| `chunks_topic` | 2 Hz | yes | `{ dim → { loadedChunkCount, forceLoadedChunkCount } }` |
| `console_log` | edge | no (drop oldest on overflow) | `{ tsMs, level, logger, msg }` |
| `event_chat` | edge | no | `{ tsMs, sender, uuid, msg, channel }` |
| `event_join` | edge | no | `{ tsMs, name, uuid }` |
| `event_leave` | edge | no | `{ tsMs, name, uuid, reason }` |
| `event_crash` | edge, priority | no | `{ tsMs, summary, fileRef }` |
| `event_alert` | edge | no | `{ tsMs, kind, severity, msg }` |
| `event_shutdown` | edge | no | `{ tsMs, graceful: bool }` |

## RPC ops (kind = Request, payload.op = ...)

| op | args | result |
|----|------|--------|
| `cmd_run` | `{ command: string }` | `{ ok: bool, output: string }` |
| `cmd_tp` | `{ playerUuid?, x, y, z, dim }` | `{ ok }` |
| `cmd_kick` | `{ uuid, reason }` | `{ ok }` |
| `cmd_ban` | `{ uuid, reason, expiresAtMs? }` | `{ ok }` |
| `cmd_op` | `{ uuid, on: bool }` | `{ ok }` |
| `cmd_whitelist` | `{ uuid, on: bool }` | `{ ok }` |
| `cmd_killEntity` | `{ entityId }` | `{ ok }` |
| `cmd_clearMobsInArea` | `{ dim, x1, z1, x2, z2 }` | `{ ok, killed: int }` |
| `cmd_weather` | `{ kind: "clear"\|"rain"\|"thunder", durationTicks? }` | `{ ok }` |
| `cmd_time` | `{ value: long }` | `{ ok }` |
| `srv_stop` | `{ reason? }` | `{ ok }` |
| `srv_restart` | `{ reason? }` | `{ ok }` |
| `chunk_tile` | `{ dim, cx, cz }` | JSON `ChunkTile` (CBOR upgrade pending) |
| `jfr_start` | `{ profile: "default"\|"detailed", durationSec? }` | `{ ok, recordingId }` (errors if JVM is JDK 8) |
| `jfr_stop` | `{ recordingId }` | `{ ok, fileRef }` |

> Future ops not yet implemented: `cmd_chunkLoadToggle`, `mods_full_breakdown`. Will land when the underlying bridge support exists; remove these from your panel-side wishlist until then.

## Backpressure

- **Coalescing topics** (`tps`, `players`, `mobs`, `chunks`): newest delta replaces queued. Writer publishes the latest value.
- **Edge topics** (`console_log`, `event_chat`, `event_join`, `event_leave`): bounded queue. Drop oldest, emit one `Event{eventType:"backpressure", droppedSinceMs, topic}` summary per drain.
- **Priority lane**: `event_crash`, `event_alert`, `event_shutdown` never dropped.
- **Bulk responses**: panel must `Ack` before next chunk; sliding window of 4.
- If writer can't drain for >2 s → bridge disconnects the pipe.

## Reconnect

- `Subscribe` may carry `since: <seq>`. Bridge replays from there if still in its 60 s ring; otherwise sends a fresh `Snapshot`.
- Server restart: marker file recreated with new `pipeName`, same `instanceUuid`. Panel polls marker every 2 s while disconnected.
- Pipe break mid-`Request`: panel receives `Error { code: "disconnected", relatedId: <id> }`. Idempotent ops (`jfr_stop`, `chunk_tile`) may be retried; mutating ops (`cmd_*`) must NOT be retried automatically.

## Versioning

- `Hello.protocolVersion` + `Welcome.protocolVersion`. Min taken.
- Major bump = breaking. Minor implied via `capabilities[]`.
- Unknown JSON fields ignored (forward-compat).
- Removing a field = major bump.
- `/fe-version` console command echoes the bridge's protocol version + bridge version for out-of-band diagnosis.
