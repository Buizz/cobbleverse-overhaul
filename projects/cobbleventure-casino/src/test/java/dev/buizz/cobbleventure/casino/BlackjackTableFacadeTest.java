package dev.buizz.cobbleventure.casino;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class BlackjackTableFacadeTest {
    @Test
    void recognizesAllPlayingCardsContentAsDecorationCandidates() {
        assertTrue(BlackjackTableFacade.isPlayingCardsId(
            ResourceLocation.fromNamespaceAndPath("playingcards", "poker_chip")
        ));
        assertTrue(BlackjackTableFacade.isPlayingCardsId(
            ResourceLocation.fromNamespaceAndPath("playingcards", "card")
        ));
    }

    @Test
    void leavesCasinoBackendAndUnrelatedEntitiesFunctional() {
        assertFalse(BlackjackTableFacade.isPlayingCardsId(
            ResourceLocation.fromNamespaceAndPath("cobblemoncasino", "blackjack_table")
        ));
        assertFalse(BlackjackTableFacade.isPlayingCardsId(
            ResourceLocation.fromNamespaceAndPath("easy_npc", "humanoid")
        ));
    }
}
