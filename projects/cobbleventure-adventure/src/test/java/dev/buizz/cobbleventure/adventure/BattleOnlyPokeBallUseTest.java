package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BattleOnlyPokeBallUseTest {
    @Test
    void battlePermissionIsResolvedForThePlayerWhoUsedTheBall() {
        UUID host = UUID.randomUUID();
        UUID remotePlayer = UUID.randomUUID();
        Set<UUID> activePlayers = Set.of(host);

        assertTrue(BattleOnlyPokeBallUse.hasActiveBattle(host, activePlayers::contains));
        assertFalse(BattleOnlyPokeBallUse.hasActiveBattle(remotePlayer, activePlayers::contains));

        activePlayers = Set.of(remotePlayer);
        assertFalse(BattleOnlyPokeBallUse.hasActiveBattle(host, activePlayers::contains));
        assertTrue(BattleOnlyPokeBallUse.hasActiveBattle(remotePlayer, activePlayers::contains));
    }
}
