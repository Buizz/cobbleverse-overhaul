package dev.buizz.cobbleventure.liveeditor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.liveeditor.client.LiveEditorClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class LiveEditorNetwork {
    private LiveEditorNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(LiveEditorNetwork::registerPayloads);
    }

    static void openAnchorEditor(
        ServerPlayer player, BlockPos position, boolean door, boolean transition,
        String currentType, String currentKind, String currentLabel
    ) {
        PacketDistributor.sendToPlayer(player, new OpenAnchorPayload(
            position, door, transition, currentType, currentKind, currentLabel
        ));
    }

    static void sendSnapshot(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new SnapshotPayload(
            LiveNbtEditorMod.hasActiveStructure(), LiveNbtEditorMod.activeStructureId(),
            LiveNbtEditorMod.activeStructureOrigin(), LiveNbtEditorMod.activeStructureSize(),
            markers(player)
        ));
    }

    static void requestOpenDecision(
        ServerPlayer player, String revision, String currentId, String nextId
    ) {
        PacketDistributor.sendToPlayer(
            player, new ConfirmOpenPayload(revision, currentId, nextId)
        );
    }

    private static List<Marker> markers(ServerPlayer player) {
        if (!LiveNbtEditorMod.hasActiveStructure()) return List.of();
        ServerLevel editLevel = player.getServer().getLevel(LiveNbtEditorMod.EDIT_LEVEL);
        List<Marker> result = new ArrayList<>();
        for (JsonElement element : LiveNbtEditorMod.activeStructureMetadata()
            .getAsJsonArray("anchors")) {
            if (!element.isJsonObject()) continue;
            JsonObject anchor = element.getAsJsonObject();
            if (!anchor.has("position") || !anchor.get("position").isJsonArray()
                || anchor.getAsJsonArray("position").size() != 3) continue;
            BlockPos relative = new BlockPos(
                anchor.getAsJsonArray("position").get(0).getAsInt(),
                anchor.getAsJsonArray("position").get(1).getAsInt(),
                anchor.getAsJsonArray("position").get(2).getAsInt()
            );
            String type = anchor.has("type") ? anchor.get("type").getAsString() : "unknown";
            String label = anchor.has("label") ? anchor.get("label").getAsString()
                : anchor.has("id") ? anchor.get("id").getAsString() : "unnamed";
            String facing = anchor.has("facing") ? anchor.get("facing").getAsString()
                : anchor.has("door_facing") ? anchor.get("door_facing").getAsString() : "";
            String kind = anchor.has("kind") ? anchor.get("kind").getAsString() : "";
            BlockPos position = LiveNbtEditorMod.activeStructureOrigin().offset(relative);
            BlockPos pairedPosition = type.equals("door") && editLevel != null
                ? LiveEditorTools.pairedDoor(editLevel, position) : null;
            List<BlockPos> regionPositions = type.equals("transition") && editLevel != null
                ? LiveEditorTools.connectedBarrierRegion(editLevel, position).stream()
                    .sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()
                : List.of();
            result.add(new Marker(
                label, type, kind, position, pairedPosition, regionPositions, facing
            ));
        }
        return List.copyOf(result);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("5");
        registrar.playToClient(
            OpenAnchorPayload.TYPE, OpenAnchorPayload.CODEC, LiveEditorNetwork::handleOpen
        );
        registrar.playToServer(
            ApplyAnchorPayload.TYPE, ApplyAnchorPayload.CODEC, LiveEditorNetwork::handleApply
        );
        registrar.playToClient(
            SnapshotPayload.TYPE, SnapshotPayload.CODEC, LiveEditorNetwork::handleSnapshot
        );
        registrar.playToServer(
            SelectWorldEditPayload.TYPE, SelectWorldEditPayload.CODEC,
            LiveEditorNetwork::handleWorldEditSelect
        );
        registrar.playToServer(
            MoveBoundsPayload.TYPE, MoveBoundsPayload.CODEC,
            LiveEditorNetwork::handleMoveBounds
        );
        registrar.playToClient(
            ConfirmOpenPayload.TYPE, ConfirmOpenPayload.CODEC,
            LiveEditorNetwork::handleConfirmOpen
        );
        registrar.playToServer(
            OpenDecisionPayload.TYPE, OpenDecisionPayload.CODEC,
            LiveEditorNetwork::handleOpenDecision
        );
        registrar.playToServer(
            ManualSavePayload.TYPE, ManualSavePayload.CODEC,
            LiveEditorNetwork::handleManualSave
        );
    }

    private static void handleOpen(OpenAnchorPayload payload, IPayloadContext context) {
        LiveEditorClient.openAnchorEditor(
            payload.position(), payload.door(), payload.transition(), payload.currentType(),
            payload.currentKind(), payload.currentLabel()
        );
    }

    private static void handleApply(ApplyAnchorPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try {
            LiveEditorTools.applyAnchor(
                player, payload.position(), payload.anchorType(), payload.markerKind(),
                payload.label()
            );
        } catch (RuntimeException error) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Live NBT Editor] " + error.getMessage()
            ));
        }
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        LiveEditorClient.updateSnapshot(
            payload.active(), payload.structureId(), payload.origin(), payload.size(),
            payload.markers()
        );
    }

    private static void handleWorldEditSelect(
        SelectWorldEditPayload payload, IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try {
            LiveNbtEditorMod.selectWorldEditRegion(player);
        } catch (RuntimeException error) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Live NBT Editor] " + error.getMessage()
            ));
        }
    }

    private static void handleMoveBounds(MoveBoundsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        try {
            LiveNbtEditorMod.moveBounds(player, payload.direction(), payload.blocks());
        } catch (RuntimeException error) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Live NBT Editor] " + error.getMessage()
            ));
        }
    }

    private static void handleConfirmOpen(ConfirmOpenPayload payload, IPayloadContext context) {
        LiveEditorClient.openSaveConfirmation(
            payload.revision(), payload.currentId(), payload.nextId()
        );
    }

    private static void handleOpenDecision(OpenDecisionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        LiveNbtEditorMod.resolveOpenDecision(
            player.getServer(), payload.revision(), payload.decision(), player
        );
    }

    private static void handleManualSave(ManualSavePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        LiveNbtEditorMod.saveFromShortcut(player.getServer(), player);
    }

    record OpenAnchorPayload(
        BlockPos position, boolean door, boolean transition,
        String currentType, String currentKind, String currentLabel
    ) implements CustomPacketPayload {
        private static final Type<OpenAnchorPayload> TYPE = new Type<>(id("open_anchor"));
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenAnchorPayload> CODEC =
            StreamCodec.ofMember(OpenAnchorPayload::write, OpenAnchorPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBlockPos(position);
            buffer.writeBoolean(door);
            buffer.writeBoolean(transition);
            buffer.writeUtf(currentType);
            buffer.writeUtf(currentKind);
            buffer.writeUtf(currentLabel);
        }

        private static OpenAnchorPayload read(RegistryFriendlyByteBuf buffer) {
            return new OpenAnchorPayload(
                buffer.readBlockPos(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readUtf(), buffer.readUtf(), buffer.readUtf()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ApplyAnchorPayload(
        BlockPos position, String anchorType, String markerKind, String label
    ) implements CustomPacketPayload {
        private static final Type<ApplyAnchorPayload> TYPE = new Type<>(id("apply_anchor"));
        private static final StreamCodec<RegistryFriendlyByteBuf, ApplyAnchorPayload> CODEC =
            StreamCodec.ofMember(ApplyAnchorPayload::write, ApplyAnchorPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBlockPos(position);
            buffer.writeUtf(anchorType);
            buffer.writeUtf(markerKind);
            buffer.writeUtf(label);
        }

        private static ApplyAnchorPayload read(RegistryFriendlyByteBuf buffer) {
            return new ApplyAnchorPayload(
                buffer.readBlockPos(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SnapshotPayload(
        boolean active, String structureId, BlockPos origin, Vec3i size, List<Marker> markers
    ) implements CustomPacketPayload {
        private static final Type<SnapshotPayload> TYPE = new Type<>(id("snapshot"));
        private static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC =
            StreamCodec.ofMember(SnapshotPayload::write, SnapshotPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(active);
            buffer.writeUtf(structureId);
            buffer.writeBlockPos(origin);
            buffer.writeVarInt(size.getX());
            buffer.writeVarInt(size.getY());
            buffer.writeVarInt(size.getZ());
            buffer.writeVarInt(markers.size());
            for (Marker marker : markers) {
                buffer.writeUtf(marker.label());
                buffer.writeUtf(marker.type());
                buffer.writeUtf(marker.kind());
                buffer.writeBlockPos(marker.position());
                buffer.writeBoolean(marker.pairedPosition() != null);
                if (marker.pairedPosition() != null) {
                    buffer.writeBlockPos(marker.pairedPosition());
                }
                buffer.writeVarInt(marker.regionPositions().size());
                for (BlockPos regionPosition : marker.regionPositions()) {
                    buffer.writeBlockPos(regionPosition);
                }
                buffer.writeUtf(marker.facing());
            }
        }

        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            boolean active = buffer.readBoolean();
            String structureId = buffer.readUtf();
            BlockPos origin = buffer.readBlockPos();
            Vec3i size = new Vec3i(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
            int count = Math.min(2048, buffer.readVarInt());
            List<Marker> markers = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String label = buffer.readUtf();
                String type = buffer.readUtf();
                String kind = buffer.readUtf();
                BlockPos position = buffer.readBlockPos();
                BlockPos pairedPosition = buffer.readBoolean() ? buffer.readBlockPos() : null;
                int regionCount = Math.min(4096, buffer.readVarInt());
                List<BlockPos> regionPositions = new ArrayList<>(regionCount);
                for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
                    regionPositions.add(buffer.readBlockPos());
                }
                markers.add(new Marker(
                    label, type, kind, position, pairedPosition,
                    List.copyOf(regionPositions), buffer.readUtf()
                ));
            }
            return new SnapshotPayload(
                active, structureId, origin, size, List.copyOf(markers)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Marker(
        String label, String type, String kind, BlockPos position, BlockPos pairedPosition,
        List<BlockPos> regionPositions, String facing
    ) {}

    public record SelectWorldEditPayload() implements CustomPacketPayload {
        private static final Type<SelectWorldEditPayload> TYPE =
            new Type<>(id("select_worldedit"));
        private static final StreamCodec<RegistryFriendlyByteBuf, SelectWorldEditPayload> CODEC =
            StreamCodec.unit(new SelectWorldEditPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record MoveBoundsPayload(
        String direction, int blocks
    ) implements CustomPacketPayload {
        private static final Type<MoveBoundsPayload> TYPE =
            new Type<>(id("move_bounds"));
        private static final StreamCodec<RegistryFriendlyByteBuf, MoveBoundsPayload> CODEC =
            StreamCodec.ofMember(MoveBoundsPayload::write, MoveBoundsPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(direction);
            buffer.writeVarInt(blocks);
        }

        private static MoveBoundsPayload read(RegistryFriendlyByteBuf buffer) {
            return new MoveBoundsPayload(buffer.readUtf(), buffer.readVarInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    record ConfirmOpenPayload(
        String revision, String currentId, String nextId
    ) implements CustomPacketPayload {
        private static final Type<ConfirmOpenPayload> TYPE = new Type<>(id("confirm_open"));
        private static final StreamCodec<RegistryFriendlyByteBuf, ConfirmOpenPayload> CODEC =
            StreamCodec.ofMember(ConfirmOpenPayload::write, ConfirmOpenPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(revision);
            buffer.writeUtf(currentId);
            buffer.writeUtf(nextId);
        }

        private static ConfirmOpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new ConfirmOpenPayload(buffer.readUtf(), buffer.readUtf(), buffer.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenDecisionPayload(
        String revision, String decision
    ) implements CustomPacketPayload {
        private static final Type<OpenDecisionPayload> TYPE = new Type<>(id("open_decision"));
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenDecisionPayload> CODEC =
            StreamCodec.ofMember(OpenDecisionPayload::write, OpenDecisionPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(revision);
            buffer.writeUtf(decision);
        }

        private static OpenDecisionPayload read(RegistryFriendlyByteBuf buffer) {
            return new OpenDecisionPayload(buffer.readUtf(), buffer.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ManualSavePayload() implements CustomPacketPayload {
        private static final Type<ManualSavePayload> TYPE = new Type<>(id("manual_save"));
        private static final StreamCodec<RegistryFriendlyByteBuf, ManualSavePayload> CODEC =
            StreamCodec.unit(new ManualSavePayload());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(LiveNbtEditorMod.MOD_ID, path);
    }
}
