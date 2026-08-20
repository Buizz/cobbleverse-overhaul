package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
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
        if (arguments == null || !arguments.isEmpty()
            || properties == null || !properties.isEmpty()) {
            throw new EventRuntimeException("heal_party는 인자와 속성을 받지 않습니다.");
        }
        EventHealingGateway.OpenResult opened = Objects.requireNonNull(
            gateway.heal(new EventHealingGateway.HealingRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId()
            )),
            "healing gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }
}
