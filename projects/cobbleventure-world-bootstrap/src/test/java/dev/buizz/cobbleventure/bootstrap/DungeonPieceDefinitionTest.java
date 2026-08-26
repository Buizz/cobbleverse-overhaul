package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class DungeonPieceDefinitionTest {
    @Test
    void parsesConnectorsAndSemanticMarkers() {
        DungeonPieceDefinition piece = parse("""
            {
              "schema_version": 1,
              "piece_id": "cobbleventure:dungeon_piece/rocket/start",
              "structure": "cobbleventure:dungeon/team_rocket/start",
              "role": "start",
              "size": [16, 8, 16],
              "weight": 10,
              "allow_rotation": true,
              "tags": ["cobbleventure:theme/team_rocket"],
              "connectors": [
                {
                  "id": "north_hall",
                  "position": [7, 1, 0],
                  "facing": "north",
                  "socket": "cobbleventure:socket/facility_hall",
                  "tags": ["cobbleventure:path/main"]
                }
              ],
              "markers": [
                {
                  "id": "main_entry",
                  "kind": "entry",
                  "position": [7, 1, 3],
                  "reference": "main"
                },
                {
                  "id": "first_grunt",
                  "kind": "encounter",
                  "position": [7, 1, 10]
                }
              ]
            }
            """);

        assertEquals("start", piece.role());
        assertEquals(new BlockPos(16, 8, 16), piece.size());
        assertEquals(Direction.NORTH, piece.connectors().getFirst().facing());
        assertEquals("entry", piece.markers().getFirst().kind());
        assertEquals(0, piece.minimumPerPlan());
        assertEquals(256, piece.maximumPerPlan());
    }

    @Test
    void rejectsConnectorThatDoesNotTouchFacingBoundary() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> parse(basePiece("[7, 1, 5]", "north", "room", "[]"))
        );

        assertTrue(error.getMessage().contains("facing boundary"));
    }

    @Test
    void requiresSemanticMarkerForSpecialRole() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> parse(basePiece("[7, 1, 0]", "north", "boss", "[]"))
        );

        assertTrue(error.getMessage().contains("exactly one boss marker"));
    }

    @Test
    void parsesAndValidatesPerPlanUsageLimits() {
        DungeonPieceDefinition piece = parse(basePiece(
            "[7, 1, 0]", "north", "room", "[]"
        ).replace(
            "\"weight\": 1,",
            "\"weight\": 1, \"min_per_plan\": 1, \"max_per_plan\": 2,"
        ));
        assertEquals(1, piece.minimumPerPlan());
        assertEquals(2, piece.maximumPerPlan());

        assertThrows(IllegalStateException.class, () -> parse(basePiece(
            "[7, 1, 0]", "north", "room", "[]"
        ).replace(
            "\"weight\": 1,",
            "\"weight\": 1, \"min_per_plan\": 3, \"max_per_plan\": 2,"
        )));
    }

    private static DungeonPieceDefinition parse(String json) {
        return DungeonPieceDefinition.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static String basePiece(
        String connectorPosition, String facing, String role, String markers
    ) {
        return """
            {
              "schema_version": 1,
              "piece_id": "cobbleventure:dungeon_piece/test",
              "structure": "cobbleventure:dungeon/test",
              "role": "%s",
              "size": [16, 8, 16],
              "weight": 1,
              "allow_rotation": false,
              "tags": [],
              "connectors": [{
                "id": "door",
                "position": %s,
                "facing": "%s",
                "socket": "cobbleventure:socket/default",
                "tags": []
              }],
              "markers": %s
            }
            """.formatted(role, connectorPosition, facing, markers);
    }
}
