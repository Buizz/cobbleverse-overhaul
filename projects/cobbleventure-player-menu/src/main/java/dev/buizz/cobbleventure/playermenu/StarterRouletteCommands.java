package dev.buizz.cobbleventure.playermenu;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Temporary entry point for testing the starter roulette. */
final class StarterRouletteCommands {
    private StarterRouletteCommands() {}

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("starterroulette")
                .executes(context -> StarterRouletteNetwork.queueOpen(context.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> StarterRouletteNetwork.queueOpen(EntityArgument.getPlayer(context, "player"))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_starter_roulette")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> StarterRouletteNetwork.queueOpen(
                        EntityArgument.getPlayer(context, "player")
                    ))
                    .then(Commands.argument("npc", EntityArgument.entity())
                        .then(Commands.argument("dialogue", StringArgumentType.word())
                            .executes(context -> StarterRouletteNetwork.queueOpen(
                                EntityArgument.getPlayer(context, "player"),
                                EntityArgument.getEntity(context, "npc"),
                                StringArgumentType.getString(context, "dialogue")
                            )))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_starter_state")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> StarterRouletteNetwork.syncState(
                        EntityArgument.getPlayer(context, "player")
                    )))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_starter_roulette_session")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("callback_token", StringArgumentType.word())
                    .executes(context -> StarterRouletteNetwork.queueEventOpen(
                        context.getSource().getPlayerOrException(),
                        StringArgumentType.getString(context, "callback_token")
                    )))
        );
    }
}
