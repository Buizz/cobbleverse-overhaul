package dev.buizz.cobbleventure.playermenu;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MapSelectionPolicyTest {
    private static final String PALLET = "cobbleventure:settlement/pallet_town";

    @Test
    void visitedSettlementIsAccepted() {
        MapSelectionPolicy.Decision result = MapSelectionPolicy.select(
            PALLET, Set.of(PALLET), false
        );

        assertTrue(result.accepted());
        assertEquals(PALLET, result.settlementId());
    }

    @Test
    void unvisitedSettlementIsRejectedForOrdinaryPlayer() {
        MapSelectionPolicy.Decision result = MapSelectionPolicy.select(
            PALLET, Set.of(), false
        );

        assertFalse(result.accepted());
        assertNull(result.settlementId());
        assertEquals("방문한 마을만 선택할 수 있습니다.", result.message());
    }

    @Test
    void privilegedPlayerMaySelectUnvisitedSettlement() {
        MapSelectionPolicy.Decision result = MapSelectionPolicy.select(
            PALLET, Set.of(), true
        );

        assertTrue(result.accepted());
        assertEquals(PALLET, result.settlementId());
    }

    @Test
    void arbitraryMapTileIsNeverASettlementSelection() {
        MapSelectionPolicy.Decision result = MapSelectionPolicy.select(
            null, Set.of(PALLET), true
        );

        assertFalse(result.accepted());
        assertNull(result.settlementId());
        assertEquals("마을을 선택해야 합니다.", result.message());
    }
}
