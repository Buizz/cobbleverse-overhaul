package dev.buizz.cobbleventure.pokefinder.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
        if (!PinnedPokefinderHud.canControl(minecraft.player)) {
            minecraft.player.displayClientMessage(Component.translatable(
                "message.cobbleventure_pokefinder.hud.missing_pokenav"
            ), true);
            return;
        }

        boolean enabled = PinnedPokefinderHud.toggleEnabled();
        minecraft.player.displayClientMessage(Component.translatable(
            enabled
                ? "message.cobbleventure_pokefinder.hud.on"
                : "message.cobbleventure_pokefinder.hud.off"
        ), true);
    }
}
