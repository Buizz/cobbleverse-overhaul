package dev.buizz.cobbleventure.playermenu;

final class LevelCapCandyMath {
    static final int MINIMUM_LEVEL_GAP = 5;
    private static final int YIELD_PERCENT = 40;

    private LevelCapCandyMath() {}

    static int experienceYield(int remainingExperience) {
        if (remainingExperience <= 0) return 0;
        return Math.max(1, (int) ((long) remainingExperience * YIELD_PERCENT / 100));
    }
}
