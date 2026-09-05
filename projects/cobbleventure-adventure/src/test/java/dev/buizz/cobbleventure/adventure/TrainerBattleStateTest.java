package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TrainerBattleStateTest {
    @Test
    void victoryIsScopedByBothPlayerAndSpawnedNpc() {
        TrainerBattleState.BattleData data = new TrainerBattleState.BattleData();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID firstNpc = UUID.randomUUID();
        UUID secondNpc = UUID.randomUUID();

        data.setDefeated(firstPlayer, firstNpc, true);

        assertTrue(data.isDefeated(firstPlayer, firstNpc));
        assertFalse(data.isDefeated(firstPlayer, secondNpc));
        assertFalse(data.isDefeated(secondPlayer, firstNpc));

        data.setDefeated(firstPlayer, firstNpc, false);
        assertFalse(data.isDefeated(firstPlayer, firstNpc));
    }
}
