package dev.buizz.cobbleventure.casino;

import dev.buizz.cobbleventure.casino.client.GachaMachineClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative machine preview and pull protocol. */
public final class GachaMachineNetwork {
    private static final String VERSION = "1";
    private static final long SESSION_TICKS = 20L * 60L * 2L;
    private static final double MAX_DISTANCE_SQUARED = 64.0D;
    private static final int MAX_REWARDS = 512;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private GachaMachineNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(GachaMachineNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(GachaMachineNetwork::onLoggedOut);
    }

    static void open(
        ServerPlayer player, BlockPos anchor, GachaCatalog.Machine machine,
        CobbleventureCasino.GachaUiState state
    ) {
        UUID token = UUID.randomUUID();
        SESSIONS.put(player.getUUID(), new Session(
            token, machine.id, anchor,
            player.serverLevel().getGameTime() + SESSION_TICKS,
            Integer.MIN_VALUE
        ));
        PacketDistributor.sendToPlayer(player, new OpenPayload(
            token, machine.id, machine.display_name, machine.ticket.display_name,
            state.tickets(), state.pullsSinceTarget(), state.hardPityCount(),
            state.selectionPoints(), state.selectionRequired(),
            rewardViews(machine, state.pullsSinceTarget())
        ));
    }

    private static List<RewardView> rewardViews(GachaCatalog.Machine machine, int misses) {
        Map<GachaCatalog.Rarity, Double> rarityWeights = CobbleventureCasino.rarityWeights(machine, misses);
        double rarityTotal = rarityWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        List<RewardView> result = new ArrayList<>();
        for (GachaCatalog.Rarity rarity : machine.rarities) {
            double rewardTotal = rarity.rewards.stream().mapToDouble(entry -> Math.max(0.0D, entry.weight)).sum();
            double rarityChance = rarityTotal <= 0 ? 0 : rarityWeights.getOrDefault(rarity, 0.0D) / rarityTotal;
            for (GachaCatalog.Reward reward : rarity.rewards) {
                if (result.size() >= MAX_REWARDS) return List.copyOf(result);
                double chance = rewardTotal <= 0 ? 0
                    : rarityChance * Math.max(0.0D, reward.weight) / rewardTotal;
                result.add(new RewardView(
                    rarity.id, rarity.display_name,
                    reward.id, reward.kind, reward.value, reward.count,
                    chance, reward.selectable
                ));
            }
        }
        return List.copyOf(result);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, GachaMachineNetwork::handleOpen);
        registrar.playToServer(PullPayload.TYPE, PullPayload.STREAM_CODEC, GachaMachineNetwork::handlePull);
        registrar.playToClient(ResultPayload.TYPE, ResultPayload.STREAM_CODEC, GachaMachineNetwork::handleResult);
    }

    private static void handleOpen(OpenPayload payload, IPayloadContext context) {
        GachaMachineClient.open(payload);
    }

    private static void handlePull(PullPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Session session = SESSIONS.get(player.getUUID());
        int tick = player.getServer().getTickCount();
        if (session == null || !session.token().equals(payload.token())
            || session.expiresAt() < player.serverLevel().getGameTime()
            || player.distanceToSqr(
                session.anchor().getX() + .5D,
                session.anchor().getY() + 1.0D,
                session.anchor().getZ() + .5D
            ) > MAX_DISTANCE_SQUARED
            || tick == session.lastPullTick()) {
            context.reply(ResultPayload.failure(
                payload.token(), "screen.cobbleventure_casino.gacha.invalid", 0
            ));
            return;
        }
        SESSIONS.put(player.getUUID(), new Session(
            session.token(), session.profile(), session.anchor(),
            player.serverLevel().getGameTime() + SESSION_TICKS, tick
        ));
        CobbleventureCasino.PullOutcome outcome =
            CobbleventureCasino.pullForScreen(player, session.profile());
        GachaCatalog.Machine machine = CobbleventureCasino.configuredMachine(session.profile());
        List<RewardView> updatedRewards = outcome.success() && machine != null
            ? rewardViews(machine, outcome.pullsSinceTarget())
            : List.of();
        context.reply(ResultPayload.from(payload.token(), outcome, updatedRewards));
    }

    private static void handleResult(ResultPayload payload, IPayloadContext context) {
        GachaMachineClient.result(payload);
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    public record RewardView(
        String rarityId, String rarityName,
        String rewardId, String kind, String value, int count,
        double chance, boolean selectable
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(rarityId); buffer.writeUtf(rarityName);
            buffer.writeUtf(rewardId); buffer.writeUtf(kind); buffer.writeUtf(value);
            buffer.writeVarInt(count); buffer.writeDouble(chance); buffer.writeBoolean(selectable);
        }
        private static RewardView read(RegistryFriendlyByteBuf buffer) {
            return new RewardView(
                buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                buffer.readVarInt(), buffer.readDouble(), buffer.readBoolean()
            );
        }
    }

    public record OpenPayload(
        UUID token, String profile, String machineName, String ticketName,
        int tickets, int pullsSinceTarget, int hardPityCount,
        int selectionPoints, int selectionRequired,
        List<RewardView> rewards
    ) implements CustomPacketPayload {
        public static final Type<OpenPayload> TYPE = new Type<>(id("gacha_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(OpenPayload::write, OpenPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(token); buffer.writeUtf(profile); buffer.writeUtf(machineName); buffer.writeUtf(ticketName);
            buffer.writeVarInt(tickets); buffer.writeVarInt(pullsSinceTarget); buffer.writeVarInt(hardPityCount);
            buffer.writeVarInt(selectionPoints); buffer.writeVarInt(selectionRequired);
            buffer.writeVarInt(rewards.size());
            rewards.forEach(reward -> reward.write(buffer));
        }
        private static OpenPayload read(RegistryFriendlyByteBuf buffer) {
            UUID token = buffer.readUUID();
            String profile = buffer.readUtf(); String machine = buffer.readUtf(); String ticket = buffer.readUtf();
            int tickets = buffer.readVarInt(); int pulls = buffer.readVarInt(); int hard = buffer.readVarInt();
            int points = buffer.readVarInt(); int required = buffer.readVarInt();
            int size = Math.clamp(buffer.readVarInt(), 0, MAX_REWARDS);
            List<RewardView> rewards = new ArrayList<>(size);
            for (int index = 0; index < size; index++) rewards.add(RewardView.read(buffer));
            return new OpenPayload(token, profile, machine, ticket, tickets, pulls, hard, points, required, List.copyOf(rewards));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PullPayload(UUID token) implements CustomPacketPayload {
        public static final Type<PullPayload> TYPE = new Type<>(id("gacha_pull"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PullPayload> STREAM_CODEC =
            StreamCodec.ofMember(PullPayload::write, PullPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUUID(token); }
        private static PullPayload read(RegistryFriendlyByteBuf buffer) { return new PullPayload(buffer.readUUID()); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ResultPayload(
        UUID token, boolean success, String messageKey, int tickets,
        String rarityId, String rarityName,
        String rewardId, String kind, String value, int count,
        int pullsSinceTarget, int hardPityCount,
        int selectionPoints, int selectionRequired,
        List<RewardView> rewards
    ) implements CustomPacketPayload {
        public static final Type<ResultPayload> TYPE = new Type<>(id("gacha_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ResultPayload> STREAM_CODEC =
            StreamCodec.ofMember(ResultPayload::write, ResultPayload::read);
        static ResultPayload from(
            UUID token, CobbleventureCasino.PullOutcome outcome, List<RewardView> rewards
        ) {
            return new ResultPayload(
                token, outcome.success(), outcome.messageKey(), outcome.tickets(),
                outcome.rarityId(), outcome.rarityName(), outcome.rewardId(),
                outcome.rewardKind(), outcome.rewardValue(), outcome.rewardCount(),
                outcome.pullsSinceTarget(), outcome.hardPityCount(),
                outcome.selectionPoints(), outcome.selectionRequired(), rewards
            );
        }
        static ResultPayload failure(UUID token, String key, int tickets) {
            return new ResultPayload(token, false, key, tickets, "", "", "", "", "", 0, 0, 0, 0, 0, List.of());
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(token); buffer.writeBoolean(success); buffer.writeUtf(messageKey); buffer.writeVarInt(tickets);
            buffer.writeUtf(rarityId); buffer.writeUtf(rarityName); buffer.writeUtf(rewardId); buffer.writeUtf(kind); buffer.writeUtf(value);
            buffer.writeVarInt(count); buffer.writeVarInt(pullsSinceTarget); buffer.writeVarInt(hardPityCount);
            buffer.writeVarInt(selectionPoints); buffer.writeVarInt(selectionRequired);
            buffer.writeVarInt(rewards.size());
            rewards.forEach(reward -> reward.write(buffer));
        }
        private static ResultPayload read(RegistryFriendlyByteBuf buffer) {
            UUID token = buffer.readUUID(); boolean success = buffer.readBoolean();
            String message = buffer.readUtf(); int tickets = buffer.readVarInt();
            String rarityId = buffer.readUtf(); String rarityName = buffer.readUtf();
            String rewardId = buffer.readUtf(); String kind = buffer.readUtf(); String value = buffer.readUtf();
            int count = buffer.readVarInt(); int pulls = buffer.readVarInt(); int hard = buffer.readVarInt();
            int points = buffer.readVarInt(); int required = buffer.readVarInt();
            int size = Math.clamp(buffer.readVarInt(), 0, MAX_REWARDS);
            List<RewardView> rewards = new ArrayList<>(size);
            for (int index = 0; index < size; index++) rewards.add(RewardView.read(buffer));
            return new ResultPayload(
                token, success, message, tickets, rarityId, rarityName,
                rewardId, kind, value, count, pulls, hard, points, required,
                List.copyOf(rewards)
            );
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void pull(UUID token) {
        PacketDistributor.sendToServer(new PullPayload(token));
    }

    private record Session(
        UUID token, String profile, BlockPos anchor, long expiresAt, int lastPullTick
    ) {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventureCasino.MOD_ID, path);
    }
}
