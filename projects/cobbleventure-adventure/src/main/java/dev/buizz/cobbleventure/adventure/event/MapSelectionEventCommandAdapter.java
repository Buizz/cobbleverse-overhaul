package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Converts map_selection to the common persisted CVES await contract. */
public final class MapSelectionEventCommandAdapter implements EventCommandAdapter {
    private final EventMapSelectionGateway gateway;
    private final EventCommandAdapter fallback;

    public MapSelectionEventCommandAdapter(
        EventMapSelectionGateway gateway, EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"map_selection".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null
            || instruction.operationId() == null) {
            throw new EventRuntimeException(
                "map_selection에는 안정 ID, await, resume 주소와 결과 변수가 필요합니다."
            );
        }
        if (!instruction.rawPayload().has("arguments")
            || !instruction.rawPayload().get("arguments").isJsonArray()
            || !instruction.rawPayload().getAsJsonArray("arguments").isEmpty()
            || !instruction.rawPayload().has("properties")
            || !instruction.rawPayload().get("properties").isJsonArray()
            || !instruction.rawPayload().getAsJsonArray("properties").isEmpty()) {
            throw new EventRuntimeException("map_selection V1은 인자와 속성을 받지 않습니다.");
        }
        EventMapSelectionGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventMapSelectionGateway.SelectionRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId()
            )),
            "map selection gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }
}
