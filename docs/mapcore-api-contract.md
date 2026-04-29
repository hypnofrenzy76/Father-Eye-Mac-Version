# mapcore API Contract — v0.2.0

This document is the source of truth for the public API of `io.fathereye:mapcore`. The desktop panel session and the in-game admin map session both pin to it and update only when this document is bumped.

> **Pin**: `implementation 'io.fathereye:mapcore:0.2.0'` — exact, no ranges.

## Versioning rules

- **Patch (0.1.x)** — bug fix, no signature change, no behavior change observable to consumers.
- **Minor (0.x.0)** — additive: new method on a NEW interface, new class in `api`, new field on a class with a backward-compatible constructor.
- **Major (x.0.0)** — breaking: signature change, removal, semantic change.

Both consumers move together. The contract bumps when the JAR bumps.

## Public surface

Only `io.fathereye.mapcore.api.*` is public. Anything in `io.fathereye.mapcore.internal.*` is implementation detail and may change without notice.

### Classes

| Type | Purpose |
|------|---------|
| `MapCore` (final, no instances) | Entry point. `VERSION="0.2.0"`, `CONTRACT_MAJOR=0`, `CONTRACT_MINOR=2`, `noOpRenderer()`, `basicRenderer()` (the v0.2 real renderer). |
| `MapTexture` (interface) | Opaque handle to a backend-uploaded texture. |
| `MapTextureLoader` (interface) | Backend factory: load from raw ARGB or PNG `InputStream`; release. |
| `MapGraphics` (interface) | Immediate-mode 2D drawing surface. Integer pixels, ARGB colors, no AA, no rotation. |
| `MapRenderer` (interface) | Top-level entry; called per frame on both backends. |
| `MapData` (final) | Per-frame snapshot: chunk tiles, players, entities, POIs. Immutable. |
| `MapViewState` (final) | Per-frame camera state: dim, center coords, zoom, viewport, layers. Immutable. |
| `LayerToggleSet` (final) | 64-bit bitset of which layers are visible. Bit constants stable across versions. |
| `ChunkTile` (final) | 16×16 chunk: biomes, heightmap, optional surface ARGB. |
| `EntityMarker` (final) | Live entity with id, type, modId, kind, position, yaw. |
| `PlayerMarker` (final) | Live player with uuid, name, dim, position, ping, online time. |
| `PointOfInterest` (final) | Generic icon marker (aura nodes, claim corners, structures, etc.). |

### Stable layer bit assignments (`LayerToggleSet`)

| Bit | Constant | Meaning |
|----:|----------|---------|
| 0 | `BIT_PLAYERS` | Live online players |
| 1 | `BIT_HOSTILE_MOBS` | Live hostile mobs |
| 2 | `BIT_PASSIVE_MOBS` | Live passive mobs |
| 3 | `BIT_ITEMS` | Dropped items |
| 4 | `BIT_LOADED_CHUNKS` | Loaded chunks heatmap |
| 5 | `BIT_TE_HOTSPOTS` | Tile entity tick hotspots |
| 6 | `BIT_BIOMES` | Biome color overlay |
| 7 | `BIT_STRUCTURES` | Server-known structures |
| 8 | `BIT_LIGHT_LEVELS` | Light-level overlay |
| 9 | `BIT_CLAIMS` | Claims/protection regions |
| 10 | `BIT_AURA_NODES` | Thaumaturgy nodes / mod-specific |
| 11 | `BIT_CUSTOM_POIS` | Generic POI registry |
| 12-63 | (reserved) | Future bits — both consumers ignore unknown bits |

### Renderer contract

A backend **must** implement `MapTextureLoader` and `MapGraphics` such that for any given `(MapViewState, MapData)`, calling `MapRenderer.render(g, view, data)` produces identical pixels regardless of which backend `g` adapts. Specific requirements:

- **No anti-aliasing.** Both backends explicitly disable it before drawing. (The in-game backend does so via `RenderSystem.disableBlend` paths; JavaFX uses `GraphicsContext.setImageSmoothing(false)`.)
- **Integer screen pixels.** Coordinate inputs are `int`; backends must NOT subpixel-snap differently.
- **Identical font.** Both backends render text by drawing glyphs from the SAME bundled font atlas (`io/fathereye/mapcore/font/minecraft-default.png`, shipped in mapcore resources from v0.2.0). Until v0.2.0, text renders are not pixel-stable across backends.
- **No alpha pre-multiplication.** ARGB tints are applied by the renderer with conventional non-premultiplied math.
- **Transform stack** is translate + uniform scale only. No rotation, no skew. Backends MAY use any underlying matrix.

### POJO conventions

- All POJOs are final classes with public final fields.
- All reference fields are non-null unless the field's javadoc explicitly says otherwise.
- Constructors validate (NPE on null, `IllegalArgumentException` on bad lengths).
- POJOs can be serialized with Jackson — consumers configure their `ObjectMapper` with field-based access (`MapperFeatures.AUTO_DETECT_FIELDS`); mapcore itself does NOT depend on Jackson.

## v0.1.0 — initial contract

Initial release. Interfaces, POJOs, `MapCore.noOpRenderer()` stub. Superseded by v0.2.0.

## v0.2.0 — current

Adds `MapCore.basicRenderer()` returning the `BasicMapRenderer` implementation:
- Background fill, chunk surfaceArgb at integer coords, chunk borders at zoom>=2
- Layer-gated entity/player/POI markers as colored squares
- Text via the backend's native font (font-atlas-based pixel-identical text deferred to v0.3.0)
Bumps `CONTRACT_MINOR` to `2`.

## Contracts under construction (NOT in v0.2.0)

These will land in subsequent minor versions:

- **v0.3.0**: Bundled font atlas at `resources/io/fathereye/mapcore/font/minecraft-default.png` so text renders pixel-identically across both backends. Biome palette PNG, marker icon PNGs.
- **v0.4.0**: World-to-screen / screen-to-world coordinate helpers (currently consumers do this math themselves).
- **v0.5.0**: Tile cache hint API (renderer can ask consumers to keep N chunks pre-uploaded).
