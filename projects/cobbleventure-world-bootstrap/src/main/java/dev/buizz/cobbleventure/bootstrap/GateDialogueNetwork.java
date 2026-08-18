package dev.buizz.cobbleventure.bootstrap;

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

/** Synchronizes the lifetime of an EasyNPC gate dialogue with the server. */
public final class GateDialogueNetwork {
    private static final String VERSION = "1";

    private GateDialogueNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(GateDialogueNetwork::registerPayloads);
    }

    public static void sendState(boolean open) {
        PacketDistributor.sendToServer(new DialogueStatePayload(open));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(
            DialogueStatePayload.TYPE,
            DialogueStatePayload.STREAM_CODEC,
            GateDialogueNetwork::handleState
        );
    }

    private static void handleState(
        DialogueStatePayload payload, IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer player) {
            WorldGateSystem.updateGateDialogueState(player, payload.open());
        }
    }

    private record DialogueStatePayload(boolean open) implements CustomPacketPayload {
        private static final Type<DialogueStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "gate_dialogue_state"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, DialogueStatePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> buffer.writeBoolean(payload.open()),
                buffer -> new DialogueStatePayload(buffer.readBoolean())
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
