package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import io.fathereye.mapcore.api.ChunkTile;
import io.fathereye.mapcore.api.MapData;
import io.fathereye.mapcore.api.PlayerMarker;
import io.fathereye.panel.ipc.PipeClient;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.BoundingBox;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance-rewritten map view (Pnl-24, 2026-04-26).
 *
 * <p>Previous design rendered chunks via {@code mapcore.BasicMapRenderer}
 * which iterates EVERY cached tile (up to 4096) and emits 256 per-pixel
 * {@code fillRect} calls per tile — so each redraw was up to ~1M draw
 * calls. On a drag event (60+ Hz) this froze the map and the FX thread.
 *
 * <p>New design:
 * <ul>
 *   <li>Each {@link ChunkTile} is pre-rendered ONCE to a 16×16
 *       {@link WritableImage} via PixelWriter when the response arrives.
 *       Subsequent redraws emit one {@code drawImage} per chunk.</li>
 *   <li>Redraw spatially culls — iterates only the visible chunk-coord
 *       range, not the cache. Zooming out scans more chunks but each is
 *       a single drawImage, so it stays cheap.</li>
 *   <li>Chunk-tile RPCs are deferred while the user is dragging or just
 *       finished zooming; a debounce timer fires the request fan-out
 *       ~120 ms after the last interaction.</li>
 *   <li>Players get larger stylised Steve-face heads, bigger bold names,
 *       and a right-click context menu with admin actions.</li>
 * </ul>
 */
public final class MapPane {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-MapPane");

    /** 16×16 pixel pre-rendered tile cap. Mac fork (audit 5): tightened
     *  from upstream's 4096 to 1024 to fit comfortably under the AMD HD
     *  6750M's 512 MB shared VRAM with macOS WindowServer + browser
     *  headroom. 1024 tiles still cover ~8x the visible-viewport chunk
     *  count at zoom 4, so panning never visibly thrashes. */
    private static final int TILE_CACHE_LIMIT = 1024;
    /** Mac fork (audit 8): "needs redraw" flag set by tile-arrival /
     *  pan / zoom paths and consumed by an AnimationTimer at FX-pulse
     *  cadence. Replaces the per-tile Platform.runLater(redraw) flood
     *  that saturated the Sandy Bridge i5's FX thread under heavy
     *  chunk_tile floods. */
    private final java.util.concurrent.atomic.AtomicBoolean dirty =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int MAX_TILE_REQUESTS_PER_FRAME = 16;
    /** Debounce window (ms) — wait this long after the last drag/zoom event
     *  before issuing chunk_tile RPCs, so panning doesn't storm the bridge. */
    private static final long REQUEST_DEBOUNCE_MS = 120L;
    /** Pnl-52 (2026-04-26): max number of chunk fetches kicked off per
     *  inbound chunks_topic snapshot. Pnl-53 (2026-04-27) bumped this
     *  from 200 to 1000 because the user reported chunks they were
     *  standing in still weren't loading -- 200/sec at chunks_topic
     *  rate is fine for ~200 loaded chunks but a steady-state 800+
     *  chunk modpack server only ever fills a fraction. The bridge
     *  serves chunk_tile from cache (already-loaded chunks) so 1000
     *  per second is well within IPC bandwidth. */
    /** Pnl-60 (2026-04-27): per-snapshot fetch budget bumped 1000
     *  -> 2500 in lockstep with the bridge's MAX_MISSES_PER_TICK
     *  bump (Brg-20: 16 -> 64 = 1280 chunks/sec throughput). The
     *  prior 1000-per-snapshot at 1 Hz capped panel-side queueing
     *  at 1000/sec, so the bridge sat partly idle while the user
     *  watched chunks fill in slowly. 2500/snapshot lets the panel
     *  saturate the new bridge throughput on big initial fills. */
    private static final int CHUNKS_TOPIC_FETCH_BUDGET = 2500;
    /** Pnl-52: persist offline players in the marker map for this long
     *  before purging. After 24 hours offline they drop out of the map. */
    private static final long OFFLINE_PLAYER_RETAIN_MS = 24L * 60 * 60 * 1000;

    private final Canvas canvas = new Canvas(800, 600);
    private final BorderPane root = new BorderPane();
    private final ChoiceBox<String> dimChoice = new ChoiceBox<>();
    private final Label coordsLabel = new Label("(--,--)");

    /** Pre-rendered 16×16 chunk Images. ConcurrentHashMap so the IPC thread
     *  can stash without synchronising with the FX redraw. */
    private final Map<Long, WritableImage> tileImages = new ConcurrentHashMap<>();
    /** Raw tile data, kept for click-info lookups (biome, height). */
    private final Map<Long, ChunkTile> tileData = new ConcurrentHashMap<>();
    /** Pnl-68 (2026-04-27): chunk-key -> request-sent timestamp.
     *  Thread-safe so the IPC reader thread can mutate it directly
     *  without going through Platform.runLater (which back-pressures
     *  on a busy FX thread). The timestamp also powers a periodic
     *  sweep that force-fails entries older than {@link
     *  #REQUEST_TIMEOUT_MS}, a backstop for cases where the
     *  CompletableFuture orTimeout doesn't fire (e.g. the bridge
     *  silently dropped the response and no exception ever
     *  propagated). The user reported "40 mins of uptime and all
     *  chunks still have not loaded" with 594 RPCs stuck in
     *  `requested` despite the per-RPC orTimeout — root cause was
     *  uncaught bridge-side exceptions whose error response also
     *  failed to send and got swallowed at
     *  IpcSession.java:122. Brg-23 surfaces those errors; this
     *  sweep recovers from them. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> requested =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Pnl-68: force-fail any chunk_tile request older than this.
     *  Slightly larger than the per-RPC orTimeout (30 s) so the
     *  orTimeout has a chance to fire first; the sweep is the
     *  backstop. */
    private static final long REQUEST_TIMEOUT_MS = 35_000L;
    /** Pnl-63 (2026-04-27): chunks the bridge has sentinel-rejected,
     *  with the wall-clock timestamp of the most recent rejection.
     *  After {@link #REJECTED_RETRY_MS} the entry is considered
     *  stale and the chunk gets retried; if the chunk is now
     *  renderable (player walked back into ticking range) the
     *  panel finally fills the gap. The previous Pnl-62 cut used a
     *  permanent {@link Set} which left visible black gaps in the
     *  map for any chunk that race-transitioned FULL -> LOAD
     *  between snapshot and RPC, even after the player came back
     *  and the chunk was FULL again. The user reported "map is
     *  disjointed with empty areas" when this happened.
     *  Pruned to the set of chunks still present in the latest
     *  chunks_topic snapshot via {@link Map#keySet} retainAll. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> rejectedChunks =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Pnl-63: retry a sentinel-rejected chunk after this many ms.
     *  5 s is short enough that a chunk re-promoted to FULL fills
     *  the gap quickly when the user walks back, long enough that
     *  a chronically unrenderable chunk doesn't burn RPC budget
     *  on every 1 Hz snapshot. */
    private static final long REJECTED_RETRY_MS = 5_000L;
    /** Pnl-66 (2026-04-27): set to true after the first chunks_topic
     *  snapshot triggers content verification. Verification samples
     *  a few chunks present in BOTH the disk cache and the current
     *  world, fetches them fresh from the bridge, and compares the
     *  surfaceArgb arrays. If the majority don't match, the cache
     *  is from a previous world (the timestamp / UUID heuristics
     *  in TileDiskCache.open missed it) and gets wiped at runtime.
     *  Keyed off `volatile` so the IPC reader thread reads a
     *  consistent value without locking. */
    private volatile boolean cacheVerificationDone = false;
    /** Pnl-52 (2026-04-26): switched from Map<UUID, PlayerMarker> to
     *  Map<UUID, PlayerEntry> so we can retain offline players (greyed
     *  out, with offline duration) instead of dropping them off the map
     *  the instant they log out. The PlayerEntry tracks firstSeenMs
     *  and lastSeenMs so the info dialog can show online time and
     *  offline duration. */
    private final Map<UUID, PlayerEntry> players = new ConcurrentHashMap<>();
    /** Last-rendered marker hit boxes for click hit-testing. Updated on FX
     *  thread, read on FX thread, no synchronisation needed. */
    private final Map<UUID, Bounds> playerHitRects = new HashMap<>();
    /** Pnl-52: shown in the toolbar; updates whenever a chunk_tile
     *  request fires or completes. "Loading X chunks (Y%)" while
     *  pending; "All chunks loaded" when zero. */
    private final Label loadingLabel = new Label("All chunks loaded");
    /** Pnl-52 (audit fix): spinner animation. Visible only while
     *  chunks are loading; sits beside loadingLabel in the toolbar.
     *  ProgressIndicator with progress=-1 renders the indeterminate
     *  rotating ring; we use that to satisfy the user's "loading
     *  animation" request without faking a determinate progress
     *  value. The accompanying loadingLabel carries the percentage. */
    private final javafx.scene.control.ProgressIndicator loadingSpinner = new javafx.scene.control.ProgressIndicator();
    /** Pnl-52: max simultaneously-pending count seen since the
     *  spinner last hit zero. Used as the denominator for the
     *  loading percentage so it monotonically rises 0% -> 100%
     *  rather than dropping when the budget refills. */
    private int peakPending = 0;
    /** Pnl-53 (2026-04-27): expected chunk count for the current
     *  dim, sourced from the latest chunks_topic snapshot. The user
     *  reported the loading label flashing too fast to read because
     *  per-batch peakPending resets to 0 every snapshot. With a
     *  session-cumulative denominator the percentage rises smoothly
     *  from 0% -> 100% across all batches and stays stable when
     *  there is nothing pending. */
    /** Pnl-57 (2026-04-27): volatile so cross-thread writes (FX
     *  thread on dim switch, IPC reader on chunks_topic snapshot)
     *  are visible to readers on the other thread (IPC reader's
     *  loadedCount comparison, FX thread's updateLoadingProgress).
     *  Without this, a stale read could let the panel's loading
     *  percentage flicker or lag a dim switch. */
    private volatile int expectedChunksThisDim = 0;
    /** Pnl-53: bounding box of every loaded chunk, recomputed on
     *  every chunks_topic snapshot. Used by the "Fit all" toolbar
     *  button to zoom to encompass every chunk the bridge reports
     *  loaded -- some heavy-modpack servers force-load chunks at
     *  spawn / chunkloaders far from any player, and they were
     *  invisible without panning. */
    private int chunkBboxMinCx = Integer.MAX_VALUE;
    private int chunkBboxMaxCx = Integer.MIN_VALUE;
    private int chunkBboxMinCz = Integer.MAX_VALUE;
    private int chunkBboxMaxCz = Integer.MIN_VALUE;
    /** Pnl-32: cached real-skin head textures fetched from Crafatar. */
    private final PlayerHeadCache headCache = new PlayerHeadCache();

    private double centerX = 0.0;
    private double centerZ = 0.0;
    private float zoom = 4.0f;
    /** Pnl-57 (2026-04-27): volatile so the IPC reader thread sees
     *  the FX thread's dim-switch write immediately. Without this,
     *  an in-flight chunk_tile response carrying the old dim could
     *  pass the equality filter at line 635 with a stale
     *  currentDim cached in a register, write to tileImages keyed
     *  only by (cx, cz), and corrupt the new dim's render. */
    private volatile String currentDim = "minecraft:overworld";

    private double dragStartX, dragStartZ;
    private double dragStartCenterX, dragStartCenterZ;
    private boolean dragging = false;

    /** Last user-interaction wall time (ms). Used to debounce RPC fan-out. */
    private final AtomicLong lastInteractionMs = new AtomicLong(0);
    /** Whether a deferred-request task is already queued.
     *  Pnl-57 (2026-04-27): AtomicBoolean.compareAndSet replaces the
     *  prior volatile-boolean check-then-set. Two threads (the FX
     *  thread on user pan/zoom and the FatherEye-MapDebounce
     *  background thread on its recursive scheduleTileRequests
     *  call) could both pass the gate before either flipped the
     *  flag, spawning two debounce daemons that each fired a
     *  duplicate chunk_tile RPC batch. CAS makes the gate atomic. */
    private final java.util.concurrent.atomic.AtomicBoolean requestTaskPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile PipeClient pipeClient;
    /** Pnl-53 (2026-04-27): on-disk persistence so every chunk ever
     *  discovered persists across panel restarts (vanilla-map-style
     *  "fill in as you walk" behaviour the user requested). Bound
     *  via {@link #setTileCache(TileDiskCache)} after the panel has
     *  resolved the server's working directory. Null until then;
     *  callers null-check. */
    private volatile TileDiskCache tileCache;
    /** Pnl-53 (audit fix, 2026-04-27): dim-generation token. Bumped
     *  every time {@link #currentDim} changes. Async warm threads
     *  capture the value at start and check it before each put into
     *  {@link #tileImages}; a stale generation means the dim has
     *  switched out from under us and the warm should abort. Same
     *  with in-flight chunk_tile responses arriving after the
     *  switch -- they're filtered by dim id directly, but the gen
     *  token also covers the warm-thread case where the data came
     *  from disk for the OLD dim. Without this fix, three audits
     *  identified silent visual corruption: old-dim tiles bleeding
     *  into the new dim's render at the same chunkKey(cx, cz). */
    private final java.util.concurrent.atomic.AtomicInteger dimGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public MapPane() {
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty().subtract(40));
        canvas.widthProperty().addListener((o, a, b) -> dirty.set(true));
        canvas.heightProperty().addListener((o, a, b) -> dirty.set(true));

        // Mac fork (audit 8): coalesced-redraw AnimationTimer. Runs on
        // the FX thread once per pulse (~60 Hz on hardware able to
        // sustain it, lower under load). Calls redraw() only if a
        // tile arrival / pan / zoom set the dirty flag. Eliminates
        // the per-tile Platform.runLater(redraw) flood that saturated
        // the FX queue on a 2.5 GHz Sandy Bridge during the 1280-
        // chunks/sec initial fill.
        new javafx.animation.AnimationTimer() {
            @Override public void handle(long nowNanos) {
                if (dirty.compareAndSet(true, false)) redraw();
            }
        }.start();

        // Pnl-68 (2026-04-27): every 5 s, scan `requested` for
        // entries older than REQUEST_TIMEOUT_MS and force-fail
        // them. CompletableFuture.orTimeout SHOULD already do this
        // per RPC, but bridge-side bugs that completed the future
        // exceptionally followed by sendResponse failures (which
        // were swallowed at IpcSession.java:122 pre-Brg-23) left
        // the panel future hanging anyway. The sweep is the
        // belt-and-suspenders backstop. The user reported "40 mins
        // of uptime and all chunks still have not loaded" before
        // this fix; the sweep guarantees eventual cleanup
        // regardless of which side dropped the response.
        java.util.concurrent.ScheduledExecutorService sweepExec =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "FatherEye-RequestSweep");
                    t.setDaemon(true);
                    return t;
                });
        sweepExec.scheduleAtFixedRate(this::sweepStaleRequests, 5L, 5L, java.util.concurrent.TimeUnit.SECONDS);

        canvas.setOnScroll(e -> {
            float old = zoom;
            zoom = (float) Math.max(0.5, Math.min(32.0, zoom * (e.getDeltaY() > 0 ? 1.2 : 1 / 1.2)));
            if (old != zoom) {
                noteInteraction();
                redraw();
            }
        });
        canvas.setOnMousePressed(e -> {
            // Right-click on a player marker opens the context menu instead
            // of starting a drag.
            if (e.getButton() == MouseButton.SECONDARY) {
                UUID hit = hitTestPlayer(e.getX(), e.getY());
                if (hit != null) {
                    showPlayerContextMenu(hit, e.getScreenX(), e.getScreenY());
                    e.consume();
                    return;
                }
            }
            dragStartX = e.getX();
            dragStartZ = e.getY();
            dragStartCenterX = centerX;
            dragStartCenterZ = centerZ;
            dragging = true;
        });
        canvas.setOnMouseDragged(e -> {
            if (!dragging) return;
            centerX = dragStartCenterX - (e.getX() - dragStartX) / zoom;
            centerZ = dragStartCenterZ - (e.getY() - dragStartZ) / zoom;
            noteInteraction();
            redraw();
        });
        canvas.setOnMouseReleased(e -> {
            dragging = false;
            // Trigger a deferred chunk fan-out for the final viewport.
            scheduleTileRequests();
        });
        canvas.setOnMouseMoved(e -> {
            double wx = centerX + (e.getX() - canvas.getWidth() / 2) / zoom;
            double wz = centerZ + (e.getY() - canvas.getHeight() / 2) / zoom;
            coordsLabel.setText(String.format("(%.0f, %.0f)", wx, wz));
        });
        // Left-click on a player without dragging shows the info dialog.
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.isStillSincePress()) {
                UUID hit = hitTestPlayer(e.getX(), e.getY());
                if (hit != null) {
                    PlayerEntry pe = players.get(hit);
                    if (pe != null) showPlayerInfoDialog(pe);
                }
            }
        });

        dimChoice.getItems().add("minecraft:overworld");
        dimChoice.getSelectionModel().select(0);
        dimChoice.setOnAction(e -> {
            String s = dimChoice.getValue();
            if (s != null && !s.equals(currentDim)) {
                // Pnl-53 (audit fix): bump dimGeneration BEFORE
                // mutating state. Any in-flight warm thread checks
                // the gen and aborts before clobbering the new dim.
                dimGeneration.incrementAndGet();
                currentDim = s;
                tileImages.clear();
                tileData.clear();
                requested.clear();
                rejectedChunks.clear();
                expectedChunksThisDim = 0;
                chunkBboxMinCx = Integer.MAX_VALUE;
                chunkBboxMaxCx = Integer.MIN_VALUE;
                chunkBboxMinCz = Integer.MAX_VALUE;
                chunkBboxMaxCz = Integer.MIN_VALUE;
                updateLoadingProgress();
                // Pnl-53: warm the new dim's tiles from the disk
                // cache so the user instantly sees their previous
                // exploration of that dim.
                warmCacheFromDisk(currentDim);
                redraw();
            }
        });

        Button center0 = new Button("Center (0,0)");
        center0.setOnAction(e -> { centerX = 0; centerZ = 0; noteInteraction(); redraw(); });
        // Pnl-53: zoom to fit every chunk the bridge has reported as
        // loaded. Useful when chunkloaders / forced chunks are far
        // from any player and would otherwise be invisible.
        Button fitAll = new Button("Fit all");
        fitAll.setOnAction(e -> fitAllLoadedChunks());

        // Pnl-52: loadingSpinner + loadingLabel give the operator an
        // explicit animation + percentage indicator. Sits in the
        // toolbar so it's always visible regardless of zoom / pan
        // state. The user explicitly asked for "loading animation
        // and percentage". Spinner visibility tracks pending state
        // so it disappears entirely when there's nothing loading.
        loadingLabel.setStyle("-fx-text-fill: #88c088;");
        loadingSpinner.setPrefSize(16, 16);
        loadingSpinner.setMaxSize(16, 16);
        loadingSpinner.setVisible(false);
        loadingSpinner.setManaged(false);
        HBox toolbar = new HBox(8, new Label("Dim:"), dimChoice, center0, fitAll,
                new Label(" Wheel = zoom · drag = pan · L-click marker = info · R-click marker = actions "),
                loadingSpinner,
                loadingLabel,
                coordsLabel);
        toolbar.setPadding(new Insets(6));
        toolbar.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: #ddd;");

        StackPane center = new StackPane(canvas);
        center.setStyle("-fx-background-color: #000;");
        root.setTop(toolbar);
        root.setCenter(center);
    }

    public BorderPane root() { return root; }

    public void bindPipeClient(PipeClient client) {
        this.pipeClient = client;
        Platform.runLater(() -> {
            redraw();
            scheduleTileRequests();
        });
    }

    /**
     * Pnl-53 (2026-04-27): install the persistent tile cache and
     * warm the in-memory cache from disk. Called by App.tryConnect
     * once the bridge handshake reveals the server's working
     * directory; safe to call multiple times (e.g. on reconnect).
     */
    public void setTileCache(TileDiskCache cache) {
        // Pnl-53 (audit fix): close the previous cache's writer
        // executor before replacing. Without this, every reconnect
        // leaked a FatherEye-TileWriter daemon thread.
        TileDiskCache prev = this.tileCache;
        this.tileCache = cache;
        if (prev != null && prev != cache) {
            try { prev.close(); } catch (Throwable ignored) {}
        }
        if (cache != null) {
            warmCacheFromDisk(currentDim);
        }
    }

    /**
     * Pnl-53: load every persisted tile for the given dim into
     * {@link #tileImages}. Runs on a background thread because a
     * heavy modpack server's cache can hit thousands of tiles, and
     * we don't want to block FX while reading them.
     *
     * <p>Pnl-53 (audit fix): captures {@link #dimGeneration} at
     * thread start; aborts if the gen changes mid-warm (rapid
     * dim-picker clicks). The gen check before every put prevents
     * old-dim tiles from leaking into the new dim's render.
     *
     * <p>Pnl-53 (audit fix): pushes a "Loading from cache..."
     * status during the warm so the user doesn't see an empty
     * canvas for 1-2 seconds with no indication anything's
     * happening.
     */
    private void warmCacheFromDisk(String dim) {
        TileDiskCache cache = this.tileCache;
        if (cache == null) return;
        final int myGen = dimGeneration.get();
        Platform.runLater(() -> {
            loadingLabel.setText("Loading cached tiles from disk...");
            loadingLabel.setStyle("-fx-text-fill: #88c0e0;");
            loadingSpinner.setVisible(true);
            loadingSpinner.setManaged(true);
        });
        Thread t = new Thread(() -> {
            java.util.List<ChunkTile> tiles = cache.loadAllFor(dim);
            if (tiles.isEmpty()) {
                LOG.info("tile cache for dim {} is empty (first run, or cache cleared)", dim);
                Platform.runLater(this::updateLoadingProgress);
                return;
            }
            int loaded = 0;
            int skippedStale = 0;
            for (ChunkTile tile : tiles) {
                if (dimGeneration.get() != myGen) {
                    skippedStale = tiles.size() - loaded;
                    LOG.info("tile cache warm aborted: dim changed mid-warm (loaded {} before abort)", loaded);
                    break;
                }
                long key = MapData.chunkKey(tile.chunkX, tile.chunkZ);
                if (tileImages.containsKey(key)) continue;
                javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(16, 16);
                try {
                    img.getPixelWriter().setPixels(0, 0, 16, 16,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                            tile.surfaceArgb, 0, 16);
                } catch (Throwable th) {
                    continue;
                }
                tileImages.put(key, img);
                tileData.put(key, tile);
                loaded++;
            }
            LOG.info("tile cache warmed: loaded {} tiles for dim {} (skipped {} due to dim switch)",
                    loaded, dim, skippedStale);
            Platform.runLater(() -> {
                updateLoadingProgress();
                redraw();
            });
        }, "FatherEye-TileCacheWarm");
        t.setDaemon(true);
        t.start();
    }

    public void setDimensions(String[] dims) {
        Platform.runLater(() -> {
            dimChoice.getItems().setAll(dims);
            if (dims.length > 0) {
                String pick = currentDim;
                boolean found = false;
                for (String d : dims) if (d.equals(pick)) { found = true; break; }
                if (!found) {
                    currentDim = dims[0];
                    dimChoice.getSelectionModel().select(0);
                }
            }
        });
    }

    public void onPlayersSnapshot(JsonNode payload) {
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;
        JsonNode arr = data.get("players");
        if (arr == null) return;
        long now = System.currentTimeMillis();
        // Pnl-52 (2026-04-26): instead of clearing the map and rebuilding,
        // mark every existing entry as offline first, then mark each
        // player in the snapshot as online (creating a fresh entry on
        // first sighting). This way logged-out players persist with
        // online=false until OFFLINE_PLAYER_RETAIN_MS expires.
        for (PlayerEntry pe : players.values()) pe.online = false;
        for (JsonNode p : arr) {
            try {
                UUID uuid = UUID.fromString(p.path("uuid").asText("00000000-0000-0000-0000-000000000000"));
                PlayerMarker m = new PlayerMarker(
                        uuid,
                        p.path("name").asText("?"),
                        p.path("dimensionId").asText("minecraft:overworld"),
                        p.path("x").asDouble(0),
                        p.path("y").asDouble(0),
                        p.path("z").asDouble(0),
                        (float) p.path("yaw").asDouble(0),
                        p.path("health").asInt(20),
                        p.path("food").asInt(20),
                        p.path("pingMs").asInt(0),
                        p.path("onlineSinceEpochMs").asLong(0),
                        p.path("gameMode").asText("survival"));
                PlayerEntry existing = players.get(uuid);
                if (existing == null) {
                    players.put(uuid, new PlayerEntry(m, now));
                } else {
                    existing.marker = m;
                    existing.lastSeenMs = now;
                    existing.online = true;
                }
            } catch (Throwable ignored) {}
        }
        // Purge entries that have been offline longer than the retention
        // window so the map doesn't accumulate ex-members forever.
        long retentionDeadline = now - OFFLINE_PLAYER_RETAIN_MS;
        players.values().removeIf(pe -> !pe.online && pe.lastSeenMs < retentionDeadline);
        Platform.runLater(this::redraw);
    }

    /**
     * Pnl-52 (2026-04-26): receive the bridge's chunks_topic snapshot
     * and queue chunk_tile fetches for every loaded chunk in the
     * current dim. Cap at {@link #CHUNKS_TOPIC_FETCH_BUDGET} per
     * snapshot so a fresh subscription against a busy server doesn't
     * flood the IPC pipe; remaining chunks are picked up on
     * subsequent snapshots.
     */
    public void onChunksSnapshot(JsonNode payload) {
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;
        JsonNode byDim = data.get("byDim");
        if (byDim == null) return;
        JsonNode dimNode = byDim.get(currentDim);
        if (dimNode == null) return;
        // Pnl-53: surface the loadedChunks count separately from the
        // coordinate list. The user reported "the chunks i am in are
        // not loading"; logging the bridge-reported count lets us
        // verify whether the bridge is reporting the player's chunks
        // (and the panel just hasn't rendered yet) versus the bridge
        // genuinely omitting them.
        int loadedCount = dimNode.path("loadedChunks").asInt(0);
        JsonNode arr = dimNode.get("chunks");
        int chunkCoordPairs = arr == null || !arr.isArray() ? 0 : arr.size() / 2;
        if (loadedCount != expectedChunksThisDim || chunkCoordPairs > 0) {
            // Once-per-change diagnostic; saves the user from
            // wondering if chunks_topic is even alive.
            LOG.info("chunks_topic [{}]: loadedChunks={} coordsReported={}",
                    currentDim, loadedCount, chunkCoordPairs);
        }
        if (arr == null || !arr.isArray() || arr.size() < 2) {
            // Pnl-53: still update the expected count even if no
            // coords arrived, so the loading label can report
            // "Loaded 0 / N chunks (bridge sent count only, no list)".
            expectedChunksThisDim = loadedCount;
            Platform.runLater(this::updateLoadingProgress);
            return;
        }
        // Parse flat int array [cx0, cz0, cx1, cz1, ...].
        int n = arr.size();
        java.util.List<int[]> coords = new java.util.ArrayList<>(n / 2);
        // Pnl-53: also recompute the bounding box so the "Fit all"
        // button has somewhere to zoom to.
        int bMinCx = Integer.MAX_VALUE, bMaxCx = Integer.MIN_VALUE;
        int bMinCz = Integer.MAX_VALUE, bMaxCz = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < n; i += 2) {
            int cx = arr.get(i).asInt(0);
            int cz = arr.get(i + 1).asInt(0);
            coords.add(new int[] { cx, cz });
            if (cx < bMinCx) bMinCx = cx;
            if (cx > bMaxCx) bMaxCx = cx;
            if (cz < bMinCz) bMinCz = cz;
            if (cz > bMaxCz) bMaxCz = cz;
        }
        if (coords.isEmpty()) return;
        final int fMinCx = bMinCx, fMaxCx = bMaxCx, fMinCz = bMinCz, fMaxCz = bMaxCz;
        // Defer the request fan-out to the FX thread so the requested
        // set, tileImages map, and pipeClient access are all
        // serialised against redraws.
        Platform.runLater(() -> {
            // Pnl-53: bump expected count to the higher of the
            // bridge-reported loadedChunks and the actual list size.
            // chunks_topic may report a smaller list (legacy bridge)
            // but a non-zero loadedChunks count.
            expectedChunksThisDim = Math.max(loadedCount, coords.size());
            chunkBboxMinCx = fMinCx;
            chunkBboxMaxCx = fMaxCx;
            chunkBboxMinCz = fMinCz;
            chunkBboxMaxCz = fMaxCz;
            if (pipeClient == null || pipeClient.isClosed()) return;
            // Pnl-62: build a fresh "currently reported" set so we
            // can drop rejectedChunks entries that the bridge no
            // longer reports (chunk fully unloaded -> not in
            // chunks_topic -> stops counting against the total).
            // Without this drop, the rejected set grows unbounded
            // across long sessions of exploration.
            java.util.Set<Long> reportedKeys = new java.util.HashSet<>(coords.size());
            for (int[] c : coords) reportedKeys.add(MapData.chunkKey(c[0], c[1]));
            rejectedChunks.keySet().retainAll(reportedKeys);
            // Pnl-64 (2026-04-27): DON'T remove TTL-expired entries
            // from rejectedChunks here. The previous version
            // evicted them so the snapshot loop would re-issue a
            // request, but eviction also dropped the counter
            // contribution (handled = tileImages + rejectedChunks).
            // When a chronic LOAD-level chunk got TTL-evicted the
            // percentage dipped, and when the bridge sentineled
            // again it climbed back — the user saw the loading
            // bar oscillate around 87 % indefinitely. Keep
            // rejected chunks in the map for counter purposes;
            // the loop below decides retry-eligibility from the
            // timestamp directly. Successful renders explicitly
            // remove entries from rejectedChunks.
            long now = System.currentTimeMillis();
            int budget = CHUNKS_TOPIC_FETCH_BUDGET;
            for (int[] c : coords) {
                long key = MapData.chunkKey(c[0], c[1]);
                if (tileImages.containsKey(key) || requested.containsKey(key)) continue;
                // Pnl-62/63/64: skip if recently rejected. Eligible
                // for retry once the rejection is older than
                // REJECTED_RETRY_MS, but we keep the entry in the
                // map so the counter still counts it as "handled"
                // while we wait for the retry result.
                Long rejectedAt = rejectedChunks.get(key);
                if (rejectedAt != null && (now - rejectedAt) < REJECTED_RETRY_MS) continue;
                requested.put(key, now);
                requestChunk(c[0], c[1]);
                if (--budget <= 0) break;
            }
            updateLoadingProgress();
            // Pnl-66 (2026-04-27): on the FIRST chunks_topic
            // snapshot, kick off content verification so the panel
            // confirms the disk cache actually matches the current
            // world. The user reported "panel has to check that
            // chunk map actually matches current world" after the
            // timestamp / UUID heuristics in TileDiskCache.open
            // missed an upgrade-after-world-delete case.
            if (!cacheVerificationDone) {
                cacheVerificationDone = true;
                verifyCacheAgainstWorld(coords);
            }
        });
    }

    /**
     * Pnl-66 (2026-04-27): sample up to 5 chunks that are present
     * in BOTH the disk cache and the live world (the {@code coords}
     * list from chunks_topic), fetch them fresh from the bridge,
     * and compare surfaceArgb arrays. If the majority don't match,
     * the disk cache is from a previous world that the boot-time
     * heuristics missed (e.g. user upgraded between world deletes
     * so the UUID stamp ended up matching but the tiles didn't).
     * Wipes both the in-memory and disk caches in that case.
     *
     * <p>Runs entirely in the background (RPC + dedicated daemon
     * thread for the comparison logic) so the FX thread isn't
     * blocked. Side effects all flow through Platform.runLater.
     *
     * @param coords currently-reported chunks_topic coordinates
     */
    private void verifyCacheAgainstWorld(java.util.List<int[]> coords) {
        if (pipeClient == null || pipeClient.isClosed()) return;
        // Pick up to 5 chunks that are in both cache and world.
        java.util.List<int[]> samples = new java.util.ArrayList<>(5);
        for (int[] c : coords) {
            long key = MapData.chunkKey(c[0], c[1]);
            ChunkTile cachedData = tileData.get(key);
            if (cachedData == null) continue; // cache doesn't have it
            samples.add(c);
            if (samples.size() >= 5) break;
        }
        if (samples.isEmpty()) {
            // No overlap; nothing to verify against. Boot-time
            // heuristics will have to do.
            return;
        }
        LOG.info("verifying disk cache against {} sample chunks", samples.size());
        final int sampleCount = samples.size();
        final java.util.concurrent.atomic.AtomicInteger replies =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger mismatches =
                new java.util.concurrent.atomic.AtomicInteger(0);
        for (int[] c : samples) {
            final int sx = c[0], sz = c[1];
            try {
                java.util.Map<String, Object> args = new LinkedHashMap<>();
                args.put("dim", currentDim);
                args.put("cx", sx);
                args.put("cz", sz);
                // Pnl-68: timeout the verify-RPC too (Agent 1 fix).
                // Without it, a hung verify-RPC would never tally
                // a result and the verification logic would wait
                // forever, possibly leaving the cache un-validated
                // for the session.
                pipeClient.sendRequest("chunk_tile", args)
                        .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .whenComplete((node, err) -> {
                    try {
                        if (err == null && node != null) {
                            JsonNode result = node.get("result");
                            if (result != null) {
                                int[] freshArgb = nodeToInts(result.path("surfaceArgb"));
                                if (freshArgb != null && freshArgb.length == 256) {
                                    long key = MapData.chunkKey(sx, sz);
                                    ChunkTile cached = tileData.get(key);
                                    if (cached != null && cached.surfaceArgb != null
                                            && cached.surfaceArgb.length == 256
                                            && !arraysSimilar(cached.surfaceArgb, freshArgb)) {
                                        mismatches.incrementAndGet();
                                    }
                                }
                            }
                        }
                    } finally {
                        if (replies.incrementAndGet() == sampleCount) {
                            int m = mismatches.get();
                            // Pnl-66: wipe if a strict majority of
                            // samples differ. Single-chunk
                            // mismatches happen normally (player
                            // built something) so we tolerate up
                            // to half.
                            if (m * 2 > sampleCount) {
                                LOG.info("cache content mismatch ({}/{} samples differ); wiping cache",
                                        m, sampleCount);
                                Platform.runLater(this::wipeCacheRuntime);
                            } else {
                                LOG.info("cache content verified ({}/{} samples differ; within tolerance)",
                                        m, sampleCount);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                LOG.warn("cache verify RPC for ({},{}) failed: {}", sx, sz, t.toString());
                if (replies.incrementAndGet() == sampleCount) {
                    int m = mismatches.get();
                    if (m * 2 > sampleCount) Platform.runLater(this::wipeCacheRuntime);
                }
            }
        }
    }

    /** Pnl-66: returns true if {@code a} and {@code b} are bit-identical
     *  surfaceArgb arrays. We require exact equality because
     *  player-modified chunks are rare in our 5-sample audit and
     *  the bridge reproduces ChunkTileHandler output deterministically
     *  for unmodified terrain. Tolerating "near matches" would let a
     *  different-world cache slip through. */
    private static boolean arraysSimilar(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /** Pnl-66: clear in-memory and disk caches, reset progress
     *  bookkeeping, and trigger a redraw. Called when content
     *  verification detected the cache is from a different world. */
    private void wipeCacheRuntime() {
        tileImages.clear();
        tileData.clear();
        requested.clear();
        rejectedChunks.clear();
        TileDiskCache c = this.tileCache;
        if (c != null) c.wipeAll();
        LOG.info("runtime cache wipe complete; chunks will re-render");
        updateLoadingProgress();
        redraw();
    }

    /**
     * Pnl-53 (2026-04-27): zoom + center the canvas to fit every
     * chunk the bridge has reported as loaded in the current dim.
     * Useful when chunks are loaded far from the player (force-loaded
     * spawn chunks, chunkloaders, etc.) and would otherwise be
     * invisible.
     */
    private void fitAllLoadedChunks() {
        if (chunkBboxMinCx == Integer.MAX_VALUE) {
            // No chunks reported yet. Just go to spawn.
            centerX = 0; centerZ = 0;
            redraw();
            return;
        }
        // Convert chunk coords to world block coords (chunk * 16).
        double minX = chunkBboxMinCx * 16.0;
        double maxX = (chunkBboxMaxCx + 1) * 16.0;
        double minZ = chunkBboxMinCz * 16.0;
        double maxZ = (chunkBboxMaxCz + 1) * 16.0;
        centerX = (minX + maxX) / 2.0;
        centerZ = (minZ + maxZ) / 2.0;
        // Zoom: pixels-per-block to fit (max - min) into the canvas
        // with ~10% padding.
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w > 0 && h > 0) {
            double rangeX = Math.max(16.0, maxX - minX);
            double rangeZ = Math.max(16.0, maxZ - minZ);
            double zX = (w * 0.9) / rangeX;
            double zZ = (h * 0.9) / rangeZ;
            zoom = (float) Math.max(0.05, Math.min(zX, zZ));
        }
        noteInteraction();
        redraw();
    }

    /**
     * Pnl-53 (2026-04-27): cumulative loading progress.
     *
     * <p>The user reported the previous label flashing too fast to
     * read because every chunks_topic snapshot reset peakPending to
     * 0 between batches. With cumulative tracking the percentage
     * rises smoothly across multiple snapshots and the label only
     * disappears once every chunk the bridge reported has been
     * fetched (or is already in the on-disk cache).
     *
     * <p>Numerator: {@code tileImages.size()} -- every chunk the
     * panel has cached for this dim, including tiles loaded from
     * disk on startup.
     * <p>Denominator: {@code expectedChunksThisDim} -- the latest
     * count the bridge reported for this dim, or, if larger, the
     * number we've already seen (so the bar never reads >100%).
     */
    private void updateLoadingProgress() {
        int pending = requested.size();
        int loaded = tileImages.size();
        int rejected = rejectedChunks.size();
        // Pnl-69 (2026-04-27): "handled" now includes pending
        // (in-flight) chunks too. Once the panel has *acknowledged*
        // every reported chunk by sending an RPC for it, the bar
        // reaches 100 % regardless of whether the responses have
        // come back yet. Previously pending chunks were excluded
        // and the bar bounced around 70-90 % indefinitely as new
        // chunks entered chunks_topic faster than they could
        // resolve. Pending eventually transition to either
        // tileImages (success) or rejectedChunks (timeout/error)
        // via the new sweep + reject logic, so the bar stays at
        // 100 % during normal operation. New exploration produces
        // brief dips when chunks_topic adds chunks the panel
        // hasn't yet sent RPCs for; the next snapshot recovers.
        int handled = loaded + rejected + pending;
        int expected = Math.max(expectedChunksThisDim, handled);
        if (expected == 0) {
            // No chunks_topic data yet AND nothing cached; the bridge
            // hasn't reported anything for this dim.
            loadingLabel.setText("Awaiting chunks_topic...");
            loadingLabel.setStyle("-fx-text-fill: #888;");
            loadingSpinner.setVisible(false);
            loadingSpinner.setManaged(false);
        } else if (pending == 0 && handled >= expected) {
            String suffix = rejected > 0 ? " (" + rejected + " unrenderable)" : "";
            loadingLabel.setText("All " + loaded + " chunks loaded" + suffix);
            loadingLabel.setStyle("-fx-text-fill: #88c088;");
            loadingSpinner.setVisible(false);
            loadingSpinner.setManaged(false);
        } else {
            int pct = expected > 0 ? (int) Math.round(100.0 * handled / expected) : 0;
            String suffix = rejected > 0 ? " (" + rejected + " unrenderable)" : "";
            loadingLabel.setText("Loading " + loaded + " / " + expected + " chunks (" + pct + "%)" + suffix);
            loadingLabel.setStyle("-fx-text-fill: #e0c060;");
            if (!loadingSpinner.isVisible()) {
                loadingSpinner.setVisible(true);
                loadingSpinner.setManaged(true);
            }
        }
    }

    /** Called on the IPC reader thread when a chunk_tile Response arrives. */
    public void onChunkTileResponse(int cx, int cz, ChunkTile tile) {
        // Pnl-53 (audit fix): drop responses whose dim no longer
        // matches the panel's current dim. Without this filter, an
        // in-flight RPC issued before a dim switch can land in
        // tileImages keyed by (cx, cz) only and corrupt the new
        // dim's render at the same coords. The dim of the
        // response is the dim it was actually loaded for on the
        // server; the panel's currentDim is the user's selection.
        if (tile != null && tile.dimensionId != null
                && !tile.dimensionId.equals(currentDim)) {
            // Mark request "completed" so the loading counter
            // doesn't get stuck, but skip the visual update.
            // Pnl-69: synchronous remove (requested is now a
            // ConcurrentHashMap). Only updateLoadingProgress goes
            // through runLater because it touches FX labels.
            requested.remove(MapData.chunkKey(cx, cz));
            Platform.runLater(this::updateLoadingProgress);
            return;
        }
        // Pnl-56 (2026-04-27): detect bridge's "empty sentinel"
        // response (chunk not loaded OR over miss budget). The
        // bridge has to send 256-length arrays to satisfy the
        // ChunkTile constructor, so it sends 256 zeros. Treat
        // all-zero surfaceArgb as "no data yet": don't add to
        // tileImages (so future requests still fire), don't save
        // to disk cache (so it doesn't poison cross-session). The
        // chunk will be re-requested on the next chunks_topic
        // snapshot once Forge actually loads it.
        if (tile != null && tile.surfaceArgb != null && tile.surfaceArgb.length == 256) {
            boolean allZero = true;
            // Pnl-61 (2026-04-27): also reject mostly-black tiles
            // (surfaceArgb full of 0xFF000000). Brg-19's holder-level
            // fallback returns a usable IChunk for LOAD-ticket-level
            // chunks, but if the chunk's in-memory ChunkSection[]
            // was freed (very common when the chunk was unloaded
            // under memory pressure), every getBlockState returns
            // AIR which maps to MaterialColor.NONE which produces
            // surfaceArgb 0xFF000000 (full alpha, zero RGB). The old
            // all-zero filter only caught surfaceArgb == 0, so the
            // 0xFF000000-filled tile slipped through and got
            // persisted as a black rectangle in tileImages and the
            // disk cache. Brg-21 catches these server-side, but
            // this client-side filter handles legacy responses and
            // any race where the server check misses. 252 of 256
            // pixels at pure black is our threshold (a real chunk
            // can't naturally produce 99 % pure-black pixels).
            int blackPixels = 0;
            for (int i = 0; i < 256; i++) {
                int p = tile.surfaceArgb[i];
                if (p != 0) allZero = false;
                if (p == 0xFF000000) blackPixels++;
            }
            if (allZero || blackPixels >= 252) {
                long key = MapData.chunkKey(cx, cz);
                // Pnl-69: synchronous bookkeeping; only the FX
                // label update is deferred. Both maps are
                // ConcurrentHashMap so the IPC reader thread can
                // mutate them directly.
                requested.remove(key);
                rejectedChunks.put(key, System.currentTimeMillis());
                Platform.runLater(this::updateLoadingProgress);
                return;
            }
        }
        long key = MapData.chunkKey(cx, cz);
        // Pre-render 16×16 ARGB pixels into a JavaFX WritableImage.
        // Done on the IPC reader thread to keep the FX thread free for paint.
        WritableImage img = new WritableImage(16, 16);
        PixelWriter pw = img.getPixelWriter();
        if (tile.surfaceArgb != null && tile.surfaceArgb.length == 256) {
            // PixelFormat.getIntArgbInstance reads ARGB ints in our exact layout.
            pw.setPixels(0, 0, 16, 16, PixelFormat.getIntArgbInstance(),
                    tile.surfaceArgb, 0, 16);
        } else {
            // Empty / unloaded tile — fill grey so it's visually distinct
            // from "no data yet" (transparent).
            int[] grey = new int[256];
            for (int i = 0; i < 256; i++) grey[i] = 0xFF303030;
            pw.setPixels(0, 0, 16, 16, PixelFormat.getIntArgbInstance(), grey, 0, 16);
        }
        tileImages.put(key, img);
        tileData.put(key, tile);
        // Pnl-53 (2026-04-27): persist to disk so this chunk
        // survives panel restarts. Async write on the disk-cache's
        // dedicated executor; doesn't block the IPC reader.
        TileDiskCache c = this.tileCache;
        if (c != null) c.save(tile);
        // Pnl-69: do bookkeeping mutations synchronously off the
        // FX thread (both maps are ConcurrentHashMap). Only label
        // update + redraw + LRU eviction (which touch FX-only
        // state) run inside Platform.runLater.
        requested.remove(key);
        rejectedChunks.remove(key);
        // Mac fork (audit 8 perf): coalesce per-tile redraws via the
        // dirty flag. The upstream code did Platform.runLater(redraw)
        // PER TILE, which on a Sandy Bridge i5 with 1280 tiles arriving
        // per second saturated the FX queue with redraw runnables that
        // each iterate every tile. Now we just mark dirty; the
        // AnimationTimer in scheduleCoalescedRedraw() picks it up at
        // most once per frame (~60 Hz) on the FX thread.
        dirty.set(true);
        Platform.runLater(() -> {
            updateLoadingProgress();
            // Bounded LRU eviction.
            if (tileImages.size() > TILE_CACHE_LIMIT) {
                int over = tileImages.size() - TILE_CACHE_LIMIT;
                java.util.Iterator<Long> it = tileImages.keySet().iterator();
                while (over-- > 0 && it.hasNext()) {
                    Long k = it.next();
                    it.remove();
                    tileData.remove(k);
                }
            }
        });
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        g.setFill(Color.web("#101010"));
        g.fillRect(0, 0, w, h);
        g.setImageSmoothing(false);

        // Spatial culling: compute the visible chunk range and iterate it
        // ONLY. The previous renderer iterated every cached tile (up to
        // 4096) regardless of visibility — the root cause of zoom-out lag.
        double halfW = w / 2.0;
        double halfH = h / 2.0;
        double viewBlocksX = w / zoom;
        double viewBlocksZ = h / zoom;
        int minCx = (int) Math.floor((centerX - viewBlocksX / 2.0) / 16.0) - 1;
        int maxCx = (int) Math.ceil((centerX + viewBlocksX / 2.0) / 16.0) + 1;
        int minCz = (int) Math.floor((centerZ - viewBlocksZ / 2.0) / 16.0) - 1;
        int maxCz = (int) Math.ceil((centerZ + viewBlocksZ / 2.0) / 16.0) + 1;

        double tileSize = 16.0 * zoom;
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                long key = MapData.chunkKey(cx, cz);
                WritableImage img = tileImages.get(key);
                if (img == null) continue;
                double sx = (cx * 16 - centerX) * zoom + halfW;
                double sy = (cz * 16 - centerZ) * zoom + halfH;
                g.drawImage(img, sx, sy, tileSize, tileSize);
            }
        }

        // Faint chunk grid overlay at high zoom, similar to the old renderer.
        if (zoom >= 2.0f) {
            g.setStroke(Color.rgb(50, 50, 50, 0.6));
            g.setLineWidth(1);
            for (int cx = minCx; cx <= maxCx; cx++) {
                double sx = (cx * 16 - centerX) * zoom + halfW + 0.5;
                g.strokeLine(sx, 0, sx, h);
            }
            for (int cz = minCz; cz <= maxCz; cz++) {
                double sy = (cz * 16 - centerZ) * zoom + halfH + 0.5;
                g.strokeLine(0, sy, w, sy);
            }
        }

        // Players: bigger Steve heads + bold names + drop-shadow, hit-test
        // rects collected for click handling.
        // Pnl-52 (2026-04-26): online players render normally; offline
        // players render with reduced opacity and an "offline Xm" label.
        playerHitRects.clear();
        long now = System.currentTimeMillis();
        for (PlayerEntry pe : players.values()) {
            PlayerMarker p = pe.marker;
            if (!p.dimensionId.equals(currentDim)) continue;
            double sx = (p.x - centerX) * zoom + halfW;
            double sy = (p.z - centerZ) * zoom + halfH;
            if (pe.online) {
                drawPlayerHead(g, p, sx, sy, true);
                drawPlayerName(g, p.name, sx, sy, true);
            } else {
                drawPlayerHead(g, p, sx, sy, false);
                drawPlayerName(g, p.name, sx, sy, false);
                long offlineMs = now - pe.lastSeenMs;
                drawOfflineLabel(g, sx, sy, offlineMs);
            }
            playerHitRects.put(p.uuid, new BoundingBox(sx - 14, sy - 14, 28, 28));
        }
    }

    /**
     * Pnl-52: small grey "offline 5m" label rendered just below the
     * player's name when they are not in the current snapshot.
     */
    private static void drawOfflineLabel(GraphicsContext g, double cx, double cy, long offlineMs) {
        Font old = g.getFont();
        g.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        String text = "offline " + fmtDuration(offlineMs);
        // Pnl-52 (audit fix): position BELOW the head (head spans
        // cy-10..cy+10 at s=20). cy+18 clears the head and leaves a
        // small gap; the previous cy+6 sat on top of the head's
        // lower half.
        double tx = cx + 14;
        double ty = cy + 18;
        g.setFill(Color.rgb(0, 0, 0, 0.75));
        g.fillText(text, tx + 1, ty + 1);
        g.setFill(Color.rgb(220, 180, 180, 0.9));
        g.fillText(text, tx, ty);
        g.setFont(old);
    }

    private void drawPlayerHead(GraphicsContext g, PlayerMarker p, double cx, double cy, boolean online) {
        // Pnl-32: try the real player skin from the cache first; if not
        // available yet, draw the stylised Steve placeholder and kick off
        // an async fetch so the next redraw has the real face.
        // Pnl-52 (2026-04-26): offline players render at reduced
        // opacity (alpha 0.4) so they're visually distinct from
        // currently-online players. globalAlpha is saved and
        // restored so subsequent draws are unaffected.
        double prevAlpha = g.getGlobalAlpha();
        if (!online) g.setGlobalAlpha(0.4);
        try {
            javafx.scene.image.Image head = headCache.get(p.uuid);
            if (head != null) {
                double s = 20.0;
                double x = cx - s / 2.0;
                double y = cy - s / 2.0;
                // 1-pixel dark border for legibility against bright biomes.
                g.setFill(Color.BLACK);
                g.fillRect(x - 1, y - 1, s + 2, s + 2);
                g.drawImage(head, x, y, s, s);
            } else {
                drawSteveHead(g, cx, cy);
                headCache.requestLoad(p.uuid, p.name, this::redraw);
            }
        } finally {
            g.setGlobalAlpha(prevAlpha);
        }
    }

    private static void drawSteveHead(GraphicsContext g, double cx, double cy) {
        double s = 20.0;
        double x = cx - s / 2.0;
        double y = cy - s / 2.0;
        // dark border
        g.setFill(Color.BLACK);
        g.fillRect(x - 1, y - 1, s + 2, s + 2);
        // skin face
        g.setFill(Color.web("#F0C9A0"));
        g.fillRect(x, y, s, s);
        // brown hair on top quarter
        g.setFill(Color.web("#5A3825"));
        g.fillRect(x, y, s, s * 0.30);
        // eyes
        g.setFill(Color.web("#202020"));
        g.fillRect(x + s * 0.25, y + s * 0.45, s * 0.12, s * 0.12);
        g.fillRect(x + s * 0.63, y + s * 0.45, s * 0.12, s * 0.12);
        // mouth (subtle)
        g.setFill(Color.web("#994444"));
        g.fillRect(x + s * 0.35, y + s * 0.72, s * 0.30, s * 0.08);
    }

    private static void drawPlayerName(GraphicsContext g, String name, double cx, double cy, boolean online) {
        Font old = g.getFont();
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        // text appears just to the right and above the head
        double tx = cx + 14;
        double ty = cy - 10;
        // shadow
        g.setFill(Color.rgb(0, 0, 0, 0.75));
        g.fillText(name, tx + 1, ty + 1);
        // main: white when online, soft grey when offline so the
        // visual distinction matches the head's reduced opacity.
        g.setFill(online ? Color.WHITE : Color.rgb(170, 170, 170));
        g.fillText(name, tx, ty);
        g.setFont(old);
    }

    private UUID hitTestPlayer(double x, double y) {
        for (Map.Entry<UUID, Bounds> e : playerHitRects.entrySet()) {
            if (e.getValue().contains(x, y)) return e.getKey();
        }
        return null;
    }

    // ---- player context menu / actions --------------------------------

    private void showPlayerContextMenu(UUID uuid, double screenX, double screenY) {
        // Pnl-52: players.get returns a PlayerEntry now; pull the
        // PlayerMarker out for the existing action methods (which
        // operate on positions / UUIDs that don't change between
        // online and offline). showPlayerInfoDialog has its own
        // PlayerEntry overload for the online/offline indicator.
        PlayerEntry pe = players.get(uuid);
        if (pe == null) return;
        PlayerMarker p = pe.marker;

        ContextMenu menu = new ContextMenu();
        MenuItem info = new MenuItem("Player Info");
        info.setOnAction(ev -> showPlayerInfoDialog(pe));
        MenuItem tp = new MenuItem("Teleport to coordinates…");
        tp.setOnAction(ev -> teleportPlayerDialog(p));
        MenuItem msg = new MenuItem("Send chat message…");
        msg.setOnAction(ev -> chatMessageDialog(p));
        MenuItem kick = new MenuItem("Kick…");
        kick.setOnAction(ev -> kickPlayerDialog(p));
        MenuItem ban = new MenuItem("Ban…");
        ban.setOnAction(ev -> banPlayerDialog(p));
        MenuItem op = new MenuItem("Op (toggle)");
        op.setOnAction(ev -> rpcVoid("cmd_op", "uuid", p.uuid.toString(), "on", true));
        MenuItem deop = new MenuItem("Deop (toggle)");
        deop.setOnAction(ev -> rpcVoid("cmd_op", "uuid", p.uuid.toString(), "on", false));
        MenuItem white = new MenuItem("Whitelist add");
        white.setOnAction(ev -> rpcVoid("cmd_whitelist", "uuid", p.uuid.toString(), "on", true));
        MenuItem unwhite = new MenuItem("Whitelist remove");
        unwhite.setOnAction(ev -> rpcVoid("cmd_whitelist", "uuid", p.uuid.toString(), "on", false));
        MenuItem kill = new MenuItem("Kill player (in-game)");
        kill.setOnAction(ev -> runVanillaCommand("kill " + p.name));

        menu.getItems().addAll(info, new SeparatorMenuItem(),
                tp, msg, new SeparatorMenuItem(),
                kick, ban, new SeparatorMenuItem(),
                op, deop, white, unwhite, new SeparatorMenuItem(),
                kill);
        menu.show(canvas, screenX, screenY);
    }

    private void showPlayerInfoDialog(PlayerEntry pe) {
        PlayerMarker p = pe.marker;
        long now = System.currentTimeMillis();
        // Pnl-52 (2026-04-26): show online/offline duration so the
        // user can see how long a player has been playing this
        // session (online) or how long ago they logged off (offline).
        // onlineSinceEpochMs comes straight from the bridge's
        // PlayersSnapshot; lastSeenMs is panel-side bookkeeping.
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "UUID:        %s%n" +
                "Dimension:   %s%n" +
                "Position:    %.1f, %.1f, %.1f%n" +
                "Yaw:         %.1f%n" +
                "Health:      %d / 20%n" +
                "Food:        %d / 20%n" +
                "Ping:        %d ms%n" +
                "Game mode:   %s%n",
                p.uuid, p.dimensionId, p.x, p.y, p.z, p.yaw,
                p.health, p.food, p.pingMs, p.gameMode));
        sb.append("Status:      ").append(pe.online ? "ONLINE" : "OFFLINE").append("\n");
        if (pe.online) {
            if (p.onlineSinceEpochMs > 0) {
                long onlineMs = now - p.onlineSinceEpochMs;
                sb.append("Online for:  ").append(fmtDuration(onlineMs)).append("\n");
            }
        } else {
            long offlineMs = now - pe.lastSeenMs;
            sb.append("Offline for: ").append(fmtDuration(offlineMs)).append("\n");
            sb.append("Last seen:   ").append(new java.util.Date(pe.lastSeenMs)).append("\n");
        }
        long sessionMs = now - pe.firstSeenMs;
        sb.append("First seen:  ").append(fmtDuration(sessionMs)).append(" ago (this panel session)");
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Player info");
        a.setHeaderText(p.name + (pe.online ? "" : "  [OFFLINE]"));
        a.setContentText(sb.toString());
        a.show();
    }

    private void teleportPlayerDialog(PlayerMarker p) {
        TextInputDialog d = new TextInputDialog(String.format("%.0f %.0f %.0f", p.x, p.y, p.z));
        d.setTitle("Teleport " + p.name);
        d.setHeaderText("Teleport " + p.name + " to coordinates");
        d.setContentText("x y z (space-separated):");
        Optional<String> result = d.showAndWait();
        result.ifPresent(text -> {
            String[] parts = text.trim().split("\\s+");
            if (parts.length < 3) return;
            try {
                double tx = Double.parseDouble(parts[0]);
                double ty = Double.parseDouble(parts[1]);
                double tz = Double.parseDouble(parts[2]);
                rpcVoid("cmd_tp",
                        "playerUuid", p.uuid.toString(),
                        "x", tx, "y", ty, "z", tz,
                        "dim", p.dimensionId);
            } catch (NumberFormatException ignored) {}
        });
    }

    private void chatMessageDialog(PlayerMarker p) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Send message to " + p.name);
        d.setHeaderText("Send chat message to " + p.name);
        d.setContentText("Message:");
        Optional<String> result = d.showAndWait();
        result.ifPresent(text -> {
            if (text.isEmpty()) return;
            // /tellraw <name> {"text":"..."} — keeps formatting intact.
            String safe = text.replace("\\", "\\\\").replace("\"", "\\\"");
            runVanillaCommand("tellraw " + p.name + " {\"text\":\"" + safe + "\"}");
        });
    }

    private void kickPlayerDialog(PlayerMarker p) {
        TextInputDialog d = new TextInputDialog("Disconnected by admin.");
        d.setTitle("Kick " + p.name);
        d.setHeaderText("Kick " + p.name);
        d.setContentText("Reason:");
        Optional<String> r = d.showAndWait();
        r.ifPresent(reason -> rpcVoid("cmd_kick", "uuid", p.uuid.toString(), "reason", reason));
    }

    private void banPlayerDialog(PlayerMarker p) {
        TextInputDialog d = new TextInputDialog("Banned by admin.");
        d.setTitle("Ban " + p.name);
        d.setHeaderText("Ban " + p.name + " (permanent)");
        d.setContentText("Reason:");
        Optional<String> r = d.showAndWait();
        r.ifPresent(reason -> rpcVoid("cmd_ban", "uuid", p.uuid.toString(), "reason", reason));
    }

    private void rpcVoid(String op, Object... kvPairs) {
        if (pipeClient == null || pipeClient.isClosed()) return;
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            args.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        try {
            pipeClient.sendRequest(op, args);
        } catch (Exception e) {
            LOG.warn("rpc {} failed: {}", op, e.getMessage());
        }
    }

    private void runVanillaCommand(String command) {
        rpcVoid("cmd_run", "command", command);
    }

    // ---- chunk_tile request scheduling --------------------------------

    private void noteInteraction() {
        lastInteractionMs.set(System.currentTimeMillis());
        scheduleTileRequests();
    }

    /**
     * Defer chunk_tile requests until the user pauses (drag/zoom debounce).
     * On heavy panning we'd otherwise issue thousands of RPCs per second
     * for chunks that the user is just passing over — wasted bandwidth and
     * unnecessary load on the bridge tick thread.
     */
    private void scheduleTileRequests() {
        // Pnl-57: atomic check-and-set. Without this, FX-thread
        // pan/zoom callers and the recursive debounce-thread caller
        // could both pass the gate, spawning duplicate daemons.
        if (!requestTaskPending.compareAndSet(false, true)) return;
        long fireAt = lastInteractionMs.get() + REQUEST_DEBOUNCE_MS;
        long delay = Math.max(REQUEST_DEBOUNCE_MS, fireAt - System.currentTimeMillis());
        Thread t = new Thread(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) { return; }
            // If another interaction came in during the wait, push the
            // task forward instead of firing now.
            long now = System.currentTimeMillis();
            long last = lastInteractionMs.get();
            if (now - last < REQUEST_DEBOUNCE_MS) {
                requestTaskPending.set(false);
                scheduleTileRequests();
                return;
            }
            requestTaskPending.set(false);
            Platform.runLater(this::requestVisibleTiles);
        }, "FatherEye-MapDebounce");
        t.setDaemon(true);
        t.start();
    }

    private void requestVisibleTiles() {
        if (pipeClient == null || pipeClient.isClosed()) return;
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        // Visible chunk range plus 1 chunk of padding so the edges fill in.
        double viewBlocksX = w / zoom;
        double viewBlocksZ = h / zoom;
        int minCx = (int) Math.floor((centerX - viewBlocksX / 2.0) / 16.0) - 1;
        int maxCx = (int) Math.ceil((centerX + viewBlocksX / 2.0) / 16.0) + 1;
        int minCz = (int) Math.floor((centerZ - viewBlocksZ / 2.0) / 16.0) - 1;
        int maxCz = (int) Math.ceil((centerZ + viewBlocksZ / 2.0) / 16.0) + 1;
        int budget = MAX_TILE_REQUESTS_PER_FRAME;

        // Prefer center-out fan-out: start near (centerX, centerZ) and
        // spiral outward so the visible centre fills first.
        int centerCx = (int) Math.floor(centerX / 16.0);
        int centerCz = (int) Math.floor(centerZ / 16.0);
        int maxRadius = Math.max(maxCx - centerCx, Math.max(centerCx - minCx,
                Math.max(maxCz - centerCz, centerCz - minCz)));
        outer:
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int cz = centerCz - radius; cz <= centerCz + radius; cz++) {
                for (int cx = centerCx - radius; cx <= centerCx + radius; cx++) {
                    if (radius != 0
                            && Math.abs(cx - centerCx) != radius
                            && Math.abs(cz - centerCz) != radius) continue;
                    if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;
                    long key = MapData.chunkKey(cx, cz);
                    if (tileImages.containsKey(key) || requested.containsKey(key)) continue;
                    // Pnl-68: also skip recently-rejected chunks
                    // (mirrors onChunksSnapshot behaviour) so a
                    // pan-induced redraw doesn't immediately re-fire
                    // a chunk the bridge just sentineled.
                    Long rejectedAt = rejectedChunks.get(key);
                    if (rejectedAt != null
                            && (System.currentTimeMillis() - rejectedAt) < REJECTED_RETRY_MS) continue;
                    requested.put(key, System.currentTimeMillis());
                    requestChunk(cx, cz);
                    if (--budget <= 0) break outer;
                }
            }
        }
        // Pnl-52: keep the toolbar progress label in sync.
        updateLoadingProgress();
    }

    private void requestChunk(int cx, int cz) {
        long key = MapData.chunkKey(cx, cz);
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("dim", currentDim);
            args.put("cx", cx);
            args.put("cz", cz);
            // Pnl-67 (2026-04-27): timeout the RPC after 30 s. Without
            // this, a request lost in transit (bridge dropped it,
            // pipe-buffer overflow, server tick stall) would sit in
            // `requested` forever and the loading bar would never
            // reach 100 %. On timeout the future completes
            // exceptionally; whenComplete sees err != null and
            // failChunkRequest moves the chunk to rejectedChunks so
            // the 5 s TTL retry kicks in.
            pipeClient.sendRequest("chunk_tile", args)
                    .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((node, err) -> {
                if (err != null) { failChunkRequest(key); return; }
                if (node == null) { failChunkRequest(key); return; }
                JsonNode result = node.get("result");
                if (result == null) { failChunkRequest(key); return; }
                try {
                    byte[] biomes = b64ToBytes(result.path("biomes"));
                    short[] heights = nodeToShorts(result.path("heightMap"));
                    int[] surface = nodeToInts(result.path("surfaceArgb"));
                    if (biomes == null || biomes.length != 256
                            || heights == null || heights.length != 256
                            || surface == null || surface.length != 256) {
                        failChunkRequest(key);
                        return;
                    }
                    // Pnl-68 (Agent 3 defensive): use the originally-
                    // requested (cx, cz) as the canonical key for
                    // bookkeeping. The bridge always echoes back
                    // the same coordinates (verified in
                    // ChunkTileHandler), but if a bug ever caused
                    // the response to carry different coords, the
                    // panel's `requested.remove(chunkKey(rcx, rcz))`
                    // would target a different key than the original
                    // `requested.put(chunkKey(cx, cz))` and leak
                    // forever.
                    String dim = result.path("dimensionId").asText(currentDim);
                    onChunkTileResponse(cx, cz, new ChunkTile(dim, cx, cz, biomes, heights, surface));
                } catch (Throwable t) {
                    LOG.warn("decode chunk_tile failed", t);
                    failChunkRequest(key);
                }
            });
        } catch (Throwable t) {
            LOG.warn("send chunk_tile failed", t);
            failChunkRequest(key);
        }
    }

    /**
     * Pnl-52: single point-of-update for failed/abandoned chunk
     * requests. Removes from the {@link #requested} set on the FX
     * thread and refreshes the loading-progress label so the user
     * doesn't see a stuck "Loading X chunks..." count.
     */
    /**
     * Pnl-68 (2026-04-27): scan {@link #requested} for entries
     * older than {@link #REQUEST_TIMEOUT_MS} and treat them as
     * timed-out (move to {@link #rejectedChunks} via
     * {@link #failChunkRequest}). Runs every 5 s on
     * {@code FatherEye-RequestSweep}, off the FX thread.
     */
    private void sweepStaleRequests() {
        try {
            long cutoff = System.currentTimeMillis() - REQUEST_TIMEOUT_MS;
            int swept = 0;
            for (java.util.Map.Entry<Long, Long> e : requested.entrySet()) {
                if (e.getValue() < cutoff) {
                    failChunkRequest(e.getKey());
                    swept++;
                }
            }
            if (swept > 0) {
                LOG.warn("force-failed {} stale chunk_tile requests (older than {} ms)", swept, REQUEST_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            LOG.warn("request sweep failed", t);
        }
    }

    private void failChunkRequest(long key) {
        // Pnl-68 (2026-04-27): do the data-structure mutations
        // SYNCHRONOUSLY (both maps are concurrent and thread-safe).
        // The previous Pnl-67 cut wrapped everything in
        // Platform.runLater, which queued behind a saturated FX
        // thread on long sessions and left 594 entries stuck in
        // `requested` for 40+ minutes despite the 30 s orTimeout
        // firing. With synchronous removes the bookkeeping is
        // immediate; only updateLoadingProgress (which mutates
        // JavaFX labels) still goes through runLater.
        requested.remove(key);
        rejectedChunks.put(key, System.currentTimeMillis());
        Platform.runLater(this::updateLoadingProgress);
    }

    private static byte[] b64ToBytes(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isBinary()) {
            try { return n.binaryValue(); } catch (Exception e) { return null; }
        }
        if (n.isTextual()) {
            try { return java.util.Base64.getDecoder().decode(n.asText()); } catch (Exception e) { return null; }
        }
        if (n.isArray()) {
            byte[] out = new byte[n.size()];
            for (int i = 0; i < n.size(); i++) out[i] = (byte) n.get(i).asInt(0);
            return out;
        }
        return null;
    }

    private static short[] nodeToShorts(JsonNode n) {
        if (n == null || !n.isArray()) return null;
        short[] out = new short[n.size()];
        for (int i = 0; i < n.size(); i++) out[i] = (short) n.get(i).asInt(0);
        return out;
    }

    private static int[] nodeToInts(JsonNode n) {
        if (n == null || !n.isArray()) return null;
        int[] out = new int[n.size()];
        for (int i = 0; i < n.size(); i++) out[i] = n.get(i).asInt(0);
        return out;
    }

    /**
     * Pnl-52 (2026-04-26): a PlayerMarker plus session bookkeeping so
     * the map can render offline players greyed out with an offline
     * duration label. Created on first sighting; updated on every
     * subsequent sighting; marked offline (online=false) when a
     * players_topic snapshot arrives without this UUID.
     */
    static final class PlayerEntry {
        // Pnl-52 (audit fix): volatile because IPC thread mutates and
        // FX thread reads. Boolean and long single-word atomicity is
        // x64-only without volatile; volatile gives JLS portability.
        volatile PlayerMarker marker;
        /** Wall time when we first saw this player in this panel session. */
        final long firstSeenMs;
        /** Wall time of the most recent snapshot containing this player. */
        volatile long lastSeenMs;
        /** True iff the latest players_topic snapshot included this UUID. */
        volatile boolean online;
        PlayerEntry(PlayerMarker m, long now) {
            this.marker = m;
            this.firstSeenMs = now;
            this.lastSeenMs = now;
            this.online = true;
        }
    }

    /**
     * Pnl-52: format a millisecond duration as a compact human label
     * for offline / online indicators. e.g. "2h 5m", "37m", "12s".
     * Caps days at the largest unit so very long offlines become
     * "3d 4h" not "75h 10m".
     */
    static String fmtDuration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m";
        long d = h / 24;
        return d + "d " + (h % 24) + "h";
    }
}
