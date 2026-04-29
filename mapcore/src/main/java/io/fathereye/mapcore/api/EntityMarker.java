package io.fathereye.mapcore.api;

import java.util.Objects;

public final class EntityMarker {

    public enum Kind { PLAYER, HOSTILE, PASSIVE, ITEM, OTHER }

    public final int entityId;
    public final String typeId;       // e.g. "minecraft:zombie"
    public final String modId;        // e.g. "minecraft", "thaumaturgy"
    public final Kind kind;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;

    public EntityMarker(int entityId, String typeId, String modId, Kind kind,
                        double x, double y, double z, float yaw) {
        this.entityId = entityId;
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.modId = Objects.requireNonNull(modId, "modId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }
}
