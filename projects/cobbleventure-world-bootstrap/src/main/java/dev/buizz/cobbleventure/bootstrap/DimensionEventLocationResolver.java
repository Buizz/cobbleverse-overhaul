package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventMovementFailureReason;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Resolves only explicitly authored, dimension-global CVES arrival anchors. */
final class DimensionEventLocationResolver {
    record Anchor(int x, int y, int z, Float yaw, Float pitch) {}

    record Catalog(Map<String, Map<String, Anchor>> dimensions) {
        Catalog {
            LinkedHashMap<String, Map<String, Anchor>> copy = new LinkedHashMap<>();
            dimensions.forEach((dimension, anchors) ->
                copy.put(dimension, Map.copyOf(anchors))
            );
            dimensions = Map.copyOf(copy);
        }
    }

    private DimensionEventLocationResolver() {}

    static Catalog parse(JsonObject root) {
        if (!root.has("schema_version") || root.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("dimension anchor schema_version은 1이어야 합니다.");
        }
        if (!root.has("dimensions") || !root.get("dimensions").isJsonArray()) {
            throw new IllegalArgumentException("dimension anchor dimensions 배열이 필요합니다.");
        }
        Map<String, Map<String, Anchor>> dimensions = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("dimensions")) {
            JsonObject dimension = element.getAsJsonObject();
            String dimensionId = requiredResourceId(dimension, "id");
            JsonObject authoredAnchors = dimension.getAsJsonObject("anchors");
            if (authoredAnchors == null) {
                throw new IllegalArgumentException("dimension anchors 객체가 필요합니다: " + dimensionId);
            }
            Map<String, Anchor> anchors = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : authoredAnchors.entrySet()) {
                String anchorId = entry.getKey();
                if (!anchorId.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")) {
                    throw new IllegalArgumentException("올바르지 않은 dimension anchor ID: " + anchorId);
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                Anchor previous = anchors.put(anchorId, new Anchor(
                    requiredInt(value, "x"), requiredInt(value, "y"), requiredInt(value, "z"),
                    optionalAngle(value, "yaw", -180F, 180F),
                    optionalAngle(value, "pitch", -90F, 90F)
                ));
                if (previous != null) {
                    throw new IllegalArgumentException("중복 dimension anchor ID: " + anchorId);
                }
            }
            if (dimensions.put(dimensionId, anchors) != null) {
                throw new IllegalArgumentException("중복 dimension ID: " + dimensionId);
            }
        }
        return new Catalog(dimensions);
    }

    static EventLocationResolverRegistry.Resolution resolve(
        Catalog catalog, EventLocationRef.Resource destination
    ) {
        if (catalog == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        Map<String, Anchor> anchors = catalog.dimensions().get(destination.resourceId());
        if (anchors == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_NOT_FOUND
            );
        }
        if (destination.anchor() == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_REQUIRED
            );
        }
        Anchor anchor = anchors.get(destination.anchor());
        if (anchor == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        return EventLocationResolverRegistry.Resolution.resolved(
            new EventLocationResolverRegistry.ResolvedLocation(
                destination.resourceId(), anchor.x() + 0.5D, anchor.y(), anchor.z() + 0.5D,
                anchor.yaw(), anchor.pitch()
            )
        );
    }

    private static String requiredResourceId(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("dimension " + key + " 문자열이 필요합니다.");
        }
        String result = value.get(key).getAsString();
        if (ResourceLocation.tryParse(result) == null) {
            throw new IllegalArgumentException("올바르지 않은 dimension ID: " + result);
        }
        return result;
    }

    private static int requiredInt(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("dimension anchor " + key + " 정수가 필요합니다.");
        }
        try {
            double number = value.get(key).getAsDouble();
            if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "dimension anchor " + key + " 정수가 필요합니다."
                );
            }
            return (int) number;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("dimension anchor " + key + " 정수가 필요합니다.", error);
        }
    }

    private static Float optionalAngle(JsonObject value, String key, float minimum, float maximum) {
        if (!value.has(key)) return null;
        float angle = value.get(key).getAsFloat();
        if (!Float.isFinite(angle) || angle < minimum || angle > maximum) {
            throw new IllegalArgumentException(
                "dimension anchor " + key + " 범위는 " + minimum + ".." + maximum + "입니다."
            );
        }
        return angle;
    }
}
