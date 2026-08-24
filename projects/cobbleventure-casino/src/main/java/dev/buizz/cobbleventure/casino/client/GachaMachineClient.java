package dev.buizz.cobbleventure.casino.client;

import dev.buizz.cobbleventure.casino.GachaMachineNetwork;
import net.minecraft.client.Minecraft;

/** Client-only bridge for the server-owned gacha session. */
public final class GachaMachineClient {
    private GachaMachineClient() {}

    public static void open(GachaMachineNetwork.OpenPayload payload) {
        Minecraft.getInstance().setScreen(new GachaMachineScreen(payload));
    }

    public static void result(GachaMachineNetwork.ResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof GachaMachineScreen screen) {
            screen.handleResult(payload);
        }
    }
}
