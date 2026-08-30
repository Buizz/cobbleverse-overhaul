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
}
