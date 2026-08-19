package dev.buizz.cobbleventure.adventure.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Internal bridge between CVES give_loot awaits and Player Menu's reward journal. */
public final class EventLootGrantBridge {
    private static final long TIMEOUT_MILLIS = 5L * 60L * 1000L;

    private EventLootGrantBridge() {}

    public static EventGiveLootGateway gateway(ServerPlayer player) {
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException("give_loot 요청의 player와 gateway player가 다릅니다.");
            }
            String token = UUID.randomUUID().toString();
            String command = "cobbleventure_loot_grant_session "
                + token + " "
                + StringArgumentType.escapeIfRequired(request.operationId()) + " "
                + StringArgumentType.escapeIfRequired(request.lootTableId()) + " "
                + request.rollCount() + " " + request.showNotification();
            player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                command
            );
            EventAwaitCallbackRegistry.register(token, request.sessionKey());
            return new EventGiveLootGateway.OpenResult(
                token, System.currentTimeMillis() + TIMEOUT_MILLIS
            );
        };
    }
}
