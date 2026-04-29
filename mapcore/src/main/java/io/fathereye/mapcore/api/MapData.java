package io.fathereye.mapcore.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of everything the renderer needs to draw a frame, scoped to the
 * {@link MapViewState#dimensionId current dimension}.
 *
 * <p>Field maps/lists are exposed read-only via accessor methods; the
 * underlying collections are unmodifiable wrappers, safe to share across
 * threads provided the caller does not subsequently mutate the originals.
 */
public final class MapData {

    private final Map<Long, ChunkTile> tilesByChunkKey;
    private final List<PlayerMarker> players;
    private final List<EntityMarker> entities;
    private final List<PointOfInterest> pois;

    public MapData(Map<Long, ChunkTile> tilesByChunkKey,
                   List<PlayerMarker> players,
                   List<EntityMarker> entities,
                   List<PointOfInterest> pois) {
        this.tilesByChunkKey = Collections.unmodifiableMap(
                new HashMap<>(Objects.requireNonNull(tilesByChunkKey, "tilesByChunkKey")));
        this.players  = Collections.unmodifiableList(Objects.requireNonNull(players, "players"));
        this.entities = Collections.unmodifiableList(Objects.requireNonNull(entities, "entities"));
        this.pois     = Collections.unmodifiableList(Objects.requireNonNull(pois, "pois"));
    }

    /** Pack chunk coords into a single 64-bit key for the tile map. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public Map<Long, ChunkTile> tilesByChunkKey() { return tilesByChunkKey; }
    public List<PlayerMarker> players()           { return players; }
    public List<EntityMarker> entities()          { return entities; }
    public List<PointOfInterest> pois()           { return pois; }

    public static MapData empty() {
        return new MapData(Collections.emptyMap(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }
}
