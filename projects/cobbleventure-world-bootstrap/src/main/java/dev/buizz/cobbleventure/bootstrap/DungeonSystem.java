package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Owns the validated dungeon catalog; run/session behavior is added in the next stage. */
final class DungeonSystem {
    private static volatile Map<String, DungeonDefinition> definitions = Map.of();
    private static volatile Map<String, DungeonEntranceRef> entrances = Map.of();

    private DungeonSystem() {}

    static void initialize(MinecraftServer server, HexWorldPlan world) {
        Map<String, DungeonDefinition> loaded = DungeonDefinition.loadAll(
            server.getResourceManager()
        );
        Map<String, DungeonEntranceRef> byEntrance = new LinkedHashMap<>();
        for (DungeonDefinition definition : loaded.values()) {
            for (DungeonDefinition.Entrance entrance : definition.entrances()) {
                byEntrance.put(
                    entrance.entranceId(),
                    new DungeonEntranceRef(definition, entrance)
                );
            }
        }
        Map<String, String> placements = new LinkedHashMap<>();
        for (WorldStructureSystem.WorldStructure structure : world.worldStructures()) {
            for (WorldStructureSystem.DungeonConnection connection
                : structure.dungeonConnections()) {
                validateStructureAnchor(
                    server.getResourceManager(), structure, connection.anchorId()
                );
                if (!byEntrance.containsKey(connection.entranceId())) {
                    throw new IllegalStateException(
                        "World structure references missing dungeon entrance: "
                            + structure.id() + " -> " + connection.entranceId()
                    );
                }
                String previous = placements.putIfAbsent(
                    connection.entranceId(), structure.id()
                );
                if (previous != null) {
                    throw new IllegalStateException(
                        "Dungeon entrance is placed more than once: "
                            + connection.entranceId() + " (" + previous
                            + " / " + structure.id() + ")"
                    );
                }
            }
        }
        for (String entranceId : byEntrance.keySet()) {
            if (!placements.containsKey(entranceId)) {
                throw new IllegalStateException(
                    "Dungeon entrance has no world placement: " + entranceId
                );
            }
        }
        definitions = loaded;
        entrances = Map.copyOf(byEntrance);
    }

    private static void validateStructureAnchor(
        ResourceManager resources,
        WorldStructureSystem.WorldStructure structure,
        String anchorId
    ) {
        ResourceLocation structureId = ResourceLocation.parse(structure.structure());
        ResourceLocation metadataId = ResourceLocation.fromNamespaceAndPath(
            structureId.getNamespace(),
            "structure_metadata/" + structureId.getPath() + ".structure.json"
        );
        Resource resource = resources.getResource(metadataId).orElseThrow(() ->
            new IllegalStateException(
                "Dungeon entrance structure metadata is missing: " + metadataId
            )
        );
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Set<String> anchors = root.getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> anchor.has("id"))
                .map(anchor -> anchor.get("id").getAsString())
                .collect(Collectors.toUnmodifiableSet());
            if (!anchors.contains(anchorId)) {
                throw new IllegalStateException(
                    "Dungeon entrance structure anchor is missing: "
                        + structure.id() + " -> " + anchorId
                );
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException(
                "Invalid dungeon entrance structure metadata: " + metadataId, error
            );
        }
    }

    static DungeonEntranceRef entrance(String entranceId) {
        return entrances.get(entranceId);
    }

    record DungeonEntranceRef(
        DungeonDefinition definition,
        DungeonDefinition.Entrance entrance
    ) {}
}
