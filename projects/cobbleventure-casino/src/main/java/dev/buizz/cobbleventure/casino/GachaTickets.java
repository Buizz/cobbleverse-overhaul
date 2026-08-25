package dev.buizz.cobbleventure.casino;

import java.lang.reflect.Method;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

final class GachaTickets {
    private static final String TICKET_TYPE_KEY = "GachaTicketType";
    private static final String LEGACY_MACHINE_KEY = "GachaMachine";

    private GachaTickets() {}

    static ItemStack create(GachaCatalog.Machine machine, int count) {
        ItemStack stack = new ItemStack(CasinoItems.GACHA_TICKET.get(), count);
        CompoundTag tag = new CompoundTag();
        tag.putString(TICKET_TYPE_KEY, machine.machine_type);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelData(machine.machine_type)));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(machine.ticket.display_name));
        return stack;
    }

    static int modelData(String ticketType) {
        return switch (ticketType) {
            case "pokemon" -> 1;
            case "item" -> 2;
            case "technical_machine" -> 3;
            default -> 0;
        };
    }

    static String ticketType(ItemStack stack) {
        if (!stack.is(CasinoItems.GACHA_TICKET.get())) return "";
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        CompoundTag tag = data.copyTag();
        String current = tag.getString(TICKET_TYPE_KEY);
        if (!current.isBlank()) return current;
        return legacyTicketType(tag.getString(LEGACY_MACHINE_KEY));
    }

    private static String legacyTicketType(String machineId) {
        if (machineId.endsWith("technical_machine_gacha")) return "technical_machine";
        if (machineId.endsWith("item_gacha")) return "item";
        if (machineId.endsWith("starter_gacha") || machineId.endsWith("pokemon_gacha")) return "pokemon";
        return "";
    }

    static int count(ServerPlayer player, GachaCatalog.Machine machine) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (matches(stack, machine)) total += stack.getCount();
        }
        Object storage = loadBag(player);
        if (storage instanceof Iterable<?> stacks) for (Object entry : stacks) {
            if (entry instanceof ItemStack stack && matches(stack, machine)) total += stack.getCount();
        }
        return total;
    }

    static boolean take(ServerPlayer player, GachaCatalog.Machine machine, int amount) {
        if (amount <= 0) return true;
        if (count(player, machine) < amount) return false;
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!matches(stack, machine)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
        player.getInventory().setChanged();
        Object storage = loadBag(player);
        boolean changed = false;
        if (storage instanceof Iterable<?> stacks) for (Object entry : stacks) {
            if (remaining <= 0) break;
            if (!(entry instanceof ItemStack stack) || !matches(stack, machine)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            changed = true;
        }
        if (changed) saveBag(player, storage);
        return remaining == 0;
    }

    private static boolean matches(ItemStack stack, GachaCatalog.Machine machine) {
        return machine.machine_type.equals(ticketType(stack));
    }

    private static Object loadBag(ServerPlayer player) {
        try {
            Class<?> storage = Class.forName("dev.buizz.cobbleventure.playermenu.BagStorage");
            return storage.getMethod("load", ServerPlayer.class).invoke(null, player);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void saveBag(ServerPlayer player, Object value) {
        if (value == null) return;
        try {
            Class<?> storage = Class.forName("dev.buizz.cobbleventure.playermenu.BagStorage");
            for (Method method : storage.getMethods()) {
                if (method.getName().equals("save") && method.getParameterCount() == 2) {
                    method.invoke(null, player, value);
                    return;
                }
            }
        } catch (ReflectiveOperationException ignored) {}
    }

    static void give(ServerPlayer player, GachaCatalog.Machine machine, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(remaining, CasinoItems.GACHA_TICKET.get().getDefaultMaxStackSize());
            ItemStack stack = create(machine, batch);
            if (!insertIntoBag(player, stack)) {
                if (!player.getInventory().add(stack)) player.drop(stack, false);
            }
            remaining -= batch;
        }
    }

    private static boolean insertIntoBag(ServerPlayer player, ItemStack stack) {
        try {
            Class<?> api = Class.forName("dev.buizz.cobbleventure.playermenu.BagApi");
            Method insert = api.getMethod("insert", ServerPlayer.class, ItemStack.class, boolean.class);
            Object result = insert.invoke(null, player, stack, true);
            return (boolean) result.getClass().getMethod("complete").invoke(result);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
