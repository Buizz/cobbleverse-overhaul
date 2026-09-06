package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.buizz.cobbleventure.adventure.event.EventDialogueLifecycle;
import dev.buizz.cobbleventure.adventure.event.EventBattleBridge;
import dev.buizz.cobbleventure.adventure.event.EventNpcInteractionHandler;
import dev.buizz.cobbleventure.adventure.event.EventSessionKey;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
    private static final long RECOVERY_COMPLETE_TICKS = 105L;
    private static final long NURSE_DIALOGUE_TIMEOUT_TICKS = 20L * 60L;
    private static final String NURSE_BINDING_TAG =
        "cves_binding/cobbleventure/facilities/pokemon_center_nurse";
    private static final String NURSE_SCRIPT_ID =
        "cobbleventure:event_script/facilities/pokemon_center_nurse";
    private static final Map<UUID, PendingReturn> PENDING_RETURNS = new HashMap<>();
    private static final Map<UUID, RecoverySequence> ACTIVE_RECOVERIES = new HashMap<>();
    private static final Map<UUID, UUID> FORFEITED_BATTLES = new HashMap<>();
    private static Predicate<ServerPlayer> defeatRecoveryOverride = player -> false;
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
        EventDialogueLifecycle.register(PokemonCenterDefeatReturn::onDialogueStateChanged);
        NeoForge.EVENT_BUS.addListener(PokemonCenterDefeatReturn::onServerTick);
        NeoForge.EVENT_BUS.addListener(PokemonCenterDefeatReturn::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PokemonCenterDefeatReturn::onPlayerLoggedOut);
    }

    /** Records a validated server-side forfeit before Cobblemon resolves it as a loss. */
    public static void recordForfeit(PlayerBattleActor actor) {
        if (!shouldRecordForfeit(actor.getBattle().isPvW())) {
            return;
        }
        ServerPlayer player = actor.getEntity();
        if (player != null) {
            FORFEITED_BATTLES.put(player.getUUID(), actor.getBattle().getBattleId());
        }
    }

    /**
     * Installs an optional world-specific handler that can replace Pokémon Center recovery.
     * Returning {@code true} means the handler safely returned and recovered the player.
     */
    public static void setDefeatRecoveryOverride(Predicate<ServerPlayer> override) {
        defeatRecoveryOverride = override == null ? player -> false : override;
    }

    /**
     * Prevents a nearby NPC from opening a fresh event after defeat while the
     * player is still standing at the battle site. The battle result dialogue
     * is already running when the pending return is registered and may finish
     * normally; only subsequent trigger attempts are rejected.
     */
    public static boolean blocksNewNpcEvents(ServerPlayer player) {
        PendingReturn pending = PENDING_RETURNS.get(player.getUUID());
        RecoverySequence recovery = ACTIVE_RECOVERIES.get(player.getUUID());
        return blocksNewNpcEvents(
            pending != null,
            recovery != null,
            recovery != null && recovery.teleported
        );
    }

    static boolean blocksNewNpcEvents(
        boolean pendingReturn, boolean activeRecovery, boolean teleported
    ) {
        return pendingReturn || (activeRecovery && !teleported);
    }

    static boolean shouldRecordForfeit(boolean wildBattle) {
        return !wildBattle;
    }

    public static void ensureFallback(
        ServerPlayer player, ServerLevel level, BlockPos position
    ) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(CHECKPOINT_DIMENSION)) {
            saveCheckpoint(data, level, position, position, false);
        }
    }

    /** Uses the authored starting point until the player visits a Pokémon Center. */
    public static void recordStarterFallback(
        ServerPlayer player, ServerLevel level, BlockPos position
    ) {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(CHECKPOINT_IS_CENTER)) {
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

    static BlockPos sameDimensionCheckpointExit(ServerPlayer player, ServerLevel level) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(CHECKPOINT_DIMENSION)
            || !data.getString(CHECKPOINT_DIMENSION)
                .equals(level.dimension().location().toString())) {
            return null;
        }
        return new BlockPos(
            data.getInt(CHECKPOINT_EXIT_X),
            data.getInt(CHECKPOINT_EXIT_Y),
            data.getInt(CHECKPOINT_EXIT_Z)
        );
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
            if (shouldDeferRecovery(
                BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) != null,
                EventBattleBridge.hasPendingTrainerBattle(player.getUUID())
            )) {
                pending.returnAt = gameTime + 1L;
                continue;
            }
            iterator.remove();
            if (pending.forceRecovery || isPartyWiped(player)) {
                if (defeatRecoveryOverride.test(player)) {
                    BattleLossEconomy.announce(player, pending.settlement);
                    continue;
                }
                startRecovery(
                    player, server, gameTime, pending.settlement, pending.forceRecovery
                );
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
        FORFEITED_BATTLES.clear();
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
                boolean forfeited = consumeForfeit(
                    player.getUUID(), event.getBattle().getBattleId()
                );
                BattleLossEconomy.Settlement settlement = BattleLossEconomy.settle(
                    event, actor, forfeited
                );
                long gameTime = player.getServer().overworld().getGameTime();
                PENDING_RETURNS.put(
                    player.getUUID(),
                    new PendingReturn(gameTime + RETURN_DELAY_TICKS, settlement, forfeited)
                );
            }
        }
    }

    private static void onBattleFled(BattleFledEvent event) {
        ServerPlayer player = event.getPlayer().getEntity();
        if (player == null) {
            return;
        }

        BattleLossEconomy.Settlement settlement = BattleLossEconomy.settle(event);
        consumeForfeit(player.getUUID(), event.getBattle().getBattleId());
        if (!event.getBattle().isPvW()) {
            long gameTime = player.getServer().overworld().getGameTime();
            PENDING_RETURNS.put(
                player.getUUID(),
                new PendingReturn(gameTime + RETURN_DELAY_TICKS, settlement, true)
            );
        } else {
            BattleLossEconomy.announce(player, settlement);
        }
    }

    private static boolean consumeForfeit(UUID playerId, UUID battleId) {
        return FORFEITED_BATTLES.remove(playerId, battleId);
    }

    public static boolean isPartyWiped(ServerPlayer player) {
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

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !isPartyWiped(player)
            || PENDING_RETURNS.containsKey(player.getUUID())
            || ACTIVE_RECOVERIES.containsKey(player.getUUID())) {
            return;
        }
        long gameTime = player.getServer().overworld().getGameTime();
        PENDING_RETURNS.put(
            player.getUUID(),
            new PendingReturn(gameTime + RETURN_DELAY_TICKS, null, true)
        );
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FORFEITED_BATTLES.remove(player.getUUID());
        }
    }

    private static void startRecovery(
        ServerPlayer player,
        MinecraftServer server,
        long gameTime,
        BattleLossEconomy.Settlement settlement,
        boolean forfeited
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
        boolean center = data.getBoolean(CHECKPOINT_IS_CENTER);
        BlockPos safePosition = resolveRecoveryPosition(destination, position, center);
        if (safePosition == null) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.pokemon_center_missing"
            ));
            return;
        }
        position = safePosition;
        player.addEffect(new MobEffectInstance(
            MobEffects.DARKNESS,
            (int) (center ? NURSE_DIALOGUE_TIMEOUT_TICKS : RECOVERY_COMPLETE_TICKS) + 20,
            0,
            true,
            false,
            false
        ));
        if (!forfeited) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.no_usable_pokemon",
                player.getDisplayName()
            ));
        }
        ACTIVE_RECOVERIES.put(player.getUUID(), new RecoverySequence(
            dimension,
            position,
            center,
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
            if (shouldDeferRecovery(
                BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) != null,
                EventBattleBridge.hasPendingTrainerBattle(player.getUUID())
            )) {
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
                teleport(player, destination, recovery.position);
                if (!recovery.center) {
                    Cobblemon.INSTANCE.getStorage().getParty(player).heal();
                }
                recovery.teleported = true;
            }
            if (recovery.center && !recovery.dialogueStarted
                && !recovery.dialogueFallback && elapsed >= NURSE_GREETING_TICKS) {
                ServerLevel destination = server.getLevel(recovery.dimension);
                Entity nurse = destination == null
                    ? null : findNurse(destination, recovery.position);
                if (nurse != null) {
                    recovery.nurseNpcId = nurse.getUUID();
                    recovery.dialogueStarted = true;
                    if (!EventNpcInteractionHandler.startBoundInteraction(player, nurse)) {
                        recovery.dialogueStarted = false;
                        recovery.nurseNpcId = null;
                        startFallbackNurseRecovery(player, recovery);
                    }
                } else {
                    startFallbackNurseRecovery(player, recovery);
                }
            }
            if (recovery.center && recovery.dialogueStarted) {
                if (!recovery.dialogueCompleted
                    && elapsed < NURSE_DIALOGUE_TIMEOUT_TICKS) {
                    continue;
                }
                if (!recovery.dialogueCompleted && isPartyWiped(player)) {
                    Cobblemon.INSTANCE.getStorage().getParty(player).heal();
                }
                iterator.remove();
                finishRecovery(player, recovery.center);
                continue;
            }
            if (elapsed < RECOVERY_COMPLETE_TICKS) {
                continue;
            }
            iterator.remove();
            finishRecovery(player, recovery.center);
        }
    }

    private static Entity findNurse(ServerLevel level, BlockPos position) {
        AABB search = new AABB(position).inflate(12.0D, 6.0D, 12.0D);
        return level.getEntities((Entity) null, search, entity ->
                entity.getTags().contains(NURSE_BINDING_TAG))
            .stream()
            .min((left, right) -> Double.compare(
                left.distanceToSqr(position.getCenter()),
                right.distanceToSqr(position.getCenter())
            ))
            .orElse(null);
    }

    private static BlockPos resolveRecoveryPosition(
        ServerLevel level, BlockPos saved, boolean center
    ) {
        if (isSafeStandingRoom(level, saved)) return saved;
        if (center) {
            AABB column = new AABB(
                saved.getX() - 32.0D, level.getMinBuildHeight(), saved.getZ() - 32.0D,
                saved.getX() + 33.0D, level.getMaxBuildHeight(), saved.getZ() + 33.0D
            );
            Entity nurse = level.getEntities((Entity) null, column, entity ->
                    entity.getTags().contains(NURSE_BINDING_TAG))
                .stream()
                .min((left, right) -> Double.compare(
                    horizontalDistance(left.blockPosition(), saved),
                    horizontalDistance(right.blockPosition(), saved)
                ))
                .orElse(null);
            if (nurse != null) {
                BlockPos nearNurse = findNearbySafeRoom(level, nurse.blockPosition(), 5, 6);
                if (nearNurse != null) return nearNurse;
            }
        }
        BlockPos nearby = findNearbySafeRoom(level, saved, 5, 16);
        if (nearby != null) return nearby;
        return findNearbySafeRoom(level, level.getSharedSpawnPos(), 8, 16);
    }

    private static double horizontalDistance(BlockPos left, BlockPos right) {
        double x = left.getX() - right.getX();
        double z = left.getZ() - right.getZ();
        return x * x + z * z;
    }

    private static BlockPos findNearbySafeRoom(
        ServerLevel level, BlockPos origin, int radius, int verticalRange
    ) {
        BlockPos sameFloor = findNearbySafeRoomAtY(level, origin, radius, 0);
        if (sameFloor != null) return sameFloor;
        for (int y = 1; y <= verticalRange; y++) {
            BlockPos below = findNearbySafeRoomAtY(level, origin, radius, -y);
            if (below != null) return below;
            BlockPos above = findNearbySafeRoomAtY(level, origin, radius, y);
            if (above != null) return above;
        }
        return null;
    }

    private static BlockPos findNearbySafeRoomAtY(
        ServerLevel level, BlockPos origin, int radius, int verticalOffset
    ) {
        BlockPos layer = origin.offset(0, verticalOffset, 0);
        for (int horizontal = 0; horizontal <= radius; horizontal++) {
            for (int x = -horizontal; x <= horizontal; x++) {
                for (int z = -horizontal; z <= horizontal; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != horizontal) continue;
                    BlockPos column = layer.offset(x, 0, z);
                    if (isSafeStandingRoom(level, column)) return column;
                }
            }
        }
        return null;
    }

    private static boolean isSafeStandingRoom(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight()
            || feet.getY() + 1 >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockPos floor = feet.below();
        var floorState = level.getBlockState(floor);
        if (floorState.isAir() || floorState.is(Blocks.BARRIER)
            || floorState.getCollisionShape(level, floor).isEmpty()) {
            return false;
        }
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above())
                .getCollisionShape(level, feet.above()).isEmpty();
    }

    private static void startFallbackNurseRecovery(
        ServerPlayer player, RecoverySequence recovery
    ) {
        recovery.dialogueFallback = true;
        Cobblemon.INSTANCE.getStorage().getParty(player).heal();
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_bootstrap.pokemon_center_nurse_greeting"
        ));
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_bootstrap.pokemon_center_nurse_complete"
        ));
    }

    private static void finishRecovery(ServerPlayer player, boolean center) {
        player.removeEffect(MobEffects.DARKNESS);
        player.sendSystemMessage(Component.translatable(
            center
                ? "message.cobbleventure_bootstrap.pokemon_center_return"
                : "message.cobbleventure_bootstrap.pokemon_center_fallback_return"
        ));
    }

    private static void onDialogueStateChanged(
        ServerPlayer player, EventSessionKey key, boolean open
    ) {
        if (open || !NURSE_SCRIPT_ID.equals(key.scriptId())) {
            return;
        }
        RecoverySequence recovery = ACTIVE_RECOVERIES.get(player.getUUID());
        if (recovery == null || !recovery.dialogueStarted
            || !key.npcId().equals(recovery.nurseNpcId)) {
            return;
        }
        recovery.closedDialogueLines++;
        if (recovery.closedDialogueLines >= 2) {
            recovery.dialogueCompleted = true;
        }
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

    static boolean shouldDeferRecovery(
        boolean registeredBattle, boolean pendingTrainerBattle
    ) {
        return registeredBattle || pendingTrainerBattle;
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
        private UUID nurseNpcId;
        private boolean dialogueStarted;
        private boolean dialogueFallback;
        private boolean dialogueCompleted;
        private int closedDialogueLines;

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
        private final boolean forceRecovery;

        private PendingReturn(
            long returnAt,
            BattleLossEconomy.Settlement settlement,
            boolean forceRecovery
        ) {
            this.returnAt = returnAt;
            this.settlement = settlement;
            this.forceRecovery = forceRecovery;
        }
    }
}
