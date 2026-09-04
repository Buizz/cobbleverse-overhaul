package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonAuthoredPlanDefinitionTest {
    @Test
    void assignsGenericMarkersDeterministicallyWithoutReusingSlots() throws Exception {
        var root = resourceJson(
            "data/cobbleventure/dungeons/generation_1/rocket_pokemon_tower.json"
        );
        var firstEncounter = root.getAsJsonArray("encounters")
            .get(0).getAsJsonObject().deepCopy();
        firstEncounter.addProperty("id", "encounter_1");
        firstEncounter.add("requires", JsonParser.parseString("[]"));
        var secondEncounter = firstEncounter.deepCopy();
        secondEncounter.addProperty("id", "encounter_2");
        var boss = root.getAsJsonArray("encounters")
            .get(root.getAsJsonArray("encounters").size() - 1)
            .getAsJsonObject().deepCopy();
        boss.addProperty("id", "boss_1");
        boss.add("requires", JsonParser.parseString(
            "[\"encounter_1\",\"encounter_2\"]"
        ));
        root.add("encounters", JsonParser.parseString("[]"));
        root.getAsJsonArray("encounters").add(firstEncounter);
        root.getAsJsonArray("encounters").add(secondEncounter);
        root.getAsJsonArray("encounters").add(boss);
        root.getAsJsonArray("gates").get(0).getAsJsonObject()
            .add("requires", JsonParser.parseString("[\"encounter_1\"]"));
        root.remove("npc_placement");
        var dungeon = DungeonDefinition.parse(root);
        var layout = new DungeonPieceLayout(null, List.of(
            new DungeonPieceLayout.ResolvedMarker("encounter", null, new BlockPos(2, 1, 2)),
            new DungeonPieceLayout.ResolvedMarker("encounter", null, new BlockPos(8, 1, 8)),
            new DungeonPieceLayout.ResolvedMarker("boss", null, new BlockPos(12, 1, 12)),
            new DungeonPieceLayout.ResolvedMarker("loot", null, new BlockPos(4, 1, 12)),
            new DungeonPieceLayout.ResolvedMarker("objective", "security_switch", new BlockPos(5, 1, 12)),
            new DungeonPieceLayout.ResolvedMarker("gate", null, new BlockPos(6, 1, 12))
        ));

        var first = layout.featureMarkers(dungeon, 928L);
        var repeated = layout.featureMarkers(dungeon, 928L);

        assertEquals(first, repeated);
        assertNotEquals(
            first.get(new DungeonPieceLayout.MarkerKey("encounter", "encounter_1")),
            first.get(new DungeonPieceLayout.MarkerKey("encounter", "encounter_2"))
        );
        var selectedAcrossSeeds = new HashSet<BlockPos>();
        for (long seed = 0; seed < 64; seed++) {
            selectedAcrossSeeds.add(layout.featureMarkers(dungeon, seed).get(
                new DungeonPieceLayout.MarkerKey("encounter", "encounter_1")
            ));
        }
        assertEquals(
            java.util.Set.of(new BlockPos(2, 1, 2), new BlockPos(8, 1, 8)),
            selectedAcrossSeeds
        );
    }

    @Test
    void loadsPokemonTowerSkinPlanFromPackagedResources() throws Exception {
        var pieces = new HashMap<String, DungeonPieceDefinition>();
        for (String id : new String[] {
            "start", "encounter_room", "stairs_up", "room", "boss", "exit", "treasure"
        }) {
            var piece = DungeonPieceDefinition.parse(resourceJson(
                "data/cobbleventure/dungeon_pieces/pokemon_tower/" + id + ".json"
            ));
            pieces.put(piece.id(), piece);
        }
        var authored = DungeonAuthoredPlanDefinition.parse(resourceJson(
            "data/cobbleventure/dungeon_plans/generation_1/rocket_pokemon_tower_test.json"
        ));
        var authoredRoot = resourceJson(
            "data/cobbleventure/dungeons/generation_1/rocket_pokemon_tower.json"
        );
        useAuthoredTowerPlan(authoredRoot);
        var dungeon = DungeonDefinition.parse(authoredRoot);

        DungeonPieceLayout layout = DungeonPieceLayout.generate(
            dungeon, pieces.values(), Map.of(authored.id(), authored), 421L
        );
        Map<DungeonPieceLayout.MarkerKey, BlockPos> features =
            layout.featureMarkers(dungeon, 421L);

        assertEquals(new BlockPos(4, 2, 8), layout.requiredMarker("entry", null));
        assertEquals(
            new BlockPos(73, 10, 9),
            features.get(new DungeonPieceLayout.MarkerKey("boss", "tower_admin"))
        );
        for (String encounter : List.of(
            "memorial_guard_1", "memorial_guard_2", "upper_guard_1", "upper_guard_2"
        )) {
            assertNotNull(features.get(
                new DungeonPieceLayout.MarkerKey("encounter", encounter)
            ), encounter);
        }
        assertEquals(
            new BlockPos(57, 10, 27),
            features.get(new DungeonPieceLayout.MarkerKey(
                "objective", "security_switch"
            ))
        );
        assertTrue(java.util.Set.of(
            new BlockPos(57, 10, 25), new BlockPos(54, 10, 6)
        ).contains(features.get(
            new DungeonPieceLayout.MarkerKey("loot", "loot_1")
        )));
        assertEquals(new BlockPos(93, 10, 8), layout.requiredMarker("exit", null));
    }

    @Test
    void rejectsGateWhenNoMarkerKeepsItsRequirementReachable() throws Exception {
        Map<String, DungeonPieceDefinition> pieces = pokemonTowerPieces();
        DungeonAuthoredPlanDefinition authored = DungeonAuthoredPlanDefinition.parse(
            resourceJson(
                "data/cobbleventure/dungeon_plans/generation_1/rocket_pokemon_tower_test.json"
            )
        );
        var root = resourceJson(
            "data/cobbleventure/dungeons/generation_1/rocket_pokemon_tower.json"
        );
        useAuthoredTowerPlan(root);
        root.getAsJsonArray("gates").get(0).getAsJsonObject()
            .add("requires", JsonParser.parseString("[\"tower_admin\"]"));
        DungeonDefinition dungeon = DungeonDefinition.parse(root);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            DungeonPieceLayout.generate(
                dungeon, pieces.values(), Map.of(authored.id(), authored), 421L
            )
        );

        assertTrue(error.getMessage().contains(
            "no gate marker with reachable requirements"
        ));
    }

    private static void useAuthoredTowerPlan(com.google.gson.JsonObject root) {
        root.add("plan", JsonParser.parseString("""
            {"mode":"authored",
             "plan_ids":["cobbleventure:dungeon_plan/rocket_pokemon_tower_test"],
             "seed_policy":"fixed","fallback":"reject_entry",
             "generation_timeout_ms":1000,"max_attempts":32}
            """).getAsJsonObject());
        root.add("layout", JsonParser.parseString("""
            {"mode":"fixed","critical_path_rooms":[6,6],
             "branch_count":[1,1],"branch_depth":[1,1],"loop_chance":0}
            """).getAsJsonObject());
    }

    @Test
    void resolvesConfiguredAuthoredPlanThroughRuntimeLayout() throws Exception {
        Map<String, DungeonPieceDefinition> pieces = pieces();
        DungeonAuthoredPlanDefinition authored = plan(
            "cobbleventure:dungeon_plan/test", 77L, 10
        );
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        );
        assertNotNull(stream);
        DungeonDefinition dungeon;
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            root.add("plan", JsonParser.parseString("""
                {"mode":"authored","plan_ids":["cobbleventure:dungeon_plan/test"],
                 "seed_policy":"fixed","fallback":"reject_entry"}
                """).getAsJsonObject());
            root.add("terrain", JsonParser.parseString("""
                {"mode":"nbt_pieces","piece_pool":"cobbleventure:authored_test",
                 "bounds":[20,8,8]}
                """).getAsJsonObject());
            root.add("layout", JsonParser.parseString("""
                {"mode":"fixed","critical_path_rooms":[3,3],
                 "branch_count":[0,0],"branch_depth":[1,1],"loop_chance":0}
                """).getAsJsonObject());
            addSyntheticFallbacks(root);
            dungeon = DungeonDefinition.parse(root);
        }

        DungeonPieceLayout layout = DungeonPieceLayout.generate(
            dungeon, pieces.values(), Map.of(authored.id(), authored), 991L
        );

        assertEquals(77L, layout.plan().seed());
        assertEquals(new BlockPos(2, 1, 2), layout.requiredMarker("entry", null));
        assertEquals(new BlockPos(12, 1, 2), layout.requiredMarker("exit", null));
    }

    @Test
    void authoredPoolSelectionIsDeterministicAndCanReachEveryPlan() throws Exception {
        Map<String, DungeonPieceDefinition> pieces = pieces();
        DungeonAuthoredPlanDefinition first = plan(
            "cobbleventure:dungeon_plan/first", 77L, 10
        );
        DungeonAuthoredPlanDefinition second = plan(
            "cobbleventure:dungeon_plan/second", 88L, 10
        );
        DungeonDefinition dungeon = dungeonWithPlan("""
            {"mode":"authored_pool","plan_ids":[
                 "cobbleventure:dungeon_plan/first",
                 "cobbleventure:dungeon_plan/second"],
             "seed_policy":"random_per_run","fallback":"reject_entry"}
            """);
        Map<String, DungeonAuthoredPlanDefinition> plans = Map.of(
            first.id(), first, second.id(), second
        );

        long chosen = DungeonPieceLayout.generate(
            dungeon, pieces.values(), plans, 4096L
        ).plan().seed();
        assertEquals(chosen, DungeonPieceLayout.generate(
            dungeon, pieces.values(), plans, 4096L
        ).plan().seed());

        var selectedSeeds = new HashSet<Long>();
        for (long seed = 0; seed < 10_000 && selectedSeeds.size() < 2; seed++) {
            selectedSeeds.add(DungeonPieceLayout.generate(
                dungeon, pieces.values(), plans, seed
            ).plan().seed());
        }
        assertEquals(java.util.Set.of(77L, 88L), selectedSeeds);
    }

    @Test
    void parsesAndValidatesConnectedStartBossExitPlan() {
        Map<String, DungeonPieceDefinition> pieces = pieces();
        DungeonAuthoredPlanDefinition authored = plan(
            "cobbleventure:dungeon_plan/test", 77L, 10
        );

        DungeonPiecePlan plan = authored.toPlan(pieces);
        DungeonPiecePlanValidator.validate(
            plan, pieces, "cobbleventure:authored_test", new BlockPos(20, 8, 8)
        );

        assertEquals("cobbleventure:dungeon_plan/test", authored.id());
        assertEquals(3, plan.placements().size());
        assertEquals("boss", plan.placements().get(1).role());
        assertEquals(2, plan.links().size());
    }

    @Test
    void rejectsLinkWhoseConnectorsDoNotActuallyMeet() {
        Map<String, DungeonPieceDefinition> pieces = pieces();
        DungeonPiecePlan shifted = plan(
            "cobbleventure:dungeon_plan/test", 77L, 11
        ).toPlan(pieces);

        assertThrows(IllegalStateException.class, () ->
            DungeonPiecePlanValidator.validate(
                shifted, pieces, "cobbleventure:authored_test",
                new BlockPos(20, 8, 8)
            )
        );
    }

    private DungeonDefinition dungeonWithPlan(String planJson) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        );
        assertNotNull(stream);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            root.add("plan", JsonParser.parseString(planJson).getAsJsonObject());
            root.add("terrain", JsonParser.parseString("""
                {"mode":"nbt_pieces","piece_pool":"cobbleventure:authored_test",
                 "bounds":[20,8,8]}
                """).getAsJsonObject());
            root.add("layout", JsonParser.parseString("""
                {"mode":"fixed","critical_path_rooms":[3,3],
                 "branch_count":[0,0],"branch_depth":[1,1],"loop_chance":0}
                """).getAsJsonObject());
            addSyntheticFallbacks(root);
            return DungeonDefinition.parse(root);
        }
    }

    private static void addSyntheticFallbacks(com.google.gson.JsonObject root) {
        int index = 0;
        for (var encounter : root.getAsJsonArray("encounters")) {
            encounter.getAsJsonObject().add(
                "position", JsonParser.parseString(
                    "[%d,1,1]".formatted(index++ % 3)
                )
            );
        }
        for (var station : root.getAsJsonObject("support")
            .getAsJsonArray("healing_stations")) {
            station.getAsJsonObject().add(
                "position", JsonParser.parseString("[2,1,2]")
            );
        }
        index = 0;
        for (var container : root.getAsJsonObject("loot")
            .getAsJsonArray("containers")) {
            container.getAsJsonObject().add(
                "position", JsonParser.parseString(
                    "[%d,1,0]".formatted(index++ % 3)
                )
            );
        }
        root.add("gates", JsonParser.parseString("[]"));
        root.getAsJsonObject("completion").add(
            "clear_exit_position", JsonParser.parseString("[0,1,2]")
        );
    }

    private com.google.gson.JsonObject resourceJson(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private Map<String, DungeonPieceDefinition> pokemonTowerPieces() throws Exception {
        var pieces = new HashMap<String, DungeonPieceDefinition>();
        for (String id : new String[] {
            "start", "encounter_room", "stairs_up", "room", "boss", "exit", "treasure"
        }) {
            var piece = DungeonPieceDefinition.parse(resourceJson(
                "data/cobbleventure/dungeon_pieces/pokemon_tower/" + id + ".json"
            ));
            pieces.put(piece.id(), piece);
        }
        return pieces;
    }

    private static DungeonAuthoredPlanDefinition plan(
        String planId, long seed, int exitX
    ) {
        return DungeonAuthoredPlanDefinition.parse(JsonParser.parseString("""
            {
              "schema_version":1,
              "plan_id":"%s",
              "seed":%d,
              "bounds":[20,8,8],
              "placements":[
                {"piece_id":"cobbleventure:dungeon_piece/authored/start",
                 "origin":[0,0,0],"rotation":"none","critical_path":true},
                {"piece_id":"cobbleventure:dungeon_piece/authored/boss",
                 "origin":[5,0,0],"rotation":"none","critical_path":true},
                {"piece_id":"cobbleventure:dungeon_piece/authored/exit",
                 "origin":[%d,0,0],"rotation":"none","critical_path":true}
              ],
              "links":[
                {"from_index":0,"from_connector":"east","to_index":1,
                 "to_connector":"west","critical_path":true},
                {"from_index":1,"from_connector":"east","to_index":2,
                 "to_connector":"west","critical_path":true}
              ]
            }
            """.formatted(planId, seed, exitX)).getAsJsonObject());
    }

    private static Map<String, DungeonPieceDefinition> pieces() {
        DungeonPieceDefinition start = piece("start", "start", "entry");
        DungeonPieceDefinition boss = piece("boss", "boss", "boss");
        DungeonPieceDefinition exit = piece("exit", "exit", "exit");
        return Map.of(start.id(), start, boss.id(), boss, exit.id(), exit);
    }

    private static DungeonPieceDefinition piece(
        String id, String role, String marker
    ) {
        return DungeonPieceDefinition.parse(JsonParser.parseString("""
            {
              "schema_version":1,
              "piece_id":"cobbleventure:dungeon_piece/authored/%s",
              "structure":"cobbleventure:dungeon/authored/%s",
              "role":"%s",
              "size":[5,5,5],
              "weight":1,
              "allow_rotation":false,
              "tags":["cobbleventure:authored_test"],
              "connectors":[
                {"id":"west","position":[0,1,2],"facing":"west",
                 "socket":"cobbleventure:socket/test","tags":[]},
                {"id":"east","position":[4,1,2],"facing":"east",
                 "socket":"cobbleventure:socket/test","tags":[]}
              ],
              "markers":[{"id":"%s","kind":"%s","position":[2,1,2]}]
            }
            """.formatted(id, id, role, marker, marker)).getAsJsonObject());
    }
}
