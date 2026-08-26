package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

final class DungeonPieceLoopsTest {
    @Test
    void linksAdjacentUnusedCompatibleConnectors() {
        DungeonPieceDefinition start = piece("start", "start", "east", "entry");
        DungeonPieceDefinition exit = piece("exit", "exit", "west", "exit");
        DungeonPiecePlan plan = new DungeonPiecePlan(
            7L,
            new BlockPos(16, 8, 8),
            List.of(
                placement(0, start.id(), "start", BlockPos.ZERO),
                placement(1, exit.id(), "exit", new BlockPos(5, 0, 0))
            ),
            List.of(new DungeonPiecePlan.Link(0, "primary", 1, "primary", true))
        );

        DungeonPiecePlan looped = DungeonPieceLoops.add(
            plan, Map.of(start.id(), start, exit.id(), exit), 1.0D, 99L
        );

        assertEquals(2, looped.links().size());
        assertEquals("secondary", looped.links().getLast().fromConnector());
        assertEquals("secondary", looped.links().getLast().toConnector());
        assertEquals(plan, DungeonPieceLoops.add(
            plan, Map.of(start.id(), start, exit.id(), exit), 0.0D, 99L
        ));
    }

    private static DungeonPiecePlan.Placement placement(
        int index, String id, String role, BlockPos origin
    ) {
        return new DungeonPiecePlan.Placement(
            index, id, role, origin, Rotation.NONE, origin,
            new BlockPos(5, 5, 5), true
        );
    }

    private static DungeonPieceDefinition piece(
        String id, String role, String facing, String marker
    ) {
        boolean east = facing.equals("east");
        int x = east ? 4 : 0;
        return DungeonPieceDefinition.parse(JsonParser.parseString("""
            {
              "schema_version":1,
              "piece_id":"cobbleventure:dungeon_piece/loop/%s",
              "structure":"cobbleventure:dungeon/loop/%s",
              "role":"%s",
              "size":[5,5,5],
              "weight":1,
              "allow_rotation":false,
              "tags":["cobbleventure:loop_test"],
              "connectors":[
                {"id":"primary","position":[%d,1,1],"facing":"%s",
                 "socket":"cobbleventure:socket/test","tags":[]},
                {"id":"secondary","position":[%d,1,3],"facing":"%s",
                 "socket":"cobbleventure:socket/test","tags":[]}
              ],
              "markers":[{"id":"%s","kind":"%s","position":[2,1,2]}]
            }
            """.formatted(id, id, role, x, facing, x, facing, marker, marker))
            .getAsJsonObject());
    }
}
