package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonGeneratedTrainerTest {
    @Test
    void resolvesAStableUniqueTeamAndDialogueFromTheConfiguredPool() {
        var generation = new DungeonDefinition.GeneratedTrainer(
            List.of(
                new DungeonDefinition.WeightedSpecies("cobblemon:rattata", 10),
                new DungeonDefinition.WeightedSpecies("cobblemon:koffing", 5),
                new DungeonDefinition.WeightedSpecies("cobblemon:zubat", 2)
            ),
            new DungeonDefinition.IntRange(2, 2), false,
            List.of("여기서 멈춰라!", "침입자다!"),
            List.of("이럴 수가…", "후퇴한다!")
        );
        var difficulty = new DungeonDefinition.Difficulty(12, 18, 14, 16);

        var first = DungeonGeneratedTrainer.generate(generation, difficulty, 421L);
        var repeated = DungeonGeneratedTrainer.generate(generation, difficulty, 421L);

        assertEquals(first, repeated);
        assertEquals(2, first.team().size());
        assertNotEquals(first.team().get(0).species(), first.team().get(1).species());
        assertTrue(first.team().stream().allMatch(pokemon ->
            pokemon.level() >= 14 && pokemon.level() <= 16
        ));
        assertTrue(generation.battleStartLines().contains(first.battleStartLine()));
        assertTrue(generation.battleEndLines().contains(first.battleEndLine()));
    }
}
