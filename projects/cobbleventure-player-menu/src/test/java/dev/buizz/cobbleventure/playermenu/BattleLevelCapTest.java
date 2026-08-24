package dev.buizz.cobbleventure.playermenu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BattleLevelCapTest {
    @Test
    void scalesCurrentHealthProportionallyWhenLevelIsLowered() {
        assertEquals(30, BattleLevelCap.scaledHealth(50, 100, 60));
    }

    @Test
    void preservesFaintedState() {
        assertEquals(0, BattleLevelCap.scaledHealth(0, 100, 60));
    }

    @Test
    void preservesLivingStateAtVeryLowHealth() {
        assertEquals(1, BattleLevelCap.scaledHealth(1, 500, 20));
    }

    @Test
    void neverExceedsTargetMaximum() {
        assertEquals(60, BattleLevelCap.scaledHealth(120, 100, 60));
    }
}
