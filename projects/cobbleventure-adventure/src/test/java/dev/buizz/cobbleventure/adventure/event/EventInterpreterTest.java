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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventInterpreterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/interpreter";
    private static final String DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void evaluatorHandlesBuiltinsMembersArithmeticAndShortCircuit() {
        AtomicInteger calls = new AtomicInteger();
        EventExpressionEnvironment environment = new TestEnvironment(calls, 150, 10);
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(environment);

        assertEquals(7, evaluator.evaluateInt(expression("""
            {"kind":"binary","operator":"+",
             "left":{"kind":"literal","type":"int","value":1},
             "right":{"kind":"binary","operator":"*",
               "left":{"kind":"literal","type":"int","value":2},
               "right":{"kind":"literal","type":"int","value":3}}}
            """), Map.of()));
        assertEquals("Red", evaluator.evaluate(expression("""
            {"kind":"member","target":{"kind":"name","name":"player"},"member":"name"}
            """), Map.of()).getAsString());
        assertEquals(false, evaluator.evaluateBoolean(expression("""
            {"kind":"binary","operator":"&&",
             "left":{"kind":"literal","type":"bool","value":false},
             "right":{"kind":"call","callee":{"kind":"name","name":"explode"},"arguments":[]}}
            """), Map.of()));
        assertEquals(0, calls.get(), "short circuit는 오른쪽 함수를 호출하지 않아야 합니다.");
        assertThrows(EventRuntimeException.class, () -> evaluator.evaluate(expression("""
            {"kind":"binary","operator":"/",
             "left":{"kind":"literal","type":"int","value":1},
             "right":{"kind":"literal","type":"int","value":0}}
            """), Map.of()));
    }

    @Test
    void pageSelectionUsesFirstMatchingConditionAndAllowsNoMatch() {
        EventScript script = pageScript();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();

        EventSession selected = EventInterpreter.startSession(
            script, 0, key(script), new TestEnvironment(new AtomicInteger(), 150, 10), store
        ).orElseThrow();
        assertEquals(1, selected.programCounter());

        EventScript noDefault = new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                trigger(),
                List.of(new EventScript.Page(0, expression("""
                    {"kind":"literal","type":"bool","value":false}
                    """), 0)),
                List.of(instruction(0, "end", "page_end", "{}"))
            ))
        );
        assertTrue(EventInterpreter.startSession(
            noDefault, 0, key(noDefault),
            new TestEnvironment(new AtomicInteger(), 0, 0),
            new InMemoryEventSessionStore()
        ).isEmpty());
    }

    @Test
    void controlLoopRunsBranchRepeatCallAndReturnDeterministically() {
        EventScript script = controlScript();
        EventSession session = EventSession.create(key(script), script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        AtomicInteger commands = new AtomicInteger();

        EventInterpreter.RunResult result = EventInterpreter.run(
            script,
            session,
            new TestEnvironment(new AtomicInteger(), 150, 10),
            context -> {
                commands.incrementAndGet();
                return new EventCommandAdapter.Completed(null);
            },
            store,
            100
        );

        assertEquals(EventInterpreter.RunResult.COMPLETED, result);
        assertEquals(3, commands.get(), "repeat 2회와 call routine 1회를 실행해야 합니다.");
        assertTrue(session.callStack().isEmpty());
        assertTrue(session.locals().keySet().stream().noneMatch(name -> name.startsWith("$repeat:")));
    }

    @Test
    void choiceWaitPersistsAllowedTargetsAndResumesSelectedBranch() {
        EventScript script = choiceScript();
        EventSession session = EventSession.create(key(script), script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        AtomicInteger branch = new AtomicInteger(-1);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(
                script,
                session,
                new TestEnvironment(new AtomicInteger(), 0, 0),
                context -> new EventCommandAdapter.Waiting("choice-token", 10_000),
                store,
                10
            )
        );
        EventSession restored = EventSession.fromJson(session.toJson());
        assertEquals(List.of(1, 3), restored.awaiting().optionTargets());
        assertThrows(EventRuntimeException.class, () -> restored.completeAwait(
            "choice-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, null, 2
            )
        ));
        assertEquals(EventSession.Status.WAITING, restored.status());
        assertEquals(
            EventSession.CallbackResult.RESUMED,
            restored.completeAwait(
                "choice-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, null, 1
                )
            )
        );
        store.save(restored);

        assertEquals(
            EventInterpreter.RunResult.COMPLETED,
            EventInterpreter.run(
                script,
                restored,
                new TestEnvironment(new AtomicInteger(), 0, 0),
                context -> {
                    branch.set(context.instruction().address());
                    return new EventCommandAdapter.Completed(null);
                },
                store,
                10
            )
        );
        assertEquals(3, branch.get());
        assertEquals(1, restored.locals().get("selected").getAsInt());
    }

    @Test
    void immediateChoiceRejectsOutOfRangeIndexWithoutAdvancing() {
        EventScript script = choiceScript();
        EventSession session = EventSession.create(key(script), script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);

        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            script,
            session,
            new TestEnvironment(new AtomicInteger(), 0, 0),
            context -> new EventCommandAdapter.Selected(2),
            store,
            10
        ));
        assertEquals(EventSession.Status.RUNNING, session.status());
        assertEquals(0, session.programCounter());
    }

    @Test
    void stepBudgetYieldsAnInfiniteAdvancedJump() {
        EventScript script = new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0, trigger(), List.of(new EventScript.Page(0, null, 0)),
                List.of(instruction(0, "loop", "jump", "{\"target\":0}"))
            ))
        );
        EventSession session = EventSession.create(key(script), script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);

        assertEquals(
            EventInterpreter.RunResult.STEP_LIMIT,
            EventInterpreter.run(
                script,
                session,
                new TestEnvironment(new AtomicInteger(), 0, 0),
                context -> new EventCommandAdapter.Completed(null),
                store,
                5
            )
        );
        assertEquals(0, session.programCounter());
    }

    @Test
    void completedSessionRestartsButRetainsCompletedOperationJournal() {
        EventScript script = pageScript();
        EventSessionKey key = key(script);
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        EventSession first = EventInterpreter.startSession(
            script, 0, key, new TestEnvironment(new AtomicInteger(), 150, 10), store
        ).orElseThrow();
        first.putLocal("temporary", new JsonPrimitive("value"));
        first.completeInstruction("stable-reward", null, null, 2);
        first.finish();
        store.save(first);

        EventSession restarted = EventInterpreter.startSession(
            script, 0, key, new TestEnvironment(new AtomicInteger(), 150, 10), store
        ).orElseThrow();

        assertTrue(first == restarted);
        assertEquals(EventSession.Status.RUNNING, restarted.status());
        assertEquals(1, restarted.programCounter());
        assertTrue(restarted.locals().isEmpty());
        assertTrue(restarted.hasCompletedOperation("stable-reward"));
    }

    @Test
    void activeSessionIsNotRestartedAndTerminalSessionRestartsOnChangedDigest() {
        EventScript script = pageScript();
        EventSessionKey key = key(script);
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        EventSession active = EventInterpreter.startSession(
            script, 0, key, new TestEnvironment(new AtomicInteger(), 150, 10), store
        ).orElseThrow();
        active.advance(2);

        EventSession duplicate = EventInterpreter.startSession(
            script, 0, key, new TestEnvironment(new AtomicInteger(), 150, 10), store
        ).orElseThrow();
        assertTrue(active == duplicate);
        assertEquals(2, duplicate.programCounter());

        active.finish();
        EventScript changed = new EventScript(
            script.schemaVersion(), script.scriptId(), "c".repeat(64), script.events()
        );
        EventSession restarted = EventInterpreter.startSession(
            changed, 0, key, new TestEnvironment(new AtomicInteger(), 150, 10), store
        ).orElseThrow();
        assertTrue(active == restarted);
        assertEquals(EventSession.Status.RUNNING, restarted.status());
        assertEquals("c".repeat(64), restarted.sourceDigest());
        assertEquals(1, restarted.programCounter());
    }

    @Test
    void runningSessionRelocatesProgramCounterAndCallStackByInstructionId() {
        EventScript oldScript = controlScript();
        EventSession session = EventSession.create(key(oldScript), oldScript, 0, 0);
        session.start();
        session.pushReturnAddress(6);
        session.advance(7);
        session.bindInstructionAnchors(oldScript);

        List<EventScript.Instruction> moved = List.of(
            instruction(0, "inserted", "page_end", "{}"),
            instruction(1, "let", "page_end", "{}"),
            instruction(2, "branch", "page_end", "{}"),
            instruction(3, "repeat", "page_end", "{}"),
            instruction(4, "repeat-command", "page_end", "{}"),
            instruction(5, "repeat-next", "page_end", "{}"),
            instruction(6, "call", "page_end", "{}"),
            instruction(7, "after-call", "page_end", "{}"),
            instruction(8, "routine-command", "page_end", "{}"),
            instruction(9, "return", "page_end", "{}"),
            instruction(10, "end", "page_end", "{}"),
            instruction(11, "else-command", "page_end", "{}")
        );
        EventScript changed = script("d".repeat(64), moved);

        assertTrue(session.relocate(changed, null));
        assertEquals(8, session.programCounter());
        assertEquals(List.of(7), session.callStack());
        assertEquals("d".repeat(64), session.sourceDigest());
    }

    @Test
    void waitingChoiceRelocatesOptionTargetsBeforeCallback() {
        EventScript oldScript = choiceScript();
        EventSession session = EventSession.create(key(oldScript), oldScript, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        assertEquals(EventInterpreter.RunResult.WAITING, EventInterpreter.run(
            oldScript, session, new TestEnvironment(new AtomicInteger(), 0, 0),
            context -> new EventCommandAdapter.Waiting("choice-relocate", 0), store, 10
        ));

        EventScript changed = script("e".repeat(64), List.of(
            instruction(0, "inserted", "jump", "{\"target\":1}"),
            instruction(1, "choice", "choice", """
                {"prompt":{"kind":"literal","value":"선택"},"result":"selected","await":true,
                 "options":[{"text":{"kind":"literal","value":"A"},"target":2},
                            {"text":{"kind":"literal","value":"B"},"target":4}]}
                """),
            command(2, "option-a", 3),
            instruction(3, "end", "page_end", "{}"),
            command(4, "option-b", 3)
        ));
        AtomicInteger selectedAddress = new AtomicInteger(-1);
        EventAwaitCompletionService.Outcome outcome = EventAwaitCompletionService.completeAndRun(
            key(oldScript).playerId(), key(oldScript), "choice-relocate",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, null, 1
            ),
            changed, new TestEnvironment(new AtomicInteger(), 0, 0),
            context -> {
                selectedAddress.set(context.instruction().address());
                return new EventCommandAdapter.Completed(null);
            }, store, 10
        );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(EventInterpreter.RunResult.COMPLETED, outcome.runResult());
        assertEquals(4, selectedAddress.get());
        assertEquals(1, session.locals().get("selected").getAsInt());
    }

    @Test
    void missingStableAnchorRejectsRelocationWithoutMutatingSession() {
        EventScript oldScript = pageScript();
        EventSession session = EventSession.create(key(oldScript), oldScript, 0, 2);
        session.start();
        String oldDigest = session.sourceDigest();

        EventScript changed = script("f".repeat(64), List.of(
            instruction(0, "first", "page_end", "{}"),
            instruction(1, "second", "page_end", "{}")
        ));
        assertThrows(EventRuntimeException.class, () -> session.relocate(changed, null));
        assertEquals(oldDigest, session.sourceDigest());
        assertEquals(2, session.programCounter());
    }

    @Test
    void versionOneSessionUpgradesOnlyWhileItsDigestStillMatches() {
        EventScript script = pageScript();
        EventSession current = EventSession.create(key(script), script, 0, 1);
        current.start();
        JsonObject legacyJson = current.toJson();
        legacyJson.addProperty("schema_version", 1);
        legacyJson.remove("event_trigger_name");
        legacyJson.remove("program_counter_instruction_id");
        legacyJson.remove("call_stack_instruction_ids");
        EventSession legacy = EventSession.fromJson(legacyJson);

        assertFalse(legacy.relocate(script, null));
        assertEquals("second", legacy.toJson().get(
            "program_counter_instruction_id"
        ).getAsString());

        EventSession unupgraded = EventSession.fromJson(legacyJson);
        EventScript changed = new EventScript(
            script.schemaVersion(), script.scriptId(), "a".repeat(64), script.events()
        );
        assertThrows(EventRuntimeException.class, () -> unupgraded.relocate(changed, null));
        assertEquals(DIGEST, unupgraded.sourceDigest());
    }

    private static EventScript pageScript() {
        List<EventScript.Instruction> instructions = List.of(
            instruction(0, "first", "page_end", "{}"),
            instruction(1, "second", "page_end", "{}"),
            instruction(2, "default", "page_end", "{}")
        );
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                trigger(),
                List.of(
                    new EventScript.Page(0, expression("""
                        {"kind":"call","callee":{"kind":"name","name":"flag"},
                         "arguments":[{"name":null,"value":{"kind":"literal","type":"resource_id","value":"test:first"}}]}
                        """), 0),
                    new EventScript.Page(1, expression("""
                        {"kind":"binary","operator":">=",
                         "left":{"kind":"call","callee":{"kind":"name","name":"money"},"arguments":[]},
                         "right":{"kind":"literal","type":"int","value":100}}
                        """), 1),
                    new EventScript.Page(2, null, 2)
                ),
                instructions
            ))
        );
    }

    private static EventScript controlScript() {
        List<EventScript.Instruction> instructions = List.of(
            instruction(0, "let", "let", """
                {"name":"rounds","value":{"kind":"literal","type":"int","value":2},"next":1}
                """),
            instruction(1, "branch", "branch", """
                {"condition":{"kind":"binary","operator":">=",
                  "left":{"kind":"call","callee":{"kind":"name","name":"money"},"arguments":[]},
                  "right":{"kind":"literal","type":"int","value":100}},"then":2,"else":10}
                """),
            instruction(2, "repeat", "repeat_begin", """
                {"count":{"kind":"name","name":"rounds"},"body":3,"exit":5}
                """),
            command(3, "repeat-command", 4),
            instruction(4, "repeat-next", "repeat_next", "{\"target\":2}"),
            instruction(5, "call", "call", "{\"target\":7,\"return_address\":6}"),
            instruction(6, "after-call", "jump", "{\"target\":9}"),
            command(7, "routine-command", 8),
            instruction(8, "return", "return", "{}"),
            instruction(9, "end", "page_end", "{}"),
            command(10, "else-command", 9)
        );
        return script(instructions);
    }

    private static EventScript choiceScript() {
        return script(List.of(
            instruction(0, "choice", "choice", """
                {"prompt":{"kind":"literal","value":"선택"},"result":"selected","await":true,
                 "options":[
                   {"text":{"kind":"literal","value":"A"},"target":1},
                   {"text":{"kind":"literal","value":"B"},"target":3}]}
                """),
            command(1, "option-a", 2),
            instruction(2, "end", "page_end", "{}"),
            command(3, "option-b", 2)
        ));
    }

    private static EventScript script(List<EventScript.Instruction> instructions) {
        return script(DIGEST, instructions);
    }

    private static EventScript script(
        String digest, List<EventScript.Instruction> instructions
    ) {
        return new EventScript(
            1, SCRIPT_ID, digest,
            List.of(new EventScript.Event(
                0, trigger(), List.of(new EventScript.Page(0, null, 0)), instructions
            ))
        );
    }

    private static EventScript.Instruction command(int address, String id, int next) {
        return instruction(address, id, "command", """
            {"command":"test","arguments":[],"properties":[],
             "await":false,"await_explicit":false,"result":null,"next":NEXT}
            """.replace("NEXT", Integer.toString(next)));
    }

    private static EventScript.Instruction instruction(
        int address, String id, String operation, String payload
    ) {
        return new EventScript.Instruction(
            address, id, operation, JsonParser.parseString(payload).getAsJsonObject()
        );
    }

    private static EventScript.Trigger trigger() {
        JsonObject value = new JsonObject();
        value.addProperty("name", "interact");
        return new EventScript.Trigger("interact", value);
    }

    private static JsonElement expression(String value) {
        return JsonParser.parseString(value);
    }

    private static EventSessionKey key(EventScript script) {
        return new EventSessionKey(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            script.scriptId(),
            "interact"
        );
    }

    private record TestEnvironment(
        AtomicInteger calls, int money, int levelCap
    ) implements EventExpressionEnvironment {
        @Override
        public Optional<JsonElement> resolveName(String name) {
            if (!name.equals("player")) {
                return Optional.empty();
            }
            JsonObject player = new JsonObject();
            player.addProperty("name", "Red");
            return Optional.of(player);
        }

        @Override
        public JsonElement call(String function, List<Argument> arguments) {
            calls.incrementAndGet();
            return switch (function) {
                case "money" -> new JsonPrimitive(money);
                case "level_cap" -> new JsonPrimitive(levelCap);
                case "flag" -> new JsonPrimitive(false);
                case "explode" -> throw new AssertionError("short circuit failed");
                default -> JsonNull.INSTANCE;
            };
        }
    }
}
