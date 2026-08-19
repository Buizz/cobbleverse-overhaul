package dev.buizz.cobbleventure.playermenu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StarterRouletteNetworkTest {
    @Test
    void eventResultTargetsTheAuthenticatedCommandSource() {
        assertEquals(
            "cobbleventure_event starter_result callback-token cobblemon:pikachu",
            StarterRouletteEventCallback.command(
                "callback-token", "cobblemon:pikachu", ""
            )
        );
    }

    @Test
    void eventCancellationTargetsTheAuthenticatedCommandSource() {
        assertEquals(
            "cobbleventure_event starter_cancel callback-token client_cancelled",
            StarterRouletteEventCallback.command(
                "callback-token", null, "client_cancelled"
            )
        );
    }
}
