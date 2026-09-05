package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EventBattleBridgeMatchingTest {
    @Test
    void pendingCvesBattleAcceptsTrainerBattleRegardlessOfRctActorSubtype() {
        assertTrue(EventBattleBridge.shouldAttachTrainerBattle(false));
    }

    @Test
    void pendingCvesBattleNeverAttachesToWildBattle() {
        assertFalse(EventBattleBridge.shouldAttachTrainerBattle(true));
    }
}
