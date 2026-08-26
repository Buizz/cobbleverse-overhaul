package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonAuthoredPlanDefinitionTest {
    @Test
    void loadsRocketSkinPokemonTowerPlanFromPackagedResources() throws Exception {
        var pieces = new HashMap<String, DungeonPieceDefinition>();
        for (String id : new String[] {
            "start", "encounter_room", "stairs_up", "room", "boss", "exit", "treasure"
        }) {
            var piece = DungeonPieceDefinition.parse(resourceJson(
                "data/cobbleventure/dungeon_pieces/rocket/" + id + ".json"
            ));
            pieces.put(piece.id(), piece);
        }
        var authored = DungeonAuthoredPlanDefinition.parse(resourceJson(
            "data/cobbleventure/dungeon_plans/generation_1/rocket_pokemon_tower_test.json"
        ));
        var dungeon = DungeonDefinition.parse(resourceJson(
            "data/cobbleventure/dungeons/generation_1/rocket_pokemon_tower.json"
        ));

        DungeonPieceLayout layout = DungeonPieceLayout.generate(
            dungeon, pieces.values(), Map.of(authored.id(), authored), 421L
        );

        assertEquals(new BlockPos(4, 2, 8), layout.requiredMarker("entry", null));
        assertEquals(new BlockPos(25, 2, 9), layout.requiredMarker("encounter", "encounter_1"));
        assertEquals(new BlockPos(73, 6, 9), layout.requiredMarker("boss", "boss_1"));
        assertEquals(new BlockPos(57, 6, 25), layout.requiredMarker("loot", "loot_1"));
        assertEquals(new BlockPos(93, 6, 8), layout.requiredMarker("exit", null));
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
            return DungeonDefinition.parse(root);
        }
    }

    private com.google.gson.JsonObject resourceJson(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
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
