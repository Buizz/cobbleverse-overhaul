package dev.buizz.cobbleventure.adventure.event;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/** Routes authoritative server callbacks into bound CVES events. */
public final class EventServerSignalDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> SIGNALS = Set.of(
        "flag_changed", "item_used", "battle_finished"
    );

    private EventServerSignalDispatcher() {}

    public static int flagChanged(ServerPlayer player, String flagId) {
        return dispatch(player, "flag_changed", flagId);
    }

    public static int itemUsed(ServerPlayer player, String itemId) {
        return dispatch(player, "item_used", itemId);
    }

    public static int battleFinished(
        ServerPlayer player, String battleId, String outcome
    ) {
        if (!Set.of("win", "loss", "cancelled").contains(outcome)) {
            throw new EventRuntimeException("지원하지 않는 battle outcome입니다: " + outcome);
        }
        return dispatch(player, "battle_finished", battleId);
    }

    static Set<String> subscribedTargets(ServerPlayer player, String trigger) {
        requireSignal(trigger);
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
            new ServerPlayerEventState(player)
        );
        Set<String> result = new HashSet<>();
        for (BoundNpc bound : loadedBoundNpcs(player)) {
            for (EventScript.Event event : bound.script.events()) {
                if (!event.trigger().name().equals(trigger)) continue;
                try {
                    result.add(EventTriggerContract.targeted(event, environment).target());
                } catch (RuntimeException error) {
                    reportFailure(player, bound.entity, trigger, error);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static int dispatch(ServerPlayer player, String trigger, String target) {
        requireSignal(trigger);
        requireResourceId(target);
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
            new ServerPlayerEventState(player)
        );
        long gameTime = player.getServer().overworld().getGameTime();
        int fired = 0;
        for (BoundNpc bound : loadedBoundNpcs(player)) {
            for (EventScript.Event event : bound.script.events()) {
                if (!event.trigger().name().equals(trigger)) continue;
                try {
                    EventTriggerContract.TargetOptions options =
                        EventTriggerContract.targeted(event, environment);
                    if (!EventSignalMatcher.matches(
                        event.trigger().name(), options.target(), trigger, target
                    )) continue;
                    EventTriggerLedger.Key key = new EventTriggerLedger.Key(
                        bound.entity.getUUID(), bound.script.scriptId(), event.index()
                    );
                    if (!EventTriggerLedger.canFire(player, key, options, gameTime)) continue;
                    if (EventTriggerExecutor.execute(
                        player, bound.entity, bound.binding, bound.script, event,
                        trigger + ":" + event.index()
                    )) {
                        EventTriggerLedger.markFired(
                            player, key, options.once(), gameTime
                        );
                        fired++;
                    }
                } catch (RuntimeException error) {
                    reportFailure(player, bound.entity, trigger, error);
                }
            }
        }
        return fired;
    }

    private static List<BoundNpc> loadedBoundNpcs(ServerPlayer player) {
        List<BoundNpc> result = new ArrayList<>();
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.isRemoved()) continue;
                try {
                    Optional<EventNpcBinding> match = EventNpcBindingRepository.instance()
                        .findByEntityTags(entity.getTags());
                    if (match.isEmpty()) continue;
                    EventNpcBinding binding = match.orElseThrow();
                    EventScriptRepository.instance().find(binding.scriptId()).ifPresent(
                        script -> result.add(new BoundNpc(entity, binding, script))
                    );
                } catch (RuntimeException error) {
                    LOGGER.error(
                        "CVES server signal binding scan failed: npc={}",
                        entity.getUUID(), error
                    );
                }
            }
        }
        result.sort(Comparator.comparing(value -> value.entity.getUUID().toString()));
        return List.copyOf(result);
    }

    private static void requireSignal(String trigger) {
        if (!SIGNALS.contains(trigger)) {
            throw new EventRuntimeException(
                "지원하지 않는 server signal trigger입니다: " + trigger
            );
        }
    }

    private static void requireResourceId(String target) {
        if (ResourceLocation.tryParse(target) == null) {
            throw new EventRuntimeException("signal target은 리소스 ID여야 합니다: " + target);
        }
    }

    private static void reportFailure(
        ServerPlayer player, Entity npc, String trigger, RuntimeException error
    ) {
        LOGGER.error(
            "CVES server signal trigger failed: player={}, npc={}, trigger={}",
            player.getGameProfile().getName(), npc.getUUID(), trigger, error
        );
    }

    private record BoundNpc(
        Entity entity, EventNpcBinding binding, EventScript script
    ) {}
}
