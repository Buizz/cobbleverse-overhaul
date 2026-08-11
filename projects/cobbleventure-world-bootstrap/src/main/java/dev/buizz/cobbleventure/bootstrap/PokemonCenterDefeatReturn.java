package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
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
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Returns a player with a fully fainted party to their latest generated Pokémon Center. */
final class PokemonCenterDefeatReturn {
    private static final String CHECKPOINT_DIMENSION = "cobbleventurePokemonCenterDimension";
    private static final String CHECKPOINT_X = "cobbleventurePokemonCenterX";
    private static final String CHECKPOINT_Y = "cobbleventurePokemonCenterY";
    private static final String CHECKPOINT_Z = "cobbleventurePokemonCenterZ";
    private static final String CHECKPOINT_IS_CENTER = "cobbleventurePokemonCenterVisited";
    private static final long RETURN_DELAY_TICKS = 20L;
    private static final Map<UUID, Long> PENDING_RETURNS = new HashMap<>();
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
    }

    static void ensureFallback(ServerPlayer player, ServerLevel level, BlockPos position) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(CHECKPOINT_DIMENSION)) {
            saveCheckpoint(data, level, position, false);
        }
    }

    static void recordCenterVisit(ServerPlayer player, ServerLevel level, BlockPos entrance) {
        CompoundTag data = player.getPersistentData();
        boolean changed = !data.getBoolean(CHECKPOINT_IS_CENTER)
            || !data.getString(CHECKPOINT_DIMENSION).equals(level.dimension().location().toString())
            || data.getInt(CHECKPOINT_X) != entrance.getX()
            || data.getInt(CHECKPOINT_Y) != entrance.getY()
            || data.getInt(CHECKPOINT_Z) != entrance.getZ();
        if (!changed) {
            return;
        }
        saveCheckpoint(data, level, entrance, true);
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_bootstrap.pokemon_center_checkpoint"
        ));
    }

    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long gameTime = server.overworld().getGameTime();
        Iterator<Map.Entry<UUID, Long>> iterator = PENDING_RETURNS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() > gameTime) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
                entry.setValue(gameTime + 1L);
                continue;
            }
            iterator.remove();
            if (isPartyWiped(player)) {
                returnToCheckpoint(player, server);
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
                long gameTime = player.getServer().overworld().getGameTime();
                PENDING_RETURNS.put(player.getUUID(), gameTime + RETURN_DELAY_TICKS);
            }
        }
    }

    private static boolean isPartyWiped(ServerPlayer player) {
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

    private static void returnToCheckpoint(ServerPlayer player, MinecraftServer server) {
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
        player.stopRiding();
        player.teleportTo(
            destination,
            position.getX() + 0.5D,
            position.getY(),
            position.getZ() + 0.5D,
            player.getYRot(),
            player.getXRot()
        );
        Cobblemon.INSTANCE.getStorage().getParty(player).heal();
        player.sendSystemMessage(Component.translatable(
            data.getBoolean(CHECKPOINT_IS_CENTER)
                ? "message.cobbleventure_bootstrap.pokemon_center_return"
                : "message.cobbleventure_bootstrap.pokemon_center_fallback_return"
        ));
    }

    private static void saveCheckpoint(
        CompoundTag data,
        ServerLevel level,
        BlockPos position,
        boolean center
    ) {
        data.putString(CHECKPOINT_DIMENSION, level.dimension().location().toString());
        data.putInt(CHECKPOINT_X, position.getX());
        data.putInt(CHECKPOINT_Y, position.getY());
        data.putInt(CHECKPOINT_Z, position.getZ());
        data.putBoolean(CHECKPOINT_IS_CENTER, center);
    }
}
