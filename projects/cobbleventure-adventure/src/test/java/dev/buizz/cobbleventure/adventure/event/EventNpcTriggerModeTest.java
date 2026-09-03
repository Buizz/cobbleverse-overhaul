package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

final class EventNpcTriggerModeTest {
    @Test
    void defaultV5RepresentationAcceptsOnlyInteraction() {
        Set<String> tags = Set.of("cves_binding/cobbleventure/test");

        assertTrue(EventNpcTriggerMode.acceptsInteraction(tags, trainer(), state(false)));
        assertFalse(EventNpcTriggerMode.acceptsProximity(tags));
    }

    @Test
    void undefeatedProximityTrainerCannotBeClickedToBypassForcedBattle() {
        Set<String> tags = Set.of(
            "cves_binding/cobbleventure/test",
            EventNpcTriggerMode.PROXIMITY_TAG
        );

        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags, trainer(), state(false)));
        assertTrue(EventNpcTriggerMode.acceptsProximity(tags));
    }

    @Test
    void victoryEnablesRepeatDialogueWithoutChangingSharedNpcTags() {
        Set<String> tags = Set.of(EventNpcTriggerMode.PROXIMITY_TAG);
        EventScript script = trainer();
        assertTrue(EventNpcTriggerMode.acceptsInteraction(tags, script, state(true)));
        assertTrue(EventNpcTriggerMode.acceptsInteraction(tags, script, state(true)));
        // A different player (or a loser / forfeiter) still has to face the encounter.
        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags, script, state(false)));
        assertTrue(EventNpcTriggerMode.acceptsProximity(tags));
    }

    @Test
    void missingFollowupOrAnActiveFallbackProximityPageKeepsClickDisabled() {
        Set<String> tags = Set.of(EventNpcTriggerMode.PROXIMITY_TAG);
        EventScript.Event finished = event(0, "proximity_enter", "false");
        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags, script(List.of(finished)), state(true)));
        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags, script(List.of(
            finished, event(1, "interact", "false")
        )), state(true)));
        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags, script(List.of(
            event(0, "proximity_enter", null), event(1, "interact", null)
        )), state(true)));
        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags, script(List.of(
            finished, event(1, "proximity_exit", null), event(2, "interact", null)
        )), state(true)));
    }

    private static EventScript trainer() {
        return script(List.of(event(0, "proximity_enter", "!defeated"),
            event(1, "proximity_enter", "!defeated"), event(2, "interact", "defeated")));
    }

    private static EventScript script(List<EventScript.Event> events) {
        return new EventScript(1, "test:event_script/trainer", "a".repeat(64), events);
    }

    private static EventScript.Event event(int index, String trigger, String condition) {
        var expression = condition == null ? null : JsonParser.parseString(switch (condition) {
            case "true", "false" -> "{\"kind\":\"literal\",\"type\":\"bool\",\"value\":" + condition + "}";
            case "defeated" -> flagExpression();
            default -> "{\"kind\":\"unary\",\"operator\":\"!\",\"operand\":" + flagExpression() + "}";
        });
        return new EventScript.Event(index, new EventScript.Trigger(trigger, new JsonObject()),
            List.of(new EventScript.Page(0, expression, 0)), List.of());
    }

    private static String flagExpression() {
        return """
            {"kind":"call","callee":{"kind":"name","name":"flag"},"arguments":[
              {"name":null,"value":{"kind":"literal","type":"string","value":"test:defeated"}}
            ]}
            """;
    }

    private static EventExpressionEnvironment state(boolean defeated) {
        return (function, arguments) -> new JsonPrimitive(defeated);
    }
}
