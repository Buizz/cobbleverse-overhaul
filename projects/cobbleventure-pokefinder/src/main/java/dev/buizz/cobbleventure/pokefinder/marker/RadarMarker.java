package dev.buizz.cobbleventure.pokefinder.marker;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Server-authored marker data shared by snapshots and the client renderer. */
public record RadarMarker(
    ResourceLocation id,
    RadarMarkerType type,
    ResourceLocation dimension,
    Vec3 position,
    String label,
    ResourceLocation icon,
    int priority,
    RadarMarkerState state,
    String areaId,
    double localRange,
    boolean edgeTracking
) {
    public RadarMarker {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(areaId, "areaId");
        if (!Double.isFinite(localRange) || localRange <= 0.0D) {
            throw new IllegalArgumentException("localRange must be finite and positive");
        }
    }
}
