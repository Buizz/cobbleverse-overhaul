package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DungeonPiecePoolValidatorTest {
    @Test
    void acceptsPackagedRocketPoolForEveryGeneratedRocketDungeon() throws Exception {
        List<DungeonPieceDefinition> pieces = packagedRocketPieces();
        for (String name : List.of(
            "rocket_casino_hideout", "rocket_silph_company", "rocket_pokemon_tower"
        )) {
            DungeonPiecePoolValidator.validate(packagedDungeon(name), pieces);
        }
    }

    @Test
    void rejectsPoolThatCannotSupplyRequiredObjective() throws Exception {
        DungeonDefinition dungeon = packagedDungeon("rocket_pokemon_tower");
        List<DungeonPieceDefinition> pieces = packagedRocketPieces().stream()
            .filter(piece -> !piece.id().endsWith("/treasure"))
            .toList();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> DungeonPiecePoolValidator.validate(dungeon, pieces)
        );

        assertTrue(error.getMessage().contains("objective"));
    }

    private List<DungeonPieceDefinition> packagedRocketPieces() throws Exception {
        List<DungeonPieceDefinition> pieces = new ArrayList<>();
        for (String id : List.of(
            "boss", "corner", "corridor", "dead_end", "encounter_room", "exit",
            "junction", "room", "stairs_down", "stairs_up", "start", "support",
            "treasure"
        )) {
            pieces.add(DungeonPieceDefinition.parse(resource(
                "data/cobbleventure/dungeon_pieces/rocket/" + id + ".json"
            )));
        }
        return pieces;
    }

    private DungeonDefinition packagedDungeon(String name) throws Exception {
        return DungeonDefinition.parse(resource(
            "data/cobbleventure/dungeons/generation_1/" + name + ".json"
        ));
    }

    private com.google.gson.JsonObject resource(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertTrue(stream != null, "Missing test resource: " + path);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
