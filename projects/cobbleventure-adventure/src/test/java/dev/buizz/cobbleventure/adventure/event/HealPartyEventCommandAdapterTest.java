package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.adventure.PokemonCenterHealingService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HealPartyEventCommandAdapterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/healing";
    private static final String DIGEST = "8".repeat(64);
    private static final UUID PLAYER_ID = UUID.fromString(
        "80000000-0000-0000-0000-000000000001"
    );

    @Test
    void healingUsesAwaitButRunsAgainOnTheNextInteraction() {
        assertRepeatableHealing(false);
    }

    @Test
    void fallbackHealingUsesTheSameAwaitAndRunsOnEveryVisit() {
        assertRepeatableHealing(true);
    }

    private void assertRepeatableHealing(boolean fallback) {
        EventScript script = script(fallback);
        EventSession session = session(script);
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        AtomicInteger opens = new AtomicInteger();
        HealPartyEventCommandAdapter adapter = new HealPartyEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                assertEquals(session.key(), request.sessionKey());
                assertEquals("healing/use_machine", request.instructionId());
                assertEquals(fallback, request.fallbackWithoutMachine());
                return new EventHealingGateway.OpenResult(
                    "healing-token-" + opens.get(), 0
                );
            },
            context -> { throw new AssertionError("fallback"); }
        );

        assertEquals(EventInterpreter.RunResult.WAITING, EventInterpreter.run(
            script, session, environment(), adapter, store, 10
        ));
        assertEquals("heal_party", session.awaiting().kind());
        assertNull(session.awaiting().operationId());

        JsonObject result = new JsonObject();
        result.addProperty("healed", true);
        result.addProperty("failure_reason", "");
        EventAwaitCompletionService.completeAndRun(
            PLAYER_ID, session.key(), "healing-token-1",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, result
            ),
            script, environment(), adapter, store, 10
        );
        assertEquals(EventSession.Status.COMPLETED, session.status());
        assertFalse(session.hasCompletedOperation(
            SCRIPT_ID + "/healing/use_machine"
        ));

        EventSession restarted = EventInterpreter.startSession(
            script, 0, session.key(), environment(), store
        ).orElseThrow();
        assertEquals(EventInterpreter.RunResult.WAITING, EventInterpreter.run(
            script, restarted, environment(), adapter, store, 10
        ));
        assertEquals(2, opens.get());
    }

    @Test
    void fallbackOnlyAppliesToMissingMachinesNotBusyOrStartedMachines() {
        for (var status : PokemonCenterHealingService.StartStatus.values()) {
            assertFalse(EventHealingBridge.shouldFallback(false, status));
            assertEquals(status == PokemonCenterHealingService.StartStatus.HEALING_MACHINE_NOT_FOUND,
                EventHealingBridge.shouldFallback(true, status));
        }
    }

    @Test
    void fallbackFlagRejectsValuesUnknownNamesDuplicatesAndPositionalArguments() {
        assertFalse(HealPartyEventCommandAdapter.fallbackFlag(new JsonArray()));
        JsonObject flag = new JsonObject();
        flag.addProperty("name", "fallback"); flag.add("value", JsonNull.INSTANCE);
        JsonArray args = new JsonArray(); args.add(flag);
        assertTrue(HealPartyEventCommandAdapter.fallbackFlag(args));
        args.add(flag.deepCopy());
        assertThrows(EventRuntimeException.class, () -> HealPartyEventCommandAdapter.fallbackFlag(args));
        args.remove(1); flag.addProperty("value", true);
        assertThrows(EventRuntimeException.class, () -> HealPartyEventCommandAdapter.fallbackFlag(args));
        flag.add("value", JsonNull.INSTANCE); flag.addProperty("name", "direct");
        assertThrows(EventRuntimeException.class, () -> HealPartyEventCommandAdapter.fallbackFlag(args));
        args.remove(0); args.add("fallback");
        assertThrows(EventRuntimeException.class, () -> HealPartyEventCommandAdapter.fallbackFlag(args));
    }

    @Test
    void existingGatewayRequestsKeepMachineOnlyBehavior() {
        var key = session(script(false)).key();
        assertFalse(new EventHealingGateway.HealingRequest(key, DIGEST, "heal").fallbackWithoutMachine());
    }

    private static EventScript script(boolean fallback) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", "heal_party");
        JsonArray arguments = new JsonArray();
        if (fallback) {
            JsonObject flag = new JsonObject();
            flag.addProperty("name", "fallback"); flag.add("value", JsonNull.INSTANCE);
            arguments.add(flag);
        }
        payload.add("arguments", arguments);
        payload.add("properties", new JsonArray());
        payload.addProperty("await", true);
        payload.addProperty("await_explicit", true);
        payload.addProperty("result", "healing");
        payload.addProperty("next", 1);
        payload.addProperty("resume", 1);
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(
                        0, "healing/use_machine", "command", payload
                    ),
                    new EventScript.Instruction(
                        1, "page/end", "page_end", new JsonObject()
                    )
                )
            ))
        );
    }

    private static EventSession session(EventScript script) {
        EventSession session = EventSession.create(
            new EventSessionKey(
                PLAYER_ID,
                UUID.fromString("80000000-0000-0000-0000-000000000002"),
                SCRIPT_ID, "interact"
            ),
            script, 0, 0
        );
        session.start();
        return session;
    }

    private static EventExpressionEnvironment environment() {
        return new EventExpressionEnvironment() {
            @Override public Optional<JsonElement> resolveName(String name) {
                return Optional.empty();
            }

            @Override public JsonElement call(String function, List<Argument> arguments) {
                return JsonNull.INSTANCE;
            }
        };
    }
}
