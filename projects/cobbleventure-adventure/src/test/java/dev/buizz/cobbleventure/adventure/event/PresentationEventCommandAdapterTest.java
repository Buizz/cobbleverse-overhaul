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

final class PresentationEventCommandAdapterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/presentation";
    private static final String DIGEST = "7".repeat(64);
    private static final UUID PLAYER_ID = UUID.fromString(
        "70000000-0000-0000-0000-000000000001"
    );

    @Test
    void faceIsImmediateAndKeepsSubjectAndRelativeTargetTyped() {
        EventScript script = script("face", name("npc"), name("player"), false, false);
        EventSession session = session(script);
        AtomicReference<EventFacingGateway.FacingRequest> request = new AtomicReference<>();
        FaceEventCommandAdapter adapter = new FaceEventCommandAdapter(
            request::set,
            context -> { throw new AssertionError("fallback"); }
        );

        assertEquals(EventInterpreter.RunResult.COMPLETED, EventInterpreter.run(
            script, session, environment(), adapter, store(session), 10
        ));

        assertEquals(EventFacingGateway.Subject.NPC, request.get().subject());
        assertEquals(EventFacingGateway.Direction.PLAYER, request.get().direction());
        assertFalse(session.hasCompletedOperation(operationId("face")));
    }

    @Test
    void presentationCommandsDecodeToClosedGatewayRequests() {
        assertRequest(
            "fade", name("white"),
            request -> {
                assertEquals(EventPresentationGateway.Kind.FADE, request.kind());
                assertEquals(EventPresentationGateway.FadeColor.WHITE, request.fadeColor());
                assertEquals(0.5D, request.durationSeconds());
            }
        );
        assertRequest(
            "wait", literal(1.25),
            request -> {
                assertEquals(EventPresentationGateway.Kind.WAIT, request.kind());
                assertEquals(1.25D, request.durationSeconds());
            }
        );
        assertRequest(
            "sound", literal("minecraft:block.note_block.pling"),
            request -> {
                assertEquals(EventPresentationGateway.Kind.SOUND, request.kind());
                assertEquals("minecraft:block.note_block.pling", request.resourceId());
            }
        );
        assertRequest(
            "effect", literal("minecraft:happy_villager"),
            request -> {
                assertEquals(EventPresentationGateway.Kind.EFFECT, request.kind());
                assertEquals("minecraft:happy_villager", request.resourceId());
            }
        );
    }

    @Test
    void successfulPresentationIsJournaledAndNotOpenedAgainAfterRestart() {
        EventScript script = script("wait", literal(0.1), null, true, true);
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicInteger opens = new AtomicInteger();
        PresentationEventCommandAdapter adapter = adapter(opens, new AtomicReference<>());

        assertEquals(EventInterpreter.RunResult.WAITING, EventInterpreter.run(
            script, session, environment(), adapter, store, 10
        ));
        EventAwaitCompletionService.Outcome completed =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID, session.key(), "presentation-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, new JsonPrimitive(true)
                ),
                script, environment(), adapter, store, 10
            );
        assertEquals(EventInterpreter.RunResult.COMPLETED, completed.runResult());
        assertTrue(session.hasCompletedOperation(operationId("wait")));

        EventSession restarted = EventInterpreter.startSession(
            script, 0, session.key(), environment(), store
        ).orElseThrow();
        assertEquals(EventInterpreter.RunResult.COMPLETED, EventInterpreter.run(
            script, restarted, environment(), adapter, store, 10
        ));
        assertEquals(1, opens.get());
        assertTrue(restarted.locals().get("completed").getAsBoolean());
    }

    @Test
    void invalidWaitAndLostPresentationAreHandledWithoutFallbackLabels() {
        EventScript invalid = script("wait", literal(-1), null, true, true);
        EventSession invalidSession = session(invalid);
        AtomicInteger opens = new AtomicInteger();
        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            invalid, invalidSession, environment(),
            adapter(opens, new AtomicReference<>()), store(invalidSession), 10
        ));
        assertEquals(0, opens.get());

        EventScript fade = script("fade", name("black"), null, true, true);
        EventSession waiting = session(fade);
        InMemoryEventSessionStore store = store(waiting);
        EventInterpreter.run(
            fade, waiting, environment(), adapter(opens, new AtomicReference<>()), store, 10
        );
        assertEquals("fade", waiting.awaiting().kind());
        assertTrue(EventRecoverableAwait.resetPresentation(store, waiting.key()));
        assertEquals(EventSession.Status.CANCELLED, waiting.status());
    }

    private static void assertRequest(
        String command,
        JsonObject argument,
        java.util.function.Consumer<EventPresentationGateway.PresentationRequest> assertion
    ) {
        EventScript script = script(command, argument, null, true, true);
        EventSession session = session(script);
        AtomicReference<EventPresentationGateway.PresentationRequest> opened =
            new AtomicReference<>();
        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), opened),
            store(session), 10
        );
        assertion.accept(opened.get());
        assertEquals(command, session.awaiting().kind());
        assertEquals(operationId(command), opened.get().operationId());
    }

    private static PresentationEventCommandAdapter adapter(
        AtomicInteger opens,
        AtomicReference<EventPresentationGateway.PresentationRequest> opened
    ) {
        return new PresentationEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                opened.set(request);
                return new EventPresentationGateway.OpenResult("presentation-token", 0);
            },
            environment(),
            context -> { throw new AssertionError("fallback"); }
        );
    }

    private static EventScript script(
        String command,
        JsonObject first,
        JsonObject second,
        boolean await,
        boolean persistent
    ) {
        JsonArray arguments = new JsonArray();
        arguments.add(argument(first));
        if (second != null) arguments.add(argument(second));
        JsonObject payload = new JsonObject();
        payload.addProperty("command", command);
        payload.add("arguments", arguments);
        payload.add("properties", new JsonArray());
        payload.addProperty("await", await);
        payload.addProperty("await_explicit", false);
        payload.addProperty("result", persistent ? "completed" : null);
        if (persistent) payload.addProperty("operation_id", operationId(command));
        payload.addProperty("next", 1);
        if (await) payload.addProperty("resume", 1);
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(0, command + "/instruction", "command", payload),
                    new EventScript.Instruction(1, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static String operationId(String command) {
        return SCRIPT_ID + "/" + command + "/instruction";
    }

    private static JsonObject argument(JsonElement value) {
        JsonObject result = new JsonObject();
        result.add("name", JsonNull.INSTANCE);
        result.add("value", value);
        return result;
    }

    private static JsonObject name(String value) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", "name");
        result.addProperty("name", value);
        return result;
    }

    private static JsonObject literal(Object value) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", "literal");
        if (value instanceof Number number) {
            result.add("value", new JsonPrimitive(number));
        } else {
            result.addProperty("value", String.valueOf(value));
        }
        return result;
    }

    private static EventSession session(EventScript script) {
        EventSession session = EventSession.create(
            new EventSessionKey(
                PLAYER_ID,
                UUID.fromString("70000000-0000-0000-0000-000000000002"),
                SCRIPT_ID, "interact"
            ),
            script, 0, 0
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
