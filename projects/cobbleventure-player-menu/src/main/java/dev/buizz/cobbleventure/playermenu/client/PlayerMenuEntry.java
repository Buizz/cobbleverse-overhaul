package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

enum PlayerMenuEntry {
    POKEMON("pokemon", false),
    BAG("bag", false),
    EQUIPMENT("equipment", true),
    PC("pc", false),
    TRAINER_CARD("trainer_card", false),
    QUESTS("quests", false),
    MAP("map", false),
    POKEDEX("pokedex", false);

    private final String id;
    private final boolean connected;

    PlayerMenuEntry(String id, boolean connected) {
        this.id = id;
        this.connected = connected;
    }

    Component title() {
        return Component.translatable("screen.cobbleventure_player_menu.entry." + id);
    }

    Component description() {
        return Component.translatable("screen.cobbleventure_player_menu.entry." + id + ".description");
    }

    boolean connected() {
        return connected || (isCobblemonEntry() && ModList.get().isLoaded("cobblemon"));
    }

    OpenResult open() {
        if (this == EQUIPMENT) {
            PlayerMenuClient.openVanillaInventory();
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
        return OpenResult.UNAVAILABLE;
    }

    private boolean isCobblemonEntry() {
        return this == POKEMON || this == PC || this == POKEDEX;
    }

    enum OpenResult {
        OPENED,
        NO_POKEMON,
        MISSING_POKEDEX,
        ACTION_FAILED,
        UNAVAILABLE
    }
}
