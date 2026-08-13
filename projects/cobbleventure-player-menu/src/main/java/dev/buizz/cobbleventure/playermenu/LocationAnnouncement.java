package dev.buizz.cobbleventure.playermenu;

import dev.buizz.cobbleventure.playermenu.client.LocationAnnouncementOverlay;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Sends main-series-style town and route plaques to the entering player. */
public final class LocationAnnouncement {
    private static final String NETWORK_VERSION = "2";
    private static final Map<UUID, LocationState> STATES = new HashMap<>();

    private LocationAnnouncement() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(LocationAnnouncement::registerPayloads);
        NeoForge.EVENT_BUS.addListener(LocationAnnouncement::onPlayerLoggedOut);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    public static void show(ServerPlayer player, Component title, Component subtitle, boolean town) {
        show(player, title, subtitle, Component.empty(), town);
    }

    public static void show(
        ServerPlayer player, Component title, Component subtitle, Component detail, boolean town
    ) {
        PacketDistributor.sendToPlayer(player, new OpenPayload(title, subtitle, detail, town));
    }

    public static void update(
        ServerPlayer player, String locationKey, Component title, Component subtitle,
        Component detail, boolean town
    ) {
        LocationState state = STATES.computeIfAbsent(
            player.getUUID(), ignored -> new LocationState()
        );
        if (!state.candidate.equals(locationKey)) {
            state.candidate = locationKey;
            state.stableSamples = 1;
            return;
        }
        if (state.stableSamples < 2) state.stableSamples++;
        if (state.stableSamples < 2 || state.shown.equals(locationKey)) return;
        state.shown = locationKey;
        if (locationKey.isEmpty()) clearOverlay(player);
        else show(player, title, subtitle, detail, town);
    }

    public static void clear(ServerPlayer player) {
        if (STATES.remove(player.getUUID()) == null) return;
        clearOverlay(player);
    }

    private static void clearOverlay(ServerPlayer player) {
        PacketDistributor.sendToPlayer(
            player, new OpenPayload(
                Component.empty(), Component.empty(), Component.empty(), false
            )
        );
    }

    private static final class LocationState {
        private String candidate = "";
        private String shown = "";
        private int stableSamples;
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
            CobbleventurePlayerMenu.MOD_ID, "location_announcement"
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
