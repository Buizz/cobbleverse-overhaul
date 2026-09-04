package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonGeneratedTrainerTest {
    @Test
    void selectsPokemonCountInsideTheConfiguredRange() {
        var generation = new DungeonDefinition.GeneratedTrainer(
            List.of(
                new DungeonDefinition.WeightedSpecies("cobblemon:rattata", 1),
                new DungeonDefinition.WeightedSpecies("cobblemon:koffing", 1),
                new DungeonDefinition.WeightedSpecies("cobblemon:zubat", 1),
                new DungeonDefinition.WeightedSpecies("cobblemon:ekans", 1)
            ),
            new DungeonDefinition.IntRange(1, 4), false,
            List.of("시작"), List.of("종료")
        );
        var difficulty = new DungeonDefinition.Difficulty(10, 10, 10, 10);

        var sizes = java.util.stream.LongStream.range(0, 64)
            .mapToObj(seed -> DungeonGeneratedTrainer.generate(
                generation, difficulty, seed * 104729L
            ).team().size())
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(sizes.stream().allMatch(size -> size >= 1 && size <= 4));
        assertTrue(sizes.size() > 1);
    }

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
