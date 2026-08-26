package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonFixedTemplateLayoutTest {
    @Test
    void resolvesPowerPlantContentFromStructureMetadata() throws Exception {
        DungeonDefinition dungeon = DungeonDefinition.parse(resource(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        ));
        DungeonFixedTemplateLayout layout = DungeonFixedTemplateLayout.parse(
            dungeon, new BlockPos(48, 8, 48), resource(
                "data/cobbleventure/structure_metadata/placeholder/"
                    + "power_plant.structure.json"
            )
        );

        assertEquals(new BlockPos(24, 1, 4), layout.entry());
        assertEquals(new BlockPos(24, 1, 0), layout.exit());
        for (var encounter : dungeon.encounters()) {
            String kind = encounter.boss() ? "boss" : "encounter";
            assertTrue(layout.markers().containsKey(
                new DungeonPieceLayout.MarkerKey(kind, encounter.id())
            ));
        }
        assertTrue(layout.markers().containsKey(
            new DungeonPieceLayout.MarkerKey("gate", "control_room_lockdown")
        ));
        assertTrue(layout.markers().containsKey(
            new DungeonPieceLayout.MarkerKey("objective", "clear_exit")
        ));
    }

    @Test
    void rejectsMissingRequiredFixedTemplateMarker() throws Exception {
        DungeonDefinition dungeon = DungeonDefinition.parse(resource(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        ));
        JsonObject metadata = resource(
            "data/cobbleventure/structure_metadata/placeholder/"
                + "power_plant.structure.json"
        );
        for (int index = metadata.getAsJsonArray("anchors").size() - 1;
             index >= 0; index--) {
            JsonObject anchor = metadata.getAsJsonArray("anchors")
                .get(index).getAsJsonObject();
            if (anchor.has("reference")
                && anchor.get("reference").getAsString().equals("west_grunt")) {
                metadata.getAsJsonArray("anchors").remove(index);
            }
        }

        assertThrows(IllegalStateException.class, () ->
            DungeonFixedTemplateLayout.parse(
                dungeon, new BlockPos(48, 8, 48), metadata
            )
        );
    }

    private JsonObject resource(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertTrue(stream != null, "Missing test resource: " + path);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
