package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Runs validated IR control flow until completion, failure, await, or a step budget yield. */
public final class EventInterpreter {
    public enum RunResult {
        WAITING, COMPLETED, FAILED, CANCELLED, STEP_LIMIT
    }

    private EventInterpreter() {}

    public static Optional<EventScript.Page> selectPage(
        EventScript.Event event, EventExpressionEnvironment environment
    ) {
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(environment);
        for (EventScript.Page page : event.pages()) {
            JsonElement condition = page.condition();
            if (condition == null || evaluator.evaluateBoolean(condition, Map.of())) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }

    public static Optional<EventSession> startSession(
        EventScript script,
        int eventIndex,
        EventSessionKey key,
        EventExpressionEnvironment environment,
        EventSessionStore store
    ) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(store, "store");
        EventScript.Event event = script.events().get(eventIndex);
        Optional<EventScript.Page> page = selectPage(event, environment);
        if (page.isEmpty()) {
            return Optional.empty();
        }
        EventSession candidate = EventSession.create(
            key, script, eventIndex, page.orElseThrow().entry()
        );
        candidate.start();
        EventSession active = store.putIfAbsent(candidate);
        if (active != candidate && isTerminal(active.status())) {
            active.restart(script, eventIndex, page.orElseThrow().entry());
        } else if (active != candidate) {
            active.relocate(script, eventIndex);
        }
        store.save(active);
        return Optional.of(active);
    }

    public static RunResult run(
        EventScript script,
        EventSession session,
        EventExpressionEnvironment environment,
        EventCommandAdapter adapter,
        EventSessionStore store,
        int maxSteps
    ) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(store, "store");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps는 1 이상이어야 합니다.");
        }
        session.relocate(script, null);
        validateSession(script, session);
        store.save(session);
        EventScript.Event event = script.events().get(session.eventIndex());
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(environment);

        for (int step = 0; step < maxSteps; step++) {
            RunResult terminal = terminalResult(session.status());
            if (terminal != null) {
                return terminal;
            }
            EventScript.Instruction instruction = event.instruction(session.programCounter());
            execute(instruction, session, evaluator, adapter);
            session.bindInstructionAnchors(script);
            store.save(session);
            terminal = terminalResult(session.status());
            if (terminal != null) {
                return terminal;
            }
        }
        return RunResult.STEP_LIMIT;
    }

    private static void execute(
        EventScript.Instruction instruction,
        EventSession session,
        EventExpressionEvaluator evaluator,
        EventCommandAdapter adapter
    ) {
        JsonObject payload = instruction.rawPayload();
        switch (instruction.operation()) {
            case "let" -> {
                session.putLocal(
                    string(payload, "name"),
                    evaluator.evaluate(required(payload, "value"), session.locals())
                );
                session.advance(integer(payload, "next"));
            }
            case "branch" -> session.advance(
                evaluator.evaluateBoolean(required(payload, "condition"), session.locals())
                    ? integer(payload, "then")
                    : integer(payload, "else")
            );
            case "choice" -> executeChoice(instruction, session, adapter);
            case "repeat_begin" -> executeRepeat(instruction, session, evaluator);
            case "repeat_next", "jump" -> session.advance(integer(payload, "target"));
            case "call" -> {
                session.pushReturnAddress(integer(payload, "return_address"));
                session.advance(integer(payload, "target"));
            }
            case "return" -> session.advance(session.popReturnAddress());
            case "label" -> session.advance(integer(payload, "next"));
            case "page_end" -> session.finish();
            case "say", "narrate", "command" -> executeAdapterInstruction(
                instruction, session, adapter
            );
            default -> throw new EventRuntimeException(
                "실행하지 못한 IR 명령입니다: " + instruction.operation()
            );
        }
    }

    private static void executeAdapterInstruction(
        EventScript.Instruction instruction,
        EventSession session,
        EventCommandAdapter adapter
    ) {
        if (instruction.operation().equals("command") && "stop".equals(instruction.command())) {
            session.finish();
            return;
        }
        EventExecution.dispatch(session, instruction, adapter);
    }

    private static void executeChoice(
        EventScript.Instruction instruction,
        EventSession session,
        EventCommandAdapter adapter
    ) {
        JsonObject payload = instruction.rawPayload();
        JsonArray options = array(payload, "options");
        List<Integer> targets = new ArrayList<>();
        for (JsonElement value : options) {
            targets.add(integer(value.getAsJsonObject(), "target"));
        }
        EventCommandAdapter.StartResult result = adapter.start(
            new EventCommandAdapter.CommandContext(
                session.key(), session.sourceDigest(), instruction, session.locals()
            )
        );
        if (result instanceof EventCommandAdapter.Waiting waiting) {
            session.beginChoiceAwait(
                waiting.token(), targets, instruction.resultVariable(),
                waiting.expiresAtEpochMilli()
            );
        } else if (result instanceof EventCommandAdapter.Selected selected) {
            int index = selected.optionIndex();
            if (index < 0 || index >= targets.size()) {
                throw new EventRuntimeException("선택지 index가 범위를 벗어났습니다: " + index);
            }
            if (instruction.resultVariable() != null) {
                session.putLocal(instruction.resultVariable(), new JsonPrimitive(index));
            }
            session.advance(targets.get(index));
        } else if (result instanceof EventCommandAdapter.Failed) {
            session.terminate(EventSession.CompletionKind.FAILED);
        } else if (result instanceof EventCommandAdapter.Cancelled) {
            session.terminate(EventSession.CompletionKind.CANCELLED);
        } else {
            throw new EventRuntimeException("choice 어댑터는 Selected 또는 Waiting을 반환해야 합니다.");
        }
    }

    private static void executeRepeat(
        EventScript.Instruction instruction,
        EventSession session,
        EventExpressionEvaluator evaluator
    ) {
        JsonObject payload = instruction.rawPayload();
        String counterName = "$repeat:" + instruction.instructionId();
        JsonElement existing = session.locals().get(counterName);
        int remaining = existing == null
            ? evaluator.evaluateInt(required(payload, "count"), session.locals())
            : existing.getAsInt();
        if (remaining <= 0) {
            session.removeLocal(counterName);
            session.advance(integer(payload, "exit"));
            return;
        }
        session.putLocal(counterName, new JsonPrimitive(remaining - 1));
        session.advance(integer(payload, "body"));
    }

    private static void validateSession(EventScript script, EventSession session) {
        if (!script.scriptId().equals(session.key().scriptId())) {
            throw new EventRuntimeException("세션과 script ID가 다릅니다.");
        }
        if (!script.sourceDigest().equals(session.sourceDigest())) {
            throw new EventRuntimeException("세션과 script digest가 다릅니다. 재배치가 필요합니다.");
        }
        if (session.eventIndex() < 0 || session.eventIndex() >= script.events().size()) {
            throw new EventRuntimeException("세션 event index가 범위를 벗어났습니다.");
        }
    }

    private static RunResult terminalResult(EventSession.Status status) {
        return switch (status) {
            case WAITING -> RunResult.WAITING;
            case COMPLETED -> RunResult.COMPLETED;
            case FAILED -> RunResult.FAILED;
            case CANCELLED -> RunResult.CANCELLED;
            case READY -> throw new EventRuntimeException("READY 세션은 start 후 실행해야 합니다.");
            case RUNNING -> null;
        };
    }

    private static boolean isTerminal(EventSession.Status status) {
        return status == EventSession.Status.COMPLETED
            || status == EventSession.Status.FAILED
            || status == EventSession.Status.CANCELLED;
    }

    private static JsonElement required(JsonObject object, String name) {
        if (!object.has(name)) {
            throw new EventRuntimeException("IR 필드가 없습니다: " + name);
        }
        return object.get(name);
    }

    private static int integer(JsonObject object, String name) {
        return required(object, name).getAsInt();
    }

    private static String string(JsonObject object, String name) {
        return required(object, name).getAsString();
    }

    private static JsonArray array(JsonObject object, String name) {
        return required(object, name).getAsJsonArray();
    }
}
