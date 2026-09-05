package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EventTriggerExecutorTest {
    @Test
    void battleLookupUsesTheTriggeringPlayersUuid() {
        UUID battlingPlayer = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();

        assertTrue(EventTriggerExecutor.hasActiveBattle(
            battlingPlayer, battlingPlayer::equals
        ));
        assertFalse(EventTriggerExecutor.hasActiveBattle(
            otherPlayer, battlingPlayer::equals
        ));
    }

    @Test
    void pendingTrainerBattleBlocksASecondEventEvenWithoutRegistryEntry() {
        UUID player = UUID.randomUUID();

        assertTrue(EventTriggerExecutor.blocksEventStart(
            player, ignored -> false, player::equals
        ));
        assertTrue(EventTriggerExecutor.blocksEventStart(
            player, player::equals, ignored -> false
        ));
        assertFalse(EventTriggerExecutor.blocksEventStart(
            player, ignored -> false, ignored -> false
        ));
    }
}
