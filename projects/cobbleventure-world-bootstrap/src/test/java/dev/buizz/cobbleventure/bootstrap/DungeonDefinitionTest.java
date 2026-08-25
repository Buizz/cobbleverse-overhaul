package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DungeonDefinitionTest {
    @Test
    void parsesFixedTemplateDungeonAndIndependentLevelRanges() {
        DungeonDefinition definition = DungeonDefinition.parse(
            JsonParser.parseString("""
                {
                  "schema_version": 1,
                  "dungeon_id": "cobbleventure:dungeon/rocket_power_plant",
                  "display_name": {"ko_kr": "점거된 발전소"},
                  "description": {"ko_kr": "로켓단이 점거한 발전소"},
                  "preset": "cobbleventure:team_rocket_facility",
                  "entrances": [{
                    "entrance_id": "cobbleventure:entrance/rocket_power_plant",
                    "destination_entry": "main",
                    "activation": "proximity",
                    "visibility": "always",
                    "return_policy": "source_safe_anchor"
                  }],
                  "entry_ui": {"info_mode": "summary", "confirm_required": true},
                  "difficulty": {
                    "recommended_min": 25,
                    "recommended_max": 30,
                    "internal_min": 24,
                    "internal_max": 31
                  },
                  "terrain": {
                    "mode": "fixed_template",
                    "template": "cobbleventure:placeholder/power_plant",
                    "entry_position": [24, 1, 4],
                    "exit_position": [24, 1, 0]
                  }
                }
                """).getAsJsonObject()
        );

        assertEquals("점거된 발전소", definition.displayName());
        assertEquals(25, definition.difficulty().recommendedMin());
        assertEquals(24, definition.difficulty().internalMin());
        assertEquals(4, definition.terrain().entryPosition().getZ());
        assertEquals(0, definition.terrain().exitPosition().getZ());
        assertEquals(
            "cobbleventure:entrance/rocket_power_plant",
            definition.entrances().getFirst().entranceId()
        );
    }

    @Test
    void rejectsFixedTemplateDungeonWithoutTemplate() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> DungeonDefinition.parse(JsonParser.parseString("""
                {
                  "schema_version": 1,
                  "dungeon_id": "cobbleventure:dungeon/test",
                  "display_name": {"ko_kr": "테스트"},
                  "description": {"ko_kr": "테스트"},
                  "preset": "cobbleventure:test",
                  "entrances": [{
                    "entrance_id": "cobbleventure:entrance/test",
                    "destination_entry": "main",
                    "activation": "interact",
                    "visibility": "always",
                    "return_policy": "source_position"
                  }],
                  "entry_ui": {"info_mode": "summary", "confirm_required": true},
                  "difficulty": {
                    "recommended_min": 10,
                    "recommended_max": 15,
                    "internal_min": 10,
                    "internal_max": 15
                  },
                  "terrain": {
                    "mode": "fixed_template",
                    "entry_position": [1, 1, 1],
                    "exit_position": [1, 1, 0]
                  }
                }
                """).getAsJsonObject())
        );

        assertEquals(true, error.getMessage().contains("terrain.template"));
    }

    @Test
    void assignsDungeonSlotsOnASeparatedEightByEightGrid() {
        assertEquals(new net.minecraft.core.BlockPos(32768, 80, 0), DungeonSystem.slotOrigin(0));
        assertEquals(new net.minecraft.core.BlockPos(36352, 80, 0), DungeonSystem.slotOrigin(7));
        assertEquals(new net.minecraft.core.BlockPos(32768, 80, 512), DungeonSystem.slotOrigin(8));
    }
}
