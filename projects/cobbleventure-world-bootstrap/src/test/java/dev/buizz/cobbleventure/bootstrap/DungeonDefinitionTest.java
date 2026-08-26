package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonDefinitionTest {
    @Test
    void parsesRuntimeNbtPiecePlanningSettings() throws Exception {
        JsonObject root = resourceObject("rocket_power_plant");
        root.add("plan", JsonParser.parseString("""
            {
              "mode": "runtime",
              "seed_policy": "random_per_run",
              "fallback": "reject_entry",
              "generation_timeout_ms": 750,
              "max_attempts": 48
            }
            """).getAsJsonObject());
        root.add("terrain", JsonParser.parseString("""
            {
              "mode": "nbt_pieces",
              "piece_pool": "cobbleventure:rocket_hideout",
              "bounds": [96, 32, 96]
            }
            """).getAsJsonObject());
        root.add("layout", JsonParser.parseString("""
            {
              "mode": "critical_path_branches",
              "critical_path_rooms": [6, 9],
              "branch_count": [1, 3],
              "branch_depth": [1, 2],
              "loop_chance": 0.15
            }
            """).getAsJsonObject());

        DungeonDefinition definition = DungeonDefinition.parse(root);

        assertEquals("runtime", definition.plan().mode());
        assertEquals("random_per_run", definition.plan().seedPolicy());
        assertEquals(750, definition.plan().generationTimeoutMs());
        assertEquals(48, definition.plan().maxAttempts());
        assertEquals("cobbleventure:rocket_hideout", definition.terrain().piecePool());
        assertEquals(96, definition.terrain().bounds().getX());
        assertEquals(6, definition.layout().criticalPathRooms().minimum());
        assertEquals(9, definition.layout().criticalPathRooms().maximum());
        assertEquals(0.15D, definition.layout().loopChance());
    }

    @Test
    void rejectsNbtPieceDungeonWithoutLayout() throws Exception {
        JsonObject root = resourceObject("rocket_power_plant");
        root.add("terrain", JsonParser.parseString("""
            {
              "mode": "nbt_pieces",
              "piece_pool": "cobbleventure:rocket_hideout",
              "bounds": [96, 32, 96]
            }
            """).getAsJsonObject());

        assertThrows(IllegalStateException.class, () -> DungeonDefinition.parse(root));
    }

    @Test
    void parsesProceduralCaveTerrainUsingCurrentCaveGenerator() throws Exception {
        JsonObject root = resourceObject("rocket_power_plant");
        root.add("plan", JsonParser.parseString("""
            {"mode":"runtime","seed_policy":"match","fallback":"reject_entry"}
            """).getAsJsonObject());
        root.add("terrain", JsonParser.parseString("""
            {"mode":"procedural_cave","cave_generator":"minecraft_worldgen",
             "bounds":[160,48,160]}
            """).getAsJsonObject());
        root.add("layout", JsonParser.parseString("""
            {"mode":"critical_path_branches","critical_path_rooms":[6,8],
             "branch_count":[1,3],"branch_depth":[1,2],"loop_chance":0.2}
            """).getAsJsonObject());

        DungeonDefinition definition = DungeonDefinition.parse(root);

        assertEquals("procedural_cave", definition.terrain().mode());
        assertEquals("minecraft_worldgen", definition.terrain().caveGenerator());
        assertEquals(160, definition.terrain().bounds().getZ());
        assertEquals("critical_path_branches", definition.layout().mode());
    }

    @Test
    void parsesEveryLevelOneTestDungeonResource() throws Exception {
        List<String> resources = List.of(
            "rocket_power_plant",
            "rocket_casino_hideout",
            "rocket_silph_company",
            "rocket_pokemon_tower",
            "zapdos_storm_chamber"
        );
        for (String name : resources) {
            var stream = getClass().getClassLoader().getResourceAsStream(
                "data/cobbleventure/dungeons/generation_1/" + name + ".json"
            );
            assertTrue(stream != null, "Missing dungeon resource: " + name);
            try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                DungeonDefinition definition = DungeonDefinition.parse(
                    JsonParser.parseReader(reader).getAsJsonObject()
                );
                assertEquals("fixed_template", definition.terrain().mode(), name);
                assertEquals(1, definition.difficulty().recommendedMin(), name);
                assertEquals(1, definition.difficulty().recommendedMax(), name);
                assertEquals(1, definition.difficulty().internalMin(), name);
                assertEquals(1, definition.difficulty().internalMax(), name);
            }
        }
    }

    @Test
    void exposesDistinctMultiplayerModesAndCatchableZapdosBoss() throws Exception {
        DungeonDefinition casino = resource("rocket_casino_hideout");
        DungeonDefinition silph = resource("rocket_silph_company");
        DungeonDefinition zapdos = resource("zapdos_storm_chamber");

        assertEquals("cooperative", casino.multiplayer().mode());
        assertEquals(2, casino.match().requiredPlayers());
        assertEquals("summon_all", casino.multiplayer().battleJoin());
        assertEquals(2, casino.encounters().getFirst().npcs().size());
        assertEquals("independent", silph.multiplayer().mode());
        assertEquals(2, silph.match().requiredPlayers());
        assertEquals("initiator_only", silph.multiplayer().battleJoin());
        assertEquals("wild_pokemon", zapdos.encounters().getFirst().kind());
        assertEquals("cobblemon:zapdos", zapdos.encounters().getFirst().pokemon().species());
        assertEquals(1, zapdos.encounters().getFirst().pokemon().level());
        assertTrue(zapdos.encounters().getFirst().pokemon().catchable());
        assertFalse(zapdos.completion().repeatable());
    }

    @Test
    void independentEncounterAcceptsOneParticipantVictory() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> participants = Set.of(first, second);

        assertTrue(DungeonSystem.encounterWon("independent", Set.of(first), participants));
        assertFalse(DungeonSystem.encounterWon("cooperative", Set.of(first), participants));
        assertTrue(DungeonSystem.encounterWon(
            "cooperative", Set.of(first, second), participants
        ));
    }

    private DungeonDefinition resource(String name) throws Exception {
        return DungeonDefinition.parse(resourceObject(name));
    }

    private JsonObject resourceObject(String name) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/" + name + ".json"
        );
        assertTrue(stream != null, "Missing dungeon resource: " + name);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

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
                  "eligibility": {
                    "minimum_party_size": 1,
                    "maximum_party_size": 6,
                    "require_usable_pokemon": true,
                    "level_measure": "average",
                    "recommended_level_policy": "warn"
                  },
                  "multiplayer": {
                    "mode": "cooperative",
                    "min_size": 2,
                    "max_size": 2,
                    "battle_join": "summon_all",
                    "tether": {
                      "warn_distance": 32,
                      "max_distance": 48,
                      "on_exceed": "return_to_partner"
                    }
                  },
                  "match": {
                    "required_players": 2,
                    "scope": "same_entrance",
                    "timeout_seconds": 300,
                    "on_timeout": "cancel",
                    "stay_radius": 8
                  },
                  "battle": {
                    "allow_flee": false,
                    "allow_capture": true,
                    "allow_items": true,
                    "allow_escape_actions": false
                  },
                  "terrain": {
                    "mode": "fixed_template",
                    "template": "cobbleventure:placeholder/power_plant",
                    "entry_position": [24, 1, 4],
                    "exit_position": [24, 1, 0]
                  },
                  "encounters": [{
                    "id": "boss",
                    "display_name": {
                      "ko_kr": "제어실 로켓단 간부",
                      "en_us": "Control Room Team Rocket Officer"
                    },
                    "npcs": [
                      "cobbleventure:npc/rocket_power_plant_officer",
                      "cobbleventure:npc/rocket_power_plant_grunt"
                    ],
                    "opponents": [
                      "cobbleventure:battle/rocket_power_plant_officer",
                      "cobbleventure:battle/rocket_power_plant_officer"
                    ],
                    "requires": [],
                    "position": [24, 1, 40],
                    "yaw": 180,
                    "boss": true
                  }],
                  "random_encounters": {
                    "enabled": true,
                    "minimum_distance": 8,
                    "maximum_distance": 16,
                    "max_active": 2,
                    "spawn_interval_ticks": 100,
                    "spawn_bounds": {
                      "min": [2, 1, 11],
                      "max": [45, 1, 45]
                    },
                    "additions": [{
                      "species": "cobblemon:magnemite",
                      "min_level": 24,
                      "max_level": 28,
                      "weight": 35
                    }]
                  },
                  "support": {
                    "healing_stations": [{
                      "id": "pre_boss_station",
                      "position": [18, 1, 35],
                      "block": "minecraft:lodestone",
                      "uses_per_run": 1,
                      "restore_hp": true,
                      "restore_status": true,
                      "restore_pp": true
                    }]
                  },
                  "gates": [{
                    "id": "boss_lockdown",
                    "min": [22, 1, 33],
                    "max": [25, 3, 33],
                    "block": "minecraft:iron_bars",
                    "requires": ["boss"]
                  }],
                  "loot": {
                    "loot_table": "cobbleventure:dungeon/rocket_power_plant_supplies",
                    "ownership": "per_player",
                    "on_failure": "grant_on_clear_only",
                    "containers": [{
                      "id": "maintenance_cache",
                      "position": [6, 1, 17],
                      "block": "barrel",
                      "facing": "south",
                      "requires_completion": true,
                      "loot_table": "cobbleventure:dungeon/control_room"
                    }]
                  },
                  "rewards": {
                    "first_clear_table": "cobbleventure:dungeon/rocket_power_plant_first_clear",
                    "repeat_table": "cobbleventure:dungeon/rocket_power_plant_repeat_clear",
                    "first_clear_field_moves": ["flash"]
                  },
                  "lifecycle": {
                    "on_wipe": "reset_run",
                    "wipe_return": "source_entrance",
                    "heal_on_wipe": true,
                    "reconnect_grace_seconds": 120
                  },
                  "completion": {
                    "victory_flag": "cobbleventure:flag/dungeon/rocket_power_plant/boss_defeated",
                    "repeatable": true,
                    "return_trigger": "clear_exit",
                    "clear_exit_position": [24, 1, 43],
                    "clear_exit_block": "minecraft:lodestone"
                  }
                }
                """).getAsJsonObject()
        );

        assertEquals("점거된 발전소", definition.displayName());
        assertEquals(25, definition.difficulty().recommendedMin());
        assertEquals(24, definition.difficulty().internalMin());
        assertTrue(definition.eligibility().requireUsablePokemon());
        assertEquals("average", definition.eligibility().levelMeasure());
        assertEquals("warn", definition.eligibility().recommendedLevelPolicy());
        assertEquals("cooperative", definition.multiplayer().mode());
        assertEquals("summon_all", definition.multiplayer().battleJoin());
        assertEquals(32, definition.multiplayer().tether().warnDistance());
        assertEquals(48, definition.multiplayer().tether().maxDistance());
        assertEquals(2, definition.match().requiredPlayers());
        assertEquals(300, definition.match().timeoutSeconds());
        assertFalse(definition.battleRules().allowFlee());
        assertTrue(definition.battleRules().allowCapture());
        assertTrue(definition.battleRules().allowItems());
        assertFalse(definition.battleRules().allowEscapeActions());
        assertEquals(4, definition.terrain().entryPosition().getZ());
        assertEquals(0, definition.terrain().exitPosition().getZ());
        assertEquals("boss", definition.encounters().getFirst().id());
        assertEquals(
            "제어실 로켓단 간부", definition.encounters().getFirst().displayName()
        );
        assertEquals(
            List.of(
                "cobbleventure:npc/rocket_power_plant_officer",
                "cobbleventure:npc/rocket_power_plant_grunt"
            ),
            definition.encounters().getFirst().npcs()
        );
        assertEquals(
            List.of(
                "cobbleventure:battle/rocket_power_plant_officer",
                "cobbleventure:battle/rocket_power_plant_officer"
            ),
            definition.encounters().getFirst().opponents()
        );
        assertTrue(definition.encounters().getFirst().requires().isEmpty());
        assertEquals(2, definition.randomEncounters().maxActive());
        assertEquals(
            "cobblemon:magnemite",
            definition.randomEncounters().additions().getFirst().species()
        );
        assertEquals(45, definition.randomEncounters().maximumPosition().getX());
        assertEquals(
            "pre_boss_station",
            definition.support().healingStations().getFirst().id()
        );
        assertEquals(1, definition.support().healingStations().getFirst().usesPerRun());
        assertEquals("boss_lockdown", definition.gates().getFirst().id());
        assertEquals(List.of("boss"), definition.gates().getFirst().requires());
        assertEquals(
            "cobbleventure:dungeon/rocket_power_plant_supplies",
            definition.loot().lootTable()
        );
        assertEquals("per_player", definition.loot().ownership());
        assertEquals("grant_on_clear_only", definition.loot().onFailure());
        assertEquals("barrel", definition.loot().containers().getFirst().block());
        assertTrue(definition.loot().containers().getFirst().requiresCompletion());
        assertEquals(
            "cobbleventure:dungeon/control_room",
            definition.loot().containers().getFirst().lootTable()
        );
        assertEquals("reset_run", definition.lifecycle().onWipe());
        assertEquals("source_entrance", definition.lifecycle().wipeReturn());
        assertTrue(definition.lifecycle().healOnWipe());
        assertEquals(120, definition.lifecycle().reconnectGraceSeconds());
        assertEquals(
            "cobbleventure:dungeon/rocket_power_plant_first_clear",
            definition.rewards().firstClearTable()
        );
        assertEquals(
            "cobbleventure:dungeon/rocket_power_plant_repeat_clear",
            definition.rewards().repeatTable()
        );
        assertEquals("flash", definition.rewards().firstClearFieldMoves().getFirst());
        assertEquals("clear_exit", definition.completion().returnTrigger());
        assertEquals(43, definition.completion().clearExitPosition().getZ());
        assertEquals("minecraft:lodestone", definition.completion().clearExitBlock());
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
                  "eligibility": {
                    "minimum_party_size": 1,
                    "maximum_party_size": 6,
                    "require_usable_pokemon": true,
                    "level_measure": "average",
                    "recommended_level_policy": "warn"
                  },
                  "multiplayer": {
                    "mode": "solo",
                    "min_size": 1,
                    "max_size": 1
                  },
                  "match": {
                    "required_players": 1,
                    "scope": "same_entrance",
                    "timeout_seconds": 300,
                    "on_timeout": "cancel",
                    "stay_radius": 8
                  },
                  "battle": {
                    "allow_flee": true,
                    "allow_capture": true,
                    "allow_items": true,
                    "allow_escape_actions": true
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

    @Test
    void limitsRandomEncounterCandidatesToTheConfiguredDungeonFloor() {
        PursuitEncounterSystem.SpawnBounds bounds = new PursuitEncounterSystem.SpawnBounds(
            new net.minecraft.core.BlockPos(32770, 81, 11),
            new net.minecraft.core.BlockPos(32813, 81, 45)
        );

        assertTrue(bounds.contains(new net.minecraft.core.BlockPos(32790, 81, 30)));
        assertFalse(bounds.contains(new net.minecraft.core.BlockPos(32790, 82, 30)));
        assertFalse(bounds.contains(new net.minecraft.core.BlockPos(32769, 81, 30)));
    }
}
