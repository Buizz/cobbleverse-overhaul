package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative map discovery and teleport networking. */
public final class MapNetwork {
    private static final String VERSION = "5";
    private static final String VISITED_PREFIX = "cobbleventure_player_menu.visited.";
    private static final int FADE_OUT_TICKS = 25;
    private static final int FADE_IN_DELAY_TICKS = 20;
    private static final int TRANSITION_EFFECT_TICKS = FADE_OUT_TICKS + FADE_IN_DELAY_TICKS + 20;
    private static final long SELECTION_LIFETIME_MILLIS = 5L * 60L * 1000L;
    private static final Map<UUID, PendingTeleport> PENDING_TELEPORTS = new HashMap<>();
    private static final Map<UUID, PendingSelection> PENDING_SELECTIONS = new HashMap<>();
    private static final Map<String, TeleportGuard> TELEPORT_GUARDS =
        new ConcurrentHashMap<>();
    private static volatile ClientSnapshot clientSnapshot = new ClientSnapshot(
        false, false, Set.of(), List.of(), "", false, 0L
    );
    private static volatile SelectionSnapshot selectionSnapshot =
        new SelectionSnapshot("", false, "", 0L);

    private MapNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(MapNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(MapNetwork::registerCommands);
        NeoForge.EVENT_BUS.addListener(MapNetwork::onServerTick);
    }

    public static ClientSnapshot clientSnapshot() {
        return clientSnapshot;
    }

    public static SelectionSnapshot selectionSnapshot() {
        return selectionSnapshot;
    }

    /** Registers a server-authoritative veto that is checked both before and during travel. */
    public static void registerTeleportGuard(String id, TeleportGuard guard) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("teleport guard id가 필요합니다.");
        }
        TELEPORT_GUARDS.put(id, java.util.Objects.requireNonNull(guard, "guard"));
    }

    public static void requestSnapshot() {
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            previous.administrator(), previous.creative(), previous.visited(), previous.players(),
            "", false, previous.revision() + 1L
        );
        PacketDistributor.sendToServer(new MapStateRequestPayload());
    }

    public static void requestTeleport(int generation, int q, int r) {
        PacketDistributor.sendToServer(new MapTeleportPayload(generation, q, r));
    }

    public static void requestSelection(String token, int generation, int q, int r) {
        PacketDistributor.sendToServer(new MapSelectionSubmitPayload(token, generation, q, r));
    }

    public static void cancelSelection(String token) {
        PacketDistributor.sendToServer(new MapSelectionCancelPayload(token));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(MapStateRequestPayload.TYPE, MapStateRequestPayload.STREAM_CODEC, MapNetwork::handleStateRequest);
        registrar.playToClient(MapStatePayload.TYPE, MapStatePayload.STREAM_CODEC, MapNetwork::handleState);
        registrar.playToServer(MapTeleportPayload.TYPE, MapTeleportPayload.STREAM_CODEC, MapNetwork::handleTeleport);
        registrar.playToClient(MapTeleportResultPayload.TYPE, MapTeleportResultPayload.STREAM_CODEC, MapNetwork::handleTeleportResult);
        registrar.playToClient(MapSelectionOpenPayload.TYPE, MapSelectionOpenPayload.STREAM_CODEC, MapNetwork::handleSelectionOpen);
        registrar.playToServer(MapSelectionSubmitPayload.TYPE, MapSelectionSubmitPayload.STREAM_CODEC, MapNetwork::handleSelectionSubmit);
        registrar.playToServer(MapSelectionCancelPayload.TYPE, MapSelectionCancelPayload.STREAM_CODEC, MapNetwork::handleSelectionCancel);
        registrar.playToClient(MapSelectionResultPayload.TYPE, MapSelectionResultPayload.STREAM_CODEC, MapNetwork::handleSelectionResult);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_map_select_session")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("token", StringArgumentType.word())
                    .executes(context -> openSelection(
                        context.getSource().getPlayerOrException(),
                        StringArgumentType.getString(context, "token")
                    )))
        );
    }

    private static int openSelection(ServerPlayer player, String token) {
        if (token == null || token.isBlank() || PENDING_SELECTIONS.containsKey(player.getUUID())) {
            return 0;
        }
        PENDING_SELECTIONS.put(player.getUUID(), new PendingSelection(
            token, System.currentTimeMillis() + SELECTION_LIFETIME_MILLIS
        ));
        PacketDistributor.sendToPlayer(player, new MapSelectionOpenPayload(token));
        return 1;
    }

    private static void handleSelectionOpen(
        MapSelectionOpenPayload payload, IPayloadContext context
    ) {
        selectionSnapshot = new SelectionSnapshot(
            payload.token(), false, "", selectionSnapshot.revision() + 1L
        );
        dev.buizz.cobbleventure.playermenu.client.PlayerMenuClient
            .openWorldMapSelection(payload.token());
    }

    private static void handleSelectionSubmit(
        MapSelectionSubmitPayload payload, IPayloadContext context
    ) {
        ServerPlayer player = (ServerPlayer) context.player();
        PendingSelection pending = PENDING_SELECTIONS.get(player.getUUID());
        if (pending == null || !pending.token().equals(payload.token())) {
            context.reply(new MapSelectionResultPayload(
                payload.token(), false, "유효하지 않거나 만료된 지도 선택입니다."
            ));
            return;
        }
        if (System.currentTimeMillis() >= pending.expiresAtEpochMilli()) {
            PENDING_SELECTIONS.remove(player.getUUID(), pending);
            context.reply(new MapSelectionResultPayload(
                payload.token(), false, "지도 선택 시간이 만료되었습니다."
            ));
            notifySelectionCancelled(player, pending.token(), "timeout");
            return;
        }
        MapContent content = MapContent.forGeneration(payload.generation());
        MapContent.Town town = content == null ? null : content.townAt(payload.q(), payload.r());
        MapSelectionPolicy.Decision decision = MapSelectionPolicy.select(
            town == null ? null : town.id(),
            Set.copyOf(visitedSettlements(player)),
            isAdministrator(player) || player.isCreative()
        );
        if (!decision.accepted()) {
            context.reply(new MapSelectionResultPayload(
                payload.token(), false, decision.message()
            ));
            return;
        }
        String command = "cobbleventure_event_map_result "
            + pending.token() + " "
            + StringArgumentType.escapeIfRequired(decision.settlementId());
        int completed;
        try {
            completed = player.getServer().getCommands().getDispatcher().execute(
                command,
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput()
            );
        } catch (CommandSyntaxException error) {
            completed = 0;
        }
        if (completed <= 0) {
            context.reply(new MapSelectionResultPayload(
                payload.token(), false, "이벤트에 지도 선택 결과를 전달하지 못했습니다."
            ));
            return;
        }
        PENDING_SELECTIONS.remove(player.getUUID(), pending);
        context.reply(new MapSelectionResultPayload(
            payload.token(), true, decision.message()
        ));
    }

    private static void handleSelectionCancel(
        MapSelectionCancelPayload payload, IPayloadContext context
    ) {
        ServerPlayer player = (ServerPlayer) context.player();
        PendingSelection pending = PENDING_SELECTIONS.get(player.getUUID());
        if (pending == null || !pending.token().equals(payload.token())
            || !PENDING_SELECTIONS.remove(player.getUUID(), pending)) {
            return;
        }
        notifySelectionCancelled(player, pending.token(), "client_cancelled");
    }

    private static void handleSelectionResult(
        MapSelectionResultPayload payload, IPayloadContext context
    ) {
        selectionSnapshot = new SelectionSnapshot(
            payload.token(), payload.accepted(), payload.message(),
            selectionSnapshot.revision() + 1L
        );
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            previous.administrator(), previous.creative(), previous.visited(),
            previous.players(), payload.message(), false, previous.revision() + 1L
        );
    }

    private static void notifySelectionCancelled(
        ServerPlayer player, String token, String reason
    ) {
        String command = "cobbleventure_event_map_cancel "
            + token + " " + reason;
        player.getServer().getCommands().performPrefixedCommand(
            player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
            command
        );
    }

    private static void handleStateRequest(MapStateRequestPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        updateVisit(player);
        context.reply(new MapStatePayload(
            isAdministrator(player), player.isCreative(), visitedSettlements(player), visiblePlayers(player)
        ));
    }

    private static void handleState(MapStatePayload payload, IPayloadContext context) {
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            payload.administrator(), payload.creative(), Set.copyOf(payload.visited()),
            payload.players(), "", false, previous.revision() + 1L
        );
    }

    private static void handleTeleport(MapTeleportPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (!ProgressionNetwork.isUnlocked(player, ProgressionNetwork.Feature.SETTLEMENT_TELEPORT)) {
            context.reply(new MapTeleportResultPayload(false, "장소 순간이동 기능을 아직 사용할 수 없습니다."));
            return;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            context.reply(new MapTeleportResultPayload(false, "전투 중에는 순간이동할 수 없습니다."));
            return;
        }
        String guarded = teleportDenialReason(player);
        if (guarded != null) {
            context.reply(new MapTeleportResultPayload(false, guarded));
            return;
        }
        if (PENDING_TELEPORTS.containsKey(player.getUUID())) {
            context.reply(new MapTeleportResultPayload(false, "이미 이동을 준비하고 있습니다."));
            return;
        }
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
        MapContent.MapObject object = content.objectAt(payload.q(), payload.r());
        String destinationId = town != null ? town.id()
            : object != null && object.teleportable() ? object.id() : null;
        if (!unrestrictedTeleport && (destinationId == null || !hasVisited(player, destinationId))) {
            context.reply(new MapTeleportResultPayload(false, "방문한 순간이동 가능 장소만 이동할 수 있습니다."));
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

        int currentTick = player.getServer().getTickCount();
        player.addEffect(new MobEffectInstance(
            MobEffects.DARKNESS,
            TRANSITION_EFFECT_TICKS,
            0,
            true,
            false,
            false
        ));
        PENDING_TELEPORTS.put(player.getUUID(), new PendingTeleport(
            content,
            dimension,
            town,
            object,
            targetQ,
            targetR,
            currentTick + FADE_OUT_TICKS
        ));
        context.reply(new MapTeleportResultPayload(true, "이동을 준비하고 있습니다."));
    }

    private static void handleTeleportResult(MapTeleportResultPayload payload, IPayloadContext context) {
        ClientSnapshot previous = clientSnapshot;
        clientSnapshot = new ClientSnapshot(
            previous.administrator(), previous.creative(), previous.visited(), previous.players(),
            payload.message(), payload.success(), previous.revision() + 1L
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        int currentTick = event.getServer().getTickCount();
        updatePendingTeleports(event, currentTick);
        updatePendingSelections(event);
        if (currentTick % 20 == 0) {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                updateVisit(player);
            }
        }
    }

    private static void updatePendingSelections(ServerTickEvent.Post event) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingSelection>> iterator =
            PENDING_SELECTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingSelection> entry = iterator.next();
            PendingSelection pending = entry.getValue();
            if (now < pending.expiresAtEpochMilli()) continue;
            iterator.remove();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) notifySelectionCancelled(player, pending.token(), "timeout");
        }
    }

    private static void updatePendingTeleports(ServerTickEvent.Post event, int currentTick) {
        Iterator<Map.Entry<UUID, PendingTeleport>> iterator =
            PENDING_TELEPORTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingTeleport> entry = iterator.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            PendingTeleport pending = entry.getValue();
            if (!pending.teleported && currentTick >= pending.teleportAt) {
                String error = performTeleport(player, pending);
                if (error != null) {
                    iterator.remove();
                    player.removeEffect(MobEffects.DARKNESS);
                    PacketDistributor.sendToPlayer(
                        player, new MapTeleportResultPayload(false, error)
                    );
                    continue;
                }
                pending.teleported = true;
                pending.brightenAt = currentTick + FADE_IN_DELAY_TICKS;
                String destinationId = pending.town != null ? pending.town.id()
                    : pending.object != null && pending.object.teleportable()
                        ? pending.object.id() : null;
                if (destinationId != null) markVisited(player, destinationId);
                PacketDistributor.sendToPlayer(player, new MapTeleportResultPayload(
                    true,
                    pending.town == null
                        ? pending.object == null
                            ? "선택 타일로 이동했습니다."
                            : pending.object.name() + "(으)로 이동했습니다."
                        : pending.town.name() + "(으)로 이동했습니다."
                ));
            }
            if (!pending.teleported || currentTick < pending.brightenAt) {
                continue;
            }
            iterator.remove();
            player.removeEffect(MobEffects.DARKNESS);
        }
    }

    private static String performTeleport(ServerPlayer player, PendingTeleport pending) {
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return "전투 중에는 순간이동할 수 없습니다.";
        }
        String guarded = teleportDenialReason(player);
        if (guarded != null) return guarded;
        ServerLevel level = player.getServer().getLevel(pending.dimension);
        if (level == null) {
            return "지도 차원을 불러오지 못했습니다.";
        }
        if (pending.town != null) {
            try {
                int result = player.getServer().getCommands().getDispatcher().execute(
                    "cobbleventure_center teleport " + player.getUUID() + " " + pending.town.id(),
                    player.getServer().createCommandSourceStack()
                        .withPermission(4)
                        .withSuppressedOutput()
                );
                if (result > 0) {
                    player.resetFallDistance();
                    return null;
                }
            } catch (CommandSyntaxException error) {
                // The world bootstrap side-mod may be absent or the settlement may
                // not have a generated Pokemon Center yet. Fall back to the town
                // hex instead of cancelling an otherwise valid map teleport.
            }
        }

        MapContent.WorldPoint point = pending.content.worldCenter(
            pending.targetQ, pending.targetR
        );
        BlockPos target = safeTeleportPosition(level, point.x(), point.z());
        if (target == null) {
            return "이동할 수 있는 안전한 지면을 찾지 못했습니다.";
        }
        player.stopRiding();
        player.teleportTo(
            level,
            target.getX() + 0.5D,
            target.getY(),
            target.getZ() + 0.5D,
            player.getYRot(),
            player.getXRot()
        );
        player.resetFallDistance();
        return null;
    }

    static String teleportDenialReason(ServerPlayer player) {
        for (TeleportGuard guard : TELEPORT_GUARDS.values()) {
            String reason;
            try {
                reason = guard.denialReason(player);
            } catch (RuntimeException error) {
                return "현재 상태를 안전하게 확인할 수 없어 순간이동을 취소했습니다.";
            }
            if (reason != null && !reason.isBlank()) return reason;
        }
        return null;
    }

    @FunctionalInterface
    public interface TeleportGuard {
        String denialReason(ServerPlayer player);
    }

    /** Finds standing room below the hidden barrier ceiling instead of landing on it. */
    private static BlockPos safeTeleportPosition(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int highestFloor = Math.min(height - 1, level.getMaxBuildHeight() - 3);
        BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();
        for (int y = highestFloor; y >= level.getMinBuildHeight(); y--) {
            floor.set(x, y, z);
            BlockState floorState = level.getBlockState(floor);
            if (floorState.is(Blocks.BARRIER) || !supportsTeleport(level, floor, floorState)) {
                continue;
            }
            feet.set(x, y + 1, z);
            head.set(x, y + 2, z);
            if (isOpen(level, feet) && isOpen(level, head)) {
                return new BlockPos(x, y + 1, z);
            }
        }
        return null;
    }

    private static boolean supportsTeleport(
        ServerLevel level, BlockPos position, BlockState state
    ) {
        return !state.getCollisionShape(level, position).isEmpty()
            || state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isOpen(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty()
            && state.getFluidState().isEmpty();
    }

    private static void updateVisit(ServerPlayer player) {
        for (MapContent content : MapContent.all()) {
            if (!player.level().dimension().location().toString().equals(content.dimension())) continue;
            MapContent.Hex hex = content.worldToHex(player.getX(), player.getZ());
            MapContent.Town town = content.townAt(hex.q(), hex.r());
            if (town != null) markVisited(player, town.id());
            MapContent.MapObject object = content.objectAt(hex.q(), hex.r());
            if (object != null && object.teleportable()) markVisited(player, object.id());
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
            for (MapContent.MapObject object : content.objects()) {
                if (object.teleportable() && hasVisited(player, object.id())) result.add(object.id());
            }
        }
        return result;
    }

    private static List<MapPlayer> visiblePlayers(ServerPlayer viewer) {
        if (viewer.getServer() == null) return List.of();
        List<MapPlayer> result = new ArrayList<>();
        for (ServerPlayer player : viewer.getServer().getPlayerList().getPlayers()) {
            if (player == viewer || player.isSpectator() || player.isInvisible()
                || player.level() != viewer.level()) continue;
            result.add(new MapPlayer(player.getGameProfile().getName(), player.getX(), player.getZ()));
        }
        return List.copyOf(result);
    }

    private static boolean isAdministrator(ServerPlayer player) {
        return player.getServer() != null
            && player.getServer().getPlayerList().isOp(player.getGameProfile());
    }

    public record ClientSnapshot(
        boolean administrator,
        boolean creative,
        Set<String> visited,
        List<MapPlayer> players,
        String message,
        boolean teleportSucceeded,
        long revision
    ) {}

    public record MapPlayer(String name, double x, double z) {}

    public record SelectionSnapshot(
        String token,
        boolean accepted,
        String message,
        long revision
    ) {}

    public record MapStateRequestPayload() implements CustomPacketPayload {
        public static final Type<MapStateRequestPayload> TYPE = new Type<>(id("map_state_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapStateRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new MapStateRequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapStatePayload(
        boolean administrator,
        boolean creative,
        List<String> visited,
        List<MapPlayer> players
    ) implements CustomPacketPayload {
        public static final Type<MapStatePayload> TYPE = new Type<>(id("map_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(MapStatePayload::write, MapStatePayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(administrator);
            buffer.writeBoolean(creative);
            buffer.writeVarInt(visited.size());
            for (String value : visited) buffer.writeUtf(value);
            buffer.writeVarInt(players.size());
            for (MapPlayer player : players) {
                buffer.writeUtf(player.name(), 16);
                buffer.writeDouble(player.x());
                buffer.writeDouble(player.z());
            }
        }
        private static MapStatePayload read(RegistryFriendlyByteBuf buffer) {
            boolean administrator = buffer.readBoolean();
            boolean creative = buffer.readBoolean();
            int size = Math.max(0, Math.min(256, buffer.readVarInt()));
            List<String> visited = new ArrayList<>(size);
            for (int index = 0; index < size; index++) visited.add(buffer.readUtf());
            int playerCount = Math.max(0, Math.min(128, buffer.readVarInt()));
            List<MapPlayer> players = new ArrayList<>(playerCount);
            for (int index = 0; index < playerCount; index++) {
                players.add(new MapPlayer(buffer.readUtf(16), buffer.readDouble(), buffer.readDouble()));
            }
            return new MapStatePayload(
                administrator, creative, List.copyOf(visited), List.copyOf(players)
            );
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

    public record MapSelectionOpenPayload(String token) implements CustomPacketPayload {
        public static final Type<MapSelectionOpenPayload> TYPE = new Type<>(id("map_selection_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapSelectionOpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(MapSelectionOpenPayload::write, MapSelectionOpenPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUtf(token); }
        private static MapSelectionOpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new MapSelectionOpenPayload(buffer.readUtf());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapSelectionSubmitPayload(
        String token, int generation, int q, int r
    ) implements CustomPacketPayload {
        public static final Type<MapSelectionSubmitPayload> TYPE = new Type<>(id("map_selection_submit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapSelectionSubmitPayload> STREAM_CODEC =
            StreamCodec.ofMember(MapSelectionSubmitPayload::write, MapSelectionSubmitPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token);
            buffer.writeVarInt(generation);
            buffer.writeVarInt(q);
            buffer.writeVarInt(r);
        }
        private static MapSelectionSubmitPayload read(RegistryFriendlyByteBuf buffer) {
            return new MapSelectionSubmitPayload(
                buffer.readUtf(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()
            );
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapSelectionCancelPayload(String token) implements CustomPacketPayload {
        public static final Type<MapSelectionCancelPayload> TYPE = new Type<>(id("map_selection_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapSelectionCancelPayload> STREAM_CODEC =
            StreamCodec.ofMember(MapSelectionCancelPayload::write, MapSelectionCancelPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUtf(token); }
        private static MapSelectionCancelPayload read(RegistryFriendlyByteBuf buffer) {
            return new MapSelectionCancelPayload(buffer.readUtf());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MapSelectionResultPayload(
        String token, boolean accepted, String message
    ) implements CustomPacketPayload {
        public static final Type<MapSelectionResultPayload> TYPE = new Type<>(id("map_selection_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapSelectionResultPayload> STREAM_CODEC =
            StreamCodec.ofMember(MapSelectionResultPayload::write, MapSelectionResultPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token);
            buffer.writeBoolean(accepted);
            buffer.writeUtf(message);
        }
        private static MapSelectionResultPayload read(RegistryFriendlyByteBuf buffer) {
            return new MapSelectionResultPayload(
                buffer.readUtf(), buffer.readBoolean(), buffer.readUtf()
            );
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record PendingSelection(String token, long expiresAtEpochMilli) {}

    private static final class PendingTeleport {
        private final MapContent content;
        private final ResourceKey<Level> dimension;
        private final MapContent.Town town;
        private final MapContent.MapObject object;
        private final int targetQ;
        private final int targetR;
        private final int teleportAt;
        private int brightenAt;
        private boolean teleported;

        private PendingTeleport(
            MapContent content,
            ResourceKey<Level> dimension,
            MapContent.Town town,
            MapContent.MapObject object,
            int targetQ,
            int targetR,
            int teleportAt
        ) {
            this.content = content;
            this.dimension = dimension;
            this.town = town;
            this.object = object;
            this.targetQ = targetQ;
            this.targetR = targetR;
            this.teleportAt = teleportAt;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }
}
