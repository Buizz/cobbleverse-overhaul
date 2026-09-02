package dev.buizz.cobbleventure.adventure.quest;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Periodically evaluates global activation and quest progress across dimensions. */
public final class QuestProgressUpdater {
    private static final int INTERVAL_TICKS = 20;
    private static int ticks;

    private QuestProgressUpdater() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(QuestProgressUpdater::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            dev.buizz.cobbleventure.adventure.event.QuestEventHooks.tick(player);
        }
        if (++ticks < INTERVAL_TICKS) return;
        ticks = 0;
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            QuestService.refreshGlobalActivations(player);
            QuestService.refreshActive(player);
        }
    }
}
