package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Pays configured trainer prizes through CobbleDollars, including held-item bonuses. */
final class TrainerMoneyRewards {
    private static final long ACTIVE_RETENTION_TICKS = 20L * 10L;
    private static final long COMPLETED_RETENTION_TICKS = 20L * 5L;
    private static final Map<UUID, Participation> ACTIVE_PARTICIPATION = new HashMap<>();
    private static final Map<UUID, CompletedParticipation> COMPLETED_PARTICIPATION = new HashMap<>();
    private static boolean registered;

    private TrainerMoneyRewards() {}

    static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(TrainerMoneyRewards::registerCommands);
        NeoForge.EVENT_BUS.addListener(TrainerMoneyRewards::onServerTick);
        CobblemonEvents.BATTLE_VICTORY.subscribe(
            (Consumer<BattleVictoryEvent>) TrainerMoneyRewards::onBattleVictory
        );
        CobblemonEvents.BATTLE_FLED.subscribe(
            (Consumer<BattleFledEvent>) TrainerMoneyRewards::onBattleFled
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        ACTIVE_PARTICIPATION.entrySet().removeIf(
            entry -> gameTime - entry.getValue().lastSeen > ACTIVE_RETENTION_TICKS
        );
        COMPLETED_PARTICIPATION.entrySet().removeIf(
            entry -> entry.getValue().expiresAt < gameTime
        );
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PokemonBattle battle = BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player);
            if (battle == null) continue;
            PlayerBattleActor actor = playerActor(battle, player);
            if (actor != null) recordActiveItems(player.getUUID(), battle.getBattleId(), actor, gameTime);
        }
    }

    private static PlayerBattleActor playerActor(PokemonBattle battle, ServerPlayer player) {
        for (BattleActor actor : battle.getActors()) {
            if (actor instanceof PlayerBattleActor playerActor && playerActor.isForPlayer(player)) {
                return playerActor;
            }
        }
        return null;
    }

    private static void recordActiveItems(
        UUID playerId, UUID battleId, PlayerBattleActor actor, long gameTime
    ) {
        Participation participation = ACTIVE_PARTICIPATION.get(playerId);
        if (participation == null || !participation.battleId.equals(battleId)) {
            participation = new Participation(battleId, gameTime);
            ACTIVE_PARTICIPATION.put(playerId, participation);
        }
        participation.lastSeen = gameTime;
        for (ActiveBattlePokemon active : actor.getActivePokemon()) {
            BattlePokemon battlePokemon = active.getBattlePokemon();
            if (battlePokemon == null) continue;
            ItemStack held = battlePokemon.getEffectedPokemon().heldItem();
            if (!held.isEmpty()) {
                participation.activeHeldItems.add(BuiltInRegistries.ITEM.getKey(held.getItem()));
            }
        }
    }

    private static void onBattleVictory(BattleVictoryEvent event) {
        long gameTime = 0L;
        for (BattleActor actor : event.getBattle().getActors()) {
            if (actor instanceof PlayerBattleActor playerActor
                && playerActor.getEntity() != null) {
                gameTime = playerActor.getEntity().serverLevel().getGameTime();
                break;
            }
        }
        for (BattleActor actor : event.getWinners()) {
            if (actor instanceof PlayerBattleActor playerActor) {
                seal(playerActor.getUuid(), event.getBattle().getBattleId(), gameTime);
            }
        }
        for (BattleActor actor : event.getLosers()) {
            if (actor instanceof PlayerBattleActor playerActor) {
                ACTIVE_PARTICIPATION.remove(playerActor.getUuid());
                COMPLETED_PARTICIPATION.remove(playerActor.getUuid());
            }
        }
    }

    private static void onBattleFled(BattleFledEvent event) {
        UUID playerId = event.getPlayer().getUuid();
        ACTIVE_PARTICIPATION.remove(playerId);
        COMPLETED_PARTICIPATION.remove(playerId);
    }

    private static void seal(UUID playerId, UUID battleId, long gameTime) {
        Participation participation = ACTIVE_PARTICIPATION.get(playerId);
        Set<ResourceLocation> heldItems = participation != null
            && participation.battleId.equals(battleId)
                ? Set.copyOf(participation.activeHeldItems)
                : Set.of();
        ACTIVE_PARTICIPATION.remove(playerId);
        COMPLETED_PARTICIPATION.put(
            playerId,
            new CompletedParticipation(battleId, heldItems, gameTime + COMPLETED_RETENTION_TICKS)
        );
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        var fixed = Commands.literal("fixed")
            .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                .then(bonusArguments(false)));
        var regional = Commands.literal("regional")
            .then(Commands.argument("fallback_level", IntegerArgumentType.integer(1, 100))
                .then(Commands.argument("per_level", IntegerArgumentType.integer(0))
                    .then(Commands.argument("offset", IntegerArgumentType.integer())
                        .then(bonusArguments(true)))));
        event.getDispatcher().register(
            Commands.literal("cobbleventure_reward")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("money")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(fixed)
                        .then(regional)))
        );
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, Boolean>
    bonusArguments(boolean regional) {
        return Commands.argument("held_bonus", BoolArgumentType.bool())
            .then(Commands.argument("held_item", StringArgumentType.word())
                .then(Commands.argument("held_multiplier", IntegerArgumentType.integer(1))
                    .executes(context -> pay(
                        EntityArgument.getPlayer(context, "player"),
                        regional
                            ? regionalAmount(
                                EntityArgument.getPlayer(context, "player"),
                                IntegerArgumentType.getInteger(context, "fallback_level"),
                                IntegerArgumentType.getInteger(context, "per_level"),
                                IntegerArgumentType.getInteger(context, "offset")
                            )
                            : IntegerArgumentType.getInteger(context, "amount"),
                        BoolArgumentType.getBool(context, "held_bonus"),
                        StringArgumentType.getString(context, "held_item"),
                        IntegerArgumentType.getInteger(context, "held_multiplier")
                    ))));
    }

    private static int regionalAmount(ServerPlayer player, int fallback, int perLevel, int offset) {
        Integer regional = CobbleventureBootstrap.averageWildSpawnLevel(
            player.serverLevel(), player.getX(), player.getZ()
        );
        int level = regional == null ? fallback : Math.max(1, Math.min(100, regional));
        return Math.max(0, level * perLevel + offset);
    }

    private static int pay(ServerPlayer player, int baseAmount, boolean heldBonus,
                           String heldItemId, int heldMultiplier) {
        long calculated = Math.max(0L, baseAmount);
        if (heldBonus && participatedWithHeldItem(player, heldItemId)) calculated *= heldMultiplier;
        int amount = (int)Math.min(Integer.MAX_VALUE, calculated);
        if (amount == 0) return 0;
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO);
        PlayerExtensionKt.setCobbleDollars(player, balance.add(BigInteger.valueOf(amount)));
        player.sendSystemMessage(Component.literal("트레이너 상금 " + amount + " 코블달러를 받았습니다."));
        return amount;
    }

    private static boolean participatedWithHeldItem(ServerPlayer player, String itemId) {
        ResourceLocation expected = ResourceLocation.tryParse(itemId);
        if (expected == null) return false;
        Participation active = ACTIVE_PARTICIPATION.get(player.getUUID());
        if (active != null) return active.activeHeldItems.contains(expected);
        CompletedParticipation completed = COMPLETED_PARTICIPATION.get(player.getUUID());
        return completed != null && completed.activeHeldItems.contains(expected);
    }

    private static final class Participation {
        private final UUID battleId;
        private final Set<ResourceLocation> activeHeldItems = new HashSet<>();
        private long lastSeen;

        private Participation(UUID battleId, long lastSeen) {
            this.battleId = battleId;
            this.lastSeen = lastSeen;
        }
    }

    private record CompletedParticipation(
        UUID battleId, Set<ResourceLocation> activeHeldItems, long expiresAt
    ) {}
}
