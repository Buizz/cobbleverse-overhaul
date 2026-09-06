package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BattleIntroPortraitLayoutTest {
    @Test
    void scalesBothTrainerPortraitsToEightyPercentOfTheOriginalPolicy() {
        assertEquals(0.8F, BattleIntroPortraitLayout.PORTRAIT_SCALE);
        assertEquals(34, BattleIntroPortraitLayout.scaleForHeight(40));
        assertEquals(74, BattleIntroPortraitLayout.scaleForHeight(100));
        assertEquals(77, BattleIntroPortraitLayout.scaleForHeight(180));
    }
}
