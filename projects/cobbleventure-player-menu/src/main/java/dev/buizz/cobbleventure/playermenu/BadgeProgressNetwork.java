package dev.buizz.cobbleventure.playermenu;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Stores item-independent Gym Badge progress and synchronizes it to the trainer card. */
public final class BadgeProgressNetwork {
    private static final String VERSION = "1";
    private static final String DATA_KEY = "cobbleventureBadges";
    private static volatile List<String> clientBadges = List.of();

    private BadgeProgressNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BadgeProgressNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(BadgeProgressNetwork::registerCommands);
        NeoForge.EVENT_BUS.addListener(BadgeProgressNetwork::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(BadgeProgressNetwork::onPlayerLoggedIn);
    }

    public static List<String> clientBadges() {
        return clientBadges;
    }

    /** Returns whether the server-side player progression contains this badge. */
    public static boolean hasBadge(ServerPlayer player, String badge) {
        return badges(player).contains(badge);
    }

    public static void requestSnapshot() {
        clientBadges = List.of();
        PacketDistributor.sendToServer(new BadgeRequestPayload());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(BadgeRequestPayload.TYPE, BadgeRequestPayload.STREAM_CODEC, BadgeProgressNetwork::handleRequest);
        registrar.playToClient(BadgeSnapshotPayload.TYPE, BadgeSnapshotPayload.STREAM_CODEC, BadgeProgressNetwork::handleSnapshot);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cobbleventure_badge")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("grant")
                .then(Commands.argument("players", EntityArgument.players())
                    .then(Commands.argument("badge", StringArgumentType.string())
                        .executes(context -> {
                            String badge = StringArgumentType.getString(context, "badge");
                            if (ResourceLocation.tryParse(badge) == null || !badge.startsWith("cobbleventure:badge/")) return 0;
                            int targets = 0;
                            for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) {
                                targets++;
                                Set<String> badges = badges(player);
                                if (badges.add(badge)) {
                                    write(player, badges);
                                    showBadgeObtained(player, badge);
                                }
                                PacketDistributor.sendToPlayer(player, new BadgeSnapshotPayload(List.copyOf(badges)));
                            }
                            return targets;
                        }))))
            .then(Commands.literal("revoke")
                .then(Commands.argument("players", EntityArgument.players())
                    .then(Commands.argument("badge", StringArgumentType.string())
                        .executes(context -> {
                            String badge = StringArgumentType.getString(context, "badge");
                            int changed = 0;
                            for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) {
                                Set<String> badges = badges(player);
                                if (badges.remove(badge)) { write(player, badges); changed++; }
                                PacketDistributor.sendToPlayer(player, new BadgeSnapshotPayload(List.copyOf(badges)));
                            }
                            return changed;
                        }))))
        );
    }

    private static Set<String> badges(ServerPlayer player) {
        Set<String> result = new LinkedHashSet<>();
        ListTag list = player.getPersistentData().getList(DATA_KEY, Tag.TAG_STRING);
        for (int index = 0; index < list.size(); index++) result.add(list.getString(index));
        return result;
    }

    private static void write(ServerPlayer player, Set<String> badges) {
        ListTag list = new ListTag();
        badges.forEach(badge -> list.add(StringTag.valueOf(badge)));
        player.getPersistentData().put(DATA_KEY, list);
    }

    private static void onPlayerClone(PlayerEvent.Clone event) {
        if (!(event.getOriginal() instanceof ServerPlayer original)
            || !(event.getEntity() instanceof ServerPlayer replacement)
            || !original.getPersistentData().contains(DATA_KEY)) {
            return;
        }
        replacement.getPersistentData().put(
            DATA_KEY, original.getPersistentData().getList(DATA_KEY, Tag.TAG_STRING).copy()
        );
        PacketDistributor.sendToPlayer(
            replacement, new BadgeSnapshotPayload(List.copyOf(badges(replacement)))
        );
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(
                player, new BadgeSnapshotPayload(List.copyOf(badges(player)))
            );
        }
    }

    private static void showBadgeObtained(ServerPlayer player, String badge) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(8, 50, 12));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("체육관 배지를 손에 넣었다!")
        ));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal(badgeDisplayName(badge))
        ));
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.85F, 1.15F);
    }

    private static String badgeDisplayName(String badge) {
        return switch (badge) {
            case "cobbleventure:badge/kanto/boulder" -> "돌배지";
            case "cobbleventure:badge/kanto/cascade" -> "블루배지";
            case "cobbleventure:badge/kanto/thunder" -> "오렌지배지";
            case "cobbleventure:badge/kanto/rainbow" -> "무지개배지";
            case "cobbleventure:badge/kanto/soul" -> "핑크배지";
            case "cobbleventure:badge/kanto/marsh" -> "골드배지";
            case "cobbleventure:badge/kanto/volcano" -> "진홍색배지";
            case "cobbleventure:badge/kanto/earth" -> "그린배지";
            default -> "새로운 배지";
        };
    }

    private static void handleRequest(BadgeRequestPayload payload, IPayloadContext context) {
        context.reply(new BadgeSnapshotPayload(List.copyOf(badges((ServerPlayer) context.player()))));
    }

    private static void handleSnapshot(BadgeSnapshotPayload payload, IPayloadContext context) {
        clientBadges = List.copyOf(payload.badges());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }

    public record BadgeRequestPayload() implements CustomPacketPayload {
        static final Type<BadgeRequestPayload> TYPE = new Type<>(id("badge_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, BadgeRequestPayload> STREAM_CODEC = StreamCodec.unit(new BadgeRequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BadgeSnapshotPayload(List<String> badges) implements CustomPacketPayload {
        static final Type<BadgeSnapshotPayload> TYPE = new Type<>(id("badge_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, BadgeSnapshotPayload> STREAM_CODEC = StreamCodec.ofMember(BadgeSnapshotPayload::write, BadgeSnapshotPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(badges.size());
            for (String badge : badges) buffer.writeUtf(badge, 128);
        }
        private static BadgeSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            int size = Math.max(0, Math.min(128, buffer.readVarInt()));
            List<String> badges = new ArrayList<>(size);
            for (int index = 0; index < size; index++) badges.add(buffer.readUtf(128));
            return new BadgeSnapshotPayload(List.copyOf(badges));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
