package dev.buizz.cobbleventure.adventure.event;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Adapts authoritative flag and item state changes to the common signal dispatcher. */
public final class EventServerSignalBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLAG_SCAN_INTERVAL_TICKS = 5;
    private static final EventValueChangeTracker<FlagKey, Boolean> FLAGS =
        new EventValueChangeTracker<>();

    private EventServerSignalBridge() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventServerSignalBridge::onServerTick);
        NeoForge.EVENT_BUS.addListener(EventServerSignalBridge::onItemUseFinished);
        NeoForge.EVENT_BUS.addListener(EventServerSignalBridge::onServerStopped);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        if (Math.floorMod(gameTime, FLAG_SCAN_INTERVAL_TICKS) != 0) return;
        Set<FlagKey> observed = new HashSet<>();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ServerPlayerEventState state = new ServerPlayerEventState(player);
            for (String target : EventServerSignalDispatcher.subscribedTargets(
                player, "flag_changed"
            )) {
                FlagKey key = new FlagKey(player.getUUID(), target);
                observed.add(key);
                boolean value = state.flag(target);
                if (FLAGS.changed(key, value)) {
                    EventServerSignalDispatcher.flagChanged(player, target);
                }
            }
        }
        FLAGS.retainAll(observed);
    }

    private static void onItemUseFinished(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getItem().isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(event.getItem().getItem()).toString();
        try {
            EventServerSignalDispatcher.itemUsed(player, itemId);
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES confirmed item use signal failed: player={}, item={}",
                player.getGameProfile().getName(), itemId, error
            );
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        FLAGS.clear();
    }

    private record FlagKey(UUID playerId, String flagId) {}
}
