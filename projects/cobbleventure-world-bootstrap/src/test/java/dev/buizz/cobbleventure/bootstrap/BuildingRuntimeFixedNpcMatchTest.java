package dev.buizz.cobbleventure.bootstrap;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BuildingRuntimeFixedNpcMatchTest {
    @Test
    void exactAssignmentTakesPriorityOverNumberedDealerPattern() {
        Map<String, String> assignments = Map.of(
            "room_1:blackjack_dealer_*", "cobbleventure:npc/blackjack_dealer",
            "room_1:blackjack_dealer_2", "cobbleventure:npc/special_dealer"
        );

        assertEquals(
            "cobbleventure:npc/blackjack_dealer",
            FixedNpcAssignments.match(
                assignments, "room_1:blackjack_dealer_1"
            )
        );
        assertEquals(
            "cobbleventure:npc/special_dealer",
            FixedNpcAssignments.match(
                assignments, "room_1:blackjack_dealer_2"
            )
        );
        assertNull(FixedNpcAssignments.match(
            assignments, "room_1:chip_clerk_1"
        ));
    }
}
