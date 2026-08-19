package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Converts the typed give_item command to the common CVES await contract. */
public final class GiveItemEventCommandAdapter implements EventCommandAdapter {
    private final EventGiveItemGateway gateway;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public GiveItemEventCommandAdapter(
        EventGiveItemGateway gateway,
        EventExpressionEnvironment environment,
        EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.evaluator = new EventExpressionEvaluator(environment);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"give_item".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null
            || instruction.operationId() == null) {
            throw new EventRuntimeException(
                "give_item에는 안정 ID, await, resume 주소와 결과 변수가 필요합니다."
            );
        }

        ParsedArguments arguments = parseArguments(instruction, context.locals());
        EventGiveItemGateway.OpenResult opened = Objects.requireNonNull(
            gateway.grant(new EventGiveItemGateway.GrantRequest(
                context.sessionKey(),
                context.sourceDigest(),
                instruction.instructionId(),
                instruction.operationId(),
                arguments.itemId(),
                arguments.count(),
                arguments.showNotification()
            )),
            "give item gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    private ParsedArguments parseArguments(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("arguments");
        if (raw == null || !raw.isJsonArray()) {
            throw new EventRuntimeException("give_item arguments는 배열이어야 합니다.");
        }
        String itemId = null;
        int count = 1;
        boolean countSeen = false;
        boolean notify = false;
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new EventRuntimeException("give_item argument는 객체여야 합니다.");
            }
            JsonObject argument = element.getAsJsonObject();
            JsonElement nameValue = argument.get("name");
            String name = nameValue == null || nameValue.isJsonNull()
                ? null : nameValue.getAsString();
            JsonElement value = argument.get("value");
            if (name == null) {
                if (itemId != null || value == null || value.isJsonNull()) {
                    throw new EventRuntimeException("give_item에는 item 인자가 하나 필요합니다.");
                }
                JsonElement evaluated = evaluator.evaluate(value, locals);
                if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isString()) {
                    throw new EventRuntimeException("give_item item은 리소스 ID 문자열이어야 합니다.");
                }
                itemId = evaluated.getAsString();
            } else if (name.equals("count")) {
                if (countSeen || value == null || value.isJsonNull()) {
                    throw new EventRuntimeException("give_item count 인자가 올바르지 않습니다.");
                }
                count = evaluator.evaluateInt(value, locals);
                countSeen = true;
            } else if (name.equals("notify")) {
                if (notify || (value != null && !value.isJsonNull())) {
                    throw new EventRuntimeException("give_item notify는 값 없는 flag여야 합니다.");
                }
                notify = true;
            } else {
                throw new EventRuntimeException("알 수 없는 give_item 인자입니다: " + name);
            }
        }
        if (itemId == null || ResourceLocation.tryParse(itemId) == null) {
            throw new EventRuntimeException("올바른 give_item 리소스 ID가 필요합니다: " + itemId);
        }
        if (count <= 0 || count > 262_144) {
            throw new EventRuntimeException("give_item count는 1..262144 범위여야 합니다: " + count);
        }
        return new ParsedArguments(itemId, count, notify);
    }

    private record ParsedArguments(String itemId, int count, boolean showNotification) {}
}
