package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministically resolves a dungeon trainer team and its short battle lines. */
final class DungeonGeneratedTrainer {
    private DungeonGeneratedTrainer() {}

    static Result generate(
        DungeonDefinition.GeneratedTrainer definition,
        DungeonDefinition.Difficulty difficulty,
        long seed
    ) {
        Random random = new Random(seed);
        int teamSize = range(
            random, definition.teamSize().minimum(), definition.teamSize().maximum()
        );
        List<DungeonDefinition.WeightedSpecies> candidates = new ArrayList<>(
            definition.pokemonPool()
        );
        List<Pokemon> team = new ArrayList<>();
        for (int index = 0; index < teamSize; index++) {
            DungeonDefinition.WeightedSpecies selected = weighted(candidates, random);
            team.add(new Pokemon(
                selected.species(), range(
                    random, difficulty.internalMin(), difficulty.internalMax()
                )
            ));
            if (!definition.allowDuplicates()) candidates.remove(selected);
        }
        return new Result(
            List.copyOf(team),
            randomValue(definition.battleStartLines(), random),
            randomValue(definition.battleEndLines(), random)
        );
    }

    private static DungeonDefinition.WeightedSpecies weighted(
        List<DungeonDefinition.WeightedSpecies> candidates, Random random
    ) {
        int total = candidates.stream().mapToInt(
            DungeonDefinition.WeightedSpecies::weight
        ).sum();
        int roll = random.nextInt(total);
        for (DungeonDefinition.WeightedSpecies candidate : candidates) {
            roll -= candidate.weight();
            if (roll < 0) return candidate;
        }
        throw new IllegalStateException("Generated trainer Pokemon pool is empty");
    }

    private static int range(Random random, int minimum, int maximum) {
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static String randomValue(List<String> values, Random random) {
        return values.get(random.nextInt(values.size()));
    }

    record Pokemon(String species, int level) {}
    record Result(List<Pokemon> team, String battleStartLine, String battleEndLine) {}
}
