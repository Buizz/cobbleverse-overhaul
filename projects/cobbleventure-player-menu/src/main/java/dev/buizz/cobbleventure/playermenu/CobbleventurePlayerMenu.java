package dev.buizz.cobbleventure.playermenu;

import dev.buizz.cobbleventure.playermenu.client.PlayerMenuClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CobbleventurePlayerMenu.MOD_ID)
public final class CobbleventurePlayerMenu {
    public static final String MOD_ID = "cobbleventure_player_menu";

    public CobbleventurePlayerMenu(IEventBus modBus) {
        PlayerMenuItems.register(modBus);
        BagNetwork.register(modBus);
        ItemAcquisition.register(modBus);
        MapNetwork.register(modBus);
        PlayerOverviewNetwork.register(modBus);
        BadgeProgressNetwork.register(modBus);
        StarterRouletteNetwork.register(modBus);
        BattleIntro.register(modBus);
        MusicPlayback.register(modBus);
        LocationAnnouncement.register(modBus);
        if (FMLEnvironment.dist.isClient()) {
            PlayerMenuClient.register(modBus);
        }
    }
}
