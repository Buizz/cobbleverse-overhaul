package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.buizz.cobbleventure.playermenu.BagNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import com.cobblemon.mod.common.client.gui.startselection.StarterSelectionScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

public final class PlayerMenuClient {
    private static final String IRIS_RELOAD_KEY = "iris.keybind.reload";
    private static final String IRIS_SHADER_PACK_SELECTION_KEY = "iris.keybind.shaderPackSelection";
    private static final String COBBLEMON_SUMMARY_KEY = "key.cobblemon.summary";
    private static final String COBBLEMON_HIDE_PARTY_KEY = "key.cobblemon.hideparty";
    private static boolean irisReloadKeyChecked;
    private static boolean irisShaderPackSelectionKeyChecked;
    private static boolean cobblemonSummaryKeyChecked;
    private static boolean cobblemonHidePartyKeyChecked;

    private PlayerMenuClient() {}

    public static void register(IEventBus modBus) {
        PlayerMenuKeyMappings.register(modBus);
        NeoForge.EVENT_BUS.addListener(PlayerMenuClient::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(PlayerMenuClient::onClientTick);
    }

    public static void openWorldMap() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new WorldMapScreen(minecraft.screen));
    }

    public static void openWorldMapSelection(String token) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || token == null || token.isBlank()) return;
        minecraft.setScreen(new WorldMapScreen(minecraft.screen, token));
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

    /** Runs the client half of an item's use method after the server validates bag ownership. */
    public static void previewBagItemUse(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || stack.isEmpty()) return;
        ItemStack original = minecraft.player.getMainHandItem();
        minecraft.player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        try {
            stack.getItem().use(minecraft.level, minecraft.player, InteractionHand.MAIN_HAND);
        } finally {
            if (minecraft.player.isUsingItem()
                && minecraft.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                minecraft.player.stopUsingItem();
            }
            minecraft.player.setItemInHand(InteractionHand.MAIN_HAND, original);
        }
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

    public static void useBagItemOnPokemon(boolean extended, int slot, int partySlot) {
        BagNetwork.requestUseOnPokemon(extended, slot, partySlot);
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

    public static void openQuestLog() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.setScreen(new QuestLogScreen(minecraft.screen));
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

        if (event.getCurrentScreen() == null) {
            event.setNewScreen(new PlayerMenuScreen());
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        disableLegacyIrisReloadKey(minecraft);
        disableLegacyIrisShaderPackSelectionKey(minecraft);
        disableLegacyCobblemonSummaryKey(minecraft);
        disableLegacyCobblemonHidePartyKey(minecraft);
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

    /** Frees O for the Pokefinder HUD while preserving a custom Iris binding. */
    private static void disableLegacyIrisShaderPackSelectionKey(Minecraft minecraft) {
        if (irisShaderPackSelectionKeyChecked || minecraft.options == null) {
            return;
        }
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (!IRIS_SHADER_PACK_SELECTION_KEY.equals(mapping.getName())) {
                continue;
            }
            irisShaderPackSelectionKeyChecked = true;
            if (!"key.keyboard.o".equals(mapping.saveString())) {
                return;
            }
            mapping.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            minecraft.options.save();
            return;
        }
    }

    /** Frees M for the world map while preserving any custom Cobblemon summary key. */
    private static void disableLegacyCobblemonSummaryKey(Minecraft minecraft) {
        if (cobblemonSummaryKeyChecked || minecraft.options == null) {
            return;
        }
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (!COBBLEMON_SUMMARY_KEY.equals(mapping.getName())) {
                continue;
            }
            cobblemonSummaryKeyChecked = true;
            if (!"key.keyboard.m".equals(mapping.saveString())) {
                return;
            }
            mapping.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            minecraft.options.save();
            return;
        }
    }

    /** Moves Cobblemon's default O party toggle to an unused key for the Pokefinder HUD. */
    private static void disableLegacyCobblemonHidePartyKey(Minecraft minecraft) {
        if (cobblemonHidePartyKeyChecked || minecraft.options == null) {
            return;
        }
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (!COBBLEMON_HIDE_PARTY_KEY.equals(mapping.getName())) {
                continue;
            }
            cobblemonHidePartyKeyChecked = true;
            if (!"key.keyboard.o".equals(mapping.saveString())) {
                return;
            }
            mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_BRACKET));
            KeyMapping.resetMapping();
            minecraft.options.save();
            return;
        }
    }
}
