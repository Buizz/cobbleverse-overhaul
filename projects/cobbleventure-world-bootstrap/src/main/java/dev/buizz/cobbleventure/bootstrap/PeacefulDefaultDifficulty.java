package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.world.Difficulty;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Applies the pack's peaceful default while respecting hardcore and locked worlds. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID)
public final class PeacefulDefaultDifficulty {
    private PeacefulDefaultDifficulty() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isHardcore()
            && !event.getServer().getWorldData().isDifficultyLocked()) {
            event.getServer().setDifficulty(Difficulty.PEACEFUL, false);
        }
    }
}
