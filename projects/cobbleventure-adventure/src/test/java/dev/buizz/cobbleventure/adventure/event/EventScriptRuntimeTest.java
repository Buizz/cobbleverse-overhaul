package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventScriptRuntimeTest {
    static final String IR = """
        {
          "schema_version": 1,
          "script_id": "cobbleventure:event_script/test/item_reward",
          "source_digest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "events": [{
            "index": 0,
            "trigger": {"name": "interact", "arguments": []},
            "pages": [{"index": 0, "condition": null, "entry": 0}],
            "instructions": [
              {
                "address": 0,
                "instruction_id": "reward/give_item",
                "op": "command",
                "command": "give_item",
                "arguments": [],
                "properties": [],
                "await": true,
                "await_explicit": false,
                "result": "item",
                "operation_id": "cobbleventure:event_script/test/item_reward/reward/give_item",
                "next": 1,
                "resume": 1
              },
              {
                "address": 1,
                "instruction_id": "e0/p0/s1",
                "op": "say",
                "speaker": "npc",
                "text": {"kind": "literal", "value": "받으렴."},
                "next": 2,
                "await": true,
                "resume": 2
              },
              {"address": 2, "instruction_id": "e0/p0/end", "op": "page_end"}
            ],
            "source_map": [
              {"address": 0, "instruction_id": "reward/give_item", "stable_id": "reward/give_item", "span": null},
              {"address": 1, "instruction_id": "e0/p0/s1", "stable_id": null, "span": null},
              {"address": 2, "instruction_id": "e0/p0/end", "stable_id": null, "span": null}
            ]
          }]
        }
        """;

    @Test
    void loaderReadsCompilerContractAndRejectsBrokenAddresses() {
        EventScript script = EventScriptLoader.parse(IR);

        assertEquals(1, script.schemaVersion());
        assertEquals("give_item", script.events().getFirst().instruction(0).command());
        assertEquals(2, script.events().getFirst().instruction(1).resumeAddress());

        JsonObject broken = JsonParser.parseString(IR).getAsJsonObject();
        broken.getAsJsonArray("events").get(0).getAsJsonObject()
            .getAsJsonArray("instructions").get(1).getAsJsonObject()
            .addProperty("address", 9);
        EventScriptFormatException error = assertThrows(
            EventScriptFormatException.class,
            () -> EventScriptLoader.parse(broken.toString())
        );
        assertTrue(error.getMessage().contains("instructions[1].address"));
    }

    @Test
    void loaderAllowsAnEventWithoutOptionalDefaultPage() {
        JsonObject conditional = JsonParser.parseString(IR).getAsJsonObject();
        JsonObject page = conditional.getAsJsonArray("events").get(0).getAsJsonObject()
            .getAsJsonArray("pages").get(0).getAsJsonObject();
        JsonObject condition = new JsonObject();
        condition.addProperty("kind", "literal");
        condition.addProperty("type", "bool");
        condition.addProperty("value", true);
        page.add("condition", condition);

        EventScript script = EventScriptLoader.parse(conditional.toString());

        assertEquals("literal", script.events().getFirst().pages().getFirst()
            .condition().getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void loaderRejectsDanglingTargetsAndDuplicateOperationIds() {
        JsonObject dangling = JsonParser.parseString(IR).getAsJsonObject();
        dangling.getAsJsonArray("events").get(0).getAsJsonObject()
            .getAsJsonArray("instructions").get(0).getAsJsonObject()
            .addProperty("resume", 99);
        assertThrows(
            EventScriptFormatException.class,
            () -> EventScriptLoader.parse(dangling.toString())
        );

        JsonObject duplicate = JsonParser.parseString(IR).getAsJsonObject();
        JsonObject dialogue = duplicate.getAsJsonArray("events").get(0).getAsJsonObject()
            .getAsJsonArray("instructions").get(1).getAsJsonObject();
        dialogue.addProperty(
            "operation_id",
            "cobbleventure:event_script/test/item_reward/reward/give_item"
        );
        assertThrows(
            EventScriptFormatException.class,
            () -> EventScriptLoader.parse(duplicate.toString())
        );
    }

    @Test
    void waitingSessionSurvivesRoundTripAndIgnoresDuplicateCallback() {
        EventScript script = EventScriptLoader.parse(IR);
        EventSession session = session(script);
        session.start();
        AtomicInteger starts = new AtomicInteger();

        assertEquals(
            EventExecution.DispatchResult.WAITING,
            EventExecution.dispatch(session, script.events().getFirst().instruction(0), context -> {
                starts.incrementAndGet();
                return new EventCommandAdapter.Waiting("token-1", 1_000L);
            })
        );
        EventSession restored = EventSession.fromJson(session.toJson());
        JsonObject itemResult = new JsonObject();
        itemResult.addProperty("granted", 1);

        assertEquals(
            EventSession.CallbackResult.RESUMED,
            restored.completeAwait(
                "token-1",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, itemResult
                )
            )
        );
        assertEquals(1, restored.programCounter());
        assertEquals(1, restored.locals().get("item").getAsJsonObject().get("granted").getAsInt());
        assertTrue(restored.hasCompletedOperation(
            "cobbleventure:event_script/test/item_reward/reward/give_item"
        ));
        assertEquals(
            EventSession.CallbackResult.DUPLICATE,
            restored.completeAwait(
                "token-1",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, itemResult
                )
            )
        );

        restored.advance(0);
        assertEquals(
            EventExecution.DispatchResult.SKIPPED_COMPLETED_OPERATION,
            EventExecution.dispatch(restored, script.events().getFirst().instruction(0), context -> {
                starts.incrementAndGet();
                return new EventCommandAdapter.Completed(itemResult);
            })
        );
        assertEquals(1, starts.get());
        assertEquals(1, restored.programCounter());
    }

    @Test
    void failedAwaitWithResultResumesWithoutCompletingOnceOperation() {
        EventScript script = EventScriptLoader.parse(IR);
        EventSession session = session(script);
        session.start();
        EventExecution.dispatch(
            session,
            script.events().getFirst().instruction(0),
            context -> new EventCommandAdapter.Waiting("token-fail", 0)
        );

        assertEquals(
            EventSession.CallbackResult.RESUMED,
            session.completeAwait(
                "token-fail",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.FAILED, new JsonPrimitive("inventory_full")
                )
            )
        );
        assertEquals(EventSession.Status.RUNNING, session.status());
        assertEquals("inventory_full", session.locals().get("item").getAsString());
        assertFalse(session.hasCompletedOperation(
            "cobbleventure:event_script/test/item_reward/reward/give_item"
        ));
    }

    @Test
    void staleAndExpiredTokensAreHandledWithoutDoubleResume() {
        EventScript script = EventScriptLoader.parse(IR);
        EventSession session = session(script);
        session.start();
        EventExecution.dispatch(
            session,
            script.events().getFirst().instruction(0),
            context -> new EventCommandAdapter.Waiting("token-expire", 100)
        );

        assertEquals(
            EventSession.CallbackResult.STALE,
            session.completeAwait(
                "other-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null
                )
            )
        );
        assertFalse(session.expireAwait(99));
        assertTrue(session.expireAwait(100));
        assertEquals(EventSession.Status.RUNNING, session.status());
        assertEquals("expired", session.locals().get("item").getAsString());
    }

    @Test
    void sessionStoreKeepsOneActiveSessionPerCompositeKey() {
        EventScript script = EventScriptLoader.parse(IR);
        EventSession first = session(script);
        EventSession second = session(script);
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();

        assertEquals(first, store.putIfAbsent(first));
        assertEquals(first, store.putIfAbsent(second));
        assertEquals(1, store.sessions().size());
        assertTrue(store.remove(first.key()));
        assertTrue(store.find(first.key()).isEmpty());
    }

    static EventSession session(EventScript script) {
        return EventSession.create(
            new EventSessionKey(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                script.scriptId(),
                "interact"
            ),
            script,
            0,
            0
        );
    }
}
