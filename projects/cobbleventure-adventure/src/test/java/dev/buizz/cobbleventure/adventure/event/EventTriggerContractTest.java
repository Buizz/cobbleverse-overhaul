package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventTriggerContractTest {
    @Test
    void proximityDefaultsAreStable() {
        EventTriggerContract.Options options = EventTriggerContract.proximity(
            event("proximity_enter", "{\"arguments\":[]}"), environment()
        );

        assertEquals(4.0D, options.range());
        assertFalse(options.once());
        assertEquals(0.0D, options.cooldownSeconds());
        assertEquals("player", options.scope());
    }

    @Test
    void evaluatesRangeOnceCooldownAndPlayerScope() {
        EventTriggerContract.Options options = EventTriggerContract.proximity(
            event("proximity_exit", """
                {"arguments":[
                  {"name":"range","value":{"kind":"literal","type":"decimal","value":7.5}},
                  {"name":"once","value":{"kind":"literal","type":"bool","value":true}},
                  {"name":"cooldown","value":{"kind":"literal","type":"int","value":30}},
                  {"name":"scope","value":{"kind":"name","name":"player"}}
                ]}
                """),
            environment()
        );

        assertEquals(7.5D, options.range());
        assertTrue(options.once());
        assertEquals(30.0D, options.cooldownSeconds());
        assertEquals("player", options.scope());
    }

    @Test
    void rejectsDuplicateArgumentsInvalidValuesAndUnsupportedScope() {
        assertThrows(EventRuntimeException.class, () -> EventTriggerContract.proximity(
            event("proximity_enter", """
                {"arguments":[
                  {"name":"range","value":{"kind":"literal","type":"int","value":4}},
                  {"name":"range","value":{"kind":"literal","type":"int","value":5}}
                ]}
                """), environment()
        ));
        assertThrows(EventRuntimeException.class, () -> EventTriggerContract.proximity(
            event("proximity_enter", """
                {"arguments":[
                  {"name":"cooldown","value":{"kind":"literal","type":"int","value":-1}}
                ]}
                """), environment()
        ));
        assertThrows(EventRuntimeException.class, () -> EventTriggerContract.proximity(
            event("proximity_enter", """
                {"arguments":[{"name":"scope","value":{"kind":"name","name":"world"}}]}
                """), environment()
        ));
    }

    @Test
    void indexedBoundaryRequiresTypedTargetAndCommonOptions() {
        EventTriggerContract.TargetOptions options = EventTriggerContract.targeted(
            event("region_enter", """
                {"arguments":[
                  {"name":"target","value":{"kind":"literal","type":"resource_id","value":"test:region/start"}},
                  {"name":"once","value":{"kind":"literal","type":"bool","value":true}},
                  {"name":"cooldown","value":{"kind":"literal","type":"decimal","value":2.5}}
                ]}
                """), environment()
        );

        assertEquals("test:region/start", options.target());
        assertTrue(options.once());
        assertEquals(2.5D, options.cooldownSeconds());
        assertEquals("player", options.scope());

        assertThrows(EventRuntimeException.class, () -> EventTriggerContract.targeted(
            event("anchor_step", "{\"arguments\":[]}"), environment()
        ));
        assertThrows(EventRuntimeException.class, () -> EventTriggerContract.targeted(
            event("region_exit", """
                {"arguments":[{"name":"target","value":{"kind":"literal","type":"string","value":"not an id"}}]}
            """), environment()
        ));

        assertEquals("test:building/lab", EventTriggerContract.targeted(
            event("building_enter", """
                {"arguments":[{"name":"target","value":{"kind":"literal","type":"resource_id","value":"test:building/lab"}}]}
                """), environment()
        ).target());
        assertEquals("test:world", EventTriggerContract.targeted(
            event("dimension_exit", """
                {"arguments":[{"name":"target","value":{"kind":"literal","type":"resource_id","value":"test:world"}}]}
                """), environment()
        ).target());
        assertEquals("test:item/key", EventTriggerContract.targeted(
            event("item_used", """
                {"arguments":[{"name":"target","value":{"kind":"literal","type":"resource_id","value":"test:item/key"}}]}
                """), environment()
        ).target());
    }

    private static EventScript.Event event(String name, String payload) {
        JsonObject trigger = JsonParser.parseString(payload).getAsJsonObject();
        return new EventScript.Event(
            0,
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
