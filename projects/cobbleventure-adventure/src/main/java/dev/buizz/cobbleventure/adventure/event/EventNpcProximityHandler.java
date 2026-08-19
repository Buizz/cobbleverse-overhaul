package dev.buizz.cobbleventure.adventure.event;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Detects proximity and indexed world edges without exposing EasyNPC internals. */
public final class EventNpcProximityHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final EventProximityTracker<BoundaryKey> TRACKER =
        new EventProximityTracker<>();

    private EventNpcProximityHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventNpcProximityHandler::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Post tick) {
        long gameTime = tick.getServer().overworld().getGameTime();
        if (Math.floorMod(gameTime, SCAN_INTERVAL_TICKS) != 0) return;
        Set<BoundaryKey> observed = new HashSet<>();
        Map<ServerLevel, List<BoundNpc>> boundNpcs = loadedBoundNpcs(tick);
        List<BoundNpc> allBoundNpcs = boundNpcs.values().stream()
            .flatMap(List::stream).toList();
        for (ServerPlayer player : tick.getServer().getPlayerList().getPlayers()) {
            EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
                new ServerPlayerEventState(player)
            );
            for (BoundNpc bound : boundNpcs.getOrDefault(player.serverLevel(), List.of())) {
                observe(
                    player, bound.entity(), bound.binding(), bound.script(),
                    environment, gameTime, observed
                );
            }
            try {
                EventBoundaryProviderRegistry.snapshot(player).ifPresent(snapshot -> {
                    for (BoundNpc bound : allBoundNpcs) {
                        observeIndexed(
                            player, bound, snapshot, environment, gameTime, observed
                        );
                    }
                });
            } catch (RuntimeException error) {
                LOGGER.error(
                    "CVES indexed boundary snapshot failed: player={}",
                    player.getGameProfile().getName(), error
                );
            }
        }
        TRACKER.retainAll(observed);
    }

    private static Map<ServerLevel, List<BoundNpc>> loadedBoundNpcs(
        ServerTickEvent.Post tick
    ) {
        Map<ServerLevel, List<BoundNpc>> result = new HashMap<>();
        for (ServerLevel level : tick.getServer().getAllLevels()) {
            List<BoundNpc> entries = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.isRemoved()) continue;
                try {
                    Optional<EventNpcBinding> match = EventNpcBindingRepository.instance()
                        .findByEntityTags(entity.getTags());
                    if (match.isEmpty()) continue;
                    EventNpcBinding binding = match.orElseThrow();
                    EventScriptRepository.instance().find(binding.scriptId()).ifPresent(
                        script -> entries.add(new BoundNpc(entity, binding, script))
                    );
                } catch (RuntimeException error) {
                    LOGGER.error(
                        "CVES NPC proximity binding scan failed: npc={}",
                        entity.getUUID(), error
                    );
                }
            }
            result.put(level, List.copyOf(entries));
        }
        return result;
    }

    private static void observe(
        ServerPlayer player,
        Entity npc,
        EventNpcBinding binding,
        EventScript script,
        EventStateExpressionEnvironment environment,
        long gameTime,
        Set<BoundaryKey> observed
    ) {
        for (EventScript.Event event : script.events()) {
            String trigger = event.trigger().name();
            if (!trigger.equals("proximity_enter") && !trigger.equals("proximity_exit")) {
                continue;
            }
            BoundaryKey key = new BoundaryKey(
                player.getUUID(), npc.getUUID(), script.scriptId(), event.index()
            );
            observed.add(key);
            try {
                EventTriggerContract.Options options = EventTriggerContract.proximity(
                    event, environment
                );
                boolean inside = player.distanceToSqr(npc) <= options.range() * options.range();
                EventProximityTracker.Transition transition = TRACKER.observe(key, inside);
                boolean matches = trigger.equals("proximity_enter")
                    ? transition == EventProximityTracker.Transition.ENTER
                    : transition == EventProximityTracker.Transition.EXIT;
                EventTriggerLedger.Key ledgerKey = ledgerKey(key);
                if (!matches || !EventTriggerLedger.canFire(
                    player, ledgerKey, options, gameTime
                )) continue;
                String triggerInstance = trigger + ":" + event.index();
                if (EventTriggerExecutor.execute(
                    player, npc, binding, script, event, triggerInstance
                )) {
                    EventTriggerLedger.markFired(
                        player, ledgerKey, options.once(), gameTime
                    );
                }
            } catch (RuntimeException error) {
                reportFailure(player, npc, "NPC proximity 이벤트를 시작하지 못했습니다.", error);
            }
        }
    }

    private static void observeIndexed(
        ServerPlayer player,
        BoundNpc bound,
        EventBoundaryProviderRegistry.Snapshot snapshot,
        EventStateExpressionEnvironment environment,
        long gameTime,
        Set<BoundaryKey> observed
    ) {
        for (EventScript.Event event : bound.script().events()) {
            String trigger = event.trigger().name();
            if (!Set.of(
                "region_enter", "region_exit", "anchor_step",
                "building_enter", "building_exit", "dimension_enter", "dimension_exit"
            ).contains(trigger)) {
                continue;
            }
            BoundaryKey key = new BoundaryKey(
                player.getUUID(), bound.entity().getUUID(),
                bound.script().scriptId(), event.index()
            );
            observed.add(key);
            try {
                EventTriggerContract.TargetOptions options = EventTriggerContract.targeted(
                    event, environment
                );
                boolean inside = EventIndexedBoundaryMatcher.inside(
                    trigger, options.target(), snapshot
                );
                EventProximityTracker.Transition transition = TRACKER.observe(key, inside);
                boolean matches = EventIndexedBoundaryMatcher.matches(trigger, transition);
                EventTriggerLedger.Key ledgerKey = ledgerKey(key);
                if (!matches || !EventTriggerLedger.canFire(
                    player, ledgerKey, options, gameTime
                )) continue;
                if (EventTriggerExecutor.execute(
                    player, bound.entity(), bound.binding(), bound.script(), event,
                    trigger + ":" + event.index()
                )) {
                    EventTriggerLedger.markFired(
                        player, ledgerKey, options.once(), gameTime
                    );
                }
            } catch (RuntimeException error) {
                reportFailure(
                    player, bound.entity(), "NPC indexed boundary 이벤트를 시작하지 못했습니다.",
                    error
                );
            }
        }
    }

    private static EventTriggerLedger.Key ledgerKey(BoundaryKey key) {
        return new EventTriggerLedger.Key(
            key.npcId(), key.scriptId(), key.eventIndex()
        );
    }

    private static void reportFailure(
        ServerPlayer player, Entity npc, String message, RuntimeException error
    ) {
        LOGGER.error(
            "CVES NPC proximity trigger failed: player={}, npc={}, message={}",
            player.getGameProfile().getName(), npc.getUUID(), message, error
        );
    }

    private record BoundaryKey(
        UUID playerId, UUID npcId, String scriptId, int eventIndex
    ) {}

    private record BoundNpc(
        Entity entity, EventNpcBinding binding, EventScript script
    ) {}
}
