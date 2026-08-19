package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
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
            String command = "cobbleventure_starter_roulette_session "
                + player.getUUID() + " " + token;
            player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                command
            );
            return new EventStarterRouletteGateway.OpenResult(
                token, System.currentTimeMillis() + TIMEOUT_MILLIS
            );
        };
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("starter_result")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("token", StringArgumentType.word())
                            .then(Commands.argument("species", StringArgumentType.string())
                                .executes(context -> complete(
                                    EntityArgument.getPlayer(context, "player"),
                                    StringArgumentType.getString(context, "token"),
                                    StringArgumentType.getString(context, "species")
                                ))))))
                .then(Commands.literal("starter_cancel")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("token", StringArgumentType.word())
                            .then(Commands.argument("reason", StringArgumentType.word())
                                .executes(context -> cancel(
                                    EntityArgument.getPlayer(context, "player"),
                                    StringArgumentType.getString(context, "token"),
                                    StringArgumentType.getString(context, "reason")
                                ))))))
        );
    }

    private static int complete(ServerPlayer player, String token, String speciesId) {
        ResourceLocation species = ResourceLocation.tryParse(speciesId);
        if (species == null) {
            LOGGER.warn("Invalid starter species callback: {}", speciesId);
            return 0;
        }
        SavedEventSessionStore store = SavedEventSessionStore.get(player.getServer());
        Optional<EventSessionKey> key = EventAwaitSessionLocator.find(
            store, player.getUUID(), token
        );
        if (key.isEmpty()) return 0;
        EventScript script = EventScriptRepository.instance()
            .find(key.orElseThrow().scriptId()).orElse(null);
        if (script == null) return 0;

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
        return outcome.status() == EventAwaitCompletionService.Status.RESUMED
            || outcome.status() == EventAwaitCompletionService.Status.DUPLICATE ? 1 : 0;
    }

    private static int cancel(ServerPlayer player, String token, String reason) {
        SavedEventSessionStore store = SavedEventSessionStore.get(player.getServer());
        Optional<EventSessionKey> key = EventAwaitSessionLocator.find(
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
