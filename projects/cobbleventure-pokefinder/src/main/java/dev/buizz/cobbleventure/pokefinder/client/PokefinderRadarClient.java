package dev.buizz.cobbleventure.pokefinder.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client entry point for the CobbleNav Pokefinder compatibility layer. */
public final class PokefinderRadarClient {
    private static final String CATEGORY = "key.categories.cobbleventure_pokefinder";
    private static final KeyMapping TOGGLE_HUD = new KeyMapping(
        "key.cobbleventure_pokefinder.toggle_hud", GLFW.GLFW_KEY_O, CATEGORY
    );

    private PokefinderRadarClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PokefinderRadarClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(PokefinderRadarClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(PokefinderRadarClient::onLogin);
        NeoForge.EVENT_BUS.addListener(PokefinderRadarClient::onLogout);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HUD);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        boolean clicked = false;
        while (TOGGLE_HUD.consumeClick()) clicked = true;
        if (!clicked) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) return;
        cycleHud();
    }

    /** Cycles off -> left -> right -> off from either the key or PokéNav app button. */
    public static void cycleHud() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (!PinnedPokefinderHud.pokenavAvailable()) {
            minecraft.player.displayClientMessage(Component.translatable(
                "message.cobbleventure_pokefinder.hud.missing_pokenav"
            ), true);
            return;
        }
        PinnedPokefinderHud.cycle();
        String state = !PinnedPokefinderHud.enabled()
            ? "off"
            : PinnedPokefinderHud.position() == PokefinderHudPosition.LEFT ? "left" : "right";
        minecraft.player.displayClientMessage(Component.translatable(
            "message.cobbleventure_pokefinder.hud." + state
        ), true);
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PinnedPokefinderHud.resetSession();
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        PinnedPokefinderHud.resetSession();
    }
}
