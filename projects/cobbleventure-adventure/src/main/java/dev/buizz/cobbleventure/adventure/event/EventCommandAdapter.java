package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import java.util.Map;
import java.util.Objects;

/** Starts one externally implemented dialogue, reward, battle, UI or movement command. */
@FunctionalInterface
public interface EventCommandAdapter {
    StartResult start(CommandContext context);

    record CommandContext(
        EventSessionKey sessionKey,
        String sourceDigest,
        EventScript.Instruction instruction,
        Map<String, JsonElement> locals
    ) {
        public CommandContext {
            Objects.requireNonNull(sessionKey, "sessionKey");
            Objects.requireNonNull(sourceDigest, "sourceDigest");
            Objects.requireNonNull(instruction, "instruction");
            locals = Map.copyOf(locals);
        }
    }

    sealed interface StartResult permits Completed, Selected, Waiting, Failed, Cancelled {}

    record Completed(JsonElement result) implements StartResult {
        public Completed {
            result = copy(result);
        }

        @Override
        public JsonElement result() {
            return result.deepCopy();
        }
    }

    record Waiting(String token, long expiresAtEpochMilli) implements StartResult {
        public Waiting {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("await token이 필요합니다.");
            }
        }
    }

    /** Immediate choice completion; asynchronous choices return the index in AwaitCompletion. */
    record Selected(int optionIndex) implements StartResult {
        public Selected {
            if (optionIndex < 0) {
                throw new IllegalArgumentException("optionIndex는 0 이상이어야 합니다.");
            }
        }
    }

    record Failed(JsonElement result) implements StartResult {
        public Failed {
            result = copy(result);
        }

        @Override
        public JsonElement result() {
            return result.deepCopy();
        }
    }

    record Cancelled(JsonElement result) implements StartResult {
        public Cancelled {
            result = copy(result);
        }

        @Override
        public JsonElement result() {
            return result.deepCopy();
        }
    }

    private static JsonElement copy(JsonElement value) {
        return value == null ? JsonNull.INSTANCE : value.deepCopy();
    }
}
