package dev.buizz.cobbleventure.adventure.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEncounterEventTest {
    @Test
    void reusableProgramReadsDungeonOwnedDialogueAndBattleLocals() {
        EventScript script = DungeonEncounterEvent.script();
        EventScript.Event event = script.events().getFirst();

        assertEquals(DungeonEncounterEvent.SCRIPT_ID, script.scriptId());
        assertEquals(7, event.instructions().size());
        assertEquals(
            "${dungeon_start_text}",
            event.instruction("dungeon/start").payload()
                .getAsJsonObject("text").get("value").getAsString()
        );
        assertEquals(
            "dungeon_battle_id",
            event.instruction("dungeon/battle").payload()
                .getAsJsonArray("arguments").get(0).getAsJsonObject()
                .getAsJsonObject("value").get("name").getAsString()
        );
        assertTrue(event.instruction("dungeon/battle").awaitsResult());
        assertEquals("battle_result", event.instruction("dungeon/battle").resultVariable());
        assertEquals(3, event.instruction("dungeon/result").payload().get("then").getAsInt());
        assertEquals(5, event.instruction("dungeon/result").payload().get("else").getAsInt());
    }
}
