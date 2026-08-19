package dev.buizz.cobbleventure.playermenu;

import dev.buizz.cobbleventure.playermenu.client.ItemAcquisitionOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Common feedback for items awarded by NPCs and scripted events. */
public final class ItemAcquisition {
    public static final int NOTICE_DURATION_TICKS = 70;
    private static final String NETWORK_VERSION = "1";
    private static final TagKey<Item> KEY_ITEMS = itemTag("key_items");
    private static final TagKey<Item> MACHINES = itemTag("machines");

    private ItemAcquisition() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(ItemAcquisition::registerPayloads);
    }

    public static void show(ServerPlayer player, ItemStack item, int count) {
        Component itemName = item.getHoverName().copy();
        Component message = Component.translatable(
            count == 1
                ? "message.cobbleventure_player_menu.item_acquired"
                : "message.cobbleventure_player_menu.items_acquired",
            player.getDisplayName(), itemName, count
        );
        PacketDistributor.sendToPlayer(
            player, new AcquiredPayload(message, sound(player, item))
        );
    }

    public static void showLoot(ServerPlayer player, int count) {
        Component message = Component.translatable(
            "message.cobbleventure_player_menu.loot_acquired",
            player.getDisplayName(), count
        );
        PacketDistributor.sendToPlayer(
            player, new AcquiredPayload(message, sound(player, "item_acquired"))
        );
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(
            AcquiredPayload.TYPE, AcquiredPayload.STREAM_CODEC, ItemAcquisition::handle
        );
    }

    private static void handle(AcquiredPayload payload, IPayloadContext context) {
        ItemAcquisitionOverlay.show(payload.message(), payload.sound());
    }

    private static ResourceLocation sound(ServerPlayer player, ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = itemId.getPath();
        if (stack.is(MACHINES) || itemId.getNamespace().equals("tmcraft")) {
            return sound(player, "machine_acquired");
        }
        if (stack.is(KEY_ITEMS) || containsAny(
            path, "pokedex", "exp_share", "key", "badge", "map", "compass"
        )) {
            return sound(player, "key_item_acquired");
        }
        return sound(player, "item_acquired");
    }

    private static ResourceLocation sound(ServerPlayer player, String context) {
        ResourceLocation configured = MusicPlayback.defaultSoundEvent(player, context);
        return configured != null ? configured : ResourceLocation.fromNamespaceAndPath(
            "minecraft", "entity.item.pickup"
        );
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path)
        );
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private record AcquiredPayload(
        Component message, ResourceLocation sound
    ) implements CustomPacketPayload {
        private static final Type<AcquiredPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventurePlayerMenu.MOD_ID, "item_acquired"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, AcquiredPayload> STREAM_CODEC =
            StreamCodec.of(AcquiredPayload::write, AcquiredPayload::read);

        private static void write(RegistryFriendlyByteBuf buffer, AcquiredPayload payload) {
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buffer, payload.message());
            buffer.writeResourceLocation(payload.sound());
        }

        private static AcquiredPayload read(RegistryFriendlyByteBuf buffer) {
            return new AcquiredPayload(
                ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buffer),
                buffer.readResourceLocation()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
