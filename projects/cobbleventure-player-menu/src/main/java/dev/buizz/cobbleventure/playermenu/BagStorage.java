package dev.buizz.cobbleventure.playermenu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Persistent extra item slots owned by one player. */
public final class BagStorage {
    /** Sparse backing slots; normal 64-item stacks provide over 260,000 items of practical capacity. */
    public static final int SLOT_COUNT = 4096;
    public static final int SHORTCUT_COUNT = 10;
    private static final String ROOT_KEY = "cobbleventure_player_menu.bag";
    private static final String ITEMS_KEY = "Items";
    private static final String SHORTCUTS_KEY = "Shortcuts";
    private static final String EVENT_REWARDS_KEY = "EventRewards";
    private static final String REWARD_KIND_ITEM = "item";
    private static final String REWARD_KIND_LOOT = "loot";

    private BagStorage() {}

    public static NonNullList<ItemStack> load(ServerPlayer player) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        CompoundTag bag = bagTag(player);
        ListTag items = bag.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < SLOT_COUNT) {
                slots.set(slot, ItemStack.parseOptional(player.registryAccess(), entry.getCompound("Stack")));
            }
        }
        return slots;
    }

    public static void save(ServerPlayer player, List<ItemStack> slots) {
        normalize(slots);
        CompoundTag bag = bagTag(player);
        bag.putInt("Version", 1);
        bag.put(ITEMS_KEY, serializeItems(player, slots));
        saveBagTag(player, bag);
    }

    /** Grants once and journals the result in the same persisted compound as the bag. */
    public static EventRewardResult grantEventReward(
        ServerPlayer player, String operationId, ItemStack prototype, int count
    ) {
        CompoundTag bag = bagTag(player);
        ListTag rewards = bag.getList(EVENT_REWARDS_KEY, Tag.TAG_COMPOUND);
        String itemId = BuiltInRegistries.ITEM.getKey(prototype.getItem()).toString();
        for (int index = 0; index < rewards.size(); index++) {
            CompoundTag entry = rewards.getCompound(index);
            if (!operationId.equals(entry.getString("OperationId"))) continue;
            if (!rewardKind(entry).equals(REWARD_KIND_ITEM)
                || !itemId.equals(entry.getString("ItemId"))
                || count != entry.getInt("Requested")) {
                return new EventRewardResult(EventRewardStatus.CONFLICT, count, 0, count);
            }
            return new EventRewardResult(
                EventRewardStatus.REPLAYED,
                entry.getInt("Requested"),
                entry.getInt("Granted"),
                entry.getInt("Remaining")
            );
        }

        NonNullList<ItemStack> working = load(player);
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, prototype.getMaxStackSize());
            ItemStack offered = prototype.copyWithCount(batch);
            BagStorage.add(working, offered);
            if (!offered.isEmpty()) {
                return new EventRewardResult(EventRewardStatus.FULL, count, 0, count);
            }
            remaining -= batch;
        }

        normalize(working);
        CompoundTag entry = new CompoundTag();
        entry.putInt("Version", 1);
        entry.putString("Kind", REWARD_KIND_ITEM);
        entry.putString("OperationId", operationId);
        entry.putString("ItemId", itemId);
        entry.putInt("Requested", count);
        entry.putInt("Granted", count);
        entry.putInt("Remaining", 0);
        rewards.add(entry);
        bag.putInt("Version", 1);
        bag.put(ITEMS_KEY, serializeItems(player, working));
        bag.put(EVENT_REWARDS_KEY, rewards);
        saveBagTag(player, bag);
        BagNetwork.syncExternalMutation(player, working);
        return new EventRewardResult(EventRewardStatus.GRANTED, count, count, 0);
    }

    /**
     * Expands a loot table once per operation and atomically grants every generated stack.
     * A full bag journals the generated payload, so a retry neither rerolls nor partially grants it.
     */
    public static EventRewardResult grantEventLootReward(
        ServerPlayer player,
        String operationId,
        String lootTableId,
        int rollCount,
        Supplier<List<ItemStack>> generator
    ) {
        CompoundTag bag = bagTag(player);
        ListTag rewards = bag.getList(EVENT_REWARDS_KEY, Tag.TAG_COMPOUND);
        CompoundTag entry = null;
        List<ItemStack> generated = null;
        for (int index = 0; index < rewards.size(); index++) {
            CompoundTag candidate = rewards.getCompound(index);
            if (!operationId.equals(candidate.getString("OperationId"))) continue;
            if (!rewardKind(candidate).equals(REWARD_KIND_LOOT)
                || !lootTableId.equals(candidate.getString("LootTableId"))
                || rollCount != candidate.getInt("RollCount")) {
                int failedCount = Math.max(1, candidate.getInt("Requested"));
                return new EventRewardResult(
                    EventRewardStatus.CONFLICT, failedCount, 0, failedCount
                );
            }
            if (candidate.getBoolean("Granted")) {
                return new EventRewardResult(
                    EventRewardStatus.REPLAYED,
                    candidate.getInt("Requested"),
                    candidate.getInt("GrantedCount"),
                    candidate.getInt("Remaining")
                );
            }
            entry = candidate;
            generated = deserializeStacks(player, candidate.getList("Generated", Tag.TAG_COMPOUND));
            if (totalCount(generated) != candidate.getInt("Requested")) {
                int failedCount = Math.max(1, candidate.getInt("Requested"));
                return new EventRewardResult(
                    EventRewardStatus.CONFLICT, failedCount, 0, failedCount
                );
            }
            break;
        }

        if (entry == null) {
            generated = sanitizeGenerated(generator.get());
            entry = new CompoundTag();
            entry.putInt("Version", 1);
            entry.putString("Kind", REWARD_KIND_LOOT);
            entry.putString("OperationId", operationId);
            entry.putString("LootTableId", lootTableId);
            entry.putInt("RollCount", rollCount);
            entry.put("Generated", serializeStacks(player, generated));
            int requested = totalCount(generated);
            entry.putInt("Requested", requested);
            entry.putInt("GrantedCount", 0);
            entry.putInt("Remaining", requested);
            rewards.add(entry);
        }

        int requested = entry.getInt("Requested");
        NonNullList<ItemStack> working = load(player);
        for (ItemStack stack : generated) {
            ItemStack offered = stack.copy();
            add(working, offered);
            if (!offered.isEmpty()) {
                bag.putInt("Version", 1);
                bag.put(EVENT_REWARDS_KEY, rewards);
                saveBagTag(player, bag);
                return new EventRewardResult(
                    EventRewardStatus.FULL, requested, 0, requested
                );
            }
        }

        normalize(working);
        entry.putBoolean("Granted", true);
        entry.putInt("GrantedCount", requested);
        entry.putInt("Remaining", 0);
        bag.putInt("Version", 1);
        bag.put(ITEMS_KEY, serializeItems(player, working));
        bag.put(EVENT_REWARDS_KEY, rewards);
        saveBagTag(player, bag);
        BagNetwork.syncExternalMutation(player, working);
        return new EventRewardResult(
            EventRewardStatus.GRANTED, requested, requested, 0
        );
    }

    private static String rewardKind(CompoundTag entry) {
        String kind = entry.getString("Kind");
        return kind.isBlank() ? REWARD_KIND_ITEM : kind;
    }

    private static List<ItemStack> sanitizeGenerated(List<ItemStack> generated) {
        List<ItemStack> result = new ArrayList<>();
        if (generated == null) return result;
        for (ItemStack stack : generated) {
            if (stack != null && !stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    private static int totalCount(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) total += stack.getCount();
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("생성된 루트 아이템 수가 너무 많습니다: " + total);
        }
        return (int) total;
    }

    private static ListTag serializeStacks(ServerPlayer player, List<ItemStack> stacks) {
        ListTag serialized = new ListTag();
        for (ItemStack stack : stacks) {
            serialized.add(stack.save(player.registryAccess(), new CompoundTag()));
        }
        return serialized;
    }

    private static List<ItemStack> deserializeStacks(ServerPlayer player, ListTag serialized) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int index = 0; index < serialized.size(); index++) {
            ItemStack stack = ItemStack.parseOptional(player.registryAccess(), serialized.getCompound(index));
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return stacks;
    }

    private static ListTag serializeItems(ServerPlayer player, List<ItemStack> slots) {
        ListTag items = new ListTag();
        for (int slot = 0; slot < Math.min(SLOT_COUNT, slots.size()); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Stack", stack.save(player.registryAccess(), new CompoundTag()));
            items.add(entry);
        }
        return items;
    }

    public static NonNullList<ItemStack> loadShortcuts(ServerPlayer player) {
        NonNullList<ItemStack> shortcuts = NonNullList.withSize(SHORTCUT_COUNT, ItemStack.EMPTY);
        ListTag items = bagTag(player).getList(SHORTCUTS_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < SHORTCUT_COUNT) {
                shortcuts.set(slot, ItemStack.parseOptional(player.registryAccess(), entry.getCompound("Stack")));
            }
        }
        return shortcuts;
    }

    public static void saveShortcuts(ServerPlayer player, List<ItemStack> shortcuts) {
        ListTag items = new ListTag();
        for (int slot = 0; slot < Math.min(SHORTCUT_COUNT, shortcuts.size()); slot++) {
            ItemStack stack = shortcuts.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Stack", stack.copyWithCount(1).save(player.registryAccess(), new CompoundTag()));
            items.add(entry);
        }
        CompoundTag bag = bagTag(player);
        bag.put(SHORTCUTS_KEY, items);
        saveBagTag(player, bag);
    }

    private static CompoundTag bagTag(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.getCompound(ROOT_KEY);
    }

    private static void saveBagTag(ServerPlayer player, CompoundTag bag) {
        CompoundTag persistentData = player.getPersistentData();
        CompoundTag persisted = persistentData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.put(ROOT_KEY, bag);
        persistentData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** Adds as much as possible and mutates {@code incoming} to the remainder. */
    public static int add(List<ItemStack> slots, ItemStack incoming) {
        int originalCount = incoming.getCount();
        for (int slot = 0; slot < slots.size() && !incoming.isEmpty(); slot++) {
            ItemStack stored = slots.get(slot);
            if (stored.isEmpty() || !ItemStack.isSameItemSameComponents(stored, incoming)) continue;
            int moved = Math.min(incoming.getCount(), stored.getMaxStackSize() - stored.getCount());
            if (moved > 0) {
                stored.grow(moved);
                incoming.shrink(moved);
            }
        }
        for (int slot = 0; slot < slots.size() && !incoming.isEmpty(); slot++) {
            if (!slots.get(slot).isEmpty()) continue;
            int moved = Math.min(incoming.getCount(), incoming.getMaxStackSize());
            ItemStack inserted = incoming.copyWithCount(moved);
            slots.set(slot, inserted);
            incoming.shrink(moved);
        }
        return originalCount - incoming.getCount();
    }

    /** Compacts equal stacks so every stack except the final remainder is full. */
    public static boolean normalize(List<ItemStack> slots) {
        List<StackGroup> groups = new ArrayList<>();
        Map<Integer, List<Integer>> buckets = new HashMap<>();
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) continue;
            int hash = ItemStack.hashItemAndComponents(stack);
            List<Integer> candidates = buckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
            StackGroup matched = null;
            for (int candidate : candidates) {
                StackGroup group = groups.get(candidate);
                if (ItemStack.isSameItemSameComponents(group.prototype, stack)) {
                    matched = group;
                    break;
                }
            }
            if (matched == null) {
                candidates.add(groups.size());
                groups.add(new StackGroup(stack.copyWithCount(1), stack.getCount()));
            } else {
                matched.count += stack.getCount();
            }
        }

        List<ItemStack> normalized = new ArrayList<>(slots.size());
        for (StackGroup group : groups) {
            int remaining = group.count;
            while (remaining > 0) {
                int count = Math.min(remaining, group.prototype.getMaxStackSize());
                normalized.add(group.prototype.copyWithCount(count));
                remaining -= count;
            }
        }
        while (normalized.size() < slots.size()) normalized.add(ItemStack.EMPTY);

        boolean changed = false;
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack before = slots.get(slot);
            ItemStack after = normalized.get(slot);
            if (before.getCount() != after.getCount()
                || !ItemStack.isSameItemSameComponents(before, after)) changed = true;
            slots.set(slot, after);
        }
        return changed;
    }

    private static final class StackGroup {
        private final ItemStack prototype;
        private int count;

        private StackGroup(ItemStack prototype, int count) {
            this.prototype = prototype;
            this.count = count;
        }
    }

    public enum EventRewardStatus { GRANTED, REPLAYED, FULL, CONFLICT }

    public record EventRewardResult(
        EventRewardStatus status, int requested, int granted, int remaining
    ) {}
}
