package dev.buizz.cobbleventure.adventure.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/** Command bridge between the CVES runtime and Player Menu map selection mode. */
public final class EventMapSelectionBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TIMEOUT_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_RESUME_STEPS = 10_000;

    private EventMapSelectionBridge() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventMapSelectionBridge::registerCommands);
    }

    public static EventMapSelectionGateway gateway(ServerPlayer player) {
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException(
                    "map selection 요청의 player와 gateway player가 다릅니다."
                );
            }
            String token = UUID.randomUUID().toString();
            String command = "cobbleventure_map_select_session "
                + token;
            int opened;
            try {
                opened = player.getServer().getCommands().getDispatcher().execute(
                    command,
                    player.createCommandSourceStack().withPermission(4).withSuppressedOutput()
                );
            } catch (CommandSyntaxException error) {
                throw new EventRuntimeException(
                    "Player Menu map selection 명령을 실행하지 못했습니다.", error
                );
            }
            if (opened <= 0) {
                throw new EventRuntimeException("Player Menu map selection을 열지 못했습니다.");
            }
            EventAwaitCallbackRegistry.register(token, request.sessionKey());
            return new EventMapSelectionGateway.OpenResult(
                token, System.currentTimeMillis() + TIMEOUT_MILLIS
            );
        };
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event_map_result")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("token", StringArgumentType.word())
                        .then(Commands.argument("settlement", StringArgumentType.string())
                            .executes(context -> complete(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "token"),
                                StringArgumentType.getString(context, "settlement")
                            ))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event_map_cancel")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("token", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.word())
                            .executes(context -> cancel(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "token"),
                                StringArgumentType.getString(context, "reason")
                            ))))
        );
    }

    private static int complete(
        ServerPlayer player, String token, String settlementId
    ) {
        ResourceLocation settlement = ResourceLocation.tryParse(settlementId);
        if (settlement == null || !settlement.getPath().startsWith("settlement/")) {
            LOGGER.warn("Invalid map selection settlement callback: {}", settlementId);
            return 0;
        }
        CallbackContext callback = callback(player, token);
        if (callback == null) return 0;
        EventLocationRef.Resource result = new EventLocationRef.Resource(
            EventLocationRef.Resource.Kind.SETTLEMENT, settlement.toString(), null
        );
        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                player.getUUID(), callback.key(), token,
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, result.toJson()
                ),
                callback.script(),
                new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
                EventDialogueNetwork.serverAdapter(player),
                callback.store(), MAX_RESUME_STEPS
            );
        if (outcome.status() != EventAwaitCompletionService.Status.STALE) {
            EventAwaitCallbackRegistry.forget(token);
        }
        return accepted(outcome.status()) ? 1 : 0;
    }

    private static int cancel(ServerPlayer player, String token, String reason) {
        CallbackContext callback = callback(player, token);
        if (callback == null) return 0;
        EventSession.CompletionKind kind = reason.equals("client_cancelled")
            ? EventSession.CompletionKind.CANCELLED
            : EventSession.CompletionKind.FAILED;
        EventAwaitCompletionService.Status status =
            EventAwaitCompletionService.terminateWithoutResume(
                player.getUUID(), callback.key(), token, kind,
                callback.script(), callback.store()
            );
        if (status != EventAwaitCompletionService.Status.STALE) {
            EventAwaitCallbackRegistry.forget(token);
        }
        if (!accepted(status)) {
            LOGGER.warn(
                "Map selection cancellation was not applied: player={}, reason={}, status={}",
                player.getGameProfile().getName(), reason, status
            );
        }
        return accepted(status) ? 1 : 0;
    }

    private static CallbackContext callback(ServerPlayer player, String token) {
        SavedEventSessionStore store = SavedEventSessionStore.get(player.getServer());
        Optional<EventSessionKey> key = EventAwaitCallbackRegistry.find(
            store, player.getUUID(), token
        );
        if (key.isEmpty()) return null;
        EventScript script = EventScriptRepository.instance()
            .find(key.orElseThrow().scriptId()).orElse(null);
        return script == null ? null : new CallbackContext(key.orElseThrow(), script, store);
    }

    private static boolean accepted(EventAwaitCompletionService.Status status) {
        return status == EventAwaitCompletionService.Status.RESUMED
            || status == EventAwaitCompletionService.Status.DUPLICATE;
    }

    private record CallbackContext(
        EventSessionKey key, EventScript script, SavedEventSessionStore store
    ) {}
}
