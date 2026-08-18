package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BattleLossEconomyTest {
    @Test
    void trainerForfeitChargesTheLossPenalty() {
        assertTrue(BattleLossEconomy.shouldChargeLoss(false, true, false));
    }

    @Test
    void wildEscapeDoesNotChargeTheLossPenalty() {
        assertFalse(BattleLossEconomy.shouldChargeLoss(true, true, false));
    }

    @Test
    void wildPartyWipeStillChargesTheLossPenalty() {
        assertTrue(BattleLossEconomy.shouldChargeLoss(true, false, true));
    }
}
