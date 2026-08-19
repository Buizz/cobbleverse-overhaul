package dev.buizz.cobbleventure.adventure.event;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EncounterWarningEventCommandAdapterTest {
    @Test
    void warningTargetsTheAuthenticatedPlayerCommandSource() {
        UUID npcId = UUID.fromString("4d28ca5e-64df-4f09-b408-d0327b60ddcf");

        assertEquals(
            "cobbleventure_battle_warning @s " + npcId + " encounter.trainer_boy",
            EncounterWarningEventCommandAdapter.warningCommand(
                npcId, "encounter.trainer_boy"
            )
        );
    }
}
