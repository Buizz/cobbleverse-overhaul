package dev.buizz.cobbleventure.casino;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Synchronizes the server-owned casino balance while a player is inside the casino. */
public final class CasinoHudNetwork {
    private static final String VERSION = "1";
    private static final int SYNC_INTERVAL_TICKS = 10;
    private static final double CASINO_SCAN_RADIUS = 56.0D;
    private static final ResourceLocation BUILDING_INTERIORS = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure", "building_interiors"
    );
    private static final Map<UUID, CasinoHudPayload> LAST_SENT = new HashMap<>();
    private static volatile boolean clientVisible;
    private static volatile long clientBalance;

    private CasinoHudNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(CasinoHudNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(CasinoHudNetwork::onServerTick);
        NeoForge.EVENT_BUS.addListener(CasinoHudNetwork::onPlayerLoggedOut);
    }

    public static boolean clientVisible() {
        return clientVisible;
    }

    public static long clientBalance() {
        return clientBalance;
    }

    /** Pushes a fresh value immediately after an exchange instead of waiting for the next poll. */
    public static void syncNow(ServerPlayer player) {
        sendIfChanged(player, isInsideCasino(player), true);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
            CasinoHudPayload.TYPE, CasinoHudPayload.STREAM_CODEC,
            CasinoHudNetwork::handleSnapshot
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            sendIfChanged(player, isInsideCasino(player), false);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    private static void sendIfChanged(ServerPlayer player, boolean visible, boolean force) {
        long balance = visible
            ? net.narrnouille.cobblemoncasino.data.PlayerCasinoBalanceData
                .get(player.getServer()).getBalance(player.getUUID())
            : 0L;
        CasinoHudPayload payload = new CasinoHudPayload(visible, balance);
        if (!force && payload.equals(LAST_SENT.get(player.getUUID()))) return;
        LAST_SENT.put(player.getUUID(), payload);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static boolean isInsideCasino(ServerPlayer player) {
        if (!player.level().dimension().location().equals(BUILDING_INTERIORS)) return false;
        AABB scan = new AABB(player.blockPosition()).inflate(
            CASINO_SCAN_RADIUS, 16.0D, CASINO_SCAN_RADIUS
        );
        return !player.serverLevel().getEntities(
            (Entity)null, scan,
            entity -> entity.getTags().contains(CasinoCashier.CASHIER_TAG)
        ).isEmpty();
    }

    private static void handleSnapshot(CasinoHudPayload payload, IPayloadContext context) {
        clientVisible = payload.visible();
        clientBalance = Math.max(0L, payload.balance());
    }

    public record CasinoHudPayload(boolean visible, long balance) implements CustomPacketPayload {
        private static final Type<CasinoHudPayload> TYPE = new Type<>(id("hud_snapshot"));
        private static final StreamCodec<RegistryFriendlyByteBuf, CasinoHudPayload> STREAM_CODEC =
            StreamCodec.ofMember(CasinoHudPayload::write, CasinoHudPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(visible);
            buffer.writeLong(balance);
        }

        private static CasinoHudPayload read(RegistryFriendlyByteBuf buffer) {
            return new CasinoHudPayload(buffer.readBoolean(), buffer.readLong());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventureCasino.MOD_ID, path);
    }
}
