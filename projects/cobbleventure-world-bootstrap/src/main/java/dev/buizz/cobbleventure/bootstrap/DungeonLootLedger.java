package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** Tracks run loot that must be granted on clear or reclaimed on failure. */
final class DungeonLootLedger {
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    private final Map<UUID, List<ItemStack>> removable = new HashMap<>();

    static DungeonLootLedger restore(
        Map<UUID, List<ItemStack>> pending,
        Map<UUID, List<ItemStack>> removable
    ) {
        DungeonLootLedger restored = new DungeonLootLedger();
        copyInto(pending, restored.pending);
        copyInto(removable, restored.removable);
        return restored;
    }

    Map<UUID, List<ItemStack>> pendingSnapshot() {
        return snapshot(pending);
    }

    Map<UUID, List<ItemStack>> removableSnapshot() {
        return snapshot(removable);
    }

    void record(String onFailure, UUID playerId, List<ItemStack> rewards) {
        LootDisposition disposition = disposition(onFailure);
        if (disposition == LootDisposition.KEEP_COLLECTED) return;
        Map<UUID, List<ItemStack>> target = disposition == LootDisposition.GRANT_ON_CLEAR
            ? pending : removable;
        List<ItemStack> stored = target.computeIfAbsent(
            playerId, ignored -> new ArrayList<>()
        );
        rewards.stream().filter(stack -> !stack.isEmpty())
            .map(ItemStack::copy).forEach(stored::add);
    }

    static LootDisposition disposition(String onFailure) {
        return switch (onFailure) {
            case "keep_collected" -> LootDisposition.KEEP_COLLECTED;
            case "grant_on_clear_only" -> LootDisposition.GRANT_ON_CLEAR;
            case "remove_run_loot" -> LootDisposition.REMOVE_ON_FAILURE;
            default -> throw new IllegalArgumentException(
                "Unknown dungeon loot failure policy: " + onFailure
            );
        };
    }

    List<ItemStack> pending(UUID playerId) {
        return copies(pending.get(playerId));
    }

    List<ItemStack> removable(UUID playerId) {
        return copies(removable.get(playerId));
    }

    private static List<ItemStack> copies(List<ItemStack> source) {
        if (source == null) return List.of();
        return source.stream().map(ItemStack::copy).toList();
    }

    private static Map<UUID, List<ItemStack>> snapshot(
        Map<UUID, List<ItemStack>> source
    ) {
        Map<UUID, List<ItemStack>> result = new HashMap<>();
        source.forEach((player, stacks) -> result.put(player, copies(stacks)));
        return Map.copyOf(result);
    }

    private static void copyInto(
        Map<UUID, List<ItemStack>> source,
        Map<UUID, List<ItemStack>> target
    ) {
        source.forEach((player, stacks) -> target.put(player, copies(stacks)));
    }

    enum LootDisposition {
        KEEP_COLLECTED,
        GRANT_ON_CLEAR,
        REMOVE_ON_FAILURE
    }
}
