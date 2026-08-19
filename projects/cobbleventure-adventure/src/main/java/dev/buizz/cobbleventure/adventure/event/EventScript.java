package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;

/** Immutable runtime view of a validated CVES Runtime IR V1 document. */
public record EventScript(
    int schemaVersion,
    String scriptId,
    String sourceDigest,
    List<Event> events
) {
    public EventScript {
        events = List.copyOf(events);
    }

    public record Event(int index, Trigger trigger, List<Page> pages, List<Instruction> instructions) {
        public Event {
            pages = List.copyOf(pages);
            instructions = List.copyOf(instructions);
        }

        public Instruction instruction(int address) {
            if (address < 0 || address >= instructions.size()) {
                throw new IllegalArgumentException("명령 주소가 범위를 벗어났습니다: " + address);
            }
            return instructions.get(address);
        }

        public Instruction instruction(String instructionId) {
            return instructions.stream()
                .filter(instruction -> instruction.instructionId().equals(instructionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "명령 ID를 찾을 수 없습니다: " + instructionId
                ));
        }

        public boolean hasInstruction(String instructionId) {
            return instructionId != null && instructions.stream().anyMatch(
                instruction -> instruction.instructionId().equals(instructionId)
            );
        }
    }

    public record Trigger(String name, JsonObject payload) {
        public Trigger {
            Objects.requireNonNull(name, "name");
            payload = payload.deepCopy();
        }

        @Override
        public JsonObject payload() {
            return payload.deepCopy();
        }
    }

    public record Page(int index, JsonElement condition, int entry) {
        public Page {
            condition = condition == null ? null : condition.deepCopy();
        }

        @Override
        public JsonElement condition() {
            return condition == null ? null : condition.deepCopy();
        }
    }

    public record Instruction(int address, String instructionId, String operation, JsonObject payload) {
        public Instruction {
            Objects.requireNonNull(instructionId, "instructionId");
            Objects.requireNonNull(operation, "operation");
            payload = payload.deepCopy();
        }

        @Override
        public JsonObject payload() {
            return payload.deepCopy();
        }

        JsonObject rawPayload() {
            return payload;
        }

        public String command() {
            return payload.has("command") ? payload.get("command").getAsString() : null;
        }

        public String operationId() {
            return payload.has("operation_id") ? payload.get("operation_id").getAsString() : null;
        }

        public String resultVariable() {
            return payload.has("result") && !payload.get("result").isJsonNull()
                ? payload.get("result").getAsString()
                : null;
        }

        public boolean awaitsResult() {
            return payload.has("await") && payload.get("await").getAsBoolean();
        }

        public Integer nextAddress() {
            if (!payload.has("next") || payload.get("next").isJsonNull()) {
                return null;
            }
            return payload.get("next").getAsInt();
        }

        public Integer resumeAddress() {
            if (!payload.has("resume") || payload.get("resume").isJsonNull()) {
                return null;
            }
            return payload.get("resume").getAsInt();
        }
    }
}
