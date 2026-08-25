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

/** Immutable first-stage contract for data-driven dungeon definitions. */
record DungeonDefinition(
    String id,
    String displayName,
    String description,
    String preset,
    EntryUi entryUi,
    Difficulty difficulty,
    Terrain terrain,
    List<Entrance> entrances
) {
    static Map<String, DungeonDefinition> loadAll(ResourceManager resources) {
        Map<String, DungeonDefinition> definitions = new LinkedHashMap<>();
        Map<String, String> entranceOwners = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files = resources.listResources(
            "dungeons", location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            DungeonDefinition definition;
            try (Reader reader = file.getValue().openAsReader()) {
                definition = parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException(
                    "Invalid dungeon definition: " + file.getKey(), error
                );
            }
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate dungeon ID: " + definition.id());
            }
            for (Entrance entrance : definition.entrances()) {
                String previous = entranceOwners.putIfAbsent(
                    entrance.entranceId(), definition.id()
                );
                if (previous != null) {
                    throw new IllegalStateException(
                        "Duplicate dungeon entrance ID: " + entrance.entranceId()
                            + " (" + previous + " / " + definition.id() + ")"
                    );
                }
            }
        }
        return Map.copyOf(definitions);
    }

    static DungeonDefinition parse(JsonObject root) {
        int schemaVersion = requiredInt(root, "schema_version");
        if (schemaVersion != 1) {
            throw new IllegalStateException(
                "Unsupported dungeon schema version: " + schemaVersion
            );
        }
        String id = resourceId(root, "dungeon_id");
        JsonObject displayName = requiredObject(root, "display_name");
        JsonObject entryUi = requiredObject(root, "entry_ui");
        JsonObject difficulty = requiredObject(root, "difficulty");
        JsonObject terrain = requiredObject(root, "terrain");
        List<Entrance> entrances = new ArrayList<>();
        JsonArray configuredEntrances = requiredArray(root, "entrances");
        if (configuredEntrances.isEmpty()) {
            throw new IllegalStateException("Dungeon requires at least one entrance: " + id);
        }
        for (JsonElement element : configuredEntrances) {
            JsonObject entrance = element.getAsJsonObject();
            entrances.add(new Entrance(
                resourceId(entrance, "entrance_id"),
                requiredString(entrance, "destination_entry"),
                enumValue(entrance, "activation", List.of(
                    "interact", "cross", "portal", "proximity"
                )),
                enumValue(entrance, "visibility", List.of(
                    "always", "discovered", "conditioned", "hidden"
                )),
                enumValue(entrance, "return_policy", List.of(
                    "source_position", "source_safe_anchor", "configured_exit"
                ))
            ));
        }
        int recommendedMin = requiredInt(difficulty, "recommended_min");
        int recommendedMax = requiredInt(difficulty, "recommended_max");
        int internalMin = requiredInt(difficulty, "internal_min");
        int internalMax = requiredInt(difficulty, "internal_max");
        validateRange("recommended level", recommendedMin, recommendedMax);
        validateRange("internal level", internalMin, internalMax);
        String terrainMode = enumValue(terrain, "mode", List.of(
            "fixed_template", "nbt_pieces", "procedural_cave", "hybrid"
        ));
        String template = terrain.has("template")
            ? resourceId(terrain, "template") : null;
        if (terrainMode.equals("fixed_template") && template == null) {
            throw new IllegalStateException(
                "fixed_template dungeon requires terrain.template: " + id
            );
        }
        BlockPos entryPosition = terrainMode.equals("fixed_template")
            ? blockPosition(terrain, "entry_position") : null;
        BlockPos exitPosition = terrainMode.equals("fixed_template")
            ? blockPosition(terrain, "exit_position") : null;
        return new DungeonDefinition(
            id,
            localized(displayName, "ko_kr", "en_us"),
            localized(requiredObject(root, "description"), "ko_kr", "en_us"),
            resourceId(root, "preset"),
            new EntryUi(
                enumValue(entryUi, "info_mode", List.of("exact", "summary", "mystery")),
                requiredBoolean(entryUi, "confirm_required")
            ),
            new Difficulty(recommendedMin, recommendedMax, internalMin, internalMax),
            new Terrain(terrainMode, template, entryPosition, exitPosition),
            List.copyOf(entrances)
        );
    }

    Entrance entrance(String entranceId) {
        return entrances.stream()
            .filter(entrance -> entrance.entranceId().equals(entranceId))
            .findFirst().orElse(null);
    }

    private static void validateRange(String name, int minimum, int maximum) {
        if (minimum < 1 || maximum > 100 || minimum > maximum) {
            throw new IllegalStateException(
                "Invalid dungeon " + name + " range: " + minimum + ".." + maximum
            );
        }
    }

    private static String localized(JsonObject value, String primary, String fallback) {
        String result = value.has(primary) ? value.get(primary).getAsString()
            : value.has(fallback) ? value.get(fallback).getAsString() : null;
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Dungeon localized text is missing");
        }
        return result;
    }

    private static String enumValue(JsonObject value, String key, List<String> allowed) {
        String result = requiredString(value, key);
        if (!allowed.contains(result)) {
            throw new IllegalStateException("Invalid dungeon " + key + ": " + result);
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
            throw new IllegalStateException("Dungeon string is missing: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalStateException("Dungeon string is empty: " + key);
        }
        return result;
    }

    private static int requiredInt(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Dungeon integer is missing: " + key);
        }
        return value.get(key).getAsInt();
    }

    private static boolean requiredBoolean(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalStateException("Dungeon boolean is missing: " + key);
        }
        return value.get(key).getAsBoolean();
    }

    private static JsonObject requiredObject(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) {
            throw new IllegalStateException("Dungeon object is missing: " + key);
        }
        return value.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalStateException("Dungeon array is missing: " + key);
        }
        return value.getAsJsonArray(key);
    }

    private static BlockPos blockPosition(JsonObject value, String key) {
        JsonArray position = requiredArray(value, key);
        if (position.size() != 3) {
            throw new IllegalStateException("Dungeon block position requires three values: " + key);
        }
        return new BlockPos(
            position.get(0).getAsInt(),
            position.get(1).getAsInt(),
            position.get(2).getAsInt()
        );
    }

    record EntryUi(String infoMode, boolean confirmRequired) {}
    record Difficulty(int recommendedMin, int recommendedMax, int internalMin, int internalMax) {}
    record Terrain(
        String mode,
        String template,
        BlockPos entryPosition,
        BlockPos exitPosition
    ) {}
    record Entrance(
        String entranceId,
        String destinationEntry,
        String activation,
        String visibility,
        String returnPolicy
    ) {}
}
