package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Web-authored piece placement graph loaded from a dungeon plan data resource. */
record DungeonAuthoredPlanDefinition(
    String id,
    long seed,
    BlockPos bounds,
    List<AuthoredPlacement> placements,
    List<DungeonPiecePlan.Link> links
) {
    static Map<String, DungeonAuthoredPlanDefinition> loadAll(ResourceManager resources) {
        Map<String, DungeonAuthoredPlanDefinition> definitions = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files = resources.listResources(
            "dungeon_plans", location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            DungeonAuthoredPlanDefinition definition;
            try (Reader reader = file.getValue().openAsReader()) {
                definition = parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException(
                    "Invalid authored dungeon plan: " + file.getKey(), error
                );
            }
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException(
                    "Duplicate authored dungeon plan ID: " + definition.id()
                );
            }
        }
        return Map.copyOf(definitions);
    }

    static DungeonAuthoredPlanDefinition parse(JsonObject root) {
        if (requiredInt(root, "schema_version") != 1) {
            throw new IllegalStateException("Unsupported authored dungeon plan version");
        }
        String id = resourceId(root, "plan_id");
        BlockPos bounds = positivePosition(root, "bounds");
        long seed = root.has("seed") ? root.get("seed").getAsLong() : 0L;
        List<AuthoredPlacement> placements = new ArrayList<>();
        JsonArray configuredPlacements = requiredArray(root, "placements");
        if (configuredPlacements.size() < 3) {
            throw new IllegalStateException(
                "Authored dungeon plan requires at least three pieces: " + id
            );
        }
        for (JsonElement element : configuredPlacements) {
            JsonObject placement = element.getAsJsonObject();
            placements.add(new AuthoredPlacement(
                resourceId(placement, "piece_id"),
                position(placement, "origin"),
                rotation(placement),
                requiredBoolean(placement, "critical_path")
            ));
        }
        List<DungeonPiecePlan.Link> links = new ArrayList<>();
        for (JsonElement element : requiredArray(root, "links")) {
            JsonObject link = element.getAsJsonObject();
            links.add(new DungeonPiecePlan.Link(
                requiredInt(link, "from_index"),
                requiredString(link, "from_connector"),
                requiredInt(link, "to_index"),
                requiredString(link, "to_connector"),
                requiredBoolean(link, "critical_path")
            ));
        }
        return new DungeonAuthoredPlanDefinition(
            id, seed, bounds, List.copyOf(placements), List.copyOf(links)
        );
    }

    DungeonPiecePlan toPlan(Map<String, DungeonPieceDefinition> pieces) {
        List<DungeonPiecePlan.Placement> planned = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            AuthoredPlacement authored = placements.get(index);
            DungeonPieceDefinition piece = pieces.get(authored.pieceId());
            if (piece == null) {
                throw new IllegalStateException(
                    "Authored dungeon plan references missing piece: "
                        + id + " -> " + authored.pieceId()
                );
            }
            Bounds transformed = transformedBounds(piece.size(), authored.rotation());
            BlockPos minimum = authored.origin().offset(transformed.minimum());
            planned.add(new DungeonPiecePlan.Placement(
                index, piece.id(), piece.role(), authored.origin(), authored.rotation(),
                minimum, transformed.size(), authored.criticalPath()
            ));
        }
        return new DungeonPiecePlan(seed, bounds, List.copyOf(planned), links);
    }

    private static Bounds transformedBounds(BlockPos size, Rotation rotation) {
        List<BlockPos> corners = List.of(
            new BlockPos(0, 0, 0),
            new BlockPos(size.getX() - 1, 0, 0),
            new BlockPos(0, size.getY() - 1, 0),
            new BlockPos(0, 0, size.getZ() - 1),
            new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1)
        ).stream().map(position -> StructureTemplate.transform(
            position, Mirror.NONE, rotation, BlockPos.ZERO
        )).toList();
        int minX = corners.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = corners.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = corners.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxX = corners.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxY = corners.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maxZ = corners.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return new Bounds(
            new BlockPos(minX, minY, minZ),
            new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1)
        );
    }

    private static Rotation rotation(JsonObject value) {
        return switch (requiredString(value, "rotation")) {
            case "none" -> Rotation.NONE;
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException("Invalid authored dungeon rotation");
        };
    }

    private static BlockPos positivePosition(JsonObject value, String key) {
        BlockPos position = position(value, key);
        if (position.getX() < 1 || position.getY() < 1 || position.getZ() < 1) {
            throw new IllegalStateException("Authored dungeon plan bounds must be positive");
        }
        return position;
    }

    private static BlockPos position(JsonObject value, String key) {
        JsonArray array = requiredArray(value, key);
        if (array.size() != 3) {
            throw new IllegalStateException("Authored dungeon position requires three values");
        }
        return new BlockPos(
            array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()
        );
    }

    private static String resourceId(JsonObject value, String key) {
        String id = requiredString(value, key);
        if (ResourceLocation.tryParse(id) == null) {
            throw new IllegalStateException("Invalid authored dungeon resource ID: " + id);
        }
        return id;
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Authored dungeon string is missing: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalStateException("Authored dungeon string is empty: " + key);
        }
        return result;
    }

    private static int requiredInt(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Authored dungeon integer is missing: " + key);
        }
        return value.get(key).getAsInt();
    }

    private static boolean requiredBoolean(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalStateException("Authored dungeon boolean is missing: " + key);
        }
        return value.get(key).getAsBoolean();
    }

    private static JsonArray requiredArray(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalStateException("Authored dungeon array is missing: " + key);
        }
        return value.getAsJsonArray(key);
    }

    record AuthoredPlacement(
        String pieceId, BlockPos origin, Rotation rotation, boolean criticalPath
    ) {}

    private record Bounds(BlockPos minimum, BlockPos size) {}
}
