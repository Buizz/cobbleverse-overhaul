package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonPiecePlannerTest {
    @Test
    void createsDeterministicCriticalPathAndBranchesWithoutOverlap() {
        List<DungeonPieceDefinition> pieces = testPieces();
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(80, 16, 80), 6, 6, 2, 2, 1, 1, 100
        );

        DungeonPiecePlan first = DungeonPiecePlanner.generate(pieces, settings, 7734L);
        DungeonPiecePlan repeated = DungeonPiecePlanner.generate(pieces, settings, 7734L);

        assertEquals(first, repeated);
        assertEquals(6, first.placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath).count());
        assertEquals("start", first.placements().getFirst().role());
        assertEquals("boss", first.placements().get(4).role());
        assertEquals("exit", first.placements().get(5).role());
        assertEquals(first.placements().size() - 1, first.links().size());
        assertEquals(2, first.links().stream()
            .filter(link -> !link.criticalPath()).count());
        assertNoOverlap(first);
        first.placements().forEach(placement -> {
            assertTrue(placement.minimum().getX() >= 0);
            assertTrue(placement.minimum().getY() >= 0);
            assertTrue(placement.minimum().getZ() >= 0);
            assertTrue(placement.minimum().getX() + placement.size().getX()
                <= first.bounds().getX());
            assertTrue(placement.minimum().getY() + placement.size().getY()
                <= first.bounds().getY());
            assertTrue(placement.minimum().getZ() + placement.size().getZ()
                <= first.bounds().getZ());
        });
    }

    @Test
    void rejectsPoolThatCannotFitInsideBounds() {
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(4, 4, 4), 3, 3, 0, 0, 1, 1, 3
        );

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> DungeonPiecePlanner.generate(testPieces(), settings, 1L)
        );

        assertTrue(error.getMessage().contains("planning failed"));
    }

    private static void assertNoOverlap(DungeonPiecePlan plan) {
        for (int first = 0; first < plan.placements().size(); first++) {
            DungeonPiecePlan.Placement a = plan.placements().get(first);
            for (int second = first + 1; second < plan.placements().size(); second++) {
                DungeonPiecePlan.Placement b = plan.placements().get(second);
                boolean overlaps = a.minimum().getX() < b.minimum().getX() + b.size().getX()
                    && a.minimum().getX() + a.size().getX() > b.minimum().getX()
                    && a.minimum().getY() < b.minimum().getY() + b.size().getY()
                    && a.minimum().getY() + a.size().getY() > b.minimum().getY()
                    && a.minimum().getZ() < b.minimum().getZ() + b.size().getZ()
                    && a.minimum().getZ() + a.size().getZ() > b.minimum().getZ();
                assertTrue(!overlaps, "Pieces overlap: " + first + " and " + second);
            }
        }
    }

    private static List<DungeonPieceDefinition> testPieces() {
        return List.of(
            piece("start", "start", fourConnectors(), marker("entry")),
            piece("room", "room", fourConnectors(), "[]"),
            piece("corridor", "corridor", northSouthConnectors(), "[]"),
            piece("junction", "junction", fourConnectors(), "[]"),
            piece("boss", "boss", fourConnectors(), marker("boss")),
            piece("exit", "exit", terminalConnector(), marker("exit")),
            piece("dead_end", "dead_end", terminalConnector(), "[]"),
            piece("treasure", "treasure", terminalConnector(), "[]"),
            piece("support", "support", terminalConnector(), "[]")
        );
    }

    private static DungeonPieceDefinition piece(
        String id, String role, String connectors, String markers
    ) {
        return DungeonPieceDefinition.parse(JsonParser.parseString("""
            {
              "schema_version": 1,
              "piece_id": "cobbleventure:dungeon_piece/test/%s",
              "structure": "cobbleventure:dungeon/test/%s",
              "role": "%s",
              "size": [5, 5, 5],
              "weight": 10,
              "allow_rotation": true,
              "tags": ["cobbleventure:theme/test"],
              "connectors": %s,
              "markers": %s
            }
            """.formatted(id, id, role, connectors, markers)).getAsJsonObject());
    }

    private static String marker(String kind) {
        return """
            [{"id":"%s","kind":"%s","position":[2,1,2]}]
            """.formatted(kind, kind);
    }

    private static String terminalConnector() {
        return """
            [{"id":"north","position":[2,1,0],"facing":"north",
              "socket":"cobbleventure:socket/test","tags":[]}]
            """;
    }

    private static String northSouthConnectors() {
        return """
            [
              {"id":"north","position":[2,1,0],"facing":"north",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"south","position":[2,1,4],"facing":"south",
               "socket":"cobbleventure:socket/test","tags":[]}
            ]
            """;
    }

    private static String fourConnectors() {
        return """
            [
              {"id":"north","position":[2,1,0],"facing":"north",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"south","position":[2,1,4],"facing":"south",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"west","position":[0,1,2],"facing":"west",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"east","position":[4,1,2],"facing":"east",
               "socket":"cobbleventure:socket/test","tags":[]}
            ]
            """;
    }
}
