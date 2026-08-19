package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GiveLootEventCommandAdapterTest {
    private static final UUID PLAYER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/loot";
    private static final String OPERATION_ID = SCRIPT_ID + "/reward/give_loot";
    private static final String DIGEST = "f".repeat(64);

    @Test
    void mapsTypedLootRequestAndStoresGeneratedItemCounts() {
        AtomicReference<EventGiveLootGateway.GrantRequest> captured = new AtomicReference<>();
        GiveLootEventCommandAdapter adapter = adapter(captured, new AtomicInteger());
        EventScript script = script(2, "cobbleventure:trainer/ai_test_rewards");
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        assertEquals("cobbleventure:trainer/ai_test_rewards", captured.get().lootTableId());
        assertEquals(2, captured.get().rollCount());
        assertTrue(captured.get().showNotification());
        assertEquals(OPERATION_ID, captured.get().operationId());

        EventAwaitCompletionService.Outcome outcome = EventAwaitCompletionService.completeAndRun(
            PLAYER_ID,
            session.key(),
            "loot-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, counts(5, 5, 0)
            ),
            script,
            environment(),
            adapter,
            store,
            10
        );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(5, session.locals().get("reward").getAsJsonObject()
            .get("granted_count").getAsInt());
        assertTrue(session.completedOperationIds().contains(OPERATION_ID));
    }

    @Test
    void fullBagReturnsTypedFailureWithoutCompletingOperation() {
        GiveLootEventCommandAdapter adapter = adapter(
            new AtomicReference<>(), new AtomicInteger()
        );
        EventScript script = script(1, "cobbleventure:trainer/ai_test_rewards");
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        EventAwaitCompletionService.Outcome outcome = EventAwaitCompletionService.completeAndRun(
            PLAYER_ID,
            session.key(),
            "loot-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.FAILED,
                failedCounts(3, 0, 3, "bag_full")
            ),
            script,
            environment(),
            adapter,
            store,
            10
        );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(3, session.locals().get("reward").getAsJsonObject()
            .get("remaining_count").getAsInt());
        assertEquals("bag_full", session.locals().get("reward").getAsJsonObject()
            .get("failure_reason").getAsString());
        assertFalse(session.completedOperationIds().contains(OPERATION_ID));
    }

    @Test
    void reconnectReusesOperationSoJournalDoesNotReroll() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger physicalRolls = new AtomicInteger();
        Map<String, String> journal = new HashMap<>();
        GiveLootEventCommandAdapter adapter = new GiveLootEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                String payload = request.lootTableId() + "#" + request.rollCount();
                String previous = journal.putIfAbsent(request.operationId(), payload);
                if (previous == null) physicalRolls.incrementAndGet();
                else assertEquals(previous, payload);
                return new EventGiveLootGateway.OpenResult("loot-token", 0);
            },
            environment(),
            context -> new EventCommandAdapter.Completed(null)
        );
        EventScript script = script(2, "cobbleventure:trainer/ai_test_rewards");
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        assertTrue(EventRecoverableAwait.resetGiveLoot(store, session.key()));
        EventSession restarted = EventInterpreter.startSession(
            script, 0, session.key(), environment(), store
        ).orElseThrow();
        EventInterpreter.run(script, restarted, environment(), adapter, store, 10);

        assertEquals(2, opens.get());
        assertEquals(1, physicalRolls.get());
        assertEquals(EventSession.Status.WAITING, restarted.status());
    }

    @Test
    void invalidCountAndResourceIdAreRejectedBeforeGateway() {
        AtomicInteger opens = new AtomicInteger();
        GiveLootEventCommandAdapter adapter = adapter(new AtomicReference<>(), opens);

        EventScript zero = script(0, "cobbleventure:trainer/ai_test_rewards");
        EventSession zeroSession = session(zero);
        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            zero, zeroSession, environment(), adapter, store(zeroSession), 10
        ));
        EventScript invalidId = script(1, "not a resource id");
        EventSession invalidIdSession = session(invalidId);
        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            invalidId, invalidIdSession, environment(), adapter,
            store(invalidIdSession), 10
        ));
        assertEquals(0, opens.get());
    }

    private static GiveLootEventCommandAdapter adapter(
        AtomicReference<EventGiveLootGateway.GrantRequest> captured,
        AtomicInteger opens
    ) {
        return new GiveLootEventCommandAdapter(
            request -> {
                captured.set(request);
                opens.incrementAndGet();
                return new EventGiveLootGateway.OpenResult("loot-token", 0);
            },
            environment(),
            context -> new EventCommandAdapter.Completed(null)
        );
    }

    private static EventScript script(int count, String lootTableId) {
        JsonArray arguments = new JsonArray();
        arguments.add(argument(null, literal(new JsonPrimitive(lootTableId))));
        arguments.add(argument("count", literal(new JsonPrimitive(count))));
        arguments.add(argument("notify", JsonNull.INSTANCE));
        JsonObject command = new JsonObject();
        command.addProperty("command", "give_loot");
        command.add("arguments", arguments);
        command.add("properties", new JsonArray());
        command.addProperty("await", true);
        command.addProperty("await_explicit", false);
        command.addProperty("result", "reward");
        command.addProperty("operation_id", OPERATION_ID);
        command.addProperty("next", 1);
        command.addProperty("resume", 1);
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(0, "reward/give_loot", "command", command),
                    new EventScript.Instruction(1, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static JsonObject argument(String name, JsonElement value) {
        JsonObject argument = new JsonObject();
        if (name == null) argument.add("name", JsonNull.INSTANCE);
        else argument.addProperty("name", name);
        argument.add("value", value);
        return argument;
    }

    private static JsonObject literal(JsonElement value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "literal");
        expression.add("value", value);
        return expression;
    }

    private static JsonObject counts(int requested, int granted, int remaining) {
        JsonObject result = new JsonObject();
        result.addProperty("requested_count", requested);
        result.addProperty("granted_count", granted);
        result.addProperty("remaining_count", remaining);
        return result;
    }

    private static JsonObject failedCounts(
        int requested, int granted, int remaining, String failureReason
    ) {
        JsonObject result = counts(requested, granted, remaining);
        result.addProperty("failure_reason", failureReason);
        return result;
    }

    private static EventSession session(EventScript script) {
        EventSession session = EventSession.create(
            new EventSessionKey(
                PLAYER_ID,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                SCRIPT_ID,
                "interact"
            ),
            script,
            0,
            0
        );
        session.start();
        return session;
    }

    private static InMemoryEventSessionStore store(EventSession session) {
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        return store;
    }

    private static EventExpressionEnvironment environment() {
        return new EventExpressionEnvironment() {
            @Override
            public Optional<JsonElement> resolveName(String name) {
                return Optional.empty();
            }

            @Override
            public JsonElement call(String function, List<Argument> arguments) {
                return JsonNull.INSTANCE;
            }
        };
    }
}
