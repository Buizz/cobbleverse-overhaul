package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.network.chat.Component;

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
        return connected;
    }

    void open() {
        if (this == EQUIPMENT) {
            PlayerMenuClient.openVanillaInventory();
        }
    }
}
