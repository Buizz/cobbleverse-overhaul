package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class WorldPlanParserTest {
    @Test
    void parsesWorldOwnedUndergroundRoadConnection() {
        var root = JsonParser.parseString("""
            {
              "cave_entrances": [{
                "id": "cobbleventure:underground_entrance/test_exit_1",
                "underground_road": "cobbleventure:underground_road/test_passage",
                "transition": "underground_entry",
                "underground_module": "stairs_1",
                "underground_connector": "surface",
                "anchor": {"q": 3, "r": -2},
                "facing": "east",
                "structure": "cobbleventure:cave_entrance/stone_mountain",
                "pokemon_center_enabled": false
              }]
            }
            """).getAsJsonObject();

        var entrances = WorldPlanParser.caveEntrances(root);

        assertEquals(1, entrances.size());
        assertEquals(
            "cobbleventure:underground_road/test_passage",
            entrances.getFirst().cave()
        );
        assertEquals("underground_entry", entrances.getFirst().surfaceTransition());
        assertEquals("stairs_1", entrances.getFirst().undergroundModule());
        assertEquals("surface", entrances.getFirst().undergroundConnector());
        assertEquals(new WorldPlanModels.HexCoord(3, -2), entrances.getFirst().anchor());
    }
}
