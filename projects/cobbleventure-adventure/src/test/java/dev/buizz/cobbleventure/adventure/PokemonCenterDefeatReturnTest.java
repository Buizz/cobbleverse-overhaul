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
}
