# 2026-06-12, Pnl-73: Arcanum player tab fixed via live player feed + name cache

## What changed
- `panel/src/main/java/io/fathereye/panel/view/ArcanumPane.java`:
  - `processPlayersResponse` now parses the bridge's actual response shape: a UUID-keyed JSON object (each value has `uuid`, `online`, `name`, `rank`, `homesCount`, `kitsClaimedCount`, `lastTeleportMs`). Entries are deep-copied before the map key is injected as `uuid` so the shared response JsonNode is never mutated. The old array shape (bare or wrapped in `players`) is still accepted, and `homesCount` is read with `homes` as fallback.
  - New `onPlayersSnapshot(JsonNode)` consumes the same `players_topic` snapshot the Players tab uses (`payload.data.players[]` with uuid+name). It maintains `onlineUuids` (ConcurrentHashMap key set) and a persistent `nameCache` (uuid -> last seen name). Row online flags and cached names are merged into the table on the JavaFX thread with `playersTable.refresh()`.
  - Name cache persisted to `PlatformPaths.appDataDir()/arcanum-player-names.json` (macOS: `~/Library/Application Support/FatherEye/`). Loaded in the constructor; saved via `CompletableFuture.runAsync` only when a name changes. Offline players resolve "Unknown" names from the cache.
  - New field `playersTable` holds the Players TableView so refresh() is reachable.
- `panel/src/main/java/io/fathereye/panel/App.java:704`: `players_topic` dispatcher additionally calls `mainWindow.arcanumPane().onPlayersSnapshot(payload)`.

## Why
The Arcanum Players tab never worked (shipped broken): bridge returns a UUID map with `homesCount` (Arcanum repo, `ArcanumBridge.java:144`), panel expected an array with `homes` (`ArcanumPane.java`, old line 1001), so the table stayed empty and the rank-change ChoiceBox never appeared. Maintainer chose the panel-side fix using the existing active player list plus a joined-name cache, avoiding a mod rebuild and server restart.

## Maintainer decisions recorded
- Panel quit stopping the server is intended behavior; the anti-orphan hook in `App.java`/`ServerLauncher.java` stays.
- Fix located in the panel, not the bridge.

## Audit outcome
Triple audit (3 parallel agents). Adopted: deepCopy before mutation, explicit previous-value compare on `nameCache.put`, `TableView.refresh()` instead of `setAll(new ArrayList<>(playersData))`, async cache save. Rejected: clear()+addAll for `onlineUuids` (empty-set window), alleged NPE on `name.equals(previous)` (receiver is non-null). Accepted minors: unbounded cache growth (tiny private server population), broad catch in cache IO, redundant online OR (bridge flag plus live set covers staleness between feeds).

## Deployment
- Version kept at 0.3.3-mac.1 (panel-only; bridge handshake pin unchanged, server jars untouched).
- `./gradlew :panel:jpackageMacApp` rebuilt `dist/Father Eye.app`; `/Applications/Father Eye.app` replaced; `onPlayersSnapshot`/`loadNameCache`/`saveNameCache` verified inside the packaged `fathereye-panel-0.3.3-mac.1.jar` via javap. `Father Eye Setup.app` untouched.
- Changes are uncommitted; maintainer commits on explicit request only.
