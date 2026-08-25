package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.client.DungeonGuideScreen;
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

/** Opens the dungeon guide and returns the player's explicit entry choice. */
public final class DungeonGuideNetwork {
    private static final String VERSION = "1";

    private DungeonGuideNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(DungeonGuideNetwork::registerPayloads);
    }

    static void open(ServerPlayer player, GuideData data) {
        PacketDistributor.sendToPlayer(player, new OpenGuidePayload(data));
    }

    public static void respond(String entranceId, boolean accepted) {
        PacketDistributor.sendToServer(new GuideResponsePayload(entranceId, accepted));
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
        boolean healOnWipe
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
        }

        private static GuideData read(RegistryFriendlyByteBuf buffer) {
            return new GuideData(
                buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(),
                buffer.readUtf(), buffer.readBoolean()
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
}
