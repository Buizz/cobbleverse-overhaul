package dev.buizz.cobbleventure.pokefinder;

import dev.buizz.cobbleventure.pokefinder.client.RadarMarkerSnapshot;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import dev.buizz.cobbleventure.pokefinder.server.WorldBootstrapRadarProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative static radar marker snapshots. */
public final class RadarMarkerNetwork {
    private static final String VERSION = "1";
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_MARKERS = 2_048;
    private static final Map<UUID, SnapshotPayload> LAST_SENT = new HashMap<>();

    private RadarMarkerNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(RadarMarkerNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(RadarMarkerNetwork::onServerTick);
        NeoForge.EVENT_BUS.addListener(RadarMarkerNetwork::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RadarMarkerNetwork::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(RadarMarkerNetwork::onPlayerLoggedOut);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
            SnapshotPayload.TYPE,
            SnapshotPayload.STREAM_CODEC,
            RadarMarkerNetwork::handleSnapshot
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            sync(player, false);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player, true);
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player, true);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    private static void sync(ServerPlayer player, boolean force) {
        SnapshotPayload payload = new SnapshotPayload(
            WorldBootstrapRadarProvider.markers(player)
        );
        if (!force && payload.equals(LAST_SENT.get(player.getUUID()))) return;
        LAST_SENT.put(player.getUUID(), payload);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        ClientHandler.apply(payload.markers());
    }

    /** Kept nested so dedicated servers never initialize the client snapshot class. */
    private static final class ClientHandler {
        private static void apply(List<RadarMarker> markers) {
            RadarMarkerSnapshot.replace(markers);
        }
    }

    private record SnapshotPayload(List<RadarMarker> markers)
        implements CustomPacketPayload {
        private static final Type<SnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventurePokefinder.MOD_ID, "marker_snapshot"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(SnapshotPayload::write, SnapshotPayload::read);

        private SnapshotPayload {
            markers = List.copyOf(markers);
            if (markers.size() > MAX_MARKERS) {
                throw new IllegalArgumentException("Too many radar markers: " + markers.size());
            }
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(markers.size());
            for (RadarMarker marker : markers) {
                buffer.writeResourceLocation(marker.id());
                buffer.writeEnum(marker.type());
                buffer.writeResourceLocation(marker.dimension());
                buffer.writeDouble(marker.position().x);
                buffer.writeDouble(marker.position().y);
                buffer.writeDouble(marker.position().z);
                buffer.writeUtf(marker.label(), 256);
                buffer.writeResourceLocation(marker.icon());
                buffer.writeVarInt(marker.priority());
                buffer.writeEnum(marker.state());
                buffer.writeUtf(marker.areaId(), 128);
                buffer.writeDouble(marker.localRange());
                buffer.writeBoolean(marker.edgeTracking());
            }
        }

        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_MARKERS) {
                throw new IllegalArgumentException("Invalid radar marker count: " + size);
            }
            java.util.ArrayList<RadarMarker> markers = new java.util.ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                ResourceLocation id = buffer.readResourceLocation();
                RadarMarkerType type = buffer.readEnum(RadarMarkerType.class);
                ResourceLocation dimension = buffer.readResourceLocation();
                Vec3 position = new Vec3(
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble()
                );
                String label = buffer.readUtf(256);
                ResourceLocation icon = buffer.readResourceLocation();
                int priority = buffer.readVarInt();
                RadarMarkerState state = buffer.readEnum(RadarMarkerState.class);
                String areaId = buffer.readUtf(128);
                double localRange = buffer.readDouble();
                boolean edgeTracking = buffer.readBoolean();
                markers.add(new RadarMarker(
                    id, type, dimension, position, label, icon, priority,
                    state, areaId, localRange, edgeTracking
                ));
            }
            return new SnapshotPayload(markers);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
