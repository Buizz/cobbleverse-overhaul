package dev.buizz.cobbleventure.adventure.daycare.client;

import dev.buizz.cobbleventure.adventure.daycare.DaycareNetwork;
import net.minecraft.client.Minecraft;

/** Client-only entry point kept out of server daycare logic. */
public final class DaycareClient {
    private DaycareClient() {}

    public static void open(DaycareNetwork.ViewPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DaycareScreen screen && screen.apply(payload)) {
            return;
        }
        minecraft.setScreen(new DaycareScreen(payload));
    }
}
