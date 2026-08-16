package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Returns a player with a fully fainted party to their latest generated Pokémon Center. */
public final class PokemonCenterDefeatReturn {
    private static final String CHECKPOINT_DIMENSION = "cobbleventurePokemonCenterDimension";
    private static final String CHECKPOINT_X = "cobbleventurePokemonCenterX";
    private static final String CHECKPOINT_Y = "cobbleventurePokemonCenterY";
    private static final String CHECKPOINT_Z = "cobbleventurePokemonCenterZ";
    private static final String CHECKPOINT_EXIT_X = "cobbleventurePokemonCenterExitX";
    private static final String CHECKPOINT_EXIT_Y = "cobbleventurePokemonCenterExitY";
    private static final String CHECKPOINT_EXIT_Z = "cobbleventurePokemonCenterExitZ";
    private static final String CHECKPOINT_IS_CENTER = "cobbleventurePokemonCenterVisited";
    private static final long RETURN_DELAY_TICKS = 20L;
    private static final long MONEY_MESSAGE_TICKS = 15L;
    private static final long FADE_OUT_TICKS = 25L;
    private static final long NURSE_GREETING_TICKS = 45L;
    private static final long NURSE_COMPLETE_TICKS = 75L;
    private static final long RECOVERY_COMPLETE_TICKS = 105L;
    private static final Map<UUID, PendingReturn> PENDING_RETURNS = new HashMap<>();
    private static final Map<UUID, RecoverySequence> ACTIVE_RECOVERIES = new HashMap<>();
    private static boolean registered;

    private PokemonCenterDefeatReturn() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CobblemonEvents.BATTLE_VICTORY.subscribe(
            (Consumer<BattleVictoryEvent>) PokemonCenterDefeatReturn::onBattleVictory
        );
        CobblemonEvents.BATTLE_FLED.subscribe(
            (Consumer<BattleFledEvent>) PokemonCenterDefeatReturn::onBattleFled
        );
    }

    public static void ensureFallback(
        ServerPlayer player, ServerLevel level, BlockPos position
    ) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(CHECKPOINT_DIMENSION)) {
            saveCheckpoint(data, level, position, position, false);
        }
    }

    public static void recordCenterVisit(
        ServerPlayer player, ServerLevel level, BlockPos interior, BlockPos exit
    ) {
        CompoundTag data = player.getPersistentData();
        boolean changed = !data.getBoolean(CHECKPOINT_IS_CENTER)
            || !data.getString(CHECKPOINT_DIMENSION).equals(level.dimension().location().toString())
            || data.getInt(CHECKPOINT_X) != interior.getX()
            || data.getInt(CHECKPOINT_Y) != interior.getY()
            || data.getInt(CHECKPOINT_Z) != interior.getZ()
            || data.getInt(CHECKPOINT_EXIT_X) != exit.getX()
            || data.getInt(CHECKPOINT_EXIT_Y) != exit.getY()
            || data.getInt(CHECKPOINT_EXIT_Z) != exit.getZ();
        if (!changed) {
            return;
        }
        saveCheckpoint(data, level, interior, exit, true);
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_bootstrap.pokemon_center_checkpoint"
        ));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long gameTime = server.overworld().getGameTime();
        Iterator<Map.Entry<UUID, PendingReturn>> iterator = PENDING_RETURNS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingReturn> entry = iterator.next();
            PendingReturn pending = entry.getValue();
            if (pending.returnAt > gameTime) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
                pending.returnAt = gameTime + 1L;
                continue;
            }
            iterator.remove();
            if (isPartyWiped(player)) {
                startRecovery(player, server, gameTime, pending.settlement);
            } else {
                BattleLossEconomy.announce(player, pending.settlement);
            }
        }
        updateRecoveries(server, gameTime);
        BattleLossEconomy.cleanup(gameTime);
    }

    public static void recoverAfterTickFailure(MinecraftServer server) {
        PENDING_RETURNS.clear();
        ACTIVE_RECOVERIES.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isPartyWiped(player)) {
                Cobblemon.INSTANCE.getStorage().getParty(player).heal();
                player.removeEffect(MobEffects.DARKNESS);
            }
        }
    }

    private static void onBattleVictory(BattleVictoryEvent event) {
        for (var loser : event.getLosers()) {
            if (!(loser instanceof PlayerBattleActor actor)) {
                continue;
            }
            ServerPlayer player = actor.getEntity();
            if (player != null) {
                BattleLossEconomy.Settlement settlement = BattleLossEconomy.settle(event, actor);
                long gameTime = player.getServer().overworld().getGameTime();
                PENDING_RETURNS.put(
                    player.getUUID(),
                    new PendingReturn(gameTime + RETURN_DELAY_TICKS, settlement)
                );
            }
        }
    }

    private static void onBattleFled(BattleFledEvent event) {
        ServerPlayer player = event.getPlayer().getEntity();
        if (player != null) {
            BattleLossEconomy.announce(player, BattleLossEconomy.settle(event));
        }
    }

    static boolean isPartyWiped(ServerPlayer player) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        if (party.occupied() == 0) {
            return false;
        }
        for (Pokemon pokemon : party) {
            if (!pokemon.isFainted()) {
                return false;
            }
        }
        return true;
    }

    private static void startRecovery(
        ServerPlayer player,
        MinecraftServer server,
        long gameTime,
        BattleLossEconomy.Settlement settlement
    ) {
        CompoundTag data = player.getPersistentData();
        ResourceLocation dimensionId = ResourceLocation.tryParse(
            data.getString(CHECKPOINT_DIMENSION)
        );
        if (dimensionId == null) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.pokemon_center_missing"
            ));
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel destination = server.getLevel(dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.pokemon_center_missing"
            ));
            return;
        }

        BlockPos position = new BlockPos(
            data.getInt(CHECKPOINT_X), data.getInt(CHECKPOINT_Y), data.getInt(CHECKPOINT_Z)
        );
        destination.getChunk(position);
        player.addEffect(new MobEffectInstance(
            MobEffects.DARKNESS,
            (int) RECOVERY_COMPLETE_TICKS + 20,
            0,
            true,
            false,
            false
        ));
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_bootstrap.no_usable_pokemon",
            player.getDisplayName()
        ));
        ACTIVE_RECOVERIES.put(player.getUUID(), new RecoverySequence(
            dimension,
            position,
            data.getBoolean(CHECKPOINT_IS_CENTER),
            gameTime,
            settlement
        ));
    }

    private static void updateRecoveries(MinecraftServer server, long gameTime) {
        Iterator<Map.Entry<UUID, RecoverySequence>> iterator =
            ACTIVE_RECOVERIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RecoverySequence> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            RecoverySequence recovery = entry.getValue();
            long elapsed = gameTime - recovery.startedAt;
            if (!recovery.moneyAnnounced && elapsed >= MONEY_MESSAGE_TICKS) {
                recovery.moneyAnnounced = true;
                BattleLossEconomy.announce(player, recovery.settlement);
            }
            if (!recovery.teleported && elapsed >= FADE_OUT_TICKS) {
                ServerLevel destination = server.getLevel(recovery.dimension);
                if (destination == null) {
                    iterator.remove();
                    player.removeEffect(MobEffects.DARKNESS);
                    player.sendSystemMessage(Component.translatable(
                        "message.cobbleventure_bootstrap.pokemon_center_missing"
                    ));
                    continue;
                }
                teleportAndHeal(player, destination, recovery.position);
                recovery.teleported = true;
            }
            if (recovery.center && !recovery.greeted && elapsed >= NURSE_GREETING_TICKS) {
                recovery.greeted = true;
                player.sendSystemMessage(Component.translatable(
                    "message.cobbleventure_bootstrap.pokemon_center_nurse_greeting"
                ));
            }
            if (recovery.center && !recovery.completedDialogue
                && elapsed >= NURSE_COMPLETE_TICKS) {
                recovery.completedDialogue = true;
                player.sendSystemMessage(Component.translatable(
                    "message.cobbleventure_bootstrap.pokemon_center_nurse_complete"
                ));
            }
            if (elapsed < RECOVERY_COMPLETE_TICKS) {
                continue;
            }
            iterator.remove();
            player.removeEffect(MobEffects.DARKNESS);
            player.sendSystemMessage(Component.translatable(
                recovery.center
                    ? "message.cobbleventure_bootstrap.pokemon_center_return"
                    : "message.cobbleventure_bootstrap.pokemon_center_fallback_return"
            ));
        }
    }

    private static void teleportAndHeal(
        ServerPlayer player, ServerLevel destination, BlockPos position
    ) {
        teleport(player, destination, position);
        Cobblemon.INSTANCE.getStorage().getParty(player).heal();
    }

    private static void teleport(
        ServerPlayer player, ServerLevel destination, BlockPos position
    ) {
        player.stopRiding();
        player.teleportTo(
            destination,
            position.getX() + 0.5D,
            position.getY(),
            position.getZ() + 0.5D,
            -90.0F,
            0.0F
        );
        player.resetFallDistance();
    }

    private static void saveCheckpoint(
        CompoundTag data,
        ServerLevel level,
        BlockPos position,
        BlockPos exit,
        boolean center
    ) {
        data.putString(CHECKPOINT_DIMENSION, level.dimension().location().toString());
        data.putInt(CHECKPOINT_X, position.getX());
        data.putInt(CHECKPOINT_Y, position.getY());
        data.putInt(CHECKPOINT_Z, position.getZ());
        data.putInt(CHECKPOINT_EXIT_X, exit.getX());
        data.putInt(CHECKPOINT_EXIT_Y, exit.getY());
        data.putInt(CHECKPOINT_EXIT_Z, exit.getZ());
        data.putBoolean(CHECKPOINT_IS_CENTER, center);
    }

    private static final class RecoverySequence {
        private final ResourceKey<Level> dimension;
        private final BlockPos position;
        private final boolean center;
        private final long startedAt;
        private final BattleLossEconomy.Settlement settlement;
        private boolean teleported;
        private boolean moneyAnnounced;
        private boolean greeted;
        private boolean completedDialogue;

        private RecoverySequence(
            ResourceKey<Level> dimension, BlockPos position,
            boolean center,
            long startedAt,
            BattleLossEconomy.Settlement settlement
        ) {
            this.dimension = dimension;
            this.position = position;
            this.center = center;
            this.startedAt = startedAt;
            this.settlement = settlement;
        }
    }

    private static final class PendingReturn {
        private long returnAt;
        private final BattleLossEconomy.Settlement settlement;

        private PendingReturn(long returnAt, BattleLossEconomy.Settlement settlement) {
            this.returnAt = returnAt;
            this.settlement = settlement;
        }
    }
}
