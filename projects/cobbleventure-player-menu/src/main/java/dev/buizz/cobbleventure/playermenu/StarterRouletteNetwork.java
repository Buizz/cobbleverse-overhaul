package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.starter.StarterChosenEvent;
import com.cobblemon.mod.common.api.storage.player.GeneralPlayerData;
import com.cobblemon.mod.common.config.starter.StarterCategory;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.playermenu.client.StarterRouletteClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Server-authoritative starter roulette sessions and claims. */
public final class StarterRouletteNetwork {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VERSION = "1";
    private static final int STARTER_LEVEL = 5;
    private static final int SEQUENCE_LENGTH = 96;
    private static final int OPEN_DELAY_TICKS = 2;
    private static final long SESSION_LIFETIME_MILLIS = 5L * 60L * 1000L;
    private static final String STARTER_RECEIVED_OBJECTIVE = "cv_starter_recv";
    private static final ResourceLocation POKEDEX_ITEM = ResourceLocation.fromNamespaceAndPath(
        "cobblemon", "pokedex_red"
    );
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Integer> PENDING_OPENS = new HashMap<>();

    private StarterRouletteNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(StarterRouletteNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(StarterRouletteCommands::register);
        NeoForge.EVENT_BUS.addListener(StarterRouletteNetwork::onServerTick);
        CobblemonEvents.STARTER_CHOSEN.subscribe(
            (Consumer<StarterChosenEvent>) event -> event.getPokemon().setLevel(STARTER_LEVEL)
        );
    }

    static int queueOpen(ServerPlayer player) {
        int openAt = player.getServer().getTickCount() + OPEN_DELAY_TICKS;
        PENDING_OPENS.put(player.getUUID(), openAt);
        LOGGER.info("Starter roulette queued: player={}, openAtTick={}", player.getGameProfile().getName(), openAt);
        return 1;
    }

    static int open(ServerPlayer player) {
        GeneralPlayerData data = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
        int partySize = Cobblemon.INSTANCE.getStorage().getParty(player).occupied();
        LOGGER.info(
            "Starter roulette opening: player={}, starterSelected={}, starterLocked={}, partySize={}",
            player.getGameProfile().getName(), data.getStarterSelected(), data.getStarterLocked(), partySize
        );
        if (hasReceivedPokemon(player, data)) {
            LOGGER.info("Starter roulette rejected for {}: starter already received", player.getGameProfile().getName());
            player.sendSystemMessage(Component.translatable("commands.cobbleventure_player_menu.starter.already_selected"));
            return 0;
        }
        if (data.getStarterLocked()) {
            LOGGER.info("Starter roulette rejected for {}: starter selection is locked", player.getGameProfile().getName());
            player.sendSystemMessage(Component.translatable("commands.cobbleventure_player_menu.starter.locked"));
            return 0;
        }

        List<StarterEntry> pool = starterPool(player);
        if (pool.isEmpty()) {
            LOGGER.warn("Starter roulette rejected for {}: configured starter pool is empty", player.getGameProfile().getName());
            player.sendSystemMessage(Component.translatable("commands.cobbleventure_player_menu.starter.empty"));
            return 0;
        }

        List<StarterEntry> sequence = shuffledSequence(pool);
        UUID token = UUID.randomUUID();
        SESSIONS.put(player.getUUID(), new Session(token, sequence, System.currentTimeMillis() + SESSION_LIFETIME_MILLIS));
        List<String> species = sequence.stream().map(StarterEntry::species).toList();
        StarterRouletteOpenPayload payload = new StarterRouletteOpenPayload(token, species);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
        LOGGER.info(
            "Starter roulette payload sent: player={}, poolSize={}, sequenceSize={}, token={}",
            player.getGameProfile().getName(), pool.size(), sequence.size(), token
        );
        return 1;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        int currentTick = event.getServer().getTickCount();
        List<UUID> ready = PENDING_OPENS.entrySet().stream()
            .filter(entry -> entry.getValue() <= currentTick)
            .map(Map.Entry::getKey)
            .toList();
        for (UUID playerId : ready) {
            PENDING_OPENS.remove(playerId);
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                LOGGER.warn("Starter roulette opening cancelled: player {} is no longer online", playerId);
                continue;
            }
            open(player);
        }
    }

    static int syncState(ServerPlayer player) {
        GeneralPlayerData data = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
        int partySize = Cobblemon.INSTANCE.getStorage().getParty(player).occupied();
        boolean selected = data.getStarterSelected() || partySize > 0;
        setStarterReceivedScore(player, selected);
        if (selected) ensurePokedex(player);
        LOGGER.info(
            "Starter state synchronized: player={}, starterSelected={}, partySize={}, received={}",
            player.getGameProfile().getName(), data.getStarterSelected(), partySize, selected
        );
        return selected ? 1 : 0;
    }

    public static void claim(UUID token, int sequenceIndex) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new StarterRouletteClaimPayload(token, sequenceIndex));
    }

    private static List<StarterEntry> starterPool(ServerPlayer player) {
        List<StarterEntry> result = new ArrayList<>();
        for (StarterCategory category : Cobblemon.INSTANCE.getStarterHandler().getStarterList(player)) {
            for (int index = 0; index < category.getPokemon().size(); index++) {
                String species = category.getPokemon().get(index).asRenderablePokemon()
                    .getSpecies().getResourceIdentifier().toString();
                result.add(new StarterEntry(category.getName(), index, species));
            }
        }
        return result;
    }

    private static List<StarterEntry> shuffledSequence(List<StarterEntry> pool) {
        List<StarterEntry> result = new ArrayList<>(SEQUENCE_LENGTH);
        List<StarterEntry> batch = new ArrayList<>(pool);
        while (result.size() < SEQUENCE_LENGTH) {
            Collections.shuffle(batch);
            for (StarterEntry entry : batch) {
                if (result.size() >= SEQUENCE_LENGTH) break;
                if (!result.isEmpty() && result.getLast().equals(entry) && batch.size() > 1) continue;
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(StarterRouletteOpenPayload.TYPE, StarterRouletteOpenPayload.STREAM_CODEC,
            StarterRouletteNetwork::handleOpen);
        registrar.playToServer(StarterRouletteClaimPayload.TYPE, StarterRouletteClaimPayload.STREAM_CODEC,
            StarterRouletteNetwork::handleClaim);
        registrar.playToClient(StarterRouletteResultPayload.TYPE, StarterRouletteResultPayload.STREAM_CODEC,
            StarterRouletteNetwork::handleResult);
    }

    private static void handleOpen(StarterRouletteOpenPayload payload, IPayloadContext context) {
        LOGGER.info(
            "Starter roulette payload received: player={}, sequenceSize={}, token={}",
            context.player().getGameProfile().getName(), payload.species().size(), payload.token()
        );
        StarterRouletteClient.open(payload.token(), payload.species());
    }

    private static void handleClaim(StarterRouletteClaimPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null || !session.token().equals(payload.token()) || session.expiresAt() < System.currentTimeMillis()
            || payload.sequenceIndex() < 0 || payload.sequenceIndex() >= session.sequence().size()) {
            context.reply(new StarterRouletteResultPayload(false, "screen.cobbleventure_player_menu.starter.invalid_session", ""));
            return;
        }

        GeneralPlayerData data = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
        if (hasReceivedPokemon(player, data) || data.getStarterLocked()) {
            context.reply(new StarterRouletteResultPayload(false, "screen.cobbleventure_player_menu.starter.unavailable", ""));
            return;
        }

        StarterEntry selected = session.sequence().get(payload.sequenceIndex());
        Cobblemon.INSTANCE.getStarterHandler().chooseStarter(player, selected.category(), selected.categoryIndex());
        boolean awarded = hasReceivedPokemon(
            player, Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player)
        );
        if (awarded) {
            setStarterReceivedScore(player, true);
            ensurePokedex(player);
        }
        context.reply(new StarterRouletteResultPayload(
            awarded,
            awarded ? "screen.cobbleventure_player_menu.starter.received" : "screen.cobbleventure_player_menu.starter.failed",
            selected.species()
        ));
    }

    private static void handleResult(StarterRouletteResultPayload payload, IPayloadContext context) {
        StarterRouletteClient.result(payload.success(), payload.translationKey(), payload.species());
    }

    private static boolean hasReceivedPokemon(ServerPlayer player, GeneralPlayerData data) {
        return data.getStarterSelected()
            || Cobblemon.INSTANCE.getStorage().getParty(player).occupied() > 0;
    }

    private static void setStarterReceivedScore(ServerPlayer player, boolean selected) {
        Scoreboard scoreboard = player.getScoreboard();
        Objective objective = scoreboard.getObjective(STARTER_RECEIVED_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(
                STARTER_RECEIVED_OBJECTIVE,
                ObjectiveCriteria.DUMMY,
                Component.literal(STARTER_RECEIVED_OBJECTIVE),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null
            );
        }
        scoreboard.getOrCreatePlayerScore(player, objective).set(selected ? 1 : 0);
    }

    private static void ensurePokedex(ServerPlayer player) {
        Item item = BuiltInRegistries.ITEM.get(POKEDEX_ITEM);
        if (item == Items.AIR) return;
        ItemStack pokedex = new ItemStack(item);
        if (BagApi.count(player, pokedex) > 0) return;
        BagApi.InsertResult result = BagApi.insert(player, pokedex, true);
        if (result.complete()) ItemAcquisition.show(player, pokedex, 1);
    }

    private record StarterEntry(String category, int categoryIndex, String species) {}
    private record Session(UUID token, List<StarterEntry> sequence, long expiresAt) {}

    public record StarterRouletteOpenPayload(UUID token, List<String> species) implements CustomPacketPayload {
        public static final Type<StarterRouletteOpenPayload> TYPE = new Type<>(id("starter_roulette_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StarterRouletteOpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(StarterRouletteOpenPayload::write, StarterRouletteOpenPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(token);
            buffer.writeVarInt(species.size());
            for (String value : species) buffer.writeUtf(value);
        }
        private static StarterRouletteOpenPayload read(RegistryFriendlyByteBuf buffer) {
            UUID token = buffer.readUUID();
            int size = Math.max(0, Math.min(SEQUENCE_LENGTH, buffer.readVarInt()));
            List<String> species = new ArrayList<>(size);
            for (int index = 0; index < size; index++) species.add(buffer.readUtf());
            return new StarterRouletteOpenPayload(token, List.copyOf(species));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StarterRouletteClaimPayload(UUID token, int sequenceIndex) implements CustomPacketPayload {
        public static final Type<StarterRouletteClaimPayload> TYPE = new Type<>(id("starter_roulette_claim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StarterRouletteClaimPayload> STREAM_CODEC =
            StreamCodec.ofMember(StarterRouletteClaimPayload::write, StarterRouletteClaimPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUUID(token); buffer.writeVarInt(sequenceIndex); }
        private static StarterRouletteClaimPayload read(RegistryFriendlyByteBuf buffer) {
            return new StarterRouletteClaimPayload(buffer.readUUID(), buffer.readVarInt());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StarterRouletteResultPayload(boolean success, String translationKey, String species)
        implements CustomPacketPayload {
        public static final Type<StarterRouletteResultPayload> TYPE = new Type<>(id("starter_roulette_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StarterRouletteResultPayload> STREAM_CODEC =
            StreamCodec.ofMember(StarterRouletteResultPayload::write, StarterRouletteResultPayload::read);
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(success); buffer.writeUtf(translationKey); buffer.writeUtf(species);
        }
        private static StarterRouletteResultPayload read(RegistryFriendlyByteBuf buffer) {
            return new StarterRouletteResultPayload(buffer.readBoolean(), buffer.readUtf(), buffer.readUtf());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }
}
