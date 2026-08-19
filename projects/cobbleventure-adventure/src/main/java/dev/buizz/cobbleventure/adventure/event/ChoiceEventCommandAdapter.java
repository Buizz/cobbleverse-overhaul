package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts a structured choice instruction to the common CVES await contract. */
public final class ChoiceEventCommandAdapter implements EventCommandAdapter {
    private final EventChoiceGateway gateway;
    private final EventCommandAdapter fallback;

    public ChoiceEventCommandAdapter(EventChoiceGateway gateway, EventCommandAdapter fallback) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"choice".equals(instruction.operation())) return fallback.start(context);
        if (!instruction.awaitsResult()) {
            throw new EventRuntimeException("choice에는 await 계약이 필요합니다.");
        }
        JsonObject payload = instruction.rawPayload();
        JsonElement prompt = payload.get("prompt");
        JsonElement rawOptions = payload.get("options");
        if (prompt == null || prompt.isJsonNull()
            || rawOptions == null || !rawOptions.isJsonArray()) {
            throw new EventRuntimeException("choice에는 prompt와 options가 필요합니다.");
        }
        JsonArray options = rawOptions.getAsJsonArray();
        if (options.isEmpty()) throw new EventRuntimeException("choice options는 비어 있을 수 없습니다.");
        List<JsonElement> texts = new ArrayList<>();
        for (JsonElement value : options) {
            if (!value.isJsonObject()) {
                throw new EventRuntimeException("choice option은 객체여야 합니다.");
            }
            JsonElement text = value.getAsJsonObject().get("text");
            if (text == null || text.isJsonNull()) {
                throw new EventRuntimeException("choice option에 text가 필요합니다.");
            }
            texts.add(text);
        }
        EventChoiceGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventChoiceGateway.ChoiceRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId(),
                prompt, List.copyOf(texts), context.locals()
            )),
            "choice gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }
}
