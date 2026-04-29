package io.fathereye.mapcore.api;

import java.util.Objects;

public final class PointOfInterest {

    public final String id;          // stable id, e.g. "thaumaturgy:node@-100,64,200"
    public final String label;
    public final String iconKey;     // texture lookup key, registered with MapTextureLoader
    public final String dimensionId;
    public final double x;
    public final double y;
    public final double z;
    public final int tintArgb;       // 0xFFFFFFFF = no tint

    public PointOfInterest(String id, String label, String iconKey,
                           String dimensionId, double x, double y, double z, int tintArgb) {
        this.id = Objects.requireNonNull(id, "id");
        this.label = label == null ? "" : label;
        this.iconKey = Objects.requireNonNull(iconKey, "iconKey");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.x = x;
        this.y = y;
        this.z = z;
        this.tintArgb = tintArgb;
    }
}
