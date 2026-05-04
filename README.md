# Father Eye Mac Version

Server administration suite for a Minecraft 1.16.5 Forge server, **macOS edition**.

This repo is a Mac-focused fork of [hypnofrenzy76/father-eye](https://github.com/hypnofrenzy76/father-eye) (the Windows reference implementation). The goal is to make the JavaFX panel run cleanly on **macOS High Sierra 10.13.6+**, with the Mac as the operator's monitoring station while the Forge server (with the bridge mod) keeps running on whatever host the user has — typically Windows or Linux.

## Status

- **mapcore** — unchanged from upstream. Pure Java 8 library; cross-platform by construction.
- **bridge** — unchanged from upstream for now. Ships as a Forge mod that goes into `<server>/mods/`. Future: relax the marker-file location so cross-machine (Mac panel ↔ remote bridge) is a first-class config option.
- **map** — unchanged from upstream.
- **panel** — the active port target. Initial fork point: upstream **0.2.9** (commit `9942794`). Mac-specific divergence starts here.
- **webaddon** — Mac-fork addition. Optional HTTPS panel that runs inside the JavaFX panel's JVM and exposes login-gated browser access to every panel feature (start/stop/restart, console, players, mobs, mods, stats, map, config). Recommended deployment is **Tailscale** for zero-port-forward WAN access. See [webaddon/README.md](webaddon/README.md).

## Prerequisites

- **macOS 10.13.6 (High Sierra)** or later. Tested target: 21.5" iMac Mid 2011 with 32 GB RAM and AMD HD 6750M, running High Sierra.
- **JDK 17** for the Gradle daemon and the panel runtime. Pinned to **JavaFX 17.0.12** (JavaFX 21 hard-blocks any macOS earlier than 11 / Big Sur). Recommended:
  - Azul Zulu 17 (Intel x64) from <https://www.azul.com/downloads/?version=java-17-lts&os=macos>. Often runs on 10.13.6 even though the docs list 10.14 as minimum.
  - Eclipse Temurin 17 from <https://adoptium.net/> (officially 10.12+ minimum).
- **JDK 8** for the Forge server runtime (Forge 1.16.5 requirement). Eclipse Temurin 8 from <https://adoptium.net/temurin/releases/?version=8&os=mac&arch=x64&package=jdk>. The Setup wizard guides you through this install.
- **Gradle daemon must run on JDK 17.** Either set `JAVA_HOME` to your JDK 17 install before running `./gradlew`, or uncomment one of the `org.gradle.java.home` lines in `gradle.properties` to pin it. The JavaFX gradle plugin requires JDK 11+ to load.

## Hardware-specific tuning baked into this fork

- **JavaFX 17** (NOT 21). Pinned to 17.0.12 with full Intel x64 mac classifier set on Maven Central.
- **`prism.order=es2,sw`** — forces JavaFX to use the OpenGL pipeline first (the AMD HD 6750M is non-Metal). Software fallback if the OpenGL handshake fails.
- **`prism.maxvram=256m` + `prism.targetvram=192m` + `prism.disableRegionCaching=true`** — caps JavaFX's GPU-side resource pool so it triggers texture eviction before hitting the HD 6750M's 512 MB VRAM ceiling.
- **`TILE_CACHE_LIMIT = 1024`** — tightened from upstream's 4096 for the same reason.
- **Coalesced redraw via AnimationTimer** — replaces upstream's per-tile `Platform.runLater(redraw)` flood that saturated FX-thread on Sandy Bridge.
- **`LSMinimumSystemVersion = 10.13.0`** patched into Info.plist via `plutil -insert` (with `-replace` fallback) so Finder accepts the .app on High Sierra.

## Build

The default build target on this fork is the Mac `.app`:

```
./gradlew :mapcore:publish        # publish mapcore JAR to local-maven/
./gradlew :panel:jpackageMacApp   # build "Father Eye.app" via jpackage
./gradlew :panel:jpackageMacDmg   # optional: package as a .dmg installer
```

Output appears under `panel/build/jpackage-macapp/` (or `.../jpackage-macdmg/`).

To also build the Forge mods (only useful if you're iterating on bridge / map yourself rather than using upstream's prebuilt mod):

```
./gradlew :bridge:shadowJar       # builds fathereye-bridge-<version>.jar (Mojang -> SRG reobf'd)
./gradlew :map:build              # placeholder until the in-game map ships
```

The Windows-only tasks from upstream (`:panel:jpackageExe`, `:panel:singleFileExe`, `:panel:deployToServer`, `:panel:distributablePrep`, `:panel:distributableZip`) have been removed from `panel/build.gradle`. The Mac targets are the only supported build outputs in this fork.

A separate `:setup:jpackageMacApp` task builds the **Father Eye Setup.app** first-run wizard, which downloads Forge 1.16.5-36.2.39, drops the Father Eye bridge mod into `<serverDir>/mods/`, pre-accepts the EULA, and writes a panel config that points at the new server install.

## gradle.properties

The upstream repo pins the Gradle daemon JVM to a Windows path. Edit `gradle.properties` for your machine:

```
org.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
org.gradle.jvmargs=-Xmx2g
```

(2 GB heap is a good Mac default. Crank it to 6 GB if you have RAM to spare.)

## Cross-machine connection

For the typical Mac-as-monitoring-station setup, the bridge runs on the Minecraft server host (e.g. a Windows box on the LAN). The Mac panel needs to find the bridge across machines — this is a known gap and a first-class roadmap item for this fork. Until then:

1. Configure the bridge to bind on `0.0.0.0` instead of `127.0.0.1` (config option in development).
2. Manually copy the bridge's marker JSON from the server's `%LOCALAPPDATA%/FatherEye/bridges/` to the Mac's `~/Library/Application Support/FatherEye/bridges/`, editing the `address` field to the server's LAN IP.

## Differences from upstream

This fork tracks upstream cherry-picks where they apply, but diverges in:

- **Marker file location**: macOS uses `~/Library/Application Support/FatherEye/bridges/` (upstream uses `%LOCALAPPDATA%`).
- **Build pipeline**: Mac-only EXE / DMG via jpackage. No C# launcher (Windows-only).
- **Watchdog / launcher**: the Win32 Job Object lifecycle (Pnl-51 in upstream) is a no-op on Mac. A future commit will replace it with a POSIX equivalent or a conscious "remote bridge only, no local launch" mode.
- **Default target**: monitoring a remote Forge server, not launching a local one.
- **Web addon**: a separate `webaddon` module provides a TLS-secured HTML panel reachable from any browser on your Tailscale tailnet (or any other deployment of your choice). Disabled by default; flip `webAddon.enabled` in `config.json` to turn it on. Single-user authentication, BCrypt-hashed credentials, brute-force throttle, security headers, full feature parity with the desktop panel.

## See also

- Upstream: <https://github.com/hypnofrenzy76/father-eye>
- Upstream CHANGELOG: <https://github.com/hypnofrenzy76/father-eye/blob/main/CHANGELOG.md>
