package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BattleEventCommandAdapterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/battle";
    private static final String OPERATION_ID = SCRIPT_ID + "/trainer/battle";
    private static final String DIGEST = "a".repeat(64);
    private static final UUID PLAYER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );

    @Test
    void winStoresTypedResultAndReplayRestoresItWithoutNewBattle() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<EventBattleGateway.BattleRequest> opened = new AtomicReference<>();
        BattleEventCommandAdapter adapter = adapter(opens, opened);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        assertEquals("cobbleventure:battle/ai_test", opened.get().battleId());
        assertEquals(OPERATION_ID, opened.get().operationId());
        EventAwaitCompletionService.completeAndRun(
            PLAYER_ID,
            session.key(),
            "battle-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED,
                result("win")
            ),
            script,
            environment(),
            adapter,
            store,
            10
        );
        assertTrue(session.hasCompletedOperation(OPERATION_ID));
        assertEquals("win", session.locals().get("battle").getAsJsonObject()
            .get("outcome").getAsString());

        EventSession restored = EventSession.fromJson(session.toJson());
        InMemoryEventSessionStore restoredStore = store(restored);
        EventSession restarted = EventInterpreter.startSession(
            script, 0, restored.key(), environment(), restoredStore
        ).orElseThrow();
        assertEquals(
            EventInterpreter.RunResult.COMPLETED,
            EventInterpreter.run(
                script, restarted, environment(), adapter, restoredStore, 10
            )
        );
        assertEquals(1, opens.get());
        assertEquals("win", restarted.locals().get("battle").getAsJsonObject()
            .get("outcome").getAsString());
    }

    @Test
    void lossReturnsResultButDoesNotConsumeRetryableBattle() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicInteger opens = new AtomicInteger();
        BattleEventCommandAdapter adapter = adapter(opens, new AtomicReference<>());
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        EventAwaitCompletionService.Outcome outcome = EventAwaitCompletionService.completeAndRun(
            PLAYER_ID,
            session.key(),
            "battle-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.FAILED,
                result("loss")
            ),
            script,
            environment(),
            adapter,
            store,
            10
        );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertFalse(session.hasCompletedOperation(OPERATION_ID));
        assertEquals("loss", session.locals().get("battle").getAsJsonObject()
            .get("outcome").getAsString());
        EventSession restarted = EventInterpreter.startSession(
            script, 0, session.key(), environment(), store
        ).orElseThrow();
        EventInterpreter.run(script, restarted, environment(), adapter, store, 10);
        assertEquals(2, opens.get());
        assertEquals(EventSession.Status.WAITING, restarted.status());
    }

    @Test
    void lostBattleCallbackCanBeResetOnReinteraction() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), new AtomicReference<>()),
            store, 10
        );

        assertTrue(EventRecoverableAwait.resetBattle(store, session.key()));
        assertEquals(EventSession.Status.CANCELLED, session.status());
    }

    @Test
    void forfeitReturnsCancelledOutcomeWithoutConsumingBattle() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        BattleEventCommandAdapter adapter = adapter(
            new AtomicInteger(), new AtomicReference<>()
        );
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        EventAwaitCompletionService.Outcome outcome = EventAwaitCompletionService.completeAndRun(
            PLAYER_ID,
            session.key(),
            "battle-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.CANCELLED,
                result("cancelled")
            ),
            script,
            environment(),
            adapter,
            store,
            10
        );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals("cancelled", session.locals().get("battle").getAsJsonObject()
            .get("outcome").getAsString());
        assertFalse(session.hasCompletedOperation(OPERATION_ID));
    }

    @Test
    void malformedBattleArgumentIsRejectedBeforeGateway() {
        EventScript script = scriptWithBattleValue(new JsonPrimitive(3));
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicInteger opens = new AtomicInteger();

        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            script, session, environment(), adapter(opens, new AtomicReference<>()),
            store, 10
        ));
        assertEquals(0, opens.get());
    }

    private static BattleEventCommandAdapter adapter(
        AtomicInteger opens, AtomicReference<EventBattleGateway.BattleRequest> opened
    ) {
        return new BattleEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                opened.set(request);
                return new EventBattleGateway.OpenResult("battle-token", 0);
            },
            environment(),
            context -> new EventCommandAdapter.Completed(null)
        );
    }

    private static EventScript script() {
        return scriptWithBattleValue(new JsonPrimitive("cobbleventure:battle/ai_test"));
    }

    private static EventScript scriptWithBattleValue(JsonElement value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "literal");
        expression.add("value", value);
        JsonObject argument = new JsonObject();
        argument.add("name", JsonNull.INSTANCE);
        argument.add("value", expression);
        JsonArray arguments = new JsonArray();
        arguments.add(argument);
        JsonObject command = new JsonObject();
        command.addProperty("command", "battle");
        command.add("arguments", arguments);
        command.add("properties", new JsonArray());
        command.addProperty("await", true);
        command.addProperty("await_explicit", true);
        command.addProperty("result", "battle");
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
                    new EventScript.Instruction(0, "trainer/battle", "command", command),
                    new EventScript.Instruction(1, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static JsonObject result(String outcome) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", outcome);
        result.addProperty("opponent", "cobbleventure:trainer/ai_test");
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
            @Override public Optional<JsonElement> resolveName(String name) {
                return Optional.empty();
            }

            @Override public JsonElement call(String function, List<Argument> arguments) {
                return JsonNull.INSTANCE;
            }
        };
    }
}
