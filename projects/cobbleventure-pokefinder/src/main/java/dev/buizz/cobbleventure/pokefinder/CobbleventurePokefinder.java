package dev.buizz.cobbleventure.pokefinder;

import dev.buizz.cobbleventure.pokefinder.client.PokefinderRadarClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CobbleventurePokefinder.MOD_ID)
public final class CobbleventurePokefinder {
    public static final String MOD_ID = "cobbleventure_pokefinder";

    public CobbleventurePokefinder(IEventBus modBus) {
        RadarMarkerNetwork.register(modBus);
        if (FMLEnvironment.dist.isClient()) {
            PokefinderRadarClient.register(modBus);
        }
    }
}
