package dev.buizz.cobbleventure.bootstrap;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegionalNpcPresetSelectionTest {
    @Test
    void v5ProximityPlacementUsesTheForcedBattleAdapter() {
        assertEquals(
            "__v5_proximity",
            RegionalNpcPresetSelection.suffix(true, "proximity")
        );
    }

    @Test
    void v5InteractionPlacementRemainsOwnedByCves() {
        assertEquals(
            "__v5",
            RegionalNpcPresetSelection.suffix(true, "interact")
        );
    }

    @Test
    void v4PlacementKeepsItsExistingTriggerVariants() {
        assertEquals(
            "__proximity",
            RegionalNpcPresetSelection.suffix(false, "proximity")
        );
        assertEquals(
            "__interact",
            RegionalNpcPresetSelection.suffix(false, "interact")
        );
    }

    @Test
    void existingV5NpcMustMatchTheSelectedTriggerRepresentation() {
        Set<String> interaction = Set.of("cves_binding/cobbleventure/examples/ai_test");
        Set<String> proximity = Set.of(
            "cves_binding/cobbleventure/examples/ai_test", "cves_trigger/proximity"
        );

        assertTrue(RegionalNpcPresetSelection.matches(true, "interact", interaction));
        assertFalse(RegionalNpcPresetSelection.matches(true, "proximity", interaction));
        assertTrue(RegionalNpcPresetSelection.matches(true, "proximity", proximity));
        assertFalse(RegionalNpcPresetSelection.matches(true, "interact", proximity));
    }
}
