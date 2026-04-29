package io.fathereye.bridge.topic;

import java.util.ArrayList;
import java.util.List;

/** Wire payload for {@link Topics#PLAYERS}. */
public final class PlayersSnapshot {

    public long timestampMs;
    public List<PlayerEntry> players = new ArrayList<>();

    public PlayersSnapshot() {}

    public static final class PlayerEntry {
        public String uuid;
        public String name;
        public String dimensionId;
        public double x, y, z;
        public float yaw;
        public int health;
        public int food;
        public int pingMs;
        public long onlineSinceEpochMs;
        public String gameMode;

        public PlayerEntry() {}
    }
}
