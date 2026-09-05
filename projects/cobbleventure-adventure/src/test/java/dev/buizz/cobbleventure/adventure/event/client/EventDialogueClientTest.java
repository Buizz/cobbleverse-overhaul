package dev.buizz.cobbleventure.adventure.event.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventDialogueClientTest {
    @Test
    void battleAwaitDoesNotInstallTheFallbackMovementLockScreen() {
        assertFalse(EventDialogueClient.usesMovementLockScreen("battle"));
    }

    @Test
    void otherAwaitsKeepTheFallbackMovementLockScreen() {
        assertTrue(EventDialogueClient.usesMovementLockScreen("starter_roulette"));
        assertTrue(EventDialogueClient.usesMovementLockScreen("transition"));
        assertTrue(EventDialogueClient.usesMovementLockScreen("move"));
    }

    @Test
    void daycareScreenCanReplaceTheCompletedDialogueDuringTransition() {
        String daycare =
            "dev.buizz.cobbleventure.adventure.daycare.client.DaycareScreen";

        assertTrue(EventDialogueClient.allowsExternalScreen(daycare, "transition"));
        assertFalse(EventDialogueClient.allowsExternalScreen(daycare, "say"));
    }

    @Test
    void playerMenuAndBagCanOpenDuringBattleAwait() {
        String playerMenu =
            "dev.buizz.cobbleventure.playermenu.client.PlayerMenuScreen";
        String bag = "dev.buizz.cobbleventure.playermenu.client.BagScreen";
        String battleTargetSelection =
            "dev.buizz.cobbleventure.playermenu.client.BagPokemonSelectScreen";

        assertTrue(EventDialogueClient.allowsExternalScreen(playerMenu, "battle"));
        assertTrue(EventDialogueClient.allowsExternalScreen(bag, "battle"));
        assertTrue(EventDialogueClient.allowsExternalScreen(battleTargetSelection, "battle"));
        assertFalse(EventDialogueClient.allowsExternalScreen(playerMenu, "move"));
        assertFalse(EventDialogueClient.allowsExternalScreen(bag, "transition"));
    }
}
