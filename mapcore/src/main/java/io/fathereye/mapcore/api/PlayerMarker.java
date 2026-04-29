package io.fathereye.mapcore.api;

import java.util.Objects;
import java.util.UUID;

public final class PlayerMarker {

    public final UUID uuid;
    public final String name;
    public final String dimensionId;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final int health;          // 0..20
    public final int food;            // 0..20
    public final int pingMs;
    public final long onlineSinceEpochMs;
    public final String gameMode;     // "survival", "creative", "spectator", "adventure"

    public PlayerMarker(UUID uuid, String name, String dimensionId,
                        double x, double y, double z, float yaw,
                        int health, int food, int pingMs,
                        long onlineSinceEpochMs, String gameMode) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = Objects.requireNonNull(name, "name");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.health = health;
        this.food = food;
        this.pingMs = pingMs;
        this.onlineSinceEpochMs = onlineSinceEpochMs;
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
    }
}
