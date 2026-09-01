package dev.buizz.cobbleventure.adventure.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class MainQuestProgressionTest {
    @Test
    void preservesAuthoredNpcQuestOrder() {
        MainQuestProgression progression = MainQuestProgression.parse(
            JsonParser.parseString("""
                {
                  "schema_version": 1,
                  "enabled": true,
                  "steps": [
                    {
                      "id":"starter",
                      "quest":"cobbleventure:quest/main/choose_starter",
                      "npc":"cobbleventure:npc/professor_oak"
                    },
                    {
                      "id":"parcel",
                      "quest":"cobbleventure:quest/main/deliver_parcel",
                      "npc":"cobbleventure:npc/viridian_clerk"
                    }
                  ]
                }
                """).getAsJsonObject()
        );

        assertTrue(progression.enabled());
        assertEquals(2, progression.steps().size());
        assertEquals(
            "cobbleventure:npc/professor_oak", progression.steps().getFirst().npcId()
        );
    }
}
