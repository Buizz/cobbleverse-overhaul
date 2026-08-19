package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict reader for compiler-generated CVES Runtime IR V1 JSON. */
public final class EventScriptLoader {
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern SCRIPT_ID = Pattern.compile(
        "^[a-z0-9_.-]+:event_script/[a-z0-9_./-]+$"
    );
    private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> OPERATIONS = Set.of(
        "say", "narrate", "let", "command", "branch", "choice",
        "repeat_begin", "repeat_next", "jump", "call", "return",
        "label", "page_end"
    );

    private EventScriptLoader() {}

    public static EventScript parse(String json) {
        try {
            return parse(JsonParser.parseString(json));
        } catch (JsonParseException | IllegalStateException error) {
            throw new EventScriptFormatException("Runtime IR JSON을 읽을 수 없습니다.", error);
        }
    }

    public static EventScript parse(Reader reader) {
        try {
            return parse(JsonParser.parseReader(reader));
        } catch (JsonParseException | IllegalStateException error) {
            throw new EventScriptFormatException("Runtime IR JSON을 읽을 수 없습니다.", error);
        }
    }

    private static EventScript parse(JsonElement document) {
        JsonObject root = object(document, "$");
        int version = integer(root, "schema_version", "$");
        if (version != SCHEMA_VERSION) {
            throw invalid("$.schema_version", "지원하지 않는 버전입니다: " + version);
        }
        String scriptId = string(root, "script_id", "$");
        if (!SCRIPT_ID.matcher(scriptId).matches()) {
            throw invalid("$.script_id", "namespace:event_script/path 형식이어야 합니다.");
        }
        String digest = string(root, "source_digest", "$");
        if (!SHA_256.matcher(digest).matches()) {
            throw invalid("$.source_digest", "SHA-256 소문자 16진수여야 합니다.");
        }

        JsonArray eventValues = array(root, "events", "$");
        if (eventValues.size() == 0) {
            throw invalid("$.events", "이벤트가 하나 이상 필요합니다.");
        }
        List<EventScript.Event> events = new ArrayList<>();
        for (int index = 0; index < eventValues.size(); index++) {
            String path = "$.events[" + index + "]";
            JsonObject event = object(eventValues.get(index), path);
            int eventIndex = integer(event, "index", path);
            if (eventIndex != index) {
                throw invalid(path + ".index", "0부터 연속된 이벤트 인덱스여야 합니다.");
            }
            JsonObject trigger = object(required(event, "trigger", path), path + ".trigger");
            String triggerName = string(trigger, "name", path + ".trigger");
            JsonArray pageValues = array(event, "pages", path);
            JsonArray instructionValues = array(event, "instructions", path);
            if (pageValues.size() == 0 || instructionValues.size() == 0) {
                throw invalid(path, "페이지와 명령이 하나 이상 필요합니다.");
            }
            List<EventScript.Instruction> instructions = instructions(instructionValues, path);
            List<EventScript.Page> pages = pages(pageValues, instructions.size(), path);
            validateSourceMap(event, instructions, path);
            events.add(new EventScript.Event(
                eventIndex,
                new EventScript.Trigger(triggerName, trigger),
                pages,
                instructions
            ));
        }
        return new EventScript(version, scriptId, digest, events);
    }

    private static List<EventScript.Instruction> instructions(JsonArray values, String eventPath) {
        List<EventScript.Instruction> result = new ArrayList<>();
        Set<String> instructionIds = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = eventPath + ".instructions[" + index + "]";
            JsonObject value = object(values.get(index), path);
            int address = integer(value, "address", path);
            if (address != index) {
                throw invalid(path + ".address", "0부터 연속된 명령 주소여야 합니다.");
            }
            String instructionId = string(value, "instruction_id", path);
            if (!instructionIds.add(instructionId)) {
                throw invalid(path + ".instruction_id", "중복 명령 ID입니다: " + instructionId);
            }
            String operation = string(value, "op", path);
            if (!OPERATIONS.contains(operation)) {
                throw invalid(path + ".op", "알 수 없는 명령 종류입니다: " + operation);
            }
            if (value.has("operation_id")) {
                String operationId = string(value, "operation_id", path);
                if (!operationIds.add(operationId)) {
                    throw invalid(path + ".operation_id", "중복 작업 ID입니다: " + operationId);
                }
            }
            result.add(new EventScript.Instruction(address, instructionId, operation, value));
        }
        for (EventScript.Instruction instruction : result) {
            validateInstruction(instruction, result.size(), eventPath);
        }
        return List.copyOf(result);
    }

    private static List<EventScript.Page> pages(JsonArray values, int instructionCount, String eventPath) {
        List<EventScript.Page> result = new ArrayList<>();
        boolean foundDefault = false;
        for (int index = 0; index < values.size(); index++) {
            String path = eventPath + ".pages[" + index + "]";
            JsonObject value = object(values.get(index), path);
            int pageIndex = integer(value, "index", path);
            if (pageIndex != index) {
                throw invalid(path + ".index", "0부터 연속된 페이지 인덱스여야 합니다.");
            }
            int entry = integer(value, "entry", path);
            target(entry, instructionCount, path + ".entry");
            JsonElement condition = required(value, "condition", path);
            boolean isDefault = condition.isJsonNull();
            if (foundDefault) {
                throw invalid(path + ".condition", "default 페이지 뒤에는 페이지를 둘 수 없습니다.");
            }
            foundDefault = isDefault;
            result.add(new EventScript.Page(pageIndex, isDefault ? null : condition, entry));
        }
        return List.copyOf(result);
    }

    private static void validateInstruction(
        EventScript.Instruction instruction, int instructionCount, String eventPath
    ) {
        JsonObject value = instruction.rawPayload();
        String path = eventPath + ".instructions[" + instruction.address() + "]";
        switch (instruction.operation()) {
            case "say", "narrate" -> {
                target(integer(value, "next", path), instructionCount, path + ".next");
                if (!booleanValue(value, "await", path)) {
                    throw invalid(path + ".await", "대화 명령은 await 경계여야 합니다.");
                }
                target(integer(value, "resume", path), instructionCount, path + ".resume");
            }
            case "let", "label" ->
                target(integer(value, "next", path), instructionCount, path + ".next");
            case "command" -> validateCommand(value, instructionCount, path);
            case "branch" -> {
                target(integer(value, "then", path), instructionCount, path + ".then");
                target(integer(value, "else", path), instructionCount, path + ".else");
            }
            case "choice" -> {
                if (!booleanValue(value, "await", path)) {
                    throw invalid(path + ".await", "선택지는 await 경계여야 합니다.");
                }
                JsonArray options = array(value, "options", path);
                if (options.size() == 0) {
                    throw invalid(path + ".options", "선택지가 하나 이상 필요합니다.");
                }
                for (int index = 0; index < options.size(); index++) {
                    JsonObject option = object(options.get(index), path + ".options[" + index + "]");
                    target(integer(option, "target", path), instructionCount, path + ".options[" + index + "].target");
                }
            }
            case "repeat_begin" -> {
                target(integer(value, "body", path), instructionCount, path + ".body");
                target(integer(value, "exit", path), instructionCount, path + ".exit");
            }
            case "repeat_next", "jump" ->
                target(integer(value, "target", path), instructionCount, path + ".target");
            case "call" -> {
                target(integer(value, "target", path), instructionCount, path + ".target");
                target(integer(value, "return_address", path), instructionCount, path + ".return_address");
            }
            case "return", "page_end" -> {
                // Terminal/control-stack instructions have no address field to validate.
            }
            default -> throw invalid(path + ".op", "검증하지 않은 명령 종류입니다.");
        }
    }

    private static void validateCommand(JsonObject value, int count, String path) {
        string(value, "command", path);
        boolean awaits = booleanValue(value, "await", path);
        required(value, "result", path);
        JsonElement next = required(value, "next", path);
        if (!next.isJsonNull()) {
            target(asInteger(next, path + ".next"), count, path + ".next");
        }
        if (awaits) {
            int resume = integer(value, "resume", path);
            target(resume, count, path + ".resume");
            if (next.isJsonNull() || resume != next.getAsInt()) {
                throw invalid(path + ".resume", "현재 IR V1에서는 next와 같은 주소여야 합니다.");
            }
        }
    }

    private static void validateSourceMap(
        JsonObject event, List<EventScript.Instruction> instructions, String eventPath
    ) {
        JsonArray values = array(event, "source_map", eventPath);
        if (values.size() != instructions.size()) {
            throw invalid(eventPath + ".source_map", "명령 수와 같은 항목 수가 필요합니다.");
        }
        for (int index = 0; index < values.size(); index++) {
            String path = eventPath + ".source_map[" + index + "]";
            JsonObject value = object(values.get(index), path);
            if (integer(value, "address", path) != index) {
                throw invalid(path + ".address", "명령 주소와 일치해야 합니다.");
            }
            String instructionId = string(value, "instruction_id", path);
            if (!instructionId.equals(instructions.get(index).instructionId())) {
                throw invalid(path + ".instruction_id", "명령 ID와 일치해야 합니다.");
            }
        }
    }

    private static JsonElement required(JsonObject object, String name, String path) {
        if (!object.has(name)) {
            throw invalid(path + "." + name, "필수 필드입니다.");
        }
        return object.get(name);
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw invalid(path, "객체여야 합니다.");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name, String path) {
        JsonElement value = required(object, name, path);
        if (!value.isJsonArray()) {
            throw invalid(path + "." + name, "배열이어야 합니다.");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String name, String path) {
        JsonElement value = required(object, name, path);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(path + "." + name, "문자열이어야 합니다.");
        }
        String result = value.getAsString();
        if (result.isBlank()) {
            throw invalid(path + "." + name, "빈 문자열일 수 없습니다.");
        }
        return result;
    }

    private static int integer(JsonObject object, String name, String path) {
        return asInteger(required(object, name, path), path + "." + name);
    }

    private static int asInteger(JsonElement value, String path) {
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw invalid(path, "정수여야 합니다.");
            }
            int result = value.getAsInt();
            if (value.getAsDouble() != result) {
                throw invalid(path, "정수여야 합니다.");
            }
            return result;
        } catch (NumberFormatException error) {
            throw invalid(path, "정수여야 합니다.");
        }
    }

    private static boolean booleanValue(JsonObject object, String name, String path) {
        JsonElement value = required(object, name, path);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(path + "." + name, "boolean이어야 합니다.");
        }
        return value.getAsBoolean();
    }

    private static void target(int address, int count, String path) {
        if (address < 0 || address >= count) {
            throw invalid(path, "명령 주소 범위를 벗어났습니다: " + address);
        }
    }

    private static EventScriptFormatException invalid(String path, String message) {
        return new EventScriptFormatException(path + ": " + message);
    }
}
