package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import dev.buizz.cobbleventure.playermenu.PlayerOverviewNetwork;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Configurable direct shortcuts for every player-menu entry. */
final class PlayerMenuKeyMappings {
    private static final String CATEGORY = "key.categories.cobbleventure_player_menu";
    private static final Map<PlayerMenuEntry, KeyMapping> MAPPINGS = new EnumMap<>(PlayerMenuEntry.class);
    private static final List<KeyMapping> BAG_SHORTCUT_MAPPINGS = new ArrayList<>(10);
    private static final KeyMapping ROCK_CLIMB_TOGGLE = new KeyMapping(
        "key.cobbleventure_player_menu.rock_climb_toggle", GLFW.GLFW_KEY_H, CATEGORY
    );

    static {
        bind(PlayerMenuEntry.POKEMON, GLFW.GLFW_KEY_U);
        bind(PlayerMenuEntry.BAG, GLFW.GLFW_KEY_B);
        bind(PlayerMenuEntry.EQUIPMENT, GLFW.GLFW_KEY_G);
        bind(PlayerMenuEntry.PC, GLFW.GLFW_KEY_P);
        bind(PlayerMenuEntry.TRAINER_CARD, GLFW.GLFW_KEY_C);
        bind(PlayerMenuEntry.QUESTS, GLFW.GLFW_KEY_J);
        bind(PlayerMenuEntry.MAP, GLFW.GLFW_KEY_M);
        bind(PlayerMenuEntry.POKENAV, GLFW.GLFW_KEY_N);
        bind(PlayerMenuEntry.POKEDEX, GLFW.GLFW_KEY_K);
        for (int slot = 1; slot <= 10; slot++) {
            BAG_SHORTCUT_MAPPINGS.add(new KeyMapping(
                "key.cobbleventure_player_menu.bag_shortcut_" + slot, GLFW.GLFW_KEY_UNKNOWN, CATEGORY
            ));
        }
    }

    private PlayerMenuKeyMappings() {}

    static void register(IEventBus modBus) {
        modBus.addListener(PlayerMenuKeyMappings::registerMappings);
        NeoForge.EVENT_BUS.addListener(PlayerMenuKeyMappings::onClientTick);
    }

    static Component keyName(PlayerMenuEntry entry) {
        KeyMapping mapping = MAPPINGS.get(entry);
        return mapping == null || mapping.isUnbound()
            ? Component.translatable("key.cobbleventure_player_menu.unbound")
            : mapping.getTranslatedKeyMessage();
    }

    static PlayerMenuEntry matchingEntry(int keyCode, int scanCode) {
        for (PlayerMenuEntry entry : PlayerMenuEntry.values()) {
            KeyMapping mapping = MAPPINGS.get(entry);
            if (mapping != null && !mapping.isUnbound() && mapping.matches(keyCode, scanCode)) return entry;
        }
        return null;
    }

    private static void registerMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : MAPPINGS.values()) event.register(mapping);
        for (KeyMapping mapping : BAG_SHORTCUT_MAPPINGS) event.register(mapping);
        event.register(ROCK_CLIMB_TOGGLE);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) return;
        boolean rockClimbClicked = false;
        while (ROCK_CLIMB_TOGGLE.consumeClick()) rockClimbClicked = true;
        if (rockClimbClicked) {
            PlayerOverviewNetwork.requestToggle("rock_climb");
            return;
        }
        PlayerMenuEntry triggered = null;
        for (PlayerMenuEntry entry : PlayerMenuEntry.values()) {
            KeyMapping mapping = MAPPINGS.get(entry);
            boolean clicked = false;
            while (mapping != null && mapping.consumeClick()) clicked = true;
            if (clicked && triggered == null) triggered = entry;
        }
        int triggeredShortcut = -1;
        for (int slot = 0; slot < BAG_SHORTCUT_MAPPINGS.size(); slot++) {
            boolean clicked = false;
            while (BAG_SHORTCUT_MAPPINGS.get(slot).consumeClick()) clicked = true;
            if (clicked && triggeredShortcut < 0) triggeredShortcut = slot;
        }
        if (triggered != null) {
            PlayerMenuEntry.OpenResult result = triggered.open();
            if (result != PlayerMenuEntry.OpenResult.OPENED) {
                minecraft.player.displayClientMessage(resultMessage(triggered, result), true);
            }
        } else if (triggeredShortcut >= 0) {
            BagNetwork.requestUseShortcut(triggeredShortcut);
        }
    }

    private static Component resultMessage(PlayerMenuEntry entry, PlayerMenuEntry.OpenResult result) {
        return switch (result) {
            case OPENED -> Component.empty();
            case NO_POKEMON -> Component.translatable("screen.cobbleventure_player_menu.status.no_pokemon");
            case MISSING_POKEDEX -> Component.translatable("screen.cobbleventure_player_menu.status.missing_pokedex");
            case MISSING_POKENAV -> Component.translatable("screen.cobbleventure_player_menu.status.missing_pokenav");
            case ACTION_FAILED -> Component.translatable("screen.cobbleventure_player_menu.status.action_failed");
            case UNAVAILABLE -> Component.translatable(
                "screen.cobbleventure_player_menu.status.coming_soon", entry.title()
            );
        };
    }

    private static void bind(PlayerMenuEntry entry, int defaultKey) {
        MAPPINGS.put(entry, new KeyMapping(
            "key.cobbleventure_player_menu." + entry.id(), defaultKey, CATEGORY
        ));
    }
}
