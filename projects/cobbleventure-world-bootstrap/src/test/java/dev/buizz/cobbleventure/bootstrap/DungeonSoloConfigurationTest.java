package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DungeonSoloConfigurationTest {
    @Test
    void testSoloDungeonsUseLevelOneResetAndRepeatRewardContracts() throws Exception {
        for (String name : List.of("rocket_power_plant", "rocket_pokemon_tower")) {
            DungeonDefinition dungeon = packagedDungeon(name);

            assertEquals("solo", dungeon.multiplayer().mode());
            assertEquals(1, dungeon.multiplayer().minSize());
            assertEquals(1, dungeon.multiplayer().maxSize());
            assertEquals(1, dungeon.match().requiredPlayers());
            assertEquals(1, dungeon.difficulty().recommendedMin());
            assertEquals(1, dungeon.difficulty().recommendedMax());
            assertEquals(1, dungeon.difficulty().internalMin());
            assertEquals(1, dungeon.difficulty().internalMax());
            assertEquals("full_reset", dungeon.lifecycle().resumeMode());
            assertEquals("reset_run", dungeon.lifecycle().onWipe());
            assertFalse(dungeon.battleRules().allowFlee());
            assertFalse(dungeon.battleRules().allowEscapeActions());
            assertTrue(dungeon.completion().repeatable());
            assertNotNull(dungeon.rewards().firstClearTable());
            assertNotNull(dungeon.rewards().repeatTable());
            assertEquals("clear_exit", dungeon.completion().returnTrigger());
        }
    }

    @Test
    void soloDungeonsExerciseBothFixedAndGeneratedTerrainContracts() throws Exception {
        assertEquals("fixed_template", packagedDungeon("rocket_power_plant")
            .terrain().mode());
        DungeonDefinition tower = packagedDungeon("rocket_pokemon_tower");
        assertEquals("nbt_pieces", tower.terrain().mode());
        assertEquals("runtime", tower.plan().mode());
        assertEquals("ascending", tower.layout().verticalDirection());
        assertTrue(tower.layout().branchCount().minimum() > 0);
    }

    private DungeonDefinition packagedDungeon(String name) throws Exception {
        String path = "data/cobbleventure/dungeons/generation_1/" + name + ".json";
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return DungeonDefinition.parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }
}
