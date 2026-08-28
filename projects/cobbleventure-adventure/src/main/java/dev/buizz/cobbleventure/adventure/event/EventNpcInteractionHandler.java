package dev.buizz.cobbleventure.adventure.event;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/** Starts a CVES session when a player interacts with an entity carrying a V5 binding tag. */
public final class EventNpcInteractionHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private EventNpcInteractionHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventNpcInteractionHandler::onEntityInteract);
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()
            || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity target = event.getTarget();
        EventNpcBinding binding;
        try {
            Optional<EventNpcBinding> match = EventNpcBindingRepository.instance()
                .findByEntityTags(target.getTags());
            if (match.isEmpty()) return;
            binding = match.orElseThrow();
        } catch (RuntimeException error) {
            cancel(event, InteractionResult.FAIL);
            reportFailure(player, target, "NPC 이벤트 바인딩이 올바르지 않습니다.", error);
            return;
        }

        // A V5 binding owns the interaction, including failure paths, so a legacy
        // representation adapter cannot also open its own dialogue.
        cancel(event, InteractionResult.SUCCESS);
        if (!EventNpcTriggerMode.acceptsInteraction(target.getTags())) {
            return;
        }
        try {
            executeInteract(player, target, binding, true);
        } catch (RuntimeException error) {
            reportFailure(player, target, "NPC 이벤트를 시작하지 못했습니다.", error);
        }
    }

    /**
     * Starts the V5 interaction bound to an NPC from another server-side system.
     * Programmatic triggers intentionally skip the player's click-range check.
     */
    public static boolean startBoundInteraction(ServerPlayer player, Entity target) {
        if (!EventNpcTriggerMode.acceptsInteraction(target.getTags())) return false;
        try {
            Optional<EventNpcBinding> match = EventNpcBindingRepository.instance()
                .findByEntityTags(target.getTags());
            if (match.isEmpty()) return false;
            return executeInteract(player, target, match.orElseThrow(), false);
        } catch (RuntimeException error) {
            reportFailure(player, target, "NPC 이벤트를 시작하지 못했습니다.", error);
            return false;
        }
    }

    private static boolean executeInteract(
        ServerPlayer player, Entity target, EventNpcBinding binding, boolean enforceRange
    ) {
        EventScript script = EventScriptRepository.instance().find(binding.scriptId())
            .orElseThrow(() -> new EventRuntimeException(
                "바인딩된 CVES 스크립트를 찾을 수 없습니다: " + binding.scriptId()
            ));
        EventScript.Event interact = EventNpcInteractionContract
            .uniqueInteractEvent(script)
            .orElseThrow(() -> new EventRuntimeException(
                "바인딩된 스크립트에 interact 이벤트가 없습니다: " + script.scriptId()
            ));
        if (enforceRange) {
            EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
                new ServerPlayerEventState(player)
            );
            double scriptedRange = EventNpcInteractionContract.interactionRange(
                interact, environment
            );
            double playerRange = player.entityInteractionRange();
            double range = EventNpcInteractionRange.directClickRange(
                scriptedRange, playerRange
            );
            if (!EventNpcInteractionRange.contains(
                player.position(), target.getBoundingBox(), range
            )) {
                throw new EventRuntimeException(
                    "NPC 상호작용 거리가 범위를 벗어났습니다: distance="
                        + EventNpcInteractionRange.distanceToBounds(
                            player.position(), target.getBoundingBox()
                        )
                        + ", scripted=" + scriptedRange
                        + ", player=" + playerRange
                        + ", allowed=" + range
                );
            }
        }
        return EventTriggerExecutor.execute(
            player, target, binding, script, interact, "interact"
        );
    }

    private static void cancel(
        PlayerInteractEvent.EntityInteract event, InteractionResult result
    ) {
        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    private static void reportFailure(
        ServerPlayer player, Entity target, String message, RuntimeException error
    ) {
        player.displayClientMessage(Component.literal(message), true);
        LOGGER.error(
            "CVES NPC interaction failed: player={}, npc={}",
            player.getGameProfile().getName(), target.getUUID(), error
        );
    }
}
