# 2026-06-12, Doc-04: mapcore doc version pins caught up to 0.3.3-mac.1

## What changed
- `docs/mapcore-api-contract.md`: title, pin line, and the `MapCore` table row updated from 0.3.1-mac.1 to 0.3.3-mac.1; a dated 2026-06-12 note block added stating the API surface is unchanged and `CONTRACT_MAJOR=0` / `CONTRACT_MINOR=2` are deliberately not bumped.
- `map/docs/HANDOFF.md` line 25: pinned mapcore version updated to 0.3.3-mac.1 with an appended dated note.
- `mapcore` 0.3.3-mac.1 published to the committed `local-maven/` repository (`./gradlew :mapcore:publish`): new artifact directory plus updated `maven-metadata.xml` and its four checksum files. This makes the new doc pin actually resolvable, per the HANDOFF bump workflow.

## Why
Bld-11 bumped every artifact to 0.3.3-mac.1 but deferred these two docs because they sit against the parallel session's `map/**` claim and the published local-maven artifact. The maintainer first asked to delete HANDOFF.md, was shown the claim conflict and the three live references to it (`FatherEyeMap.java:11`, `mods.toml:14`, `map/build.gradle:2`), and decided instead to keep HANDOFF.md and update the stale version references. The doc-only edit inside `map/**` was explicitly authorized.

## Notable facts for future sessions
- 0.3.2-mac.1 was a real released version but was never published to `local-maven/`; skipping it there is safe because every subproject consumes mapcore via `project(':mapcore')`, not the maven coordinate. The local-maven copy exists only so the parallel `map/` session could build standalone.
- `.gitignore` carries a `!local-maven/**` negation, so published artifact jars commit despite broader jar/build ignores.
- mapcore tests pass with the 0.3.3-mac.1 assertion (`MapCoreTest`).
- Triple audit (3 parallel agents) all PASS; one agent needed a re-dispatch after returning empty output. Only flag: stale gitignored build temp file `mapcore/build/tmp/publishMapcorePublicationToLocalMavenRepository/module-maven-metadata.xml`, regenerated on each publish, no action.
- Client distribution unaffected: changes are docs plus a build-time maven artifact; the bridge jar on the server (`fathereye-bridge-0.3.3-mac.1.jar`) was already current.
