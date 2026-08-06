package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.item.PokedexItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

final class CobblemonPokedexIntegration {
    private CobblemonPokedexIntegration() {}

    static boolean openOwnedPokedex() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }

        ItemStack pokedexStack = findPokedex(player.getInventory());
        if (!(pokedexStack.getItem() instanceof PokedexItem pokedex)) {
            return false;
        }

        CobblemonClient.INSTANCE
            .getPokedexUsageContext()
            .openPokedexGUI(pokedex.getType(), null);
        return true;
    }

    private static ItemStack findPokedex(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof PokedexItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
