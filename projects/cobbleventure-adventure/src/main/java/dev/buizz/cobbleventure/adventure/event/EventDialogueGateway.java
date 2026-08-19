package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Transport boundary for opening one CVES dialogue line without EasyNPC labels. */
@FunctionalInterface
public interface EventDialogueGateway {
    OpenResult open(DialogueRequest request);

    enum Kind {
        SAY, NARRATE
    }

    record DialogueRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        Kind kind,
        String speaker,
        JsonElement text,
        Map<String, JsonElement> locals
    ) {
        public DialogueRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            Objects.requireNonNull(sourceDigest, "sourceDigest");
            Objects.requireNonNull(instructionId, "instructionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            text = text.deepCopy();
            LinkedHashMap<String, JsonElement> copied = new LinkedHashMap<>();
            Objects.requireNonNull(locals, "locals").forEach(
                (name, value) -> copied.put(name, value.deepCopy())
            );
            locals = Collections.unmodifiableMap(copied);
        }

        @Override
        public JsonElement text() {
            return text.deepCopy();
        }

        @Override
        public Map<String, JsonElement> locals() {
            LinkedHashMap<String, JsonElement> copied = new LinkedHashMap<>();
            locals.forEach((name, value) -> copied.put(name, value.deepCopy()));
            return Collections.unmodifiableMap(copied);
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("dialogue await token이 필요합니다.");
            }
        }
    }
}
