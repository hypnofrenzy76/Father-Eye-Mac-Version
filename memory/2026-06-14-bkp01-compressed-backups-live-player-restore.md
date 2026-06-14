# Bkp-01: Compressed external backups + live player-state restore

Date: 2026-06-14
Modules: `webportal`, `bridge`, `panel` (scripts), repo root
Version: 0.3.3-mac.1 (no version bump; additive feature + ops fix)

## Problem

1. The old `BackupService` wrote full, **uncompressed** copies of the
   world + config into `/Users/luke/Desktop/Server/backups` on the
   internal disk. That folder had grown to **18 GB** and was the
   immediate disk-space pressure.
2. There was no way to roll back the world and player data
   independently, and no way to restore a single player's inventory /
   full state while the server kept running.

## What changed

### Compressed, structured backups (external drive)

- Backups now target the external volume `"/Volumes/Server Backups/backups"`.
- `scripts/fe-backup.sh` writes a per-run directory
  `<dest>/fe-<timestamp>/` containing:
  - `world.tar.gz` — the world **minus** the player-owned subtrees
    (playerdata, stats, advancements, playerdata/*_old, etc.).
  - `playerdata.tar.gz` — **only** the player-owned subtrees.
  - an optional server-config archive.
  - a JSON `manifest` recording sizes/presence of each stream.
- `scripts/fe-rollback.sh` restores either stream atomically and
  independently.
- Space-based retention prunes the oldest `fe-*` (and legacy
  `thaumaturgy-*.tar.gz`) archives to hold the drive above its free-GB
  floor (`PortalConfig.minFreeGb`, default 60).
- The web portal `BackupManager` + `/api/backup/*` routes drive a
  Backups tab (list / run / independent world+playerdata rollback).

### Live player-state restore (no disconnect)

Goal: restore one player's full saved state (inventory, ender chest, XP,
health, food, position, dimension, abilities) from any structured backup
while the server is running, for online OR offline players.

Design = NBT injection. Data flow:

1. **Web portal — `PlayerRestoreService`**
   - `listPlayers(backupId)` lists `<uuid>.dat` members inside the
     backup's `playerdata.tar.gz` (`tar -tzf`), resolving names from the
     live server's `usercache.json` best-effort.
   - `extractPlayerDat(backupId, uuid)` streams exactly one member out
     (`tar -xzO -f <archive> <member>`) and returns the raw gzip-NBT
     bytes.
   - Inputs are tightly validated: backup id must match
     `fe-YYYYMMDD-HHMMSS`, player id must be a canonical dashed UUID, so
     neither can smuggle a path component or `tar` option into the arg
     list (no shell; args passed literally).
2. **Web portal — `WebPortalMain` routes** `/api/backup/players`
   (GET, list) and `/api/player/restore` (POST). The restore route
   validates id+uuid, requires the bridge connected (409 otherwise),
   extracts the `.dat`, base64-encodes it, and forwards to the bridge op
   `player_restoreState` with a 30s timeout.
3. **Bridge — `PlayerRestoreHandler`** (registered in `RpcHandlers`,
   runs on the **tick thread** so touching live entities is safe):
   - decodes base64 -> `CompressedStreamTools.readCompressed` -> NBT.
   - takes a **pre-restore safety snapshot** (timestamped `.dat` under
     `playerdata/fe-pre-restore/`) of the player's current state so a bad
     restore can be undone — live entity serialised for online players,
     on-disk file copied for offline.
   - **online:** `ServerPlayerEntity.load(nbt)` (same entry point vanilla
     login uses) then resends inventory (`SWindowItemsPacket`), health
     (`SUpdateHealthPacket`), XP (`SSetExperiencePacket`), abilities
     (`SPlayerAbilitiesPacket`) and teleports the client so the screen
     updates immediately.
   - **offline:** writes the NBT atomically (`*.fe-tmp` + ATOMIC_MOVE)
     over `playerdata/<uuid>.dat` for next login.
4. **UI** (`WebPortalAssets`): a "Live Players" action on each backup row
   opens a modal listing players in that backup; clicking one restores.

## Triple-audit (rule 9) — fixes applied

Three parallel Opus reviewers (Minecraft-correctness, security, JS/UI):

1. **Cross-dimension desync (critical).** `Entity.load()` sets the
   dimension reference but does NOT move the entity between the
   source/target `ServerWorld` registries; the follow-up `teleportTo`
   would then try to remove it from a world it was never registered in
   (ghost / desync). Fix: when the backup's target dimension differs from
   the player's current one, strip `"Dimension"` from a **copy** of the
   NBT before `load()` (keeping the entity in its correctly-registered
   world), then do an explicit, fully-registered cross-dimension
   `teleportTo(targetWorld, ...)`.
2. **`tar` pipe deadlock (critical).** Listing merges stderr into stdout
   (`redirectErrorStream(true)`) — harmless for a text listing and
   un-deadlockable. Single-member extraction keeps stderr **separate**
   (so tar diagnostics can't corrupt the binary `.dat` on stdout) and
   drains stderr on a dedicated daemon thread so a chatty `tar` can never
   fill the stderr pipe and deadlock the stdout reader.
3. **Memory guard.** `MAX_DAT_BYTES = 32 MB` aborts an oversized extract
   (`destroyForcibly`) instead of buffering unbounded bytes.
4. A JS-escaping flag was verified a **false positive**: `\\'s` in the
   Java text block emits valid JS `\'s`; left as-is.

## Disk cleanup

- Verified the newest external full archive
  (`thaumaturgy-20260613-224348.tar.gz`, 16.6 GB) is a valid gzip/tar
  containing the world before deleting anything.
- Removed the stale 18 GB internal folder
  `/Users/luke/Desktop/Server/backups/world-20260613-222138-849` (+ its
  `.DS_Store`). Data-volume free space went **147 GiB -> 166 GiB**
  (~19 GB reclaimed). The internal `backups/` dir is now empty; future
  backups land on the external drive in the new `fe-*` structured layout.
- Note: existing external archives use the legacy `thaumaturgy-*.tar.gz`
  single-archive naming (from the old service). The new structured
  `fe-*/{world,playerdata}.tar.gz` layout — which the live-restore
  feature reads — is produced on the next backup run; legacy archives
  remain as a safety net and are pruned by retention.

## Build / verification

- `./gradlew :bridge:compileJava :webportal:compileJava` — clean.
- `./gradlew build -x test` — full build green; embedded bridge bundle
  re-emitted into the setup module.
- Live online+offline restore test on a running server: PENDING (next
  run will also produce the first `fe-*` structured backup the restore
  UI consumes).

## Files touched

- `bridge/.../rpc/PlayerRestoreHandler.java` (new; NBT injection +
  cross-dimension fix + safety snapshot)
- `bridge/.../rpc/RpcHandlers.java` (register `player_restoreState`)
- `webportal/.../PlayerRestoreService.java` (new; tar extract, validation,
  deadlock + memory fixes)
- `webportal/.../WebPortalMain.java` (`/api/backup/players`,
  `/api/player/restore` routes)
- `webportal/.../WebPortalAssets.java` (Live Players modal)
- `webportal/.../BackupManager.java`, `PortalConfig.java`,
  `scripts/fe-backup.sh`, `scripts/fe-rollback.sh` (compressed structured
  backups + rollback)
- `CHANGELOG.md`, `memory/MEMORY.md`, this entry.
