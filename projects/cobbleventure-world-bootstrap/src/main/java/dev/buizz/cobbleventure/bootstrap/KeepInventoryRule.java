package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Keeps player inventory and experience when a player dies. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID)
public final class KeepInventoryRule {
    private KeepInventoryRule() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
        }
    }
}
