package dev.buizz.cobbleventure.structurebuilder;

import dev.buizz.cobbleventure.structurebuilder.client.BuilderEditorClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class BuilderEditorNetwork {
    private static final String VERSION = "5";

    private BuilderEditorNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(BuilderEditorNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(SnapshotPayload.TYPE, SnapshotPayload.CODEC, BuilderEditorNetwork::handleSnapshot);
        registrar.playToClient(OpenAnchorPayload.TYPE, OpenAnchorPayload.CODEC, BuilderEditorNetwork::handleOpenAnchor);
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, BuilderEditorNetwork::handleRequest);
        registrar.playToServer(ApplyAnchorPayload.TYPE, ApplyAnchorPayload.CODEC, BuilderEditorNetwork::handleApply);
        registrar.playToServer(TeleportPayload.TYPE, TeleportPayload.CODEC, BuilderEditorNetwork::handleTeleport);
        registrar.playToServer(ResizePayload.TYPE, ResizePayload.CODEC, BuilderEditorNetwork::handleResize);
        registrar.playToServer(SaveCurrentPayload.TYPE, SaveCurrentPayload.CODEC, BuilderEditorNetwork::handleSaveCurrent);
        registrar.playToServer(SelectCurrentPayload.TYPE, SelectCurrentPayload.CODEC, BuilderEditorNetwork::handleSelectCurrent);
        registrar.playToServer(MoveCurrentPayload.TYPE, MoveCurrentPayload.CODEC, BuilderEditorNetwork::handleMoveCurrent);
    }

    static void openAnchorEditor(
        ServerPlayer player, BlockPos position, boolean door, boolean transition
    ) {
        PacketDistributor.sendToPlayer(player, new OpenAnchorPayload(position, door, transition));
        sendSnapshot(player);
    }

    static void sendSnapshot(ServerPlayer player) {
        StructureBuilderMod.EditorSnapshot snapshot = StructureBuilderMod.editorSnapshot(player);
        PacketDistributor.sendToPlayer(player, SnapshotPayload.from(snapshot));
    }

    public static void requestSnapshot() { PacketDistributor.sendToServer(new RequestPayload()); }
    public static void applyAnchor(BlockPos position, String type, String label) {
        PacketDistributor.sendToServer(new ApplyAnchorPayload(position, type, label));
    }
    public static void teleport(String key) { PacketDistributor.sendToServer(new TeleportPayload(key)); }
    public static void resize(int width, int depth, int floorHeight, int floors) {
        PacketDistributor.sendToServer(new ResizePayload(width, depth, floorHeight, floors));
    }
    public static void saveCurrent() { PacketDistributor.sendToServer(new SaveCurrentPayload()); }
    public static void selectCurrent() { PacketDistributor.sendToServer(new SelectCurrentPayload()); }
    public static void moveCurrent(String direction, int amount) {
        PacketDistributor.sendToServer(new MoveCurrentPayload(direction, amount));
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        BuilderEditorClient.update(payload);
    }

    private static void handleOpenAnchor(OpenAnchorPayload payload, IPayloadContext context) {
        BuilderEditorClient.openAnchorEditor(
            payload.position(), payload.door(), payload.transition()
        );
    }

    private static void handleRequest(RequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) sendSnapshot(player);
    }

    private static void handleApply(ApplyAnchorPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try {
            StructureBuilderMod.applyEditorAnchor(player, payload.position(), payload.anchorType(), payload.label());
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal("[Structure Builder] " + error.getMessage()));
        }
    }

    private static void handleTeleport(TeleportPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try { StructureBuilderMod.editorTeleport(player, payload.key()); }
        catch (RuntimeException error) { player.sendSystemMessage(Component.literal("[Structure Builder] " + error.getMessage())); }
    }

    private static void handleResize(ResizePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try { StructureBuilderMod.editorResize(player, payload.width(), payload.depth(), payload.floorHeight(), payload.floors()); }
        catch (RuntimeException error) { player.sendSystemMessage(Component.literal("[Structure Builder] " + error.getMessage())); }
    }

    private static void handleSaveCurrent(SaveCurrentPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try { StructureBuilderMod.editorSaveCurrent(player); }
        catch (RuntimeException error) { player.sendSystemMessage(Component.literal("[Structure Builder] " + error.getMessage())); }
    }

    private static void handleSelectCurrent(SelectCurrentPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try { StructureBuilderMod.editorSelectCurrent(player); }
        catch (RuntimeException error) { player.sendSystemMessage(Component.literal("[Structure Builder] " + error.getMessage())); }
    }

    private static void handleMoveCurrent(MoveCurrentPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try { StructureBuilderMod.editorMoveCurrent(player, payload.direction(), payload.amount()); }
        catch (RuntimeException error) { player.sendSystemMessage(Component.literal("[Structure Builder] " + error.getMessage())); }
    }

    public record Space(String key, String label, boolean interior, BlockPos origin, Vec3i size,
                        boolean resizable, int floorHeight, int floors) {}
    public record Marker(
        String label, String type, BlockPos position, BlockPos pairedPosition,
        String facing
    ) {}

    public record SnapshotPayload(
        String currentKey, String currentLabel, boolean interior, Vec3i size,
        List<Space> spaces, List<Marker> markers
    ) implements CustomPacketPayload {
        static final Type<SnapshotPayload> TYPE = new Type<>(id("editor_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.ofMember(
            SnapshotPayload::write, SnapshotPayload::read
        );
        static SnapshotPayload from(StructureBuilderMod.EditorSnapshot snapshot) {
            return new SnapshotPayload(snapshot.currentKey(), snapshot.currentLabel(), snapshot.interior(), snapshot.size(),
                snapshot.spaces().stream().map(space -> new Space(space.key(), space.label(), space.interior(), space.origin(), space.size(), space.resizable(), space.floorHeight(), space.floors())).toList(),
                snapshot.markers().stream().map(marker -> new Marker(
                    marker.label(), marker.type(), marker.position(), marker.pairedPosition(),
                    marker.facing()
                )).toList());
        }
        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(currentKey); buffer.writeUtf(currentLabel); buffer.writeBoolean(interior); writeVec(buffer, size);
            buffer.writeVarInt(spaces.size());
            for (Space space : spaces) {
                buffer.writeUtf(space.key); buffer.writeUtf(space.label); buffer.writeBoolean(space.interior);
                buffer.writeBlockPos(space.origin); writeVec(buffer, space.size); buffer.writeBoolean(space.resizable);
                buffer.writeVarInt(space.floorHeight); buffer.writeVarInt(space.floors);
            }
            buffer.writeVarInt(markers.size());
            for (Marker marker : markers) {
                buffer.writeUtf(marker.label); buffer.writeUtf(marker.type);
                buffer.writeBlockPos(marker.position);
                buffer.writeBoolean(marker.pairedPosition != null);
                if (marker.pairedPosition != null) buffer.writeBlockPos(marker.pairedPosition);
                buffer.writeUtf(marker.facing == null ? "" : marker.facing);
            }
        }
        static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            String key = buffer.readUtf(); String label = buffer.readUtf(); boolean interior = buffer.readBoolean(); Vec3i size = readVec(buffer);
            int spaceCount = Math.min(512, buffer.readVarInt()); List<Space> spaces = new ArrayList<>(spaceCount);
            for (int i = 0; i < spaceCount; i++) spaces.add(new Space(buffer.readUtf(), buffer.readUtf(), buffer.readBoolean(), buffer.readBlockPos(), readVec(buffer), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt()));
            int markerCount = Math.min(512, buffer.readVarInt()); List<Marker> markers = new ArrayList<>(markerCount);
            for (int i = 0; i < markerCount; i++) {
                String markerLabel = buffer.readUtf();
                String markerType = buffer.readUtf();
                BlockPos markerPosition = buffer.readBlockPos();
                BlockPos pairedPosition = buffer.readBoolean() ? buffer.readBlockPos() : null;
                String markerFacing = buffer.readUtf();
                markers.add(new Marker(
                    markerLabel, markerType, markerPosition, pairedPosition,
                    markerFacing.isBlank() ? null : markerFacing
                ));
            }
            return new SnapshotPayload(key, label, interior, size, List.copyOf(spaces), List.copyOf(markers));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenAnchorPayload(
        BlockPos position, boolean door, boolean transition
    ) implements CustomPacketPayload {
        static final Type<OpenAnchorPayload> TYPE = new Type<>(id("open_anchor"));
        static final StreamCodec<RegistryFriendlyByteBuf, OpenAnchorPayload> CODEC = StreamCodec.ofMember(OpenAnchorPayload::write, OpenAnchorPayload::read);
        void write(RegistryFriendlyByteBuf b) {
            b.writeBlockPos(position); b.writeBoolean(door); b.writeBoolean(transition);
        }
        static OpenAnchorPayload read(RegistryFriendlyByteBuf b) {
            return new OpenAnchorPayload(b.readBlockPos(), b.readBoolean(), b.readBoolean());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record RequestPayload() implements CustomPacketPayload {
        static final Type<RequestPayload> TYPE = new Type<>(id("request_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = StreamCodec.unit(new RequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ApplyAnchorPayload(BlockPos position, String anchorType, String label) implements CustomPacketPayload {
        static final Type<ApplyAnchorPayload> TYPE = new Type<>(id("apply_anchor"));
        static final StreamCodec<RegistryFriendlyByteBuf, ApplyAnchorPayload> CODEC = StreamCodec.ofMember(ApplyAnchorPayload::write, ApplyAnchorPayload::read);
        void write(RegistryFriendlyByteBuf b) { b.writeBlockPos(position); b.writeUtf(anchorType); b.writeUtf(label); }
        static ApplyAnchorPayload read(RegistryFriendlyByteBuf b) { return new ApplyAnchorPayload(b.readBlockPos(), b.readUtf(), b.readUtf()); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record TeleportPayload(String key) implements CustomPacketPayload {
        static final Type<TeleportPayload> TYPE = new Type<>(id("teleport"));
        static final StreamCodec<RegistryFriendlyByteBuf, TeleportPayload> CODEC = StreamCodec.ofMember(TeleportPayload::write, TeleportPayload::read);
        void write(RegistryFriendlyByteBuf b) { b.writeUtf(key); }
        static TeleportPayload read(RegistryFriendlyByteBuf b) { return new TeleportPayload(b.readUtf()); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ResizePayload(int width, int depth, int floorHeight, int floors) implements CustomPacketPayload {
        static final Type<ResizePayload> TYPE = new Type<>(id("resize"));
        static final StreamCodec<RegistryFriendlyByteBuf, ResizePayload> CODEC = StreamCodec.ofMember(ResizePayload::write, ResizePayload::read);
        void write(RegistryFriendlyByteBuf b) { b.writeVarInt(width); b.writeVarInt(depth); b.writeVarInt(floorHeight); b.writeVarInt(floors); }
        static ResizePayload read(RegistryFriendlyByteBuf b) { return new ResizePayload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SaveCurrentPayload() implements CustomPacketPayload {
        static final Type<SaveCurrentPayload> TYPE = new Type<>(id("save_current"));
        static final StreamCodec<RegistryFriendlyByteBuf, SaveCurrentPayload> CODEC = StreamCodec.unit(new SaveCurrentPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SelectCurrentPayload() implements CustomPacketPayload {
        static final Type<SelectCurrentPayload> TYPE = new Type<>(id("select_current"));
        static final StreamCodec<RegistryFriendlyByteBuf, SelectCurrentPayload> CODEC = StreamCodec.unit(new SelectCurrentPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record MoveCurrentPayload(String direction, int amount) implements CustomPacketPayload {
        static final Type<MoveCurrentPayload> TYPE = new Type<>(id("move_current"));
        static final StreamCodec<RegistryFriendlyByteBuf, MoveCurrentPayload> CODEC = StreamCodec.ofMember(MoveCurrentPayload::write, MoveCurrentPayload::read);
        void write(RegistryFriendlyByteBuf b) { b.writeUtf(direction); b.writeVarInt(amount); }
        static MoveCurrentPayload read(RegistryFriendlyByteBuf b) { return new MoveCurrentPayload(b.readUtf(), b.readVarInt()); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static void writeVec(RegistryFriendlyByteBuf b, Vec3i value) { b.writeVarInt(value.getX()); b.writeVarInt(value.getY()); b.writeVarInt(value.getZ()); }
    private static Vec3i readVec(RegistryFriendlyByteBuf b) { return new Vec3i(b.readVarInt(), b.readVarInt(), b.readVarInt()); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(StructureBuilderMod.MOD_ID, path); }
}
