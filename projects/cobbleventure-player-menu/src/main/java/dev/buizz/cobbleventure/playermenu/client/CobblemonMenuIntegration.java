package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.keybind.keybinds.SummaryBinding;
import com.cobblemon.mod.common.item.PokedexItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

final class CobblemonMenuIntegration {
    private CobblemonMenuIntegration() {}

    static boolean openPartySummary() {
        int selection = CobblemonClient.INSTANCE.getStorage().getSelectedSlot();
        Pokemon selectedPokemon = CobblemonClient.INSTANCE.getStorage().getParty().get(selection);
        if (selectedPokemon == null) {
            selectedPokemon = firstPartyPokemon();
        }
        if (selectedPokemon == null) {
            return false;
        }

        CobblemonClient.INSTANCE.getStorage().switchToPokemon(selectedPokemon.getUuid());
        SummaryBinding.INSTANCE.onPress();
        return true;
    }

    static boolean requestRemotePc() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return false;
        }

        minecraft.setScreen(null);
        connection.sendCommand("pc");
        return true;
    }

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

    private static Pokemon firstPartyPokemon() {
        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = CobblemonClient.INSTANCE.getStorage().getParty().get(slot);
            if (pokemon != null) {
                return pokemon;
            }
        }
        return null;
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
