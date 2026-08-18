package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.api.storage.pc.link.PCLink;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.net.messages.client.storage.pc.OpenPCPacket;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-owned unlocks and level-cap state shared by menus, NPCs and gameplay. */
public final class ProgressionNetwork {
    private static final String VERSION = "1";
    private static final String FEATURE_PREFIX = "cobbleventureFeature.";
    private static final String LEVEL_CAP_KEY = "cobbleventureCurrentLevelCap";
    private static final int DEFAULT_LEVEL_CAP = 5;
    private static volatile ClientSnapshot clientSnapshot = ClientSnapshot.locked();

    private ProgressionNetwork() {}

    public enum Feature {
        MAP("map"),
        SETTLEMENT_TELEPORT("settlement_teleport"),
        PC("pc");

        private final String id;
        Feature(String id) { this.id = id; }
        public String id() { return id; }

        static Feature parse(String value) {
            for (Feature feature : values()) if (feature.id.equals(value)) return feature;
            return null;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ProgressionNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(ProgressionNetwork::registerCommands);
    }

    public static ClientSnapshot clientSnapshot() { return clientSnapshot; }

    public static boolean isUnlocked(ServerPlayer player, Feature feature) {
        return player.getPersistentData().getBoolean(FEATURE_PREFIX + feature.id);
    }

    public static int levelCap(ServerPlayer player) {
        int stored = player.getPersistentData().getInt(LEVEL_CAP_KEY);
        return stored <= 0 ? DEFAULT_LEVEL_CAP : Math.max(1, Math.min(100, stored));
    }

    public static void requestSnapshot() {
        PacketDistributor.sendToServer(new ProgressRequestPayload());
    }

    public static void requestPc() {
        PacketDistributor.sendToServer(new OpenPcPayload());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(ProgressRequestPayload.TYPE, ProgressRequestPayload.STREAM_CODEC, ProgressionNetwork::handleRequest);
        registrar.playToClient(ProgressSnapshotPayload.TYPE, ProgressSnapshotPayload.STREAM_CODEC, ProgressionNetwork::handleSnapshot);
        registrar.playToServer(OpenPcPayload.TYPE, OpenPcPayload.STREAM_CODEC, ProgressionNetwork::handleOpenPc);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        var unlockFeature = featureArgument()
            .executes(context -> setFeature(
                context.getSource(),
                EntityArgument.getPlayers(context, "players"),
                StringArgumentType.getString(context, "feature"), true));
        var lockFeature = featureArgument()
            .executes(context -> setFeature(
                context.getSource(),
                EntityArgument.getPlayers(context, "players"),
                StringArgumentType.getString(context, "feature"), false));
        var enableFeature = featureArgument()
            .executes(context -> setFeature(
                context.getSource(),
                EntityArgument.getPlayers(context, "players"),
                StringArgumentType.getString(context, "feature"), true));
        var disableFeature = featureArgument()
            .executes(context -> setFeature(
                context.getSource(),
                EntityArgument.getPlayers(context, "players"),
                StringArgumentType.getString(context, "feature"), false));
        var levelCap = Commands.argument("level", IntegerArgumentType.integer(1, 100))
            .executes(context -> {
                int level = IntegerArgumentType.getInteger(context, "level");
                int changed = 0;
                for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) {
                    if (levelCap(player) != level) changed++;
                    player.getPersistentData().putInt(LEVEL_CAP_KEY, level);
                    sync(player);
                }
                return changed;
            });
        dispatcher.register(Commands.literal("cobbleventure_progress")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("unlock")
                .then(Commands.argument("players", EntityArgument.players()).then(unlockFeature)))
            .then(Commands.literal("lock")
                .then(Commands.argument("players", EntityArgument.players()).then(lockFeature)))
            .then(Commands.literal("on")
                .then(Commands.argument("players", EntityArgument.players()).then(enableFeature)))
            .then(Commands.literal("off")
                .then(Commands.argument("players", EntityArgument.players()).then(disableFeature)))
            .then(Commands.literal("level_cap")
                .then(Commands.argument("players", EntityArgument.players()).then(levelCap))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
        CommandSourceStack, String
    > featureArgument() {
        return Commands.argument("feature", StringArgumentType.word())
            .suggests((context, builder) -> {
                for (Feature feature : Feature.values()) {
                    builder.suggest(feature.id());
                }
                return builder.buildFuture();
            });
    }

    private static int setFeature(
        CommandSourceStack source, Iterable<ServerPlayer> players,
        String id, boolean unlocked
    ) {
        Feature feature = Feature.parse(id.toLowerCase(Locale.ROOT));
        if (feature == null) {
            source.sendFailure(Component.literal(
                "[Cobbleventure] 알 수 없는 메뉴 기능입니다: " + id
            ));
            return 0;
        }
        int changed = 0;
        int targets = 0;
        for (ServerPlayer player : players) {
            targets++;
            if (isUnlocked(player, feature) != unlocked) changed++;
            player.getPersistentData().putBoolean(FEATURE_PREFIX + feature.id, unlocked);
            sync(player);
        }
        int changedCount = changed;
        int targetCount = targets;
        source.sendSuccess(() -> Component.literal(
            "[Cobbleventure] " + feature.id + " 메뉴 기능 "
                + (unlocked ? "ON" : "OFF") + " · 대상 " + targetCount
                + "명, 변경 " + changedCount + "명"
        ), true);
        return changed;
    }

    private static void handleRequest(ProgressRequestPayload payload, IPayloadContext context) {
        context.reply(snapshot((ServerPlayer) context.player()));
    }

    private static void handleSnapshot(ProgressSnapshotPayload payload, IPayloadContext context) {
        clientSnapshot = new ClientSnapshot(payload.map(), payload.teleport(), payload.pc(), payload.levelCap());
    }

    private static void handleOpenPc(OpenPcPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (!isUnlocked(player, Feature.PC)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("포켓몬 PC는 아직 사용할 수 없습니다."), true);
            return;
        }
        if (PlayerExtensionsKt.isInBattle(player)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("전투 중에는 포켓몬 PC를 열 수 없습니다."), true);
            return;
        }
        var pc = PlayerExtensionsKt.pc(player);
        PCLinkManager.INSTANCE.addLink(new PCLink(pc, player.getUUID()));
        new OpenPCPacket(pc).sendToPlayer(player);
    }

    private static ProgressSnapshotPayload snapshot(ServerPlayer player) {
        return new ProgressSnapshotPayload(
            isUnlocked(player, Feature.MAP),
            isUnlocked(player, Feature.SETTLEMENT_TELEPORT),
            isUnlocked(player, Feature.PC),
            levelCap(player)
        );
    }

    private static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, snapshot(player));
    }

    public record ClientSnapshot(boolean map, boolean settlementTeleport, boolean pc, int levelCap) {
        static ClientSnapshot locked() { return new ClientSnapshot(false, false, false, DEFAULT_LEVEL_CAP); }
        public boolean unlocked(Feature feature) {
            return switch (feature) {
                case MAP -> map;
                case SETTLEMENT_TELEPORT -> settlementTeleport;
                case PC -> pc;
            };
        }
    }

    public record ProgressRequestPayload() implements CustomPacketPayload {
        static final Type<ProgressRequestPayload> TYPE = new Type<>(id("progress_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, ProgressRequestPayload> STREAM_CODEC = StreamCodec.unit(new ProgressRequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ProgressSnapshotPayload(boolean map, boolean teleport, boolean pc, int levelCap) implements CustomPacketPayload {
        static final Type<ProgressSnapshotPayload> TYPE = new Type<>(id("progress_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, ProgressSnapshotPayload> STREAM_CODEC = StreamCodec.ofMember(ProgressSnapshotPayload::write, ProgressSnapshotPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(map); buffer.writeBoolean(teleport); buffer.writeBoolean(pc); buffer.writeVarInt(levelCap);
        }
        private static ProgressSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            return new ProgressSnapshotPayload(buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenPcPayload() implements CustomPacketPayload {
        static final Type<OpenPcPayload> TYPE = new Type<>(id("open_pc"));
        static final StreamCodec<RegistryFriendlyByteBuf, OpenPcPayload> STREAM_CODEC = StreamCodec.unit(new OpenPcPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }
}
