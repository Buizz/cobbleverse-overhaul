package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistable program counter, locals, call stack and idempotency journal. */
public final class EventSession {
    public enum Status {
        READY, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED
    }

    public enum CompletionKind {
        COMPLETED, FAILED, CANCELLED
    }

    public enum CallbackResult {
        RESUMED, DUPLICATE, STALE
    }

    public record AwaitState(
        String kind,
        String token,
        String operationId,
        Integer resumeAddress,
        List<Integer> optionTargets,
        String resultVariable,
        long expiresAtEpochMilli,
        String resumeInstructionId,
        List<String> optionTargetInstructionIds
    ) {
        public AwaitState(
            String kind,
            String token,
            String operationId,
            Integer resumeAddress,
            List<Integer> optionTargets,
            String resultVariable,
            long expiresAtEpochMilli
        ) {
            this(
                kind, token, operationId, resumeAddress, optionTargets,
                resultVariable, expiresAtEpochMilli, null, List.of()
            );
        }

        public AwaitState {
            if (kind == null || kind.isBlank() || token == null || token.isBlank()) {
                throw new IllegalArgumentException("await kind와 token이 필요합니다.");
            }
            optionTargets = List.copyOf(optionTargets);
            optionTargetInstructionIds = List.copyOf(optionTargetInstructionIds);
            if (resumeAddress == null && optionTargets.isEmpty()) {
                throw new IllegalArgumentException("await 재개 주소 또는 선택지 target이 필요합니다.");
            }
            if (resumeAddress != null && (resumeAddress < 0 || !optionTargets.isEmpty())) {
                throw new IllegalArgumentException("고정 재개 주소와 선택지 target을 함께 사용할 수 없습니다.");
            }
            if (optionTargets.stream().anyMatch(address -> address == null || address < 0)) {
                throw new IllegalArgumentException("선택지 target은 0 이상이어야 합니다.");
            }
            if (!optionTargetInstructionIds.isEmpty()
                && optionTargetInstructionIds.size() != optionTargets.size()) {
                throw new IllegalArgumentException("선택지 target 주소와 안정 ID 수가 다릅니다.");
            }
        }
    }

    public record AwaitCompletion(
        CompletionKind kind, JsonElement result, Integer optionIndex
    ) {
        public AwaitCompletion(CompletionKind kind, JsonElement result) {
            this(kind, result, null);
        }

        public AwaitCompletion {
            Objects.requireNonNull(kind, "kind");
            result = result == null ? JsonNull.INSTANCE : result.deepCopy();
        }

        @Override
        public JsonElement result() {
            return result.deepCopy();
        }
    }

    private final EventSessionKey key;
    private String sourceDigest;
    private int eventIndex;
    private String eventTriggerName;
    private int programCounter;
    private String programCounterInstructionId;
    private Status status;
    private final Map<String, JsonElement> locals = new LinkedHashMap<>();
    private final Deque<Integer> callStack = new ArrayDeque<>();
    private final Deque<String> callStackInstructionIds = new ArrayDeque<>();
    private final Set<String> completedOperationIds = new LinkedHashSet<>();
    private final Map<String, JsonElement> completedOperationResults = new LinkedHashMap<>();
    private final Set<String> consumedCallbackTokens = new LinkedHashSet<>();
    private AwaitState awaiting;

    private EventSession(
        EventSessionKey key,
        String sourceDigest,
        int eventIndex,
        int programCounter,
        Status status
    ) {
        this.key = Objects.requireNonNull(key, "key");
        if (sourceDigest == null || sourceDigest.isBlank()) {
            throw new IllegalArgumentException("sourceDigest가 필요합니다.");
        }
        if (eventIndex < 0 || programCounter < 0) {
            throw new IllegalArgumentException("eventIndex와 programCounter는 0 이상이어야 합니다.");
        }
        this.sourceDigest = sourceDigest;
        this.eventIndex = eventIndex;
        this.programCounter = programCounter;
        this.status = Objects.requireNonNull(status, "status");
    }

    public static EventSession create(EventSessionKey key, EventScript script, int eventIndex, int entry) {
        Objects.requireNonNull(script, "script");
        if (!key.scriptId().equals(script.scriptId())) {
            throw new IllegalArgumentException("세션 키와 스크립트 ID가 다릅니다.");
        }
        script.events().get(eventIndex).instruction(entry);
        EventSession session = new EventSession(
            key, script.sourceDigest(), eventIndex, entry, Status.READY
        );
        session.bindInstructionAnchors(script);
        return session;
    }

    public EventSessionKey key() {
        return key;
    }

    public String sourceDigest() {
        return sourceDigest;
    }

    public int eventIndex() {
        return eventIndex;
    }

    public int programCounter() {
        return programCounter;
    }

    public Status status() {
        return status;
    }

    public AwaitState awaiting() {
        return awaiting;
    }

    public Map<String, JsonElement> locals() {
        Map<String, JsonElement> copy = new LinkedHashMap<>();
        locals.forEach((name, value) -> copy.put(name, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    public List<Integer> callStack() {
        return List.copyOf(callStack);
    }

    /** Whether every persisted numeric continuation has a stable instruction anchor. */
    public boolean hasCompleteInstructionAnchors() {
        if (eventTriggerName == null || programCounterInstructionId == null
            || callStack.size() != callStackInstructionIds.size()) {
            return false;
        }
        if (awaiting == null) return true;
        if (awaiting.resumeAddress() != null && awaiting.resumeInstructionId() == null) {
            return false;
        }
        return awaiting.optionTargets().size()
            == awaiting.optionTargetInstructionIds().size();
    }

    /** Refreshes persisted stable anchors while the numeric addresses are authoritative. */
    public void bindInstructionAnchors(EventScript script) {
        Objects.requireNonNull(script, "script");
        if (!key.scriptId().equals(script.scriptId())
            || !sourceDigest.equals(script.sourceDigest())) {
            throw new EventRuntimeException("현재 digest와 같은 스크립트만 세션 anchor를 갱신할 수 있습니다.");
        }
        EventScript.Event event = script.events().get(eventIndex);
        eventTriggerName = event.trigger().name();
        programCounterInstructionId = event.instruction(programCounter).instructionId();
        callStackInstructionIds.clear();
        for (int address : callStack) {
            callStackInstructionIds.addLast(event.instruction(address).instructionId());
        }
        if (awaiting != null) {
            String resumeId = awaiting.resumeAddress() == null
                ? null : event.instruction(awaiting.resumeAddress()).instructionId();
            List<String> optionIds = awaiting.optionTargets().stream()
                .map(address -> event.instruction(address).instructionId())
                .toList();
            awaiting = new AwaitState(
                awaiting.kind(), awaiting.token(), awaiting.operationId(),
                awaiting.resumeAddress(), awaiting.optionTargets(), awaiting.resultVariable(),
                awaiting.expiresAtEpochMilli(), resumeId, optionIds
            );
        }
    }

    /** Relocates every live address to a new digest using persisted instruction IDs. */
    public boolean relocate(EventScript script, Integer preferredEventIndex) {
        Objects.requireNonNull(script, "script");
        if (!key.scriptId().equals(script.scriptId())) {
            throw new EventRuntimeException("세션과 script ID가 다릅니다.");
        }
        if (sourceDigest.equals(script.sourceDigest())) {
            bindInstructionAnchors(script);
            return false;
        }
        if (programCounterInstructionId == null || eventTriggerName == null) {
            throw new EventRuntimeException(
                "기존 세션에 안정 instruction anchor가 없어 digest 변경을 재배치할 수 없습니다."
            );
        }

        EventScript.Event target = null;
        if (preferredEventIndex != null
            && preferredEventIndex >= 0
            && preferredEventIndex < script.events().size()) {
            EventScript.Event preferred = script.events().get(preferredEventIndex);
            if (canRelocateTo(preferred)) target = preferred;
        }
        if (target == null) {
            List<EventScript.Event> candidates = script.events().stream()
                .filter(this::canRelocateTo)
                .toList();
            if (candidates.size() != 1) {
                throw new EventRuntimeException(
                    candidates.isEmpty()
                        ? "새 digest에서 세션의 안정 instruction anchor를 찾을 수 없습니다."
                        : "새 digest에서 세션 anchor가 여러 event와 일치합니다."
                );
            }
            target = candidates.getFirst();
        }

        EventScript.Event relocationTarget = target;
        int relocatedProgramCounter = relocationTarget.instruction(
            programCounterInstructionId
        ).address();
        Deque<Integer> relocatedStack = new ArrayDeque<>();
        for (String instructionId : callStackInstructionIds) {
            relocatedStack.addLast(relocationTarget.instruction(instructionId).address());
        }
        AwaitState relocatedAwaiting = awaiting;
        if (awaiting != null) {
            Integer resumeAddress = awaiting.resumeInstructionId() == null
                ? null : relocationTarget.instruction(awaiting.resumeInstructionId()).address();
            List<Integer> optionTargets = awaiting.optionTargetInstructionIds().stream()
                .map(instructionId -> relocationTarget.instruction(instructionId).address())
                .toList();
            relocatedAwaiting = new AwaitState(
                awaiting.kind(), awaiting.token(), awaiting.operationId(),
                resumeAddress, optionTargets, awaiting.resultVariable(),
                awaiting.expiresAtEpochMilli(), awaiting.resumeInstructionId(),
                awaiting.optionTargetInstructionIds()
            );
            validateAwaitInstruction(relocationTarget.instruction(programCounterInstructionId));
        }
        programCounter = relocatedProgramCounter;
        callStack.clear();
        callStack.addAll(relocatedStack);
        awaiting = relocatedAwaiting;
        eventIndex = relocationTarget.index();
        sourceDigest = script.sourceDigest();
        bindInstructionAnchors(script);
        return true;
    }

    private boolean canRelocateTo(EventScript.Event event) {
        if (!event.trigger().name().equals(eventTriggerName)
            || !event.hasInstruction(programCounterInstructionId)
            || callStackInstructionIds.stream().anyMatch(id -> !event.hasInstruction(id))) {
            return false;
        }
        if (awaiting != null) {
            if (awaiting.resumeAddress() != null
                && (awaiting.resumeInstructionId() == null
                    || !event.hasInstruction(awaiting.resumeInstructionId()))) {
                return false;
            }
            if (awaiting.optionTargets().size()
                != awaiting.optionTargetInstructionIds().size()
                || awaiting.optionTargetInstructionIds().stream().anyMatch(
                    id -> !event.hasInstruction(id)
                )) {
                return false;
            }
        }
        for (String name : locals.keySet()) {
            if (name.startsWith("$repeat:")
                && !event.hasInstruction(name.substring("$repeat:".length()))) {
                return false;
            }
        }
        return true;
    }

    private void validateAwaitInstruction(EventScript.Instruction instruction) {
        String kind = instruction.command() == null
            ? instruction.operation() : instruction.command();
        if (!kind.equals(awaiting.kind())
            || !Objects.equals(instruction.operationId(), awaiting.operationId())) {
            throw new EventRuntimeException(
                "await 중인 안정 ID의 명령 종류 또는 operation ID가 변경되었습니다: "
                    + instruction.instructionId()
            );
        }
    }

    public Set<String> completedOperationIds() {
        return Set.copyOf(completedOperationIds);
    }

    public Optional<JsonElement> completedOperationResult(String operationId) {
        JsonElement result = operationId == null ? null : completedOperationResults.get(operationId);
        return result == null ? Optional.empty() : Optional.of(result.deepCopy());
    }

    public void start() {
        requireStatus(Status.READY);
        status = Status.RUNNING;
    }

    /** Starts a new invocation while retaining the stable-operation idempotency journal. */
    public void restart(EventScript script, int eventIndex, int entry) {
        Objects.requireNonNull(script, "script");
        if (status != Status.COMPLETED
            && status != Status.FAILED
            && status != Status.CANCELLED) {
            throw new IllegalStateException("종료된 세션만 다시 시작할 수 있습니다. 현재 상태: " + status);
        }
        if (entry < 0) {
            throw new IllegalArgumentException("entry는 0 이상이어야 합니다.");
        }
        programCounter = entry;
        sourceDigest = script.sourceDigest();
        this.eventIndex = eventIndex;
        locals.clear();
        callStack.clear();
        callStackInstructionIds.clear();
        consumedCallbackTokens.clear();
        awaiting = null;
        status = Status.RUNNING;
        bindInstructionAnchors(script);
    }

    public void advance(int nextAddress) {
        requireStatus(Status.RUNNING);
        if (nextAddress < 0) {
            throw new IllegalArgumentException("nextAddress는 0 이상이어야 합니다.");
        }
        programCounter = nextAddress;
    }

    public void pushReturnAddress(int address) {
        requireStatus(Status.RUNNING);
        if (address < 0) {
            throw new IllegalArgumentException("호출 복귀 주소는 0 이상이어야 합니다.");
        }
        callStack.push(address);
    }

    public int popReturnAddress() {
        requireStatus(Status.RUNNING);
        if (callStack.isEmpty()) {
            throw new IllegalStateException("호출 스택이 비어 있습니다.");
        }
        return callStack.pop();
    }

    public void putLocal(String name, JsonElement value) {
        requireStatus(Status.RUNNING);
        putLocalUnchecked(name, value);
    }

    public void removeLocal(String name) {
        requireStatus(Status.RUNNING);
        locals.remove(name);
    }

    public boolean hasCompletedOperation(String operationId) {
        return operationId != null && completedOperationIds.contains(operationId);
    }

    public void completeInstruction(
        String operationId,
        String resultVariable,
        JsonElement result,
        int nextAddress
    ) {
        completeInstruction(
            CompletionKind.COMPLETED, operationId, resultVariable, result, nextAddress
        );
    }

    public void completeInstruction(
        CompletionKind kind,
        String operationId,
        String resultVariable,
        JsonElement result,
        int nextAddress
    ) {
        requireStatus(Status.RUNNING);
        Objects.requireNonNull(kind, "kind");
        if (kind == CompletionKind.COMPLETED && operationId != null) {
            completedOperationIds.add(operationId);
            completedOperationResults.put(
                operationId, result == null ? JsonNull.INSTANCE : result.deepCopy()
            );
        }
        if (resultVariable != null) {
            putLocalUnchecked(resultVariable, result);
            advance(nextAddress);
        } else if (kind == CompletionKind.COMPLETED) {
            advance(nextAddress);
        } else {
            status = kind == CompletionKind.FAILED ? Status.FAILED : Status.CANCELLED;
        }
    }

    public void beginAwait(
        String kind,
        String token,
        String operationId,
        int resumeAddress,
        String resultVariable,
        long expiresAtEpochMilli
    ) {
        requireStatus(Status.RUNNING);
        if (consumedCallbackTokens.contains(token)) {
            throw new IllegalArgumentException("이미 소비된 await token입니다.");
        }
        if (hasCompletedOperation(operationId)) {
            throw new IllegalStateException("이미 완료된 작업을 다시 대기할 수 없습니다.");
        }
        awaiting = new AwaitState(
            kind, token, operationId, resumeAddress, List.of(),
            resultVariable, expiresAtEpochMilli
        );
        status = Status.WAITING;
    }

    public void beginChoiceAwait(
        String token,
        List<Integer> optionTargets,
        String resultVariable,
        long expiresAtEpochMilli
    ) {
        requireStatus(Status.RUNNING);
        if (consumedCallbackTokens.contains(token)) {
            throw new IllegalArgumentException("이미 소비된 await token입니다.");
        }
        awaiting = new AwaitState(
            "choice", token, null, null, optionTargets,
            resultVariable, expiresAtEpochMilli
        );
        status = Status.WAITING;
    }

    public CallbackResult completeAwait(String token, AwaitCompletion completion) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(completion, "completion");
        if (consumedCallbackTokens.contains(token)) {
            return CallbackResult.DUPLICATE;
        }
        if (status != Status.WAITING || awaiting == null || !awaiting.token().equals(token)) {
            return CallbackResult.STALE;
        }

        AwaitState completedAwait = awaiting;
        Integer resumeAddress = resolveResumeAddress(completedAwait, completion);
        String resumeInstructionId = resolveResumeInstructionId(completedAwait, completion);
        awaiting = null;
        consumedCallbackTokens.add(token);
        if (completion.kind() == CompletionKind.COMPLETED) {
            if (completedAwait.operationId() != null) {
                completedOperationIds.add(completedAwait.operationId());
                completedOperationResults.put(
                    completedAwait.operationId(), completion.result()
                );
            }
            JsonElement result = completedAwait.optionTargets().isEmpty()
                ? completion.result()
                : new JsonPrimitive(completion.optionIndex());
            resumeWithResult(
                completedAwait, result, resumeAddress, resumeInstructionId
            );
        } else if (completedAwait.resultVariable() != null && resumeAddress != null) {
            resumeWithResult(
                completedAwait, completion.result(), resumeAddress, resumeInstructionId
            );
        } else {
            status = completion.kind() == CompletionKind.FAILED
                ? Status.FAILED
                : Status.CANCELLED;
        }
        return CallbackResult.RESUMED;
    }

    /** Terminates an await whose command has no authored failure continuation. */
    public CallbackResult terminateAwait(String token, CompletionKind kind) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(kind, "kind");
        if (kind == CompletionKind.COMPLETED) {
            throw new IllegalArgumentException("성공 await는 completeAwait로 완료해야 합니다.");
        }
        if (consumedCallbackTokens.contains(token)) return CallbackResult.DUPLICATE;
        if (status != Status.WAITING || awaiting == null || !awaiting.token().equals(token)) {
            return CallbackResult.STALE;
        }
        awaiting = null;
        consumedCallbackTokens.add(token);
        status = kind == CompletionKind.FAILED ? Status.FAILED : Status.CANCELLED;
        return CallbackResult.RESUMED;
    }

    public boolean expireAwait(long nowEpochMilli) {
        if (status != Status.WAITING || awaiting == null) {
            return false;
        }
        if (awaiting.expiresAtEpochMilli() <= 0 || nowEpochMilli < awaiting.expiresAtEpochMilli()) {
            return false;
        }
        completeAwait(
            awaiting.token(),
            new AwaitCompletion(CompletionKind.FAILED, new JsonPrimitive("expired"))
        );
        return true;
    }

    public void finish() {
        requireStatus(Status.RUNNING);
        status = Status.COMPLETED;
    }

    public void terminate(CompletionKind kind) {
        requireStatus(Status.RUNNING);
        if (kind == CompletionKind.COMPLETED) {
            status = Status.COMPLETED;
        } else {
            status = kind == CompletionKind.FAILED ? Status.FAILED : Status.CANCELLED;
        }
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 2);
        root.add("key", keyToJson());
        root.addProperty("source_digest", sourceDigest);
        root.addProperty("event_index", eventIndex);
        root.addProperty("event_trigger_name", eventTriggerName);
        root.addProperty("program_counter", programCounter);
        root.addProperty("program_counter_instruction_id", programCounterInstructionId);
        root.addProperty("status", status.name().toLowerCase());
        JsonObject localValues = new JsonObject();
        locals.forEach((name, value) -> localValues.add(name, value.deepCopy()));
        root.add("locals", localValues);
        root.add("call_stack", integerArray(callStack));
        root.add("call_stack_instruction_ids", stringArray(callStackInstructionIds));
        root.add("completed_operation_ids", stringArray(completedOperationIds));
        JsonObject operationResults = new JsonObject();
        completedOperationResults.forEach(
            (operationId, result) -> operationResults.add(operationId, result.deepCopy())
        );
        root.add("completed_operation_results", operationResults);
        root.add("consumed_callback_tokens", stringArray(consumedCallbackTokens));
        root.add("awaiting", awaiting == null ? JsonNull.INSTANCE : awaitToJson(awaiting));
        return root;
    }

    public static EventSession fromJson(JsonObject root) {
        int schemaVersion = root.get("schema_version").getAsInt();
        if (schemaVersion != 1 && schemaVersion != 2) {
            throw new IllegalArgumentException("지원하지 않는 이벤트 세션 버전입니다.");
        }
        JsonObject keyValue = root.getAsJsonObject("key");
        EventSessionKey key = new EventSessionKey(
            UUID.fromString(keyValue.get("player_id").getAsString()),
            UUID.fromString(keyValue.get("npc_id").getAsString()),
            keyValue.get("script_id").getAsString(),
            keyValue.get("trigger_instance").getAsString()
        );
        EventSession session = new EventSession(
            key,
            root.get("source_digest").getAsString(),
            root.get("event_index").getAsInt(),
            root.get("program_counter").getAsInt(),
            Status.valueOf(root.get("status").getAsString().toUpperCase())
        );
        root.getAsJsonObject("locals").entrySet().forEach(
            entry -> session.locals.put(entry.getKey(), entry.getValue().deepCopy())
        );
        root.getAsJsonArray("call_stack").forEach(
            value -> session.callStack.addLast(value.getAsInt())
        );
        if (schemaVersion >= 2) {
            session.eventTriggerName = nullableString(root, "event_trigger_name");
            session.programCounterInstructionId = nullableString(
                root, "program_counter_instruction_id"
            );
            root.getAsJsonArray("call_stack_instruction_ids").forEach(
                value -> session.callStackInstructionIds.addLast(value.getAsString())
            );
            if (session.callStack.size() != session.callStackInstructionIds.size()) {
                throw new IllegalArgumentException("호출 스택 주소와 안정 ID 수가 다릅니다.");
            }
        }
        root.getAsJsonArray("completed_operation_ids").forEach(
            value -> session.completedOperationIds.add(value.getAsString())
        );
        if (root.has("completed_operation_results")) {
            root.getAsJsonObject("completed_operation_results").entrySet().forEach(
                entry -> session.completedOperationResults.put(
                    entry.getKey(), entry.getValue().deepCopy()
                )
            );
        }
        root.getAsJsonArray("consumed_callback_tokens").forEach(
            value -> session.consumedCallbackTokens.add(value.getAsString())
        );
        if (!root.get("awaiting").isJsonNull()) {
            JsonObject value = root.getAsJsonObject("awaiting");
            session.awaiting = new AwaitState(
                value.get("kind").getAsString(),
                value.get("token").getAsString(),
                nullableString(value, "operation_id"),
                nullableInteger(value, "resume_address"),
                value.has("option_targets")
                    ? integerList(value.getAsJsonArray("option_targets"))
                    : List.of(),
                nullableString(value, "result_variable"),
                value.get("expires_at_epoch_milli").getAsLong(),
                schemaVersion >= 2
                    ? nullableString(value, "resume_instruction_id") : null,
                schemaVersion >= 2 && value.has("option_target_instruction_ids")
                    ? stringList(value.getAsJsonArray("option_target_instruction_ids"))
                    : List.of()
            );
        }
        if ((session.status == Status.WAITING) != (session.awaiting != null)) {
            throw new IllegalArgumentException("WAITING 상태와 awaiting 데이터가 일치하지 않습니다.");
        }
        return session;
    }

    private Integer resolveResumeAddress(
        AwaitState state, AwaitCompletion completion
    ) {
        if (completion.kind() != CompletionKind.COMPLETED) {
            return state.resumeAddress();
        }
        if (state.optionTargets().isEmpty()) {
            if (completion.optionIndex() != null) {
                throw new EventRuntimeException("고정 await callback에는 option index를 사용할 수 없습니다.");
            }
            return state.resumeAddress();
        }
        Integer index = completion.optionIndex();
        if (index == null || index < 0 || index >= state.optionTargets().size()) {
            throw new EventRuntimeException("선택지 callback index가 범위를 벗어났습니다: " + index);
        }
        return state.optionTargets().get(index);
    }

    private String resolveResumeInstructionId(
        AwaitState state, AwaitCompletion completion
    ) {
        if (completion.kind() != CompletionKind.COMPLETED
            || state.optionTargets().isEmpty()) {
            return state.resumeInstructionId();
        }
        Integer index = completion.optionIndex();
        return index == null || index < 0 || index >= state.optionTargetInstructionIds().size()
            ? null : state.optionTargetInstructionIds().get(index);
    }

    private void resumeWithResult(
        AwaitState completedAwait,
        JsonElement result,
        int resumeAddress,
        String resumeInstructionId
    ) {
        if (completedAwait.resultVariable() != null) {
            putLocalUnchecked(completedAwait.resultVariable(), result);
        }
        programCounter = resumeAddress;
        programCounterInstructionId = resumeInstructionId;
        status = Status.RUNNING;
    }

    private void putLocalUnchecked(String name, JsonElement value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("지역 변수 이름이 필요합니다.");
        }
        locals.put(name, value == null ? JsonNull.INSTANCE : value.deepCopy());
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException(
                expected + " 상태에서만 가능한 작업입니다. 현재 상태: " + status
            );
        }
    }

    private JsonObject keyToJson() {
        JsonObject value = new JsonObject();
        value.addProperty("player_id", key.playerId().toString());
        value.addProperty("npc_id", key.npcId().toString());
        value.addProperty("script_id", key.scriptId());
        value.addProperty("trigger_instance", key.triggerInstance());
        return value;
    }

    private static JsonObject awaitToJson(AwaitState state) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", state.kind());
        value.addProperty("token", state.token());
        addNullable(value, "operation_id", state.operationId());
        if (state.resumeAddress() == null) {
            value.add("resume_address", JsonNull.INSTANCE);
        } else {
            value.addProperty("resume_address", state.resumeAddress());
        }
        value.add("option_targets", integerArray(state.optionTargets()));
        addNullable(value, "resume_instruction_id", state.resumeInstructionId());
        value.add(
            "option_target_instruction_ids",
            stringArray(state.optionTargetInstructionIds())
        );
        addNullable(value, "result_variable", state.resultVariable());
        value.addProperty("expires_at_epoch_milli", state.expiresAtEpochMilli());
        return value;
    }

    private static JsonArray integerArray(Iterable<Integer> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static void addNullable(JsonObject object, String name, String value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    private static String nullableString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Integer nullableInteger(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsInt();
    }

    private static List<Integer> integerList(JsonArray values) {
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.getAsInt()));
        return List.copyOf(result);
    }

    private static List<String> stringList(JsonArray values) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return List.copyOf(result);
    }
}
