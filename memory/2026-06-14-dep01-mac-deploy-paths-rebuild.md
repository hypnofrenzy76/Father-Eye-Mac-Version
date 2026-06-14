# Dep-01: rebuild all outputs from commit abe1bb8 and deploy to new macOS paths

## Why
Commit `abe1bb8` (Bkp-02/Map-02: region-selective rollback + disk-backed pre-gen
chunks) changed bridge, panel, and webportal source plus the backup/rollback
scripts, but every built artifact on disk predated that commit:

- `bridge/build/libs/*.jar`: 12:02 (commit was 12:11)
- `panel`/`webportal` `build/libs` jars: 12:09
- `dist/Father Eye.app` and the copy in `/Applications`: Jun 12 (very stale)
- `~/Desktop/Server/mods/fathereye-bridge-0.3.3-mac.1.jar`: Jun 12 17:57

So the running server and the installed apps did not contain the committed
feature work. A full rebuild and redeploy was required.

## Deploy-path change (standing)
The Windows deploy targets in the old standing instructions
(`C:\Users\Luke\Desktop\Server\mods\`) were only ever used for Chunky pre-gen on
the Windows box and are no longer needed. The canonical macOS deploy targets are
now:

- **Forge bridge mod jar** -> `~/Desktop/Server/mods/fathereye-bridge-0.3.3-mac.1.jar`
  (replace the existing jar of the same name)
- **Desktop apps** -> `/Applications/Father Eye.app` and
  `/Applications/Father Eye Setup.app` (remove old bundle, copy fresh with
  `cp -R`)

## Build procedure (JDK 17 for the Gradle daemon, JDK 8 toolchain for Forge)
Run with `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`:

1. `./gradlew :mapcore:publish` (publishes mapcore to `local-maven/`)
2. `./gradlew :bridge:shadowJar` (produces the Mojang->SRG reobf'd Forge jar)
3. `./gradlew :panel:jpackageMacApp` (writes `dist/Father Eye.app`)
4. `./gradlew :setup:jpackageMacApp` (embeds the fresh bridge as
   `fathereye-bridge-bundle.jar`, writes `dist/Father Eye Setup.app`)
5. `./gradlew :webportal:clean :webportal:installDist` (clean rebuild of the
   portal jar to rule out staleness)

## Verification done
- All rebuilt artifacts timestamp AFTER the commit (12:11:35):
  bridge jar 12:23:46, webportal jar 12:24:24, `Father Eye.app` 12:23:14,
  `Father Eye Setup.app` 12:23:49.
- Deployed bridge jar in `mods/` grew 6192377 -> 6208465 bytes (new build).
- `/Applications/Father Eye.app` contains the fresh
  `fathereye-panel-0.3.3-mac.1.jar` (309779 bytes, 12:24:47).
- `/Applications/Father Eye Setup.app` jar embeds
  `fathereye-bridge-bundle.jar` at 6208465 bytes, byte-identical to the jar
  deployed to `mods/`.

## Note
No source code changed in this task; it was a build + deploy + path-policy
update only. The server must be restarted to load the new bridge jar.
