package dev.buizz.cobbleventure.pokefinder;

import dev.buizz.cobbleventure.pokefinder.client.PokefinderRadarClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CobbleventurePokefinder.MOD_ID)
public final class CobbleventurePokefinder {
    public static final String MOD_ID = "cobbleventure_pokefinder";

    public CobbleventurePokefinder(IEventBus modBus) {
        RadarMarkerNetwork.register(modBus);
        modBus.addListener(PokefinderItemCompatibility::hideCreativeEntries);
        NeoForge.EVENT_BUS.addListener(PokefinderItemCompatibility::migrateLegacyInventory);
        if (FMLEnvironment.dist.isClient()) {
            PokefinderRadarClient.register(modBus);
        }
    }
}
