package dev.buizz.cobbleventure.bootstrap;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildingRuntimeNurseRoutingTest {
    private static final String NURSE =
        "cobbleventure_npc/cobbleventure/npc/pokemon_center_nurse";

    @Test
    void onlyLegacyNurseUsesTheBuildingRuntimeFallback() {
        assertTrue(NurseNpcRouting.usesLegacyFallback(Set.of(NURSE)));
        assertFalse(NurseNpcRouting.usesLegacyFallback(Set.of(
            NURSE, "cves_binding/cobbleventure/facilities/pokemon_center_nurse"
        )));
        assertFalse(NurseNpcRouting.usesLegacyFallback(Set.of(
            "cobbleventure_regional_npc"
        )));
    }
}
