package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.cobblemon.mod.common.client.CobblemonClient;
import dev.buizz.cobbleventure.playermenu.BagNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.components.toasts.Toast;
import com.cobblemon.mod.common.client.gui.startselection.StarterSelectionScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;

public final class PlayerMenuClient {
    private static final String IRIS_RELOAD_KEY = "iris.keybind.reload";
    private static boolean allowNextInventoryScreen;
    private static boolean irisReloadKeyChecked;

    private PlayerMenuClient() {}

    public static void register(IEventBus modBus) {
        PlayerMenuKeyMappings.register(modBus);
        NeoForge.EVENT_BUS.addListener(PlayerMenuClient::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(PlayerMenuClient::suppressCobblemonStarterPrompt);
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

    public static void assignBagItemToShortcut(boolean extended, int slot, int shortcutIndex) {
        BagNetwork.requestShortcut(extended, slot, shortcutIndex);
    }

    public static void discardBagItem(boolean extended, int slot, int quantity) {
        BagNetwork.requestDiscard(extended, slot, quantity);
    }

    public static void dropBagItem(boolean extended, int slot, int quantity) {
        BagNetwork.requestDrop(extended, slot, quantity);
    }

    public static void giveBagItemToPokemon(boolean extended, int slot, int partySlot) {
        BagNetwork.requestGiveToPokemon(extended, slot, partySlot);
    }

    public static void openPokenav() {
        BagNetwork.requestUsePokenav();
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
        if (newScreen instanceof StarterSelectionScreen) {
            event.setNewScreen(event.getCurrentScreen());
            return;
        }
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

    private static void suppressCobblemonStarterPrompt(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        disableLegacyIrisReloadKey(minecraft);
        if (minecraft.player == null) return;
        CobblemonClient.INSTANCE.setCheckedStarterScreen(true);
        CobblemonClient.INSTANCE.getOverlay().getStarterToast()
            .setNextVisibility$common(Toast.Visibility.HIDE);
    }

    /** Migrates existing pack instances whose Iris reload key predates options.txt overrides. */
    private static void disableLegacyIrisReloadKey(Minecraft minecraft) {
        if (irisReloadKeyChecked || minecraft.options == null) {
            return;
        }
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (!IRIS_RELOAD_KEY.equals(mapping.getName())) {
                continue;
            }
            irisReloadKeyChecked = true;
            if (!"key.keyboard.r".equals(mapping.saveString())) {
                return;
            }
            mapping.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            minecraft.options.save();
            return;
        }
    }
}
