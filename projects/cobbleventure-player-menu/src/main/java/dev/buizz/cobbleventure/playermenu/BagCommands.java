package dev.buizz.cobbleventure.playermenu;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Administrator command that uses the same insertion path as future rewards and shops. */
final class BagCommands {
    private BagCommands() {}

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventurebag")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("give")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                            .executes(context -> give(
                                context.getSource(), EntityArgument.getPlayers(context, "targets"),
                                ItemArgument.getItem(context, "item"), 1
                            ))
                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                .executes(context -> give(
                                    context.getSource(), EntityArgument.getPlayers(context, "targets"),
                                    ItemArgument.getItem(context, "item"),
                                    IntegerArgumentType.getInteger(context, "count")
                                ))))))
        );
    }

    private static int give(CommandSourceStack source, Collection<ServerPlayer> players,
                            ItemInput item, int count) throws CommandSyntaxException {
        int recipients = 0;
        int insertedTotal = 0;
        for (ServerPlayer player : players) {
            int remaining = count;
            int insertedForPlayer = 0;
            ItemStack sample = item.createItemStack(1, false);
            while (remaining > 0) {
                int batch = Math.min(remaining, sample.getMaxStackSize());
                BagApi.InsertResult result = BagApi.insert(player, item.createItemStack(batch, false), false);
                insertedForPlayer += result.inserted();
                remaining -= batch;
                if (!result.complete()) break;
            }
            if (insertedForPlayer > 0) recipients++;
            insertedTotal += insertedForPlayer;
        }

        int finalInsertedTotal = insertedTotal;
        source.sendSuccess(() -> Component.translatable(
            "commands.cobbleventure_player_menu.bag.give", finalInsertedTotal, players.size()
        ), true);
        return recipients;
    }
}
