package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Converts the typed starter_roulette command to the common CVES await contract. */
public final class StarterRouletteEventCommandAdapter implements EventCommandAdapter {
    private final EventStarterRouletteGateway gateway;
    private final EventCommandAdapter fallback;

    public StarterRouletteEventCommandAdapter(
        EventStarterRouletteGateway gateway, EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!instruction.operation().equals("command")
            || !"starter_roulette".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null) {
            throw new EventRuntimeException(
                "starter_roulette에는 await, resume 주소와 결과 변수가 필요합니다."
            );
        }
        EventStarterRouletteGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventStarterRouletteGateway.RouletteRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId()
            )),
            "starter roulette gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }
}
