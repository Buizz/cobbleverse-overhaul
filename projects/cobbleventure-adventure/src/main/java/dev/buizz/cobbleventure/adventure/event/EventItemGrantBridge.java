package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/** Internal bridge between CVES give_item awaits and Player Menu's reward journal. */
public final class EventItemGrantBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TIMEOUT_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_RESUME_STEPS = 10_000;

    private EventItemGrantBridge() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventItemGrantBridge::registerCommands);
    }

    public static EventGiveItemGateway gateway(ServerPlayer player) {
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException("give_item 요청의 player와 gateway player가 다릅니다.");
            }
            String token = UUID.randomUUID().toString();
            String command = "cobbleventure_item_grant_session "
                + token + " "
                + StringArgumentType.escapeIfRequired(request.operationId()) + " "
                + StringArgumentType.escapeIfRequired(request.itemId()) + " "
                + request.count() + " " + request.showNotification();
            player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                command
            );
            EventAwaitCallbackRegistry.register(token, request.sessionKey());
            return new EventGiveItemGateway.OpenResult(
                token, System.currentTimeMillis() + TIMEOUT_MILLIS
            );
        };
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event_item_result")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("token", StringArgumentType.word())
                        .then(Commands.argument("requested", IntegerArgumentType.integer(0))
                            .then(Commands.argument("granted", IntegerArgumentType.integer(0))
                                .then(Commands.argument("remaining", IntegerArgumentType.integer(0))
                                    .executes(context -> complete(
                                        context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "token"),
                                        IntegerArgumentType.getInteger(context, "requested"),
                                        IntegerArgumentType.getInteger(context, "granted"),
                                        IntegerArgumentType.getInteger(context, "remaining"),
                                        null
                                    ))
                                    .then(Commands.argument(
                                        "failure_reason", StringArgumentType.word()
                                    ).executes(context -> complete(
                                        context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "token"),
                                        IntegerArgumentType.getInteger(context, "requested"),
                                        IntegerArgumentType.getInteger(context, "granted"),
                                        IntegerArgumentType.getInteger(context, "remaining"),
                                        StringArgumentType.getString(context, "failure_reason")
                                    )))))))
        );
    }

    private static int complete(
        ServerPlayer player,
        String token,
        int requested,
        int granted,
        int remaining,
        String failureReason
    ) {
        if (granted + remaining != requested) {
            LOGGER.warn(
                "Invalid item reward callback counts: requested={}, granted={}, remaining={}",
                requested, granted, remaining
            );
            return 0;
        }
        SavedEventSessionStore store = SavedEventSessionStore.get(player.getServer());
        Optional<EventSessionKey> key = EventAwaitCallbackRegistry.find(
            store, player.getUUID(), token
        );
        if (key.isEmpty()) return 0;
        EventScript script = EventScriptRepository.instance()
            .find(key.orElseThrow().scriptId()).orElse(null);
        if (script == null) return 0;

        JsonObject result = new JsonObject();
        result.addProperty("requested_count", requested);
        result.addProperty("granted_count", granted);
        result.addProperty("remaining_count", remaining);
        if (failureReason != null) result.addProperty("failure_reason", failureReason);
        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                player.getUUID(),
                key.orElseThrow(),
                token,
                new EventSession.AwaitCompletion(
                    remaining == 0 && failureReason == null
                        ? EventSession.CompletionKind.COMPLETED
                        : EventSession.CompletionKind.FAILED,
                    result
                ),
                script,
                new EventStateExpressionEnvironment(new ServerPlayerEventState(
                    player, key.orElseThrow().npcId()
                )),
                EventDialogueNetwork.serverAdapter(player, key.orElseThrow().npcId()),
                store,
                MAX_RESUME_STEPS
            );
        if (outcome.status() != EventAwaitCompletionService.Status.STALE) {
            EventAwaitCallbackRegistry.forget(token);
        }
        return outcome.status() == EventAwaitCompletionService.Status.RESUMED
            || outcome.status() == EventAwaitCompletionService.Status.DUPLICATE ? 1 : 0;
    }
}
