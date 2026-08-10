package dev.buizz.cobbleventure.playermenu;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Persistent extra item slots owned by one player. */
public final class BagStorage {
    public static final int SLOT_COUNT = 180;
    private static final String ROOT_KEY = "cobbleventure_player_menu.bag";
    private static final String ITEMS_KEY = "Items";

    private BagStorage() {}

    public static NonNullList<ItemStack> load(ServerPlayer player) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag bag = persisted.getCompound(ROOT_KEY);
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
        ListTag items = new ListTag();
        for (int slot = 0; slot < Math.min(SLOT_COUNT, slots.size()); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Stack", stack.save(player.registryAccess(), new CompoundTag()));
            items.add(entry);
        }

        CompoundTag bag = new CompoundTag();
        bag.putInt("Version", 1);
        bag.put(ITEMS_KEY, items);
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
}
