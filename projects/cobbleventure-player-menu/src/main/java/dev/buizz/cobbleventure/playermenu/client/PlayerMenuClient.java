package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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
        if (minecraft.player == null || minecraft.gameMode == null
            || inventoryIndex < 0 || inventoryIndex >= 36
            || minecraft.player.getInventory().getItem(inventoryIndex).isEmpty()) {
            return;
        }

        int selectedHotbarSlot = minecraft.player.getInventory().selected;
        if (inventoryIndex < 9) {
            minecraft.player.getInventory().selected = inventoryIndex;
        } else {
            minecraft.gameMode.handleInventoryMouseClick(
                minecraft.player.inventoryMenu.containerId,
                inventoryIndex,
                selectedHotbarSlot,
                ClickType.SWAP,
                minecraft.player
            );
        }

        minecraft.setScreen(null);
        InteractionResult result = InteractionResult.PASS;
        if (minecraft.hitResult instanceof EntityHitResult entityHit) {
            result = minecraft.gameMode.interactAt(
                minecraft.player,
                entityHit.getEntity(),
                entityHit,
                InteractionHand.MAIN_HAND
            );
            if (!result.consumesAction()) {
                result = minecraft.gameMode.interact(
                    minecraft.player,
                    entityHit.getEntity(),
                    InteractionHand.MAIN_HAND
                );
            }
        } else if (minecraft.hitResult instanceof BlockHitResult blockHit) {
            result = minecraft.gameMode.useItemOn(
                minecraft.player,
                InteractionHand.MAIN_HAND,
                blockHit
            );
        }
        if (!result.consumesAction()) {
            minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        }
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
