package dev.buizz.cobbleventure.playermenu.client;

import java.util.List;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Shared description source for the bag and both shop modes. */
final class ItemDescriptions {
    private ItemDescriptions() {}

    static List<Component> forStack(ItemStack stack, Player player) {
        return ItemDescriptionResolver.resolve(
            stack.getTooltipLines(Item.TooltipContext.EMPTY, player, TooltipFlag.NORMAL),
            stack.getDescriptionId(), Component::getString, I18n::exists, Component::translatable,
            Component.translatable("screen.cobbleventure_player_menu.bag.no_description",
                BuiltInRegistries.ITEM.getKey(stack.getItem()))
        );
    }

}
