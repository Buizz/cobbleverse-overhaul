package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
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

    public static void openWorldMap() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new WorldMapScreen(minecraft.screen));
    }

    public static void openBag() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new BagScreen(minecraft.screen));
    }

    public static void useInventoryItem(int inventoryIndex) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
            || inventoryIndex < 0 || inventoryIndex >= 36
            || minecraft.player.getInventory().getItem(inventoryIndex).isEmpty()) {
            return;
        }
        BagNetwork.requestUse(inventoryIndex);
    }

    public static void assignInventoryItemToHotbar(int inventoryIndex, int hotbarIndex) {
        clickInventory(inventoryIndex, hotbarIndex, ClickType.SWAP);
    }

    public static void pickUpInventoryItem(int inventoryIndex, int mouseButton) {
        clickInventory(inventoryIndex, mouseButton, ClickType.PICKUP);
    }

    public static void discardInventoryItem(int inventoryIndex) {
        clickInventory(inventoryIndex, 1, ClickType.THROW);
    }

    private static void clickInventory(int inventoryIndex, int button, ClickType clickType) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null
            || inventoryIndex < 0 || inventoryIndex >= 36) {
            return;
        }
        int menuSlot = inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
        minecraft.gameMode.handleInventoryMouseClick(
            minecraft.player.inventoryMenu.containerId,
            menuSlot,
            button,
            clickType,
            minecraft.player
        );
    }

    public static void openTrainerCard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new TrainerCardScreen(minecraft.screen));
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
