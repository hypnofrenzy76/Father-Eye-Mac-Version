# Map-03 (2026-06-14): viewport-aware map tile eviction (fix "chunks load then disappear in a wave on zoom-out")

## Symptom
The user reported (with a screenshot) that on the panel map, zooming OUT made
the map look empty: chunks would load and then "disappear in a wave across the
screen to the left."

## Root cause
`panel/.../view/MapPane.java` capped its rendered-tile cache at
`TILE_CACHE_LIMIT = 1024` (16x16 ARGB `WritableImage`s, sized for the AMD HD
6750M's shared VRAM). When the cache exceeded the cap, the eviction block ran:

```java
java.util.Iterator<Long> it = tileImages.keySet().iterator();
while (over-- > 0 && it.hasNext()) { ... it.remove(); ... }
```

Two bugs compounded:
1. **Not LRU, not viewport-aware.** Despite the "Bounded LRU eviction" comment,
   `tileImages` is a `ConcurrentHashMap` — `keySet()` iterates in hash order,
   not insertion/access order. Eviction dropped *arbitrary* tiles, including
   ones currently on screen.
2. **Cap smaller than the zoomed-out viewport.** When zoomed out the visible
   chunk count exceeds 1024, so every newly-arrived tile forced the eviction of
   another (often on-screen) tile. The evicted on-screen chunk was then
   re-requested via `chunks_topic`, re-rendered, and re-evicted — an infinite
   thrash. The "wave" the user saw was the hash-order eviction sweeping the map
   as the cache churned.

## Fix
New `evictOverflowTiles()` (FX-thread, called from the existing
`Platform.runLater` after a tile is stored) replaces the blind eviction:
- Computes the visible chunk range from `centerX/centerZ/zoom/canvas` plus a
  **one-viewport margin**, clamped to `>= 1` chunk so the protected range is
  always at least as large as `redraw()`'s and the request fan-out's `+/-1`
  pad. On-screen tiles are therefore NEVER evicted.
- Evicts ONLY off-screen tiles, **farthest-from-viewport-centre first**
  (squared-distance sort).
- If the visible set alone exceeds the cap (extreme zoom-out), nothing is
  evicted that frame — we keep what the user can see rather than thrash; the
  soft cap is allowed to be exceeded until the user zooms/pans back in. This is
  a viewport-bounded soft cap, not an unbounded leak.
- Key decode matches `MapData.chunkKey` (`((long)x<<32)|(z&0xFFFFFFFFL)`):
  `cx=(int)(key>>32)`, `cz=(int)key.longValue()`.

**Hysteresis (perf):** the allocating+sorting pass only fires once the cache is
`EVICT_BATCH = 128` tiles over the cap, then trims back to the cap in one pass —
amortising the O(n log n) sort across a burst of arrivals during a zoom-out
fill instead of paying it on every single arrival.

## Surface parity
No portal change needed. The browser WebPortal (`WebPortalAssets.java`,
`dims[d].chunks`) keeps ALL tiles with no cache cap or eviction, so it never had
this bug. The panel's VRAM-bounded soft-cap-with-protect is the correct,
intentional divergence (browser has no VRAM ceiling to respect).

## Audit + verification
Triple Opus audit: one "safe", two "needs changes" flagging (a) margin could be
< redraw's +/-1 pad at extreme zoom and (b) per-arrival sort cost — both
addressed by the `Math.max(1, ...)` clamp and the `EVICT_BATCH` hysteresis. The
"parity violation" finding was a false positive (portal has no eviction to
mirror). Built `:panel:jpackageMacApp`, deployed to `/Applications/Father
Eye.app`. Verified live: tile cache warmed 6671 tiles (well over the 1024 cap —
the exact overflow scenario) with the map stable and `chunks_topic` flowing.
No version bump (bugfix).
