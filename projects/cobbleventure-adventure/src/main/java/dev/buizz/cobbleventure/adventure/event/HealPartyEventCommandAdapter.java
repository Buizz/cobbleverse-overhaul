package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Objects;

/** Converts heal_party to a repeatable common CVES await. */
public final class HealPartyEventCommandAdapter implements EventCommandAdapter {
    private final EventHealingGateway gateway;
    private final EventCommandAdapter fallback;

    public HealPartyEventCommandAdapter(
        EventHealingGateway gateway, EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"heal_party".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null
            || instruction.operationId() != null) {
            throw new EventRuntimeException(
                "heal_party에는 안정 ID, await, resume 주소와 결과 변수가 필요하며 "
                    + "반복 서비스에는 operation ID를 사용할 수 없습니다."
            );
        }
        JsonArray arguments = instruction.rawPayload().getAsJsonArray("arguments");
        JsonArray properties = instruction.rawPayload().getAsJsonArray("properties");
        if (arguments == null || properties == null || !properties.isEmpty()) {
            throw new EventRuntimeException("heal_party는 선택적인 fallback 플래그만 받습니다.");
        }
        boolean fallbackWithoutMachine = fallbackFlag(arguments);
        EventHealingGateway.OpenResult opened = Objects.requireNonNull(
            gateway.heal(new EventHealingGateway.HealingRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId(), fallbackWithoutMachine
            )),
            "healing gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    static boolean fallbackFlag(JsonArray arguments) {
        if (arguments.isEmpty()) return false;
        if (arguments.size() == 1 && arguments.get(0).isJsonObject()) {
            var argument = arguments.get(0).getAsJsonObject();
            JsonElement name = argument.get("name");
            JsonElement value = argument.get("value");
            if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()
                && "fallback".equals(name.getAsString()) && (value == null || value.isJsonNull())) {
                return true;
            }
        }
        throw new EventRuntimeException("heal_party는 중복 없는 fallback 플래그만 받습니다.");
    }
}
