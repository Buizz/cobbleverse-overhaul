package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Converts say and narrate instructions into the common CVES await contract. */
public final class DialogueEventCommandAdapter implements EventCommandAdapter {
    private final EventDialogueGateway gateway;
    private final EventCommandAdapter fallback;

    public DialogueEventCommandAdapter(
        EventDialogueGateway gateway,
        EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        String operation = context.instruction().operation();
        if (!operation.equals("say") && !operation.equals("narrate")) {
            return fallback.start(context);
        }
        EventScript.Instruction instruction = context.instruction();
        if (!instruction.awaitsResult() || instruction.resumeAddress() == null) {
            throw new EventRuntimeException(operation + " 명령에는 await resume 주소가 필요합니다.");
        }
        JsonObject payload = instruction.rawPayload();
        JsonElement text = payload.get("text");
        if (text == null || text.isJsonNull()) {
            throw new EventRuntimeException(operation + " 명령에 text가 없습니다.");
        }
        String speaker = operation.equals("say") ? requiredString(payload, "speaker") : null;
        EventDialogueGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventDialogueGateway.DialogueRequest(
                context.sessionKey(),
                context.sourceDigest(),
                instruction.instructionId(),
                operation.equals("say")
                    ? EventDialogueGateway.Kind.SAY
                    : EventDialogueGateway.Kind.NARRATE,
                speaker,
                text,
                context.locals()
            )),
            "dialogue gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException("문자열 필드가 필요합니다: " + name);
        }
        return value.getAsString();
    }
}
