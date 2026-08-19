package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.adventure.event.EventBoundaryProviderRegistry;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import dev.buizz.cobbleventure.adventure.event.EventMovementFailureReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Immutable explicit index for CVES region and anchor boundary IDs. */
final class EventBoundaryCatalog {
    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Box {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("event boundary box의 min은 max 이하여야 합니다.");
            }
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
        }
    }

    record Boundary(String id, String dimension, Box box) {}

    private final List<Boundary> regions;
    private final List<Boundary> anchors;

    private EventBoundaryCatalog(List<Boundary> regions, List<Boundary> anchors) {
        this.regions = List.copyOf(regions);
        this.anchors = List.copyOf(anchors);
    }

    static EventBoundaryCatalog parse(JsonObject root) {
        if (!root.has("schema_version") || root.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("event boundary schema_version은 1이어야 합니다.");
        }
        return new EventBoundaryCatalog(
            boundaries(root, "regions"), boundaries(root, "anchors")
        );
    }

    EventBoundaryProviderRegistry.Snapshot snapshot(
        String dimension, int x, int y, int z
    ) {
        return new EventBoundaryProviderRegistry.Snapshot(
            active(regions, dimension, x, y, z),
            active(anchors, dimension, x, y, z),
            Set.of(),
            Set.of(dimension)
        );
    }

    static EventLocationResolverRegistry.Resolution resolveAnchor(
        EventBoundaryCatalog catalog, EventLocationRef.Resource destination
    ) {
        if (catalog == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        if (destination.anchor() != null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        Boundary anchor = catalog.anchors.stream()
            .filter(candidate -> candidate.id().equals(destination.resourceId()))
            .findFirst().orElse(null);
        if (anchor == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_NOT_FOUND
            );
        }
        Box box = anchor.box();
        return EventLocationResolverRegistry.Resolution.resolved(
            new EventLocationResolverRegistry.ResolvedLocation(
                anchor.dimension(),
                midpoint(box.minX(), box.maxX()) + 0.5D,
                midpoint(box.minY(), box.maxY()),
                midpoint(box.minZ(), box.maxZ()) + 0.5D,
                null, null
            )
        );
    }

    private static double midpoint(int minimum, int maximum) {
        return minimum + ((long) maximum - minimum) / 2.0D;
    }

    private static Set<String> active(
        List<Boundary> boundaries, String dimension, int x, int y, int z
    ) {
        Set<String> result = new HashSet<>();
        for (Boundary boundary : boundaries) {
            if (boundary.dimension().equals(dimension) && boundary.box().contains(x, y, z)) {
                result.add(boundary.id());
            }
        }
        return Set.copyOf(result);
    }

    private static List<Boundary> boundaries(JsonObject root, String field) {
        JsonElement values = root.get(field);
        if (values == null || !values.isJsonArray()) {
            throw new IllegalArgumentException("event boundary " + field + " 배열이 필요합니다.");
        }
        List<Boundary> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : values.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("event boundary 항목은 object여야 합니다: " + field);
            }
            JsonObject value = element.getAsJsonObject();
            String id = resourceId(value, "id");
            String dimension = resourceId(value, "dimension");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("중복 event boundary ID: " + field + "/" + id);
            }
            JsonObject box = value.getAsJsonObject("box");
            if (box == null) {
                throw new IllegalArgumentException("event boundary box가 필요합니다: " + id);
            }
            result.add(new Boundary(id, dimension, new Box(
                integer(box, "min_x"), integer(box, "min_y"), integer(box, "min_z"),
                integer(box, "max_x"), integer(box, "max_y"), integer(box, "max_z")
            )));
        }
        return List.copyOf(result);
    }

    private static String resourceId(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString()
            || ResourceLocation.tryParse(element.getAsString()) == null) {
            throw new IllegalArgumentException("event boundary " + field + "는 리소스 ID여야 합니다.");
        }
        return element.getAsString();
    }

    private static int integer(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("event boundary " + field + "는 정수여야 합니다.");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(
                "event boundary " + field + "는 정수여야 합니다.", error
            );
        }
    }
}
