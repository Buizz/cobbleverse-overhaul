package dev.buizz.cobbleventure.playermenu;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BattleIntroRoutingTest {
    @Test
    void legacyProximityNeverOwnsACvesBoundNpc() {
        assertTrue(LegacyProximityRouting.accepts(Set.of(
            "cobbleventure_npc_preset_v4"
        )));
        assertFalse(LegacyProximityRouting.accepts(Set.of(
            "cobbleventure_npc_preset_v4",
            "cves_binding/cobbleventure/generation_1/kanto_psychic_seon"
        )));
    }
}
