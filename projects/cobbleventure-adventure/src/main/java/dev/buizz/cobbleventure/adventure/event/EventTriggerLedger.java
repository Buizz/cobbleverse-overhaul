package dev.buizz.cobbleventure.adventure.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** Persistent player-scoped once/cooldown ledger shared by every server trigger source. */
final class EventTriggerLedger {
    private static final String LEDGER_KEY = "cobbleventureCvesTriggerLedger";
    private static final String LAST_FIRED_KEY = "lastFiredGameTime";
    private static final String COMPLETED_KEY = "completed";

    record Key(UUID npcId, String scriptId, int eventIndex) {}

    private EventTriggerLedger() {}

    static boolean canFire(
        ServerPlayer player, Key key, EventTriggerContract.Options options, long gameTime
    ) {
        return canFire(player, key, options.once(), options.cooldownSeconds(), gameTime);
    }

    static boolean canFire(
        ServerPlayer player, Key key, EventTriggerContract.TargetOptions options, long gameTime
    ) {
        return canFire(player, key, options.once(), options.cooldownSeconds(), gameTime);
    }

    private static boolean canFire(
        ServerPlayer player, Key key, boolean once, double cooldown, long gameTime
    ) {
        CompoundTag state = triggerState(player, key);
        Long lastFired = state.contains(LAST_FIRED_KEY, Tag.TAG_LONG)
            ? state.getLong(LAST_FIRED_KEY) : null;
        return EventTriggerGate.canFire(
            state.getBoolean(COMPLETED_KEY), lastFired,
            once, cooldown, gameTime
        );
    }

    static void markFired(ServerPlayer player, Key key, boolean once, long gameTime) {
        CompoundTag root = player.getPersistentData().getCompound(LEDGER_KEY);
        String stateKey = stateKey(key);
        CompoundTag state = root.getCompound(stateKey);
        state.putLong(LAST_FIRED_KEY, gameTime);
        if (once) state.putBoolean(COMPLETED_KEY, true);
        root.put(stateKey, state);
        player.getPersistentData().put(LEDGER_KEY, root);
    }

    private static CompoundTag triggerState(ServerPlayer player, Key key) {
        CompoundTag root = player.getPersistentData().getCompound(LEDGER_KEY);
        String stateKey = stateKey(key);
        if (!root.contains(stateKey, Tag.TAG_COMPOUND)) return new CompoundTag();
        return root.getCompound(stateKey);
    }

    private static String stateKey(Key key) {
        String identity = key.npcId() + "|" + key.scriptId() + "|" + key.eventIndex();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
