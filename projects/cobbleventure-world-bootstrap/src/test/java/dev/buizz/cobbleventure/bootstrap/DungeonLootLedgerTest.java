package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DungeonLootLedgerTest {
    private static final UUID PLAYER = UUID.fromString(
        "00000000-0000-0000-0000-000000000001"
    );

    @Test
    void separatesKeepPendingAndRemovableFailurePolicies() {
        assertEquals(
            DungeonLootLedger.LootDisposition.KEEP_COLLECTED,
            DungeonLootLedger.disposition("keep_collected")
        );
        assertEquals(
            DungeonLootLedger.LootDisposition.GRANT_ON_CLEAR,
            DungeonLootLedger.disposition("grant_on_clear_only")
        );
        assertEquals(
            DungeonLootLedger.LootDisposition.REMOVE_ON_FAILURE,
            DungeonLootLedger.disposition("remove_run_loot")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DungeonLootLedger.disposition("unknown")
        );
    }

    @Test
    void emptyPolicyRecordsDoNotCreatePhantomRewards() {
        DungeonLootLedger ledger = new DungeonLootLedger();
        ledger.record("keep_collected", PLAYER, List.of());
        ledger.record("grant_on_clear_only", PLAYER, List.of());
        ledger.record("remove_run_loot", PLAYER, List.of());

        assertTrue(ledger.pending(PLAYER).isEmpty());
        assertTrue(ledger.removable(PLAYER).isEmpty());
    }
}
