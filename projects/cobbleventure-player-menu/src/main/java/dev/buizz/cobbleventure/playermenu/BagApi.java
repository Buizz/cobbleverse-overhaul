package dev.buizz.cobbleventure.playermenu;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Public server API for rewards, shops and quests that read or mutate a player's bag. */
public final class BagApi {
    private BagApi() {}

    /** Inserts directly into the extended bag without changing the inventory or hotbar. */
    public static InsertResult insert(ServerPlayer player, ItemStack offered) {
        return insert(player, offered, false);
    }

    /**
     * Inserts an item while preserving all components. When {@code allOrNothing} is true,
     * nothing changes unless the full offered stack fits.
     */
    public static InsertResult insert(ServerPlayer player, ItemStack offered, boolean allOrNothing) {
        if (offered.isEmpty()) return new InsertResult(0, ItemStack.EMPTY);
        ItemStack remainder = offered.copy();
        int requested = remainder.getCount();
        if (allOrNothing && availableCapacity(player, remainder) < requested) {
            return new InsertResult(0, remainder);
        }

        NonNullList<ItemStack> storage = BagStorage.load(player);
        int extendedInserted = BagStorage.add(storage, remainder);
        if (extendedInserted > 0) BagStorage.save(player, storage);
        int inserted = requested - remainder.getCount();
        if (inserted > 0) BagNetwork.syncExternalMutation(player, storage);
        return new InsertResult(inserted, remainder.copy());
    }

    /** Inserts a complete reward batch into the extended bag as one transaction. */
    public static BatchInsertResult insertAll(ServerPlayer player, List<ItemStack> offered) {
        NonNullList<ItemStack> original = BagStorage.load(player);
        NonNullList<ItemStack> working = NonNullList.withSize(BagStorage.SLOT_COUNT, ItemStack.EMPTY);
        for (int slot = 0; slot < original.size(); slot++) {
            working.set(slot, original.get(slot).copy());
        }

        int requested = 0;
        int inserted = 0;
        for (ItemStack stack : offered) {
            if (stack.isEmpty()) continue;
            ItemStack remainder = stack.copy();
            requested += remainder.getCount();
            inserted += BagStorage.add(working, remainder);
            if (!remainder.isEmpty()) return new BatchInsertResult(0, requested, false);
        }

        if (inserted > 0) {
            BagStorage.save(player, working);
            BagNetwork.syncExternalMutation(player, working);
        }
        return new BatchInsertResult(inserted, requested, true);
    }

    /** Returns the total matching count across the vanilla and extended slots. */
    public static int count(ServerPlayer player, ItemStack prototype) {
        if (prototype.isEmpty()) return 0;
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, prototype)) total += stack.getCount();
        }
        for (ItemStack stack : BagStorage.load(player)) {
            if (ItemStack.isSameItemSameComponents(stack, prototype)) total += stack.getCount();
        }
        return total;
    }

    /** Returns the total count by item type, ignoring stack components. */
    public static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        for (ItemStack stack : BagStorage.load(player)) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Atomically removes the requested amount, returning false without changes when insufficient. */
    public static boolean remove(ServerPlayer player, ItemStack prototype, int amount) {
        if (amount <= 0) return true;
        if (prototype.isEmpty() || count(player, prototype) < amount) return false;

        int remaining = amount;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!ItemStack.isSameItemSameComponents(stack, prototype)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) player.getInventory().setItem(slot, ItemStack.EMPTY);
        }

        NonNullList<ItemStack> storage = BagStorage.load(player);
        boolean storageChanged = false;
        for (int slot = 0; slot < storage.size() && remaining > 0; slot++) {
            ItemStack stack = storage.get(slot);
            if (!ItemStack.isSameItemSameComponents(stack, prototype)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            storageChanged = true;
            if (stack.isEmpty()) storage.set(slot, ItemStack.EMPTY);
        }
        if (storageChanged) BagStorage.save(player, storage);
        BagNetwork.syncExternalMutation(player, storage);
        return true;
    }

    public static int occupiedSlots(ServerPlayer player) {
        int occupied = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) occupied++;
        }
        for (ItemStack stack : BagStorage.load(player)) {
            if (!stack.isEmpty()) occupied++;
        }
        return occupied;
    }

    public static int totalSlots() {
        return 36 + BagStorage.SLOT_COUNT;
    }

    private static int availableCapacity(ServerPlayer player, ItemStack prototype) {
        return capacityIn(BagStorage.load(player), prototype);
    }

    private static int capacityIn(List<ItemStack> slots, ItemStack prototype) {
        int capacity = 0;
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) capacity += prototype.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stack, prototype)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return capacity;
    }

    public record InsertResult(int inserted, ItemStack remainder) {
        public boolean complete() {
            return remainder.isEmpty();
        }
    }

    public record BatchInsertResult(int inserted, int requested, boolean complete) {}
}
