package dev.buizz.cobbleventure.playermenu;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative bag storage, synchronization and item actions. */
public final class BagNetwork {
    private static final String VERSION = "2";
    private static volatile ClientSnapshot clientSnapshot = new ClientSnapshot(emptySnapshot(), 0L);

    private BagNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BagNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(BagNetwork::onItemPickup);
        NeoForge.EVENT_BUS.addListener(BagCommands::register);
    }

    public static ClientSnapshot clientSnapshot() {
        return clientSnapshot;
    }

    public static void requestSnapshot() {
        clientSnapshot = new ClientSnapshot(emptySnapshot(), clientSnapshot.revision() + 1L);
        PacketDistributor.sendToServer(new SnapshotRequestPayload());
    }

    public static void requestUse(boolean extended, int slot) {
        PacketDistributor.sendToServer(new UseItemPayload(extended, slot));
    }

    public static void requestMove(boolean sourceExtended, int sourceSlot,
                                   boolean targetExtended, int targetSlot, boolean singleItem) {
        PacketDistributor.sendToServer(new MoveItemPayload(
            sourceExtended, sourceSlot, targetExtended, targetSlot, singleItem
        ));
    }

    public static void requestShortcut(boolean extended, int slot, int hotbarSlot) {
        PacketDistributor.sendToServer(new ShortcutPayload(extended, slot, hotbarSlot));
    }

    public static void requestDiscard(boolean extended, int slot) {
        PacketDistributor.sendToServer(new DiscardPayload(extended, slot));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(SnapshotRequestPayload.TYPE, SnapshotRequestPayload.STREAM_CODEC, BagNetwork::handleSnapshotRequest);
        registrar.playToClient(SnapshotPayload.TYPE, SnapshotPayload.STREAM_CODEC, BagNetwork::handleSnapshot);
        registrar.playToServer(UseItemPayload.TYPE, UseItemPayload.STREAM_CODEC, BagNetwork::handleUseItem);
        registrar.playToServer(MoveItemPayload.TYPE, MoveItemPayload.STREAM_CODEC, BagNetwork::handleMoveItem);
        registrar.playToServer(ShortcutPayload.TYPE, ShortcutPayload.STREAM_CODEC, BagNetwork::handleShortcut);
        registrar.playToServer(DiscardPayload.TYPE, DiscardPayload.STREAM_CODEC, BagNetwork::handleDiscard);
    }

    private static void handleSnapshotRequest(SnapshotRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) sync(player, BagStorage.load(player));
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        List<ItemStack> copy = new ArrayList<>(payload.slots().size());
        for (ItemStack stack : payload.slots()) copy.add(stack.copy());
        clientSnapshot = new ClientSnapshot(List.copyOf(copy), clientSnapshot.revision() + 1L);
    }

    private static void handleUseItem(UseItemPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        if (!validSlot(payload.extended(), payload.slot())) return;
        ItemStack source = getStack(player, storage, payload.extended(), payload.slot());
        if (source.isEmpty()) return;

        if (!payload.extended()) {
            useInventorySlot(player, payload.slot());
        } else {
            int handIndex = player.getInventory().selected;
            ItemStack originalHand = player.getInventory().getItem(handIndex);
            player.getInventory().setItem(handIndex, source);
            try {
                useAndFinish(player);
            } finally {
                ItemStack result = player.getInventory().getItem(handIndex);
                player.getInventory().setItem(handIndex, originalHand);
                storage.set(payload.slot(), result);
            }
            BagStorage.save(player, storage);
            markInventoryChanged(player);
        }
        sync(player, storage);
    }

    private static void handleMoveItem(MoveItemPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !validSlot(payload.sourceExtended(), payload.sourceSlot())
            || !validSlot(payload.targetExtended(), payload.targetSlot())
            || payload.sourceExtended() == payload.targetExtended() && payload.sourceSlot() == payload.targetSlot()) {
            return;
        }
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack source = getStack(player, storage, payload.sourceExtended(), payload.sourceSlot());
        ItemStack target = getStack(player, storage, payload.targetExtended(), payload.targetSlot());
        if (source.isEmpty()) return;

        if (payload.singleItem()) {
            if (target.isEmpty()) {
                setStack(player, storage, payload.targetExtended(), payload.targetSlot(), source.copyWithCount(1));
                source.shrink(1);
                setStack(player, storage, payload.sourceExtended(), payload.sourceSlot(), source);
            } else if (ItemStack.isSameItemSameComponents(source, target) && target.getCount() < target.getMaxStackSize()) {
                target.grow(1);
                source.shrink(1);
                setStack(player, storage, payload.sourceExtended(), payload.sourceSlot(), source);
            } else {
                return;
            }
        } else if (target.isEmpty()) {
            setStack(player, storage, payload.targetExtended(), payload.targetSlot(), source);
            setStack(player, storage, payload.sourceExtended(), payload.sourceSlot(), ItemStack.EMPTY);
        } else if (ItemStack.isSameItemSameComponents(source, target)
            && target.getCount() < target.getMaxStackSize()) {
            int moved = Math.min(source.getCount(), target.getMaxStackSize() - target.getCount());
            target.grow(moved);
            source.shrink(moved);
            setStack(player, storage, payload.sourceExtended(), payload.sourceSlot(), source);
        } else {
            setStack(player, storage, payload.sourceExtended(), payload.sourceSlot(), target);
            setStack(player, storage, payload.targetExtended(), payload.targetSlot(), source);
        }

        finishMutation(player, storage, payload.sourceExtended() || payload.targetExtended());
    }

    private static void handleShortcut(ShortcutPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !validSlot(payload.extended(), payload.slot())
            || payload.hotbarSlot() < 0 || payload.hotbarSlot() >= 9) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack source = getStack(player, storage, payload.extended(), payload.slot());
        if (source.isEmpty()) return;
        ItemStack hotbar = player.getInventory().getItem(payload.hotbarSlot());
        setStack(player, storage, payload.extended(), payload.slot(), hotbar);
        player.getInventory().setItem(payload.hotbarSlot(), source);
        finishMutation(player, storage, payload.extended());
    }

    private static void handleDiscard(DiscardPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !validSlot(payload.extended(), payload.slot())) return;
        NonNullList<ItemStack> storage = BagStorage.load(player);
        ItemStack stack = getStack(player, storage, payload.extended(), payload.slot());
        if (stack.isEmpty()) return;
        setStack(player, storage, payload.extended(), payload.slot(), ItemStack.EMPTY);
        player.drop(stack, false);
        finishMutation(player, storage, payload.extended());
    }

    private static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        ItemEntity entity = event.getItemEntity();
        ItemStack worldStack = entity.getItem();
        var pickedUpItem = worldStack.getItem();
        Inventory inventory = player.getInventory();
        if (entity.hasPickUpDelay() || entity.getTarget() != null && !entity.getTarget().equals(player.getUUID())
            || inventory.getSlotWithRemainingSpace(worldStack) >= 0 || inventory.getFreeSlot() >= 0) return;

        BagApi.InsertResult result = BagApi.insert(player, worldStack, false);
        int moved = result.inserted();
        if (moved <= 0) return;
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

    private static void finishMutation(ServerPlayer player, List<ItemStack> storage, boolean storageChanged) {
        if (storageChanged) BagStorage.save(player, storage);
        markInventoryChanged(player);
        sync(player, storage);
    }

    private static void markInventoryChanged(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }

    static void syncExternalMutation(ServerPlayer player, List<ItemStack> storage) {
        markInventoryChanged(player);
        sync(player, storage);
    }

    private static void sync(ServerPlayer player, List<ItemStack> storage) {
        List<ItemStack> copy = new ArrayList<>(BagStorage.SLOT_COUNT);
        for (ItemStack stack : storage) copy.add(stack.copy());
        PacketDistributor.sendToPlayer(player, new SnapshotPayload(copy));
    }

    private static List<ItemStack> emptySnapshot() {
        List<ItemStack> result = new ArrayList<>(BagStorage.SLOT_COUNT);
        for (int index = 0; index < BagStorage.SLOT_COUNT; index++) result.add(ItemStack.EMPTY);
        return List.copyOf(result);
    }

    public record ClientSnapshot(List<ItemStack> slots, long revision) {}

    public record SnapshotRequestPayload() implements CustomPacketPayload {
        public static final Type<SnapshotRequestPayload> TYPE = new Type<>(id("bag_snapshot_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new SnapshotRequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SnapshotPayload(List<ItemStack> slots) implements CustomPacketPayload {
        public static final Type<SnapshotPayload> TYPE = new Type<>(id("bag_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(SnapshotPayload::write, SnapshotPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(slots.size());
            for (ItemStack stack : slots) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        }
        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            int size = Math.max(0, Math.min(BagStorage.SLOT_COUNT, buffer.readVarInt()));
            List<ItemStack> slots = new ArrayList<>(BagStorage.SLOT_COUNT);
            for (int index = 0; index < size; index++) slots.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            while (slots.size() < BagStorage.SLOT_COUNT) slots.add(ItemStack.EMPTY);
            return new SnapshotPayload(List.copyOf(slots));
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

    public record MoveItemPayload(boolean sourceExtended, int sourceSlot, boolean targetExtended,
                                  int targetSlot, boolean singleItem) implements CustomPacketPayload {
        public static final Type<MoveItemPayload> TYPE = new Type<>(id("bag_move_item"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MoveItemPayload> STREAM_CODEC =
            StreamCodec.ofMember(MoveItemPayload::write, MoveItemPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(sourceExtended); buffer.writeVarInt(sourceSlot);
            buffer.writeBoolean(targetExtended); buffer.writeVarInt(targetSlot); buffer.writeBoolean(singleItem);
        }
        private static MoveItemPayload read(RegistryFriendlyByteBuf buffer) {
            return new MoveItemPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readBoolean(),
                buffer.readVarInt(), buffer.readBoolean());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ShortcutPayload(boolean extended, int slot, int hotbarSlot) implements CustomPacketPayload {
        public static final Type<ShortcutPayload> TYPE = new Type<>(id("bag_shortcut"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ShortcutPayload> STREAM_CODEC =
            StreamCodec.ofMember(ShortcutPayload::write, ShortcutPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(extended); buffer.writeVarInt(slot); buffer.writeVarInt(hotbarSlot);
        }
        private static ShortcutPayload read(RegistryFriendlyByteBuf buffer) {
            return new ShortcutPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DiscardPayload(boolean extended, int slot) implements CustomPacketPayload {
        public static final Type<DiscardPayload> TYPE = new Type<>(id("bag_discard"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DiscardPayload> STREAM_CODEC = codec(
            DiscardPayload::new, DiscardPayload::extended, DiscardPayload::slot
        );
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
