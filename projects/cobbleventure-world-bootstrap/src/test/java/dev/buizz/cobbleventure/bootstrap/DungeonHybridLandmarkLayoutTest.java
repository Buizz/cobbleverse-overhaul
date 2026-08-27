package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonHybridLandmarkLayoutTest {
    @Test
    void deterministicallyAlignsNbtMarkersToSafeCaveRooms() throws Exception {
        DungeonDefinition dungeon = hybridDungeon();
        DungeonPieceDefinition landmark = DungeonPieceDefinition.parse(
            JsonParser.parseString("""
                {
                  "schema_version":1,
                  "piece_id":"cobbleventure:dungeon_piece/test/hybrid_landmark",
                  "structure":"cobbleventure:dungeon/test/hybrid_landmark",
                  "role":"room",
                  "size":[12,8,12],
                  "weight":1,
                  "allow_rotation":false,
                  "tags":["cobbleventure:test_hybrid"],
                  "connectors":[{
                    "id":"door","position":[5,1,0],"facing":"north",
                    "socket":"cobbleventure:socket/default","tags":[]
                  }],
                  "markers":[
                    {"id":"encounter","kind":"encounter","position":[5,1,5]},
                    {"id":"boss","kind":"boss","position":[6,1,6]},
                    {"id":"loot","kind":"loot","position":[4,1,6]},
                    {"id":"objective","kind":"objective","position":[7,1,6]}
                  ]
                }
                """).getAsJsonObject()
        );
        List<BlockPos> main = List.of(
            new BlockPos(20, 12, 20), new BlockPos(48, 12, 20),
            new BlockPos(76, 12, 20), new BlockPos(104, 12, 20),
            new BlockPos(48, 12, 64), new BlockPos(76, 12, 64)
        );
        List<BlockPos> branches = List.of(
            new BlockPos(20, 12, 104), new BlockPos(48, 12, 104),
            new BlockPos(76, 12, 104), new BlockPos(104, 12, 104)
        );

        DungeonHybridLandmarkLayout.Result first = DungeonHybridLandmarkLayout.plan(
            dungeon, List.of(landmark), main, branches, 7712L
        );
        DungeonHybridLandmarkLayout.Result repeated = DungeonHybridLandmarkLayout.plan(
            dungeon, List.of(landmark), main, branches, 7712L
        );

        assertEquals(first, repeated);
        assertFalse(first.placements().isEmpty());
        String bossId = dungeon.encounters().stream()
            .filter(DungeonDefinition.Encounter::boss).findFirst().orElseThrow().id();
        assertTrue(first.featureMarkers().containsKey(
            new DungeonPieceLayout.MarkerKey("boss", bossId)
        ));
        String lootId = dungeon.loot().containers().getFirst().id();
        assertTrue(first.featureMarkers().containsKey(
            new DungeonPieceLayout.MarkerKey("loot", lootId)
        ));
        assertTrue(first.placements().stream().allMatch(placement -> {
            BlockPos origin = placement.templateOrigin();
            return origin.getX() >= 0 && origin.getY() >= 0 && origin.getZ() >= 0
                && origin.getX() + placement.piece().size().getX()
                    <= dungeon.terrain().bounds().getX()
                && origin.getZ() + placement.piece().size().getZ()
                    <= dungeon.terrain().bounds().getZ();
        }));
    }

    private DungeonDefinition hybridDungeon() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        );
        JsonObject root;
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        root.add("plan", JsonParser.parseString("""
            {"mode":"runtime","seed_policy":"match","fallback":"reject_entry"}
            """).getAsJsonObject());
        root.add("terrain", JsonParser.parseString("""
            {"mode":"hybrid","piece_pool":"cobbleventure:test_hybrid",
             "cave_generator":"minecraft_worldgen","bounds":[128,40,128]}
            """).getAsJsonObject());
        root.add("layout", JsonParser.parseString("""
            {"mode":"critical_path_branches","critical_path_rooms":[5,5],
             "branch_count":[2,2],"branch_depth":[1,1],"loop_chance":0.1}
            """).getAsJsonObject());
        root.getAsJsonArray("encounters").forEach(
            element -> element.getAsJsonObject().remove("position")
        );
        root.getAsJsonObject("loot").getAsJsonArray("containers").forEach(
            element -> element.getAsJsonObject().remove("position")
        );
        root.getAsJsonObject("completion").remove("clear_exit_position");
        root.add("objectives", JsonParser.parseString("""
            [{"id":"power_trace","kind":"investigate","placement":"marker",
              "block":"minecraft:lodestone","activation_radius":3}]
            """).getAsJsonArray());
        return DungeonDefinition.parse(root);
    }
}
