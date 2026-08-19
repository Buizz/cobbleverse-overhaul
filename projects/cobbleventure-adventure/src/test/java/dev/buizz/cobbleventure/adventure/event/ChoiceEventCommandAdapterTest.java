package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChoiceEventCommandAdapterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/choice";
    private static final String DIGEST = "f".repeat(64);
    private static final UUID PLAYER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );

    @Test
    void opensStructuredOptionsAndResumesOnlyServerStoredTarget() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicReference<EventChoiceGateway.ChoiceRequest> opened = new AtomicReference<>();
        AtomicInteger executedBranch = new AtomicInteger(-1);
        ChoiceEventCommandAdapter adapter = adapter(opened, executedBranch);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        assertEquals("choice", session.awaiting().kind());
        assertEquals(List.of(1, 3), session.awaiting().optionTargets());
        assertEquals(2, opened.get().options().size());
        assertEquals("choice/main", opened.get().instructionId());

        EventAwaitCompletionService.Outcome outcome = EventAwaitCompletionService.completeAndRun(
            PLAYER_ID,
            session.key(),
            "choice-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, null, 1
            ),
            script,
            environment(),
            adapter,
            store,
            10
        );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(3, executedBranch.get());
        assertEquals(1, session.locals().get("selected").getAsInt());
        assertEquals(EventSession.Status.COMPLETED, session.status());
    }

    @Test
    void forgedIndexLeavesSessionWaitingAndCancellationTerminates() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        ChoiceEventCommandAdapter adapter = adapter(
            new AtomicReference<>(), new AtomicInteger(-1)
        );
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        assertThrows(EventRuntimeException.class, () -> session.completeAwait(
            "choice-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, null, 99
            )
        ));
        assertEquals(EventSession.Status.WAITING, session.status());
        assertEquals(
            EventSession.CallbackResult.RESUMED,
            session.completeAwait(
                "choice-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.CANCELLED,
                    new JsonPrimitive("client_cancelled"),
                    null
                )
            )
        );
        assertEquals(EventSession.Status.CANCELLED, session.status());
    }

    @Test
    void reconnectCancelsLostScreenAndOpensFreshChoice() {
        EventScript script = script();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicInteger opens = new AtomicInteger();
        ChoiceEventCommandAdapter adapter = new ChoiceEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                return new EventChoiceGateway.OpenResult("choice-token-" + opens.get(), 0);
            },
            context -> new EventCommandAdapter.Completed(null)
        );
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        assertTrue(EventRecoverableAwait.resetChoice(store, session.key()));
        EventSession restarted = EventInterpreter.startSession(
            script, 0, session.key(), environment(), store
        ).orElseThrow();
        EventInterpreter.run(script, restarted, environment(), adapter, store, 10);

        assertEquals(2, opens.get());
        assertEquals("choice-token-2", restarted.awaiting().token());
    }

    @Test
    void malformedChoiceIsRejectedBeforeGateway() {
        JsonObject payload = JsonParser.parseString("""
            {"prompt":{"kind":"literal","value":"선택"},"await":true,
             "options":[{"target":1}]}
            """).getAsJsonObject();
        EventScript broken = eventScript(List.of(
            new EventScript.Instruction(0, "choice/broken", "choice", payload),
            instruction(1, "end", "page_end", "{}")
        ));
        AtomicInteger opens = new AtomicInteger();
        ChoiceEventCommandAdapter adapter = new ChoiceEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                return new EventChoiceGateway.OpenResult("choice-token", 0);
            },
            context -> new EventCommandAdapter.Completed(null)
        );

        EventSession session = session(broken);
        InMemoryEventSessionStore store = store(session);
        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            broken, session, environment(), adapter, store, 10
        ));
        assertEquals(0, opens.get());
    }

    private static ChoiceEventCommandAdapter adapter(
        AtomicReference<EventChoiceGateway.ChoiceRequest> opened,
        AtomicInteger executedBranch
    ) {
        return new ChoiceEventCommandAdapter(
            request -> {
                opened.set(request);
                return new EventChoiceGateway.OpenResult("choice-token", 0);
            },
            context -> {
                executedBranch.set(context.instruction().address());
                return new EventCommandAdapter.Completed(null);
            }
        );
    }

    private static EventScript script() {
        return eventScript(List.of(
            instruction(0, "choice/main", "choice", """
                {"prompt":{"kind":"literal","value":"무엇을 할까?"},
                 "result":"selected","await":true,
                 "options":[
                   {"text":{"kind":"literal","value":"받는다"},"target":1},
                   {"text":{"kind":"literal","value":"그만둔다"},"target":3}]}
                """),
            command(1, "choice/accept", 2),
            instruction(2, "end", "page_end", "{}"),
            command(3, "choice/decline", 2)
        ));
    }

    private static EventScript eventScript(List<EventScript.Instruction> instructions) {
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                instructions
            ))
        );
    }

    private static EventScript.Instruction command(int address, String id, int next) {
        return instruction(address, id, "command", """
            {"command":"test","arguments":[],"properties":[],"await":false,
             "await_explicit":false,"result":null,"next":NEXT}
            """.replace("NEXT", Integer.toString(next)));
    }

    private static EventScript.Instruction instruction(
        int address, String id, String operation, String payload
    ) {
        return new EventScript.Instruction(
            address, id, operation, JsonParser.parseString(payload).getAsJsonObject()
        );
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
