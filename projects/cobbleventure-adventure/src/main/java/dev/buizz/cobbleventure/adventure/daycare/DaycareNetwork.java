package dev.buizz.cobbleventure.adventure.daycare;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.buizz.cobbleventure.adventure.CobbleventureAdventure;
import dev.buizz.cobbleventure.adventure.daycare.client.DaycareClient;
import dev.buizz.cobbleventure.adventure.event.EventNpcBindingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
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

/** Server-authoritative transport for the multi-Pokemon daycare screen. */
public final class DaycareNetwork {
    private static final String VERSION = "6";
    private static final String DAYCARE_SCRIPT = "cobbleventure:event_script/facilities/daycare";
    private static final double MAX_NPC_DISTANCE_SQUARED = 64.0D;

    private DaycareNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(DaycareNetwork::registerPayloads);
    }

    public static void open(ServerPlayer player, Entity npc) {
        PacketDistributor.sendToPlayer(player, snapshot(player, npc.getUUID(), Component.empty()));
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
            Component feedback = Component.translatable(
                "message.cobbleventure_adventure.daycare.npc_too_far"
            );
            player.sendSystemMessage(feedback);
            PacketDistributor.sendToPlayer(player, snapshot(player, payload.npcId(), feedback));
            return;
        }

        Component feedback;
        switch (payload.action()) {
            case DEPOSIT -> {
                var paddock = CobbleventureAdventure.daycarePaddock(player);
                if (paddock == null) {
                    feedback = Component.translatable(
                        "message.cobbleventure_adventure.daycare.paddock_unavailable"
                    );
                    player.sendSystemMessage(feedback);
                } else {
                    feedback = DaycareService.depositWithFeedback(
                        player, payload.slot(), payload.training(),
                        paddock.dimension(), paddock.position()
                    ).message();
                }
            }
            case WITHDRAW -> feedback = DaycareService.withdrawWithFeedback(
                player, payload.slot()
            ).message();
            case COLLECT -> feedback = DaycareService.collectWithFeedback(player).message();
            case REFRESH -> {
                DaycareService.status(player);
                feedback = Component.empty();
            }
            default -> throw new IllegalStateException("Unknown daycare action");
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, npc.getUUID(), feedback));
    }

    private static boolean isDaycareNpc(Entity entity) {
        try {
            return EventNpcBindingRepository.instance().findByEntityTags(entity.getTags())
                .map(binding -> DAYCARE_SCRIPT.equals(binding.scriptId())).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ViewPayload snapshot(
        ServerPlayer player, UUID npcId, Component feedback
    ) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        List<PokemonView> partySlots = new ArrayList<>(6);
        for (int index = 0; index < 6; index++) {
            Pokemon pokemon = party.get(index);
            partySlots.add(pokemon == null ? PokemonView.empty() : viewOf(player, pokemon, false));
        }

        DaycareJob job = DaycareService.refreshState(player);
        List<PokemonView> stored = new ArrayList<>();
        if (job != null) {
            for (DaycareJob.StoredPokemon value : job.pokemon()) {
                Pokemon pokemon = new Pokemon().loadFromNBT(
                    player.registryAccess(), value.data().copy()
                );
                stored.add(viewOf(player, pokemon, value.training()));
            }
        }
        boolean compatible = DaycareService.hasCompatiblePair(player, job);
        return new ViewPayload(
            npcId,
            DaycareService.SERVICE_FEE, DaycareService.TRAINING_COST_PER_EXPERIENCE,
            DaycareService.MAX_TRAINING_EXPERIENCE,
            job == null ? 0L : DaycareService.remainingMinutes(job),
            partySlots, stored, job == null ? 0 : job.eggCount(), compatible, feedback
        );
    }

    private static PokemonView viewOf(
        ServerPlayer player, Pokemon pokemon, boolean training
    ) {
        return new PokemonView(
            pokemon.getDisplayName(false).getString(), pokemon.getLevel(), training,
            pokemon.saveToNBT(player.registryAccess(), new CompoundTag())
        );
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventureAdventure.MOD_ID, path);
    }

    public enum Action { DEPOSIT, WITHDRAW, COLLECT, REFRESH }

    public record PokemonView(
        String name, int level, boolean training, CompoundTag data
    ) {
        public PokemonView {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(data, "data");
            data = data.copy();
        }

        public static PokemonView empty() {
            return new PokemonView("", 0, false, new CompoundTag());
        }

        public boolean emptySlot() { return name.isBlank(); }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(name);
            buffer.writeVarInt(level);
            buffer.writeBoolean(training);
            buffer.writeNbt(data);
        }

        private static PokemonView read(RegistryFriendlyByteBuf buffer) {
            String name = buffer.readUtf();
            int level = buffer.readVarInt();
            boolean training = buffer.readBoolean();
            CompoundTag data = buffer.readNbt();
            return new PokemonView(
                name, level, training,
                data == null ? new CompoundTag() : data
            );
        }
    }

    public record ViewPayload(
        UUID npcId,
        long fee, long trainingCostPerExperience, int maxTrainingExperience,
        long remainingMinutes,
        List<PokemonView> partySlots, List<PokemonView> storedPokemon,
        int eggCount, boolean compatiblePair, Component feedback
    ) implements CustomPacketPayload {
        public static final Type<ViewPayload> TYPE = new Type<>(id("daycare_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ViewPayload> STREAM_CODEC =
            StreamCodec.ofMember(ViewPayload::write, ViewPayload::read);

        public ViewPayload {
            Objects.requireNonNull(npcId, "npcId");
            Objects.requireNonNull(feedback, "feedback");
            partySlots = List.copyOf(partySlots);
            storedPokemon = List.copyOf(storedPokemon);
            if (partySlots.size() != 6) throw new IllegalArgumentException("partySlots must contain 6 entries");
            if (storedPokemon.size() > DaycareJob.MAX_POKEMON) throw new IllegalArgumentException("too many stored Pokemon");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(npcId);
            buffer.writeLong(fee);
            buffer.writeLong(trainingCostPerExperience);
            buffer.writeVarInt(maxTrainingExperience);
            buffer.writeLong(remainingMinutes);
            partySlots.forEach(value -> value.write(buffer));
            buffer.writeVarInt(storedPokemon.size());
            storedPokemon.forEach(value -> value.write(buffer));
            buffer.writeVarInt(eggCount);
            buffer.writeBoolean(compatiblePair);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buffer, feedback);
        }

        private static ViewPayload read(RegistryFriendlyByteBuf buffer) {
            UUID npcId = buffer.readUUID();
            long fee = buffer.readLong();
            long trainingCost = buffer.readLong();
            int maxTrainingExperience = buffer.readVarInt();
            long remaining = buffer.readLong();
            List<PokemonView> party = new ArrayList<>(6);
            for (int index = 0; index < 6; index++) party.add(PokemonView.read(buffer));
            int storedCount = buffer.readVarInt();
            List<PokemonView> stored = new ArrayList<>(storedCount);
            for (int index = 0; index < storedCount; index++) stored.add(PokemonView.read(buffer));
            int eggs = buffer.readVarInt();
            boolean compatible = buffer.readBoolean();
            Component feedback = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buffer);
            return new ViewPayload(
                npcId, fee, trainingCost, maxTrainingExperience, remaining,
                party, stored, eggs, compatible, feedback
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ActionPayload(UUID npcId, Action action, int slot, boolean training)
        implements CustomPacketPayload {
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
            buffer.writeVarInt(slot);
            buffer.writeBoolean(training);
        }

        private static ActionPayload read(RegistryFriendlyByteBuf buffer) {
            return new ActionPayload(
                buffer.readUUID(), buffer.readEnum(Action.class), buffer.readVarInt(),
                buffer.readBoolean()
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
