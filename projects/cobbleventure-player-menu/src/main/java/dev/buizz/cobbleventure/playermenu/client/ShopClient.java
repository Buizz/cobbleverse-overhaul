package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.ShopNetwork;
import net.minecraft.client.Minecraft;

/** Client-only bridge for opening and refreshing the custom shop screen. */
public final class ShopClient {
    private ShopClient() {}

    public static void open(ShopNetwork.OpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new ShopScreen(payload)));
    }

    public static void update(ShopNetwork.TransactionResultPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof ShopScreen screen) screen.update(payload);
        });
    }
}
