package dev.buizz.cobbleventure.playermenu;

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
                    )))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_starter_state")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> StarterRouletteNetwork.syncState(
                        EntityArgument.getPlayer(context, "player")
                    )))
        );
    }
}
