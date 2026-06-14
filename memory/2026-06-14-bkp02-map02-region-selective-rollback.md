# Bkp-02 + Map-02: region-selective rollback (rubber-band) + disk-backed pre-gen chunks

**Date:** 2026-06-14
**Version:** 0.3.3-mac.1 (no bump — additive feature, parity on both surfaces)
**Files:** `scripts/fe-region-rollback.sh` (new) + the same copy bundled under
`panel/src/main/resources/scripts/` and `webportal/src/main/resources/scripts/`;
`panel/.../launcher/BackupOps.java` (new), `panel/.../view/BackupsPane.java` (new),
`panel/.../view/RollbackPane.java` (new), `panel/.../view/MapPane.java`,
`panel/.../view/MainWindow.java`, `panel/.../App.java`,
`panel/.../launcher/PanelPlayerRestore.java` (new);
`webportal/.../BackupManager.java`, `webportal/.../WebPortalMain.java`,
`webportal/.../WebPortalAssets.java`;
`bridge/.../profiler/DiskChunkRenderer.java` (new),
`bridge/.../rpc/RegionIndexHandler.java` (new), `bridge/.../rpc/ChunkTileHandler.java`,
`bridge/.../rpc/RpcHandlers.java`; `CHANGELOG.md`, `coordination/CLAIMED.md`,
this entry + `memory/MEMORY.md`.

## What this adds

Region-selective rollback: the operator drags a rubber-band rectangle over the
map, and ONLY the Anvil region files (`.mca`, one per 32x32-chunk / 512x512-block
region) covered by that rectangle are restored from a chosen structured backup
for the current dimension. The rest of the live world is left untouched. This is
the surgical complement to the existing whole-world `fe-rollback.sh`.

It is implemented identically on BOTH surfaces (standing 100%-parity rule):
the JavaFX panel `MapPane` and the browser `WebPortalAssets` map view.

## The script (`fe-region-rollback.sh`)

Standalone bash, mirrors `fe-rollback.sh`'s safety model:

1. **Refuses to run while the server is up** — probes RCON on 127.0.0.1:25575
   (defence in depth; the panel / portal also gate on server-stopped).
2. **Pre-rollback safety snapshot** — copies exactly the live `.mca` files it is
   about to overwrite into `<DEST>/pre-region-rollback-<ts>/`, and records names
   it would CREATE in `created.txt`, so the op is reversible.
3. **Atomic per-file swap** — extracts only the chosen members from
   `world.tar.gz` into a temp dir on the same filesystem, then
   move-old-aside → move-new-in → delete-old per region, so an interrupted run
   never leaves a half-written region. A failed swap-in rolls the moved-aside
   file back.

Dimension → region folder mapping matches `DiskChunkRenderer.regionFolder`:
overworld `region`, nether `DIM-1/region`, end `DIM1/region`, modded
`dimensions/<ns>/<path>/region`. Region tokens are validated as signed integers
(`rx,rz`) so a malformed token can never widen the tar member-selection glob.
Emits `FE_PROGRESS=` markers across stages (0/10/25/60..99/100).

## Java job wiring (panel + portal)

`BackupOps.startRegionRollback(id, dim, regions)` (panel) and the twin in
`BackupManager` (portal) validate the `fe-YYYYMMDD-HHMMSS` id, non-blank dim, and
each region token against `^-?\d+,-?\d+$` before claiming the single job slot and
shelling the script. Same single-slot `AtomicReference<Job>` model as the other
backup/rollback ops, so a region rollback and a whole-world rollback can never
run at once.

## Rubber-band UI (both surfaces)

`MapPane` draws a selection overlay; on release `selectionToRegionBox()` converts
screen → world → region coordinates (`floor(blockX/512)`), enumerates the covered
`rx,rz` regions, and (on a **background** thread, never the FX thread — see audit
fix) fetches the structured backup ids from `BackupOps.list()`, then a confirm
dialog on the FX thread starts the rollback through the server-stopped gate wired
in `App`. The portal JS replicates the rubber-band, POSTs to
`/api/region-rollback`, gated on auth + server-not-running.

## Audit findings and fixes (triple Opus audit before push)

- **MAJOR (fixed): FX-thread disk I/O.** `MapPane.finishRegionSelection()` called
  `BackupOps.list()` (disk enumeration of the external volume) on the JavaFX
  application thread, which could freeze the UI on a slow/unmounted drive. Split
  into `finishRegionSelection()` (spawns a background thread to fetch ids) +
  `showRegionRollbackDialog()` (continues on the FX thread via
  `Platform.runLater`).
- **MAJOR (fixed): portal had no determinate progress.**
  `BackupManager.runAsync()` did not parse `FE_PROGRESS` markers and its `Job`
  had no `percent` field, so region rollback showed no progress in the browser
  while the panel did — a parity break. Brought the portal to parity: added the
  `FE_PROGRESS` pattern + monotonic clamped `pct` parse, a `percent` field on the
  portal `Job` (emitted in `toJson`), and a determinate `.bk-bar` in
  `bkRenderJob()` that fills from `j.percent` while running / on success.
- **MINOR:** comment-clarity note, applied.

## Disk-backed pre-gen chunks (Map-02, companion)

`DiskChunkRenderer` + `RegionIndexHandler` (registered in `RpcHandlers`) read
`.mca` region files straight off disk so the map can show pre-generated terrain
the live server has not currently loaded; `ChunkTileHandler` consults it on a
live miss. This is what makes the region selection meaningful (you can select and
roll back terrain you can actually see on the map).

## Build / vetting

`./gradlew :webportal:build :panel:build` green; `:webportal:test :panel:test`
green. The script's region-token validation and dim→folder mapping were checked
to match the Java token guard and `DiskChunkRenderer.regionFolder` exactly.
