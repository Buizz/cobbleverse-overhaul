package dev.buizz.cobbleventure.adventure.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class QuestDefinitionTest {
    @Test
    void parsesWebAuthoredQuestContract() {
        QuestDefinition definition = QuestDefinition.parse(
            "cobbleventure:quest/main/get_cut",
            JsonParser.parseString("""
                {
                  "schema_version": 1,
                  "id": "cobbleventure:quest/main/get_cut",
                  "enabled": true,
                  "category": "main",
                  "display_name": {"ko_kr":"풀베기 준비"},
                  "summary": {"ko_kr":"풀베기를 배워 길을 엽니다."},
                  "accept_conditions": {"condition_mode":"all","conditions":[]},
                  "objectives": [{
                    "id":"unlock_cut",
                    "text":{"ko_kr":"풀베기 배우기"},
                    "conditions":{"condition_mode":"all","conditions":[
                      {"type":"flag","key":"cobbleventure:flag/field_move/cut","value":true}
                    ]}
                  }],
                  "completion":{"mode":"npc_turn_in"}
                }
                """).getAsJsonObject()
        );

        assertEquals("cobbleventure:quest/main/get_cut", definition.id());
        assertTrue(definition.enabled());
        assertEquals("풀베기 준비", definition.displayName());
        assertEquals("풀베기를 배워 길을 엽니다.", definition.summary());
        assertEquals("unlock_cut", definition.objectives().getFirst().id());
        assertEquals("풀베기 배우기", definition.objectives().getFirst().text());
        assertEquals(QuestDefinition.CompletionMode.NPC_TURN_IN, definition.completionMode());
        assertFalse(definition.globalActivation().enabled());
        assertNull(definition.onAccept());
        assertNull(definition.onComplete());
        assertNull(definition.objectives().getFirst().onComplete());
    }

    @Test void parsesAllOptionalEventHooksAndRejectsUnknownHookKinds() {
        var root = JsonParser.parseString("""
            {"schema_version":1,"id":"test:quest/one","enabled":true,"category":"side",
             "display_name":{"ko_kr":"훅"},"accept_conditions":{"condition_mode":"all","conditions":[]},
             "objectives":[{"id":"first","conditions":{"condition_mode":"all","conditions":[]},
               "on_complete":{"script_id":"test:event_script/goal","npc_id":"test:npc/oak"}}],
             "completion":{"mode":"automatic"},"event_hooks":{
               "on_accept":{"script_id":"test:event_script/start","npc_id":"test:npc/oak"},
               "on_complete":{"script_id":"test:event_script/end","npc_id":"test:npc/oak"}}}
            """).getAsJsonObject();
        var definition = QuestDefinition.parse("test:quest/one", root);
        assertEquals("test:event_script/start", definition.onAccept().scriptId());
        assertEquals("test:event_script/end", definition.onComplete().scriptId());
        assertEquals("test:event_script/goal", definition.objectives().getFirst().onComplete().scriptId());
        root.getAsJsonObject("event_hooks").addProperty("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> QuestDefinition.parse("test:quest/one", root));
    }

    @Test
    void parsesGlobalMainQuestActivationConditions() {
        QuestDefinition definition = QuestDefinition.parse(
            "cobbleventure:quest/main/get_cut",
            JsonParser.parseString("""
                {
                  "schema_version": 1,
                  "id": "cobbleventure:quest/main/get_cut",
                  "enabled": true,
                  "category": "main",
                  "display_name": {"ko_kr":"풀베기 준비"},
                  "accept_conditions": {"condition_mode":"all","conditions":[]},
                  "global_activation": {
                    "enabled": true,
                    "conditions": {"condition_mode":"all","conditions":[
                      {"type":"flag","key":"cobbleventure:flag/story/arrived","value":true}
                    ]}
                  },
                  "objectives": [{
                    "id":"unlock_cut",
                    "conditions":{"condition_mode":"all","conditions":[]}
                  }],
                  "completion":{"mode":"npc_turn_in"}
                }
                """).getAsJsonObject()
        );

        assertEquals(QuestDefinition.Category.MAIN, definition.category());
        assertTrue(definition.globalActivation().enabled());
        assertEquals(1, definition.globalActivation().conditions().conditions().size());
    }
}
