package dev.buizz.cobbleventure.playermenu;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Synchronizes the server-owned field-move flags shown in the player overview. */
public final class PlayerOverviewNetwork {
    private static final String VERSION = "2";
    private static final String FLAG_PREFIX = "cobbleventureFieldMove.";
    private static final String ACTIVE_PREFIX = "cobbleventureFieldMoveActive.";
    private static final List<String> FIELD_MOVES = List.of(
        "surf", "fly", "flash", "defog", "rock_climb", "whirlpool", "strength", "rock_smash"
    );
    private static volatile List<String> clientFieldMoves = List.of();
    private static volatile List<String> clientActiveFieldMoves = List.of();

    private PlayerOverviewNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PlayerOverviewNetwork::registerPayloads);
    }

    public static List<String> clientFieldMoves() {
        return clientFieldMoves;
    }

    public static boolean isActive(String move) {
        return clientActiveFieldMoves.contains(move);
    }

    public static void requestSnapshot() {
        clientFieldMoves = List.of();
        clientActiveFieldMoves = List.of();
        PacketDistributor.sendToServer(new OverviewRequestPayload());
    }

    public static void requestToggle(String move) {
        PacketDistributor.sendToServer(new ToggleFieldMovePayload(move));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(
            OverviewRequestPayload.TYPE, OverviewRequestPayload.STREAM_CODEC,
            PlayerOverviewNetwork::handleRequest
        );
        registrar.playToClient(
            OverviewPayload.TYPE, OverviewPayload.STREAM_CODEC,
            PlayerOverviewNetwork::handleOverview
        );
        registrar.playToServer(
            ToggleFieldMovePayload.TYPE, ToggleFieldMovePayload.STREAM_CODEC,
            PlayerOverviewNetwork::handleToggle
        );
    }

    private static void handleRequest(OverviewRequestPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        context.reply(snapshot(player));
    }

    private static OverviewPayload snapshot(ServerPlayer player) {
        List<String> moves = FIELD_MOVES.stream()
            .filter(move -> player.getPersistentData().getBoolean(FLAG_PREFIX + move))
            .toList();
        List<String> active = moves.stream()
            .filter(move -> player.getPersistentData().getBoolean(ACTIVE_PREFIX + move))
            .toList();
        return new OverviewPayload(moves, active);
    }

    private static void handleOverview(OverviewPayload payload, IPayloadContext context) {
        clientFieldMoves = List.copyOf(payload.fieldMoves());
        clientActiveFieldMoves = List.copyOf(payload.activeFieldMoves());
    }

    private static void handleToggle(ToggleFieldMovePayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        String move = payload.move();
        if (!("rock_climb".equals(move) || "flash".equals(move) || "strength".equals(move)
            || "rock_smash".equals(move))
            || !player.getPersistentData().getBoolean(FLAG_PREFIX + move)) {
            player.displayClientMessage(Component.literal(
                "[Cobbleventure] 해당 ON/OFF 비전머신을 보유하고 있지 않습니다."
            ), true);
            context.reply(snapshot(player));
            return;
        }
        boolean active = !player.getPersistentData().getBoolean(ACTIVE_PREFIX + move);
        player.getPersistentData().putBoolean(ACTIVE_PREFIX + move, active);
        player.displayClientMessage(Component.literal(
            "[Cobbleventure] " + fieldMoveName(move) + " " + (active ? "ON" : "OFF")
        ), true);
        context.reply(snapshot(player));
    }

    private static String fieldMoveName(String move) {
        return switch (move) {
            case "flash" -> "플래쉬";
            case "strength" -> "괴력";
            case "rock_smash" -> "바위깨기";
            default -> "락클레임";
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }

    public record OverviewRequestPayload() implements CustomPacketPayload {
        public static final Type<OverviewRequestPayload> TYPE = new Type<>(id("overview_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OverviewRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new OverviewRequestPayload());

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OverviewPayload(
        List<String> fieldMoves, List<String> activeFieldMoves
    ) implements CustomPacketPayload {
        public static final Type<OverviewPayload> TYPE = new Type<>(id("overview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OverviewPayload> STREAM_CODEC =
            StreamCodec.ofMember(OverviewPayload::write, OverviewPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(fieldMoves.size());
            for (String move : fieldMoves) buffer.writeUtf(move);
            buffer.writeVarInt(activeFieldMoves.size());
            for (String move : activeFieldMoves) buffer.writeUtf(move);
        }

        private static OverviewPayload read(RegistryFriendlyByteBuf buffer) {
            int size = Math.max(0, Math.min(FIELD_MOVES.size(), buffer.readVarInt()));
            List<String> moves = new ArrayList<>(size);
            for (int index = 0; index < size; index++) moves.add(buffer.readUtf(32));
            int activeSize = Math.max(0, Math.min(FIELD_MOVES.size(), buffer.readVarInt()));
            List<String> active = new ArrayList<>(activeSize);
            for (int index = 0; index < activeSize; index++) active.add(buffer.readUtf(32));
            return new OverviewPayload(List.copyOf(moves), List.copyOf(active));
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ToggleFieldMovePayload(String move) implements CustomPacketPayload {
        public static final Type<ToggleFieldMovePayload> TYPE = new Type<>(id("toggle_field_move"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleFieldMovePayload> STREAM_CODEC =
            StreamCodec.ofMember(ToggleFieldMovePayload::write, ToggleFieldMovePayload::read);

        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUtf(move, 32); }
        private static ToggleFieldMovePayload read(RegistryFriendlyByteBuf buffer) {
            return new ToggleFieldMovePayload(buffer.readUtf(32));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
