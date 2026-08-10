package dev.buizz.cobbleventure.playermenu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative bag item actions. */
public final class BagNetwork {
    private static final String VERSION = "1";

    private BagNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BagNetwork::registerPayloads);
    }

    public static void requestUse(int inventoryIndex) {
        PacketDistributor.sendToServer(new UseItemPayload(inventoryIndex));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(UseItemPayload.TYPE, UseItemPayload.STREAM_CODEC, BagNetwork::handleUseItem);
    }

    private static void handleUseItem(UseItemPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        Inventory inventory = player.getInventory();
        int sourceIndex = payload.inventoryIndex();
        if (sourceIndex < 0 || sourceIndex >= 36 || inventory.getItem(sourceIndex).isEmpty()) {
            return;
        }

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
                ItemStack useResult = inventory.getItem(handIndex);
                inventory.setItem(handIndex, originalHand);
                inventory.setItem(sourceIndex, useResult);
            }
        }

        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
    }

    private static void useAndFinish(ServerPlayer player) {
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            ItemStack result = player.getMainHandItem().finishUsingItem(player.level(), player);
            player.stopUsingItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, result);
        }
    }

    public record UseItemPayload(int inventoryIndex) implements CustomPacketPayload {
        public static final Type<UseItemPayload> TYPE = new Type<>(id("bag_use_item"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UseItemPayload> STREAM_CODEC =
            StreamCodec.ofMember(UseItemPayload::write, UseItemPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(inventoryIndex);
        }

        private static UseItemPayload read(RegistryFriendlyByteBuf buffer) {
            return new UseItemPayload(buffer.readVarInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }
}
