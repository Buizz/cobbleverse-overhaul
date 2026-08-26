package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import dev.buizz.cobbleventure.playermenu.ProgressionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

enum PlayerMenuEntry {
    POKEMON("pokemon", false, "poke_ball"),
    BAG("bag", true, "relic_coin_pouch"),
    PC("pc", false, "pc"),
    TRAINER_CARD("trainer_card", true, "red_card"),
    QUESTS("quests", false, "scroll_of_darkness"),
    MAP("map", true, null),
    POKENAV("pokenav", false, null),
    POKEDEX("pokedex", false, "pokedex_red");

    private final String id;
    private final boolean connected;
    private final String cobblemonIconId;

    PlayerMenuEntry(String id, boolean connected, String cobblemonIconId) {
        this.id = id;
        this.connected = connected;
        this.cobblemonIconId = cobblemonIconId;
    }

    Component title() {
        return Component.translatable("screen.cobbleventure_player_menu.entry." + id);
    }

    Component description() {
        return Component.translatable("screen.cobbleventure_player_menu.entry." + id + ".description");
    }

    String id() {
        return id;
    }

    ItemStack icon() {
        ItemStack fallback = new ItemStack(switch (this) {
            case POKEMON -> Items.EGG;
            case BAG -> Items.BUNDLE;
            case PC -> Items.COMPARATOR;
            case TRAINER_CARD -> Items.NAME_TAG;
            case QUESTS -> Items.WRITABLE_BOOK;
            case MAP -> Items.FILLED_MAP;
            case POKENAV -> Items.COMPASS;
            case POKEDEX -> Items.KNOWLEDGE_BOOK;
        });
        if (this == POKENAV) {
            if (!ModList.get().isLoaded("cobblenav")) return fallback;
            Item iconItem = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("cobblenav", "pokenav_item_base")
            ).orElse(Items.AIR);
            return iconItem == Items.AIR ? fallback : new ItemStack(iconItem);
        }
        if (cobblemonIconId == null || !ModList.get().isLoaded("cobblemon")) {
            return fallback;
        }

        ResourceLocation iconId = ResourceLocation.fromNamespaceAndPath("cobblemon", cobblemonIconId);
        Item iconItem = BuiltInRegistries.ITEM.getOptional(iconId).orElse(Items.AIR);
        return iconItem == Items.AIR ? fallback : new ItemStack(iconItem);
    }

    boolean connected() {
        if (this == POKENAV) return ModList.get().isLoaded("cobblenav");
        return connected || (isCobblemonEntry() && ModList.get().isLoaded("cobblemon"));
    }

    boolean unlocked() {
        ProgressionNetwork.ClientSnapshot progress = ProgressionNetwork.clientSnapshot();
        return switch (this) {
            case MAP -> progress.map();
            case PC -> progress.pc();
            default -> true;
        };
    }

    boolean enabled() {
        if (!unlocked()) return false;
        return switch (this) {
            case POKENAV -> connected() && hasPokenav();
            case POKEDEX -> connected() && CobblemonMenuIntegration.hasOwnedPokedex();
            default -> true;
        };
    }

    OpenResult open() {
        if (!unlocked()) return OpenResult.LOCKED;
        if (this == POKENAV && !hasPokenav()) return OpenResult.MISSING_POKENAV;
        if (this == BAG) {
            PlayerMenuClient.openBag();
            return OpenResult.OPENED;
        }
        if (this == MAP) {
            PlayerMenuClient.openWorldMap();
            return OpenResult.OPENED;
        }
        if (this == TRAINER_CARD) {
            PlayerMenuClient.openTrainerCard();
            return OpenResult.OPENED;
        }
        if (this == POKEMON && connected()) {
            return CobblemonMenuIntegration.openPartySummary()
                ? OpenResult.OPENED
                : OpenResult.NO_POKEMON;
        }
        if (this == PC && connected()) {
            return CobblemonMenuIntegration.requestRemotePc()
                ? OpenResult.OPENED
                : OpenResult.ACTION_FAILED;
        }
        if (this == POKEDEX && connected()) {
            return CobblemonMenuIntegration.openOwnedPokedex()
                ? OpenResult.OPENED
                : OpenResult.MISSING_POKEDEX;
        }
        if (this == POKENAV && connected()) {
            PlayerMenuClient.openPokenav();
            return OpenResult.OPENED;
        }
        return OpenResult.UNAVAILABLE;
    }

    private boolean isCobblemonEntry() {
        return this == POKEMON || this == PC || this == POKEDEX;
    }

    private static boolean hasPokenav() {
        if (Minecraft.getInstance().player == null) return false;
        Inventory inventory = Minecraft.getInstance().player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (BagNetwork.isPokenavItem(inventory.getItem(slot))) return true;
        }
        for (ItemStack stack : BagNetwork.clientSnapshot().slots()) {
            if (BagNetwork.isPokenavItem(stack)) return true;
        }
        return false;
    }

    enum OpenResult {
        OPENED,
        NO_POKEMON,
        MISSING_POKEDEX,
        MISSING_POKENAV,
        ACTION_FAILED,
        LOCKED,
        UNAVAILABLE
    }
}
