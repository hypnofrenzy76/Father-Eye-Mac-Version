# Father Eye `map` — Handoff to the Parallel Claude Session

This subproject is the **in-game admin map GUI** for Minecraft 1.16.5 Forge. It is owned by a separate Claude session that the user runs alongside the Father Eye desktop suite.

## Why two sessions

The Father Eye panel (JavaFX desktop app) and the in-game admin map both render the SAME map, using the SAME visual code. That shared code lives in `mapcore`. Each surface (in-game vs desktop) supplies its own backend implementation of `MapGraphics` (Minecraft's `RenderSystem` here, JavaFX `Canvas` there). The renderer logic in `mapcore` itself is identical — that is what makes the two views pixel-identical.

This subproject implements the in-game side: the Forge `Screen` that opens with `/fathereye-map open`, the `MapGraphics` adapter over `MatrixStack` + `Tessellator` + `RenderSystem`, the keybind, the op-gated command.

## What the parallel session is responsible for

- `src/main/java/io/fathereye/map/**` — all Java code.
- `src/main/resources/META-INF/mods.toml` — mod metadata.
- `src/main/resources/assets/fathereye_map/**` — any in-game-only assets (lang files, keybind config). Shared visual assets (map textures, font atlas) come from `mapcore` via classpath, NOT here.

## What the Father Eye session is responsible for (do not duplicate)

- `mapcore/**` — the renderer, POJOs, and bundled visual assets.
- `bridge/**` — the named-pipe API and per-mod profiler. The map mod can call into bridge as an optional in-process dependency (see `mods.toml` snippet below) to read live data without going over IPC.
- `panel/**` — the desktop app.

## The mapcore contract

- Pinned version: **`io.fathereye:mapcore:0.2.0`** (exact, not range).
- Public package: `io.fathereye.mapcore.api.*`. Anything outside is internal and may change.
- Full API reference: `docs/mapcore-api-contract.md` at the root of the Server GUI repo.
- Bumps: any signature change → major version. New methods on a NEW interface → minor. Both sessions update their pin together.

The `build.gradle` here already pulls mapcore via `implementation project(':mapcore')` (uses the local Gradle project) and via `localMaven` (the `local-maven/` directory committed at the repo root) so the parallel session can also build standalone if it ever decouples.

> **Build order note**: `map/build.gradle` declares `compileOnly project(':bridge')` for the optional in-process bridge API. Run `./gradlew :bridge:build` at least once before `./gradlew :map:build` so the bridge jar is on the classpath; otherwise compilation fails with `package io.fathereye.bridge.api does not exist`.

## Implementing `MapGraphics` over Minecraft's RenderSystem

```java
public final class McMapGraphics implements MapGraphics {
    private final MatrixStack stack;
    private final FontRenderer font;
    private final Deque<float[]> transforms = new ArrayDeque<>();

    public McMapGraphics(MatrixStack stack, FontRenderer font) {
        this.stack = stack;
        this.font = font;
    }

    @Override public void setClip(int x, int y, int w, int h) {
        RenderSystem.enableScissor(x, y, w, h); // remember to flip Y for OpenGL coords
    }
    @Override public void clearClip() { RenderSystem.disableScissor(); }

    @Override public void fillRect(int x, int y, int w, int h, int argb) {
        AbstractGui.fill(stack, x, y, x + w, y + h, argb);
    }

    @Override public void drawTexture(MapTexture t, int dx, int dy, int dw, int dh,
                                       float u0, float v0, float u1, float v1, int tint) {
        // Bind via TextureManager using t.identifier() (loaded ahead of time
        // through MapTextureLoader); blit with Tessellator.
    }

    @Override public void drawText(String s, int x, int y, int argb, boolean shadow) {
        if (shadow) font.drawShadow(stack, s, x, y, argb);
        else        font.draw(stack, s, x, y, argb);
    }

    @Override public void pushTransform(float tx, float ty, float scale) {
        stack.pushPose();
        stack.translate(tx, ty, 0);
        stack.scale(scale, scale, 1);
    }
    @Override public void popTransform() { stack.popPose(); }
    @Override public void flush() { /* RenderSystem flushes per draw; no-op */ }
}
```

The exact texture-loading path (atlas vs per-PNG `ResourceLocation`) is left to the parallel session — both work, atlas is faster.

## Reading live data from the bridge mod (in-process, no IPC)

Add to this subproject's `META-INF/mods.toml`:

```toml
[[dependencies.fathereye_map]]
    modId = "fathereye_bridge"
    mandatory = false
    versionRange = "[0.1.0,1.0.0)"
    ordering = "AFTER"
    side = "BOTH"
```

When loaded, query the bridge through its server-side classes (currently
`io.fathereye.bridge.profiler.WorldStateCollector`, `io.fathereye.bridge.profiler.ModsImpactCollector`):

```java
io.fathereye.bridge.profiler.WorldStateCollector.collectPlayers();
io.fathereye.bridge.profiler.WorldStateCollector.collectMobs();
io.fathereye.bridge.profiler.ModsImpactCollector.collect();
```

A formal `io.fathereye.bridge.api.BridgeServerApi` facade will be carved out
when the public surface stabilises; for now the parallel session can either
reach into the profiler classes directly or duplicate the small amount of
Minecraft-API enumeration code locally.

If `fathereye_bridge` is not present (e.g., on a player's client where only the map mod is installed), fall back to standard Forge sources (`Minecraft.getInstance().level`, etc.).

## Op-gated command pattern

Mirror Thaumaturgy's `CommandDupeLog.java` (`C:\Users\lukeo\Desktop\TomCraft\src\main\java\thaumaturgy\common\commands\CommandDupeLog.java`):
- Register on `RegisterCommandsEvent`.
- Brigadier dispatcher with `.requires(src -> src.hasPermission(2))`.
- Subcommands: `/fathereye-map open`, `/fathereye-map close`, `/fathereye-map layer <name> <on|off>`.

## Coordination

- This subproject is permanently claimed in `coordination/CLAIMED.md` to the parallel Claude session. The Father Eye session will not touch `map/**` after writing this skeleton.
- When bumping mapcore version, the Father Eye session edits `docs/mapcore-api-contract.md` first, publishes the new mapcore JAR to `local-maven/`, and notifies via a new `HANDOFF.md` section. The parallel session updates its pin in lockstep.
- Bug reports, regressions, and feature requests for the in-game GUI go to the parallel session, not the Father Eye session.
