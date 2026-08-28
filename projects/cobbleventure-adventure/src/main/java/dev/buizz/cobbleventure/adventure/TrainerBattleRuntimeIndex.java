package dev.buizz.cobbleventure.adventure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Keeps temporary trainer resources isolated by the exact Cobblemon battle UUID. */
final class TrainerBattleRuntimeIndex<T> {
    private final Map<String, T> pending = new HashMap<>();
    private final Map<UUID, T> active = new HashMap<>();

    void register(String runtimeId, T runtime) {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(runtime, "runtime");
        if (pending.putIfAbsent(runtimeId, runtime) != null) {
            throw new IllegalStateException("중복 임시 트레이너 ID입니다: " + runtimeId);
        }
    }

    boolean activate(UUID battleId, Predicate<T> participantMatch) {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(participantMatch, "participantMatch");
        if (active.containsKey(battleId)) return false;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, T> entry = iterator.next();
            if (!participantMatch.test(entry.getValue())) continue;
            T runtime = entry.getValue();
            iterator.remove();
            active.put(battleId, runtime);
            return true;
        }
        return false;
    }

    T finish(UUID battleId) {
        return active.remove(Objects.requireNonNull(battleId, "battleId"));
    }

    List<Map.Entry<String, T>> pendingEntries() {
        return new ArrayList<>(pending.entrySet());
    }

    boolean removePending(String runtimeId, T runtime) {
        return pending.remove(runtimeId, runtime);
    }
}
