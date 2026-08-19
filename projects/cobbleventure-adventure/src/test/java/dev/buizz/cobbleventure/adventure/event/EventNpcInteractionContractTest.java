package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventNpcInteractionContractTest {
    @Test
    void resolvesUniqueInteractEventAndNamedLiteralRange() {
        EventScript.Event interact = event(1, "interact", """
            {"name":"interact","arguments":[
              {"name":"range","value":{"kind":"literal","type":"decimal","value":6.5}}
            ]}
            """);
        EventScript script = script(List.of(event(0, "login", "{}"), interact));

        assertEquals(interact, EventNpcInteractionContract.uniqueInteractEvent(script).orElseThrow());
        assertEquals(6.5, EventNpcInteractionContract.interactionRange(interact, environment()));
    }

    @Test
    void usesDefaultRangeAndRejectsAmbiguousOrInvalidContracts() {
        EventScript.Event interact = event(0, "interact", "{\"arguments\":[]}");
        assertEquals(
            EventNpcInteractionContract.DEFAULT_RANGE,
            EventNpcInteractionContract.interactionRange(interact, environment())
        );
        assertTrue(EventNpcInteractionContract.uniqueInteractEvent(
            script(List.of(event(0, "login", "{}")))
        ).isEmpty());
        assertThrows(EventRuntimeException.class, () ->
            EventNpcInteractionContract.uniqueInteractEvent(script(List.of(interact, interact)))
        );
        EventScript.Event invalid = event(0, "interact", """
            {"arguments":[{"name":"range","value":{"kind":"name","name":"distance"}}]}
            """);
        assertThrows(
            EventRuntimeException.class,
            () -> EventNpcInteractionContract.interactionRange(invalid, environment())
        );
    }

    @Test
    void evaluatesRangeWithTheCommonExpressionEvaluator() {
        EventScript.Event interact = event(0, "interact", """
            {"arguments":[{"name":"range","value":{
              "kind":"binary","operator":"+",
              "left":{"kind":"literal","type":"int","value":2},
              "right":{"kind":"literal","type":"decimal","value":2.5}
            }}]}
            """);
        assertEquals(
            4.5, EventNpcInteractionContract.interactionRange(interact, environment())
        );
    }

    private static EventScript script(List<EventScript.Event> events) {
        return new EventScript(
            1, "test:event_script/npc", "a".repeat(64), events
        );
    }

    private static EventScript.Event event(int index, String name, String payload) {
        JsonObject trigger = JsonParser.parseString(payload).getAsJsonObject();
        return new EventScript.Event(
            index,
            new EventScript.Trigger(name, trigger),
            List.of(new EventScript.Page(0, null, 0)),
            List.of(new EventScript.Instruction(
                0, "end", "page_end", JsonParser.parseString("{}").getAsJsonObject()
            ))
        );
    }

    private static EventExpressionEnvironment environment() {
        return (function, arguments) -> JsonNull.INSTANCE;
    }
}
