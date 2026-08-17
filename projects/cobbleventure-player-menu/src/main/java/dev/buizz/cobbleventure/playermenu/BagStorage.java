package dev.buizz.cobbleventure.playermenu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
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
        ListTag items = new ListTag();
        for (int slot = 0; slot < Math.min(SLOT_COUNT, slots.size()); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Stack", stack.save(player.registryAccess(), new CompoundTag()));
            items.add(entry);
        }

        CompoundTag bag = bagTag(player);
        bag.putInt("Version", 1);
        bag.put(ITEMS_KEY, items);
        saveBagTag(player, bag);
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
}
