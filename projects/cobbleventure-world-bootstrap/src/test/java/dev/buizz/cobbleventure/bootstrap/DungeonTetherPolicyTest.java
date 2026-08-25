package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DungeonTetherPolicyTest {
    @Test
    void classifiesCooperativeMemberDistancesAtConfiguredBoundaries() {
        assertEquals(
            DungeonTetherPolicy.Zone.TOGETHER,
            DungeonTetherPolicy.classify(32.0D * 32.0D, 32, 48)
        );
        assertEquals(
            DungeonTetherPolicy.Zone.WARNING,
            DungeonTetherPolicy.classify(40.0D * 40.0D, 32, 48)
        );
        assertEquals(
            DungeonTetherPolicy.Zone.EXCEEDED,
            DungeonTetherPolicy.classify(48.01D * 48.01D, 32, 48)
        );
    }
}
