package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
import dev.buizz.cobbleventure.bootstrap.GatePokemonNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class GatePokemonClientLifecycle {
    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { GatePokemonNetwork.clearClient(); }
}
