package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Keeps every player's air meter full, including while underwater. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID)
public final class InfiniteAirSupply {
    private InfiniteAirSupply() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player
            && player.getAirSupply() < player.getMaxAirSupply()) {
            player.setAirSupply(player.getMaxAirSupply());
        }
    }
}
