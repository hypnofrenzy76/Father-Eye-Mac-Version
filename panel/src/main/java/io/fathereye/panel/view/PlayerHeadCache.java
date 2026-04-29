package io.fathereye.panel.view;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pnl-32 (2026-04-26): caches Minecraft player face textures so the map
 * can render real player heads instead of a stylised Steve placeholder.
 *
 * <p>Uses <a href="https://crafatar.com/">Crafatar</a> as the avatar
 * service — `https://crafatar.com/avatars/&lt;uuid&gt;?size=20&amp;overlay`
 * returns a pre-cropped 20×20 PNG of the head with the hat overlay
 * composited on, exactly what the map wants. For unknown / offline
 * (LAN) UUIDs Crafatar falls back to the default Steve, so we always
 * get something usable.
 *
 * <p>Threading: HTTP fetches run on a small daemon executor (Crafatar
 * rate-limits at ~100 req / minute / IP, plenty for a typical server).
 * Successful fetches publish a JavaFX {@link Image} into the cache and
 * fire {@code onLoaded.run()} on the FX thread so the map can repaint.
 *
 * <p>Failure handling: a fetch that fails is recorded with a 60-second
 * cool-down before we'll retry, so a flaky network or rate-limit doesn't
 * keep the executor saturated.
 */
public final class PlayerHeadCache {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-PlayerHead");
    private static final int HEAD_SIZE = 20;
    private static final long FAIL_RETRY_NANOS = 60_000_000_000L; // 60s
    /**
     * Pnl-37: switched from Crafatar (UUID-only) to mc-heads.net which
     * accepts a player NAME and resolves it server-side. Crafatar returned
     * the default-Steve fallback for offline-mode/LAN UUIDs because those
     * are deterministic hashes not registered with Mojang. mc-heads.net
     * looks up by the player's real Minecraft username, fetching the
     * actual skin even when the server reports an offline UUID. URL
     * pattern accepts both UUID and name; we always pass name.
     */
    private static final String HEAD_URL =
            System.getProperty("fathereye.headUrl",
                    "https://mc-heads.net/avatar/%s/" + HEAD_SIZE + "/nohelm.png");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "FatherEye-PlayerHead");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<UUID, Image> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> failedAtNanos = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /** Returns the cached head image or null. */
    public Image get(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    /**
     * Kick off a head fetch keyed by UUID, looked up via player NAME
     * (which mc-heads.net accepts even for offline / LAN servers whose
     * UUIDs Mojang doesn't know). {@code onLoaded} fires on the FX thread
     * when the head becomes available.
     */
    public void requestLoad(UUID uuid, String name, Runnable onLoaded) {
        if (uuid == null) return;
        if (cache.containsKey(uuid)) return;
        if (inFlight.contains(uuid)) return;
        Long failedAt = failedAtNanos.get(uuid);
        if (failedAt != null && System.nanoTime() - failedAt < FAIL_RETRY_NANOS) return;
        if (!inFlight.add(uuid)) return;
        final String key = (name != null && !name.isEmpty() && !"?".equals(name))
                ? name : uuid.toString().replace("-", "");
        pool.submit(() -> {
            try {
                Image img = fetch(key);
                if (img != null && !img.isError()) {
                    cache.put(uuid, img);
                    failedAtNanos.remove(uuid);
                    if (onLoaded != null) Platform.runLater(onLoaded);
                } else {
                    failedAtNanos.put(uuid, System.nanoTime());
                }
            } catch (Throwable t) {
                LOG.debug("head fetch failed for {} ({}): {}", name, uuid, t.getMessage());
                failedAtNanos.put(uuid, System.nanoTime());
            } finally {
                inFlight.remove(uuid);
            }
        });
    }

    /** Backward-compatible signature; uses UUID-only lookup. */
    public void requestLoad(UUID uuid, Runnable onLoaded) {
        requestLoad(uuid, null, onLoaded);
    }

    private Image fetch(String identifier) throws Exception {
        String url = String.format(HEAD_URL, java.net.URLEncoder.encode(identifier, "UTF-8"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "FatherEye-Panel/0.3.0-mac.1 (+https://github.com/hypnofrenzy76/Father-Eye-Mac-Version)")
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            return null;
        }
        byte[] png = resp.body();
        if (png == null || png.length < 8) return null;
        return new Image(new ByteArrayInputStream(png), HEAD_SIZE, HEAD_SIZE, true, false);
    }

    public void shutdown() {
        pool.shutdownNow();
        try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    public int size() { return cache.size(); }
}
