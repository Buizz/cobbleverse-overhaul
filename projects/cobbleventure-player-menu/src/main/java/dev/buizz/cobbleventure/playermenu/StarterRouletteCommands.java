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
                .executes(context -> StarterRouletteNetwork.open(context.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> StarterRouletteNetwork.open(EntityArgument.getPlayer(context, "player"))))
        );
    }
}
