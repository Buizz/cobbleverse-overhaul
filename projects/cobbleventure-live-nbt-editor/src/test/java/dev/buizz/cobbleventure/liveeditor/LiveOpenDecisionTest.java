package dev.buizz.cobbleventure.liveeditor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

final class LiveOpenDecisionTest {
    @Test
    void automaticRefreshDoesNotWaitForAPlayerDecision() {
        JsonObject command = new JsonObject();
        command.addProperty("preserve_current", false);

        assertFalse(LiveNbtEditorMod.requiresOpenDecision(command, true));
    }

    @Test
    void manualSwitchStillProtectsCurrentChanges() {
        JsonObject command = new JsonObject();
        command.addProperty("preserve_current", true);

        assertTrue(LiveNbtEditorMod.requiresOpenDecision(command, true));
    }

    @Test
    void firstOpenNeverNeedsAPlayerDecision() {
        JsonObject command = new JsonObject();

        assertFalse(LiveNbtEditorMod.requiresOpenDecision(command, false));
    }

    @Test
    void legacyOpenCommandsRemainProtected() {
        assertTrue(LiveNbtEditorMod.requiresOpenDecision(new JsonObject(), true));
    }
}
