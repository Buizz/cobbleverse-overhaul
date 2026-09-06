package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorldPlanParserTest {
    @Test
    void parsesTileRouteAndCustomHabitatsWithTimeOverrides() {
        var root = JsonParser.parseString("""
            {"tiles":[
              {"q":1,"r":2,"biome":"minecraft:plains",
               "boundary_profile":"cobbleventure:boundary/earthwork",
               "terrain_profile":{"base_height_offset":0,"height_variation":3,"noise_scale_blocks":96},
               "pokemon_habitat":{"source":"route","route_id":"route_one"}},
              {"q":2,"r":2,"biome":"minecraft:forest",
               "boundary_profile":"cobbleventure:boundary/earthwork",
               "terrain_profile":{"base_height_offset":0,"height_variation":3,"noise_scale_blocks":96},
               "pokemon_habitat":{"source":"custom","pokemon_spawns":{
                 "inherit_biome":false,"excluded_species":[],"additions":[],"level_overrides":[],
                 "time_overrides":[{"species":"cobblemon:hoothoot","time":"night"}]
               }}}
            ]}
            """).getAsJsonObject();
        String boundaryId = "cobbleventure:boundary/earthwork";
        var boundary = new WorldPlanModels.BoundaryProfile(boundaryId, "earthwork", 1, 1, 1,
            "solid", "minecraft:stone", List.of(), null);

        var tiles = WorldPlanParser.tiles(root, Map.of(boundaryId, boundary));

        assertEquals("route", tiles.get(0).pokemonHabitat().source());
        assertEquals("route_one", tiles.get(0).pokemonHabitat().routeId());
        assertEquals("night", tiles.get(1).pokemonHabitat().pokemonSpawns()
            .timeOverrides().get("cobblemon:hoothoot"));
    }

    @Test
    void preservesAuthoredLevelWeightsAndLegacyUniformRanges() {
        var root = JsonParser.parseString("""
            {"connections": [{"id":"route_one", "corridor_width_blocks":12,
              "surface_style":"road", "cells":[{"q":-5,"r":7}],
              "pokemon_spawns":{"inherit_biome":false,"level_overrides":[
                {"species":"cobblemon:pidgey","min_level":2,"max_level":5,
                 "level_weights":{"2":10,"3":35,"4":4,"5":1}},
                {"species":"cobblemon:rattata","min_level":2,"max_level":4}
              ],"encounter_pools":{"old_rod":{"level_overrides":[
                {"species":"cobblemon:magikarp","min_level":5,"max_level":10,
                 "level_weights":{"5":9,"10":1}}
              ]}}}
            }]}
            """).getAsJsonObject();
        String boundaryId = "cobbleventure:boundary/dense_tree_line";
        var boundary = new WorldPlanModels.BoundaryProfile(boundaryId, "tree", 1, 1, 1,
            "solid", "minecraft:stone", List.of(), null);
        var spawns = WorldPlanParser.connections(root, Map.of(boundaryId, boundary))
            .getFirst().pokemonSpawns();
        assertEquals(Map.of(2, 10, 3, 35, 4, 4, 5, 1),
            spawns.levelOverrides().get("cobblemon:pidgey").levelWeights());
        assertEquals(Map.of(), spawns.levelOverrides().get("cobblemon:rattata").levelWeights());
        assertEquals(Map.of(5, 9, 10, 1), spawns.pool("old_rod")
            .levelOverrides().get("cobblemon:magikarp").levelWeights());
    }

    @Test
    void parsesBarrierTransitionForCaveEntrance() {
        var root = JsonParser.parseString("""
            {
              "cave_entrances": [{
                "id": "cobbleventure:cave_entrance/test_west",
                "cave": "cobbleventure:cave/test",
                "entrance": "west",
                "transition": "cave_entry",
                "anchor": {"q": 1, "r": 2},
                "facing": "north",
                "structure": "cobbleventure:cave_entrance/stone_mountain"
              }]
            }
            """).getAsJsonObject();

        var entrance = WorldPlanParser.caveEntrances(root).getFirst();

        assertEquals("cobbleventure:cave/test", entrance.cave());
        assertEquals("west", entrance.entrance());
        assertEquals("cave_entry", entrance.surfaceTransition());
    }

    @Test
    void usesRuntimePokemonCenterStructureForCaveEntrances() {
        var root = JsonParser.parseString("""
            {
              "cave_entrances": [
                {
                  "id": "cobbleventure:cave_entrance/default_center",
                  "cave": "cobbleventure:cave/test",
                  "entrance": "west",
                  "transition": "cave_entry",
                  "anchor": {"q": 1, "r": 2},
                  "facing": "north",
                  "structure": "cobbleventure:cave_entrance/stone_mountain",
                  "pokemon_center_enabled": true
                },
                {
                  "id": "cobbleventure:cave_entrance/legacy_center",
                  "cave": "cobbleventure:cave/test",
                  "entrance": "east",
                  "transition": "cave_entry",
                  "anchor": {"q": 2, "r": 2},
                  "facing": "south",
                  "structure": "cobbleventure:cave_entrance/stone_mountain",
                  "pokemon_center": {
                    "structure": "bca:default/one_off/pokecenter",
                    "offset": {"q": 0, "r": 1}
                  }
                }
              ]
            }
            """).getAsJsonObject();

        var entrances = WorldPlanParser.caveEntrances(root);

        assertEquals(
            "cobbleventure:facilities/pokemon_center",
            entrances.get(0).pokemonCenterStructure()
        );
        assertEquals(
            "cobbleventure:facilities/pokemon_center",
            entrances.get(1).pokemonCenterStructure()
        );
    }

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
