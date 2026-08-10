package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;

public final class PlayerMenuClient {
    private static boolean allowNextInventoryScreen;

    private PlayerMenuClient() {}

    public static void register(IEventBus modBus) {
        PlayerMenuKeyMappings.register(modBus);
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
        BagNetwork.requestSnapshot();
        minecraft.setScreen(new BagScreen(minecraft.screen));
    }

    public static void useBagItem(boolean extended, int slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        BagNetwork.requestUse(extended, slot);
    }

    public static void moveBagItem(boolean sourceExtended, int sourceSlot,
                                   boolean targetExtended, int targetSlot, boolean singleItem) {
        BagNetwork.requestMove(sourceExtended, sourceSlot, targetExtended, targetSlot, singleItem);
    }

    public static void assignBagItemToHotbar(boolean extended, int slot, int hotbarIndex) {
        BagNetwork.requestShortcut(extended, slot, hotbarIndex);
    }

    public static void discardBagItem(boolean extended, int slot) {
        BagNetwork.requestDiscard(extended, slot);
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
