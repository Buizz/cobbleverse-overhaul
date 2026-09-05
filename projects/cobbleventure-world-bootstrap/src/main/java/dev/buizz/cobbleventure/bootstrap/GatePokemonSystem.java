package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.event.EventBattleBridge;
import dev.buizz.cobbleventure.adventure.event.EventNpcInteractionHandler;
import dev.buizz.cobbleventure.adventure.event.ServerPlayerEventState;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Fixed story actors. Wild battle Pokemon are separate, player-owned attempts. */
public final class GatePokemonSystem {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    public static final String ACTOR_TAG = "cobbleventure_gate_pokemon";
    public static final String CHALLENGER_KEY = "cobbleventureGateChallenger";
    private static final Map<String, Actor> ACTORS = new HashMap<>();
    private static final Map<UUID, Challenge> CHALLENGES = new HashMap<>();
    private static final Map<UUID, List<GatePokemonNetwork.View>> VIEWS = new HashMap<>();
    private static final Map<String, Long> RETRY_AT = new HashMap<>();

    private GatePokemonSystem() {}

    static void register(IEventBus modBus) {
        GatePokemonNetwork.register(modBus);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, GatePokemonSystem::interact);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            ACTORS.clear(); CHALLENGES.clear(); VIEWS.clear(); RETRY_AT.clear();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) finish(player, false);
            VIEWS.remove(event.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                finish(player, false);
                VIEWS.remove(player.getUUID());
                GatePokemonNetwork.sync(player, List.of());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) finish(player, false);
            VIEWS.remove(event.getEntity().getUUID());
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe((Consumer<BattleVictoryEvent>) GatePokemonSystem::victory);
        CobblemonEvents.BATTLE_FLED.subscribe((Consumer<BattleFledEvent>) event -> {
            ServerPlayer player = event.getPlayer().getEntity();
            if (player != null && matchesBattle(player, event.getBattle().getBattleId())) finish(player, false);
        });
        CobblemonEvents.POKEMON_CAPTURED.subscribe((Consumer<PokemonCapturedEvent>) event -> {
            Challenge challenge = CHALLENGES.get(event.getPlayer().getUUID());
            if (challenge != null && challenge.pokemonId != null
                    && challenge.pokemonId.equals(event.getPokemon().getUuid())) finish(event.getPlayer(), true);
        });
    }

    static void tick(ServerPlayer player, ServerLevel level, WorldPlanModels.HexWorldPlan world, long time) {
        tickChallenge(player, time);
        List<GatePokemonNetwork.View> views = new ArrayList<>();
        if (player.serverLevel() == level) {
            for (WorldGateSystem.Gate gate : world.gates()) {
                if (gate.pokemon() == null) continue;
                var center = WorldGateSystem.alignedGateCenter(world, gate);
                if (player.distanceToSqr(center.x(), player.getY(), center.z()) > 128 * 128) continue;
                String key = level.dimension().location() + "/" + gate.id();
                Actor actor = ACTORS.get(key);
                if (actor == null || actor.entity.isRemoved()) {
                    if (time < RETRY_AT.getOrDefault(key, 0L)) continue;
                    BlockPos ground = new BlockPos(center.x(),
                        CobbleventureBootstrap.nativeTerrainColumn(world, center.x(), center.z()).groundY() + 1, center.z());
                    if (!level.hasChunkAt(ground)) continue;
                    try {
                        actor = spawn(level, gate, ground);
                    } catch (RuntimeException error) {
                        LOGGER.warn("Could not spawn gate Pokemon: {}", gate.id(), error);
                    }
                    if (actor == null || actor.entity.isRemoved()) {
                        RETRY_AT.put(key, time + 200);
                        continue;
                    }
                    RETRY_AT.remove(key);
                    ACTORS.put(key, actor);
                }
                Challenge challenge = CHALLENGES.get(player.getUUID());
                boolean active = challenge != null && challenge.actor == actor;
                boolean challenged = isActorChallenged(actor);
                String pose = gate.pokemon().poseWhileChallenged(challenged);
                synchronizeActorPose(actor, pose);
                boolean hidden = gate.allows(player) || (active && challenge.entity != null);
                views.add(new GatePokemonNetwork.View(actor.entity.getUUID(), actor.bounds,
                    hidden, pose));
            }
        }
        List<GatePokemonNetwork.View> snapshot = List.copyOf(views);
        if (!snapshot.equals(VIEWS.put(player.getUUID(), snapshot))) {
            GatePokemonNetwork.sync(player, snapshot);
        }
    }

    private static Actor spawn(ServerLevel level, WorldGateSystem.Gate gate, BlockPos feet) {
        GatePokemonConfig config = gate.pokemon();
        PokemonEntity entity = createPokemon(level, config);
        entity.addTag(ACTOR_TAG);
        if (config.eventBinding() != null) entity.addTag("cves_binding/" + config.eventBinding().replace(':', '/'));
        entity.setNoAi(true);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setPersistenceRequired();
        entity.setCountsTowardsSpawnCap(false);
        entity.setEnablePoseTypeRecalculation(false);
        entity.getEntityData().set(PokemonEntity.getUNBATTLEABLE(), true);
        entity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), config.pose().equals("sleep") ? PoseType.SLEEP : PoseType.STAND);
        float yaw = switch (gate.facing()) { case "north" -> 180; case "east" -> -90; case "west" -> 90; default -> 0; };
        entity.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, yaw, 0);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
        if (!level.addFreshEntity(entity)) return null;
        AABB bounds = config.bounds(entity.position(), gate.facing());
        entity.setBoundingBox(bounds);
        return new Actor(gate, entity, bounds);
    }

    private static PokemonEntity createPokemon(ServerLevel level, GatePokemonConfig config) {
        if (PokemonSpecies.getByIdentifier(ResourceLocation.parse(config.species())) == null)
            throw new IllegalArgumentException("Unknown gate Pokemon species: " + config.species());
        var pokemon = PokemonProperties.Companion.parse(config.species() + " level=" + config.level()).createEntity(level);
        pokemon.getPokemon().setScaleModifier(config.scale());
        pokemon.setCountsTowardsSpawnCap(false);
        return pokemon;
    }

    private static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!event.getTarget().getTags().contains(ACTOR_TAG)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) return;
        Actor actor = ACTORS.values().stream().filter(entry -> entry.entity == event.getTarget()).findFirst().orElse(null);
        if (actor != null) activate(player, actor, player.getItemInHand(event.getHand()));
    }

    /** Called by an actual item use, including the bag's server-side use action. */
    static boolean useActivationItem(ServerPlayer player, ItemStack usedItem) {
        String itemId = BuiltInRegistries.ITEM.getKey(usedItem.getItem()).toString();
        Actor actor = ACTORS.values().stream()
            .filter(entry -> !entry.entity.isRemoved() && entry.entity.level() == player.level()
                && itemId.equals(entry.gate.pokemon().activationItem())
                && !entry.gate.allows(player) && player.distanceToSqr(entry.entity) <= 8 * 8
                && player.hasLineOfSight(entry.entity))
            .min(java.util.Comparator.comparingDouble(entry -> player.distanceToSqr(entry.entity)))
            .orElse(null);
        if (actor == null) {
            player.displayClientMessage(Component.translatable("message.cobbleventure_bootstrap.poke_flute.no_target"), true);
            return false;
        }
        return activate(player, actor, usedItem);
    }

    private static boolean activate(ServerPlayer player, Actor actor, ItemStack usedItem) {
        if (actor == null || actor.gate.allows(player) || CHALLENGES.containsKey(player.getUUID())
                || player.isSpectator() || player.distanceToSqr(actor.entity) > 8 * 8
                || actor.entity.level() != player.level() || !player.hasLineOfSight(actor.entity)
                || EventBattleBridge.hasPendingTrainerBattle(player.getUUID())
                || BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) != null) return false;
        GatePokemonConfig config = actor.gate.pokemon();
        if (new ServerPlayerEventState(player).flag(config.completionFlag())) {
            player.displayClientMessage(Component.literal(actor.gate.denyMessage()), true);
            return false;
        }
        if (!config.acceptsActivationItem(BuiltInRegistries.ITEM.getKey(usedItem.getItem()).toString())) {
            player.displayClientMessage(Component.literal(actor.gate.denyMessage()), true);
            return false;
        }
        if (!PlayerConditions.matches(player, "all", config.activationConditions())) {
            player.displayClientMessage(Component.literal(actor.gate.denyMessage()), true);
            return false;
        }
        if (config.eventBinding() != null) {
            EventNpcInteractionHandler.startBoundInteraction(player, actor.entity);
            return true;
        }
        CHALLENGES.put(player.getUUID(), new Challenge(actor, player.serverLevel().getGameTime() + 20));
        // Keep the authoritative entity data in step with the per-player view.
        // Leaving the server actor asleep made its synced SLEEP pose repeatedly
        // overwrite the client's temporary STAND pose during the wake-up delay.
        synchronizeActorPose(actor, config.poseWhileChallenged(true));
        if (config.activationItem() != null) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 1, 1);
            player.displayClientMessage(Component.translatable("message.cobbleventure_bootstrap.poke_flute.awakened"), true);
        }
        return true;
    }

    private static void tickChallenge(ServerPlayer player, long time) {
        Challenge challenge = CHALLENGES.get(player.getUUID());
        if (challenge == null) return;
        if (!player.isAlive() || player.serverLevel() != challenge.actor.entity.level()) {
            finish(player, false); return;
        }
        if (challenge.entity == null
            && (EventBattleBridge.hasPendingTrainerBattle(player.getUUID())
                || BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) != null)) {
            finish(player, false);
            return;
        }
        if (challenge.entity == null && time >= challenge.startAt) {
            try {
                var entity = createPokemon(player.serverLevel(), challenge.actor.gate.pokemon());
                entity.getPersistentData().putUUID(CHALLENGER_KEY, player.getUUID());
                entity.moveTo(challenge.actor.entity.position(), challenge.actor.entity.getYRot(), 0);
                challenge.entity = entity;
                challenge.pokemonId = entity.getPokemon().getUuid();
                if (!player.serverLevel().addFreshEntity(entity)) {
                    finish(player, false);
                    player.displayClientMessage(Component.literal("전투를 시작할 수 없습니다. 싸울 수 있는 포켓몬을 준비해 주세요."), true);
                    return;
                }
                // Match the proven pursuit encounter lifecycle: let one full
                // server tick publish the entity before registering its battle.
                challenge.battleStartTick = time + 1L;
            } catch (RuntimeException error) {
                LOGGER.warn("Could not start gate Pokemon battle: {}", challenge.actor.gate.id(), error);
                finish(player, false);
                player.displayClientMessage(Component.literal("관문 전투를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요."), true);
            }
        } else if (challenge.entity != null) {
            if (challenge.battleId == null && time >= challenge.battleStartTick) {
                challenge.battleStartTick = Long.MAX_VALUE;
                startBattle(player, challenge);
                return;
            }
            if (challenge.battleId == null && challenge.entity.getBattleId() != null) {
                challenge.battleId = challenge.entity.getBattleId();
            }
            if (time > challenge.startAt + 40
                && BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) == null) {
                finish(player, false);
            }
        }
    }

    private static void startBattle(ServerPlayer player, Challenge challenge) {
        if (CHALLENGES.get(player.getUUID()) != challenge
            || !player.isAlive() || challenge.entity == null
            || !challenge.entity.isAlive()
            || player.serverLevel() != challenge.entity.level()
            || EventBattleBridge.hasPendingTrainerBattle(player.getUUID())
            || BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) != null) {
            finish(player, false);
            return;
        }
        try {
            if (!challenge.entity.forceBattle(player)) {
                finish(player, false);
                player.displayClientMessage(Component.literal(
                    "전투를 시작할 수 없습니다. 싸울 수 있는 포켓몬을 준비해 주세요."
                ), true);
                return;
            }
            challenge.battleId = challenge.entity.getBattleId();
            LOGGER.info(
                "Gate Pokemon battle started: gate={}, player={}, species={}, entity={}, battle={}",
                challenge.actor.gate.id(), player.getGameProfile().getName(),
                challenge.entity.getPokemon().getSpecies().getResourceIdentifier(),
                challenge.entity.getUUID(), challenge.battleId
            );
        } catch (RuntimeException error) {
            LOGGER.warn(
                "Could not start gate Pokemon battle: {}",
                challenge.actor.gate.id(), error
            );
            finish(player, false);
            player.displayClientMessage(Component.literal(
                "관문 전투를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요."
            ), true);
        }
    }

    private static void victory(BattleVictoryEvent event) {
        for (var actor : event.getBattle().getActors()) {
            if (actor instanceof PlayerBattleActor playerActor && playerActor.getEntity() != null
                    && matchesBattle(playerActor.getEntity(), event.getBattle().getBattleId())) {
                finish(playerActor.getEntity(), event.getWinners().contains(actor));
            }
        }
    }

    private static boolean matchesBattle(ServerPlayer player, UUID battle) {
        Challenge challenge = CHALLENGES.get(player.getUUID());
        if (challenge == null) return false;
        UUID activeBattle = challenge.battleId;
        if (activeBattle == null && challenge.entity != null) {
            activeBattle = challenge.entity.getBattleId();
            challenge.battleId = activeBattle;
        }
        return battle.equals(activeBattle);
    }

    private static void finish(ServerPlayer player, boolean completed) {
        Challenge challenge = CHALLENGES.remove(player.getUUID());
        if (challenge == null) return;
        if (completed) new ServerPlayerEventState(player).setFlag(challenge.actor.gate.pokemon().completionFlag(), true);
        if (challenge.entity != null) challenge.entity.discard();
        synchronizeActorPose(
            challenge.actor,
            challenge.actor.gate.pokemon().poseWhileChallenged(
                isActorChallenged(challenge.actor)
            )
        );
    }

    private static boolean isActorChallenged(Actor actor) {
        return CHALLENGES.values().stream().anyMatch(
            challenge -> challenge.actor == actor
        );
    }

    private static void synchronizeActorPose(Actor actor, String pose) {
        if (actor.entity.isRemoved()) return;
        PoseType desired = pose.equals("sleep") ? PoseType.SLEEP : PoseType.STAND;
        if (actor.entity.getEntityData().get(PokemonEntity.getPOSE_TYPE()) != desired) {
            actor.entity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), desired);
        }
    }

    static boolean isChallenging(ServerPlayer player, String gateId) {
        Challenge challenge = CHALLENGES.get(player.getUUID());
        return challenge != null && challenge.actor.gate.id().equals(gateId);
    }

    public static List<GatePokemonNetwork.View> views(Entity entity) {
        if (entity.level().isClientSide()) return GatePokemonNetwork.clientViews(entity.level().dimension().location());
        return VIEWS.getOrDefault(entity.getUUID(), List.of());
    }

    public static AABB actorBounds(Entity entity) {
        if (entity.level().isClientSide()) return GatePokemonNetwork.clientViews(entity.level().dimension().location()).stream()
            .filter(view -> view.entityId().equals(entity.getUUID())).map(GatePokemonNetwork.View::bounds).findFirst().orElse(null);
        if (!entity.getTags().contains(ACTOR_TAG)) return null;
        return ACTORS.values().stream().filter(actor -> actor.entity == entity).map(Actor::bounds).findFirst().orElse(null);
    }

    private record Actor(WorldGateSystem.Gate gate, PokemonEntity entity, AABB bounds) {}
    private static final class Challenge {
        final Actor actor;
        final long startAt;
        PokemonEntity entity;
        UUID pokemonId;
        UUID battleId;
        long battleStartTick = Long.MAX_VALUE;
        Challenge(Actor actor, long startAt) { this.actor = actor; this.startAt = startAt; }
    }
}
