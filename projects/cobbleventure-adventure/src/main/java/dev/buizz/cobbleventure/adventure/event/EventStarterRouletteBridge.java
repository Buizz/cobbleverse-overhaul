package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/** Internal bridge between CVES await sessions and Player Menu's starter service. */
public final class EventStarterRouletteBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TIMEOUT_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_RESUME_STEPS = 10_000;

    private EventStarterRouletteBridge() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventStarterRouletteBridge::registerCommands);
    }

    public static EventStarterRouletteGateway gateway(ServerPlayer player) {
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException(
                    "starter roulette 요청의 player와 gateway player가 다릅니다."
                );
            }
            String token = UUID.randomUUID().toString();
            String command = starterRouletteSessionCommand(token);
            AtomicBoolean completed = new AtomicBoolean();
            AtomicBoolean accepted = new AtomicBoolean();
            player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack()
                    .withPermission(4)
                    .withSuppressedOutput()
                    .withCallback((success, result) -> {
                        completed.set(true);
                        accepted.set(success && result > 0);
                    }),
                command
            );
            if (completed.get() && !accepted.get()) {
                throw new EventRuntimeException(
                    "Player Menu가 starter roulette 요청을 거부했습니다."
                );
            }
            EventAwaitCallbackRegistry.register(token, request.sessionKey());
            LOGGER.info(
                "CVES starter roulette queued: player={}, token={}",
                player.getGameProfile().getName(), token
            );
            return new EventStarterRouletteGateway.OpenResult(
                token, System.currentTimeMillis() + TIMEOUT_MILLIS
            );
        };
    }

    static String starterRouletteSessionCommand(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("starter roulette token이 필요합니다.");
        }
        return "cobbleventure_starter_roulette_session "
            + StringArgumentType.escapeIfRequired(token);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event_starter_result")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("token", StringArgumentType.word())
                    .then(Commands.argument("species", ResourceLocationArgument.id())
                        .executes(context -> complete(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "token"),
                            ResourceLocationArgument.getId(context, "species")
                        ))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event_starter_cancel")
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

    private static int complete(ServerPlayer player, String token, ResourceLocation species) {
        LOGGER.info(
            "Starter roulette callback received: player={}, token={}, species={}",
            player.getGameProfile().getName(), token, species
        );
        SavedEventSessionStore store = SavedEventSessionStore.get(player.getServer());
        Optional<EventSessionKey> key = EventAwaitCallbackRegistry.find(
            store, player.getUUID(), token
        );
        if (key.isEmpty()) {
            LOGGER.error(
                "Starter roulette callback session was not found: player={}, token={}",
                player.getGameProfile().getName(), token
            );
            return 0;
        }
        EventScript script = EventScriptRepository.instance()
            .find(key.orElseThrow().scriptId()).orElse(null);
        if (script == null) {
            LOGGER.error(
                "Starter roulette callback script was not found: token={}, script={}",
                token, key.orElseThrow().scriptId()
            );
            return 0;
        }

        JsonObject result = new JsonObject();
        result.addProperty("species_id", species.toString());
        result.add("form", JsonNull.INSTANCE);
        result.addProperty("level", 5);
        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                player.getUUID(),
                key.orElseThrow(),
                token,
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, result
                ),
                script,
                new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
                EventDialogueNetwork.serverAdapter(player),
                store,
                MAX_RESUME_STEPS
            );
        LOGGER.info(
            "Starter roulette await completion: player={}, token={}, status={}, runResult={}",
            player.getGameProfile().getName(), token, outcome.status(), outcome.runResult()
        );
        if (outcome.status() != EventAwaitCompletionService.Status.STALE) {
            EventAwaitCallbackRegistry.forget(token);
        }
        return outcome.status() == EventAwaitCompletionService.Status.RESUMED
            || outcome.status() == EventAwaitCompletionService.Status.DUPLICATE ? 1 : 0;
    }

    private static int cancel(ServerPlayer player, String token, String reason) {
        SavedEventSessionStore store = SavedEventSessionStore.get(player.getServer());
        Optional<EventSessionKey> key = EventAwaitCallbackRegistry.find(
            store, player.getUUID(), token
        );
        if (key.isEmpty()) return 0;
        EventScript script = EventScriptRepository.instance()
            .find(key.orElseThrow().scriptId()).orElse(null);
        if (script == null) return 0;
        EventSession.CompletionKind kind = reason.equals("client_cancelled")
            ? EventSession.CompletionKind.CANCELLED
            : EventSession.CompletionKind.FAILED;
        EventAwaitCompletionService.Status status =
            EventAwaitCompletionService.terminateWithoutResume(
                player.getUUID(), key.orElseThrow(), token, kind, script, store
            );
        if (status != EventAwaitCompletionService.Status.STALE) {
            EventAwaitCallbackRegistry.forget(token);
        }
        if (status != EventAwaitCompletionService.Status.RESUMED
            && status != EventAwaitCompletionService.Status.DUPLICATE) {
            LOGGER.warn(
                "Starter roulette cancellation was not applied: player={}, reason={}, status={}",
                player.getGameProfile().getName(), reason, status
            );
        }
        return status == EventAwaitCompletionService.Status.RESUMED
            || status == EventAwaitCompletionService.Status.DUPLICATE ? 1 : 0;
    }
}
