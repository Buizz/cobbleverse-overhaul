package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.client.GymBlockerVisibility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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

/** Synchronizes gym blocker visibility independently for each player. */
final class GymBlockerVisibilityNetwork {
    private static final String VERSION = "1";
    private static final int MAX_BLOCKERS = 4096;

    private GymBlockerVisibilityNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(GymBlockerVisibilityNetwork::registerPayloads);
    }

    static void sync(ServerPlayer player, Collection<UUID> hiddenBlockers) {
        PacketDistributor.sendToPlayer(
            player, new VisibilityPayload(List.copyOf(hiddenBlockers))
        );
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
            VisibilityPayload.TYPE,
            VisibilityPayload.STREAM_CODEC,
            GymBlockerVisibilityNetwork::apply
        );
    }

    private static void apply(VisibilityPayload payload, IPayloadContext context) {
        GymBlockerVisibility.replaceHidden(payload.hiddenBlockers());
    }

    private record VisibilityPayload(List<UUID> hiddenBlockers)
        implements CustomPacketPayload {
        private static final Type<VisibilityPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "gym_blocker_visibility"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, VisibilityPayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeVarInt(payload.hiddenBlockers.size());
                    payload.hiddenBlockers.forEach(buffer::writeUUID);
                },
                buffer -> {
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_BLOCKERS) {
                        throw new IllegalArgumentException("Invalid gym blocker count: " + count);
                    }
                    List<UUID> hidden = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        hidden.add(buffer.readUUID());
                    }
                    return new VisibilityPayload(List.copyOf(hidden));
                }
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
