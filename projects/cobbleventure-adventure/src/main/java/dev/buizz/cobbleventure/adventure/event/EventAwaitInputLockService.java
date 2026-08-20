package dev.buizz.cobbleventure.adventure.event;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Keeps player input locked while a live CVES await owns the interaction flow. */
public final class EventAwaitInputLockService {
    private static final Map<UUID, ActiveLock> ACTIVE = new HashMap<>();
    private static boolean registered;

    private EventAwaitInputLockService() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventAwaitInputLockService::onServerTick);
    }

    static void acquire(
        ServerPlayer player, EventSessionKey key, String token, String kind
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        if (token == null || token.isBlank() || kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("await input lock에는 token과 kind가 필요합니다.");
        }
        ACTIVE.put(player.getUUID(), new ActiveLock(key, token, player));
        EventDialogueNetwork.setAwaitInputLocked(player, kind, true);
    }

    /** Allows the next authored screen to replace the previous await screen safely. */
    static void prepareResume(UUID playerId, String token) {
        ActiveLock active = ACTIVE.get(playerId);
        if (active == null || !active.token.equals(token)) return;
        EventDialogueNetwork.setAwaitInputLocked(active.owner, "transition", true);
    }

    static boolean isLocked(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        for (Map.Entry<UUID, ActiveLock> entry : ACTIVE.entrySet().stream().toList()) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            ActiveLock active = entry.getValue();
            if (player == null) {
                ACTIVE.remove(entry.getKey(), active);
                continue;
            }
            active.owner = player;
            EventSession session = SavedEventSessionStore.get(event.getServer())
                .find(active.key).orElse(null);
            EventSession.AwaitState awaiting = session == null ? null : session.awaiting();
            boolean current = session != null
                && session.status() == EventSession.Status.WAITING
                && awaiting != null
                && awaiting.token().equals(active.token)
                && (awaiting.expiresAtEpochMilli() <= 0
                    || System.currentTimeMillis() < awaiting.expiresAtEpochMilli());
            if (current || !ACTIVE.remove(entry.getKey(), active)) continue;
            EventDialogueNetwork.setAwaitInputLocked(player, "", false);
        }
    }

    private static final class ActiveLock {
        private final EventSessionKey key;
        private final String token;
        private ServerPlayer owner;

        private ActiveLock(EventSessionKey key, String token, ServerPlayer owner) {
            this.key = key;
            this.token = token;
            this.owner = owner;
        }
    }
}
