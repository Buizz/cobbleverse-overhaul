package dev.buizz.cobbleventure.bootstrap;

/** Pure entry checks shared by the runtime and unit tests. */
final class DungeonEntryEligibility {
    private DungeonEntryEligibility() {}

    static Evaluation evaluate(
        DungeonDefinition.Eligibility settings,
        DungeonDefinition.Difficulty difficulty,
        PartySnapshot party
    ) {
        if (party.size() < settings.minimumPartySize()) {
            return new Evaluation(false, Issue.PARTY_TOO_SMALL, 0);
        }
        if (party.size() > settings.maximumPartySize()) {
            return new Evaluation(false, Issue.PARTY_TOO_LARGE, 0);
        }
        if (settings.requireUsablePokemon() && party.usable() == 0) {
            return new Evaluation(false, Issue.NO_USABLE_POKEMON, 0);
        }
        int measuredLevel = settings.levelMeasure().equals("highest")
            ? party.highestLevel() : party.averageLevel();
        boolean outsideRecommended = measuredLevel < difficulty.recommendedMin()
            || measuredLevel > difficulty.recommendedMax();
        if (!outsideRecommended
            || settings.recommendedLevelPolicy().equals("ignore")) {
            return new Evaluation(true, Issue.NONE, measuredLevel);
        }
        return settings.recommendedLevelPolicy().equals("enforce")
            ? new Evaluation(false, Issue.LEVEL_OUTSIDE_RECOMMENDED, measuredLevel)
            : new Evaluation(true, Issue.LEVEL_OUTSIDE_RECOMMENDED, measuredLevel);
    }

    record PartySnapshot(int size, int usable, int averageLevel, int highestLevel) {}
    record Evaluation(boolean allowed, Issue issue, int measuredLevel) {}

    enum Issue {
        NONE,
        PARTY_TOO_SMALL,
        PARTY_TOO_LARGE,
        NO_USABLE_POKEMON,
        LEVEL_OUTSIDE_RECOMMENDED
    }
}
