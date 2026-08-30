package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CaveEntrancePlacementPolicyTest {
    @Test
    void defersStartupPlacementUntilTheWorldMapIsComplete() {
        assertFalse(CaveEntrancePlacementPolicy.restoreEntrancesAtStartup(false));
        assertTrue(CaveEntrancePlacementPolicy.restoreEntrancesAtStartup(true));
    }

    @Test
    void doesNotOverwriteAnEntranceThatAlreadyHasItsPlacementMarker() {
        assertTrue(CaveEntrancePlacementPolicy.placeTemplate(false));
        assertFalse(CaveEntrancePlacementPolicy.placeTemplate(true));
    }
}
