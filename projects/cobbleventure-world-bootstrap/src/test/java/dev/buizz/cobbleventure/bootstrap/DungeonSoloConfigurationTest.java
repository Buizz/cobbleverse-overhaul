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
    void testSoloDungeonsUseFullResetAndRepeatRewardContracts() throws Exception {
        for (String name : List.of("rocket_power_plant", "rocket_pokemon_tower")) {
            DungeonDefinition dungeon = packagedDungeon(name);

            assertEquals("solo", dungeon.multiplayer().mode());
            assertEquals(1, dungeon.multiplayer().minSize());
            assertEquals(1, dungeon.multiplayer().maxSize());
            assertEquals(1, dungeon.match().requiredPlayers());
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
    void soloDungeonLevelsPreserveEachDungeonsEncounterContract() throws Exception {
        DungeonDefinition powerPlant = packagedDungeon("rocket_power_plant");
        assertEquals(24, powerPlant.difficulty().recommendedMin());
        assertEquals(29, powerPlant.difficulty().recommendedMax());
        assertEquals(22, powerPlant.difficulty().internalMin());
        assertEquals(29, powerPlant.difficulty().internalMax());

        DungeonDefinition tower = packagedDungeon("rocket_pokemon_tower");
        assertEquals(27, tower.difficulty().recommendedMin());
        assertEquals(30, tower.difficulty().recommendedMax());
        assertEquals(27, tower.difficulty().internalMin());
        assertEquals(30, tower.difficulty().internalMax());
    }

    @Test
    void pokemonTowerUsesDungeonOwnedProximityEncounters() throws Exception {
        DungeonDefinition tower = packagedDungeon("rocket_pokemon_tower");

        assertEquals(5, tower.encounters().size());
        assertTrue(tower.encounters().stream().allMatch(encounter ->
            encounter.trainers().size() == 1
                && encounter.trigger() != null
                && encounter.trigger().type().equals("proximity")
                && encounter.trigger().leader() == 0
                && encounter.trigger().warningTrack()
                    .equals("encounter.trainer_bad_guys")
        ));
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
