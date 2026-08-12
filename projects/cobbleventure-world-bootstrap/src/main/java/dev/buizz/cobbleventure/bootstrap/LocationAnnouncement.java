package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.client.LocationAnnouncementOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Sends main-series-style town and route plaques to the entering player. */
final class LocationAnnouncement {
    private static final String NETWORK_VERSION = "2";

    private LocationAnnouncement() {}

    static void register(IEventBus modBus) {
        modBus.addListener(LocationAnnouncement::registerPayloads);
    }

    static void show(ServerPlayer player, Component title, Component subtitle, boolean town) {
        show(player, title, subtitle, Component.empty(), town);
    }

    static void show(
        ServerPlayer player, Component title, Component subtitle, Component detail, boolean town
    ) {
        PacketDistributor.sendToPlayer(player, new OpenPayload(title, subtitle, detail, town));
    }

    static void clear(ServerPlayer player) {
        PacketDistributor.sendToPlayer(
            player, new OpenPayload(
                Component.empty(), Component.empty(), Component.empty(), false
            )
        );
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, LocationAnnouncement::open);
    }

    private static void open(OpenPayload payload, IPayloadContext context) {
        if (payload.title().getString().isEmpty()) {
            LocationAnnouncementOverlay.clear();
        } else {
            LocationAnnouncementOverlay.show(
                payload.title(), payload.subtitle(), payload.detail(), payload.town()
            );
        }
    }

    private record OpenPayload(
        Component title, Component subtitle, Component detail, boolean town
    ) implements CustomPacketPayload {
        private static final Type<OpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleventureBootstrap.MOD_ID, "location_announcement"
        ));
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                ComponentSerialization.TRUSTED_STREAM_CODEC,
                OpenPayload::title,
                ComponentSerialization.TRUSTED_STREAM_CODEC,
                OpenPayload::subtitle,
                ComponentSerialization.TRUSTED_STREAM_CODEC,
                OpenPayload::detail,
                StreamCodec.of(
                    (buffer, value) -> buffer.writeBoolean(value),
                    RegistryFriendlyByteBuf::readBoolean
                ),
                OpenPayload::town,
                OpenPayload::new
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
