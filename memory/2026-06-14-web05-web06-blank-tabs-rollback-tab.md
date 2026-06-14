# Web-05 + Web-06: web panel blank tabs fixed + dedicated Backups/Rollback tabs

**Date:** 2026-06-14
**Version:** 0.3.3-mac.1 (no bump — portal-only behavior + additive UI)
**Files:** `webportal/src/main/java/io/fathereye/webportal/BridgeConnection.java`,
`webportal/src/main/java/io/fathereye/webportal/WebPortalAssets.java`,
`CHANGELOG.md`, `coordination/CLAIMED.md`, this entry + `memory/MEMORY.md`.

## Symptom (user report)

> "web panel is still not working, all tabs are blank, cant even see console...
> it has to work %100... also i dont see the backup and rollback tabs in the
> server panel, they need their own tabs with clearly easy to use ui's"
> "rollback ui has to be able to rollback specific player inventories to earlier states"
> "needs to work flawlessly first time, use thoroughly vetted researched solutions"

Every live tab (Stats/Players/Mobs/Mods/Map) was blank, the console was empty,
and there were no Backups/Rollback tabs.

## Root cause

It was **not** a code bug in the data path — Web-02 had already fixed the
`{seq,data}` snapshot unwrapping. There were two operational faults:

1. **Stale build.** The deployed `webportal/build/install` was built before the
   Backups tab existed, so the served HTML simply did not contain the new UI.
2. **A dead bridge marker shadowing a healthy one.** `BridgeConnection.ensureConnected()`
   only dialled `MarkerDiscovery.discoverFirst()` (the single newest marker).
   Bridges advertise via JSON marker files in
   `~/Library/Application Support/FatherEye/bridges/`. A marker whose PID was
   still alive but whose TCP socket was dead (a server mid-shutdown, or a
   crashed second instance) sat at the front of the newest-first list and
   wedged the portal forever. The browser saw a permanently disconnected
   portal, so every live tab stayed blank and no console arrived.

`MarkerDiscovery.discover()` already prunes markers whose **PID** is dead, but
it cannot detect a live-PID / dead-socket marker — that is exactly the case
that wedged us.

## Fix 1 — resilient connect (`BridgeConnection.java`)

- `ensureConnected()` now iterates **all** live markers (newest-first) and
  connects to the first that completes the full connect+handshake+subscribe
  via a new `tryMarker()`. A dead-socket marker can no longer block a healthy
  bridge listed in another marker — we just move on to the next one.
- `tryMarker()` defensively `stop()`s the old `PipeReader` and `close()`s the
  old `PipeClient` before publishing the new connection, so a socket is never
  leaked and a reader thread is never orphaned (adopted from the connection
  audit).
- New `pruneMarker()` deletes an unreachable marker **only when its advertising
  PID is no longer alive**. A genuinely-live-but-busy bridge (still starting,
  GC pause) is kept and retried on the next 5 s tick. Even an over-eager prune
  self-heals because a live bridge re-writes its marker.

## Fix 2 — UI (`WebPortalAssets.java`)

- **Connection banner.** A persistent banner (`#connBanner`, driven by
  `setConnBanner()` from `setBadge()`) states connecting / offline / connected,
  so a not-yet-connected portal is never a silent blank screen — the original
  "all tabs blank with no explanation" failure mode is gone.
- **Backups and Rollback split into two dedicated tabs:**
  - *Backups* tab: create a backup + per-player **live** restore. The
    "Live Players" button opens a modal listing players in that backup; one
    click injects that player's full saved state (inventory, ender chest, XP,
    health, position) into the running server with no disconnect (Brg-27 path).
  - *Rollback* tab: whole-world / whole-playerdata / both restore, with a
    server-must-be-stopped warning and a safety-snapshot note.
  - `bkLoad()` renders each backup row into both `#backupsTable` (Live Players
    only) and `#rollbackTable` (World / Players / Both). `bkRenderJob()` mirrors
    job status into both `#bkJob` and `#rbJob`. `#rbRefresh` and rollback-tab
    activation are wired.
- **Per-player inventory rollback** (the explicit user requirement) is served
  by the existing `/api/backup/players` (GET) + `/api/player/restore` (POST)
  endpoints surfaced from the Backups tab.

## Vetting (the "must work flawlessly" requirement)

- `:webportal:test` green.
- **Empirical end-to-end test, run twice** (before and after the connection
  hardening): a mock TCP bridge wrote a marker, the real portal discovered it,
  connected, handshook, subscribed; a raw RFC 6455 WebSocket client then
  received `status:connected`, `welcome`, an unwrapped `tps_topic`
  (`{tps:19.97,mspt:4.2}`), and a `console_log` event — proving the full
  bridge→portal→browser path delivers data.
- The authenticated `/app` page was fetched and confirmed to contain all 10
  tabs including the new **Rollback** tab plus `connBanner`, `rollbackTable`,
  `rbRefresh`. `/api/backups` and `/api/job` returned real data.
- **Triple audit** (3 parallel reviewers): JS/UI and API-contract agents PASS
  (all 6 endpoint contracts, scope values `world|playerdata|both`, response
  shapes, table colspans, DOM ids verified). The connection agent flagged an
  orphaned-reader risk and an over-eager prune — both were hardened (see Fix 1).

## Artifacts

`./gradlew :webportal:clean assembleDist installDist` regenerated the jar,
zip, tar, and `build/install` tree; `javap` confirmed the built classes contain
the new code (`pruneMarker` PID guard + rollback tab strings). The portal runs
directly from `webportal/build/install/webportal/bin/webportal`, so that tree
**is** the deployed artifact. The panel `Father Eye.app` bundle contains no
webportal code and was not touched.
