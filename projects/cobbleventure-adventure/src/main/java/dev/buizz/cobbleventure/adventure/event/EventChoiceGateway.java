package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transport boundary for one structured CVES choice without dialogue labels. */
@FunctionalInterface
public interface EventChoiceGateway {
    OpenResult open(ChoiceRequest request);

    record ChoiceRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        JsonElement prompt,
        List<JsonElement> options,
        Map<String, JsonElement> locals
    ) {
        public ChoiceRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
            prompt = Objects.requireNonNull(prompt, "prompt").deepCopy();
            options = Objects.requireNonNull(options, "options").stream()
                .map(value -> Objects.requireNonNull(value, "option").deepCopy())
                .toList();
            if (options.isEmpty()) throw new IllegalArgumentException("선택지가 필요합니다.");
            LinkedHashMap<String, JsonElement> copied = new LinkedHashMap<>();
            Objects.requireNonNull(locals, "locals").forEach(
                (name, value) -> copied.put(name, value.deepCopy())
            );
            locals = Collections.unmodifiableMap(copied);
        }

        @Override public JsonElement prompt() { return prompt.deepCopy(); }

        @Override public List<JsonElement> options() {
            return options.stream().map(JsonElement::deepCopy).toList();
        }

        @Override public Map<String, JsonElement> locals() {
            LinkedHashMap<String, JsonElement> copied = new LinkedHashMap<>();
            locals.forEach((name, value) -> copied.put(name, value.deepCopy()));
            return Collections.unmodifiableMap(copied);
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + "가 필요합니다.");
            }
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("choice await token이 필요합니다.");
            }
        }
    }
}
