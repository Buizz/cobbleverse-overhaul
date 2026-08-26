package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Resolves semantic dungeon markers authored beside one complete NBT template. */
record DungeonFixedTemplateLayout(
    BlockPos entry,
    BlockPos exit,
    Map<DungeonPieceLayout.MarkerKey, BlockPos> markers
) {
    private static final java.util.Set<String> KINDS = java.util.Set.of(
        "entry", "exit", "encounter", "boss", "loot", "healing_station",
        "gate", "objective", "checkpoint"
    );

    static DungeonFixedTemplateLayout parse(
        DungeonDefinition definition,
        BlockPos templateSize,
        JsonObject metadata
    ) {
        Map<DungeonPieceLayout.MarkerKey, BlockPos> markers = new LinkedHashMap<>();
        JsonArray anchors = metadata.has("anchors")
            ? metadata.getAsJsonArray("anchors") : new JsonArray();
        for (JsonElement element : anchors) {
            JsonObject anchor = element.getAsJsonObject();
            if (!anchor.has("type")
                || !anchor.get("type").getAsString().equals("dungeon_marker")) {
                continue;
            }
            String kind = requiredString(anchor, "kind");
            if (!KINDS.contains(kind)) {
                throw new IllegalStateException(
                    "Unknown fixed dungeon marker kind: " + kind
                );
            }
            String reference = anchor.has("reference")
                ? requiredString(anchor, "reference") : null;
            if (!kind.equals("entry") && !kind.equals("exit") && reference == null) {
                throw new IllegalStateException(
                    "Fixed dungeon marker requires a reference: " + kind
                );
            }
            BlockPos position = position(anchor, "position");
            if (!inside(position, templateSize)) {
                throw new IllegalStateException(
                    "Fixed dungeon marker exceeds template bounds: " + kind
                        + "/" + reference + " -> " + position
                );
            }
            DungeonPieceLayout.MarkerKey key = new DungeonPieceLayout.MarkerKey(
                kind, reference
            );
            if (markers.putIfAbsent(key, position) != null) {
                throw new IllegalStateException(
                    "Duplicate fixed dungeon marker: " + kind + "/" + reference
                );
            }
        }
        BlockPos entry = markers.getOrDefault(
            new DungeonPieceLayout.MarkerKey("entry", null),
            definition.terrain().entryPosition()
        );
        BlockPos exit = markers.getOrDefault(
            new DungeonPieceLayout.MarkerKey("exit", null),
            definition.terrain().exitPosition()
        );
        requirePosition(entry, templateSize, "entry", definition.id());
        requirePosition(exit, templateSize, "exit", definition.id());
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            if (encounter.position() != null) continue;
            requireMarker(
                markers, encounter.boss() ? "boss" : "encounter", encounter.id(),
                definition.id()
            );
        }
        for (DungeonDefinition.LootContainer container : definition.loot().containers()) {
            if (container.position() == null) {
                requireMarker(markers, "loot", container.id(), definition.id());
            }
        }
        for (DungeonDefinition.HealingStation station
            : definition.support().healingStations()) {
            if (station.position() == null) {
                requireMarker(
                    markers, "healing_station", station.id(), definition.id()
                );
            }
        }
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            if (objective.position() == null) {
                requireMarker(markers, "objective", objective.id(), definition.id());
            }
        }
        for (DungeonDefinition.Gate gate : definition.gates()) {
            if (gate.placement().equals("marker")) {
                requireMarker(markers, "gate", gate.id(), definition.id());
            }
        }
        if (definition.completion().returnTrigger().equals("clear_exit")
            && definition.completion().clearExitPosition() == null) {
            requireMarker(markers, "objective", "clear_exit", definition.id());
        }
        return new DungeonFixedTemplateLayout(entry, exit, Map.copyOf(markers));
    }

    private static void requireMarker(
        Map<DungeonPieceLayout.MarkerKey, BlockPos> markers,
        String kind,
        String reference,
        String dungeonId
    ) {
        if (!markers.containsKey(new DungeonPieceLayout.MarkerKey(kind, reference))) {
            throw new IllegalStateException(
                "Fixed dungeon marker is missing: " + dungeonId + " -> "
                    + kind + "/" + reference
            );
        }
    }

    private static void requirePosition(
        BlockPos position, BlockPos size, String kind, String dungeonId
    ) {
        if (position == null || !inside(position, size)) {
            throw new IllegalStateException(
                "Fixed dungeon " + kind + " is missing or outside the template: "
                    + dungeonId
            );
        }
    }

    private static boolean inside(BlockPos position, BlockPos size) {
        return position.getX() >= 0 && position.getY() >= 0 && position.getZ() >= 0
            && position.getX() < size.getX() && position.getY() < size.getY()
            && position.getZ() < size.getZ();
    }

    private static BlockPos position(JsonObject owner, String key) {
        JsonArray value = owner.getAsJsonArray(key);
        if (value == null || value.size() != 3) {
            throw new IllegalStateException("Fixed dungeon marker position is missing");
        }
        return new BlockPos(
            value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt()
        );
    }

    private static String requiredString(JsonObject owner, String key) {
        if (!owner.has(key) || owner.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("Fixed dungeon marker field is missing: " + key);
        }
        return owner.get(key).getAsString();
    }
}
