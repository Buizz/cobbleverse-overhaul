package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Converts the typed give_loot command to the common CVES await contract. */
public final class GiveLootEventCommandAdapter implements EventCommandAdapter {
    private final EventGiveLootGateway gateway;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public GiveLootEventCommandAdapter(
        EventGiveLootGateway gateway,
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
            || !"give_loot".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null
            || instruction.operationId() == null) {
            throw new EventRuntimeException(
                "give_loot에는 안정 ID, await, resume 주소와 결과 변수가 필요합니다."
            );
        }

        ParsedArguments arguments = parseArguments(instruction, context.locals());
        EventGiveLootGateway.OpenResult opened = Objects.requireNonNull(
            gateway.grant(new EventGiveLootGateway.GrantRequest(
                context.sessionKey(),
                context.sourceDigest(),
                instruction.instructionId(),
                instruction.operationId(),
                arguments.lootTableId(),
                arguments.rollCount(),
                arguments.showNotification()
            )),
            "give loot gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    private ParsedArguments parseArguments(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("arguments");
        if (raw == null || !raw.isJsonArray()) {
            throw new EventRuntimeException("give_loot arguments는 배열이어야 합니다.");
        }
        String lootTableId = null;
        int rollCount = 1;
        boolean countSeen = false;
        boolean notify = false;
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new EventRuntimeException("give_loot argument는 객체여야 합니다.");
            }
            JsonObject argument = element.getAsJsonObject();
            JsonElement nameValue = argument.get("name");
            String name = nameValue == null || nameValue.isJsonNull()
                ? null : nameValue.getAsString();
            JsonElement value = argument.get("value");
            if (name == null) {
                if (lootTableId != null || value == null || value.isJsonNull()) {
                    throw new EventRuntimeException("give_loot에는 loot 인자가 하나 필요합니다.");
                }
                JsonElement evaluated = evaluator.evaluate(value, locals);
                if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isString()) {
                    throw new EventRuntimeException("give_loot loot은 리소스 ID 문자열이어야 합니다.");
                }
                lootTableId = evaluated.getAsString();
            } else if (name.equals("count")) {
                if (countSeen || value == null || value.isJsonNull()) {
                    throw new EventRuntimeException("give_loot count 인자가 올바르지 않습니다.");
                }
                rollCount = evaluator.evaluateInt(value, locals);
                countSeen = true;
            } else if (name.equals("notify")) {
                if (notify || (value != null && !value.isJsonNull())) {
                    throw new EventRuntimeException("give_loot notify는 값 없는 flag여야 합니다.");
                }
                notify = true;
            } else {
                throw new EventRuntimeException("알 수 없는 give_loot 인자입니다: " + name);
            }
        }
        if (lootTableId == null || ResourceLocation.tryParse(lootTableId) == null) {
            throw new EventRuntimeException(
                "올바른 give_loot 리소스 ID가 필요합니다: " + lootTableId
            );
        }
        if (rollCount <= 0 || rollCount > 1024) {
            throw new EventRuntimeException(
                "give_loot count는 1..1024 범위여야 합니다: " + rollCount
            );
        }
        return new ParsedArguments(lootTableId, rollCount, notify);
    }

    private record ParsedArguments(
        String lootTableId, int rollCount, boolean showNotification
    ) {}
}
