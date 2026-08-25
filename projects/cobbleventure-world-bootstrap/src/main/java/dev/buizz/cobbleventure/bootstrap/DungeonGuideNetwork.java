package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.client.DungeonGuideScreen;
import dev.buizz.cobbleventure.bootstrap.client.DungeonQueueScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Synchronizes dungeon guide and matchmaking screens with server-owned entry state. */
public final class DungeonGuideNetwork {
    private static final String VERSION = "3";

    private DungeonGuideNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(DungeonGuideNetwork::registerPayloads);
    }

    static void open(ServerPlayer player, GuideData data) {
        PacketDistributor.sendToPlayer(player, new OpenGuidePayload(data));
    }

    static void openQueue(ServerPlayer player, QueueData data) {
        PacketDistributor.sendToPlayer(player, new OpenQueuePayload(data));
    }

    static void preparingQueue(ServerPlayer player, String entranceId) {
        PacketDistributor.sendToPlayer(
            player, new QueueStatePayload(entranceId, "preparing")
        );
    }

    static void closeQueue(ServerPlayer player, String entranceId) {
        PacketDistributor.sendToPlayer(
            player, new QueueStatePayload(entranceId, "closed")
        );
    }

    public static void respond(String entranceId, boolean accepted) {
        PacketDistributor.sendToServer(new GuideResponsePayload(entranceId, accepted));
    }

    public static void cancelQueue(String entranceId) {
        PacketDistributor.sendToServer(new QueueCancelPayload(entranceId));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
            OpenGuidePayload.TYPE,
            OpenGuidePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleOpen
        );
        registrar.playToServer(
            GuideResponsePayload.TYPE,
            GuideResponsePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleResponse
        );
        registrar.playToClient(
            OpenQueuePayload.TYPE,
            OpenQueuePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleOpenQueue
        );
        registrar.playToClient(
            QueueStatePayload.TYPE,
            QueueStatePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleQueueState
        );
        registrar.playToServer(
            QueueCancelPayload.TYPE,
            QueueCancelPayload.STREAM_CODEC,
            DungeonGuideNetwork::handleQueueCancel
        );
    }

    private static void handleOpen(OpenGuidePayload payload, IPayloadContext context) {
        DungeonGuideScreen.open(payload.data());
    }

    private static void handleResponse(
        GuideResponsePayload payload, IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer player) {
            DungeonSystem.respond(player, payload.entranceId(), payload.accepted());
        }
    }

    private static void handleOpenQueue(OpenQueuePayload payload, IPayloadContext context) {
        DungeonQueueScreen.open(payload.data());
    }

    private static void handleQueueState(QueueStatePayload payload, IPayloadContext context) {
        if (payload.state().equals("preparing")) {
            DungeonQueueScreen.preparing(payload.entranceId());
        } else {
            DungeonQueueScreen.close(payload.entranceId());
        }
    }

    private static void handleQueueCancel(
        QueueCancelPayload payload, IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer player) {
            DungeonSystem.cancelWaiting(player, payload.entranceId());
        }
    }

    public record GuideData(
        String entranceId,
        String title,
        String description,
        int recommendedMin,
        int recommendedMax,
        int internalMin,
        int internalMax,
        String infoMode,
        String wipeReturn,
        boolean healOnWipe,
        boolean repeatable,
        String levelMeasure,
        int currentPartyLevel,
        String multiplayerMode,
        int requiredPlayers
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(entranceId);
            buffer.writeUtf(title);
            buffer.writeUtf(description);
            buffer.writeVarInt(recommendedMin);
            buffer.writeVarInt(recommendedMax);
            buffer.writeVarInt(internalMin);
            buffer.writeVarInt(internalMax);
            buffer.writeUtf(infoMode);
            buffer.writeUtf(wipeReturn);
            buffer.writeBoolean(healOnWipe);
            buffer.writeBoolean(repeatable);
            buffer.writeUtf(levelMeasure);
            buffer.writeVarInt(currentPartyLevel);
            buffer.writeUtf(multiplayerMode);
            buffer.writeVarInt(requiredPlayers);
        }

        private static GuideData read(RegistryFriendlyByteBuf buffer) {
            return new GuideData(
                buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(),
                buffer.readUtf(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readUtf(), buffer.readVarInt(), buffer.readUtf(), buffer.readVarInt()
            );
        }
    }

    public record QueueData(
        String entranceId,
        String title,
        int currentPlayers,
        int requiredPlayers,
        int timeoutSeconds
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(entranceId);
            buffer.writeUtf(title);
            buffer.writeVarInt(currentPlayers);
            buffer.writeVarInt(requiredPlayers);
            buffer.writeVarInt(timeoutSeconds);
        }

        private static QueueData read(RegistryFriendlyByteBuf buffer) {
            return new QueueData(
                buffer.readUtf(), buffer.readUtf(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt()
            );
        }
    }

    private record OpenGuidePayload(GuideData data) implements CustomPacketPayload {
        private static final Type<OpenGuidePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "open_dungeon_guide"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenGuidePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> payload.data.write(buffer),
                buffer -> new OpenGuidePayload(GuideData.read(buffer))
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record GuideResponsePayload(
        String entranceId, boolean accepted
    ) implements CustomPacketPayload {
        private static final Type<GuideResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "dungeon_guide_response"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, GuideResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUtf(payload.entranceId);
                    buffer.writeBoolean(payload.accepted);
                },
                buffer -> new GuideResponsePayload(
                    buffer.readUtf(), buffer.readBoolean()
                )
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record OpenQueuePayload(QueueData data) implements CustomPacketPayload {
        private static final Type<OpenQueuePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "open_dungeon_queue"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenQueuePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> payload.data.write(buffer),
                buffer -> new OpenQueuePayload(QueueData.read(buffer))
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record QueueStatePayload(
        String entranceId, String state
    ) implements CustomPacketPayload {
        private static final Type<QueueStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "dungeon_queue_state"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, QueueStatePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUtf(payload.entranceId);
                    buffer.writeUtf(payload.state);
                },
                buffer -> new QueueStatePayload(buffer.readUtf(), buffer.readUtf())
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record QueueCancelPayload(String entranceId) implements CustomPacketPayload {
        private static final Type<QueueCancelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "dungeon_queue_cancel"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, QueueCancelPayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> buffer.writeUtf(payload.entranceId),
                buffer -> new QueueCancelPayload(buffer.readUtf())
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
