package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonDefinitionTest {
    @Test
    void powerPlantUsesFourDungeonOwnedTrainers() throws Exception {
        DungeonDefinition definition = DungeonDefinition.parse(
            resourceObject("rocket_power_plant")
        );

        assertEquals(4, definition.encounters().size());
        assertTrue(definition.encounters().stream().allMatch(encounter ->
            encounter.npcs().isEmpty() && encounter.opponents().size() == 1
                && encounter.trainers().size() == 1
        ));
    }

    @Test
    void parsesPoolGeneratedTrainerWithoutBattlePreset() throws Exception {
        JsonObject root = resourceObject("rocket_power_plant");
        JsonObject encounter = root.getAsJsonArray("encounters")
            .get(0).getAsJsonObject();
        encounter.remove("trainers");
        encounter.add("npcs", JsonParser.parseString(
            "[\"cobbleventure:npc/rocket_power_plant_grunt\"]"
        ).getAsJsonArray());
        encounter.remove("opponents");
        encounter.add("trainer_generation", JsonParser.parseString("""
            {
              "pokemon_pool": [
                {"species":"cobblemon:rattata","weight":10},
                {"species":"cobblemon:koffing","weight":5}
              ],
              "team_size":[1,2],
              "allow_duplicates":false,
              "battle_start_lines":["침입자다!", "여기서 멈춰라!"],
              "battle_end_lines":["후퇴한다!", "이럴 수가…"]
            }
            """).getAsJsonObject());

        DungeonDefinition definition = DungeonDefinition.parse(root);
        DungeonDefinition.GeneratedTrainer generated = definition.encounters()
            .getFirst().generatedTrainer();

        assertEquals(2, generated.pokemonPool().size());
        assertEquals(1, generated.teamSize().minimum());
        assertEquals(2, generated.teamSize().maximum());
        assertTrue(definition.encounters().getFirst().opponents().isEmpty());
    }

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
    void allowsAuthoredNbtPlansAtEntryButKeepsCavesRuntimeOnly() throws Exception {
        DungeonDefinition tower = resource("rocket_pokemon_tower");
        assertEquals("runtime", tower.plan().mode());
        assertEquals(null, DungeonSystem.terrainEntryProblem(tower));

        JsonObject caveRoot = resourceObject("rocket_power_plant");
        caveRoot.add("plan", JsonParser.parseString("""
            {"mode":"authored","plan_ids":["cobbleventure:dungeon_plan/test"],
             "seed_policy":"fixed","fallback":"reject_entry"}
            """).getAsJsonObject());
        caveRoot.add("terrain", JsonParser.parseString("""
            {"mode":"procedural_cave","cave_generator":"minecraft_worldgen",
             "bounds":[160,48,160]}
            """).getAsJsonObject());
        caveRoot.add("layout", JsonParser.parseString("""
            {"mode":"critical_path_branches","critical_path_rooms":[6,8],
             "branch_count":[1,3],"branch_depth":[1,2],"loop_chance":0.2}
            """).getAsJsonObject());

        assertEquals(
            "절차 동굴은 현재 런타임 계획만 입장할 수 있습니다.",
            DungeonSystem.terrainEntryProblem(DungeonDefinition.parse(caveRoot))
        );
    }

    @Test
    void parsesCheckpointResumePolicyAndRejectsMissingCheckpoint() throws Exception {
        JsonObject root = resourceObject("rocket_power_plant");
        root.getAsJsonObject("support").add("checkpoints", JsonParser.parseString("""
            [{"id":"control_room","position":[24,1,32],"activation_radius":3}]
            """));
        root.getAsJsonObject("lifecycle").addProperty("resume_mode", "checkpoint");

        DungeonDefinition definition = DungeonDefinition.parse(root);

        assertEquals("checkpoint", definition.lifecycle().resumeMode());
        assertEquals("control_room", definition.support().checkpoints().getFirst().id());
        assertEquals(3, definition.support().checkpoints().getFirst().activationRadius());

        JsonObject invalid = resourceObject("rocket_power_plant");
        invalid.getAsJsonObject("lifecycle").addProperty("resume_mode", "checkpoint");
        assertThrows(IllegalStateException.class, () -> DungeonDefinition.parse(invalid));
    }

    @Test
    void parsesEveryConfiguredDungeonResourceAtItsExpectedLevelRange() throws Exception {
        Map<String, String> resources = Map.of(
            "rocket_power_plant", "fixed_template",
            "rocket_casino_hideout", "nbt_pieces",
            "rocket_silph_company", "nbt_pieces",
            "rocket_pokemon_tower", "nbt_pieces",
            "zapdos_storm_chamber", "procedural_cave"
        );
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            String name = resource.getKey();
            var stream = getClass().getClassLoader().getResourceAsStream(
                "data/cobbleventure/dungeons/generation_1/" + name + ".json"
            );
            assertTrue(stream != null, "Missing dungeon resource: " + name);
            try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                DungeonDefinition definition = DungeonDefinition.parse(
                    JsonParser.parseReader(reader).getAsJsonObject()
                );
                assertEquals(resource.getValue(), definition.terrain().mode(), name);
                int recommendedMinimum = switch (name) {
                    case "rocket_power_plant" -> 24;
                    case "zapdos_storm_chamber" -> 22;
                    case "rocket_casino_hideout" -> 25;
                    case "rocket_pokemon_tower" -> 27;
                    case "rocket_silph_company" -> 30;
                    default -> throw new IllegalStateException(name);
                };
                int recommendedMaximum = switch (name) {
                    case "rocket_power_plant", "rocket_casino_hideout" -> 29;
                    case "zapdos_storm_chamber" -> 30;
                    case "rocket_pokemon_tower" -> 30;
                    case "rocket_silph_company" -> 41;
                    default -> throw new IllegalStateException(name);
                };
                int internalMinimum = switch (name) {
                    case "rocket_power_plant" -> 22;
                    case "zapdos_storm_chamber" -> 18;
                    case "rocket_casino_hideout" -> 24;
                    case "rocket_pokemon_tower" -> 27;
                    case "rocket_silph_company" -> 30;
                    default -> throw new IllegalStateException(name);
                };
                int internalMaximum = switch (name) {
                    case "rocket_power_plant", "rocket_casino_hideout" -> 29;
                    case "zapdos_storm_chamber" -> 30;
                    case "rocket_pokemon_tower" -> 30;
                    case "rocket_silph_company" -> 41;
                    default -> throw new IllegalStateException(name);
                };
                assertEquals(recommendedMinimum, definition.difficulty().recommendedMin(), name);
                assertEquals(recommendedMaximum, definition.difficulty().recommendedMax(), name);
                assertEquals(internalMinimum, definition.difficulty().internalMin(), name);
                assertEquals(internalMaximum, definition.difficulty().internalMax(), name);
                assertEquals(
                    name.equals("zapdos_storm_chamber")
                        ? "cobbleventure_bootstrap:textures/gui/dungeons/storm_cavern.png"
                        : "cobbleventure_bootstrap:textures/gui/dungeons/rocket_facility.png",
                    definition.entryUi().backgroundTexture(),
                    name
                );
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
        assertEquals(5, casino.encounters().size());
        assertTrue(casino.encounters().stream().allMatch(encounter ->
            encounter.trainers().size() == 1
        ));
        assertEquals(5, casino.encounters().stream()
            .flatMap(encounter -> encounter.trainers().stream())
            .map(DungeonDefinition.TrainerActor::id).distinct().count());
        assertTrue(casino.encounters().stream().allMatch(encounter ->
            encounter.npcs().isEmpty() && encounter.runStateKeys().isEmpty()
                && encounter.trigger() != null
                && encounter.trigger().type().equals("proximity")
                && encounter.trigger().leader() == 0
        ));
        assertEquals(
            "cobbleventure:trainer_class/villain_admin",
            casino.encounters().getLast().trainers().getFirst().trainerClass()
        );
        assertEquals(List.of("admin_guard"), casino.encounters().getLast().requires());
        assertEquals("independent", silph.multiplayer().mode());
        assertEquals(2, silph.match().requiredPlayers());
        assertEquals("initiator_only", silph.multiplayer().battleJoin());
        assertEquals(8, silph.encounters().size());
        assertEquals("descending", casino.layout().verticalDirection());
        assertEquals("ascending", silph.layout().verticalDirection());
        assertEquals("procedural_cave", zapdos.terrain().mode());
        assertEquals("rock", zapdos.terrain().caveSettings().style());
        assertEquals(4, zapdos.terrain().caveSettings().mainRooms());
        assertEquals(5, zapdos.terrain().caveSettings().branchCount());
        assertTrue(zapdos.terrain().caveSettings().requiresFlash());
        assertEquals("wild_pokemon", zapdos.encounters().getFirst().kind());
        assertEquals("cobblemon:zapdos", zapdos.encounters().getFirst().pokemon().species());
        assertEquals(30, zapdos.encounters().getFirst().pokemon().level());
        assertTrue(zapdos.encounters().getFirst().pokemon().catchable());
        assertFalse(zapdos.completion().repeatable());
        assertTrue(zapdos.randomEncounters().enabled());
        assertEquals(8, zapdos.randomEncounters().additions().size());
        assertEquals("cobblemon:electabuzz", zapdos.randomEncounters().additions().get(6).species());
        assertEquals(2, zapdos.randomEncounters().additions().get(6).weight());
        assertEquals("cobblemon:raichu", zapdos.randomEncounters().additions().getLast().species());
        assertEquals(1, zapdos.randomEncounters().additions().getLast().weight());
    }

    @Test
    void parsesMarkerRelativeGateFromPokemonTower() throws Exception {
        DungeonDefinition tower = resource("rocket_pokemon_tower");
        DungeonDefinition.Gate gate = tower.gates().getFirst();

        assertEquals("tower_upper_lock", gate.id());
        assertEquals("marker", gate.placement());
        assertEquals(-2, gate.minimum().getZ());
        assertEquals(2, gate.maximum().getZ());
        assertEquals(List.of("memorial_guard_1", "memorial_guard_2"), gate.requires());
        assertEquals(1, tower.objectives().size());
        assertEquals("security_switch", tower.objectives().getFirst().id());
        assertEquals("objective", gate.requirements().getLast().type());
        assertEquals("security_switch", gate.requirements().getLast().reference());
    }

    @Test
    void rejectsGateWithUnknownObjectiveRequirement() throws Exception {
        JsonObject root = resourceObject("rocket_pokemon_tower");
        root.getAsJsonArray("gates").get(0).getAsJsonObject()
            .getAsJsonArray("requirements").get(0).getAsJsonObject()
            .addProperty("id", "missing_switch");

        assertThrows(IllegalStateException.class, () -> DungeonDefinition.parse(root));
    }

    @Test
    void acceptsMarkerRelativeGateInFixedTemplateDungeon() throws Exception {
        DungeonDefinition dungeon = resource("rocket_power_plant");

        assertEquals("marker", dungeon.gates().getFirst().placement());
        assertEquals("all", dungeon.eligibility().conditionMode());
        assertEquals(1, dungeon.eligibility().conditions().size());
        assertEquals(
            "갈색시티 체육관을 클리어한 트레이너만 발전소 조사에 참여할 수 있습니다.",
            dungeon.eligibility().lockedMessage()
        );
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
        assertEquals("all", definition.eligibility().conditionMode());
        assertTrue(definition.eligibility().conditions().isEmpty());
        assertEquals(
            "아직 이 던전에 입장할 수 없습니다.",
            definition.eligibility().lockedMessage()
        );
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

    @Test
    void identifiesTheHorizontalAreaOwnedByDungeonEncounters() {
        var origin = new net.minecraft.core.BlockPos(32768, 80, 0);
        var size = new net.minecraft.core.BlockPos(48, 24, 48);

        assertTrue(DungeonSystem.insideRunHorizontalBounds(32790, 30, origin, size));
        assertFalse(DungeonSystem.insideRunHorizontalBounds(32700, 30, origin, size));
        assertFalse(DungeonSystem.insideRunHorizontalBounds(32790, 80, origin, size));
    }
}
