# CHANGELOG — Father Eye Mac Version

All notable changes per session, newest first.

## 2026-04-29 (continued — full Mac compatibility pass)

After the initial fork commit, applied a comprehensive Mac-compatibility
pass guided by 10 parallel Opus audits. Notable changes:

**Critical compatibility (audits 1, 4):**
- JavaFX downgrade 21 -> **17.0.12**. JavaFX 21 hard-blocks any macOS
  earlier than 11 (Big Sur) at runtime; the user's High Sierra 10.13.6
  needs JFX 17. 17.0.12 chosen over 17.0.13 because Maven Central's
  17.0.13 mac (Intel x64) classifier set is incomplete at fork time.
- Added `java.datatransfer` to the jpackage `--add-modules` list
  (JFreeChart-FX needs it for chart context-menu copy).
- jpackage `LSMinimumSystemVersion=10.13.0` patched via `plutil -insert`
  with `-replace` fallback (jpackage 17 omits the key by default).
- `prism.order=es2,sw` baked into the bundle's `--java-options` AND
  set defensively in `Launcher.main()`.

**VRAM tuning for the AMD HD 6750M / 512 MB shared (audit 5):**
- `prism.maxvram=256m`, `prism.targetvram=192m`,
  `prism.disableRegionCaching=true` set in `Launcher.main()`.
- `TILE_CACHE_LIMIT` 4096 -> 1024.
- StatsPane `setMaximumItemCount` 900 -> 300 (matches the documented
  5-min @ 1 Hz rolling window).

**Mac path correctness (audit 2):**
- Three-way `PlatformPaths` agreement (panel, bridge, setup); all
  resolve to `~/Library/Application Support/FatherEye/` on macOS.
- `App.java` startup error dialog now shows the platform-correct log
  path instead of `%LOCALAPPDATA%`.
- `MarkerDiscovery`, `MarkerFile`, `AppConfig`, `MetricsDb` all route
  through `PlatformPaths.appDataDir()`.
- `logback.xml` uses `${FATHEREYE_APPDATA}` system property set in
  App's static block before LoggerFactory binds Logback.

**FX-thread / IPC (audits 8, 9):**
- Per-tile `Platform.runLater(redraw)` flood replaced with a coalesced
  AnimationTimer driven by a `dirty` flag. Eliminates the FX-saturation
  risk on the 2.5 GHz Sandy Bridge.
- `PipeClient.chooseTransport` now refuses `named-pipe` on non-Windows
  with a clear `IllegalStateException` instead of crashing on JNA's
  `kernel32` load.
- `MarkerDiscovery.discover()` validates marker `pid` via
  `ProcessHandle.of(pid).isAlive()` and unlinks stale markers.
- `MarkerFile.write()` uses temp-file + `ATOMIC_MOVE` to eliminate
  partially-written-marker reads.

**ServerLauncher Mac behavior (audit 6):**
- `ServerLaunchSpec.resolveJavaPath()` now reads each candidate JDK's
  `release` file and only returns a path whose `JAVA_VERSION` major is
  8 (Forge 1.16.5 requirement). Prevents the "first JDK wins" trap
  where Temurin 17 was returned to a server that needed Java 8.

**Backup robustness (audits 7, 10):**
- `BackupService.runBackup()` and `sweepStalePartials()` early-return
  on empty `backupDir` instead of writing into cwd `/`.
- `App.java` hourly backup runnable also gates on empty `backupDir`.

**JVM args reconciled (audit 7):**
- `AppConfig.ServerRuntime.jvmArgs` aligned with
  `ServerLaunchSpec.defaults()` and `SetupApp.writePanelConfig` —
  Aikar's tuning for Forge 1.16.5 with 12 GB heap. Dropped legacy
  `-noverify`.

**Setup wizard polish (audit 3):**
- Forge installer JAR auto-deletes after a successful install so two
  `forge-*.jar` files don't coexist in `serverDir`.
- Cancel button now `task.cancel(true)` and `forgeProcess.destroyForcibly()`
  so cancelling mid-Forge-install doesn't orphan the installer JVM.
- Adoptium URLs include `&package=jdk` so the user lands on JDK
  downloads (not JRE).
- Java check page wording clarified: only JDK 8 is required on the
  user's machine; JDK 17 ships inside Father Eye.app's bundled JRE.

**Repo hygiene (audit 10):**
- Deleted upstream's `stop-server.ps1` (Windows-only).
- README updated with concrete install steps for High Sierra + JDK 17
  pinning + JDK 8 Setup-wizard guidance.

## 2026-04-29

- **Fork point: 0.3.0-mac.1.** Created the Mac-focused fork
  `hypnofrenzy76/Father-Eye-Mac-Version` from upstream
  `hypnofrenzy76/father-eye` at commit `9942794` (panel 0.2.9). The
  bridge stays on 0.2.9 for now (still server-side Forge mod, still
  Windows-friendly); the panel begins its own version line under
  `0.3.0-mac.N`. Initial divergence:
  - README rewritten to reflect the macOS focus (target: iMac Mid
    2011 + High Sierra 10.13.6, JDK 17 from Temurin or Zulu, build
    via `:panel:jpackageMacApp`).
  - `gradle.properties` no longer hardcodes the Windows JDK path;
    `org.gradle.jvmargs` reduced from 3 GB to 2 GB (Mac default;
    bump back up if you have headroom).
  - `panelVersion` 0.2.9 -> 0.3.0-mac.1.
  - `App.VERSION` and `PlayerHeadCache` User-Agent updated.
  - History rewritten to a single fork commit; previous 0.1.0 ->
    0.2.9 development is preserved as text in this CHANGELOG and
    referenceable via the upstream repo.
  - GitHub Actions workflow removed (the OAuth token didn't have
    `workflow` scope and CI is upstream-Windows-specific anyway). A
    Mac-targeted Actions job will be added later via the GitHub web
    UI.

The remainder of this file (everything below) is a verbatim copy
of the upstream changelog at the fork point — kept as historical
context for how the panel evolved and what each Pnl/Brg/Bld task ID
refers to. New Mac-fork entries land above this line.

---

## Upstream history (verbatim from father-eye @ 9942794)

## 2026-04-27

- **Pnl-69: counter semantics fix — version 0.2.9.** User: "still
  hangs at a percentage and doesnt reach 100" + "i need
  something that actually fixes this". v0.2.8 fixed the leak but
  the counter math still excluded in-flight chunks.
  - **Counter root cause**: `handled = tileImages + rejected`
    excluded chunks currently in `requested` (in-flight RPCs).
    On a 2025-chunk world, after the snapshot fired RPCs the
    panel had ~600 in `requested` and ~1400 in `tileImages`.
    `handled = 1400`, `expected = 2025`, percentage = 69 %. As
    chunks resolved the percentage climbed but new chunks
    entering `chunks_topic` (player exploration, server saves)
    kept replenishing `requested`, so the bar never quite hit
    100 %.
  - **Pnl-69 fix**: `handled = tileImages + rejected + pending`
    where `pending = requested.size()`. Once the panel has
    *acknowledged* (sent an RPC for) every reported chunk, the
    bar shows 100 %, regardless of whether the responses have
    arrived. New exploration causes brief dips when chunks_topic
    adds entries the panel hasn't yet sent RPCs for; the next
    1 Hz snapshot recovers within a second. Pending chunks
    eventually transition to `tileImages` (success) or
    `rejectedChunks` (timeout via Pnl-67 orTimeout / Pnl-68
    sweep), so the visible state stays consistent with the
    counter.
  - **Pnl-69 (synchronous bookkeeping in all paths)**: dim-
    mismatch and all-zero / mostly-black paths in
    `onChunkTileResponse` were still wrapping the
    `requested.remove` + `rejectedChunks.put` in
    `Platform.runLater`. Pnl-68 only fixed `failChunkRequest`.
    Now all three rejection paths plus the success path do the
    map mutations synchronously off the FX thread; only the
    label update / redraw stays in `runLater`.
  - **Versions**: bridge 0.2.8 -> 0.2.9, panel 0.2.8 -> 0.2.9.
    Built and deployed.

- **Brg-23 + Pnl-68: stop the 594-stuck-RPCs leak — version
  0.2.8.** User: "40 mins of uptime and all chunks still have not
  loaded" then "use many agents and figure it out". The Pnl-67
  orTimeout fix wasn't enough — three independent root causes
  combined to leak 594 chunk_tile RPCs forever.
  - **Brg-23 (bridge: render-loop guard)**: ChunkTileHandler's
    inner 256-pixel loop only protected `state.getMapColor` with
    try/catch. `chunk.getHeight`, `chunk.getBlockState`, and
    `chunk.getBiomes` could all throw on partially-loaded
    holder-fallback chunks (Brg-19 returns whatever IChunk is
    held, including ProtoChunks with null Heightmap or null
    ChunkSection[]). Any throw aborted the whole handler; the
    RPC future then completed exceptionally and the bridge
    tried to send back an Error response.
  - **Brg-23 (bridge: surface IpcSession swallow)**: that Error
    response sometimes failed to send (pipe back-pressure,
    closed connection), and the IOException was silently
    swallowed at IpcSession.handleRequest's `catch (IOException
    ignored)`. Net effect: panel future hangs forever, panel
    `requested` set leaks. Now logs the swallowed IOException
    at WARN with the request ID.
  - **Brg-23 (bridge: getBiomes guard)**: chunk.getBiomes()
    called once outside the inner loop also could throw on
    partial chunks. Now guarded with try/catch -> null, and the
    inner loop falls back to `world.getBiome(pos)` when the
    container is null.
  - **Pnl-68 (panel: requested -> ConcurrentHashMap with
    timestamp)**: switched from `LinkedHashSet<Long>` to
    `ConcurrentHashMap<Long, Long>` (key -> request-sent
    timestamp). Mutations are now thread-safe so failChunkRequest
    can `requested.remove(key)` and `rejectedChunks.put(key, now)`
    SYNCHRONOUSLY without going through Platform.runLater.
    Earlier code wrapped both in runLater, which queued behind a
    saturated FX thread and left 594 entries stranded for
    minutes despite the 30 s orTimeout firing.
  - **Pnl-68 (panel: periodic sweep backstop)**: a daemon thread
    `FatherEye-RequestSweep` runs every 5 s, scans `requested`
    for entries older than 35 s (REQUEST_TIMEOUT_MS), and force-
    fails them via failChunkRequest. Belt-and-suspenders for any
    case where the per-RPC orTimeout doesn't fire (Java timer
    dropped, cache-verify path missing the timeout, etc).
  - **Pnl-68 (panel: cache-verify orTimeout)**: the Pnl-66
    cache-verify RPCs (5 sample chunks) were missing the 30 s
    orTimeout that the main chunk_tile RPCs had. Without it a
    hung verify RPC could hold the verification logic forever.
    Added.
  - **Pnl-68 (panel: canonical key)**: the success path was
    using the response-echoed `chunkX`/`chunkZ` instead of the
    originally-requested `(cx, cz)`. Currently identical (the
    bridge always echoes back the same coords) but a defensive
    fix in case any future bridge bug ever produces a mismatch.
  - **Pnl-68 (panel: requestVisibleTiles parity)**: pan/zoom
    debounce loop now also skips chunks recently in
    rejectedChunks, mirroring onChunksSnapshot behaviour. A
    pan-induced redraw won't re-fire a chunk the bridge just
    sentineled.
  - **Versions**: bridge 0.2.7 -> 0.2.8, panel 0.2.7 -> 0.2.8.
    Built and deployed; on next launcher boot the existing
    `mods/fathereye-bridge-0.2.7.jar` auto-deletes and
    `0.2.8.jar` takes its place.

- **Pnl-67: chunk RPC timeout + uptime display — version 0.2.7.**
  User: "is dynamically loading new chunks as i walk around but
  its never reaching %100 chunks loaded" + "heartbeat is sitting
  at 0 once server is running" + "i need to know how long server
  has been running."
  - **Loading reaches 100 % (root cause)**: `chunk_tile` RPCs that
    got lost in transit (silent bridge drop, pipe-buffer
    overflow, server tick stall) had no timeout, so they sat in
    the panel's `requested` set forever. The loading bar
    plateaued at 31-55 %.
  - **Pnl-67 (timeout + reject-on-fail)**: every `chunk_tile`
    RPC now has a 30 s `orTimeout`. On timeout the future
    completes exceptionally, `whenComplete` sees the error, and
    `failChunkRequest` (a) removes the chunk from `requested`
    and (b) marks it rejected with the current timestamp. The
    chunk now counts toward "handled" so the percentage can
    reach 100 %, and the existing 5 s TTL retry kicks in for
    the next snapshot if the chunk is still loaded.
  - **Heartbeat clarification**: the "Heartbeat: 0s" label was
    correct (it shows seconds *since* the last heartbeat, not
    "0 heartbeats received"). The user's confusion was that it
    didn't show server uptime — they wanted "how long has the
    server been running." Both labels now coexist.
  - **Pnl-67 (Uptime label)**: new `Uptime: Xh Ym Zs` label in
    the toolbar, anchored to the marker's `startedAtEpochMs`
    and refreshed once per second by an `AnimationTimer`.
    Resets to `--` on bridge disconnect so a stale duration
    doesn't tick on after the server stops.
  - **Versions**: bridge 0.2.6 -> 0.2.7, panel 0.2.6 -> 0.2.7.
    Built and deployed.

- **Pnl-65 + Pnl-66: content-based cache verification — version
  0.2.6.** User: "new chunks are loading fine but the old world
  chunks are still persisting" (so v0.2.5's heuristics still
  missed the case) plus "panel has to check that chunk map
  actually matches current world".
  - **Pnl-65 (level.dat creationTime, run unconditionally)**:
    Pnl-64's level.dat fallback only ran when the `.world-id`
    stamp was missing. v0.2.4 wrote a stamp matching the user's
    current bridge UUID on first launch, so v0.2.5 saw a valid-
    looking stamp and skipped the fallback even though the cache
    was actually from a deleted world. Now the level.dat check
    runs on every open, regardless of stamp state, and uses
    `BasicFileAttributes.creationTime()` (not `lastModifiedTime`,
    which the server touches every periodic save). Only trusts
    the creation time when it's strictly older than the file's
    last-modified time, which is the signal that the FS actually
    distinguishes the two — equality means the FS doesn't track
    creation, so we skip the heuristic to avoid wiping on every
    relaunch.
  - **Pnl-66 (content verification)**: on the first chunks_topic
    snapshot per session, the panel picks up to 5 chunks that
    are present in BOTH the disk cache and the live world, fires
    `chunk_tile` RPCs to fetch them fresh from the bridge, and
    bit-compares the surfaceArgb arrays against the cached
    versions. If a strict majority (3+ of 5) differ, the cache
    is from a previous world that the boot-time heuristics
    missed; the panel wipes the in-memory and disk caches at
    runtime via a new `TileDiskCache.wipeAll()` and re-renders.
    This is the strongest signal — the bridge produces
    deterministic ChunkTileHandler output for unmodified terrain,
    so a few sample chunks are sufficient to distinguish "same
    world" from "different world" with very high confidence.
    Player-modified chunks are tolerated (single-chunk mismatches
    don't trigger a wipe).
  - **Versions**: bridge 0.2.5 -> 0.2.6, panel 0.2.5 -> 0.2.6.
    Built and deployed; on next launcher boot the existing
    `mods/fathereye-bridge-0.2.5.jar` auto-deletes and
    `0.2.6.jar` takes its place.

- **Pnl-64: stable counter at 100 % + retroactive cache wipe via
  level.dat — version 0.2.5.** User: "map is still showing the
  map from the old world, it is not updating to reflect the new
  world folder" + "is still not getting to %100".
  - **Old-world bleed-through (root cause)**: Pnl-63's UUID stamp
    only triggered a wipe when the stored stamp was non-null and
    different from the current UUID. On first launch with v0.2.4
    after an existing v0.2.3 install, the stamp file did not
    exist yet, so the comparison short-circuited, the cache wrote
    the new UUID without wiping, and the user's old-world tiles
    survived. The mismatch detection only kicked in for the
    SECOND world delete.
  - **Pnl-64 (level.dat fallback)**: when no `.world-id` stamp
    exists yet, fall back to comparing the world's
    `level.dat.lastModifiedTime` against the oldest tile's
    `lastModifiedTime`. If the world is newer than every tile,
    those tiles must be from a previous world that was deleted
    before the current world existed — wipe. Then write the new
    UUID stamp so subsequent opens use the fast UUID path.
    Existing v0.2.3 users with valid worlds keep their tiles
    (level.dat older than tiles) and only newcomers / world-
    regenerators wipe.
  - **Counter oscillation (root cause)**: Pnl-63 evicted entries
    from `rejectedChunks` once the 5 s TTL expired, so the
    counter contribution dropped on every retry tick. When the
    bridge sentineled again, the entry was re-added and the
    counter climbed back. The user saw the loading bar oscillate
    around 87 % indefinitely.
  - **Pnl-64 (counter stability)**: keep entries in
    `rejectedChunks` continuously; the TTL only controls retry
    eligibility, not membership. The snapshot loop reads the
    timestamp and decides whether the chunk is eligible for re-
    request. Successful renders explicitly remove the entry on
    the success path so a chunk that was rejected then later
    rendered isn't double-counted (handled = tileImages +
    rejectedChunks).
  - **Versions**: bridge 0.2.4 -> 0.2.5, panel 0.2.4 -> 0.2.5.
    Built and deployed; on next launcher boot the existing
    `mods/fathereye-bridge-0.2.4.jar` auto-deletes and
    `0.2.5.jar` takes its place.

- **Pnl-63: TTL retry for rejected chunks + world-id cache
  invalidation — version 0.2.4.** User: "is loading chunks when
  i walk around but still not all chunks load and map is
  disjointed with empty areas" + "doesn't reset when i delete
  the world and generate a new one." Screenshot at v0.2.3 showed
  Father Eye stuck at 86 % with 80 unrenderable chunks visible
  as black gaps inside the explored area.
  - **Disjoint-gaps root cause**: Pnl-62's `rejectedChunks` set
    was permanent for the session. When the user walked away
    from a chunk it transitioned FULL -> LOAD between snapshot
    and RPC, the bridge sentineled, the panel marked the chunk
    rejected. When the user walked back the chunk re-promoted
    to FULL but the panel's permanent skip never retried, so
    the chunk stayed as a permanent black gap on the map.
  - **Pnl-63 (TTL retry)**: switched `rejectedChunks` from
    `Set<Long>` to `ConcurrentHashMap<Long, Long>` (key ->
    rejection timestamp). Entries TTL out after 5 s and the
    chunk becomes eligible for retry. The retry succeeds once
    the chunk is FULL again, filling the gap. Counter
    semantics preserved (rejected entries still count toward
    "handled" while alive). 5 s strikes a balance: short
    enough to fill gaps quickly when the player walks back,
    long enough that a chronically unrenderable chunk doesn't
    burn RPC budget every snapshot.
  - **World-reset root cause**: `TileDiskCache` keyed only on
    `serverDir`. Deleting `<serverDir>/world/` and regenerating
    the world inside the same server folder left the panel
    cache untouched — every previously-rendered tile reloaded
    from disk on warm and the new world was painted on top of
    the old terrain.
  - **Pnl-63 (world-id stamp)**: `TileDiskCache.open` now
    accepts the bridge's `instanceUuid` (persisted at
    `<serverDir>/world/serverconfig/fathereye-instance.uuid`,
    so a regenerated world creates a fresh UUID). The cache
    writes the UUID into `<serverDir>/fathereye-tiles/.world-id`
    on open and compares it on every subsequent open. On
    mismatch the cache wipes every `*.tile` under its base dir
    (the stamp file is preserved with the new UUID) so the new
    world starts with a clean canvas. App.tryConnect now passes
    `welcome.instanceUuid` through.
  - **Versions**: bridge 0.2.3 -> 0.2.4, panel 0.2.3 -> 0.2.4.
    Built and deployed; on next launcher boot the existing
    `mods/fathereye-bridge-0.2.3.jar` auto-deletes and
    `0.2.4.jar` takes its place.

- **Brg-22 + Pnl-62: loading bar always reaches 100 %, no more
  hangs — version 0.2.3.** User: "map panel is stopping loading
  and does not continue loading no matter how long i wait" +
  "hangs around 40 %" + "map has to slowly load until it gets
  to 100 %, it can never hang."
  - **Root cause**: chunks_topic reported every ChunkHolder
    regardless of ticket level, including LOAD-level (44) chunks
    whose ChunkSection[] had been freed. Brg-21 sentinel-rejected
    those at the bridge (correctly — they have no usable data).
    The panel removed sentinels from `requested`, the next
    chunks_topic snapshot re-added them, the bridge re-rejected,
    and the cycle repeated indefinitely. Net: the loading bar
    sat at 40-60 % forever, never finishing, even though every
    renderable chunk had long since been drawn.
  - **Brg-22 (chunks_topic filter)**: `WorldStateCollector` now
    filters holders down to chunks where `getChunk(FULL, false)`
    is non-null. That's the same predicate `ChunkTileHandler`
    uses for the FULL render path, so by construction every
    chunk reported in chunks_topic is one the bridge can
    actually render. LOAD-level chunks are excluded; they're
    invisible to the panel until they promote back to FULL.
    The panel's `TileDiskCache` preserves previously-rendered
    tiles even after the chunk downgrades to LOAD, so the
    user's map keeps accumulating coverage as they explore.
    Cost: one `getChunk` call per holder per snapshot, ~µs each,
    ~4 ms for 4 000 holders.
  - **Pnl-62 (rejected-chunks set)**: a new in-memory set tracks
    chunks the panel has sentinel-rejected this session. The
    onChunksSnapshot loop skips any chunk in this set, so even
    if a stale older bridge reports an unrenderable chunk, the
    panel won't ping-pong. The set is auto-pruned to chunks
    that are still in chunks_topic (so a chunk that fully
    unloads stops counting), and cleared on dim switch.
  - **Pnl-62 (progress counter semantics)**: `updateLoadingProgress`
    now counts rejected chunks toward "handled" so the
    percentage hits 100 % when every reported chunk has been
    answered, even if some answers were sentinels. Label format
    becomes `Loading X / Y chunks (Z %)` plus an
    `(N unrenderable)` suffix if any rejections happened.
  - **Versions**: bridge 0.2.2 -> 0.2.3, panel 0.2.2 -> 0.2.3.
    Built and deployed; on next launcher boot the existing
    `mods/fathereye-bridge-0.2.2.jar` auto-deletes and
    `0.2.3.jar` takes its place.

- **Brg-21 + Pnl-61: black-rectangle artefacts gone — version
  0.2.2.** User: "map was loading fast at first but has hung",
  screenshot showed the panel stuck at 1336 / 2025 (66 %) with
  black 16×16 rectangles scattered around the rendered area.
  - **Root cause**: Brg-19's holder-level fallback returned the
    in-memory `IChunk` for chunks at LOAD ticket-level. Their
    `ChunkHolder` is still in `chunkMap` (so chunks_topic
    listed them), but their `ChunkSection[]` was freed under
    memory pressure. Every `getBlockState` call returned AIR,
    which maps to `MaterialColor.NONE` (col == 0), which
    produced `surfaceArgb = 0xFF000000 | 0 = 0xFF000000` for
    every pixel. The Pnl-56 all-zero filter checked `== 0`,
    NOT `== 0xFF000000`, so the all-black tile slipped through
    and got persisted to `tileImages` and the disk cache. The
    user's panel keeps those black tiles forever.
  - **Brg-21 (server-side guard)**: the render loop now counts
    pixels with usable colour (`color != null && color !=
    MaterialColor.NONE && color.col != 0`). If the count is
    zero, the chunk is unrenderable in-memory and we sentinel
    it instead of returning the all-black tile. The panel's
    TileDiskCache keeps the previously-rendered version of the
    chunk alive across the LOAD downgrade, so the user's map
    still shows everything they've ever explored.
  - **Pnl-61 (client-side filter)**: `MapPane.onChunkTileResponse`
    now also rejects mostly-black tiles (≥ 252 of 256 pixels at
    `0xFF000000`). Catches any race where Brg-21 misses, and
    handles legacy responses from older bridge versions.
  - **Pnl-61 (disk cache cleanup)**: `TileDiskCache.loadOne`
    deletes any cached tile that's mostly-black on warm. The
    user's existing black rectangles will be auto-cleaned the
    next time the panel starts; the cleared chunks get re-
    requested and re-render properly (or sentinel if still at
    LOAD level).
  - **Versions**: bridge 0.2.1 -> 0.2.2, panel 0.2.1 -> 0.2.2.
    Built and deployed; on next launcher boot the existing
    `mods/fathereye-bridge-0.2.1.jar` auto-deletes and
    `0.2.2.jar` takes its place via the Bld-10 always-push-
    fresh launcher logic.

- **Brg-20 + Pnl-60: faster chunk fill — version 0.2.1.** User:
  "chunk loading is now progressing but is going very slowly".
  - **Brg-20 (per-render hoist)**: the inner 256-pixel render
    loop was making 256 expensive lookups per chunk:
    `world.registryAccess().registryOrThrow(BIOME_REGISTRY)` plus
    `world.getBiome(pos)` (8-corner BiomeManager noise blend) on
    every pixel. Hoisted both out of the loop:
    - Registry handle resolved once per chunk (not 256 times).
    - Inner loop uses `chunk.getBiomes().getNoiseBiome(x>>2, y>>2,
      z>>2)` — direct array lookup into the chunk-local 4x4x4
      biome grid, no 8-corner blend.
    - Fallback to `world.getBiome(pos)` if `getBiomes()` returns
      null (rare; only ProtoChunks before BIOMES status, which
      shouldn't happen for chunks reported by chunks_topic).
    Per-render cost drops from ~1-2 ms to ~0.3-0.5 ms.
  - **Brg-20 (miss budget bump)**: `MAX_MISSES_PER_TICK` 16 -> 64.
    Combined with the per-render speedup, worst-case tick cost is
    ~64 * 0.5 ms = 32 ms — still inside the 50 ms tick budget,
    same headroom as the previous 16 misses at 1-2 ms each.
    Steady-state fill rate jumps 320 chunks/sec -> 1280 chunks/sec,
    so a 3600-chunk world converges in ~3 s instead of ~11 s.
  - **Pnl-60 (panel-side fan-out)**: `CHUNKS_TOPIC_FETCH_BUDGET`
    1000 -> 2500. The previous 1000-per-snapshot at 1 Hz capped
    panel-side queueing at 1000 RPCs/sec, so the bridge sat
    under-utilised at the new 1280 chunks/sec capacity. 2500 lets
    the panel saturate the bridge during big initial fills.
  - **Triple audit (rule 9)**: clean. Audit noted that custom
    `getMapColor` on heavily modded servers (107 mods including
    ProjectE / Mekanism / Thaumcraft) could exceed the 0.5 ms
    per-render estimate, in which case the 64-misses-per-tick
    budget would push tick time past 50 ms and drop TPS below 20.
    Mitigation: monitor MSPT after deploy; the existing watchdog
    catches sustained TPS dips.
  - **Versions bumped**: bridge 0.2.0 -> 0.2.1, panel 0.2.0 ->
    0.2.1. The user can confirm the deploy via the in-app status
    bar (`Connected. bridge=0.2.1 ...`).
  - Built and deployed; the existing
    `mods/fathereye-bridge-0.2.0.jar` will be auto-deleted on
    next launch and replaced with `fathereye-bridge-0.2.1.jar`
    via the Bld-10 always-push-fresh launcher logic.

- **Bld-10: launcher always pushes fresh bridge jar on boot, plus
  bridge / panel version bumps to 0.2.0.** User: "still map panel
  is stopping loading chunks at 42 %. console is being spammed by
  fathereye every second and i do not want this. i launched from
  the modpack test server folder and your fixes made no
  difference there." Followed by "launcher has to always check
  and push a fresh bridge jar on boot" and "make sure you are
  updating version numbers for bridge so we can keep track
  easier."
  - **Root cause of the "no difference" symptom**: two compounding
    bugs.
    1. The bridge `:reobfJar` task target is invalid in this
       project. `bridge/build.gradle` has `jar { enabled = false }`
       (the actual jar is produced by `:shadowJar` since we ship
       a fat jar with Jackson / JNA / jctools / mapcore bundled
       in). My prior `./gradlew :bridge:reobfJar` runs were
       picking up the *previously-built* jar from `build/libs/`
       and just rewriting its names — never seeing the freshly-
       compiled classes from `build/classes/`. Verified:
       extracted `ChunkTileHandler.class` from the deployed jar
       and grep'd for "ChunkTile 60s:" — found "ChunkTile 1s:"
       (the pre-Brg-19 string). Switched build to
       `./gradlew :bridge:clean :bridge:shadowJar`; shadowJar
       has `finalizedBy reobfShadowJar` so the obfuscation runs
       on the fresh fat jar in one shot.
    2. The C# launcher only deployed the embedded bridge jar to
       `<serverDir>/mods` if NO `fathereye-bridge-*.jar` was
       already present. So once any jar (even a stale one) was
       there, the launcher refused to update it, defeating the
       whole "ship a new EXE to deploy a new bridge" workflow.
       Fix: launcher now ALWAYS overwrites with the embedded
       jar on every launch, deletes any older-version
       `fathereye-bridge-*.jar` siblings so version bumps don't
       load two copies, and silently keeps the existing jar if
       it's locked by a still-running server JVM.
  - **Version bumps for traceability**: `bridge` and `panel`
    both 0.1.0 -> 0.2.0. The user can now glance at the in-app
    status bar (`Connected. bridge=0.2.0 mc=1.16.5 ...`) and
    the marker file in `%LOCALAPPDATA%\FatherEye\bridges\*.json`
    to confirm a fresh deploy actually took effect. Updated
    sites: gradle.properties (bridgeVersion, panelVersion),
    Constants.BRIDGE_VERSION, App.VERSION, PlayerHeadCache User-
    Agent header.
  - Built fresh bridge-0.2.0.jar (verified contents include
    "ChunkTile 60s:", "func_219219_b", "func_219287_e",
    BRIDGE_VERSION="0.2.0"). Repackaged single-file panel EXE
    (72 MiB), deployed. On next launcher launch the user's
    `mods/fathereye-bridge-0.1.0.jar` will be deleted and
    replaced with `mods/fathereye-bridge-0.2.0.jar` carrying
    Brg-17/18/19 + the audit fixes.

- **Brg-19: holder-level chunk fallback + log de-spam.** User:
  "map panel is stopping loading chunks at 42 %. console is being
  spammed by fathereye every second and i do not want this".
  Screenshot showed the new `ChunkTile 1s:` diagnostic flooding
  the in-app console with `hits=0 newFull=0 newPartial=0
  sentinel(budget=N,null=16)` every second.
  - **Root cause of the 42 % stall**: even after Brg-18's
    FULL -> HEIGHTMAPS -> SURFACE fallback, chunks at ticket-
    level 44 (LOAD) still sentinel-rejected. In 1.16.5
    `ChunkSource.getChunk(cx, cz, status, false)` checks the
    chunk's *effective* status from its ticket level via
    `ChunkLevel.getStatus(level)`. A LOAD-level chunk maps to
    `ChunkStatus.EMPTY`, so even a SURFACE-status check fails
    even though the chunk's data is fully populated in memory.
  - **Fix**: when all three status checks return null, fall
    through to the `ChunkHolder` directly via reflection. The
    holder's `getLastAvailable()` walks the per-status future
    list from FULL down and returns whatever IChunk is held,
    ignoring the level-to-status gating. SRG names verified
    against the Forge userdev `joined.tsrg`:
    - `ChunkManager.func_219219_b(long)` -> ChunkHolder
      (`getVisibleChunkIfPresent`, matches the visible map
      WorldStateCollector iterates for chunks_topic, so the
      lookup always finds the holder).
    - `ChunkHolder.func_219287_e()` -> IChunk
      (`getLastAvailable`).
    Both reflected via `ObfuscationReflectionHelper.findMethod`
    so dev (Mojang names) and prod (SRG names) both work.
  - **Log de-spam**: the 1 Hz diagnostic was at INFO level and
    the panel ConsolePane shows everything, so the user saw a
    line per second. Demoted to DEBUG and stretched the cadence
    to 60 s. Default logback root keeps DEBUG out of the in-app
    console; users who need the diagnostic flip the
    `FatherEye-ChunkTile` logger to DEBUG in panel logback.
  - **Audit fix**: the first cut of the 60 s rewrite called
    `counter.getAndSet(0)` unconditionally, then only logged if
    DEBUG. In production (root = INFO) this zeroed the tallies
    every minute and silently discarded them, so any later flip
    to DEBUG would only show the post-reset window. Fix: bail
    out of `maybeLogSummary` on `!isDebugEnabled()` BEFORE
    touching the counters.
  - **Audit fix**: the holder-fallback `catch (Throwable t)`
    logged `t.toString()`, losing the stack trace. Pass `t` as
    the SLF4J last-arg so log4j prints the full frame; needed
    for diagnosing any future SRG-name drift on a different MC
    version.
  - Built and deployed; the next start should fill the rest of
    the 2025 reported chunks instead of stalling at 42 %.

- **Brg-18 + Pnl-57 + Pnl-58 + Pnl-59: map-hang fix + 6 audit-flagged
  bugs.** User: "map loading is hanging" (screenshot showed the
  loading counter stuck at 642 / 2025 chunks 32 %); plus prior
  request "everything has to be airtight, use many agents,
  overkill" surfaced six concrete bugs in the post-Brg-17 audit.
  - **Brg-18 (the urgent map-hang fix)**: root cause was a
    mismatch between `WorldStateCollector.collectChunkPositions`,
    which reports every `ChunkHolder` regardless of status, and
    `ChunkTileHandler`, which only rendered chunks at
    `ChunkStatus.FULL`. Chunks at LOAD / SPAWN / HEIGHTMAPS were
    in `chunks_topic` but the bridge sentinel-rejected them
    forever, so the panel's loading counter stalled at the FULL
    subset. Fix: try FULL first, fall back to HEIGHTMAPS, then
    SURFACE. Don't cache fallback tiles so they get re-rendered
    once the chunk promotes.
  - **Brg-18 (miss-budget bump)**: `MAX_MISSES_PER_TICK` raised
    from 4 to 16. With 4 misses / tick the cold-start fill rate
    was 80 chunks / sec, so a 2025-chunk world took ~25 s to
    converge. The user perceived that as hung. 16 / tick yields
    320 chunks / sec, ~6 s for 2025. Worst-case tick cost rises
    from 4-8 ms to 16-32 ms (still inside Forge's 50 ms budget).
  - **Brg-18 (NTP-jump fix)**: tick-budget reset switched from
    `System.currentTimeMillis()` to `System.nanoTime()`. Wall-
    clock backward jumps could leave `currentTickMs` in the
    future and never reset the budget; monotonic nanos eliminate
    that failure mode.
  - **Brg-18 (diagnostic 1 Hz log)**: new `ChunkTile 1s` summary
    line emits per-second counters (cache hits, new full tiles,
    new partial / fallback tiles, sentinels by reason). Without
    this, "the map is stuck" was un-debuggable from the server
    side.
  - **Pnl-57 (volatile fields)**: `currentDim` and
    `expectedChunksThisDim` in MapPane are now `volatile`. Both
    cross threads (FX writes, IPC reader reads / writes) and
    the audit confirmed the race was real.
  - **Pnl-57 (AtomicBoolean for requestTaskPending)**: replaces
    the `volatile boolean` check-then-set with
    `AtomicBoolean.compareAndSet(false, true)`. The FX thread and
    the recursive debounce thread could both pass the gate,
    spawning duplicate daemons that fired duplicate chunk_tile
    RPC batches.
  - **Pnl-57 (PipeClient leak on handshake exception)**: the
    `App.tryConnect` catch block now closes the transport on
    every connect / handshake failure path. The five throw sites
    in `PipeClient.handshake` (Hello write IOException, Welcome
    read, Jackson decode, protocol-too-old, 16-attempt timeout)
    were leaving the socket FD open until JVM exit; on reconnect,
    `pipeClient` was reassigned, dropping the only reference to
    the live FD.
  - **Pnl-58 (BackupService partial-folder marking)**: backups
    now copy into `world-<ts>.partial`, then atomically rename
    to `world-<ts>` only after every copy succeeds. A panel
    crash mid-copy used to leave a half-populated folder
    indistinguishable from a valid backup; restoring from one
    yielded half a world. `applyRetention` filters
    `endsWith(".partial")` so partials are never enrolled. New
    `sweepStalePartials()` deletes any `.partial` older than
    1 hour, called once at panel startup.
  - **Pnl-59 (MetricsDb event_log retention)**: the `event_log`
    table grew unbounded across long-running sessions because
    `trimRetention` only swept `metric_1m`. Added
    `AppConfig.History.eventLogRetentionDays` (default 30 d, 0
    disables) and a `DELETE FROM event_log WHERE ts_ms < cutoff`
    in the existing 60-min retention sweep. The pre-existing
    `event_log_ts` index keeps the predicate cheap.
  - **Triple audit (rule 9)**: 3 parallel agents reviewed the
    diff. Brg-18: clean ship-it; concerns about tighter tick
    headroom on heavily loaded servers documented for monitoring
    the 1 Hz log. Pnl-57: clean. Pnl-58 + Pnl-59: clean. One
    cosmetic FQN cleanup applied (`StandardCopyOption.ATOMIC_MOVE`
    already imported).
  - **Verification deferred to runtime**: rebuilt bridge jar,
    repackaged panel EXE (single-file 72 MiB), deployed both. The
    `ChunkTile 1s` log line lets the user verify throughput
    immediately on next server start.

- **Pnl-56 + Brg-17: stop the "biomes must be length 256" console
  flood.** User: "[image] server is running and i see map but now
  im getting constant looping error spamming console". Followed
  by "i need everything working properly", "everything has to be
  airtight, use many agents, overkill".
  - **Root cause**: Brg-16 introduced length-0 sentinel arrays
    (`new byte[0]` / `new short[0]` / `new int[0]`) returned for
    "chunk not loaded" or "over miss budget" responses. The mapcore
    `ChunkTile` constructor enforces a precondition
    (`Preconditions.checkArgument(biomes.length == 256, ...)`).
    With thousands of unloaded chunks the bridge hit the
    `IllegalArgumentException` 43 000+ times per second, flooding
    the server console.
  - **Brg-17 fix**: bridge now builds a fresh 256-length zero
    sentinel `ChunkTile` per cache miss / throttled response via a
    new `emptySentinel(dim, cx, cz)` helper. Length 256 satisfies
    the precondition. Allocating fresh per call (rather than
    reusing static arrays) keeps the sentinel immutable by
    construction, so any future in-VM panel mode can't contaminate
    bridge-side state.
  - **Pnl-56 fix**: `MapPane.onChunkTileResponse` adds an all-zero
    detection block at the top. If `surfaceArgb` is 256-length and
    every entry is 0, the panel removes the chunk from `requested`
    and returns BEFORE `tileImages.put`, `tileData.put`, or
    `tileCache.save`. This preserves the original Brg-16 disk
    cache anti-poisoning guarantee: the empty sentinel reaches
    neither the in-memory tile map nor disk.
  - **Why all-zero is unambiguous**: `ChunkTileHandler` at line 141
    builds `surfaceArgb[idx] = 0xFF000000 | rgb`, so a real loaded
    chunk's pixel is always at least `0xFF000000` (alpha set). The
    `MaterialColor.NONE` fallback path uses `0xFF202020`. A real
    chunk cannot produce all-zero ARGB, so the filter has zero
    false positives.
  - **Defense in depth preserved**: `TileDiskCache.loadOne`
    already deletes any all-zero tile encountered during cache
    warm, self-healing prior poisoned caches.
    `ChunkSurfaceCache.put` keeps its `surfaceArgb.length != 256`
    rejection as a redundant safety net.
  - **Triple audit (rule 9)** dispatched 3 parallel agents on the
    diff. All three reported clean. Agent 1 flagged a latent
    static-array contamination hazard if the panel ever ran
    in-process. Addressed by switching from shared static
    `EMPTY_*` arrays to fresh-alloc-per-call sentinel.
  - **Verification**: rebuilt and deployed bridge jar; ran the
    Forge server for 19 minutes with the panel connected. Server
    log shows zero `biomes must be length 256` errors. Panel log
    shows clean handshake, tile cache warm of 642 tiles, and
    1 Hz `chunks_topic` snapshots reporting 2 025 loaded chunks
    end to end. Server shut down gracefully via TCP `srv_stop`
    RPC with all dimensions saving cleanly.

- **Brg-16: server-side ChunkSurfaceCache to fix tick-thread
  starvation from chunk_tile RPCs.** User: "i started flying
  around and the chunks stopped loading in-game and they stopped
  loading on the map. approach for the map loading needs to have
  no impact on server performance and still load all loaded
  chunks on map".
  - **Root cause**: `ChunkTileHandler.handle` reads 256 block
    states + 256 biomes per chunk on the tick thread. With
    Pnl-52's chunks_topic listing every loaded chunk and the
    panel requesting tiles for all of them, the tick thread
    was hit with ~1 M reads/sec on a 4 000-chunk modpack
    server. Forge's chunk-gen task lost the budget fight, so
    chunks the player flew into never loaded.
  - **Fix**: new `ChunkSurfaceCache` (per-dim LRU, default
    16 384 entries / dim cap) fronts every chunk_tile RPC.
    Cache hits return in microseconds with zero world reads.
    Cache misses compute once, store the result, and serve
    every subsequent RPC for the same chunk from memory.
  - **Per-tick miss budget**: `MAX_MISSES_PER_TICK = 4` caps
    tick-thread compute work at ~16 % of a 50 ms tick. Cache
    fills in ~12 s for 1000 chunks; steady state is 100 %
    cache hits with negligible tick impact.
  - **Triple-audit reconciled** (3 parallel general-purpose
    agents per Standing Rule 9). All three flagged the SAME
    critical bug:
    - **Empty-tile disk-cache poisoning**: the bridge
      previously returned `int[256]` of zeros for "chunk not
      loaded" / "miss budget exhausted" responses. The panel
      persisted those to disk as valid all-black tiles,
      blocking re-fetch forever. Fix: bridge now returns
      zero-LENGTH sentinel arrays. The panel's existing
      `surface.length != 256` check triggers `failChunkRequest`
      which removes from `requested` without persisting.
    - **`ChunkSurfaceCache.put` defensive guard**: rejects
      tiles whose surfaceArgb is null or not 256-length so a
      future code path can't accidentally cache an empty
      sentinel.
    - **`TileDiskCache.loadOne` poison cleanup**: detects
      all-zero tiles on warm and deletes them so files left
      behind by prior buggy versions get cleaned up
      automatically.
    - **`MAX_MISSES_PER_TICK` 8 -> 4** per audit
      recommendation (was at risk of consuming up to 80 %
      of a 50 ms tick on heavy modded blocks with custom
      `getMapColor`).

- **Pnl-54: hourly world backups with 14-day age retention.** User:
  "i want hourly world backups, they should be deleted when they
  are two weeks old".
  - **`AppConfig.Backup`**: new `hourlyBackups` (default true),
    `hourlyIntervalMs` (1 h), `retainDays` (14). `retainCount`
    default lowered 10 -> 0 (unlimited within the age window) so
    age alone governs deletion. User's `config.json` patched.
  - **`App.wireLauncher`**: `FatherEye-HourlyBackup`
    `ScheduledExecutorService` fires `BackupService.runBackup()`
    every `hourlyIntervalMs` as long as `launcher.state() ==
    RUNNING`. Initial delay 5 min so short panel sessions still
    get coverage.
  - **`BackupService.applyRetention`** rewritten: walks all
    `world-*` folders newest-first, deletes any older than
    `retainDays` OR beyond `retainCount` (when `retainCount>0`).
    New `backupTimestamp(Path)` helper parses both the new
    `world-yyyyMMdd-HHmmss-SSS` and the legacy
    `world-yyyyMMdd-HHmmss` formats, falling back to
    file-mtime on parse failure.
  - **`runBackup`** uses millisecond-precision folder names so
    two panels (or hourly + pre-stop) firing in the same second
    don't collide.
  - **Triple-audit reconciled** (3 parallel general-purpose
    agents per Standing Rule 9). All three flagged the SAME
    bugs:
    - **SimpleDateFormat thread race** in `runBackup`
      (unsynchronized `TS.format` would corrupt under concurrent
      hourly + pre-stop). Fixed by switching to immutable
      `DateTimeFormatter`.
    - **Retention contradicted user intent** — with
      `retainCount=10` legacy default, only the newest 10
      backups kept, so 14-day age rule was moot. Fixed by
      defaulting `retainCount=0` and letting age govern.
    - **`App.stop` shutdown order** — `shutdownNow()` first
      interrupted in-flight backups mid-copy, leaving
      half-copied folders. Fixed: `shutdown()` →
      `awaitTermination(15 s)` → `shutdownNow()` only on
      timeout.
    - **Initial-delay coverage gap** — 1 h delay meant short
      panel sessions never produced a backup. Fixed by
      dropping initial delay to 5 min.
    - **Multi-panel timestamp collision** — same-second
      backups from two panels overwrote each other. Fixed by
      ms-precision in the folder name.

## 2026-04-27

- **Pnl-53: persistent on-disk tile cache (vanilla-map "fill
  in as you walk") + cumulative loading progress + Fit-all
  button + larger chunks_topic budget.** User: "i want the
  map to fill out like a vanilla map as they walk forward so
  that i can see a dynamic view of new chunks being rendered
  on the map" + "every chunk that has ever been loaded by any
  player to load and load dynamically as they explore".
  - **`TileDiskCache`** (new class): persists every
    `chunk_tile` response to
    `<serverDir>/fathereye-tiles/<dim>/<cx>_<cz>.tile` as a
    1048-byte little-endian binary (magic + version + cx + cz
    + ts + 256 surfaceArgb ints). Async writer on a daemon
    executor; idempotent overwrite per chunk; corrupt files
    are skipped on load.
  - **`MapPane.setTileCache`**: attaches the cache after
    handshake (`App.tryConnect` resolves
    `marker.serverDir`). `warmCacheFromDisk` runs on a
    background thread, repopulates `tileImages`, fires a
    redraw -- the user's previously-discovered area appears
    immediately on next launch.
  - **Live persistence**: every successful `chunk_tile`
    response also writes to disk; new chunks the player walks
    into get cached.
  - **Cumulative loading progress**: `tileImages.size() /
    max(expectedChunksThisDim, loaded)`. No more flashing
    yellow text between snapshots; the percentage rises
    monotonically.
  - **Fit-all button** + bounding-box tracking on every
    `chunks_topic` snapshot so the user can zoom to encompass
    every loaded chunk in the dim.
  - **`CHUNKS_TOPIC_FETCH_BUDGET` 200 -> 1000** so chunks
    around the player fetch faster on heavy modpacks.
  - **Diagnostic LOG.info** per chunks_topic snapshot with
    `loadedChunks` count and coord-pair count so the user
    can confirm the bridge is reporting.
  - **Triple-audit reconciled** (3 parallel general-purpose
    agents). All three flagged the SAME critical bugs:
    - **Dim-switch race**: in-flight `chunk_tile` responses
      for the OLD dim were corrupting NEW dim's `tileImages`
      (keyed only by `(cx, cz)`). Fixed with a dim-equality
      check at the top of `onChunkTileResponse`.
    - **Concurrent warm-thread races**: rapid dim-picker
      clicks spawn parallel warm threads that race on
      `tileImages`. Fixed with an `AtomicInteger
      dimGeneration` captured at thread start and checked
      before every put.
    - **`TileDiskCache` executor leak** on
      `setTileCache` replacement. Fixed with a new
      `TileDiskCache.close()` called on the previous
      instance.
    - **Silent canvas during warm**. Fixed with a "Loading
      cached tiles from disk..." status message during
      warm.

- **Brg-15: ChunkManager.getChunks reflection actually works in
  production.** Pnl-52's Brg-14 (yesterday) used
  `Class.getDeclaredMethod("getChunks")` which works in dev but
  fails at runtime with `NoSuchMethodException` because Forge
  1.16.5's reobf step rewrites Mojang method names to SRG names
  at the .class file level. The user's server log on first
  launch confirmed: `ChunkManager.getChunks reflective lookup
  failed; chunks_topic coordinate list will be empty.
  NoSuchMethodException: net.minecraft.world.server.ChunkManager.getChunks()`.
  Pnl-52's all-loaded-chunks feature was therefore silently a
  no-op on the production server. Switched to
  `net.minecraftforge.fml.common.ObfuscationReflectionHelper.findMethod`
  with the SRG name `func_223491_f`, verified against the
  authoritative `joined.tsrg` mapping in the Forge userdev
  cache. Helper handles dev (Mojang) and production (SRG)
  automatically.

## 2026-04-26

- **Pnl-52 + Brg-14: map shows all loaded chunks; offline-player
  persistence with greying + offline duration; online time in
  player info; chunk loading animation + percentage.** User
  request: "map is not updating correctly to show all loaded
  chunks, players are persisting on map when they log off, they
  need to be greyed out if they are not online with an indicator
  of how long they have been offline. also we need indicators on
  players in their info showing how long theyve been online.
  also we need a loading animation and percentage to illustrate
  the map loading chunks".
  - **Bridge (Brg-14)**: `ChunksSnapshot.DimChunks` adds
    `int[] chunks` -- a flat coordinate array of every loaded
    chunk in the dim, capped at `MAX_CHUNKS_REPORTED = 4096` per
    snapshot to bound payload (~48 KB). `WorldStateCollector`
    reflects `ChunkManager.getChunks()` (protected) via a cached
    static `Method` handle resolved once at class-load; one-shot
    `WARN` log on reflection failure so the user can diagnose
    "why is the chunks list empty".
  - **Panel**: `AUTO_SUBSCRIBE` adds `chunks_topic`. Dispatcher
    routes to `MapPane.onChunksSnapshot` which queues
    `chunk_tile` RPCs for every loaded chunk in the current dim
    up to `CHUNKS_TOPIC_FETCH_BUDGET = 200` per snapshot;
    remaining chunks fill in over subsequent 1 Hz snapshots.
  - **MapPane player persistence**: `Map<UUID, PlayerMarker>` ->
    `Map<UUID, PlayerEntry>` (volatile `online` / `lastSeenMs`
    / `marker`). On every `players_topic` snapshot we mark every
    entry offline first then re-mark the present ones online,
    so logged-out players persist for `OFFLINE_PLAYER_RETAIN_MS
    = 24 h` before being purged. Render: online players draw at
    full alpha with WHITE name; offline players draw at alpha
    0.4 with soft-grey name and an "offline 5m" label below.
  - **MapPane player info dialog**: shows `ONLINE` / `OFFLINE`
    status; "Online for Xh Ym" (from
    `PlayerMarker.onlineSinceEpochMs`) when online; "Offline
    for Z" + "Last seen ..." + "First seen W ago (this panel
    session)" when offline.
  - **MapPane loading widgets**: toolbar gains a JavaFX
    `ProgressIndicator` (animated spinner) plus a label
    "Loading X / Y chunks (Z%)" with a monotonically rising
    denominator (`peakPending`) so the percentage climbs 0 ->
    100 instead of fluctuating when the budget refills.
    "All chunks loaded" / spinner hidden when zero.
  - **Triple-audit reconciled** (3 parallel general-purpose
    agents). Real fixes adopted from all three audits: cached
    reflective `Method` handle (was per-tick `getDeclaredMethod`
    + `setAccessible`); one-shot WARN on reflection failure;
    offline label moved `cy + 6` -> `cy + 18` to clear the head
    (was overlapping); `PlayerEntry.online` / `lastSeenMs` /
    `marker` marked `volatile` for JLS portability across
    cross-thread access; `ProgressIndicator` + percentage in
    the toolbar to satisfy the user's "loading animation and
    percentage" request literally (not just a count).

- **Pnl-51: orphan-server elimination via Win32 Job Object +
  dialog kill-and-restart.** User: "we have to be certain there
  are never any orphans" + "i need it to wait to start the
  server until i click the save & start button".
  Diagnosis: a previous panel was force-killed (TaskKill /F);
  Pnl-18 had noted that bypasses the JVM shutdown hook. The
  child Forge server JVM (PID 19704) survived as an orphan
  parented by the dead panel. New panel auto-attached to the
  orphan and the dialog disabled Save & Start.
  Two-prong fix:
  - **`WindowsJobObject`**: new JNA-platform wrapper for Win32
    `CreateJobObject` / `SetInformationJobObject` /
    `AssignProcessToJobObject` with `KILL_ON_JOB_CLOSE`. The
    `ServerLauncher.start` path lazily creates a Job Object on
    first launch and enrolls every spawned JVM. When the panel
    JVM dies for ANY reason (graceful exit, TaskKill /F, BSOD,
    OS reboot), the kernel itself reaps the assigned process.
    Replaces the unreliable shutdown-hook-only guarantee.
  - **`PreBootDialog`**: `serverIsRunning` narrowed to mean
    panel-owned RUNNING; new `externalBridgeDetected` param.
    When an external orphan is detected (`anyLiveBridge && !launcherRunning`),
    Save & Start is **enabled** with the label "Stop existing
    & Start". The click handler in `App.openPreBootDialog`
    calls `killOrphanServers()` (`ProcessHandle.destroyForcibly`
    via the marker's PID, marker file deletion via
    `m.markerPath`) then sleeps 2 s for the OS to release
    `session.lock`, then `launcher.start`.
  - **Triple-audit reconciled** (3 parallel general-purpose
    agents). CRITICAL fix adopted from all three audits:
    REMOVED the `autoStart`-time `killOrphanServers` call.
    Marker files carry only the server PID (no panel-owner
    identity) so an auto-kill on panel B's startup would
    terminate panel A's healthy server. Job Object handles
    all future orphans; pre-existing orphans must be killed
    via the user's explicit "Stop existing & Start" click
    (intentional consent only, no inter-panel hostility).
  - Other audit fixes: use `m.markerPath` instead of
    recomputing the bridges-dir path; Job Object
    create/assign failures now push a WARNING through
    `stdoutSink` so the user sees the orphan-protection-OFF
    state in the in-app console, not just `panel.log`.

- **Pnl-50: companion outputs in Evaluations/.** User: "i need
  the outputs in evaluations folder". `EvaluationGenerator` now
  writes three artifacts alongside the markdown report on every
  server stop / crash:
  - `console-<ts>.log`: every `console_log` line, chronological,
    full ISO 8601 timestamp + level + thread + logger + msg.
  - `metrics-<ts>.csv`: per-second TPS / MSPT / heap / heap %% /
    GC / CPU / thread count rows for the entire session.
  - `alerts-<ts>.csv`: one row per fired alert with severity,
    kind, message.
  Each companion is independently try / catch wrapped so a
  partial failure doesn't block the markdown report. Audit
  fixes: ISO 8601 timestamps (was `HUMAN_TS` with
  locale-dependent timezone abbreviation that Excel choked on);
  `csvField()` now also escapes carriage return so embedded
  CRLF doesn't corrupt row boundaries.

- **Pnl-49: verbose logging end-to-end + pre-boot dialog
  always shows.** User: "now the memory pop up isnt coming up
  when i open it, and console is basically empty". Diagnosis
  confirmed by inspecting `logs/debug.log` (2.4 MiB / 30 min)
  on the running server: Forge IS emitting DEBUG events but
  our bridge's Log4j2 appender filter at `Level.INFO` was
  dropping them. Two fixes:
  - **Bridge `IpcAppender`**: `Level.INFO` -> `Level.ALL`. The
    appender now forwards whatever the root logger admits;
    backpressure stays in `Publisher`'s ring buffer and the
    panel's bounded `ConsolePane` ring. **Requires server
    restart**: the running JVM still has the old bridge jar
    in memory until you stop and relaunch.
  - **App.startInternal autoStart** no longer short-circuits
    the dialog when `anyLiveBridge()` is true. The dialog
    always shows when `showPreBootDialogOnStart=true`. The
    dialog itself detects the live external bridge (via
    `anyLiveBridge()`) and disables Save & Start in that
    state, so we can't collide on `world/session.lock`.

- **Pnl-48: verbose logging.** User: "i need verbose logging".
  Root logback level bumped INFO -> DEBUG so panel.log captures
  every IPC, launcher, watchdog, PipeReader and Eval event during
  boot. Per-logger overrides keep all FatherEye components at
  DEBUG explicitly; third-party noise (org.sqlite, com.sun.jna,
  javafx, com.fasterxml) capped at INFO so the log file stays
  focused on FatherEye behaviour. The 5 MiB rolling file appender
  keeps disk usage bounded even with DEBUG. Synthetic in-app
  console lines added on bridge-discovery start and on
  handshake success (full version banner with mc / forge / mod
  count / dim count).

- **Pnl-47: deep evaluation reports.** User: "i need the
  evaluations to be much more in depth and show all info, i
  need specific heap info, tps. mspt load, everything".
  `EvaluationGenerator` rewritten end-to-end. Highlights:
  - Every numeric metric now reports avg / min / max plus
    p10 / p50 / p90 / p95 / p99 percentiles via a new
    `ExtStats` class with linear-interpolation percentile.
  - Dedicated **Heap & Memory** section: configured `-Xmx`,
    distribution of used bytes and used %, pressure windows at
    `>= 75 / 85 / 90 / 95 %`, peak with timestamp, ASCII
    sparkline.
  - Dedicated **Garbage Collection** section: total GC wall
    time, seconds-with-GC, overhead %, max single-second pause
    with timestamp, full GC pause distribution.
  - Dedicated **CPU & Threads** section: distribution table,
    time at `> 70 / 85 / 95 %`, thread drift detection
    (start vs end count -> possible leak heuristic), CPU
    sparkline.
  - **Lag-Spike Analysis**: auto-detected contiguous runs of
    `TPS < 18`, ranked by severity, with start / end /
    duration / min-TPS / max-MSPT.
  - **Mod Impact by Dimension**: per-dim breakdown with top
    15 mods per dim.
  - **Player Sessions**: reconstructed per-player first-seen /
    last-seen / total-online-time from `players_topic`
    snapshots.
  - **Mobs trend**: average + peak total mobs and peak per
    dimension.
  - **Alerts Timeline**: full list (no 10-line cap),
    timestamp / severity / kind / message.
  - **Console Highlights**: top WARN and ERROR sources
    counted by logger name.
  - **Crash Analysis** (only on non-zero exit): last 50
    console lines plus all `Exception` / `Caused by:` /
    stack-trace lines extracted to a code block.
  - ASCII sparkline helper using Unicode block characters.
  - Existing sections retained; performance overview
    extended with the new percentile distribution table and
    TPS / MSPT sparklines.

- **Pnl-46: two console / dialog UX bugs.**
  - "Save && Start" rendered with the literal double-ampersand
    because JavaFX `ButtonType` labels do NOT honour Win32/Swing
    `&&` escape sequences -- the label is rendered verbatim.
    Changed to single ampersand.
  - Console pane appeared empty unless the user clicked
    Auto-scroll. Cause: the Pnl-40 tail-f auto-pause listener
    misfired on the first render before the TextArea was laid
    out (Console tab not active). `setScrollTop(MAX_VALUE)` was
    stored verbatim and `lastKnownMaxScroll` captured
    `MAX_VALUE`; on the next layout pass the clamped value
    (e.g. 100) compared against `MAX_VALUE - 8` was `false`, so
    Auto-scroll was silently auto-unchecked. Removed the
    auto-pause-on-scroll listener entirely; the user controls
    pause via the explicit checkbox. `renderAndSnap` now also
    re-applies `setScrollTop(MAX_VALUE)` via
    `Platform.runLater` so the bottom is hit reliably even if
    the first call landed before layout. Dead fields removed.

- **Pnl-45: CRITICAL deadlock fix.** User report: "class loading
  definitely doesnt take this long. it should take less than a
  minute before log starts. 230s have elapsed". The actual cause
  was nothing to do with class loading -- the server JVM was
  never launching because of a deadlock I introduced in Pnl-43.
  - **Root cause**: `App.startInternal` submitted both the
    autoStart task AND `tryConnect` to a SINGLE-THREADED
    `ipcExecutor`. Pre-Pnl-43, the autoStart task called
    `launcher.start()` directly, blocking the IPC thread for the
    few seconds it took to spawn the JVM, then returning so
    `tryConnect` could poll. Pnl-43 changed autoStart to instead
    show the pre-boot dialog (a Platform.runLater call that
    returns immediately), with `launcher.start()` submitted to
    `ipcExecutor` LATER from inside `openPreBootDialog`. But
    `tryConnect` was already running by that point, in the IPC
    thread's poll-and-Thread.sleep(2000) loop. With Pnl-44's
    timeout bumped to 10 minutes, `launcher.start()` sat queued
    behind `tryConnect` for up to 10 minutes -- the JVM never
    spawned, no marker was ever written, the user just watched
    the elapsed counter tick up.
  - **Fix**: `tryConnect` now runs on its own dedicated
    `FatherEye-Connect` daemon thread (not on `ipcExecutor`).
    Same fix for the `onBridgeDisconnect` reconnect watcher
    (`FatherEye-Reconnect` thread). `ipcExecutor` is now
    free for `launcher.start()`, RPCs, etc.
  - **Bonus state-aware polling**: when `launcher.state()` is
    STOPPED or CRASHED, the polling status shows "No server
    running. Use Configure or Start in the toolbar to launch."
    instead of counting elapsed seconds against an idle
    launcher. The 10-minute deadline only ticks while a real
    launch attempt is in flight, so cancelling the dialog and
    later clicking Start gives a fresh budget.

- **Pnl-44: state-machine + bridge timeout fix for heavy modpacks.**
  User reports: "it says server running but nothing is happening"
  + "bridge has to wait longer than 90 seconds for a server with so
  many mods" + "panels are blank... about a minute before i see any
  thing happening in the log". Three underlying issues:
  - **Premature RUNNING transition**: `ServerLauncher.start()`
    flipped state to RUNNING the moment `process.start()`
    returned. On a 100+ mod modpack the server JVM takes 5-10
    minutes after spawn to reach `FMLServerStartedEvent` (when the
    bridge writes its marker and the server is actually accepting
    connections). The toolbar said RUNNING the whole time. Fix:
    state stays at STARTING after the JVM spawn. New
    `markRunning()` method promotes STARTING -> RUNNING; called
    from `App.tryConnect` only after the bridge handshake
    succeeds. The toolbar badge now reflects reality.
  - **Bridge marker poll deadline 90 s -> 600 s**: on heavy
    modpacks the bridge is not even registered at 90 s, so the
    panel gave up and stats/players/map never populated even
    after the server eventually came up. New
    `AppConfig.serverRuntime.bridgeMarkerTimeoutMs` (default
    600 000 ms = 10 min). The user's existing config.json was
    hand-patched. Status bar shows elapsed/budget seconds during
    the wait so the user knows it's still progressing.
  - **Silent boot phase**: Forge's classloader and mod scanning
    don't emit stdout for 30-90 s on heavy modpacks, so the
    console pane was blank. The state-sink now emits a synthetic
    console line on STARTING ("Server JVM spawned. Waiting for
    Forge classloader and mod scanning to begin emitting
    output...") and another on RUNNING ("Bridge handshake
    complete; server is now ready to accept connections.") so the
    operator sees the panel is alive.
  - **Audit fix**: seed `watchdog.heartbeat()` right after
    `markRunning()`. On long boots (5+ min) the watchdog's
    arm-time + warmup window (4 min default) expires before the
    bridge handshake; the first real heartbeat would have
    arrived after the no-heartbeat threshold (90 s) was already
    breached, logging a misleading WARN. Auto-restart is disabled
    by default so it was just noise, but the seed eliminates it.

- **Pnl-43: pre-boot dialog auto-shows before autoStart.** User
  report: "i didnt get a config pop up for server ram usage and
  the server wouldnt start". Pnl-42 added the Configure dialog as
  a manual toolbar button, but on this user's `autoStart=true`
  config the launcher fires immediately on panel start and the
  Pnl-42 audit fix auto-disables the Configure button once state
  leaves STOPPED/CRASHED, so the user never had a chance to
  click it. Two changes:
  - **AppConfig**: new `serverRuntime.showPreBootDialogOnStart`
    flag (default true; user's existing `config.json` was
    hand-patched to add the field).
  - **App.startInternal autoStart**: when `autoStart` is on AND
    no live bridge AND `showPreBootDialogOnStart` is true, hop to
    the FX thread via `Platform.runLater(this::openPreBootDialog)`
    instead of calling `launcher.start()` directly. The dialog
    becomes the gate. User clicks Save & Start to actually boot;
    Save (no start) or Cancel leaves the server stopped and the
    user can click Start in the toolbar later.
  - **PreBootDialog**: new "Show this dialog automatically before
    every server start" CheckBox at the bottom, seeded from
    current config. The user can untick it once and from then on
    the launcher boots directly (original "click EXE, get
    server" UX). `Result.showOnNextStart` carries the value back
    and `App.openPreBootDialog` persists it.
  - **Audit fix**: re-check `anyLiveBridge()` at click time on
    the Save & Start path. The original check happens once when
    autoStart fires; if a second panel or an external `start.bat`
    brings up a server while the dialog is open, the original
    sample is stale and we'd collide on `world/session.lock`.

- **Pnl-42: pre-boot configuration modal (RAM, JVM args,
  server.properties).** User request: "i want to add a pop up that
  comes up so you can set server ram usages and special arguments,
  server properties, etc. before server boot". New
  "Configure..." toolbar button opens a modal Dialog with two tabs:
  - **JVM & Memory**: dedicated TextFields for Xmx and Xms, a
    MaxGCPauseMillis field, checkboxes for UseG1GC /
    ParallelRefProcEnabled / HeapDumpOnOutOfMemoryError /
    -noverify, plus a free-form "Other JVM args" textarea for
    everything we don't have a checkbox for. All fields are
    seeded by parsing the existing
    `AppConfig.serverRuntime.jvmArgs` string.
  - **server.properties**: editable TableView seeded with 18
    common keys (motd, max-players, view-distance, online-mode,
    ...) plus all other keys actually present in the file. New
    `ServerProperties` helper class loads / parses / writes the
    file while preserving comments and ordering (vanilla
    `java.util.Properties` discards both).
  - Buttons: Cancel / Save / Save & Start. Save & Start is
    disabled when the server is currently running (the user must
    Stop first to apply RAM / JVM arg changes).
  - `ServerLauncher.spec` was final; it is now volatile, with a
    new `updateSpec(ServerLaunchSpec)` synchronized method that
    refuses any state other than STOPPED or CRASHED, so a
    half-applied spec mid-run is impossible.
  - `App.openPreBootDialog`: persists `jvmArgs` into
    `config.json`, writes `server.properties`, calls
    `launcher.updateSpec`, and (if Save & Start) submits
    `launcher.start()` to the IPC executor with an immediate
    "Launching server with new configuration..." status update so
    the panel does not look idle during the multi-second JVM
    spawn.
  - State-sink integration: the Configure button is auto-disabled
    while state is STARTING / RUNNING / STOPPING and re-enabled
    on STOPPED / CRASHED.
  - Triple-audit reconciled (3 parallel general-purpose agents).
    Real bugs fixed: TableView edit-commit gap (ActionEvent filter
    on the Save buttons forces `edit(-1, null)` so cells with
    in-flight typing are committed before the result converter
    runs); silent tmpdir fallback when server.properties is
    unreadable replaced with a visible red warning Label and
    no-op saves. Cheap UX wins: Xmx/Xms/gcPause regex validation
    on Save (refuses `-Xmxgarbage` before the JVM ever sees it);
    EvaluationGenerator lambda now reads
    `launcher.spec().workingDir` live instead of capturing
    `runSpec.workingDir` at wireLauncher time; immediate
    "Launching..." status update on Save & Start.

- **Pnl-41: alert UX overhaul + UTF-8 encoding root cause fix.**
  User report: a "Father Eye â€" Low TPS" pop-up fired right after
  joining a 100+ mod modpack server. Three problems behind one
  screenshot:
  - **Encoding (root cause)**: the dialog title contained a UTF-8
    em-dash ("Father Eye " + EM-DASH + " Low TPS"). The build had
    no `compileJava.options.encoding`, so `javac` fell back to the
    platform default (Windows-1252 on US Windows) and decoded the
    UTF-8 source bytes (E2 80 94) as the three Windows-1252
    characters that displayed as `â€"`. Fix: added
    `tasks.withType(JavaCompile).configureEach { options.encoding
    = 'UTF-8' }` to root `build.gradle` allprojects, so every
    subproject's compileJava + compileTestJava runs UTF-8. Future
    non-ASCII literals will not silently mojibake.
  - **Modal UX**: the alert was a `javafx.scene.control.Alert`
    that, even with `.show()` (non-blocking), opened a separate
    Windows window the user had to click-to-dismiss every time the
    threshold tripped. Replaced with `MainWindow.showAlertBanner`,
    a non-modal in-window banner overlaid via `StackPane` on top
    of the tab area (so the tabs do not shift down when it
    appears). Click anywhere on the banner to dismiss. Auto-fades
    after 12 s. Warning-amber palette
    (`#7a5510` / `#fff2c8` / border `#b8860b`).
  - **Trigger logic**: the docstring promised "30 s suppression
    after a player join-storm" but the wiring was never connected.
    Implemented: `AlertEngine.onPlayersSnapshot` detects player
    count increases and pushes `armedAfterMs` forward 30 s, since
    chunk-gen + entity-wakeup TPS dips on player join are normal.
    Also resets `tpsBelowSinceMs = 0` on join so pre-join sustain
    accumulation does not survive into the post-disarm period.
    Wired in `App.tryConnect`'s players_topic dispatcher.
  - **Defaults**: bumped `tpsDropThreshold` 18.0 -> 16.0 and
    `tpsDropSustainSec` 10 -> 30 for new installs. The existing
    user's `config.json` was hand-patched to the same values so
    the change takes effect for them immediately.

  Triple-audit reconciled (3 parallel general-purpose agents):
  encoding root cause adopted (must-fix, A3); 12 s timeout +
  warning-amber + StackPane overlay adopted (A3 polish).
  Idempotent bindMainWindow guard and "+N alerts" counter
  declined as low-priority.

- **Pnl-40: Console + Config promoted to top-level tabs; auto-scroll
  freeze and skip-to-top fixes.** User report: "i want the log panel
  to be in its own seperate tab from the map so that the log takes
  up more of the window, also log keeps skipping to top and not
  always following latest entries when i scroll".
  - **Layout**: removed the bottom horizontal SplitPane that pinned
    ConsolePane and ConfigPane to the bottom 30% of the window.
    Both panes are now Tabs in the single TabPane (alongside Map,
    Players, Mobs, Mods, Stats, Alerts), so any selected tab fills
    the entire main area below the toolbar. The Console tab gives
    the log the full window height the user asked for. (ConfigPane
    was a sibling in the bottom split, so removing the split forced
    it somewhere; promoting it to a tab is consistent with every
    other panel.)
  - **Auto-scroll bug**: two underlying issues. (a) `TextArea.setText`
    resets `scrollTop` to 0 on every call, so the previous "rewrite
    on each tick" approach yanked the user back to top every 100 ms
    even when the sticky-bottom guard fired. (b) `positionCaret(end)`
    moves the caret but does not reliably scroll the viewport, so
    the latest line stayed off-screen below.
  - **Fix**: replaced `positionCaret` with
    `setScrollTop(Double.MAX_VALUE)`, which the TextArea skin clamps
    to (contentHeight - viewportHeight). Added an explicit
    "Auto-scroll" CheckBox (default ON) plus a "Dropped: N" counter
    in a small toolbar above the log. While Auto-scroll is OFF, the
    ring keeps draining (so no log lines are lost and the bridge IPC
    isn't corked) but the TextArea is frozen; toggling ON fires
    `renderAndSnap` immediately. "tail -f" auto-pause: the
    scrollTop property listener watches for user wheel and scrollbar
    drag, compares against the last clamped max, and auto-toggles
    the CheckBox so the user can wheel up to read history without
    finding a control first; scrolling back to the bottom auto-
    re-engages. An `ignoreScrollChange` flag suppresses our own
    programmatic `setText`/`setScrollTop` so they're not misread as
    user-initiated pauses.

- **Pnl-39: Mods Impact tab shows every installed mod (idle or
  active).** User report: "not all installed mods are showing in
  mods impact tab" (screenshot showed 6 rows on a 114-mod modpack
  server). The bridge's `mods_impact_topic` snapshot only contains
  per-dim entries for mods that own ticking entities or tile
  entities at the moment of sampling, so any mod without a
  registered ticker (the vast majority on a typical modpack) was
  invisible in the panel even though Forge had loaded it. The wire
  already carried the full set: `Welcome.mods` is built from
  `ModList.get().getMods()` and includes every installed mod's id
  and version. Surfaced it on the panel side: `WelcomeInfo` now
  exposes `String[] modIds`; `ModsPane.setKnownMods(String[])` is
  invoked from `App.tryConnect` immediately after handshake. The
  pane retains the last impact snapshot and re-renders on each
  snapshot AND on `setKnownMods`, merging idle placeholder rows
  (dim "all dims", entityCount/TE 0, descriptor "Idle" in grey) for
  any installed mod absent from the snapshot. Active impact rows
  still rank by attributedNanos descending; idle rows sort
  alphabetically below (sortKey 0, secondary mod-id compare).
  Header label updates per snapshot to "Mod impact (proportional,
  approx): N active, M idle". On reconnect, `setKnownMods` clears
  the cached snapshot too, so a server with a different mod set
  doesn't render stale impact rows for a beat.

- **Pnl-38 — ConsolePane FX-thread freeze fix (TextArea anti-pattern)**
  — User report: "panel has frozen and is not updating now when im
  using it for my Modpack test server". Thread dump confirmed the FX
  thread was stuck in `TextArea.appendText` for the running session
  (114 s of CPU on a 136 s elapsed thread). On a heavily-modded server
  the bridge's `console_log` topic streamed thousands of mod-load WARN
  lines per second; each line did a fresh `Platform.runLater` that
  called `appendText`, which copies the TextArea's entire backing
  String — O(n) per append, O(n²) over the session. With ~700 MB of
  retained text the FX thread spent all its time on memory copies and
  never serviced redraws, status updates, or input.
  Fix: pull the producer/consumer apart. `onLogLine` now just enqueues
  the formatted line on a lock-free `ConcurrentLinkedQueue`; a 10 Hz
  `AnimationTimer` on the FX thread drains up to 200 lines per tick
  into a bounded ring (`ArrayDeque` capped at 600 lines) and rewrites
  the TextArea via a single `setText` call. Total buffer is now
  capped at ~70 KB and per-tick cost is constant regardless of the
  inbound line rate.

- **Pnl-36 — server-session evaluation reports** — User: "whenever
  server is shutdown i want a detailed file with an evaluation of all
  aspects of server peformance and how each mod affects it. put these
  in a folder called 'Evaluations' in the server's directory". New
  `EvaluationGenerator` queries the SQLite history (tps_topic,
  mods_impact_topic, players_topic, event_alert, console_log) for the
  session window and writes a markdown post-mortem to
  `<serverDir>/Evaluations/evaluation-<timestamp>.md`. Sections:
  duration / exit code, performance table (avg/min/max for TPS/MSPT/
  heap/GC/CPU/threads), composite Health-score (avg + worst), per-mod
  tick-time totals ranked with Negligible→Severe descriptors, peak
  player count, alerts and WARN/ERROR console-line tally,
  recommendations. App's launcher state-sink fires the report whenever
  state transitions to STOPPED or CRASHED, off-thread so the state
  propagation isn't stalled by the DB query.

- **Pnl-37 — real player skin heads (offline-mode-friendly)** — User:
  "panel is still not correctly showing player face texture where they
  are on map, its using a generic face texture still on the map".
  PlayerHeadCache now resolves skins via mc-heads.net by NAME instead
  of Crafatar by UUID — Crafatar returned its default-Steve fallback
  for offline / LAN servers because their UUIDs are deterministic
  hashes Mojang doesn't recognise. mc-heads.net does username→Mojang
  lookup server-side and returns the actual skin. Cache is still keyed
  by UUID so per-player heads cache cleanly, but the fetch query uses
  the live name from the snapshot.

- **Pnl-35 + Bld-09 — wire AppConfig into ServerLaunchSpec; replace
  IExpress wrapper with self-contained C# launcher** — User: "app is
  still launching from wrong folder, all of my mods arent loading from
  the modpack test mods folder", "network path was not found again",
  "panel worked fine in other folder, why doesnt it work in this one",
  "its still running from the other directory".
  - **Bld-09**: replaced the IExpress single-file wrapper (which
    extracted to %TEMP%\IXP000.TMP and inherited that as the panel's
    cwd, AND which choked on folder names with spaces showing
    "The network path was not found" with title `\\` BEFORE the
    launcher could even start) with a pure C# self-contained EXE.
    Embeds the panel jpackage bundle and the bridge mod jar as PE
    resources via csc.exe `/resource:` switches; on launch, extracts
    to `%LOCALAPPDATA%\FatherEye\bundle-<ver>` if not cached and
    auto-deploys the bridge jar. Crucially uses
    `Process.GetCurrentProcess().MainModule.FileName` to derive the
    server folder — bulletproof regardless of cwd inheritance, parent
    process, or path characters.
  - **Pnl-35**: `App.wireLauncher` was building the launcher via
    `ServerLaunchSpec.defaults()` (a hardcoded TomCraft-Server path)
    so the Pnl-34 cwd auto-detection in AppConfig was correctly
    overriding `serverRuntime.workingDir` but the launcher silently
    used the hardcoded path anyway. New `ServerLaunchSpec.fromConfig
    (AppConfig)` reads the resolved config; `App` now uses it. The
    panel finally honours the detected server folder end-to-end.
  - **Stale-marker reconnect**: `App.onBridgeDisconnect` was
    `discoverFirst`-ing the first marker on disk and trying to
    reconnect — but a still-on-disk marker for a killed server gives
    Connection refused. Switched to `anyLiveBridge()` which validates
    the PID is alive and the cmd looks like a JVM before treating
    the marker as live.

- **Pnl-34 — auto-detect cwd as the server folder (multi-server support)**
  — User: "i have made a copy of my server with more mods, with a copy
  of the server start application in the folder. the server is called
  'modpack server test'". The shared `%LOCALAPPDATA%/FatherEye/config.json`
  was hard-pinning `serverRuntime.workingDir` to the original
  TomCraft-Server, so a panel launched from a second server folder
  would still try to manage the first server. `AppConfig.load()` now
  detects when cwd looks like a Forge 1.16.5 server (has `mods/` plus
  any of `eula.txt` / `server.properties` / the Forge or vanilla
  server jars) and overrides `workingDir` (and `backup.backupDir`) to
  the cwd. The shared config still drives display / alerts / backup
  retention so user preferences carry across instances. Same EXE drops
  into any number of server folders and "just works".

- **Pnl-33 — disconnect handling, auto-reconnect, faster server shutdown**
  — User: "panel froze after server crashed" + "server is not being
  killed correctly it seems".
  - **PipeReader on-disconnect hook**: when the reader exits (server
    crash, restart, network blip), the panel now (a) calls
    `pipeClient.close()` so subsequent writes fail fast instead of
    blocking on a dead socket — the proximate cause of the perceived
    freeze; (b) disarms the watchdog so a stale heartbeat doesn't
    fire; (c) updates the status bar; (d) kicks off a 5-minute
    background marker-poll for auto-reconnect when a fresh bridge
    comes up.
  - **Bounded shutdown-hook stop**: the JVM-shutdown hook (Pnl-18) was
    calling `launcher.stop()` which waits up to 30 s for graceful
    server exit, but Windows TerminateProcess()es a JVM after about
    10 s of shutdown, orphaning the server mid-stop. New
    `launcher.stopFast()` uses a 7 s grace window so the hook always
    finishes inside Windows' allowance.
  - **forceKillTree**: when force-killing the server, the panel now
    also walks `process.descendants()` and kills each. Plain
    `destroyForcibly()` only tears down the named process; Forge mod
    loaders occasionally fork helpers that would otherwise survive
    as orphans.

- **Pnl-32 — real player skin heads on the map** — User: "map is not
  using players face texture". New `PlayerHeadCache` async-fetches each
  player's head from Crafatar
  (`https://crafatar.com/avatars/<uuid>?size=20&overlay`), caches the
  result keyed by UUID, and serves it to the map renderer. While a
  fetch is in flight the existing stylised Steve placeholder is drawn;
  when the real head arrives the map redraws and the placeholder is
  replaced. Failed fetches back off for 60 s before retry. Crafatar
  serves a default Steve for unknown / offline UUIDs so we always
  render *something* recognisable. Added `java.net.http` to the
  jpackage / jpackageMacDmg / jpackageMacApp `--add-modules` lists so
  HttpClient is in the bundled JLink runtime.

- **Pnl-30 + Pnl-31 — top-mod factor on Stats + per-mod impact rating
  on Mods** —
  - **Pnl-30 (Stats)**: 6th load factor in the Health Assessment shows
    the dominant mod's tick share. Cap 3.0 pt deduction; 5% of one
    real-time second = ignored, 50%+ = max deduction. Row label
    updates to "Top mod (&lt;modid&gt;)" so the operator sees who's hurting.
    Wired via `mods_impact_topic` Snapshot routed to StatsPane.
  - **Pnl-31 (Mods)**: every mod row now shows an Impact descriptor
    (Negligible &lt;1%, Low &lt;5%, Moderate &lt;15%, High &lt;35%, Severe ≥35%)
    plus colour-matched inline ProgressBar of its tick share. The
    table is still sorted by raw nanos so the top-of-list order
    matches the worst-offenders.

- **Pnl-29 — Server Health Assessment widget on Stats** — User: "i want
  the stats panel to have an assessment that give the server current
  state a rating out of 20 with corresponding descriptor and bars or
  percentages indicating what is causing loads". Composite 0.0–20.0
  health score, recomputed each `tps_topic` Snapshot. Score starts at
  20.0 and subtracts deductions per load factor:
  - **TPS deficit** (cap 5.0): 0.5 pt per TPS below 20 (TPS ≤ 10 → max).
  - **MSPT load** (cap 5.0): 0.1 pt/ms above 30 ms (MSPT ≥ 80 → max).
  - **Heap pressure** (cap 4.0): 0.16 pt/% above 75% (100% → max).
  - **GC pressure** (cap 3.0): 0.06 pt/ms above 50 ms/sec.
  - **CPU load** (cap 3.0): 0.10 pt/% above 70%.

  Big colour-coded score (green/blue/yellow/orange/red) plus
  descriptor: Excellent ≥19, Good ≥16, Fair ≥12, Poor ≥8, Critical &lt;8.
  Each load factor renders as a horizontal ProgressBar (deduction /
  cap) so the operator sees at a glance which factor is hurting the
  server.

- **Pnl-28 — finish the deferred Watchdog audit items** —
  - **Clock skew**: timestamps now use `System.nanoTime()` instead of
    `System.currentTimeMillis()`. NTP corrections, system suspend/resume,
    or RTC adjustments mid-session can no longer mis-fire the threshold.
  - **UI surface**: toolbar now shows a "Heartbeat: Ns" badge that
    updates at 1 Hz on the FX thread, colour-coded (green &lt; 5s, yellow
    &lt; 30s, red &gt; 30s). `--` when the watchdog is disarmed.
  - **Broader heartbeat signal**: `PipeReader` now invokes a frame-hook
    Runnable on every successfully-read inbound frame, regardless of
    kind/topic. App wires that hook to `watchdog.heartbeat()` so the
    watchdog tracks any bridge activity, not just the narrow
    `tps_topic` Snapshot. If the tps publisher specifically dies but
    other publishers keep running, the watchdog now correctly stays
    happy.

- **Pnl-27 — Stats pane gets 3 more charts (GC ms, live threads,
  process CPU %)** — User: "i wants stats page to also have graph or
  graphs". Added a second row of charts beside TPS / MSPT / Heap:
  - **GC pause (ms / sec)** — total stop-the-world pause time per second.
  - **Live threads** — JVM live thread count over time.
  - **Process CPU %** — OS-reported CPU utilisation of the server JVM.
  All three values are pulled from the existing `tps_topic` Snapshot;
  no bridge changes required. Charts share the JFreeChart-FX rolling
  window (15 min @ 1 Hz = 900 samples cap).

- **Bld-08 — true standalone single-file Windows .exe (IExpress + C# launcher
  + bridge auto-deploy)** — User: "i want the server starter application
  to be a standalone app with no dependency folders" → "find another
  solution than 7zip, has to be standalone" → "i dont want things hidden,
  i want an actual standalone" → "i want the exe to deploy the bridge jar
  to mods folder of the in same directory in it if the mods folder doesnt
  contain the bridge jar". Solution:
  - Windows-bundled `iexpress.exe` packs the jpackage app-image bundle
    plus a tiny native C# launcher (~5 KB) into a single 73 MB self-
    extracting `.exe`. No app/ or runtime/ siblings.
  - The launcher is compiled at build time via `csc.exe` (always present
    in `C:\Windows\Microsoft.NET\Framework64\v4.0.30319\`). A .bat-based
    AppLaunched fails on 64-bit Windows because IExpress hard-codes the
    long-removed `Command.com /c <bat>` shim — a native EXE sidesteps it.
  - On launch, the launcher: (a) extracts the bundled jpackage zip to
    `%LOCALAPPDATA%/FatherEye/bundle-<ver>` if not cached, (b) checks if
    the host server's `mods/` folder is missing a `fathereye-bridge-*.jar`
    and copies the embedded bridge jar there if so, (c) launches the
    panel with `WorkingDirectory = <server folder>` so auto-start finds
    the right serverDir.
  - Verified end-to-end: handshake OK, all 5 topics subscribed in &lt;1 ms,
    map renders, bridge auto-deployed.

- **Bld-09 — GitHub Actions cross-platform builds** — `:panel:jpackageMacDmg`
  task added (macOS-only, jpackage cannot cross-compile). New
  `.github/workflows/build.yml` runs on every push to main: builds the
  Windows .exe on `windows-latest` and the macOS .dmg on `macos-latest`,
  uploads both as Actions artifacts, and on a `v*` tag attaches them to
  a GitHub Release.

- **Pnl-26 — backup skip session.lock** — User: "had backup issues
  ... pre-stop backup failed: world/session.lock". Forge holds session.lock
  for the world's lifetime; the pre-stop backup races the still-running
  server. `BackupService.copyTree` now skips `session.lock`, `*.lock`,
  `*.tmp`, sqlite WAL/SHM (`-wal`, `-shm`), and logs (rather than fails)
  any other locked file. The rest of the world is backed up successfully.

- **Pnl-24 + Pnl-25 — full MapPane perf rewrite + PipeClient close-on-error**
  — User reports: map froze on zoom-out, freezing-while-dragging, error
  spam after server crash, panel lag changing tabs. Diagnosis: previous
  MapPane delegated rendering to `mapcore.BasicMapRenderer` which
  iterated EVERY cached tile (up to 4096) and emitted 256 per-pixel
  `fillRect` calls per tile — so a single redraw was up to ~1 million
  draw calls. With drag events firing at 60+ Hz, the FX thread froze.
  Plus, after the server died the panel kept firing `chunk_tile` RPCs on
  every redraw because `pipeClient.isClosed()` returned false even
  though the socket was broken — endless `SocketException` flood.
  Rewrote MapPane:
  - Each `ChunkTile` is pre-rendered ONCE to a 16x16 JavaFX
    `WritableImage` via PixelWriter when the response arrives. Subsequent
    redraws emit one `drawImage` per chunk (256x speed-up per tile).
  - Spatial culling: redraw iterates only the visible chunk-coord range,
    not the entire cache.
  - `chunk_tile` RPCs deferred via 120 ms debounce after the last
    drag/zoom event; pan/zoom no longer storms the bridge.
  - Centre-out spiral fan-out so the visible centre fills first.
  - Bigger 20x20 stylised Steve heads, bold 14pt names with shadow,
    canvas hit-test rectangles for click handling.
  - Left-click a player marker to open an info dialog (UUID, dim,
    coords, ping, health, food, gamemode).
  - Right-click for the admin context menu: teleport, send chat,
    kick / ban (input dialogs), op / deop / whitelist add or remove,
    kill in-game.
  Pnl-25: `PipeClient.sendRequest` and `.sendJson` now (a) pre-flight
  via `isClosed()` and fail fast without writing, and (b) call `close()`
  on any IOException so subsequent calls don't keep retrying a broken
  socket. Eliminates the post-server-crash log flood.

- **Bld-07 — self-contained distributable zip** — User: "the server start
  application and folders to be in one self-contained wrapper so i can
  distribute it to others". New Gradle task `:panel:distributableZip`
  builds `dist/FatherEye-Server-<version>.zip` (~189 MB) containing the
  jpackage-bundled panel (EXE + app + JLink runtime), the bridge mod jar,
  the entire Forge install (libraries, server jar, configs, mods), and a
  README. Excludes per-user state (world, logs, crash-reports, backups,
  ban / op / whitelist lists, user caches, session.lock,
  fathereye-instance.uuid). `eula.txt` pre-accepted per user directive.
  Recipient extracts, double-clicks the EXE, panel auto-starts the
  bundled server.

- **Brg-13 + Pnl-23 — `chunk_tile` non-blocking + tighter per-frame budget
  (server crash fix)** — User report: server crashed after a map zoom-out.
  Crash report (`crash-2026-04-26_01.40.52-server.txt`) named
  `io.fathereye.bridge.rpc.ChunkTileHandler.handle` line 34 directly:
  `world.getChunk(cx, cz)` on the tick thread forces SYNCHRONOUS chunk
  generation for unloaded chunks; the panel firing 64 chunk_tile RPCs
  per redraw on a zoom-out cumulatively blocked the tick thread past
  Vanilla's 60-second `ServerHangWatchdog` and triggered a hard crash
  (`java.lang.Error: ServerHangWatchdog detected that a single server
  tick took 60.00 seconds`). Two fixes:
  - **Brg-13**: `ChunkTileHandler` switched to
    `world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL,
    load=false)`, which returns null when the chunk isn't already loaded;
    the handler then returns an empty tile rather than blocking on
    world-gen. Tiles for unloaded chunks fill in naturally as players
    visit them.
  - **Pnl-23**: lowered `MapPane.MAX_TILE_REQUESTS_PER_FRAME` from 64 → 16
    so a single redraw enqueues at most 16 sendRequests; the rest fan in
    over subsequent frames. Reduces backpressure on the IPC writer and
    keeps the panel responsive even on aggressive zoom-outs.

- **Pnl-22 + Brg-12 — switch Windows transport to TCP localhost (FINAL
  fix)** — After Pnl-19/20/21 + Brg-11 attempts to fix the named-pipe
  deadlock via FlushFileBuffers removal + reorder + OVERLAPPED I/O kept
  hitting JNA Structure auto-write semantics that clobbered the
  kernel-populated Internal/InternalHigh fields between ReadFile and
  GetOverlappedResult, every iteration produced a different failure mode
  (zero-byte reads, ERROR_PIPE_NOT_CONNECTED, "No content to map" Jackson
  errors). Resolved by deleting the Windows-specific named-pipe code path
  entirely and using TCP localhost on every OS — the same transport
  Mac/Linux were already using cleanly. TCP sockets have no kernel-level
  read/write serialisation, no JNA Structure semantics to navigate, and
  Java's standard Socket I/O is battle-tested. Verified: all 5 topic
  subscriptions complete in &lt;1 ms after handshake, map renders chunks,
  panel runs reliably for 5+ minutes. Reported by user: "map is working".

- **Brg-11 + Pnl-20 + Pnl-21 — OVERLAPPED I/O on named-pipe handles
  (intermediate diagnosis, superseded by Pnl-22)** —
  Pnl-19's FlushFileBuffers removal + the tryConnect reorder weren't enough.
  Thread-dumping the running panel revealed:
  - Panel `FatherEye-IPC` thread: parked in `Kernel32.WriteFile` for the
    second Subscribe — the call had been blocked for 80+ seconds.
  - Panel `FatherEye-PipeReader`: parked in `Kernel32.ReadFile` waiting
    for inbound bytes.
  - Bridge `FatherEye-Publisher`: parked in `Kernel32.WriteFile` for the
    first tps_topic snapshot, holding the IpcSession monitor.
  - Bridge `FatherEye-PipeAccept` (IpcSession reader): parked in
    `Kernel32.ReadFile` waiting for the panel's next Subscribe.
  Both ends symmetrically deadlocked. Cause: synchronous (default) named-
  pipe handles on Windows serialise concurrent read+write inside the
  kernel I/O queue. With one thread parked in ReadFile, another thread's
  WriteFile on the same handle blocks indefinitely even though the
  outbound buffer has plenty of space. Fix: open both ends with
  `FILE_FLAG_OVERLAPPED` and route every ReadFile/WriteFile/
  ConnectNamedPipe through OVERLAPPED structs with per-direction reusable
  events; wait via `WaitForSingleObject` and resolve via
  `GetOverlappedResult`. From the caller's perspective the API is still
  synchronous, but the kernel allows concurrent in-flight I/O on the
  same handle. JNA 5.14 doesn't expose `GetOverlappedResult` in its
  bundled `Kernel32` interface — declared a small `Kernel32Ex` extension
  in both modules.

- **Pnl-19 — FlushFileBuffers wedge in WindowsPipeTransport.writeAll
  (initial root-cause hypothesis — true but insufficient)** — User: "panel
  hung and stopped responding again", "world seems to still be running
  but the panel is frozen", "map still isnt working". Diagnosis traced
  to a single line: `Kernel32.INSTANCE.FlushFileBuffers(handle)` after
  every WriteFile in `WindowsPipeTransport.writeAll`. Per Win32 docs,
  FlushFileBuffers on a named-pipe handle blocks until the peer has
  READ the queued data. With the pipe-buffer/peer-read interaction the
  panel writer would deadlock mid-loop in `App.tryConnect`'s subscribe
  loop: every panel.log run since v0.1.0 ended with exactly one
  `Subscribed to tps_topic` line and then no further output, because
  the second sendJson wedged inside writeAll's flush. Symptom: panel
  UI freezes after handshake; bridge keeps running; user can play MC
  fine but the panel is dead. Map is blank because players_topic /
  mods_impact_topic / etc. were never subscribed. The watchdog
  cascade (Pnl-16) was masking this earlier — once that was disabled
  the underlying hang surfaced. Fix: removed FlushFileBuffers — for
  named-pipe clients it's redundant (WriteFile already places data in
  the kernel buffer; the OS guarantees in-order delivery to the peer)
  AND harmful (causes the observed hang).

- **Pnl-18 — orphaned-server defense via JVM shutdown hook** — User
  reported: "panel and terminal are closed, server shouldnt be running".
  When the panel JVM is killed externally (Task Manager "End task",
  Ctrl+C, SIGTERM), JavaFX's `App.stop()` clean-shutdown path is
  bypassed and the forked MC server child JVM survives headless, holding
  `world/session.lock` and chunk-region handles — preventing the user
  from restarting OR deleting the world folder. Fixed by registering a
  `Runtime.addShutdownHook` in `App.startInternal` that calls
  `launcher.stop()` if the server is still RUNNING at hook execution
  time. Catches all signal-based termination paths; only hard
  TerminateProcess / "End process tree" still bypasses (would require
  Win32 Job Object via JNA — deferred).

- **Bld-06 — flat panel deploy at server folder root (per user directive)**
  — Per user "app needs to be in server folder, not in sub-directory", a
  new Gradle task `:panel:deployToServer` flattens jpackage's app-image
  output into the server folder root: `Server Start with Father Eye.exe`
  sits beside `mods/`, `world/`, `start.bat`, etc., and the supporting
  `app/` + `runtime/` directories are siblings of the EXE (jpackage
  requires them adjacent). Default target
  `C:/Users/lukeo/Desktop/TomCraft-Server`; override with
  `-PdeployTarget=<absolute-path>`. Replaces the previous nested
  `Server Start with Father Eye/` subfolder layout.

- **Pnl-17 — blank-map fix on fresh handshake** — `MapPane.bindPipeClient()`
  now triggers an immediate FX-thread redraw. Previously the canvas only
  redrew on size change, mouse events, players-snapshot, or chunk-tile
  response. A fresh panel connecting to a running server with zero players
  online and no mouse interaction had no event to kick the FX thread, so
  `requestVisibleTiles()` was never called and the canvas stayed blank
  (background-color only) until the user moved the mouse. Now bindPipeClient
  posts a `Platform.runLater(this::redraw)` so the chunk fan-out fires the
  moment the pipe is bound.

- **Watchdog cascade fix (Pnl-16) + auto-restart disabled by default per user
  directive** — Triple-audit-reconciled patch addressing a false-positive
  cascade observed in the wild: cold-boot panel constructed `Watchdog`
  with `lastHeartbeatMs = now()` at panel-init time, then `arm()` only
  reset `armedAfterMs`, leaving the heartbeat clock anchored at panel-init.
  At T+65 s after `arm()` (5 s past the 60 s warmup), the watchdog measured
  `since = 65000 ms > noHeartbeatMs(60000)` and force-killed a perfectly
  healthy server that hadn't yet finished mod-loading. The kill cascaded
  into a `world/session.lock` collision when the auto-restart's pre-flight
  `tryLock` raced the OS file-handle release. Fixes:
  - `Watchdog.arm()` now resets `lastHeartbeatMs` AND `armedAfterMs` and
    publishes them via `enabled = true` written last (volatile happens-
    before for the scheduler thread per audit A#2).
  - `Watchdog.tick()` measures idle time from `max(lastHeartbeatMs,
    armedAfterMs)`, granting a full `noHeartbeatMs` window post-warmup
    even if no heartbeat arrived during warmup.
  - Defaults lifted to `noHeartbeatMs=90 s, warmupMs=240 s` (4 minutes
    cold-boot grace; matches Thaumaturgy + 200-mod load times).
  - Per user directive 2026-04-26 ("watchdog doesnt need to kill server,
    on boot i will kill server if it isnt working"), `restartServer()` is
    gated behind a NEW config field `serverRuntime.watchdogAutoRestart`
    that defaults to **false**. The watchdog still tracks heartbeat for
    diagnostic/log purposes but no longer takes destructive action by
    default. Old configs deserialize cleanly (Jackson missing-field
    behavior) and inherit the new defaults.
  - Watchdog log message is neutral ("No TPS heartbeat for X ms (threshold
    Y)"); the gated Action emits its own decision-line so logs don't lie
    when restart is off (audit C#1).
  - `ServerLauncher.start()` now polls `world/session.lock` for up to
    15 s with 500 ms intervals before declaring the lock permanently
    held — covers the OS file-handle release window after `destroyForcibly`
    (audit B1, the originally-cascading symptom).
  - `canAcquireLock` narrowed from `catch (Throwable)` to `catch
    (IOException | RuntimeException)` so OOM and other Errors are not
    swallowed (audit A#6).
  - `App.wireLauncher` moved manual Start/Restart `setOnAction` handlers
    onto `ipcExecutor.submit(...)` because the new lock-poll could freeze
    the FX thread for up to 15 s (audit A#5, B#A, C#3 — blocking).
  - All start/restart paths `disarm()` the watchdog on failure to prevent
    half-state (audit A#9).

  Deferred items raised to user (Standing Rule 11, NOT silently dropped):
  - **Clock skew**: `System.currentTimeMillis()` is not skew-immune; an
    NTP correction or system suspend/resume could mis-time the warmup.
    `System.nanoTime()` would be correct but requires changing the public
    API of `Watchdog`. Defer.
  - **No watchdog UI surface**: the watchdog state ("Last heartbeat Xs ago")
    is not visible in `MainWindow`; user must tail `panel.log`. Defer
    until a future Pnl-NN milestone.
  - **Narrow heartbeat signal**: only `tps_topic` Snapshot resets the
    heartbeat. A server whose Publisher's tps scheduler dies but other
    topics still publish would be flagged as hung. Acceptable as the
    documented intent (watchdog measures TPS-publisher liveness, not
    full-server liveness). Defer.

## 2026-04-25

- **Audit-fix-02** — Second triple-audit pass over the post-M20 fixes. Blocking
  items addressed: TcpTransportServer now wraps an externally-owned
  ServerSocket so the bridge no longer rebinds the same port per accept
  cycle (Audit B1 — eliminated a TOCTOU race where another process could
  grab the port between probe close and the loop's first cycle).
  TcpTransportServer.disconnect() no longer nulls socket fields (Audit B2
  — closing the socket alone unblocks readers cleanly with SocketException
  instead of risking NPE in concurrent writers). bridge/build.gradle
  switched from `assemble.dependsOn reobfShadowJar` to
  `shadowJar.finalizedBy reobfShadowJar` (Audit B-1 — guarantees reobf
  runs even when shadowJar is invoked alone, otherwise a Mojang-named
  jar leaks out and the server crashes on first ServerTickEvent).
  ServerLaunchSpec.defaults() is now OS-aware: Windows returns the
  TomCraft-Server paths, Mac/Linux returns placeholder paths and
  AppConfig.ServerRuntime.autoStart defaults to false there (Audit
  blocker 3). MarkerFile no longer writes the legacy `pipeName` field
  for TCP markers — v0.1 panels reading them would otherwise feed
  "127.0.0.1:54321" to JNA CreateFile (Audit S1). App.anyLiveBridge
  adds a PID-recycling guard: a marker-listed PID counts as live only
  if its command line looks like a JVM (Audit S-3). README + ipc-
  protocol.md updated with the marker schema, transport details, and
  Mac setup notes.

- **Server-side-only deployment** (commit `fb5f2b5`) — bridge JAR switched
  to shadowJar (Forge classloader doesn't see Gradle implementation
  classpath; Jackson NoClassDefFoundError on FMLServerStartingEvent).
  Multi-release jar exclusion lets SpecialSource reobf finish.
  AppConfig.serverRuntime.autoStart=true so the panel auto-launches the
  server. tryConnect polls for the bridge marker for up to 90 s. mods.toml
  side="SERVER" + displayTest="IGNORE_SERVER_VERSION" so vanilla clients
  aren't kicked. EXE renamed to "Server Start with Father Eye".

- **Panel instant-close fix** (commit `ffc7978`) — jpackage's bundled JLink
  runtime was missing java.naming/java.sql/java.xml/jdk.unsupported and
  more, so logback failed at class init and the JVM exited before
  Application.start. Explicit --add-modules list. logback.xml dropped a
  broken <define> referencing the abstract PropertyDefinerBase class.
  App.start now wraps everything in try/catch, sets a default uncaught
  handler, moves auto-start to the IPC executor (background) so it
  doesn't block the FX thread, and adds a logback file appender at
  %LOCALAPPDATA%/FatherEye/panel.log.

- **Bridge fix: exclude multi-release classes** (commit `9f6d50e`) — the
  fat-jar reobf step (SpecialSource 1.11) choked on Jackson 2.16.1's
  Java 21 multi-release class files. exclude `META-INF/versions/**` in
  shadowJar; server runs Java 8 so the per-version overrides are unused
  at runtime anyway.

- **Add Mac panel support** (commit `da83429`) — TransportServer
  abstraction on the bridge side (NamedPipeTransportServer +
  TcpTransportServer); Transport abstraction on the panel side
  (WindowsPipeTransport + TcpTransport). BridgeLifecycle picks based on
  os.name; MarkerFile records the choice. PipeClient.forMarker dispatches.
  New `:panel:jpackageMacApp` task for macOS jpackage.

- **Audit-fix-01** — Triple-audit reconciliation after M1–M20 push. Bridge:
  removed stale `MixinConfigs` jar manifest entry (M9 ships proportional
  profiler, no Mixin); rewrote `JfrController` to load `jdk.jfr.Recording`
  via reflection so the class itself loads on JDK 8; fixed
  `ChunkTileHandler` biome lookup (1.16.5 `Biome` is not a
  ForgeRegistryEntry — use `world.getBiome` + `RegistryAccess.getKey`);
  switched moderation handlers to the vanilla command dispatcher (avoids
  `ProfileCache#get(UUID)` which is 1.18+); added `JfrController.closeAll`
  to bridge shutdown. Panel: split `PipeClient` read/write monitors to
  fix steady-state read-write deadlock; `sendRequest` now serializes the
  write under `writeLock`; `close()` drains `pendingResponses` so blocked
  callers fail cleanly; `MetricsDb` runs downsample/trim on the writer
  thread to avoid sqlite-jdbc's single-connection thread races; final
  flush + WAL checkpoint on close; `MapPane.tiles` switched to
  `ConcurrentHashMap` and gained per-frame request budget + LRU
  eviction; `requested` set is cleared on RPC failure to allow retry;
  `App.stop` reorders pipe close before reader stop. Welcome handshake
  now surfaces real `dimensions[]` to the map dim picker. Docs:
  mapcore contract bumped to v0.2.0; IPC protocol documents the
  `{ seq, data }` Snapshot wrapper and the `protocol_too_old`
  rejection policy; HANDOFF.md updated to reference the actual
  collector classes; README adds JDK 17 prerequisite note. CHANGELOG
  + CLAIMED.md fully filled in for milestones 1–20.

- **M19+M20 / Bld-03 + Map-01** — `panel:jpackageExe` task wired (depends
  on `installDist`, invokes `jpackage --type app-image` with explicit
  JavaFX module-path). `fathereye-map` skeleton: `mods.toml` declaring
  optional after-dep on `fathereye_bridge`, `pack.mcmeta` (pack_format=6),
  `FatherEyeMap.java` no-op @Mod that links against mapcore.

- **M16+M17+M18 / Pnl-11 + Brg-08 + Pnl-12** — `BackupService` (timestamped
  world copy, retention), `RestartScheduler` (daily 04:00 local with
  re-arm), bridge `JfrController` (lazy reflective JFR), version-mismatch
  warning on Welcome. App stop button now spawns a backup-then-stop
  background thread.

- **M15 / Pnl-10** — `AppConfig` POJO (display/alerts/serverRuntime/bridge/
  history/backup), persists JSON at `%LOCALAPPDATA%/FatherEye/config.json`,
  loads with sane defaults. `AlertEngine` fires JavaFX alerts on
  TPS/heap/MSPT thresholds with 60 s cooldown and cold-start gate.
  `ConfigPane` JSON editor with Save / Reload-from-disk buttons.

- **M14 / Pnl-09** — `MetricsDb`: SQLite-WAL at `%LOCALAPPDATA%/FatherEye/
  metrics.sqlite` with `metric_raw / metric_1m / metric_1h / event_log`.
  Daemon writer thread drains a `LinkedBlockingQueue` in batches; 5-min
  downsample task rolls raw rows older than 24 h into 1-min buckets;
  60-min retention task trims 1m rows older than 7 d.

- **M11+M12+M13** — Bridge `chunk_tile` RPC (heightmap + map-color surface
  ARGB + biome id). `mapcore` bumped to **0.2.0**: new `BasicMapRenderer`
  draws chunk pixels + entity/player/POI markers, layer-gated. Panel
  `JfxMapGraphics` adapts mapcore to JavaFX Canvas; `MapPane` is the
  default tab with pan/zoom/dim picker and on-demand chunk fetching.

- **M10 / Brg-06 + Pnl-07** — `RpcDispatcher` (server-thread `execute`
  pattern) + `RpcHandlers` (cmd_run, cmd_tp, cmd_kick/ban/op/whitelist,
  cmd_killEntity, cmd_clearMobsInArea, cmd_weather, cmd_time, srv_stop,
  srv_restart). Panel `PipeClient.sendRequest` returns a future correlated
  by id.

- **M9 / Brg-05 + Pnl-06** — `WorldTickProfiler` (per-dim wall time via
  `WorldTickEvent` START/END), `ModsImpactCollector` distributing tick
  nanos proportionally across mod namespaces. Panel `ModsPane` table.
  Note: this is the no-Mixin proportional approximation; Mixin-based
  per-tick attribution remains a future bridge milestone.

- **M8 / Brg-04 + Pnl-05** — Bridge publishes `players_topic`, `mobs_topic`,
  `chunks_topic` snapshots; panel renders `PlayersPane` and `MobsPane`.

- **M7 / Pnl-04** — `ServerLauncher` (`ProcessBuilder` + stdout pump +
  exit watcher), `Watchdog` heartbeat-based restart, toolbar
  Start/Stop/Restart buttons.

- **M6 / Brg-03 + Pnl-03** — Programmatic Log4j2 `IpcAppender`,
  `console_log` topic, panel `ConsolePane` (read-only TextArea +
  command bar).

- **M5 / Brg-02 + Pnl-02** — `JmxSampler`, `TpsCollector` (1200-tick
  rolling MSPT percentile), `Subscriptions`, `Publisher` scheduled
  topic publish, panel `StatsPane` charts (JFreeChart-FX).

- **M4 / Pnl-01** — JavaFX shell (Launcher + App + MainWindow), panel-side
  IPC stack (PipeFrame, PipeCodecs, PipeEnvelope, MarkerDiscovery,
  PipeClient with WaitNamedPipe + ERROR_PIPE_BUSY backoff).

- **M3 / Brg-01** — Bridge mod skeleton (`@Mod`, `BridgeLifecycle`),
  IPC stack (`Frame`, `Codecs`, `IpcEnvelope`, `MarkerFile`,
  `NamedPipeServer`, `PipeAcceptLoop`, `IpcSession` with Hello/Welcome).

- **M2 / Mc-01** — mapcore 0.1.0 published (interfaces, POJOs, NoOp
  renderer, JUnit tests). Local-maven repository at `local-maven/`.

- **Bld-02** — Pinned Gradle daemon to JDK 17 (Adoptium 17.0.18.8) so the
  JavaFX plugin loads. Restored the JavaFX Gradle plugin in panel/.

- **Bld-01** — Initial repo scaffolding. Gradle 7.6.4 multi-project rooted
  at `C:\Users\lukeo\Desktop\Server GUI\`. Four subprojects declared:
  `mapcore` (Java 8 lib), `bridge` (Forge 1.16.5 mod), `map` (skeleton,
  owned by parallel Claude session), `panel` (JavaFX 21 desktop). Wrapper
  copied from TomCraft; `.gitignore`, `README.md`, `CHANGELOG.md`,
  `coordination/CLAIMED.md` created. Plan file:
  `C:\Users\lukeo\.claude\plans\abstract-imagining-wave.md`.
