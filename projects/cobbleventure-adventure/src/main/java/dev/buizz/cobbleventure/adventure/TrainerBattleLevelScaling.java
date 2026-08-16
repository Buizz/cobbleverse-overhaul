package dev.buizz.cobbleventure.adventure;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Resolves an authored trainer team against the level scaling at its map position. */
final class TrainerBattleLevelScaling {
    private TrainerBattleLevelScaling() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(TrainerBattleLevelScaling::registerCommands);
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
        ResourceLocation scaledTrainerId = scaledTrainerId(trainerId, resolvedLevel);
        String scaledCommand = replaceTrainerId(battleCommand, trainerId, scaledTrainerId);
        if (scaledCommand == null) return 0;

        String introCommand = "cobbleventure_battle_intro "
            + player.getUUID() + " " + opponent.getUUID() + " " + battleId + " " + scaledCommand;
        source.getServer().getCommands().performPrefixedCommand(source, introCommand);
        return 1;
    }

    static int resolveLevel(Integer regionalLevel, int fallbackLevel, int offset) {
        int base = regionalLevel == null ? fallbackLevel : regionalLevel;
        return Math.max(1, Math.min(100, base + offset));
    }

    static ResourceLocation scaledTrainerId(ResourceLocation trainerId, int level) {
        return ResourceLocation.fromNamespaceAndPath(
            trainerId.getNamespace(), trainerId.getPath() + "__level_" + level
        );
    }

    static String replaceTrainerId(
        String battleCommand, ResourceLocation trainerId, ResourceLocation scaledTrainerId
    ) {
        String normalized = battleCommand.startsWith("/")
            ? battleCommand.substring(1)
            : battleCommand;
        if (!normalized.startsWith("tbcs battle ")) return null;
        String marker = " as " + trainerId;
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) return null;
        return normalized.substring(0, markerIndex)
            + " as " + scaledTrainerId
            + normalized.substring(markerIndex + marker.length());
    }
}
