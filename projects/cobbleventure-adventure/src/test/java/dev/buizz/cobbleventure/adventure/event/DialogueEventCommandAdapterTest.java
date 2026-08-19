package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DialogueEventCommandAdapterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/dialogue";
    private static final String DIGEST = "dialogue-digest";
    private static final UUID PLAYER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final EventSessionKey KEY = new EventSessionKey(
        PLAYER_ID,
        UUID.fromString("00000000-0000-0000-0000-000000000012"),
        SCRIPT_ID,
        "interact"
    );

    @Test
    void sayWaitsWithOpaqueTokenAndCallbackContinuesTheSession() {
        EventScript script = dialogueScript("say");
        EventSession session = EventSession.create(KEY, script, 0, 0);
        session.start();
        session.putLocal("visitor", new JsonPrimitive("레드"));
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        AtomicReference<EventDialogueGateway.DialogueRequest> opened = new AtomicReference<>();
        DialogueEventCommandAdapter adapter = new DialogueEventCommandAdapter(
            request -> {
                opened.set(request);
                return new EventDialogueGateway.OpenResult("dialogue-token", 0L);
            },
            context -> new EventCommandAdapter.Failed(new JsonPrimitive("unsupported"))
        );

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        EventDialogueGateway.DialogueRequest request = opened.get();
        assertNotNull(request);
        assertEquals(EventDialogueGateway.Kind.SAY, request.kind());
        assertEquals("npc", request.speaker());
        assertEquals("안녕, ${player.name}!", request.text().getAsJsonObject()
            .getAsJsonArray("entries").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("레드", request.locals().get("visitor").getAsString());
        assertEquals("dialogue-token", session.awaiting().token());

        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID,
                KEY,
                "dialogue-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                ),
                script,
                environment(),
                adapter,
                store,
                10
            );
        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(EventInterpreter.RunResult.COMPLETED, outcome.runResult());
        assertEquals(EventSession.Status.COMPLETED, session.status());

        EventAwaitCompletionService.Outcome duplicate =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID,
                KEY,
                "dialogue-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                ),
                script,
                environment(),
                adapter,
                store,
                10
            );
        assertEquals(EventAwaitCompletionService.Status.DUPLICATE, duplicate.status());
        assertNull(duplicate.runResult());
    }

    @Test
    void callbackRejectsAnotherPlayerBeforeConsumingTheToken() {
        EventScript script = dialogueScript("narrate");
        EventSession session = EventSession.create(KEY, script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        DialogueEventCommandAdapter adapter = new DialogueEventCommandAdapter(
            request -> new EventDialogueGateway.OpenResult("narrate-token", 0L),
            context -> new EventCommandAdapter.Completed(null)
        );
        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );

        EventAwaitCompletionService.Outcome rejected =
            EventAwaitCompletionService.completeAndRun(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                KEY,
                "narrate-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                ),
                script,
                environment(),
                adapter,
                store,
                10
            );

        assertEquals(EventAwaitCompletionService.Status.PLAYER_MISMATCH, rejected.status());
        assertEquals(EventSession.Status.WAITING, session.status());
        assertEquals("narrate-token", session.awaiting().token());

        EventScript changedScript = new EventScript(
            script.schemaVersion(), script.scriptId(), "changed-digest", script.events()
        );
        EventAwaitCompletionService.Outcome changed =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID,
                KEY,
                "narrate-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                ),
                changedScript,
                environment(),
                adapter,
                store,
                10
            );
        assertEquals(EventAwaitCompletionService.Status.RESUMED, changed.status());
        assertEquals(EventInterpreter.RunResult.COMPLETED, changed.runResult());
        assertEquals(EventSession.Status.COMPLETED, session.status());
        assertEquals("changed-digest", session.sourceDigest());
    }

    @Test
    void nonDialogueInstructionIsDelegatedUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        EventCommandAdapter.StartResult expected = new EventCommandAdapter.Completed(null);
        DialogueEventCommandAdapter adapter = new DialogueEventCommandAdapter(
            request -> new EventDialogueGateway.OpenResult("unused", 0L),
            context -> {
                calls.incrementAndGet();
                return expected;
            }
        );
        EventScript.Instruction instruction = commandInstruction();

        EventCommandAdapter.StartResult actual = adapter.start(
            new EventCommandAdapter.CommandContext(KEY, DIGEST, instruction, Map.of())
        );

        assertSame(expected, actual);
        assertEquals(1, calls.get());
    }

    @Test
    void callbackRejectsChangedAwaitMeaningWithoutMutatingTheSession() {
        EventScript script = dialogueScript("say");
        EventSession session = EventSession.create(KEY, script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        DialogueEventCommandAdapter adapter = new DialogueEventCommandAdapter(
            request -> new EventDialogueGateway.OpenResult("meaning-token", 0L),
            context -> new EventCommandAdapter.Completed(null)
        );
        assertEquals(EventInterpreter.RunResult.WAITING, EventInterpreter.run(
            script, session, environment(), adapter, store, 10
        ));

        EventScript narrate = dialogueScript("narrate");
        EventScript changed = new EventScript(
            narrate.schemaVersion(), narrate.scriptId(), "meaning-changed", narrate.events()
        );
        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID, KEY, "meaning-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                ),
                changed, environment(), adapter, store, 10
            );

        assertEquals(EventAwaitCompletionService.Status.SCRIPT_MISMATCH, outcome.status());
        assertEquals(EventSession.Status.WAITING, session.status());
        assertEquals(DIGEST, session.sourceDigest());
        assertEquals(0, session.programCounter());
        assertEquals("meaning-token", session.awaiting().token());
    }

    @Test
    void expiredDialogueCallbackTerminatesWithoutRunningFurtherInstructions() {
        EventScript script = dialogueScript("say");
        EventSession session = EventSession.create(KEY, script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        DialogueEventCommandAdapter adapter = new DialogueEventCommandAdapter(
            request -> new EventDialogueGateway.OpenResult("expired-token", 1L),
            context -> new EventCommandAdapter.Completed(null)
        );
        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );

        EventAwaitCompletionService.Outcome expired =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID,
                KEY,
                "expired-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                ),
                script,
                environment(),
                adapter,
                store,
                10
            );

        assertEquals(EventAwaitCompletionService.Status.EXPIRED, expired.status());
        assertEquals(EventSession.Status.FAILED, session.status());
        assertNull(expired.runResult());
    }

    private static EventExpressionEnvironment environment() {
        return new EventExpressionEnvironment() {
            @Override
            public Optional<JsonElement> resolveName(String name) {
                if (!name.equals("player")) return Optional.empty();
                JsonObject player = new JsonObject();
                player.addProperty("name", "레드");
                return Optional.of(player);
            }

            @Override
            public JsonElement call(String function, List<Argument> arguments) {
                throw new EventRuntimeException("unexpected function: " + function);
            }
        };
    }

    private static EventScript dialogueScript(String operation) {
        JsonObject dialogue = new JsonObject();
        if (operation.equals("say")) {
            dialogue.addProperty("speaker", "npc");
        }
        JsonObject text = new JsonObject();
        text.addProperty("kind", "localized");
        JsonArray entries = new JsonArray();
        JsonObject ko = new JsonObject();
        ko.addProperty("language", "ko_kr");
        ko.addProperty("value", "안녕, ${player.name}!");
        entries.add(ko);
        text.add("entries", entries);
        dialogue.add("text", text);
        dialogue.addProperty("next", 1);
        dialogue.addProperty("await", true);
        dialogue.addProperty("resume", 1);
        JsonObject end = new JsonObject();
        return new EventScript(
            1,
            SCRIPT_ID,
            DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", trigger()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(0, "line/one", operation, dialogue),
                    new EventScript.Instruction(1, "page/end", "page_end", end)
                )
            ))
        );
    }

    private static JsonObject trigger() {
        JsonObject trigger = new JsonObject();
        trigger.add("arguments", new JsonArray());
        return trigger;
    }

    private static EventScript.Instruction commandInstruction() {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", "set_flag");
        payload.add("arguments", new JsonArray());
        return new EventScript.Instruction(0, "command/test", "command", payload);
    }
}
