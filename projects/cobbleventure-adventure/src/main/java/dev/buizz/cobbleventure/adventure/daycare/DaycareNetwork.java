package dev.buizz.cobbleventure.adventure.daycare;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.buizz.cobbleventure.adventure.CobbleventureAdventure;
import dev.buizz.cobbleventure.adventure.daycare.client.DaycareClient;
import dev.buizz.cobbleventure.adventure.event.EventNpcBindingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative transport for the daycare NPC screen. */
public final class DaycareNetwork {
    private static final String VERSION = "1";
    private static final String DAYCARE_SCRIPT =
        "cobbleventure:event_script/facilities/daycare";
    private static final double MAX_NPC_DISTANCE_SQUARED = 64.0D;

    private DaycareNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(DaycareNetwork::registerPayloads);
    }

    public static void open(ServerPlayer player, Entity npc) {
        PacketDistributor.sendToPlayer(player, snapshot(player, npc.getUUID()));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(ViewPayload.TYPE, ViewPayload.STREAM_CODEC, DaycareNetwork::handleView);
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, DaycareNetwork::handleAction);
    }

    private static void handleView(ViewPayload payload, IPayloadContext context) {
        DaycareClient.open(payload);
    }

    private static void handleAction(ActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Entity npc = player.serverLevel().getEntity(payload.npcId());
        if (npc == null || !npc.isAlive()
            || player.distanceToSqr(npc) > MAX_NPC_DISTANCE_SQUARED
            || !isDaycareNpc(npc)) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_adventure.daycare.npc_too_far"
            ));
            return;
        }
        switch (payload.action()) {
            case DEPOSIT -> DaycareService.deposit(
                player, payload.firstSlot(), payload.secondSlot(),
                player.level().dimension().location(), npc.blockPosition()
            );
            case COLLECT -> DaycareService.collect(player);
            case CANCEL -> DaycareService.cancel(player);
            case REFRESH -> DaycareService.status(player);
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, npc.getUUID()));
    }

    private static boolean isDaycareNpc(Entity entity) {
        try {
            return EventNpcBindingRepository.instance().findByEntityTags(entity.getTags())
                .map(binding -> DAYCARE_SCRIPT.equals(binding.scriptId()))
                .orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ViewPayload snapshot(ServerPlayer player, UUID npcId) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        List<String> slots = new ArrayList<>(6);
        for (int index = 0; index < 6; index++) {
            Pokemon pokemon = party.get(index);
            slots.add(pokemon == null ? "" : pokemon.getDisplayName(false).getString()
                + "  Lv." + pokemon.getLevel());
        }
        DaycareJob job = DaycareSavedData.get(player.getServer())
            .find(player.getUUID()).orElse(null);
        String state = "EMPTY";
        long remainingMinutes = 0L;
        if (job != null) {
            job = ensureEgg(player, job);
            state = job.hasEgg() ? "READY" : "BREEDING";
            remainingMinutes = Math.max(1L, Duration.ofMillis(
                job.readyAtMillis() - Instant.now().toEpochMilli()
            ).toMinutes());
        }
        return new ViewPayload(npcId, state, DaycareService.SERVICE_FEE, remainingMinutes, slots);
    }

    private static DaycareJob ensureEgg(ServerPlayer player, DaycareJob job) {
        if (job.hasEgg() || !job.isTimeReady(Instant.now().toEpochMilli())) return job;
        DaycareService.status(player);
        return DaycareSavedData.get(player.getServer()).find(player.getUUID()).orElse(job);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventureAdventure.MOD_ID, path);
    }

    public enum Action {
        DEPOSIT,
        COLLECT,
        CANCEL,
        REFRESH
    }

    public record ViewPayload(
        UUID npcId, String state, long fee, long remainingMinutes, List<String> partySlots
    ) implements CustomPacketPayload {
        public static final Type<ViewPayload> TYPE = new Type<>(id("daycare_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ViewPayload> STREAM_CODEC =
            StreamCodec.ofMember(ViewPayload::write, ViewPayload::read);

        public ViewPayload {
            Objects.requireNonNull(npcId, "npcId");
            Objects.requireNonNull(state, "state");
            partySlots = List.copyOf(partySlots);
            if (partySlots.size() != 6) throw new IllegalArgumentException("partySlots must contain 6 entries");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(npcId);
            buffer.writeUtf(state);
            buffer.writeLong(fee);
            buffer.writeLong(remainingMinutes);
            partySlots.forEach(buffer::writeUtf);
        }

        private static ViewPayload read(RegistryFriendlyByteBuf buffer) {
            UUID npcId = buffer.readUUID();
            String state = buffer.readUtf();
            long fee = buffer.readLong();
            long remaining = buffer.readLong();
            List<String> slots = new ArrayList<>(6);
            for (int index = 0; index < 6; index++) slots.add(buffer.readUtf());
            return new ViewPayload(npcId, state, fee, remaining, slots);
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ActionPayload(
        UUID npcId, Action action, int firstSlot, int secondSlot
    ) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(id("daycare_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> STREAM_CODEC =
            StreamCodec.ofMember(ActionPayload::write, ActionPayload::read);

        public ActionPayload {
            Objects.requireNonNull(npcId, "npcId");
            Objects.requireNonNull(action, "action");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(npcId);
            buffer.writeEnum(action);
            buffer.writeVarInt(firstSlot);
            buffer.writeVarInt(secondSlot);
        }

        private static ActionPayload read(RegistryFriendlyByteBuf buffer) {
            return new ActionPayload(
                buffer.readUUID(), buffer.readEnum(Action.class),
                buffer.readVarInt(), buffer.readVarInt()
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
