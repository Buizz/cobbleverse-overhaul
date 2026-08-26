package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.client.CopycatRenderInvalidation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
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

/** Reapplies authoritative Copycats materials after their block-entity data is resent. */
final class CopycatRenderSyncNetwork {
    private static final String VERSION = "1";
    private static final int MAX_POSITIONS = 4096;

    private CopycatRenderSyncNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(CopycatRenderSyncNetwork::registerPayloads);
    }

    static void sync(ServerPlayer player, Collection<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(
            player,
            new InvalidatePayload(positions.stream().map(BlockPos::asLong).toList())
        );
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
            InvalidatePayload.TYPE,
            InvalidatePayload.STREAM_CODEC,
            CopycatRenderSyncNetwork::apply
        );
    }

    private static void apply(InvalidatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> CopycatRenderInvalidation.reapply(payload.positions()));
    }

    private record InvalidatePayload(List<Long> positions)
        implements CustomPacketPayload {
        private static final Type<InvalidatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "copycat_render_invalidation"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, InvalidatePayload>
            STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeVarInt(payload.positions().size());
                    payload.positions().forEach(buffer::writeLong);
                },
                buffer -> {
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_POSITIONS) {
                        throw new IllegalArgumentException(
                            "Invalid copycat position count: " + count
                        );
                    }
                    List<Long> positions = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        positions.add(buffer.readLong());
                    }
                    return new InvalidatePayload(List.copyOf(positions));
                }
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
