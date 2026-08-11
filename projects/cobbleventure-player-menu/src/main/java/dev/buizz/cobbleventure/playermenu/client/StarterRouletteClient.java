package dev.buizz.cobbleventure.playermenu.client;

import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Client-only bridge used by starter roulette payload handlers. */
public final class StarterRouletteClient {
    private StarterRouletteClient() {}

    public static void open(UUID token, List<String> species) {
        Minecraft.getInstance().setScreen(new StarterRouletteScreen(token, species));
    }

    public static void result(boolean success, String translationKey, String species) {
        if (Minecraft.getInstance().screen instanceof StarterRouletteScreen screen) {
            screen.handleResult(success, translationKey, species);
        }
    }
}
