package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PokemonCenterDefeatReturnTest {
    @Test
    void trainerForfeitIsRecordedAsForcedDefeat() {
        assertTrue(PokemonCenterDefeatReturn.shouldRecordForfeit(false));
    }

    @Test
    void wildEscapeIsNotRecordedAsForcedDefeat() {
        assertFalse(PokemonCenterDefeatReturn.shouldRecordForfeit(true));
    }

    @Test
    void npcEventsStayBlockedUntilDefeatRecoveryHasTeleportedThePlayer() {
        assertTrue(PokemonCenterDefeatReturn.blocksNewNpcEvents(true, false, false));
        assertTrue(PokemonCenterDefeatReturn.blocksNewNpcEvents(false, true, false));
        assertFalse(PokemonCenterDefeatReturn.blocksNewNpcEvents(false, true, true));
        assertFalse(PokemonCenterDefeatReturn.blocksNewNpcEvents(false, false, false));
    }
}
