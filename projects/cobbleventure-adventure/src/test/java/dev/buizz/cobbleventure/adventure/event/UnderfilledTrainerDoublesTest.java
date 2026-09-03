package dev.buizz.cobbleventure.adventure.event;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class UnderfilledTrainerDoublesTest {
    @Test
    void permitsOnlyOnePokemonInAnOwnedTrainerDoublesBattle() {
        assertTrue(UnderfilledTrainerDoubles.allows(1, 2, 1, true, true));
        for (int count : new int[] {0, 2, 6}) {
            assertFalse(UnderfilledTrainerDoubles.allows(1, 2, count, true, true));
        }
        assertFalse(UnderfilledTrainerDoubles.allows(1, 1, 1, true, true));
        assertFalse(UnderfilledTrainerDoubles.allows(2, 1, 1, true, true));
        assertFalse(UnderfilledTrainerDoubles.allows(1, 2, 1, false, true));
        assertFalse(UnderfilledTrainerDoubles.allows(1, 2, 1, true, false));
    }
}
