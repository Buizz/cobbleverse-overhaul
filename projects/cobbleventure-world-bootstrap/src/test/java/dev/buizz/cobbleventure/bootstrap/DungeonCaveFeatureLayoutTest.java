package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonCaveFeatureLayoutTest {
    @Test
    void assignsBossToLateMainRoomAndLootToBranchesDeterministically()
        throws Exception {
        DungeonDefinition dungeon = caveDungeon();
        NaturalCaveGenerator.InstancePlan cave = NaturalCaveGenerator.planInstance(
            dungeon.id(), 7721L, BlockPos.ZERO, dungeon.terrain().bounds(),
            dungeon.layout().mode(), 7, 3, dungeon.layout().loopChance()
        );

        Map<DungeonPieceLayout.MarkerKey, BlockPos> first =
            DungeonCaveFeatureLayout.assign(
                dungeon, cave.mainRoomPositions(), cave.branchRoomPositions(), 7721L
            );
        Map<DungeonPieceLayout.MarkerKey, BlockPos> repeated =
            DungeonCaveFeatureLayout.assign(
                dungeon, cave.mainRoomPositions(), cave.branchRoomPositions(), 7721L
            );

        assertEquals(first, repeated);
        String bossId = dungeon.encounters().stream()
            .filter(DungeonDefinition.Encounter::boss).findFirst().orElseThrow().id();
        assertEquals(
            cave.mainRoomPositions().getLast(),
            first.get(new DungeonPieceLayout.MarkerKey("boss", bossId))
        );
        String lootId = dungeon.loot().containers().getFirst().id();
        BlockPos loot = first.get(new DungeonPieceLayout.MarkerKey("loot", lootId));
        assertTrue(cave.branchRoomPositions().stream().anyMatch(
            room -> horizontalDistance(room, loot) <= 3
        ));
        BlockPos trace = first.get(new DungeonPieceLayout.MarkerKey(
            "objective", "legendary_trace"
        ));
        assertTrue(cave.branchRoomPositions().stream().anyMatch(
            room -> horizontalDistance(room, trace) <= 3
        ));
        assertTrue(first.containsKey(new DungeonPieceLayout.MarkerKey(
            "objective", "clear_exit"
        )));
        assertEquals(first.size(), new HashSet<>(first.values()).size());
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
            + Math.abs(first.getZ() - second.getZ());
    }

    private DungeonDefinition caveDungeon() throws Exception {
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
            {"mode":"procedural_cave","cave_generator":"minecraft_worldgen",
             "bounds":[160,48,160]}
            """).getAsJsonObject());
        root.add("layout", JsonParser.parseString("""
            {"mode":"critical_path_branches","critical_path_rooms":[7,7],
             "branch_count":[3,3],"branch_depth":[1,2],"loop_chance":0.2}
            """).getAsJsonObject());
        root.getAsJsonArray("encounters").forEach(
            element -> element.getAsJsonObject().remove("position")
        );
        root.getAsJsonObject("loot").getAsJsonArray("containers").forEach(
            element -> element.getAsJsonObject().remove("position")
        );
        root.getAsJsonObject("completion").remove("clear_exit_position");
        root.add("objectives", JsonParser.parseString("""
            [{"id":"legendary_trace","kind":"investigate","placement":"marker",
              "block":"minecraft:lodestone","activation_radius":3}]
            """).getAsJsonArray());
        return DungeonDefinition.parse(root);
    }
}
