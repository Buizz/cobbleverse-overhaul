package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import java.util.Objects;

/** Opens the daycare storage screen after the authored V5 dialogue completes. */
public final class DaycareEventCommandAdapter implements EventCommandAdapter {
    private final EventDaycareGateway gateway;
    private final EventCommandAdapter fallback;

    public DaycareEventCommandAdapter(
        EventDaycareGateway gateway, EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"open_daycare".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (instruction.awaitsResult()
            || instruction.resumeAddress() != null
            || instruction.resultVariable() != null
            || instruction.operationId() != null) {
            throw new EventRuntimeException(
                "open_daycare는 await, 결과 변수 또는 안정 ID를 사용하지 않습니다."
            );
        }
        JsonArray arguments = instruction.rawPayload().getAsJsonArray("arguments");
        JsonArray properties = instruction.rawPayload().getAsJsonArray("properties");
        if (arguments == null || !arguments.isEmpty()
            || properties == null || !properties.isEmpty()) {
            throw new EventRuntimeException("open_daycare는 인자와 속성을 받지 않습니다.");
        }
        gateway.open(context.sessionKey());
        return new Completed(JsonNull.INSTANCE);
    }
}
