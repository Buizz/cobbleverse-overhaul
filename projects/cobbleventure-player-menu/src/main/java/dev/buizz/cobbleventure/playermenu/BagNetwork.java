package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.item.PokemonSelectingItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.buizz.cobbleventure.playermenu.client.PlayerMenuClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative bag storage, synchronization and item actions. */
public final class BagNetwork {
    private static final String VERSION = "7";
    private static final int VANILLA_HOTBAR_SIZE = 9;
    private static volatile ClientSnapshot clientSnapshot = new ClientSnapshot(
        emptySnapshot(), emptyShortcuts(), 0L
    );

    private BagNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BagNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(BagNetwork::onItemPickup);
        NeoForge.EVENT_BUS.addListener(BagNetwork::onServerTick);
        NeoForge.EVENT_BUS.addListener(BagCommands::register);
    }

    public static ClientSnapshot clientSnapshot() {
        return clientSnapshot;
    }

    public static int extendedSlotCount() {
        return BagStorage.SLOT_COUNT;
    }

    public static void requestSnapshot() {
        clientSnapshot = new ClientSnapshot(emptySnapshot(), emptyShortcuts(), clientSnapshot.revision() + 1L);
        PacketDistributor.sendToServer(new SnapshotRequestPayload());
    }

    public static void requestUse(boolean extended, int slot) {
        PacketDistributor.sendToServer(new UseItemPayload(extended, slot));
    }

    public static void requestShortcut(boolean extended, int slot, int shortcutSlot) {
        PacketDistributor.sendToServer(new ShortcutPayload(extended, slot, shortcutSlot));
    }

    public static void requestDiscard(boolean extended, int slot, int quantity) {
        PacketDistributor.sendToServer(new DiscardPayload(extended, slot, quantity));
    }

    public static void requestDrop(boolean extended, int slot, int quantity) {
        PacketDistributor.sendToServer(new DropPayload(extended, slot, quantity));
    }

    public static void requestGiveToPokemon(boolean extended, int slot, int partySlot) {
        PacketDistributor.sendToServer(new GiveToPokemonPayload(extended, slot, partySlot));
    }

    public static void requestUseOnPokemon(boolean extended, int slot, int partySlot) {
        PacketDistributor.sendToServer(new UseOnPokemonPayload(extended, slot, partySlot));
    }

    public static void requestUseShortcut(int shortcutSlot) {
        PacketDistributor.sendToServer(new UseShortcutPayload(shortcutSlot));
    }

    public static void requestUsePokenav() {
        PacketDistributor.sendToServer(new UsePokenavPayload());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(SnapshotRequestPayload.TYPE, SnapshotRequestPayload.STREAM_CODEC, BagNetwork::handleSnapshotRequest);
        registrar.playToClient(SnapshotPayload.TYPE, SnapshotPayload.STREAM_CODEC, BagNetwork::handleSnapshot);
        registrar.playToServer(UseItemPayload.TYPE, UseItemPayload.STREAM_CODEC, BagNetwork::handleUseItem);
        registrar.playToClient(ClientUsePayload.TYPE, ClientUsePayload.STREAM_CODEC, BagNetwork::handleClientUse);
        registrar.playToServer(ShortcutPayload.TYPE, ShortcutPayload.STREAM_CODEC, BagNetwork::handleShortcut);
        registrar.playToServer(UseShortcutPayload.TYPE, UseShortcutPayload.STREAM_CODEC, BagNetwork::handleUseShortcut);
        registrar.playToServer(UsePokenavPayload.TYPE, UsePokenavPayload.STREAM_CODEC, BagNetwork::handleUsePokenav);
        registrar.playToServer(DiscardPayload.TYPE, DiscardPayload.STREAM_CODEC, BagNetwork::handleDiscard);
        registrar.playToServer(DropPayload.TYPE, DropPayload.STREAM_CODEC, BagNetwork::handleDrop);
        registrar.playToServer(GiveToPokemonPayload.TYPE, GiveToPokemonPayload.STREAM_CODEC,
            BagNetwork::handleGiveToPokemon);
        registrar.playToServer(UseOnPokemonPayload.TYPE, UseOnPokemonPayload.STREAM_CODEC,
            BagNetwork::handleUseOnPokemon);
    }

    private static void handleSnapshotRequest(SnapshotRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) sync(player, BagStorage.load(player));
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        List<ItemStack> copy = new ArrayList<>(payload.slots().size());
        for (ItemStack stack : payload.slots()) copy.add(stack.copy());
        List<ItemStack> shortcuts = new ArrayList<>(payload.shortcuts().size());
        for (ItemStack stack : payload.shortcuts()) shortcuts.add(stack.copy());
        clientSnapshot = new ClientSnapshot(
            List.copyOf(copy), List.copyOf(shortcuts), clientSnapshot.revision() + 1L
        );
    }

    private static void handleUseItem(UseItemPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        if (!validSlot(payload.extended(), payload.slot())) return;
        ItemStack source = getStack(player, storage, payload.extended(), payload.slot());
        if (source.isEmpty()) return;
        PacketDistributor.sendToPlayer(player, new ClientUsePayload(source.copyWithCount(1)));

        if (!payload.extended()) {
            useInventorySlot(player, payload.slot());
        } else {
            useExtendedSlot(player, storage, payload.slot());
        }
        sync(player, storage);
    }

    private static void handleClientUse(ClientUsePayload payload, IPayloadContext context) {
        PlayerMenuClient.previewBagItemUse(payload.stack());
    }

    private static void handleShortcut(ShortcutPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !validSlot(payload.extended(), payload.slot())
            || payload.shortcutSlot() < 0 || payload.shortcutSlot() >= BagStorage.SHORTCUT_COUNT) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack source = getStack(player, storage, payload.extended(), payload.slot());
        if (source.isEmpty()) return;
        NonNullList<ItemStack> shortcuts = BagStorage.loadShortcuts(player);
        shortcuts.set(payload.shortcutSlot(), source.copyWithCount(1));
        BagStorage.saveShortcuts(player, shortcuts);
        syncVanillaHotbarShortcuts(player, storage, shortcuts);
        sync(player, storage);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 5 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            BagConditionTracker.sync(player);
            NonNullList<ItemStack> storage = BagStorage.load(player);
            NonNullList<ItemStack> shortcuts = BagStorage.loadShortcuts(player);
            boolean changed = BagStorage.normalize(storage);
            changed |= refillInventoryStacks(player.getInventory(), storage);
            boolean shortcutsChanged = syncVanillaHotbarShortcuts(player, storage, shortcuts);
            if (changed && !shortcutsChanged) {
                BagStorage.save(player, storage);
                markInventoryChanged(player);
            }
            if (!changed && !shortcutsChanged) continue;
            sync(player, storage);
        }
    }

    /** Keeps every existing vanilla inventory stack full whenever the extended bag has supplies. */
    private static boolean refillInventoryStacks(Inventory inventory, List<ItemStack> storage) {
        boolean changed = false;
        for (int inventorySlot = 0; inventorySlot < 36; inventorySlot++) {
            ItemStack target = inventory.getItem(inventorySlot);
            if (target.isEmpty() || target.getCount() >= target.getMaxStackSize()) continue;
            int needed = target.getMaxStackSize() - target.getCount();
            for (int storageSlot = 0; storageSlot < storage.size() && needed > 0; storageSlot++) {
                ItemStack supply = storage.get(storageSlot);
                if (!ItemStack.isSameItemSameComponents(target, supply)) continue;
                int moved = Math.min(needed, supply.getCount());
                target.grow(moved);
                supply.shrink(moved);
                needed -= moved;
                changed = true;
                if (supply.isEmpty()) storage.set(storageSlot, ItemStack.EMPTY);
            }
        }
        if (changed) BagStorage.normalize(storage);
        return changed;
    }

    /** Mirrors shortcut slots 1-9 into Minecraft's real hotbar and keeps their stacks replenished. */
    private static boolean syncVanillaHotbarShortcuts(ServerPlayer player,
                                                       NonNullList<ItemStack> storage,
                                                       NonNullList<ItemStack> shortcuts) {
        Inventory inventory = player.getInventory();
        boolean changed = false;
        for (int hotbarSlot = 0; hotbarSlot < VANILLA_HOTBAR_SIZE; hotbarSlot++) {
            ItemStack prototype = shortcuts.get(hotbarSlot);
            if (prototype.isEmpty()) continue;

            ItemStack target = inventory.getItem(hotbarSlot);
            boolean compatible = ItemStack.isSameItemSameComponents(target, prototype)
                || !target.isEmpty() && target.is(prototype.getItem()) && target.getMaxStackSize() == 1;
            if (compatible) {
                int needed = Math.max(0, target.getMaxStackSize() - target.getCount());
                int moved = pullShortcutSupplies(inventory, storage, shortcuts, hotbarSlot, prototype, needed);
                if (moved > 0) {
                    target.grow(moved);
                    changed = true;
                }
                continue;
            }

            int available = countShortcutSupplies(inventory, storage, shortcuts, hotbarSlot, prototype);
            if (available <= 0) continue;
            ItemStack displaced = target.copy();
            inventory.setItem(hotbarSlot, ItemStack.EMPTY);
            int moved = pullShortcutSupplies(
                inventory, storage, shortcuts, hotbarSlot, prototype, prototype.getMaxStackSize()
            );
            if (moved > 0) inventory.setItem(hotbarSlot, prototype.copyWithCount(moved));
            if (!displaced.isEmpty()) storeDisplacedStack(player, storage, displaced);
            changed = true;
        }
        if (!changed) return false;
        BagStorage.save(player, storage);
        markInventoryChanged(player);
        return true;
    }

    private static int countShortcutSupplies(Inventory inventory, List<ItemStack> storage,
                                             List<ItemStack> shortcuts, int targetSlot,
                                             ItemStack prototype) {
        int count = 0;
        for (int slot = VANILLA_HOTBAR_SIZE; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, prototype)) count += stack.getCount();
        }
        for (ItemStack stack : storage) {
            if (ItemStack.isSameItemSameComponents(stack, prototype)) count += stack.getCount();
        }
        for (int slot = 0; slot < VANILLA_HOTBAR_SIZE; slot++) {
            if (slot == targetSlot || !shortcuts.get(slot).isEmpty()) continue;
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, prototype)) count += stack.getCount();
        }
        return count;
    }

    private static int pullShortcutSupplies(Inventory inventory, List<ItemStack> storage,
                                            List<ItemStack> shortcuts, int targetSlot,
                                            ItemStack prototype, int requested) {
        int moved = 0;
        for (int slot = VANILLA_HOTBAR_SIZE; slot < 36 && moved < requested; slot++) {
            moved += takeMatching(inventory.getItem(slot), prototype, requested - moved);
            if (inventory.getItem(slot).isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < storage.size() && moved < requested; slot++) {
            moved += takeMatching(storage.get(slot), prototype, requested - moved);
            if (storage.get(slot).isEmpty()) storage.set(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < VANILLA_HOTBAR_SIZE && moved < requested; slot++) {
            if (slot == targetSlot || !shortcuts.get(slot).isEmpty()) continue;
            moved += takeMatching(inventory.getItem(slot), prototype, requested - moved);
            if (inventory.getItem(slot).isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
        }
        return moved;
    }

    private static int takeMatching(ItemStack source, ItemStack prototype, int requested) {
        if (requested <= 0 || !ItemStack.isSameItemSameComponents(source, prototype)) return 0;
        int moved = Math.min(requested, source.getCount());
        source.shrink(moved);
        return moved;
    }

    private static void storeDisplacedStack(ServerPlayer player, List<ItemStack> storage, ItemStack stack) {
        for (ItemStack stored : storage) {
            if (stack.isEmpty()) return;
            if (!ItemStack.isSameItemSameComponents(stored, stack) || stored.getCount() >= stored.getMaxStackSize()) continue;
            int moved = Math.min(stack.getCount(), stored.getMaxStackSize() - stored.getCount());
            stored.grow(moved);
            stack.shrink(moved);
        }
        for (int slot = 0; slot < storage.size() && !stack.isEmpty(); slot++) {
            if (!storage.get(slot).isEmpty()) continue;
            int moved = Math.min(stack.getCount(), stack.getMaxStackSize());
            storage.set(slot, stack.copyWithCount(moved));
            stack.shrink(moved);
        }
        if (!stack.isEmpty()) player.drop(stack, false);
    }

    private static void handleUseShortcut(UseShortcutPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || payload.shortcutSlot() < 0 || payload.shortcutSlot() >= BagStorage.SHORTCUT_COUNT) return;
        ItemStack prototype = BagStorage.loadShortcuts(player).get(payload.shortcutSlot());
        if (prototype.isEmpty()) return;

        NonNullList<ItemStack> storage = BagStorage.load(player);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (!ItemStack.isSameItemSameComponents(inventory.getItem(slot), prototype)) continue;
            useRefilledShortcut(player, storage, false, slot, prototype);
            return;
        }
        for (int slot = 0; slot < storage.size(); slot++) {
            if (!ItemStack.isSameItemSameComponents(storage.get(slot), prototype)) continue;
            useRefilledShortcut(player, storage, true, slot, prototype);
            return;
        }
    }

    private static void useRefilledShortcut(ServerPlayer player, NonNullList<ItemStack> storage,
                                            boolean extended, int sourceSlot, ItemStack prototype) {
        refillShortcutStack(player, storage, extended, sourceSlot, prototype);
        if (extended) useExtendedSlot(player, storage, sourceSlot);
        else useInventorySlot(player, sourceSlot);
        refillShortcutStack(player, storage, extended, sourceSlot, prototype);
        BagStorage.save(player, storage);
        markInventoryChanged(player);
        sync(player, storage);
    }

    /** Consolidates matching supplies so the logical quick slot always exposes one full vanilla stack. */
    private static void refillShortcutStack(ServerPlayer player, NonNullList<ItemStack> storage,
                                            boolean extended, int sourceSlot, ItemStack prototype) {
        ItemStack target = getStack(player, storage, extended, sourceSlot);
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, prototype)) return;
        int current = target.isEmpty() ? 0 : target.getCount();
        int needed = prototype.getMaxStackSize() - current;
        if (needed <= 0) return;

        int moved = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36 && moved < needed; slot++) {
            if (!extended && slot == sourceSlot) continue;
            ItemStack supply = inventory.getItem(slot);
            if (!ItemStack.isSameItemSameComponents(supply, prototype)) continue;
            int transfer = Math.min(needed - moved, supply.getCount());
            supply.shrink(transfer);
            moved += transfer;
            if (supply.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < storage.size() && moved < needed; slot++) {
            if (extended && slot == sourceSlot) continue;
            ItemStack supply = storage.get(slot);
            if (!ItemStack.isSameItemSameComponents(supply, prototype)) continue;
            int transfer = Math.min(needed - moved, supply.getCount());
            supply.shrink(transfer);
            moved += transfer;
            if (supply.isEmpty()) storage.set(slot, ItemStack.EMPTY);
        }
        if (moved <= 0) return;
        if (target.isEmpty()) {
            setStack(player, storage, extended, sourceSlot, prototype.copyWithCount(moved));
        } else {
            target.grow(moved);
        }
    }

    private static void handleUsePokenav(UsePokenavPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (!isPokenav(inventory.getItem(slot))) continue;
            useInventorySlot(player, slot);
            sync(player, storage);
            return;
        }
        for (int slot = 0; slot < storage.size(); slot++) {
            if (!isPokenav(storage.get(slot))) continue;
            useExtendedSlot(player, storage, slot);
            sync(player, storage);
            return;
        }
        player.displayClientMessage(Component.translatable(
            "screen.cobbleventure_player_menu.status.missing_pokenav"
        ), true);
    }

    private static void handleDiscard(DiscardPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !validSlot(payload.extended(), payload.slot())
            || payload.quantity() <= 0) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack stack = getStack(player, storage, payload.extended(), payload.slot());
        if (stack.isEmpty()) return;
        int remaining = payload.quantity();
        if (!payload.extended()) {
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            if (stack.isEmpty()) setStack(player, storage, false, payload.slot(), ItemStack.EMPTY);
        } else {
            ItemStack prototype = stack.copyWithCount(1);
            for (int slot = 0; slot < storage.size() && remaining > 0; slot++) {
                ItemStack stored = storage.get(slot);
                if (!ItemStack.isSameItemSameComponents(stored, prototype)) continue;
                int removed = Math.min(remaining, stored.getCount());
                stored.shrink(removed);
                remaining -= removed;
                if (stored.isEmpty()) storage.set(slot, ItemStack.EMPTY);
            }
        }
        finishMutation(player, storage, payload.extended());
    }

    private static void handleDrop(DropPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !validSlot(payload.extended(), payload.slot())
            || payload.quantity() <= 0) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack dropped = takeSelectedItems(player, storage, payload.extended(), payload.slot(), payload.quantity());
        if (dropped.isEmpty()) return;
        player.drop(dropped, false, true);
        finishMutation(player, storage, payload.extended());
    }

    private static void handleGiveToPokemon(GiveToPokemonPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !validSlot(payload.extended(), payload.slot())
            || payload.partySlot() < 0 || payload.partySlot() >= 6) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack source = getStack(player, storage, payload.extended(), payload.slot());
        Pokemon pokemon = Cobblemon.INSTANCE.getStorage().getParty(player).get(payload.partySlot());
        if (source.isEmpty() || pokemon == null) return;

        ItemStack offered = source.copy();
        int before = offered.getCount();
        ItemStack returned = pokemon.swapHeldItem(offered, true, true);
        if (offered.getCount() != before - 1) {
            player.displayClientMessage(Component.translatable(
                "screen.cobbleventure_player_menu.bag.give_failed"
            ), true);
            return;
        }

        source.shrink(1);
        if (source.isEmpty()) setStack(player, storage, payload.extended(), payload.slot(), ItemStack.EMPTY);
        if (!returned.isEmpty()) {
            ItemStack remainder = returned.copy();
            BagStorage.add(storage, remainder);
            if (!remainder.isEmpty()) player.drop(remainder, false, true);
        }
        finishMutation(player, storage, true);
        player.displayClientMessage(Component.translatable(
            "screen.cobbleventure_player_menu.bag.given_to_pokemon",
            pokemon.getDisplayName(false)
        ), true);
    }

    private static void handleUseOnPokemon(UseOnPokemonPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !validSlot(payload.extended(), payload.slot())
            || payload.partySlot() < 0 || payload.partySlot() >= 6
            || PlayerExtensionsKt.getBattleState(player) != null) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack source = getStack(player, storage, payload.extended(), payload.slot());
        Pokemon pokemon = Cobblemon.INSTANCE.getStorage().getParty(player).get(payload.partySlot());
        if (source.isEmpty() || pokemon == null
            || !(source.getItem() instanceof PokemonSelectingItem selectingItem)
            || !selectingItem.canUseOnPokemon(source, pokemon)) return;

        ItemStack result = selectingItem.applyToPokemon(player, source, pokemon).getObject();
        setStack(player, storage, payload.extended(), payload.slot(), result);
        finishMutation(player, storage, payload.extended());
    }

    private static ItemStack takeSelectedItems(ServerPlayer player, NonNullList<ItemStack> storage,
                                               boolean extended, int slot, int quantity) {
        ItemStack source = getStack(player, storage, extended, slot);
        if (source.isEmpty()) return ItemStack.EMPTY;
        int requested = Math.min(quantity, extended ? Integer.MAX_VALUE : source.getCount());
        ItemStack result = source.copyWithCount(0);
        ItemStack prototype = source.copyWithCount(1);
        if (!extended) {
            int removed = Math.min(requested, source.getCount());
            source.shrink(removed);
            result.setCount(removed);
            if (source.isEmpty()) setStack(player, storage, false, slot, ItemStack.EMPTY);
            return result;
        }
        for (int index = 0; index < storage.size() && result.getCount() < requested; index++) {
            ItemStack stored = storage.get(index);
            if (!ItemStack.isSameItemSameComponents(stored, prototype)) continue;
            int removed = Math.min(requested - result.getCount(), stored.getCount());
            stored.shrink(removed);
            result.grow(removed);
            if (stored.isEmpty()) storage.set(index, ItemStack.EMPTY);
        }
        return result;
    }

    private static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        ItemEntity entity = event.getItemEntity();
        ItemStack worldStack = entity.getItem();
        var pickedUpItem = worldStack.getItem();
        if (entity.hasPickUpDelay() || entity.getTarget() != null && !entity.getTarget().equals(player.getUUID())
            || worldStack.isEmpty()) return;

        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack remainder = worldStack.copy();
        int moved = BagStorage.add(storage, remainder);
        if (moved <= 0) {
            // 가방이 가득 찬 경우에도 기본 인벤토리나 핫바로 우회하지 않는다.
            event.setCanPickup(TriState.FALSE);
            return;
        }

        BagStorage.save(player, storage);
        syncExternalMutation(player, storage);
        worldStack.shrink(moved);
        player.take(entity, moved);
        player.awardStat(Stats.ITEM_PICKED_UP.get(pickedUpItem), moved);
        player.onItemPickup(entity);
        if (worldStack.isEmpty()) entity.discard();
        event.setCanPickup(TriState.FALSE);
    }

    private static void useInventorySlot(ServerPlayer player, int sourceIndex) {
        Inventory inventory = player.getInventory();
        int handIndex = inventory.selected;
        if (sourceIndex == handIndex) {
            useAndFinish(player);
        } else {
            ItemStack originalHand = inventory.getItem(handIndex);
            ItemStack sourceStack = inventory.getItem(sourceIndex);
            inventory.setItem(handIndex, sourceStack);
            inventory.setItem(sourceIndex, originalHand);
            try {
                useAndFinish(player);
            } finally {
                ItemStack result = inventory.getItem(handIndex);
                inventory.setItem(handIndex, originalHand);
                inventory.setItem(sourceIndex, result);
            }
        }
        markInventoryChanged(player);
    }

    private static void useExtendedSlot(ServerPlayer player, NonNullList<ItemStack> storage, int sourceIndex) {
        Inventory inventory = player.getInventory();
        int handIndex = inventory.selected;
        ItemStack originalHand = inventory.getItem(handIndex);
        inventory.setItem(handIndex, storage.get(sourceIndex));
        try {
            useAndFinish(player);
        } finally {
            ItemStack result = inventory.getItem(handIndex);
            inventory.setItem(handIndex, originalHand);
            storage.set(sourceIndex, result);
        }
        BagStorage.save(player, storage);
        markInventoryChanged(player);
    }

    private static void useAndFinish(ServerPlayer player) {
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            ItemStack result = player.getMainHandItem().finishUsingItem(player.level(), player);
            player.stopUsingItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, result);
        }
    }

    private static ItemStack getStack(ServerPlayer player, List<ItemStack> storage, boolean extended, int slot) {
        return extended ? storage.get(slot) : player.getInventory().getItem(slot);
    }

    private static void setStack(ServerPlayer player, List<ItemStack> storage, boolean extended, int slot, ItemStack stack) {
        if (extended) storage.set(slot, stack);
        else player.getInventory().setItem(slot, stack);
    }

    private static boolean validSlot(boolean extended, int slot) {
        return slot >= 0 && slot < (extended ? BagStorage.SLOT_COUNT : 36);
    }

    private static boolean isPokenav(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("cobblenav") && id.getPath().startsWith("pokenav_item");
    }

    private static void finishMutation(ServerPlayer player, List<ItemStack> storage, boolean storageChanged) {
        if (storageChanged) BagStorage.save(player, storage);
        BagConditionTracker.sync(player);
        markInventoryChanged(player);
        sync(player, storage);
    }

    private static void markInventoryChanged(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }

    static void syncExternalMutation(ServerPlayer player, List<ItemStack> storage) {
        BagConditionTracker.sync(player);
        markInventoryChanged(player);
        sync(player, storage);
    }

    private static void sync(ServerPlayer player, List<ItemStack> storage) {
        List<ItemStack> copy = new ArrayList<>(BagStorage.SLOT_COUNT);
        for (ItemStack stack : storage) copy.add(stack.copy());
        List<ItemStack> shortcuts = new ArrayList<>(BagStorage.SHORTCUT_COUNT);
        for (ItemStack stack : BagStorage.loadShortcuts(player)) shortcuts.add(stack.copy());
        PacketDistributor.sendToPlayer(player, new SnapshotPayload(copy, shortcuts));
    }

    private static List<ItemStack> emptySnapshot() {
        List<ItemStack> result = new ArrayList<>(BagStorage.SLOT_COUNT);
        for (int index = 0; index < BagStorage.SLOT_COUNT; index++) result.add(ItemStack.EMPTY);
        return List.copyOf(result);
    }

    private static List<ItemStack> emptyShortcuts() {
        List<ItemStack> result = new ArrayList<>(BagStorage.SHORTCUT_COUNT);
        for (int index = 0; index < BagStorage.SHORTCUT_COUNT; index++) result.add(ItemStack.EMPTY);
        return List.copyOf(result);
    }

    public record ClientSnapshot(List<ItemStack> slots, List<ItemStack> shortcuts, long revision) {}

    public record SnapshotRequestPayload() implements CustomPacketPayload {
        public static final Type<SnapshotRequestPayload> TYPE = new Type<>(id("bag_snapshot_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new SnapshotRequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SnapshotPayload(List<ItemStack> slots, List<ItemStack> shortcuts) implements CustomPacketPayload {
        public static final Type<SnapshotPayload> TYPE = new Type<>(id("bag_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(SnapshotPayload::write, SnapshotPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            int occupied = 0;
            for (ItemStack stack : slots) if (!stack.isEmpty()) occupied++;
            buffer.writeVarInt(occupied);
            for (int slot = 0; slot < slots.size(); slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty()) continue;
                buffer.writeVarInt(slot);
                ItemStack.STREAM_CODEC.encode(buffer, stack);
            }
            for (int index = 0; index < BagStorage.SHORTCUT_COUNT; index++) {
                ItemStack stack = index < shortcuts.size() ? shortcuts.get(index) : ItemStack.EMPTY;
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
            }
        }
        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            int occupied = Math.max(0, Math.min(BagStorage.SLOT_COUNT, buffer.readVarInt()));
            List<ItemStack> slots = new ArrayList<>(emptySnapshot());
            for (int index = 0; index < occupied; index++) {
                int slot = buffer.readVarInt();
                ItemStack stack = ItemStack.STREAM_CODEC.decode(buffer);
                if (slot >= 0 && slot < BagStorage.SLOT_COUNT) slots.set(slot, stack);
            }
            List<ItemStack> shortcuts = new ArrayList<>(BagStorage.SHORTCUT_COUNT);
            for (int index = 0; index < BagStorage.SHORTCUT_COUNT; index++) {
                shortcuts.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            return new SnapshotPayload(List.copyOf(slots), List.copyOf(shortcuts));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UseItemPayload(boolean extended, int slot) implements CustomPacketPayload {
        public static final Type<UseItemPayload> TYPE = new Type<>(id("bag_use_item"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UseItemPayload> STREAM_CODEC = codec(
            UseItemPayload::new, UseItemPayload::extended, UseItemPayload::slot
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClientUsePayload(ItemStack stack) implements CustomPacketPayload {
        public static final Type<ClientUsePayload> TYPE = new Type<>(id("bag_client_use"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientUsePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, value) -> ItemStack.STREAM_CODEC.encode(buffer, value.stack),
                buffer -> new ClientUsePayload(ItemStack.STREAM_CODEC.decode(buffer))
            );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ShortcutPayload(boolean extended, int slot, int shortcutSlot) implements CustomPacketPayload {
        public static final Type<ShortcutPayload> TYPE = new Type<>(id("bag_shortcut"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ShortcutPayload> STREAM_CODEC =
            StreamCodec.ofMember(ShortcutPayload::write, ShortcutPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(extended); buffer.writeVarInt(slot); buffer.writeVarInt(shortcutSlot);
        }
        private static ShortcutPayload read(RegistryFriendlyByteBuf buffer) {
            return new ShortcutPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UseShortcutPayload(int shortcutSlot) implements CustomPacketPayload {
        public static final Type<UseShortcutPayload> TYPE = new Type<>(id("bag_use_shortcut"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UseShortcutPayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, value) -> buffer.writeVarInt(value.shortcutSlot),
                buffer -> new UseShortcutPayload(buffer.readVarInt())
            );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UsePokenavPayload() implements CustomPacketPayload {
        public static final Type<UsePokenavPayload> TYPE = new Type<>(id("use_pokenav"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UsePokenavPayload> STREAM_CODEC =
            StreamCodec.unit(new UsePokenavPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DiscardPayload(boolean extended, int slot, int quantity) implements CustomPacketPayload {
        public static final Type<DiscardPayload> TYPE = new Type<>(id("bag_discard"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DiscardPayload> STREAM_CODEC =
            StreamCodec.ofMember(DiscardPayload::write, DiscardPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(extended); buffer.writeVarInt(slot); buffer.writeVarInt(quantity);
        }
        private static DiscardPayload read(RegistryFriendlyByteBuf buffer) {
            return new DiscardPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DropPayload(boolean extended, int slot, int quantity) implements CustomPacketPayload {
        public static final Type<DropPayload> TYPE = new Type<>(id("bag_drop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DropPayload> STREAM_CODEC =
            StreamCodec.ofMember(DropPayload::write, DropPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(extended); buffer.writeVarInt(slot); buffer.writeVarInt(quantity);
        }
        private static DropPayload read(RegistryFriendlyByteBuf buffer) {
            return new DropPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record GiveToPokemonPayload(boolean extended, int slot, int partySlot) implements CustomPacketPayload {
        public static final Type<GiveToPokemonPayload> TYPE = new Type<>(id("bag_give_to_pokemon"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GiveToPokemonPayload> STREAM_CODEC =
            StreamCodec.ofMember(GiveToPokemonPayload::write, GiveToPokemonPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(extended); buffer.writeVarInt(slot); buffer.writeVarInt(partySlot);
        }
        private static GiveToPokemonPayload read(RegistryFriendlyByteBuf buffer) {
            return new GiveToPokemonPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UseOnPokemonPayload(boolean extended, int slot, int partySlot) implements CustomPacketPayload {
        public static final Type<UseOnPokemonPayload> TYPE = new Type<>(id("bag_use_on_pokemon"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UseOnPokemonPayload> STREAM_CODEC =
            StreamCodec.ofMember(UseOnPokemonPayload::write, UseOnPokemonPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(extended); buffer.writeVarInt(slot); buffer.writeVarInt(partySlot);
        }
        private static UseOnPokemonPayload read(RegistryFriendlyByteBuf buffer) {
            return new UseOnPokemonPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(
        SlotPayloadFactory<T> factory, java.util.function.Predicate<T> extended,
        java.util.function.ToIntFunction<T> slot) {
        return StreamCodec.of(
            (buffer, value) -> { buffer.writeBoolean(extended.test(value)); buffer.writeVarInt(slot.applyAsInt(value)); },
            buffer -> factory.create(buffer.readBoolean(), buffer.readVarInt())
        );
    }

    @FunctionalInterface
    private interface SlotPayloadFactory<T> { T create(boolean extended, int slot); }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }
}
