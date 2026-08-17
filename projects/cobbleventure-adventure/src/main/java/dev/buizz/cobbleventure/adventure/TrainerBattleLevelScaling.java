package dev.buizz.cobbleventure.adventure;

import com.gitlab.srcmc.rctapi.api.RCTApi;
import com.gitlab.srcmc.rctapi.api.battle.BattleState;
import com.gitlab.srcmc.rctapi.api.events.Events;
import com.gitlab.srcmc.rctapi.api.trainer.Trainer;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Creates a per-battle trainer copy and resolves its level against the current map. */
final class TrainerBattleLevelScaling {
    private static final String TBCS_REGISTRY = "tbcs";
    private static final long PENDING_RETENTION_TICKS = 20L * 20L;
    private static final Map<String, PendingTrainer> PENDING = new HashMap<>();
    private static boolean registered;
    private static boolean battleListenerRegistered;

    private TrainerBattleLevelScaling() {}

    static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(TrainerBattleLevelScaling::registerCommands);
        NeoForge.EVENT_BUS.addListener(TrainerBattleLevelScaling::onServerTick);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_scaled_trainer_battle")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("opponent", EntityArgument.entity())
                        .then(Commands.argument("battle_id", ResourceLocationArgument.id())
                            .then(Commands.argument("level_offset", IntegerArgumentType.integer(-99, 99))
                                .then(Commands.argument("fallback_level", IntegerArgumentType.integer(1, 100))
                                    .then(Commands.argument("trainer_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("battle_command", StringArgumentType.greedyString())
                                            .executes(context -> start(
                                                context.getSource(),
                                                EntityArgument.getPlayer(context, "player"),
                                                EntityArgument.getEntity(context, "opponent"),
                                                ResourceLocationArgument.getId(context, "battle_id"),
                                                IntegerArgumentType.getInteger(context, "level_offset"),
                                                IntegerArgumentType.getInteger(context, "fallback_level"),
                                                ResourceLocationArgument.getId(context, "trainer_id"),
                                                StringArgumentType.getString(context, "battle_command")
                                            )))))))))
        );
    }

    private static int start(
        CommandSourceStack source,
        ServerPlayer player,
        Entity opponent,
        ResourceLocation battleId,
        int levelOffset,
        int fallbackLevel,
        ResourceLocation trainerId,
        String battleCommand
    ) {
        Integer regionalLevel = CobbleventureAdventure.averageWildSpawnLevel(
            player.serverLevel(), player.getX(), player.getZ()
        );
        int resolvedLevel = resolveLevel(regionalLevel, fallbackLevel, levelOffset);
        RCTApi tbcs = RCTApi.getInstance(TBCS_REGISTRY);
        if (tbcs == null) {
            source.sendFailure(Component.literal("TBCS trainer registry is not available."));
            return 0;
        }

        TrainerRegistry registry = tbcs.getTrainerRegistry();
        TrainerNPC authoredTrainer = registry.getById(trainerId.toString(), TrainerNPC.class);
        if (authoredTrainer == null) {
            source.sendFailure(Component.literal("No such trainer registered '" + trainerId + "'."));
            return 0;
        }

        TrainerNPC scaledTrainer = new TrainerNPC(authoredTrainer);
        for (var pokemon : scaledTrainer.getTeam()) {
            pokemon.setLevel(resolvedLevel);
        }

        ResourceLocation runtimeTrainerId = runtimeTrainerId();
        String scaledCommand = replaceTrainerId(battleCommand, trainerId, runtimeTrainerId);
        if (scaledCommand == null) {
            source.sendFailure(Component.literal("Scaled trainer battle requires a TBCS battle command."));
            return 0;
        }

        ensureBattleListener(tbcs);
        String runtimeId = runtimeTrainerId.toString();
        registry.registerNPC(runtimeId, scaledTrainer);
        PENDING.put(runtimeId, new PendingTrainer(
            scaledTrainer,
            source.getServer().overworld().getGameTime() + PENDING_RETENTION_TICKS
        ));

        String introCommand = "cobbleventure_battle_intro "
            + player.getGameProfile().getName() + " " + opponent.getUUID() + " "
            + battleId + " " + scaledCommand;
        source.getServer().getCommands().performPrefixedCommand(source, introCommand);
        return 1;
    }

    private static void ensureBattleListener(RCTApi tbcs) {
        if (battleListenerRegistered) return;
        battleListenerRegistered = true;
        tbcs.getEventContext().register(Events.BATTLE_STARTED, event -> onBattleStarted(event.getValue()));
    }

    private static void onBattleStarted(BattleState battle) {
        Stream<Trainer> participants = Stream.concat(
            battle.getParticipants1().stream(), battle.getParticipants2().stream()
        );
        var startedTrainers = participants.toList();
        PENDING.entrySet().removeIf(entry -> {
            if (!startedTrainers.contains(entry.getValue().trainer())) return false;
            unregister(entry.getKey());
            return true;
        });
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        long gameTime = event.getServer().overworld().getGameTime();
        PENDING.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt() >= gameTime) return false;
            unregister(entry.getKey());
            return true;
        });
    }

    private static void unregister(String trainerId) {
        RCTApi tbcs = RCTApi.getInstance(TBCS_REGISTRY);
        if (tbcs != null) tbcs.getTrainerRegistry().unregisterById(trainerId);
    }

    static int resolveLevel(Integer regionalLevel, int fallbackLevel, int offset) {
        int base = regionalLevel == null ? fallbackLevel : regionalLevel;
        return Math.max(1, Math.min(100, base + offset));
    }

    static ResourceLocation runtimeTrainerId() {
        return ResourceLocation.fromNamespaceAndPath(
            CobbleventureAdventure.MOD_ID, "scaled/" + UUID.randomUUID()
        );
    }

    static String replaceTrainerId(
        String battleCommand, ResourceLocation trainerId, ResourceLocation runtimeTrainerId
    ) {
        String normalized = battleCommand.startsWith("/")
            ? battleCommand.substring(1)
            : battleCommand;
        if (!normalized.startsWith("tbcs battle ")) return null;
        String marker = " as " + trainerId;
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) return null;
        return normalized.substring(0, markerIndex)
            + " as " + runtimeTrainerId
            + normalized.substring(markerIndex + marker.length());
    }

    private record PendingTrainer(TrainerNPC trainer, long expiresAt) {}
}
