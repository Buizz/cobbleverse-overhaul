package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Converts the typed battle command to the common CVES await contract. */
public final class BattleEventCommandAdapter implements EventCommandAdapter {
    private final EventBattleGateway gateway;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public BattleEventCommandAdapter(
        EventBattleGateway gateway,
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
            || !"battle".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null
            || instruction.operationId() == null) {
            throw new EventRuntimeException(
                "battle에는 안정 ID, await, resume 주소와 결과 변수가 필요합니다."
            );
        }
        String battleId = battleId(instruction, context.locals());
        EventBattleGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventBattleGateway.BattleRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId(),
                instruction.operationId(), battleId
            )),
            "battle gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    private String battleId(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("arguments");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 1) {
            throw new EventRuntimeException("battle에는 battle ID 인자가 하나 필요합니다.");
        }
        JsonElement element = raw.getAsJsonArray().get(0);
        if (!element.isJsonObject()) throw new EventRuntimeException("battle argument는 객체여야 합니다.");
        JsonObject argument = element.getAsJsonObject();
        if (!argument.get("name").isJsonNull()) {
            throw new EventRuntimeException("battle ID는 위치 인자여야 합니다.");
        }
        JsonElement value = argument.get("value");
        if (value == null || value.isJsonNull()) {
            throw new EventRuntimeException("battle ID 값이 필요합니다.");
        }
        JsonElement evaluated = evaluator.evaluate(value, locals);
        if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException("battle ID는 리소스 ID 문자열이어야 합니다.");
        }
        String battleId = evaluated.getAsString();
        if (ResourceLocation.tryParse(battleId) == null || !battleId.contains(":battle/")) {
            throw new EventRuntimeException("올바른 battle 리소스 ID가 필요합니다: " + battleId);
        }
        return battleId;
    }
}
