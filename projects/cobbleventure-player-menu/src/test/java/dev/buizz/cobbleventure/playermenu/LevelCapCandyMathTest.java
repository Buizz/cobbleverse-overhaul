package dev.buizz.cobbleventure.playermenu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LevelCapCandyMathTest {
    @Test
    void grantsFortyPercentOfRemainingExperience() {
        assertEquals(12_000, LevelCapCandyMath.experienceYield(30_000));
        assertEquals(400, LevelCapCandyMath.experienceYield(1_000));
    }

    @Test
    void repeatedUseRecalculatesFromTheNewRemainder() {
        int first = LevelCapCandyMath.experienceYield(30_000);
        int second = LevelCapCandyMath.experienceYield(30_000 - first);

        assertEquals(12_000, first);
        assertEquals(7_200, second);
    }

    @Test
    void grantsAtLeastOneExperienceWithoutOvershooting() {
        assertEquals(1, LevelCapCandyMath.experienceYield(1));
        assertEquals(0, LevelCapCandyMath.experienceYield(0));
        assertEquals(0, LevelCapCandyMath.experienceYield(-1));
    }

    @Test
    void avoidsOverflowForLargeExperienceValues() {
        assertEquals(858_993_458, LevelCapCandyMath.experienceYield(Integer.MAX_VALUE));
    }
}
