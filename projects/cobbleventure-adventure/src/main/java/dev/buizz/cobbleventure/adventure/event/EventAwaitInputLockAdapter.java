package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Adds the common input-lock lifecycle to every asynchronous command adapter. */
final class EventAwaitInputLockAdapter implements EventCommandAdapter {
    private final ServerPlayer player;
    private final EventCommandAdapter delegate;

    EventAwaitInputLockAdapter(ServerPlayer player, EventCommandAdapter delegate) {
        this.player = Objects.requireNonNull(player, "player");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public StartResult start(CommandContext context) {
        StartResult result = delegate.start(context);
        if (result instanceof Waiting waiting) {
            EventScript.Instruction instruction = context.instruction();
            String kind = instruction.command() == null
                ? instruction.operation() : instruction.command();
            EventAwaitInputLockService.acquire(
                player, context.sessionKey(), waiting.token(), kind
            );
        }
        return result;
    }
}
