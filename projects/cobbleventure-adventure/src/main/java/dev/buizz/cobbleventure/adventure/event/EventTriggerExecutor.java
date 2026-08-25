package dev.buizz.cobbleventure.adventure.event;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Shared session entry point for interaction and server-detected CVES triggers. */
final class EventTriggerExecutor {
    private static final int MAX_STEPS = 10_000;

    private EventTriggerExecutor() {}

    static boolean execute(
        ServerPlayer player,
        Entity npc,
        EventNpcBinding binding,
        EventScript script,
        EventScript.Event event,
        String triggerInstance
    ) {
        if (!binding.scriptId().equals(script.scriptId())) {
            throw new EventRuntimeException(
                "NPC 바인딩과 실행 스크립트가 다릅니다: " + binding.bindingId()
            );
        }
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
            new ServerPlayerEventState(player)
        );
        EventSessionStore store = SavedEventSessionStore.get(player.getServer());
        EventSessionKey key = new EventSessionKey(
            player.getUUID(), npc.getUUID(), script.scriptId(), triggerInstance
        );
        EventRecoverableAwait.resetLegacyCancelledNumberInput(store, key);
        if (EventAwaitInputLockService.isLocked(player.getUUID())) return false;
        resetRecoverableAwait(store, key);
        Optional<EventSession> session = EventInterpreter.startSession(
            script, event.index(), key, environment, store
        );
        if (session.isEmpty()) return false;
        EventInterpreter.run(
            script,
            session.orElseThrow(),
            environment,
            EventDialogueNetwork.serverAdapter(player),
            store,
            MAX_STEPS
        );
        return true;
    }

    private static void resetRecoverableAwait(EventSessionStore store, EventSessionKey key) {
        EventRecoverableAwait.resetStarterRoulette(store, key);
        EventRecoverableAwait.resetMapSelection(store, key);
        EventRecoverableAwait.resetGiveItem(store, key);
        EventRecoverableAwait.resetGiveLoot(store, key);
        EventRecoverableAwait.resetChoice(store, key);
        EventRecoverableAwait.resetBattle(store, key);
        EventRecoverableAwait.resetMovement(store, key);
        EventRecoverableAwait.resetPresentation(store, key);
        EventRecoverableAwait.resetHealing(store, key);
    }
}
