package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class PlayerMenuClient {
    private static boolean allowNextInventoryScreen;

    private PlayerMenuClient() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PlayerMenuClient::onScreenOpening);
    }

    public static void openVanillaInventory() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        allowNextInventoryScreen = true;
        minecraft.setScreen(new InventoryScreen(minecraft.player));
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Screen newScreen = event.getNewScreen();
        if (!(newScreen instanceof InventoryScreen)) {
            return;
        }

        if (allowNextInventoryScreen) {
            allowNextInventoryScreen = false;
            return;
        }

        if (event.getCurrentScreen() == null) {
            event.setNewScreen(new PlayerMenuScreen());
        }
    }
}
