package dev.buizz.cobbleventure.adventure.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BattleOnlyPokeBallClientUseTest {
    @Test
    void integratedServerEventNeverUsesTheHostsClientBattleState() {
        assertFalse(BattleOnlyPokeBallClientUse.shouldBlockUse(false, false));
        assertFalse(BattleOnlyPokeBallClientUse.shouldBlockUse(false, true));
    }

    @Test
    void logicalClientOnlyBlocksPredictionOutsideItsOwnBattle() {
        assertTrue(BattleOnlyPokeBallClientUse.shouldBlockUse(true, false));
        assertFalse(BattleOnlyPokeBallClientUse.shouldBlockUse(true, true));
    }
}
