package dev.buizz.cobbleventure.playermenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative map discovery and teleport networking. */
public final class MapNetwork {
    private static final String VERSION = "3";
    private static final String VISITED_PREFIX = "cobbleventure_player_menu.visited.";
    private static volatile ClientSnapshot clientSnapshot = new ClientSnapshot(false, false, Set.of(), "", false, 0L);

    private MapNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(MapNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(MapNetwork::onServerTick);
    }

    public static ClientSnapshot clientSnapshot() {
        return clientSnapshot;
    }

    public static void requestSnapshot() {
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            false, false, Set.of(), "", false, previous.revision() + 1L
        );
        PacketDistributor.sendToServer(new MapStateRequestPayload());
    }

    public static void requestTeleport(int generation, int q, int r) {
        PacketDistributor.sendToServer(new MapTeleportPayload(generation, q, r));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(MapStateRequestPayload.TYPE, MapStateRequestPayload.STREAM_CODEC, MapNetwork::handleStateRequest);
        registrar.playToClient(MapStatePayload.TYPE, MapStatePayload.STREAM_CODEC, MapNetwork::handleState);
        registrar.playToServer(MapTeleportPayload.TYPE, MapTeleportPayload.STREAM_CODEC, MapNetwork::handleTeleport);
        registrar.playToClient(MapTeleportResultPayload.TYPE, MapTeleportResultPayload.STREAM_CODEC, MapNetwork::handleTeleportResult);
    }

    private static void handleStateRequest(MapStateRequestPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        updateVisit(player);
        context.reply(new MapStatePayload(isAdministrator(player), player.isCreative(), visitedSettlements(player)));
    }

    private static void handleState(MapStatePayload payload, IPayloadContext context) {
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            payload.administrator(), payload.creative(), Set.copyOf(payload.visited()), "", false, previous.revision() + 1L
        );
    }

    private static void handleTeleport(MapTeleportPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        MapContent content = MapContent.forGeneration(payload.generation());
        if (content == null) {
            context.reply(new MapTeleportResultPayload(false, "존재하지 않는 세대 지도입니다."));
            return;
        }
        if (!content.contains(payload.q(), payload.r())) {
            context.reply(new MapTeleportResultPayload(false, "지도 범위를 벗어난 타일입니다."));
            return;
        }

        boolean administrator = isAdministrator(player);
        boolean unrestrictedTeleport = administrator || player.isCreative();
        MapContent.Town town = content.townAt(payload.q(), payload.r());
        if (!unrestrictedTeleport && (town == null || !hasVisited(player, town.id()))) {
            context.reply(new MapTeleportResultPayload(false, "방문한 마을만 순간이동할 수 있습니다."));
            return;
        }

        int targetQ = unrestrictedTeleport || town == null ? payload.q() : town.hex().q();
        int targetR = unrestrictedTeleport || town == null ? payload.r() : town.hex().r();
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.parse(content.dimension())
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) {
            context.reply(new MapTeleportResultPayload(false, "지도 차원을 불러오지 못했습니다."));
            return;
        }

        MapContent.WorldPoint point = content.worldCenter(targetQ, targetR);
        level.getChunk(point.x() >> 4, point.z() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, point.x(), point.z()) + 1;
        player.teleportTo(level, point.x() + 0.5D, y, point.z() + 0.5D, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        if (town != null) markVisited(player, town.id());
        context.reply(new MapTeleportResultPayload(true, town == null ? "선택 타일로 이동했습니다." : town.name() + "(으)로 이동했습니다."));
    }

    private static void handleTeleportResult(MapTeleportResultPayload payload, IPayloadContext context) {
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            previous.administrator(), previous.creative(), previous.visited(), payload.message(), payload.success(), previous.revision() + 1L
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) updateVisit(player);
    }

    private static void updateVisit(ServerPlayer player) {
        for (MapContent content : MapContent.all()) {
            if (!player.level().dimension().location().toString().equals(content.dimension())) continue;
            MapContent.Hex hex = content.worldToHex(player.getX(), player.getZ());
            MapContent.Town town = content.townAt(hex.q(), hex.r());
            if (town != null) markVisited(player, town.id());
            return;
        }
    }

    private static void markVisited(ServerPlayer player, String settlementId) {
        player.getPersistentData().putBoolean(VISITED_PREFIX + settlementId, true);
    }

    private static boolean hasVisited(ServerPlayer player, String settlementId) {
        return player.getPersistentData().getBoolean(VISITED_PREFIX + settlementId);
    }

    private static List<String> visitedSettlements(ServerPlayer player) {
        List<String> result = new ArrayList<>();
        for (MapContent content : MapContent.all()) {
            for (MapContent.Town town : content.towns()) {
                if (hasVisited(player, town.id())) result.add(town.id());
            }
        }
        return result;
    }

    private static boolean isAdministrator(ServerPlayer player) {
        return player.getServer() != null
            && player.getServer().getPlayerList().isOp(player.getGameProfile());
    }

    public record ClientSnapshot(
        boolean administrator,
        boolean creative,
        Set<String> visited,
        String message,
        boolean teleportSucceeded,
        long revision
    ) {}

    public record MapStateRequestPayload() implements CustomPacketPayload {
        public static final Type<MapStateRequestPayload> TYPE = new Type<>(id("map_state_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapStateRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new MapStateRequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapStatePayload(boolean administrator, boolean creative, List<String> visited) implements CustomPacketPayload {
        public static final Type<MapStatePayload> TYPE = new Type<>(id("map_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(MapStatePayload::write, MapStatePayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(administrator);
            buffer.writeBoolean(creative);
            buffer.writeVarInt(visited.size());
            for (String value : visited) buffer.writeUtf(value);
        }
        private static MapStatePayload read(RegistryFriendlyByteBuf buffer) {
            boolean administrator = buffer.readBoolean();
            boolean creative = buffer.readBoolean();
            int size = Math.max(0, Math.min(256, buffer.readVarInt()));
            List<String> visited = new ArrayList<>(size);
            for (int index = 0; index < size; index++) visited.add(buffer.readUtf());
            return new MapStatePayload(administrator, creative, List.copyOf(visited));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapTeleportPayload(int generation, int q, int r) implements CustomPacketPayload {
        public static final Type<MapTeleportPayload> TYPE = new Type<>(id("map_teleport"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapTeleportPayload> STREAM_CODEC =
            StreamCodec.ofMember(MapTeleportPayload::write, MapTeleportPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(generation);
            buffer.writeVarInt(q);
            buffer.writeVarInt(r);
        }
        private static MapTeleportPayload read(RegistryFriendlyByteBuf buffer) {
            return new MapTeleportPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapTeleportResultPayload(boolean success, String message) implements CustomPacketPayload {
        public static final Type<MapTeleportResultPayload> TYPE = new Type<>(id("map_teleport_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapTeleportResultPayload> STREAM_CODEC =
            StreamCodec.ofMember(MapTeleportResultPayload::write, MapTeleportResultPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeBoolean(success); buffer.writeUtf(message); }
        private static MapTeleportResultPayload read(RegistryFriendlyByteBuf buffer) {
            return new MapTeleportResultPayload(buffer.readBoolean(), buffer.readUtf());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }
}
