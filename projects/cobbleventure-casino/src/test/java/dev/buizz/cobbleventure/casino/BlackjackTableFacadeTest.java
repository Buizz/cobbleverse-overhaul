package dev.buizz.cobbleventure.casino;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
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

    @Test
    void dealerInteractionRunsBeforeTheGenericV5NpcHandler() throws Exception {
        Method handler = BlackjackTableFacade.class.getDeclaredMethod(
            "onDealerInteract",
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract.class
        );
        SubscribeEvent subscription = handler.getAnnotation(SubscribeEvent.class);

        assertEquals(EventPriority.HIGHEST, subscription.priority());
    }
}
