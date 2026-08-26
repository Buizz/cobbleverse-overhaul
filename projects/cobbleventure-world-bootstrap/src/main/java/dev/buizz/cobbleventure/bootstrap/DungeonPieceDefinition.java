package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Authored NBT piece metadata shared by dungeon planning, preview, and placement. */
record DungeonPieceDefinition(
    String id,
    String structure,
    String role,
    BlockPos size,
    int weight,
    int minimumPerPlan,
    int maximumPerPlan,
    String placementScope,
    Set<String> forbiddenAdjacentTags,
    boolean allowRotation,
    Set<String> tags,
    List<Connector> connectors,
    List<Marker> markers
) {
    private static final List<String> ROLES = List.of(
        "start", "room", "corridor", "junction", "dead_end", "support",
        "treasure", "boss", "exit"
    );
    private static final List<String> MARKER_KINDS = List.of(
        "entry", "exit", "encounter", "boss", "loot", "healing_station",
        "gate", "checkpoint", "wild_spawn", "objective", "trace"
    );
    private static final List<String> PLACEMENT_SCOPES = List.of(
        "any", "critical_path", "branch"
    );

    static Map<String, DungeonPieceDefinition> loadAll(ResourceManager resources) {
        Map<String, DungeonPieceDefinition> definitions = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files = resources.listResources(
            "dungeon_pieces", location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            DungeonPieceDefinition definition;
            try (Reader reader = file.getValue().openAsReader()) {
                definition = parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException(
                    "Invalid dungeon piece definition: " + file.getKey(), error
                );
            }
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException(
                    "Duplicate dungeon piece ID: " + definition.id()
                );
            }
        }
        return Map.copyOf(definitions);
    }

    static DungeonPieceDefinition parse(JsonObject root) {
        int schemaVersion = requiredInt(root, "schema_version");
        if (schemaVersion != 1) {
            throw new IllegalStateException(
                "Unsupported dungeon piece schema version: " + schemaVersion
            );
        }
        String id = resourceId(root, "piece_id");
        String structure = resourceId(root, "structure");
        String role = enumValue(root, "role", ROLES);
        BlockPos size = positivePosition(root, "size");
        if (size.getX() > 128 || size.getY() > 128 || size.getZ() > 128) {
            throw new IllegalStateException("Dungeon piece is larger than 128 blocks: " + id);
        }
        int weight = requiredInt(root, "weight");
        if (weight < 1 || weight > 1000) {
            throw new IllegalStateException("Invalid dungeon piece weight: " + id);
        }
        int minimumPerPlan = root.has("min_per_plan")
            ? requiredInt(root, "min_per_plan") : 0;
        int maximumPerPlan = root.has("max_per_plan")
            ? requiredInt(root, "max_per_plan") : 256;
        if (minimumPerPlan < 0 || maximumPerPlan < 1
            || minimumPerPlan > maximumPerPlan || maximumPerPlan > 256) {
            throw new IllegalStateException(
                "Invalid dungeon piece usage limits: " + id
            );
        }
        String placementScope = root.has("placement_scope")
            ? enumValue(root, "placement_scope", PLACEMENT_SCOPES) : "any";
        Set<String> forbiddenAdjacentTags = new HashSet<>();
        JsonArray forbiddenTags = root.has("forbid_adjacent_tags")
            ? requiredArray(root, "forbid_adjacent_tags") : new JsonArray();
        for (JsonElement element : forbiddenTags) {
            String tag = element.getAsString();
            if (ResourceLocation.tryParse(tag) == null || !forbiddenAdjacentTags.add(tag)) {
                throw new IllegalStateException(
                    "Invalid forbidden adjacent tag: " + id + " -> " + tag
                );
            }
        }

        Set<String> tags = new HashSet<>();
        for (JsonElement element : requiredArray(root, "tags")) {
            String tag = element.getAsString();
            if (ResourceLocation.tryParse(tag) == null || !tags.add(tag)) {
                throw new IllegalStateException("Invalid dungeon piece tag: " + id + " -> " + tag);
            }
        }

        List<Connector> connectors = new ArrayList<>();
        Set<String> connectorIds = new HashSet<>();
        for (JsonElement element : requiredArray(root, "connectors")) {
            JsonObject value = element.getAsJsonObject();
            String connectorId = requiredString(value, "id");
            if (!connectorIds.add(connectorId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon piece connector: " + id + " -> " + connectorId
                );
            }
            BlockPos position = boundedPosition(value, "position", size, id);
            Direction facing;
            try {
                facing = Direction.byName(requiredString(value, "facing"));
            } catch (RuntimeException error) {
                facing = null;
            }
            if (facing == null || facing.getAxis().isVertical()) {
                throw new IllegalStateException(
                    "Dungeon piece connector must face horizontally: " + id + " -> " + connectorId
                );
            }
            if (!onFacingBoundary(position, size, facing)) {
                throw new IllegalStateException(
                    "Dungeon piece connector is not on its facing boundary: "
                        + id + " -> " + connectorId
                );
            }
            Set<String> connectorTags = new HashSet<>();
            for (JsonElement tagElement : requiredArray(value, "tags")) {
                String tag = tagElement.getAsString();
                if (ResourceLocation.tryParse(tag) == null || !connectorTags.add(tag)) {
                    throw new IllegalStateException(
                        "Invalid dungeon connector tag: " + id + " -> " + connectorId
                    );
                }
            }
            connectors.add(new Connector(
                connectorId,
                position,
                facing,
                resourceId(value, "socket"),
                Set.copyOf(connectorTags)
            ));
        }
        if (connectors.isEmpty()) {
            throw new IllegalStateException("Connectable dungeon piece has no connectors: " + id);
        }

        List<Marker> markers = new ArrayList<>();
        Set<String> markerIds = new HashSet<>();
        for (JsonElement element : requiredArray(root, "markers")) {
            JsonObject value = element.getAsJsonObject();
            String markerId = requiredString(value, "id");
            if (!markerIds.add(markerId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon piece marker: " + id + " -> " + markerId
                );
            }
            String kind = enumValue(value, "kind", MARKER_KINDS);
            String reference = value.has("reference")
                ? requiredString(value, "reference") : null;
            markers.add(new Marker(
                markerId,
                kind,
                boundedPosition(value, "position", size, id),
                reference
            ));
        }
        requireRoleMarker(id, role, markers);

        return new DungeonPieceDefinition(
            id,
            structure,
            role,
            size,
            weight,
            minimumPerPlan,
            maximumPerPlan,
            placementScope,
            Set.copyOf(forbiddenAdjacentTags),
            requiredBoolean(root, "allow_rotation"),
            Set.copyOf(tags),
            List.copyOf(connectors),
            List.copyOf(markers)
        );
    }

    boolean allowsPlacement(boolean criticalPath) {
        return placementScope.equals("any")
            || placementScope.equals(criticalPath ? "critical_path" : "branch");
    }

    boolean allowsAdjacentTo(DungeonPieceDefinition other) {
        return forbiddenAdjacentTags.stream().noneMatch(other.tags()::contains)
            && other.forbiddenAdjacentTags().stream().noneMatch(tags::contains);
    }

    private static void requireRoleMarker(String id, String role, List<Marker> markers) {
        String requiredKind = switch (role) {
            case "start" -> "entry";
            case "boss" -> "boss";
            case "exit" -> "exit";
            default -> null;
        };
        if (requiredKind == null) return;
        long count = markers.stream().filter(marker -> marker.kind().equals(requiredKind)).count();
        if (count != 1L) {
            throw new IllegalStateException(
                "Dungeon " + role + " piece requires exactly one " + requiredKind
                    + " marker: " + id
            );
        }
    }

    private static boolean onFacingBoundary(BlockPos position, BlockPos size, Direction facing) {
        return switch (facing) {
            case NORTH -> position.getZ() == 0;
            case SOUTH -> position.getZ() == size.getZ() - 1;
            case WEST -> position.getX() == 0;
            case EAST -> position.getX() == size.getX() - 1;
            default -> false;
        };
    }

    private static BlockPos positivePosition(JsonObject value, String key) {
        BlockPos position = position(value, key);
        if (position.getX() < 1 || position.getY() < 1 || position.getZ() < 1) {
            throw new IllegalStateException("Dungeon piece size must be positive");
        }
        return position;
    }

    private static BlockPos boundedPosition(
        JsonObject value, String key, BlockPos size, String pieceId
    ) {
        BlockPos position = position(value, key);
        if (position.getX() < 0 || position.getY() < 0 || position.getZ() < 0
            || position.getX() >= size.getX() || position.getY() >= size.getY()
            || position.getZ() >= size.getZ()) {
            throw new IllegalStateException(
                "Dungeon piece position is outside its bounds: " + pieceId + " -> " + key
            );
        }
        return position;
    }

    private static BlockPos position(JsonObject value, String key) {
        JsonArray array = requiredArray(value, key);
        if (array.size() != 3) {
            throw new IllegalStateException("Dungeon piece position requires three values: " + key);
        }
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static String enumValue(JsonObject value, String key, List<String> allowed) {
        String result = requiredString(value, key);
        if (!allowed.contains(result)) {
            throw new IllegalStateException("Invalid dungeon piece " + key + ": " + result);
        }
        return result;
    }

    private static String resourceId(JsonObject value, String key) {
        String result = requiredString(value, key);
        if (ResourceLocation.tryParse(result) == null) {
            throw new IllegalStateException("Invalid resource ID for " + key + ": " + result);
        }
        return result;
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Dungeon piece string is missing: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalStateException("Dungeon piece string is empty: " + key);
        }
        return result;
    }

    private static int requiredInt(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Dungeon piece integer is missing: " + key);
        }
        return value.get(key).getAsInt();
    }

    private static boolean requiredBoolean(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalStateException("Dungeon piece boolean is missing: " + key);
        }
        return value.get(key).getAsBoolean();
    }

    private static JsonArray requiredArray(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalStateException("Dungeon piece array is missing: " + key);
        }
        return value.getAsJsonArray(key);
    }

    record Connector(
        String id,
        BlockPos position,
        Direction facing,
        String socket,
        Set<String> tags
    ) {}

    record Marker(String id, String kind, BlockPos position, String reference) {}
}
