package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import dev.buizz.cobbleventure.adventure.PokemonCenterDefeatReturn;
import dev.buizz.cobbleventure.adventure.event.EventBattleBridge;
import dev.buizz.cobbleventure.adventure.event.EventBattlePreset;
import dev.buizz.cobbleventure.adventure.event.EventBattlePresetRepository;
import dev.buizz.cobbleventure.adventure.event.ServerPlayerEventState;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import dev.buizz.cobbleventure.playermenu.BagApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Validates dungeon content and runs fixed-template dungeon instances. */
final class DungeonSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceKey<Level> DUNGEONS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "dungeons")
    );
    private static final String RETURN_STACK = "cobbleventureDungeonReturnStack";
    private static final int MAX_SLOTS = 64;
    private static final int SLOT_SPACING = 512;
    private static final int SLOT_START_X = 32768;
    private static final int SLOT_Y = 80;
    private static final double ENTRANCE_RADIUS_SQUARED = 9.0D;
    private static final double EXIT_RADIUS_SQUARED = 2.25D;
    private static volatile Map<String, DungeonDefinition> definitions = Map.of();
    private static volatile Map<String, DungeonPieceDefinition> pieceDefinitions = Map.of();
    private static volatile Map<String, DungeonAuthoredPlanDefinition> authoredPlans = Map.of();
    private static volatile Map<String, DungeonEntranceRef> entrances = Map.of();
    private static volatile Map<String, StructureAnchor> structureAnchors = Map.of();
    private static final Map<String, PlacedEntrance> ACTIVE_ENTRANCES = new HashMap<>();
    private static final Map<UUID, String> INSIDE_ENTRANCES = new HashMap<>();
    private static final Map<UUID, PendingEntry> PENDING_ENTRIES = new HashMap<>();
    private static final DungeonEntryQueue ENTRY_QUEUE = new DungeonEntryQueue();
    private static final Map<UUID, QueuedEntry> QUEUED_ENTRIES = new HashMap<>();
    private static final Map<UUID, ActiveRun> ACTIVE_RUNS = new HashMap<>();
    private static final Set<Integer> ACTIVE_SLOTS = new HashSet<>();
    private static final Set<UUID> COMPLETING_RUNS = new HashSet<>();
    private static final Set<UUID> INTERNAL_TELEPORTS = new HashSet<>();
    private static final long TETHER_WARNING_COOLDOWN_TICKS = 100L;
    private static final long OBJECTIVE_TRACKER_INTERVAL_TICKS = 80L;

    private DungeonSystem() {}

    static void register(IEventBus modBus) {
        DungeonGuideNetwork.register(modBus);
        PokemonCenterDefeatReturn.setDefeatRecoveryOverride(
            DungeonSystem::handlePartyWipe
        );
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST, DungeonSystem::onEntityInteract
        );
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onTeleport);
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onServerTick);
        CobblemonEvents.BATTLE_STARTED_POST.subscribe(
            (Consumer<BattleStartedEvent.Post>) DungeonSystem::onBattleStarted
        );
        CobblemonEvents.BATTLE_VICTORY.subscribe(
            (Consumer<BattleVictoryEvent>) DungeonSystem::onBattleVictory
        );
        CobblemonEvents.BATTLE_FLED.subscribe(
            (Consumer<BattleFledEvent>) DungeonSystem::onBattleFled
        );
    }

    static synchronized void initialize(MinecraftServer server, HexWorldPlan world) {
        Map<String, DungeonDefinition> loaded = DungeonDefinition.loadAll(
            server.getResourceManager()
        );
        Map<String, DungeonPieceDefinition> loadedPieces = DungeonPieceDefinition.loadAll(
            server.getResourceManager()
        );
        Map<String, DungeonAuthoredPlanDefinition> loadedPlans =
            DungeonAuthoredPlanDefinition.loadAll(server.getResourceManager());
        DungeonPieceLayout.validateAuthoredDefinitions(
            loaded.values(), loadedPieces.values(), loadedPlans
        );
        Map<String, DungeonEntranceRef> byEntrance = new LinkedHashMap<>();
        for (DungeonDefinition definition : loaded.values()) {
            for (DungeonDefinition.Entrance entrance : definition.entrances()) {
                byEntrance.put(
                    entrance.entranceId(),
                    new DungeonEntranceRef(definition, entrance)
                );
            }
        }
        Map<String, String> placements = new LinkedHashMap<>();
        Map<String, StructureAnchor> anchors = new LinkedHashMap<>();
        for (WorldStructureSystem.WorldStructure structure : world.worldStructures()) {
            for (WorldStructureSystem.DungeonConnection connection
                : structure.dungeonConnections()) {
                StructureAnchor anchor = readStructureAnchor(
                    server.getResourceManager(), structure, connection.anchorId()
                );
                anchors.put(anchorKey(structure.structure(), connection.anchorId()), anchor);
                if (!byEntrance.containsKey(connection.entranceId())) {
                    throw new IllegalStateException(
                        "World structure references missing dungeon entrance: "
                            + structure.id() + " -> " + connection.entranceId()
                    );
                }
                String previous = placements.putIfAbsent(
                    connection.entranceId(), structure.id()
                );
                if (previous != null) {
                    throw new IllegalStateException(
                        "Dungeon entrance is placed more than once: "
                            + connection.entranceId() + " (" + previous
                            + " / " + structure.id() + ")"
                    );
                }
            }
        }
        discoverBuildingPlacements(
            server.getResourceManager(), byEntrance, placements
        );
        List<CaveDungeonPlacement> cavePlacements = discoverCavePlacements(
            server.getResourceManager(), byEntrance, placements
        );
        for (String entranceId : byEntrance.keySet()) {
            if (!placements.containsKey(entranceId)) {
                throw new IllegalStateException(
                    "Dungeon entrance has no world placement: " + entranceId
                );
            }
        }
        definitions = loaded;
        pieceDefinitions = loadedPieces;
        authoredPlans = loadedPlans;
        DungeonPieceLayout.clearCache();
        entrances = Map.copyOf(byEntrance);
        structureAnchors = Map.copyOf(anchors);
        ACTIVE_ENTRANCES.clear();
        activateCavePlacements(server, cavePlacements);
        INSIDE_ENTRANCES.clear();
        PENDING_ENTRIES.clear();
        for (Map.Entry<UUID, QueuedEntry> queued : QUEUED_ENTRIES.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(queued.getKey());
            if (player != null) {
                DungeonGuideNetwork.closeQueue(
                    player,
                    queued.getValue().pending().ref().entrance().entranceId()
                );
            }
        }
        ENTRY_QUEUE.clear();
        QUEUED_ENTRIES.clear();
        ACTIVE_RUNS.clear();
        ACTIVE_SLOTS.clear();
        COMPLETING_RUNS.clear();
        INTERNAL_TELEPORTS.clear();
        restorePersistedRuns(server);
        LOGGER.info(
            "Dungeon catalog loaded: definitions={}, entrances={}",
            definitions.size(), entrances.size()
        );
    }

    static synchronized void registerWorldPlacement(
        ServerLevel level,
        WorldStructureSystem.WorldStructure structure,
        BlockPos origin,
        Rotation rotation
    ) {
        for (WorldStructureSystem.DungeonConnection connection
            : structure.dungeonConnections()) {
            StructureAnchor anchor = structureAnchors.get(
                anchorKey(structure.structure(), connection.anchorId())
            );
            if (anchor == null) {
                throw new IllegalStateException(
                    "Dungeon placement anchor was not initialized: " + connection.anchorId()
                );
            }
            BlockPos rotated = StructureTemplate.transform(
                anchor.position(), Mirror.NONE, rotation, BlockPos.ZERO
            );
            Direction facing = rotation.rotate(anchor.facing());
            BlockPos trigger = origin.offset(rotated);
            BlockPos safeReturn = trigger.relative(facing);
            ACTIVE_ENTRANCES.put(
                connection.entranceId(),
                new PlacedEntrance(
                    connection.entranceId(), level.dimension(), trigger, safeReturn
                )
            );
        }
    }

    static synchronized void registerBuildingPlacement(
        ServerLevel level, String entranceId, BlockPos trigger, BlockPos safeReturn
    ) {
        if (!entrances.containsKey(entranceId)) {
            return;
        }
        ACTIVE_ENTRANCES.put(
            entranceId,
            new PlacedEntrance(entranceId, level.dimension(), trigger, safeReturn)
        );
    }

    static synchronized void tick(ServerPlayer player, long gameTime) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (player.serverLevel().dimension().equals(DUNGEONS)) {
            if (run != null && escapeActionsBlocked(run)
                && !insideRunBounds(player.position(), run.origin(), run.size())) {
                failRun(
                    run,
                    "참가자가 던전 경계를 벗어나 도전이 종료되었습니다.",
                    false
                );
                return;
            }
            if (run != null && enforceCooperativeTether(player, run, gameTime)) {
                return;
            }
            if (run != null && expirePendingEncounter(run, gameTime)) {
                return;
            }
            if (run != null) {
                activateCheckpoint(player, run);
            }
            boolean completed = run != null && completionReached(player, run);
            if (completed) {
                DungeonDefinition definition = definitions.get(run.dungeonId());
                if (definition.completion().returnTrigger().equals("automatic")
                    || (run.clearExit() != null
                        && distanceSquared(player.position(), run.clearExit())
                            <= EXIT_RADIUS_SQUARED)) {
                    completeRun(player, run);
                    return;
                }
            }
            if (run != null && gameTime >= run.teleportCooldownUntil()
                && distanceSquared(player.position(), run.exit()) <= EXIT_RADIUS_SQUARED) {
                if (completed) {
                    completeRun(player, run);
                } else {
                    failRun(
                        run, "참가자가 출구를 사용해 던전 도전이 종료되었습니다.", false
                    );
                }
                return;
            }
            if (run != null) {
                showObjectiveTracker(player, run, gameTime);
                return;
            }
        }
        if (run != null) {
            if (escapeActionsBlocked(run)) {
                failRun(
                    run,
                    "참가자의 외부 이동이 감지되어 던전 도전이 종료되었습니다.",
                    false
                );
            } else {
                abandonRun(player);
            }
            return;
        }
        QueuedEntry queued = QUEUED_ENTRIES.get(player.getUUID());
        if (queued != null) {
            if (!queued.pending().placement().dimension().equals(
                    player.serverLevel().dimension())
                || distanceSquared(
                    player.position(), queued.pending().placement().trigger()
                ) > queued.stayRadiusSquared()
                || BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
                cancelQueuedEntry(
                    player,
                    "입구에서 멀어졌거나 다른 행동을 시작해 던전 대기가 취소되었습니다."
                );
            } else if (gameTime >= queued.expiresAt()
                && queued.pending().ref().definition().match().onTimeout().equals("cancel")) {
                cancelQueuedEntry(player, "던전 매칭 대기 시간이 만료되었습니다.");
            } else {
                return;
            }
        }
        PlacedEntrance touching = ACTIVE_ENTRANCES.values().stream()
            .filter(placed -> placed.dimension().equals(player.serverLevel().dimension()))
            .filter(placed -> distanceSquared(player.position(), placed.trigger())
                <= ENTRANCE_RADIUS_SQUARED)
            .findFirst().orElse(null);
        String previous = INSIDE_ENTRANCES.get(player.getUUID());
        if (touching == null) {
            INSIDE_ENTRANCES.remove(player.getUUID());
            PENDING_ENTRIES.remove(player.getUUID());
            return;
        }
        if (touching.entranceId().equals(previous)) {
            return;
        }
        INSIDE_ENTRANCES.put(player.getUUID(), touching.entranceId());
        openGuide(player, touching);
    }

    private static synchronized void onTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || INTERNAL_TELEPORTS.contains(player.getUUID())
            || !escapeActionsBlocked(run)) {
            return;
        }
        event.setCanceled(true);
        deferObjectiveTracker(run, player, 60L);
        player.displayClientMessage(Component.literal(
            "[Cobbleventure] 던전 도전 중에는 외부 순간이동을 사용할 수 없습니다."
        ), true);
    }

    private static boolean escapeActionsBlocked(ActiveRun run) {
        DungeonDefinition definition = definitions.get(run.dungeonId());
        return definition != null && !definition.battleRules().allowEscapeActions();
    }

    static boolean insideRunBounds(Vec3 position, BlockPos origin, BlockPos size) {
        double margin = 2.0D;
        return size.getX() > 0 && size.getY() > 0 && size.getZ() > 0
            && position.x >= origin.getX() - margin
            && position.x < origin.getX() + size.getX() + margin
            && position.y >= origin.getY() - margin
            && position.y < origin.getY() + size.getY() + margin
            && position.z >= origin.getZ() - margin
            && position.z < origin.getZ() + size.getZ() + margin;
    }

    private static boolean enforceCooperativeTether(
        ServerPlayer player, ActiveRun run, long gameTime
    ) {
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null
            || !definition.multiplayer().mode().equals("cooperative")
            || definition.multiplayer().tether() == null
            || run.participantIds().size() < 2
            || BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return false;
        }
        ServerPlayer partner = run.participantIds().stream()
            .filter(id -> !id.equals(player.getUUID()))
            .map(id -> run.server().getPlayerList().getPlayer(id))
            .filter(candidate -> candidate != null
                && candidate.serverLevel() == player.serverLevel())
            .findFirst().orElse(null);
        if (partner == null
            || BattleRegistry.getBattleByParticipatingPlayer(partner) != null) {
            return false;
        }
        DungeonDefinition.Tether tether = definition.multiplayer().tether();
        DungeonTetherPolicy.Zone zone = DungeonTetherPolicy.classify(
            player.distanceToSqr(partner), tether.warnDistance(), tether.maxDistance()
        );
        if (zone == DungeonTetherPolicy.Zone.TOGETHER) {
            run.tetherWarningUntil().remove(player.getUUID());
            return false;
        }
        if (zone == DungeonTetherPolicy.Zone.WARNING) {
            long warningUntil = run.tetherWarningUntil().getOrDefault(
                player.getUUID(), 0L
            );
            if (gameTime >= warningUntil) {
                player.displayClientMessage(Component.literal(
                    "[던전] 동료와 너무 멀어지고 있습니다. 최대 거리: "
                        + tether.maxDistance() + "블록"
                ), true);
                run.tetherWarningUntil().put(
                    player.getUUID(), gameTime + TETHER_WARNING_COOLDOWN_TICKS
                );
            }
            return false;
        }
        Vec3 target = tetherReturnPosition(player, partner, run);
        INTERNAL_TELEPORTS.add(player.getUUID());
        try {
            player.teleportTo(
                player.serverLevel(), target.x, target.y, target.z,
                player.getYRot(), player.getXRot()
            );
            player.fallDistance = 0.0F;
        } finally {
            INTERNAL_TELEPORTS.remove(player.getUUID());
        }
        player.displayClientMessage(Component.literal(
            "[던전] 동료와의 최대 거리를 넘어 가까운 위치로 복귀했습니다."
        ), true);
        partner.displayClientMessage(Component.literal(
            "[던전] 멀어진 동료가 가까운 위치로 복귀했습니다."
        ), true);
        run.tetherWarningUntil().put(
            player.getUUID(), gameTime + TETHER_WARNING_COOLDOWN_TICKS
        );
        return true;
    }

    private static Vec3 tetherReturnPosition(
        ServerPlayer player, ServerPlayer partner, ActiveRun run
    ) {
        double[][] offsets = {
            {2.0D, 0.0D}, {-2.0D, 0.0D}, {0.0D, 2.0D}, {0.0D, -2.0D}
        };
        for (double[] offset : offsets) {
            Vec3 candidate = partner.position().add(offset[0], 0.0D, offset[1]);
            if (!insideRunBounds(candidate, run.origin(), run.size())) {
                continue;
            }
            BlockPos floor = BlockPos.containing(candidate).below();
            if (!player.serverLevel().getBlockState(floor).isFaceSturdy(
                player.serverLevel(), floor, Direction.UP
            )) {
                continue;
            }
            AABB movedBounds = player.getBoundingBox().move(
                candidate.x - player.getX(),
                candidate.y - player.getY(),
                candidate.z - player.getZ()
            );
            if (player.serverLevel().noCollision(player, movedBounds)) {
                return candidate;
            }
        }
        return partner.position();
    }

    static synchronized PursuitEncounterSystem.Config randomEncounterConfig(
        ServerPlayer player
    ) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        return run == null ? null : run.randomEncounters();
    }

    static synchronized DungeonDefinition.BattleRules activeBattleRules(
        ServerPlayer player
    ) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || !player.serverLevel().dimension().equals(DUNGEONS)) {
            return null;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        return definition == null ? null : definition.battleRules();
    }

    private static synchronized boolean handlePartyWipe(ServerPlayer player) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || !player.serverLevel().dimension().equals(DUNGEONS)) {
            return false;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) {
            Cobblemon.INSTANCE.getStorage().getParty(player).heal();
            returnPlayer(player, "던전 도전에 실패해 입구로 복귀했습니다.");
            return true;
        }
        DungeonDefinition.Lifecycle lifecycle = definition.lifecycle();
        if (lifecycle.healOnWipe()) {
            for (UUID participantId : run.participantIds()) {
                ServerPlayer participant = player.getServer().getPlayerList().getPlayer(
                    participantId
                );
                if (participant != null) {
                    Cobblemon.INSTANCE.getStorage().getParty(participant).heal();
                }
            }
        }
        boolean usePokemonCenter = lifecycle.wipeReturn().equals("pokemon_center");
        if (!usePokemonCenter) {
            failRun(run, "파티가 전멸해 던전 도전에 실패했습니다.", false);
        }
        return !usePokemonCenter;
    }

    private static void openGuide(ServerPlayer player, PlacedEntrance placement) {
        DungeonEntranceRef ref = entrances.get(placement.entranceId());
        if (ref == null) {
            player.sendSystemMessage(Component.literal("던전 입구 설정을 찾을 수 없습니다."));
            return;
        }
        PENDING_ENTRIES.put(
            player.getUUID(), new PendingEntry(ref, placement)
        );
        DungeonDefinition definition = ref.definition();
        DungeonEntryEligibility.PartySnapshot party = partySnapshot(player);
        int currentPartyLevel = definition.eligibility().levelMeasure().equals("highest")
            ? party.highestLevel() : party.averageLevel();
        DungeonGuideNetwork.open(
            player,
            new DungeonGuideNetwork.GuideData(
                ref.entrance().entranceId(),
                definition.displayName(),
                definition.description(),
                definition.difficulty().recommendedMin(),
                definition.difficulty().recommendedMax(),
                definition.difficulty().internalMin(),
                definition.difficulty().internalMax(),
                definition.entryUi().infoMode(),
                definition.lifecycle().wipeReturn(),
                definition.lifecycle().healOnWipe(),
                definition.completion().repeatable(),
                definition.eligibility().levelMeasure(),
                currentPartyLevel,
                definition.multiplayer().mode(),
                definition.match().requiredPlayers(),
                definition.multiplayer().tether() == null ? 0
                    : definition.multiplayer().tether().warnDistance(),
                definition.multiplayer().tether() == null ? 0
                    : definition.multiplayer().tether().maxDistance()
            )
        );
    }

    static synchronized void respond(
        ServerPlayer player, String entranceId, boolean accepted
    ) {
        PendingEntry pending = PENDING_ENTRIES.remove(player.getUUID());
        if (!accepted) {
            return;
        }
        if (pending == null
            || !pending.ref().entrance().entranceId().equals(entranceId)
            || !pending.placement().dimension().equals(player.serverLevel().dimension())
            || distanceSquared(player.position(), pending.placement().trigger())
                > ENTRANCE_RADIUS_SQUARED) {
            player.sendSystemMessage(Component.literal(
                "입구에서 멀어졌거나 입장 요청이 만료되었습니다."
            ));
            return;
        }
        String problem = entryProblem(player, pending);
        if (problem != null) {
            player.sendSystemMessage(Component.literal(problem));
            return;
        }
        DungeonDefinition definition = pending.ref().definition();
        if (definition.match().requiredPlayers() == 1) {
            startMatchedRun(List.of(new MatchedEntry(player, pending)));
            return;
        }
        long queuedAt = player.serverLevel().getGameTime();
        long expiresAt = queuedAt + definition.match().timeoutSeconds() * 20L;
        String poolKey = pending.ref().entrance().entranceId();
        if (!ENTRY_QUEUE.enqueue(player.getUUID(), poolKey, queuedAt, expiresAt)) {
            player.sendSystemMessage(Component.literal(
                "이미 다른 던전 입장을 기다리고 있습니다."
            ));
            return;
        }
        double stayRadius = definition.match().stayRadius();
        QUEUED_ENTRIES.put(
            player.getUUID(),
            new QueuedEntry(pending, expiresAt, stayRadius * stayRadius)
        );
        DungeonGuideNetwork.openQueue(
            player,
            new DungeonGuideNetwork.QueueData(
                pending.ref().entrance().entranceId(),
                definition.displayName(),
                ENTRY_QUEUE.size(poolKey),
                definition.match().requiredPlayers(),
                definition.match().timeoutSeconds()
            )
        );
        List<DungeonEntryQueue.Request> matched = ENTRY_QUEUE.poll(
            poolKey, definition.match().requiredPlayers()
        );
        if (matched.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "다른 도전자를 기다리는 중입니다. (1/"
                    + definition.match().requiredPlayers() + ")"
            ));
            return;
        }
        List<MatchedEntry> entries = new ArrayList<>(matched.size());
        boolean missingParticipant = false;
        for (DungeonEntryQueue.Request request : matched) {
            QueuedEntry queued = QUEUED_ENTRIES.remove(request.playerId());
            ServerPlayer member = player.getServer().getPlayerList().getPlayer(
                request.playerId()
            );
            if (queued == null || member == null) {
                missingParticipant = true;
                continue;
            }
            entries.add(new MatchedEntry(member, queued.pending()));
        }
        if (missingParticipant) {
            cancelMatch(entries, "참가자의 연결이 끊겨 던전 매칭이 취소되었습니다.");
            return;
        }
        for (MatchedEntry entry : entries) {
            String memberProblem = entryProblem(entry.player(), entry.pending());
            if (memberProblem != null) {
                cancelMatch(entries, "참가자 조건이 변경되어 매칭이 취소되었습니다.");
                entry.player().sendSystemMessage(Component.literal(memberProblem));
                return;
            }
        }
        entries.forEach(entry -> DungeonGuideNetwork.preparingQueue(
            entry.player(), entry.pending().ref().entrance().entranceId()
        ));
        startMatchedRun(List.copyOf(entries));
    }

    private static String entryProblem(ServerPlayer player, PendingEntry pending) {
        DungeonDefinition definition = pending.ref().definition();
        if (!definition.terrain().mode().equals("fixed_template")
            && !definition.terrain().mode().equals("nbt_pieces")
            && !definition.terrain().mode().equals("procedural_cave")) {
            return "아직 지원하지 않는 던전 지형 방식입니다.";
        }
        if ((definition.terrain().mode().equals("nbt_pieces")
            || definition.terrain().mode().equals("procedural_cave"))
            && !definition.plan().mode().equals("runtime")) {
            return "현재는 런타임 생성 던전만 입장할 수 있습니다.";
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return "배틀 중에는 던전에 입장할 수 없습니다.";
        }
        DungeonEntryEligibility.Evaluation eligibility = DungeonEntryEligibility.evaluate(
            definition.eligibility(), definition.difficulty(), partySnapshot(player)
        );
        if (!eligibility.allowed()) {
            return eligibilityMessage(definition, eligibility);
        }
        if (eligibility.issue()
            == DungeonEntryEligibility.Issue.LEVEL_OUTSIDE_RECOMMENDED) {
            player.sendSystemMessage(Component.literal(
                "주의: " + eligibilityMessage(definition, eligibility)
            ));
        }
        ServerPlayerEventState state = new ServerPlayerEventState(player);
        int previousClears = DungeonClearProgress.importLegacyClear(
            player.getPersistentData(),
            definition.id(),
            state.flag(definition.completion().victoryFlag())
        );
        if (!definition.completion().repeatable() && previousClears > 0) {
            return "이미 클리어한 던전입니다.";
        }
        return null;
    }

    private static void startMatchedRun(List<MatchedEntry> entries) {
        MatchedEntry first = entries.getFirst();
        ServerPlayer player = first.player();
        PendingEntry pending = first.pending();
        DungeonDefinition definition = pending.ref().definition();
        ServerLevel dungeonLevel = player.getServer().getLevel(DUNGEONS);
        if (dungeonLevel == null) {
            cancelMatch(entries, "던전 차원을 찾을 수 없습니다.");
            return;
        }
        int slot = allocateSlot();
        if (slot < 0) {
            cancelMatch(entries, "사용 가능한 던전 슬롯이 없습니다.");
            return;
        }
        BlockPos origin = slotOrigin(slot);
        BlockPos size = BlockPos.ZERO;
        PreparedTerrain terrain = null;
        BlockPos clearExit = null;
        PursuitEncounterSystem.Config randomEncounters;
        SpawnedEncounters spawnedEncounters;
        Map<String, BlockPos> lootPositions;
        Map<String, BlockPos> healingPositions;
        Map<String, BlockPos> objectivePositions;
        Map<String, CheckpointPosition> checkpointPositions;
        Map<String, GateBounds> gateBounds;
        try {
            terrain = prepareTerrain(
                dungeonLevel, definition, origin, first.player().getUUID()
            );
            size = terrain.size();
            gateBounds = resolveGateBounds(definition, origin, terrain);
            placeGates(dungeonLevel, definition, gateBounds);
            clearExit = placeClearExit(dungeonLevel, definition, origin, terrain);
            healingPositions = placeHealingStations(
                dungeonLevel, definition, origin, terrain
            );
            objectivePositions = placeObjectives(
                dungeonLevel, definition, origin, terrain
            );
            lootPositions = placeLootContainers(
                dungeonLevel, definition, origin, terrain
            );
            checkpointPositions = resolveCheckpointPositions(
                definition, origin, terrain
            );
            spawnedEncounters = spawnEncounters(
                dungeonLevel, definition, origin, terrain
            );
            randomEncounters = createRandomEncounterConfig(
                definition, origin, size, slot
            );
        } catch (RuntimeException error) {
            ACTIVE_SLOTS.remove(slot);
            if (!size.equals(BlockPos.ZERO)) {
                clearSlot(dungeonLevel, origin, size);
            }
            LOGGER.error("Dungeon instance preparation failed: {}", definition.id(), error);
            cancelMatch(entries, "던전 준비에 실패했습니다. 서버 로그를 확인하세요.");
            return;
        }
        for (MatchedEntry entry : entries) {
            pushReturnFrame(entry.player(), entry.pending().placement().safeReturn());
            if (definition.completion().repeatable()) {
                new ServerPlayerEventState(entry.player()).setFlag(
                    definition.completion().victoryFlag(), false
                );
            }
        }
        BlockPos entry = origin.offset(terrain.entryPosition());
        BlockPos exit = origin.offset(terrain.exitPosition());
        long cooldown = dungeonLevel.getGameTime() + 40L;
        Set<UUID> participantIds = entries.stream()
            .map(matched -> matched.player().getUUID())
            .collect(Collectors.toUnmodifiableSet());
        ActiveRun run = new ActiveRun(
            UUID.randomUUID(), terrain.seed(), player.getServer(), definition.id(),
            slot, origin, size, entry, exit,
            clearExit, cooldown, randomEncounters, new HashMap<>(), participantIds,
            new HashMap<>(),
            new EncounterRuntime(
                spawnedEncounters.entities(), definition.encounters(),
                spawnedEncounters.positions()
            ),
            new DungeonLootClaims(), new DungeonLootLedger(), lootPositions,
            healingPositions, objectivePositions, new HashSet<>(),
            checkpointPositions, new HashMap<>(), new HashMap<>(),
            gateBounds, new HashSet<>(), new HashMap<>()
        );
        entries.forEach(matched -> ACTIVE_RUNS.put(matched.player().getUUID(), run));
        persistActiveRuns(player.getServer());
        for (int index = 0; index < entries.size(); index++) {
            ServerPlayer member = entries.get(index).player();
            double xOffset = (index - (entries.size() - 1) / 2.0D) * 1.25D;
            member.teleportTo(
                dungeonLevel,
                entry.getX() + 0.5D + xOffset, entry.getY(), entry.getZ() + 0.5D,
                member.getYRot(), member.getXRot()
            );
            if (member.serverLevel() != dungeonLevel) {
                failRun(
                    run,
                    "참가자 이동에 실패해 준비된 던전이 초기화되었습니다.",
                    false
                );
                return;
            }
        }
        for (MatchedEntry matched : entries) {
            DungeonGuideNetwork.closeQueue(
                matched.player(), matched.pending().ref().entrance().entranceId()
            );
            matched.player().sendSystemMessage(Component.literal(
                definition.displayName() + " 도전을 " + entries.size()
                    + "명이 함께 시작합니다."
            ));
        }
    }

    private static void cancelMatch(List<MatchedEntry> entries, String message) {
        for (MatchedEntry entry : entries) {
            DungeonGuideNetwork.closeQueue(
                entry.player(), entry.pending().ref().entrance().entranceId()
            );
            entry.player().sendSystemMessage(Component.literal(message));
        }
    }

    private static DungeonEntryEligibility.PartySnapshot partySnapshot(
        ServerPlayer player
    ) {
        int size = 0;
        int usable = 0;
        int levelTotal = 0;
        int highestLevel = 0;
        for (var pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            size++;
            if (!pokemon.isFainted()) usable++;
            levelTotal += pokemon.getLevel();
            highestLevel = Math.max(highestLevel, pokemon.getLevel());
        }
        int averageLevel = size == 0 ? 0 : Math.round((float) levelTotal / size);
        return new DungeonEntryEligibility.PartySnapshot(
            size, usable, averageLevel, highestLevel
        );
    }

    private static String eligibilityMessage(
        DungeonDefinition definition,
        DungeonEntryEligibility.Evaluation evaluation
    ) {
        DungeonDefinition.Eligibility settings = definition.eligibility();
        return switch (evaluation.issue()) {
            case PARTY_TOO_SMALL -> "던전 입장에는 포켓몬이 최소 "
                + settings.minimumPartySize() + "마리 필요합니다.";
            case PARTY_TOO_LARGE -> "이 던전에는 포켓몬을 최대 "
                + settings.maximumPartySize() + "마리까지 데려갈 수 있습니다.";
            case NO_USABLE_POKEMON -> "사용 가능한 포켓몬이 없어 던전에 입장할 수 없습니다.";
            case LEVEL_OUTSIDE_RECOMMENDED -> "현재 파티 "
                + (settings.levelMeasure().equals("highest") ? "최고" : "평균")
                + " 레벨은 Lv." + evaluation.measuredLevel()
                + "이며 권장 범위는 Lv." + definition.difficulty().recommendedMin()
                + "–" + definition.difficulty().recommendedMax() + "입니다.";
            case NONE -> "던전 입장 조건을 충족했습니다.";
        };
    }

    private static BlockPos prepareFixedTemplate(
        ServerLevel level, DungeonDefinition definition, BlockPos origin
    ) {
        ResourceLocation templateId = ResourceLocation.parse(
            definition.terrain().template()
        );
        StructureTemplate template = level.getStructureManager().get(templateId)
            .orElseThrow(() -> new IllegalStateException(
                "Dungeon template is missing: " + templateId
            ));
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE);
        ExplicitAirPlacementProcessor.configure(template, settings);
        if (!template.placeInWorld(
            level, origin, origin, settings,
            RandomSource.create(level.getSeed() ^ origin.asLong()), 2
        )) {
            throw new IllegalStateException(
                "Dungeon template placement failed: " + definition.id()
            );
        }
        return new BlockPos(template.getSize());
    }

    private static PreparedTerrain prepareTerrain(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        UUID playerId
    ) {
        if (definition.terrain().mode().equals("fixed_template")) {
            return new PreparedTerrain(
                prepareFixedTemplate(level, definition, origin),
                definition.terrain().entryPosition(),
                definition.terrain().exitPosition(),
                Map.of(), level.getSeed() ^ origin.asLong()
            );
        }
        return switch (definition.terrain().mode()) {
            case "nbt_pieces" -> prepareNbtPieces(
                level, definition, origin, playerId
            );
            case "procedural_cave" -> prepareProceduralCave(
                level, definition, origin, playerId
            );
            default -> throw new IllegalStateException(
                "Unsupported dungeon terrain mode: " + definition.terrain().mode()
            );
        };
    }

    private static PreparedTerrain prepareNbtPieces(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        UUID playerId
    ) {
        long seed = dungeonPlanSeed(level, definition, origin, playerId);
        long startedAt = System.nanoTime();
        DungeonPieceLayout layout = DungeonPieceLayout.generate(
            definition, pieceDefinitions.values(), authoredPlans, seed
        );
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (elapsedMs > definition.plan().generationTimeoutMs()) {
            throw new IllegalStateException(
                "Dungeon piece planning exceeded generation_timeout_ms: "
                    + elapsedMs + "ms > " + definition.plan().generationTimeoutMs() + "ms"
            );
        }
        for (DungeonPiecePlan.Placement placement : layout.plan().placements()) {
            DungeonPieceDefinition piece = pieceDefinitions.get(placement.pieceId());
            if (piece == null) {
                throw new IllegalStateException(
                    "Dungeon piece definition is missing: " + placement.pieceId()
                );
            }
            ResourceLocation templateId = ResourceLocation.parse(piece.structure());
            StructureTemplate template = level.getStructureManager().get(templateId)
                .orElseThrow(() -> new IllegalStateException(
                    "Dungeon piece template is missing: " + templateId
                ));
            BlockPos actualSize = new BlockPos(template.getSize());
            if (!actualSize.equals(piece.size())) {
                throw new IllegalStateException(
                    "Dungeon piece metadata size differs from its template: "
                        + piece.id() + " (metadata=" + piece.size()
                        + ", template=" + actualSize + ")"
                );
            }
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(placement.rotation())
                .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE);
            ExplicitAirPlacementProcessor.configure(template, settings);
            BlockPos placementOrigin = origin.offset(placement.templateOrigin());
            if (!template.placeInWorld(
                level, placementOrigin, placementOrigin, settings,
                RandomSource.create(seed ^ placement.index()), 2
            )) {
                throw new IllegalStateException(
                    "Dungeon piece template placement failed: " + piece.id()
                );
            }
        }
        LOGGER.info(
            "Prepared dungeon piece plan: dungeon={}, seed={}, pieces={}, elapsed={}ms",
            definition.id(), seed, layout.plan().placements().size(), elapsedMs
        );
        return new PreparedTerrain(
            definition.terrain().bounds(),
            layout.requiredMarker("entry", null),
            layout.requiredMarker("exit", null),
            layout.featureMarkers(definition, seed), seed
        );
    }

    private static PreparedTerrain prepareProceduralCave(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        UUID playerId
    ) {
        DungeonDefinition.Layout layout = definition.layout();
        if (layout.mode().equals("fixed")) {
            throw new IllegalStateException(
                "Procedural cave layout mode is not implemented yet: " + layout.mode()
            );
        }
        long seed = dungeonPlanSeed(level, definition, origin, playerId);
        RandomSource random = RandomSource.create(seed);
        int rooms = randomRange(random, layout.criticalPathRooms());
        int branches = randomRange(random, layout.branchCount());
        long startedAt = System.nanoTime();
        NaturalCaveGenerator.InstanceResult generated = NaturalCaveGenerator.generateInstance(
            level, definition.id(), seed, origin, definition.terrain().bounds(),
            layout.mode(), rooms, branches, layout.loopChance()
        );
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        LOGGER.info(
            "Prepared procedural cave dungeon: dungeon={}, seed={}, layout={}, rooms={}, "
                + "branches={}, elapsed={}ms",
            definition.id(), seed, layout.mode(), rooms, branches, elapsedMs
        );
        return new PreparedTerrain(
            definition.terrain().bounds(),
            generated.entryPosition(), generated.exitPosition(), Map.of(), seed
        );
    }

    private static int randomRange(
        RandomSource random, DungeonDefinition.IntRange range
    ) {
        return range.minimum() + random.nextInt(range.maximum() - range.minimum() + 1);
    }

    private static long dungeonPlanSeed(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        UUID playerId
    ) {
        long base = level.getSeed() ^ ((long) definition.id().hashCode() << 32);
        return switch (definition.plan().seedPolicy()) {
            case "fixed" -> base;
            case "daily" -> base ^ Math.floorDiv(level.getDayTime(), 24_000L);
            case "weekly" -> base ^ Math.floorDiv(level.getDayTime(), 168_000L);
            case "player" -> base ^ playerId.getMostSignificantBits()
                ^ playerId.getLeastSignificantBits();
            case "match" -> base ^ origin.asLong();
            case "random_per_run" -> level.getRandom().nextLong();
            default -> throw new IllegalStateException(
                "Unsupported dungeon seed policy: " + definition.plan().seedPolicy()
            );
        };
    }

    private static BlockPos featurePosition(
        PreparedTerrain terrain,
        String kind,
        String reference,
        BlockPos fallback
    ) {
        return terrain.markers().getOrDefault(
            new DungeonPieceLayout.MarkerKey(kind, reference), fallback
        );
    }

    private static Map<String, CheckpointPosition> resolveCheckpointPositions(
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        Map<String, CheckpointPosition> positions = new HashMap<>();
        for (DungeonDefinition.Checkpoint checkpoint : definition.support().checkpoints()) {
            BlockPos relative = featurePosition(
                terrain, "checkpoint", checkpoint.id(), checkpoint.position()
            );
            if (relative.getX() < 0 || relative.getY() < 0 || relative.getZ() < 0
                || relative.getX() >= terrain.size().getX()
                || relative.getY() >= terrain.size().getY()
                || relative.getZ() >= terrain.size().getZ()) {
                throw new IllegalStateException(
                    "Dungeon checkpoint exceeds the terrain: " + checkpoint.id()
                );
            }
            positions.put(
                checkpoint.id(),
                new CheckpointPosition(
                    origin.offset(relative), checkpoint.activationRadius()
                )
            );
        }
        return Map.copyOf(positions);
    }

    private static void activateCheckpoint(ServerPlayer player, ActiveRun run) {
        for (Map.Entry<String, CheckpointPosition> entry
            : run.checkpointPositions().entrySet()) {
            CheckpointPosition checkpoint = entry.getValue();
            if (distanceSquared(player.position(), checkpoint.position())
                > checkpoint.activationRadius() * checkpoint.activationRadius()) {
                continue;
            }
            BlockPos previous = run.activeCheckpoints().put(
                player.getUUID(), checkpoint.position()
            );
            if (!checkpoint.position().equals(previous)) {
                player.sendSystemMessage(Component.literal(
                    "[던전] 체크포인트를 활성화했습니다: " + entry.getKey()
                ));
            }
            return;
        }
    }

    private static SpawnedEncounters spawnEncounters(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        Map<UUID, EncounterEntityRef> spawned = new HashMap<>();
        Map<String, BlockPos> positions = new HashMap<>();
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            String markerKind = encounter.boss() ? "boss" : "encounter";
            BlockPos authoredPosition = origin.offset(featurePosition(
                terrain, markerKind, encounter.id(), encounter.position()
            ));
            positions.put(encounter.id(), authoredPosition);
            if (encounter.kind().equals("wild_pokemon")) {
                Entity pokemon = DungeonWildEncounterSupport.spawn(
                    level, encounter.pokemon(), authoredPosition, encounter.yaw()
                );
                spawned.put(
                    pokemon.getUUID(), new EncounterEntityRef(encounter.id(), 0)
                );
                continue;
            }
            for (int index = 0; index < encounter.npcs().size(); index++) {
                int opponentIndex = index;
                BlockPos position = encounterNpcPosition(
                    level, authoredPosition, encounter.yaw(), opponentIndex
                );
                if (!CobbleventureBootstrap.spawnRegionalNpc(
                    level, encounter.npcs().get(opponentIndex), position,
                    encounter.yaw(), "proximity"
                )) {
                    throw new IllegalStateException(
                        "Dungeon NPC placement failed: " + encounter.id()
                            + "[" + opponentIndex + "]"
                    );
                }
                Entity entity = level.getEntitiesOfClass(
                    Entity.class,
                    new AABB(position).inflate(6.0D, 10.0D, 6.0D),
                    candidate -> isEasyNpc(candidate)
                        && !spawned.containsKey(candidate.getUUID())
                ).stream().min(java.util.Comparator.comparingDouble(
                    candidate -> candidate.distanceToSqr(Vec3.atCenterOf(position))
                )).orElse(null);
                if (entity == null) {
                    LOGGER.debug(
                        "Dungeon NPC registration is pending: {}[{}] at {}",
                        encounter.id(), opponentIndex, position
                    );
                } else {
                    spawned.put(entity.getUUID(), new EncounterEntityRef(
                        encounter.id(), opponentIndex
                    ));
                }
            }
        }
        return new SpawnedEncounters(Map.copyOf(spawned), Map.copyOf(positions));
    }

    private static BlockPos encounterNpcPosition(
        ServerLevel level, BlockPos authored, float yaw, int index
    ) {
        if (index == 0) return authored;
        Direction facing = Direction.fromYRot(yaw);
        Direction right = facing.getClockWise();
        List<BlockPos> candidates = List.of(
            authored.relative(right, 2),
            authored.relative(right.getOpposite(), 2),
            authored.relative(facing, 2),
            authored.relative(facing.getOpposite(), 2)
        );
        return candidates.stream().filter(position -> {
            BlockPos floor = position.below();
            return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                && level.getBlockState(position).getCollisionShape(level, position).isEmpty()
                && level.getBlockState(position.above()).getCollisionShape(
                    level, position.above()
                ).isEmpty();
        }).findFirst().orElseThrow(() -> new IllegalStateException(
            "Dungeon partner NPC has no safe adjacent position: " + authored
        ));
    }

    private static boolean isEasyNpc(Entity entity) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return type != null && type.getNamespace().equals("easy_npc");
    }

    private static Map<String, BlockPos> placeLootContainers(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        Map<String, BlockPos> positions = new HashMap<>();
        long runSeed = level.getRandom().nextLong() ^ origin.asLong();
        for (DungeonDefinition.LootContainer container : definition.loot().containers()) {
            String lootTableId = container.lootTable() == null
                ? definition.loot().lootTable() : container.lootTable();
            ResourceKey<LootTable> lootTable = ResourceKey.create(
                Registries.LOOT_TABLE, ResourceLocation.parse(lootTableId)
            );
            if (level.getServer().reloadableRegistries().getLootTable(lootTable)
                == LootTable.EMPTY) {
                throw new IllegalStateException(
                    "Dungeon loot table is missing: " + lootTableId
                        + " (" + definition.id() + " -> " + container.id() + ")"
                );
            }
            BlockPos position = origin.offset(featurePosition(
                terrain, "loot", container.id(), container.position()
            ));
            positions.put(container.id(), position);
            Direction facing = Direction.byName(container.facing());
            var blockState = container.block().equals("barrel")
                ? Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, facing)
                : Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
            if (!level.setBlock(position, blockState, 3)) {
                throw new IllegalStateException(
                    "Dungeon loot container placement failed: " + container.id()
                );
            }
            if (!(level.getBlockEntity(position)
                instanceof RandomizableContainerBlockEntity blockEntity)) {
                throw new IllegalStateException(
                    "Dungeon loot block entity is missing: " + container.id()
                );
            }
            if (definition.loot().ownership().equals("run_shared")) {
                blockEntity.setLootTable(lootTable);
                blockEntity.setLootTableSeed(runSeed ^ container.id().hashCode());
            }
            blockEntity.setChanged();
        }
        return Map.copyOf(positions);
    }

    private static Map<String, BlockPos> placeHealingStations(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        Map<String, BlockPos> positions = new HashMap<>();
        for (DungeonDefinition.HealingStation station
            : definition.support().healingStations()) {
            BlockPos relative = featurePosition(
                terrain, "healing_station", station.id(), station.position()
            );
            BlockPos size = terrain.size();
            if (relative.getX() < 0 || relative.getY() < 0 || relative.getZ() < 0
                || relative.getX() >= size.getX() || relative.getY() >= size.getY()
                || relative.getZ() >= size.getZ()) {
                throw new IllegalStateException(
                    "Dungeon healing station exceeds the template: " + station.id()
                );
            }
            ResourceLocation blockId = ResourceLocation.parse(station.block());
            var block = BuiltInRegistries.BLOCK.getOptional(blockId).orElseThrow(() ->
                new IllegalStateException(
                    "Dungeon healing station block is missing: " + blockId
                )
            );
            BlockPos position = origin.offset(relative);
            if (block == Blocks.AIR
                || !level.setBlock(position, block.defaultBlockState(), 3)) {
                throw new IllegalStateException(
                    "Dungeon healing station placement failed: " + station.id()
                );
            }
            positions.put(station.id(), position);
        }
        return Map.copyOf(positions);
    }

    private static Map<String, BlockPos> placeObjectives(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        Map<String, BlockPos> positions = new HashMap<>();
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            BlockPos relative = featurePosition(
                terrain, "objective", objective.id(), objective.position()
            );
            if (relative == null || relative.getX() < 0 || relative.getY() < 0
                || relative.getZ() < 0 || relative.getX() >= terrain.size().getX()
                || relative.getY() >= terrain.size().getY()
                || relative.getZ() >= terrain.size().getZ()) {
                throw new IllegalStateException(
                    "Dungeon objective exceeds the terrain: " + objective.id()
                );
            }
            ResourceLocation blockId = ResourceLocation.parse(objective.block());
            var block = BuiltInRegistries.BLOCK.getOptional(blockId).orElseThrow(() ->
                new IllegalStateException("Dungeon objective block is missing: " + blockId)
            );
            BlockPos position = origin.offset(relative);
            if (block == Blocks.AIR
                || !level.setBlock(position, block.defaultBlockState(), 3)) {
                throw new IllegalStateException(
                    "Dungeon objective placement failed: " + objective.id()
                );
            }
            positions.put(objective.id(), position);
        }
        return Map.copyOf(positions);
    }

    private static BlockPos placeClearExit(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        DungeonDefinition.Completion completion = definition.completion();
        if (!completion.returnTrigger().equals("clear_exit")) {
            return null;
        }
        BlockPos relative = featurePosition(
            terrain, "objective", "clear_exit", completion.clearExitPosition()
        );
        BlockPos size = terrain.size();
        if (relative.getX() >= size.getX() || relative.getY() >= size.getY()
            || relative.getZ() >= size.getZ()) {
            throw new IllegalStateException(
                "Dungeon clear exit exceeds the template: " + definition.id()
            );
        }
        ResourceLocation blockId = ResourceLocation.parse(completion.clearExitBlock());
        var block = BuiltInRegistries.BLOCK.getOptional(blockId).orElseThrow(() ->
            new IllegalStateException("Dungeon clear exit block is missing: " + blockId)
        );
        BlockPos position = origin.offset(relative);
        if (block == Blocks.AIR
            || !level.setBlock(position, block.defaultBlockState(), 3)) {
            throw new IllegalStateException(
                "Dungeon clear exit placement failed: " + definition.id()
            );
        }
        return position;
    }

    private static Map<String, GateBounds> resolveGateBounds(
        DungeonDefinition definition,
        BlockPos origin,
        PreparedTerrain terrain
    ) {
        Map<String, GateBounds> resolved = new HashMap<>();
        for (DungeonDefinition.Gate gate : definition.gates()) {
            BlockPos anchor = gate.placement().equals("marker")
                ? featurePosition(terrain, "gate", gate.id(), null)
                : BlockPos.ZERO;
            BlockPos relativeMinimum = anchor.offset(gate.minimum());
            BlockPos relativeMaximum = anchor.offset(gate.maximum());
            if (relativeMinimum.getX() < 0 || relativeMinimum.getY() < 0
                || relativeMinimum.getZ() < 0
                || relativeMaximum.getX() >= terrain.size().getX()
                || relativeMaximum.getY() >= terrain.size().getY()
                || relativeMaximum.getZ() >= terrain.size().getZ()) {
                throw new IllegalStateException(
                    "Dungeon gate exceeds the terrain: " + gate.id()
                );
            }
            resolved.put(gate.id(), new GateBounds(
                origin.offset(relativeMinimum), origin.offset(relativeMaximum)
            ));
        }
        return Map.copyOf(resolved);
    }

    private static void placeGates(
        ServerLevel level,
        DungeonDefinition definition,
        Map<String, GateBounds> resolved
    ) {
        for (DungeonDefinition.Gate gate : definition.gates()) {
            GateBounds bounds = resolved.get(gate.id());
            if (bounds == null) throw new IllegalStateException(
                "Dungeon gate bounds are missing: " + gate.id()
            );
            ResourceLocation blockId = ResourceLocation.parse(gate.block());
            var block = BuiltInRegistries.BLOCK.getOptional(blockId).orElseThrow(() ->
                new IllegalStateException("Dungeon gate block is missing: " + blockId)
            );
            if (block == Blocks.AIR) {
                throw new IllegalStateException(
                    "Dungeon gate block cannot be air: " + gate.id()
                );
            }
            BlockPos.betweenClosedStream(
                bounds.minimum(), bounds.maximum()
            ).forEach(position -> level.setBlock(
                position, block.defaultBlockState(), 3
            ));
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
            || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)
            || (!claimDungeonLoot(player, event.getPos())
                && !useHealingStation(player, event.getPos())
                && !activateObjective(player, event.getPos()))) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static synchronized boolean activateObjective(
        ServerPlayer player, BlockPos position
    ) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || !player.serverLevel().dimension().equals(DUNGEONS)) {
            return false;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) return false;
        DungeonDefinition.Objective objective = definition.objectives().stream()
            .filter(value -> position.equals(run.objectivePositions().get(value.id())))
            .findFirst().orElse(null);
        if (objective == null) return false;
        deferObjectiveTracker(run, player, 60L);
        if (run.completedObjectives().contains(objective.id())) {
            player.displayClientMessage(Component.literal(
                "[던전] 이미 작동한 장치입니다."
            ), true);
            return true;
        }
        run.completedObjectives().add(objective.id());
        var objectiveState = player.serverLevel().getBlockState(position);
        if (objective.kind().equals("switch")
            && objectiveState.hasProperty(BlockStateProperties.POWERED)) {
            player.serverLevel().setBlock(
                position,
                objectiveState.setValue(BlockStateProperties.POWERED, true),
                3
            );
        }
        player.serverLevel().playSound(
            null, position,
            objective.kind().equals("switch")
                ? SoundEvents.LEVER_CLICK : SoundEvents.EXPERIENCE_ORB_PICKUP,
            SoundSource.BLOCKS, 0.8F, 1.0F
        );
        player.serverLevel().sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            position.getX() + 0.5D, position.getY() + 1.0D, position.getZ() + 0.5D,
            12, 0.3D, 0.4D, 0.3D, 0.02D
        );
        notifyEncounterResult(run, objective.kind().equals("switch")
            ? "[던전] 장치를 작동했습니다."
            : "[던전] 중요한 흔적을 조사했습니다.");
        unlockSatisfiedGates(run, definition);
        return true;
    }

    private static synchronized boolean claimDungeonLoot(
        ServerPlayer player, BlockPos position
    ) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || !player.serverLevel().dimension().equals(DUNGEONS)) {
            return false;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) {
            return false;
        }
        DungeonDefinition.LootContainer container = definition.loot().containers()
            .stream()
            .filter(candidate -> position.equals(run.lootPositions().get(candidate.id())))
            .findFirst().orElse(null);
        if (container == null) return false;
        deferObjectiveTracker(run, player, 60L);
        if (container.requiresCompletion() && !completionReached(player, run)) {
            player.displayClientMessage(Component.literal(
                "[던전] 클리어 조건을 달성해야 이 상자를 열 수 있습니다."
            ), true);
            return true;
        }
        if (definition.loot().ownership().equals("run_shared")) {
            return false;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.literal(
                "[던전] 전투 중에는 전리품 상자를 열 수 없습니다."
            ), true);
            return true;
        }
        boolean perPlayer = definition.loot().ownership().equals("per_player");
        if (run.lootClaims().claim(
            definition.loot().ownership(), container.id(), player.getUUID()
        ) == DungeonLootClaims.ClaimResult.ALREADY_CLAIMED) {
            player.displayClientMessage(Component.literal(perPlayer
                ? "[던전] 이 상자의 개인 전리품은 이미 수령했습니다."
                : "[던전] 이 상자의 전리품은 다른 참가자가 먼저 수령했습니다."
            ), true);
            return true;
        }

        List<ItemStack> rewards;
        try {
            String lootTableId = container.lootTable() == null
                ? definition.loot().lootTable() : container.lootTable();
            rewards = generateDungeonLoot(player, lootTableId, position);
        } catch (RuntimeException error) {
            run.lootClaims().release(container.id(), player.getUUID());
            LOGGER.error(
                "Dungeon loot claim failed: dungeon={}, container={}, player={}",
                definition.id(), container.id(), player.getUUID(), error
            );
            player.sendSystemMessage(Component.literal(
                "전리품을 지급하지 못했습니다. 잠시 후 다시 시도하세요."
            ));
            return true;
        }
        boolean deferred = definition.loot().onFailure().equals("grant_on_clear_only");
        if (!deferred) {
            if (!BagApi.insertAll(player, rewards).complete()) {
                run.lootClaims().release(container.id(), player.getUUID());
                player.sendSystemMessage(Component.literal(
                    "가방 공간이 부족합니다. 공간을 비운 뒤 상자를 다시 여세요."
                ));
                return true;
            }
        }
        run.lootLedger().record(
            definition.loot().onFailure(), player.getUUID(), rewards
        );

        int itemCount = rewards.stream().mapToInt(ItemStack::getCount).sum();
        player.sendSystemMessage(Component.literal(itemCount == 0
            ? "[던전] 상자가 비어 있었습니다."
            : deferred
                ? "[던전] 개인 전리품 " + itemCount
                    + "개를 확보했습니다. 클리어하면 지급됩니다."
                : "[던전] 개인 전리품 " + itemCount + "개를 획득했습니다."
        ));
        player.serverLevel().playSound(
            null, position,
            container.block().equals("barrel")
                ? SoundEvents.BARREL_OPEN : SoundEvents.CHEST_OPEN,
            SoundSource.BLOCKS, 0.7F, 1.0F
        );
        player.serverLevel().sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            position.getX() + 0.5D, position.getY() + 1.0D, position.getZ() + 0.5D,
            8, 0.25D, 0.25D, 0.25D, 0.01D
        );
        return true;
    }

    private static List<ItemStack> generateDungeonLoot(
        ServerPlayer player, String lootTableId, BlockPos position
    ) {
        ResourceKey<LootTable> key = ResourceKey.create(
            Registries.LOOT_TABLE, ResourceLocation.parse(lootTableId)
        );
        LootTable lootTable = player.getServer().reloadableRegistries().getLootTable(key);
        if (lootTable == LootTable.EMPTY) {
            throw new IllegalStateException(
                "Dungeon loot table is missing: " + lootTableId
            );
        }
        LootParams params = new LootParams.Builder(player.serverLevel())
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(position))
            .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
            .withLuck(player.getLuck())
            .create(LootContextParamSets.CHEST);
        return lootTable.getRandomItems(params);
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()
            || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)
            || !startEncounter(player, event.getTarget())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static synchronized boolean startEncounter(
        ServerPlayer initiator, Entity opponent
    ) {
        ActiveRun run = ACTIVE_RUNS.get(initiator.getUUID());
        if (run == null || !initiator.serverLevel().dimension().equals(DUNGEONS)) {
            return false;
        }
        EncounterEntityRef entityRef = encounterEntityRef(run, opponent);
        if (entityRef == null) return false;
        deferObjectiveTracker(run, initiator, 60L);
        String encounterId = entityRef.encounterId();
        DungeonDefinition definition = definitions.get(run.dungeonId());
        DungeonDefinition.Encounter encounter = definition == null ? null
            : definition.encounters().stream()
                .filter(candidate -> candidate.id().equals(encounterId))
                .findFirst().orElse(null);
        if (encounter == null) {
            initiator.sendSystemMessage(Component.literal(
                "던전 조우 설정을 찾을 수 없습니다."
            ));
            return true;
        }
        if (!definition.multiplayer().mode().equals("cooperative")
            && encounter.generatedTrainer() == null) {
            // CVES V5 owns dialogue and battle launch for solo/independent trainers.
            // Wild Pokemon use Cobblemon's normal battle interaction.
            return false;
        }
        EncounterStatus status = run.encounters().statusById.get(encounterId);
        if (status == EncounterStatus.DEFEATED) {
            initiator.displayClientMessage(Component.literal(
                "[던전] 이미 승리한 상대입니다."
            ), true);
            return true;
        }
        List<String> missingRequirements = encounter.requires().stream()
            .filter(required -> run.encounters().statusById.get(required)
                != EncounterStatus.DEFEATED)
            .toList();
        if (!missingRequirements.isEmpty()) {
            initiator.displayClientMessage(Component.literal(
                "[던전] 먼저 완료해야 할 조우: "
                    + String.join(", ", missingRequirements)
            ), true);
            return true;
        }
        if (status != EncounterStatus.AVAILABLE
            || run.encounters().pendingEncounterId != null
            || run.encounters().statusById.containsValue(EncounterStatus.ACTIVE)) {
            initiator.displayClientMessage(Component.literal(
                "[던전] 다른 조우가 시작 중이거나 진행 중입니다."
            ), true);
            return true;
        }
        if (encounter.generatedTrainer() != null) {
            return startGeneratedEncounter(
                run, definition, encounter, entityRef, initiator, opponent
            );
        }
        if (!definition.multiplayer().mode().equals("cooperative")
            || !definition.multiplayer().battleJoin().equals("summon_all")
            || run.participantIds().size() != 2) {
            initiator.sendSystemMessage(Component.literal(
                "이 조우는 2인 협력 던전에서만 시작할 수 있습니다."
            ));
            return true;
        }
        List<ServerPlayer> players = encounterPlayers(run, initiator);
        if (players.size() != 2) {
            initiator.sendSystemMessage(Component.literal(
                "동료가 던전에 없어 전투를 시작할 수 없습니다."
            ));
            return true;
        }
        if (players.stream().anyMatch(player ->
            BattleRegistry.getBattleByParticipatingPlayer(player) != null)) {
            initiator.displayClientMessage(Component.literal(
                "[던전] 참가자 중 전투 중인 사람이 있습니다."
            ), true);
            return true;
        }
        List<String> trainerIds;
        try {
            trainerIds = encounter.opponents().stream().map(battleId ->
                EventBattlePresetRepository.instance().find(battleId)
                    .orElseThrow(() -> new IllegalStateException(
                        "Dungeon battle preset is missing: " + battleId
                    ))
            ).map(EventBattlePreset::rctTrainerId).toList();
            if (entityRef.opponentIndex() == 1) {
                trainerIds = List.of(trainerIds.get(1), trainerIds.get(0));
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "Dungeon encounter battle preset resolution failed: {} -> {}",
                definition.id(), encounter.id(), error
            );
            initiator.sendSystemMessage(Component.literal(
                "조우 전투 설정을 불러오지 못했습니다. 서버 로그를 확인하세요."
            ));
            return true;
        }

        EncounterRuntime runtime = run.encounters();
        runtime.statusById.put(encounterId, EncounterStatus.STARTING);
        runtime.pendingEncounterId = encounterId;
        runtime.pendingExpiresAt = initiator.serverLevel().getGameTime() + 200L;
        runtime.pendingPlayers = Set.copyOf(
            players.stream().map(ServerPlayer::getUUID).toList()
        );
        gatherEncounterPlayers(players, opponent.position(), run);
        String command = DungeonCooperativeBattleCommand.build(
            players.get(0).getGameProfile().getName(),
            players.get(1).getGameProfile().getName(),
            trainerIds,
            definition.battleRules().allowItems()
        );
        try {
            int result = initiator.getServer().getCommands().getDispatcher().execute(
                command,
                opponent.createCommandSourceStack().withPermission(4).withSuppressedOutput()
            );
            if (result <= 0) {
                throw new IllegalStateException("TBCS rejected the battle command");
            }
        } catch (CommandSyntaxException | RuntimeException error) {
            runtime.statusById.put(encounterId, EncounterStatus.AVAILABLE);
            runtime.pendingEncounterId = null;
            runtime.pendingPlayers = Set.of();
            LOGGER.error(
                "Dungeon cooperative battle launch failed: {} -> {}",
                definition.id(), encounter.id(), error
            );
            players.forEach(player -> player.sendSystemMessage(Component.literal(
                "협력 전투를 시작하지 못했습니다. 서버 로그를 확인하세요."
            )));
            return true;
        }
        players.forEach(player -> player.sendSystemMessage(Component.literal(
            "[던전] " + encounter.id() + " 협력 전투를 시작합니다."
        )));
        return true;
    }

    private static boolean startGeneratedEncounter(
        ActiveRun run,
        DungeonDefinition definition,
        DungeonDefinition.Encounter encounter,
        EncounterEntityRef interactedRef,
        ServerPlayer initiator,
        Entity interactedEntity
    ) {
        List<ServerPlayer> players = definition.multiplayer().mode().equals("cooperative")
            ? encounterPlayers(run, initiator) : List.of(initiator);
        if (definition.multiplayer().mode().equals("cooperative")
            && players.size() != 2) {
            initiator.sendSystemMessage(Component.literal(
                "동료가 던전에 없어 전투를 시작할 수 없습니다."
            ));
            return true;
        }
        if (players.stream().anyMatch(player ->
            BattleRegistry.getBattleByParticipatingPlayer(player) != null)) {
            initiator.displayClientMessage(Component.literal(
                "[던전] 참가자 중 전투 중인 사람이 있습니다."
            ), true);
            return true;
        }

        EncounterRuntime runtime = run.encounters();
        List<String> trainerIds = new ArrayList<>();
        DungeonGeneratedTrainer.Result firstGenerated = null;
        try {
            for (int index = 0; index < encounter.npcs().size(); index++) {
                Entity entity = index == interactedRef.opponentIndex()
                    ? interactedEntity : generatedEncounterEntity(run, encounter, index);
                if (!(entity instanceof LivingEntity living)) {
                    throw new IllegalStateException(
                        "Generated dungeon trainer entity is missing: "
                            + encounter.id() + "[" + index + "]"
                    );
                }
                DungeonGeneratedTrainer.Result generated = DungeonGeneratedTrainer.generate(
                    encounter.generatedTrainer(), definition.difficulty(),
                    run.seed() ^ ((long) encounter.id().hashCode() << 32) ^ index
                );
                if (firstGenerated == null) firstGenerated = generated;
                trainerIds.add(DungeonGeneratedTrainerRuntime.register(
                    run.runId(), encounter.id(), index, encounter.displayName(),
                    generated, living
                ));
            }
        } catch (RuntimeException error) {
            trainerIds.forEach(DungeonGeneratedTrainerRuntime::unregister);
            LOGGER.error(
                "Generated dungeon trainer registration failed: {} -> {}",
                definition.id(), encounter.id(), error
            );
            initiator.sendSystemMessage(Component.literal(
                "즉석 트레이너를 준비하지 못했습니다. 서버 로그를 확인하세요."
            ));
            return true;
        }

        runtime.generatedTrainerIds.put(encounter.id(), List.copyOf(trainerIds));
        runtime.generatedEndLines.put(encounter.id(), firstGenerated.battleEndLine());
        runtime.statusById.put(encounter.id(), EncounterStatus.STARTING);
        runtime.pendingEncounterId = encounter.id();
        runtime.pendingExpiresAt = initiator.serverLevel().getGameTime() + 200L;
        runtime.pendingPlayers = Set.copyOf(
            players.stream().map(ServerPlayer::getUUID).toList()
        );
        notifyEncounterResult(run, encounter.displayName() + ": "
            + firstGenerated.battleStartLine());
        if (players.size() == 2) gatherEncounterPlayers(
            players, interactedEntity.position(), run
        );

        String command = players.size() == 2
            ? DungeonCooperativeBattleCommand.build(
                players.get(0).getGameProfile().getName(),
                players.get(1).getGameProfile().getName(), trainerIds,
                definition.battleRules().allowItems()
            )
            : "tbcs battle GEN_9_SINGLES "
                + initiator.getGameProfile().getName() + " vs @s as "
                + trainerIds.getFirst()
                + (definition.battleRules().allowItems()
                    ? "" : " rules {maxItemUses:0}");
        try {
            int result = initiator.getServer().getCommands().getDispatcher().execute(
                command,
                interactedEntity.createCommandSourceStack()
                    .withPermission(4).withSuppressedOutput()
            );
            if (result <= 0) throw new IllegalStateException(
                "TBCS rejected the generated trainer battle"
            );
        } catch (CommandSyntaxException | RuntimeException error) {
            cleanupGeneratedEncounter(runtime, encounter.id());
            runtime.statusById.put(encounter.id(), EncounterStatus.AVAILABLE);
            runtime.pendingEncounterId = null;
            runtime.pendingPlayers = Set.of();
            LOGGER.error(
                "Generated dungeon trainer battle launch failed: {} -> {}",
                definition.id(), encounter.id(), error
            );
            players.forEach(player -> player.sendSystemMessage(Component.literal(
                "즉석 트레이너 전투를 시작하지 못했습니다. 서버 로그를 확인하세요."
            )));
        }
        return true;
    }

    private static Entity generatedEncounterEntity(
        ActiveRun run, DungeonDefinition.Encounter encounter, int opponentIndex
    ) {
        ServerLevel level = run.server().getLevel(DUNGEONS);
        if (level == null) return null;
        EncounterEntityRef expected = new EncounterEntityRef(encounter.id(), opponentIndex);
        for (Map.Entry<UUID, EncounterEntityRef> entry
            : run.encounters().encounterByEntity.entrySet()) {
            if (!entry.getValue().equals(expected)) continue;
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) return entity;
        }
        BlockPos anchor = run.encounters().positionsById.get(encounter.id());
        if (anchor == null) return null;
        BlockPos expectedPosition = encounterNpcPosition(
            level, anchor, encounter.yaw(), opponentIndex
        );
        Entity entity = level.getEntitiesOfClass(
            Entity.class, new AABB(expectedPosition).inflate(6.0D, 10.0D, 6.0D),
            DungeonSystem::isEasyNpc
        ).stream().min(java.util.Comparator.comparingDouble(
            candidate -> candidate.distanceToSqr(Vec3.atCenterOf(expectedPosition))
        )).orElse(null);
        if (entity != null) {
            run.encounters().encounterByEntity.put(entity.getUUID(), expected);
        }
        return entity;
    }

    private static void cleanupGeneratedEncounter(
        EncounterRuntime runtime, String encounterId
    ) {
        List<String> trainerIds = runtime.generatedTrainerIds.remove(encounterId);
        if (trainerIds != null) {
            trainerIds.forEach(DungeonGeneratedTrainerRuntime::unregister);
        }
        runtime.generatedEndLines.remove(encounterId);
    }

    private static EncounterEntityRef encounterEntityRef(
        ActiveRun run, Entity entity
    ) {
        EncounterRuntime runtime = run.encounters();
        EncounterEntityRef mapped = runtime.encounterByEntity.get(entity.getUUID());
        if (mapped != null || !isEasyNpc(entity)) return mapped;
        if (!(entity.level() instanceof ServerLevel level)) return null;
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) return null;

        record Candidate(EncounterEntityRef ref, double distanceSquared) {}
        Set<EncounterEntityRef> assigned = new HashSet<>(
            runtime.encounterByEntity.values()
        );
        Candidate nearest = null;
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            BlockPos authored = runtime.positionsById.get(encounter.id());
            if (authored == null) continue;
            for (int index = 0; index < encounter.npcs().size(); index++) {
                EncounterEntityRef ref = new EncounterEntityRef(encounter.id(), index);
                if (assigned.contains(ref)) continue;
                BlockPos expected = encounterNpcPosition(
                    level, authored, encounter.yaw(), index
                );
                double distance = entity.distanceToSqr(Vec3.atCenterOf(expected));
                if (distance <= 144.0D
                    && (nearest == null || distance < nearest.distanceSquared())) {
                    nearest = new Candidate(ref, distance);
                }
            }
        }
        if (nearest == null) return null;
        runtime.encounterByEntity.put(entity.getUUID(), nearest.ref());
        LOGGER.debug(
            "Late dungeon NPC registration resolved: {} -> {}[{}]",
            entity.getUUID(), nearest.ref().encounterId(), nearest.ref().opponentIndex()
        );
        return nearest.ref();
    }

    private static List<ServerPlayer> encounterPlayers(
        ActiveRun run, ServerPlayer initiator
    ) {
        List<ServerPlayer> players = new ArrayList<>();
        players.add(initiator);
        run.participantIds().stream()
            .filter(id -> !id.equals(initiator.getUUID()))
            .map(id -> run.server().getPlayerList().getPlayer(id))
            .filter(player -> player != null
                && player.serverLevel() == initiator.serverLevel())
            .forEach(players::add);
        return players;
    }

    private static void gatherEncounterPlayers(
        List<ServerPlayer> players, Vec3 anchor, ActiveRun run
    ) {
        double[][] preferred = {{2.0D, 0.0D}, {-2.0D, 0.0D}};
        for (int index = 0; index < players.size(); index++) {
            ServerPlayer player = players.get(index);
            Vec3 target = safeEncounterPosition(player, anchor, preferred[index], run);
            if (target == null) continue;
            INTERNAL_TELEPORTS.add(player.getUUID());
            try {
                player.teleportTo(
                    player.serverLevel(), target.x, target.y, target.z,
                    player.getYRot(), player.getXRot()
                );
                player.fallDistance = 0.0F;
            } finally {
                INTERNAL_TELEPORTS.remove(player.getUUID());
            }
        }
    }

    private static Vec3 safeEncounterPosition(
        ServerPlayer player, Vec3 anchor, double[] preferred, ActiveRun run
    ) {
        double[][] offsets = {
            preferred, {0.0D, 2.0D}, {0.0D, -2.0D},
            {-preferred[0], -preferred[1]}
        };
        for (double[] offset : offsets) {
            Vec3 candidate = new Vec3(
                Math.floor(anchor.x) + 0.5D + offset[0],
                Math.floor(anchor.y),
                Math.floor(anchor.z) + 0.5D + offset[1]
            );
            if (!insideRunBounds(candidate, run.origin(), run.size())) continue;
            BlockPos floor = BlockPos.containing(candidate).below();
            if (!player.serverLevel().getBlockState(floor).isFaceSturdy(
                player.serverLevel(), floor, Direction.UP
            )) continue;
            AABB moved = player.getBoundingBox().move(
                candidate.x - player.getX(), candidate.y - player.getY(),
                candidate.z - player.getZ()
            );
            if (player.serverLevel().noCollision(player, moved)) return candidate;
        }
        return null;
    }

    private static synchronized void onBattleStarted(BattleStartedEvent.Post event) {
        Set<UUID> players = new HashSet<>();
        for (BattleActor actor : event.getBattle().getActors()) {
            if (actor instanceof PlayerBattleActor playerActor) {
                players.add(playerActor.getUuid());
            }
        }
        ActiveRun run = ACTIVE_RUNS.values().stream()
            .filter(candidate -> candidate.encounters().pendingEncounterId != null)
            .filter(candidate -> !candidate.encounters().pendingPlayers.isEmpty())
            .filter(candidate -> players.containsAll(
                candidate.encounters().pendingPlayers
            ))
            .findFirst().orElse(null);
        if (run == null) {
            if (!attachWildBattle(event, players)) {
                attachCvesIndividualBattle(event, players);
            }
            return;
        }
        EncounterRuntime runtime = run.encounters();
        String encounterId = runtime.pendingEncounterId;
        runtime.pendingEncounterId = null;
        runtime.pendingPlayers = Set.of();
        runtime.statusById.put(encounterId, EncounterStatus.ACTIVE);
        runtime.battleToEncounter.put(event.getBattle().getBattleId(), encounterId);
    }

    private static boolean attachWildBattle(
        BattleStartedEvent.Post event, Set<UUID> players
    ) {
        if (players.size() != 1) return false;
        UUID playerId = players.iterator().next();
        ActiveRun run = ACTIVE_RUNS.get(playerId);
        DungeonDefinition definition = run == null ? null
            : definitions.get(run.dungeonId());
        if (definition == null) return false;
        UUID pokemonEntityId = DungeonWildEncounterSupport.findEncounterPokemon(
            event.getBattle().getActors(), run.encounters().encounterByEntity.keySet()
        );
        if (pokemonEntityId == null) return false;
        EncounterEntityRef ref = run.encounters().encounterByEntity.get(pokemonEntityId);
        DungeonDefinition.Encounter encounter = definition.encounters().stream()
            .filter(candidate -> candidate.id().equals(ref.encounterId()))
            .findFirst().orElse(null);
        if (encounter == null || !encounter.kind().equals("wild_pokemon")
            || run.encounters().statusById.get(encounter.id())
                != EncounterStatus.AVAILABLE) {
            return false;
        }
        run.encounters().statusById.put(encounter.id(), EncounterStatus.ACTIVE);
        run.encounters().battleToEncounter.put(
            event.getBattle().getBattleId(), encounter.id()
        );
        LOGGER.debug(
            "Wild Pokemon battle attached to dungeon encounter: {} -> {}",
            encounter.pokemon().species(), encounter.id()
        );
        return true;
    }

    private static void attachCvesIndividualBattle(
        BattleStartedEvent.Post event, Set<UUID> players
    ) {
        if (players.size() != 1) return;
        UUID playerId = players.iterator().next();
        ActiveRun run = ACTIVE_RUNS.get(playerId);
        DungeonDefinition definition = run == null ? null
            : definitions.get(run.dungeonId());
        if (definition == null
            || definition.multiplayer().mode().equals("cooperative")) {
            return;
        }
        EventBattleBridge.BattleContext context = EventBattleBridge
            .pendingContext(playerId).orElse(null);
        ServerLevel level = run.server().getLevel(DUNGEONS);
        Entity opponent = level == null || context == null ? null
            : level.getEntity(context.npcId());
        EncounterEntityRef ref = opponent == null ? null
            : encounterEntityRef(run, opponent);
        DungeonDefinition.Encounter encounter = ref == null ? null
            : definition.encounters().stream()
                .filter(candidate -> candidate.id().equals(ref.encounterId()))
                .findFirst().orElse(null);
        if (encounter == null
            || !encounter.opponents().contains(context.battleId())
            || run.encounters().statusById.get(encounter.id())
                != EncounterStatus.AVAILABLE) {
            return;
        }
        run.encounters().statusById.put(encounter.id(), EncounterStatus.ACTIVE);
        run.encounters().battleToEncounter.put(
            event.getBattle().getBattleId(), encounter.id()
        );
        LOGGER.debug(
            "CVES V5 battle attached to dungeon encounter: {} -> {}",
            context.battleId(), encounter.id()
        );
    }

    private static synchronized void onBattleVictory(BattleVictoryEvent event) {
        ActiveRun run = runForBattle(event.getBattle().getBattleId());
        if (run == null) return;
        Set<UUID> winners = event.getWinners().stream()
            .filter(PlayerBattleActor.class::isInstance)
            .map(PlayerBattleActor.class::cast)
            .map(PlayerBattleActor::getUuid)
            .collect(Collectors.toSet());
        String encounterId = run.encounters().battleToEncounter.remove(
            event.getBattle().getBattleId()
        );
        DungeonDefinition activeDefinition = definitions.get(run.dungeonId());
        boolean won = activeDefinition != null && encounterWon(
            activeDefinition.multiplayer().mode(), winners, run.participantIds()
        );
        run.encounters().statusById.put(
            encounterId, won ? EncounterStatus.DEFEATED : EncounterStatus.AVAILABLE
        );
        DungeonDefinition definition = activeDefinition;
        DungeonDefinition.Encounter encounter = definition == null ? null
            : definition.encounters().stream()
                .filter(candidate -> candidate.id().equals(encounterId))
                .findFirst().orElse(null);
        String generatedEndLine = run.encounters().generatedEndLines.get(encounterId);
        cleanupGeneratedEncounter(run.encounters(), encounterId);
        if (won && definition != null) {
            unlockSatisfiedGates(run, definition);
        }
        if (won && encounter != null && encounter.boss()) {
            for (UUID participantId : run.participantIds()) {
                ServerPlayer player = run.server().getPlayerList().getPlayer(participantId);
                if (player != null) {
                    new ServerPlayerEventState(player).setFlag(
                        definition.completion().victoryFlag(), true
                    );
                }
            }
            activateClearExit(run, definition);
        }
        notifyEncounterResult(run, won
            ? "[던전] 전투에서 승리했습니다."
            : "[던전] 전투에서 패배했습니다.");
        if (generatedEndLine != null && encounter != null) {
            notifyEncounterResult(run, encounter.displayName() + ": " + generatedEndLine);
        }
    }

    private static void activateClearExit(
        ActiveRun run, DungeonDefinition definition
    ) {
        if (!definition.completion().returnTrigger().equals("clear_exit")
            || run.clearExit() == null) {
            return;
        }
        ServerLevel level = run.server().getLevel(DUNGEONS);
        if (level != null) {
            BlockPos position = run.clearExit();
            level.playSound(
                null, position, SoundEvents.BEACON_ACTIVATE,
                SoundSource.BLOCKS, 1.0F, 1.0F
            );
            level.sendParticles(
                ParticleTypes.END_ROD,
                position.getX() + 0.5D, position.getY() + 1.0D,
                position.getZ() + 0.5D,
                24, 0.6D, 0.8D, 0.6D, 0.03D
            );
        }
        notifyEncounterResult(
            run, "[던전] 보스가 쓰러졌습니다. 클리어 룸의 귀환 장치가 활성화되었습니다."
        );
    }

    private static void unlockSatisfiedGates(
        ActiveRun run, DungeonDefinition definition
    ) {
        ServerLevel level = run.server().getLevel(DUNGEONS);
        if (level == null) return;
        for (DungeonDefinition.Gate gate : definition.gates()) {
            if (run.openedGates().contains(gate.id())
                || !gateRequirementsSatisfied(run, gate)) {
                continue;
            }
            GateBounds bounds = run.gateBounds().get(gate.id());
            if (bounds == null) {
                throw new IllegalStateException(
                    "Active dungeon gate bounds are missing: " + gate.id()
                );
            }
            consumeGateItems(run, gate);
            run.openedGates().add(gate.id());
            BlockPos minimum = bounds.minimum();
            BlockPos maximum = bounds.maximum();
            BlockPos.betweenClosedStream(minimum, maximum).forEach(position ->
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 3)
            );
            BlockPos center = new BlockPos(
                (minimum.getX() + maximum.getX()) / 2,
                (minimum.getY() + maximum.getY()) / 2,
                (minimum.getZ() + maximum.getZ()) / 2
            );
            level.playSound(
                null, center, SoundEvents.IRON_DOOR_OPEN,
                SoundSource.BLOCKS, 1.0F, 0.8F
            );
            level.sendParticles(
                ParticleTypes.POOF,
                center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D,
                12, 1.0D, 1.0D, 0.2D, 0.02D
            );
            notifyEncounterResult(run, "[던전] 잠금 게이트가 해제되었습니다.");
        }
    }

    private static boolean gateRequirementsSatisfied(
        ActiveRun run, DungeonDefinition.Gate gate
    ) {
        for (DungeonDefinition.GateRequirement requirement : gate.requirements()) {
            switch (requirement.type()) {
                case "encounter" -> {
                    if (run.encounters().statusById.get(requirement.reference())
                        != EncounterStatus.DEFEATED) return false;
                }
                case "objective" -> {
                    if (!run.completedObjectives().contains(requirement.reference())) return false;
                }
                case "item" -> {
                    Item item = BuiltInRegistries.ITEM.getOptional(
                        ResourceLocation.parse(requirement.item())
                    ).orElse(null);
                    if (item == null
                        || participantItemCount(run, item) < requirement.count()) {
                        return false;
                    }
                }
                default -> throw new IllegalStateException(
                    "Unknown dungeon gate requirement type: " + requirement.type()
                );
            }
        }
        return true;
    }

    private static int participantItemCount(ActiveRun run, Item item) {
        int count = 0;
        for (UUID participantId : run.participantIds()) {
            ServerPlayer player = run.server().getPlayerList().getPlayer(participantId);
            if (player == null) continue;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item)) count += stack.getCount();
            }
        }
        return count;
    }

    private static void consumeGateItems(
        ActiveRun run, DungeonDefinition.Gate gate
    ) {
        for (DungeonDefinition.GateRequirement requirement : gate.requirements()) {
            if (!requirement.type().equals("item") || !requirement.consume()) continue;
            Item item = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.parse(requirement.item())
            ).orElseThrow();
            int remaining = requirement.count();
            for (UUID participantId : run.participantIds()) {
                ServerPlayer player = run.server().getPlayerList().getPlayer(participantId);
                if (player == null) continue;
                for (int slot = 0;
                     slot < player.getInventory().getContainerSize() && remaining > 0;
                     slot++) {
                    ItemStack stack = player.getInventory().getItem(slot);
                    if (!stack.is(item)) continue;
                    int removed = Math.min(remaining, stack.getCount());
                    stack.shrink(removed);
                    remaining -= removed;
                }
                if (remaining == 0) break;
            }
        }
    }

    private static synchronized void onBattleFled(BattleFledEvent event) {
        ActiveRun run = runForBattle(event.getBattle().getBattleId());
        if (run == null) return;
        String encounterId = run.encounters().battleToEncounter.remove(
            event.getBattle().getBattleId()
        );
        String generatedEndLine = run.encounters().generatedEndLines.get(encounterId);
        DungeonDefinition definition = definitions.get(run.dungeonId());
        DungeonDefinition.Encounter encounter = definition == null ? null
            : definition.encounters().stream()
                .filter(candidate -> candidate.id().equals(encounterId))
                .findFirst().orElse(null);
        cleanupGeneratedEncounter(run.encounters(), encounterId);
        run.encounters().statusById.put(encounterId, EncounterStatus.AVAILABLE);
        notifyEncounterResult(run, "[던전] 전투가 중단되었습니다.");
        if (generatedEndLine != null && encounter != null) {
            notifyEncounterResult(run, encounter.displayName() + ": " + generatedEndLine);
        }
    }

    private static ActiveRun runForBattle(UUID battleId) {
        return ACTIVE_RUNS.values().stream()
            .filter(run -> run.encounters().battleToEncounter.containsKey(battleId))
            .findFirst().orElse(null);
    }

    static boolean encounterWon(
        String multiplayerMode, Set<UUID> winners, Set<UUID> participants
    ) {
        if (multiplayerMode.equals("independent")) {
            return winners.stream().anyMatch(participants::contains);
        }
        return winners.containsAll(participants);
    }

    private static void notifyEncounterResult(ActiveRun run, String message) {
        for (UUID participantId : run.participantIds()) {
            ServerPlayer player = run.server().getPlayerList().getPlayer(participantId);
            if (player != null) player.sendSystemMessage(Component.literal(message));
        }
    }

    private static void showObjectiveTracker(
        ServerPlayer player, ActiveRun run, long gameTime
    ) {
        UUID playerId = player.getUUID();
        if (gameTime < run.objectiveMessageAfter().getOrDefault(playerId, 0L)
            || gameTime < run.tetherWarningUntil().getOrDefault(playerId, 0L)
            || BattleRegistry.getBattleByParticipatingPlayer(player) != null
            || run.encounters().pendingEncounterId != null
            || run.encounters().statusById.containsValue(EncounterStatus.ACTIVE)) {
            return;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) return;
        run.objectiveMessageAfter().put(
            playerId, gameTime + OBJECTIVE_TRACKER_INTERVAL_TICKS
        );
        if (completionReached(player, run)) {
            player.displayClientMessage(Component.literal(
                definition.completion().returnTrigger().equals("clear_exit")
                    ? "[던전] 목표 완료 | 클리어 룸의 귀환 장치로 이동하세요."
                    : "[던전] 목표 완료 | 귀환을 준비합니다."
            ), true);
            return;
        }
        long defeated = definition.encounters().stream()
            .filter(encounter -> run.encounters().statusById.get(encounter.id())
                == EncounterStatus.DEFEATED)
            .count();
        List<String> available = definition.encounters().stream()
            .filter(encounter -> run.encounters().statusById.get(encounter.id())
                == EncounterStatus.AVAILABLE)
            .filter(encounter -> encounter.requires().stream().allMatch(required ->
                run.encounters().statusById.get(required) == EncounterStatus.DEFEATED
            ))
            .map(DungeonDefinition.Encounter::displayName)
            .toList();
        String objective = available.isEmpty()
            ? "목표 상태를 갱신하는 중입니다."
            : String.join(" / ", available);
        player.displayClientMessage(Component.literal(
            "[던전] 필수 조우 " + defeated + "/" + definition.encounters().size()
                + " | 현재 목표: " + objective
        ), true);
    }

    private static void deferObjectiveTracker(
        ActiveRun run, ServerPlayer player, long delayTicks
    ) {
        long next = player.serverLevel().getGameTime() + delayTicks;
        run.objectiveMessageAfter().merge(player.getUUID(), next, Math::max);
    }

    private static boolean expirePendingEncounter(ActiveRun run, long gameTime) {
        EncounterRuntime runtime = run.encounters();
        if (runtime.pendingEncounterId == null
            || gameTime < runtime.pendingExpiresAt) return false;
        String encounterId = runtime.pendingEncounterId;
        runtime.statusById.put(encounterId, EncounterStatus.AVAILABLE);
        cleanupGeneratedEncounter(runtime, encounterId);
        runtime.pendingEncounterId = null;
        runtime.pendingPlayers = Set.of();
        notifyEncounterResult(run, "[던전] 협력 전투 시작 시간이 초과되었습니다.");
        return true;
    }

    private static synchronized boolean useHealingStation(
        ServerPlayer player, BlockPos position
    ) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || !player.serverLevel().dimension().equals(DUNGEONS)) {
            return false;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) return false;
        DungeonDefinition.HealingStation station = definition.support().healingStations()
            .stream()
            .filter(candidate -> position.equals(run.healingPositions().get(candidate.id())))
            .findFirst().orElse(null);
        if (station == null) return false;
        deferObjectiveTracker(run, player, 60L);
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.literal(
                "전투 중에는 던전 치료소를 사용할 수 없습니다."
            ), true);
            return true;
        }
        int used = run.healingUses().getOrDefault(station.id(), 0);
        if (used >= station.usesPerRun()) {
            player.displayClientMessage(Component.literal(
                "이 치료소는 이번 도전에서 더 이상 사용할 수 없습니다."
            ), true);
            return true;
        }
        var party = Cobblemon.INSTANCE.getStorage().getParty(player);
        if (!needsHealing(party, station)) {
            player.displayClientMessage(Component.literal(
                "현재 파티는 이 치료소에서 회복할 필요가 없습니다."
            ), true);
            return true;
        }
        applyHealing(party, station);
        run.healingUses().put(station.id(), used + 1);
        int remaining = station.usesPerRun() - used - 1;
        player.sendSystemMessage(Component.literal(
            "던전 치료소에서 파티를 회복했습니다. 남은 사용 횟수: " + remaining
        ));
        ServerLevel level = player.serverLevel();
        level.playSound(
            null, position, SoundEvents.BEACON_ACTIVATE,
            SoundSource.BLOCKS, 0.8F, 1.25F
        );
        level.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            position.getX() + 0.5D, position.getY() + 1.0D, position.getZ() + 0.5D,
            16, 0.35D, 0.45D, 0.35D, 0.02D
        );
        return true;
    }

    private static boolean needsHealing(
        Iterable<com.cobblemon.mod.common.pokemon.Pokemon> party,
        DungeonDefinition.HealingStation station
    ) {
        for (var pokemon : party) {
            if (station.restoreHp() && !pokemon.isFullHealth()) return true;
            if (station.restoreStatus() && pokemon.getStatus() != null) return true;
            if (station.restorePp()) {
                for (var move : pokemon.getMoveSet()) {
                    if (move.getCurrentPp() < move.getMaxPp()) return true;
                }
            }
        }
        return false;
    }

    private static void applyHealing(
        com.cobblemon.mod.common.api.storage.party.PlayerPartyStore party,
        DungeonDefinition.HealingStation station
    ) {
        if (station.restoreHp() && station.restoreStatus() && station.restorePp()) {
            party.heal();
            return;
        }
        for (var pokemon : party) {
            if (station.restoreHp()) pokemon.setCurrentHealth(pokemon.getMaxHealth());
            if (station.restoreStatus()) pokemon.setStatus(null);
            if (station.restorePp()) {
                for (var move : pokemon.getMoveSet()) {
                    move.setCurrentPp(move.getMaxPp());
                }
            }
        }
    }

    private static PursuitEncounterSystem.Config createRandomEncounterConfig(
        DungeonDefinition definition, BlockPos origin, BlockPos size, int slot
    ) {
        DungeonDefinition.RandomEncounters settings = definition.randomEncounters();
        if (!settings.enabled()) return null;
        BlockPos maximum = settings.maximumPosition();
        if (maximum.getX() >= size.getX() || maximum.getY() >= size.getY()
            || maximum.getZ() >= size.getZ()) {
            throw new IllegalStateException(
                "Dungeon random encounter bounds exceed the template: " + definition.id()
            );
        }
        return new PursuitEncounterSystem.Config(
            definition.id() + "#slot-" + slot,
            settings.minimumDistance(),
            settings.maximumDistance(),
            settings.maxActive(),
            settings.spawnIntervalTicks(),
            new PursuitEncounterSystem.SpawnBounds(
                origin.offset(settings.minimumPosition()),
                origin.offset(settings.maximumPosition())
            ),
            settings.additions().stream().map(species ->
                new PursuitEncounterSystem.SpeciesChoice(
                    species.species(),
                    species.minLevel(),
                    species.maxLevel(),
                    species.weight(),
                    species.spawnAsEvolved()
                )
            ).toList()
        );
    }

    private static int allocateSlot() {
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            if (ACTIVE_SLOTS.add(slot)) {
                return slot;
            }
        }
        return -1;
    }

    static BlockPos slotOrigin(int slot) {
        return new BlockPos(
            SLOT_START_X + Math.floorMod(slot, 8) * SLOT_SPACING,
            SLOT_Y,
            Math.floorDiv(slot, 8) * SLOT_SPACING
        );
    }

    private static void cancelQueuedEntry(ServerPlayer player, String message) {
        DungeonEntryQueue.Request request = ENTRY_QUEUE.remove(player.getUUID());
        QueuedEntry queued = QUEUED_ENTRIES.remove(player.getUUID());
        PENDING_ENTRIES.remove(player.getUUID());
        String entranceId = queued != null
            ? queued.pending().ref().entrance().entranceId()
            : request != null ? request.poolKey() : null;
        if (entranceId != null) {
            DungeonGuideNetwork.closeQueue(player, entranceId);
        }
        player.sendSystemMessage(Component.literal(message));
    }

    static synchronized void cancelWaiting(ServerPlayer player, String entranceId) {
        QueuedEntry queued = QUEUED_ENTRIES.get(player.getUUID());
        if (queued == null || !queued.pending().ref().entrance().entranceId()
            .equals(entranceId)) {
            return;
        }
        cancelQueuedEntry(player, "던전 매칭 대기를 취소했습니다.");
    }

    private static void failRun(ActiveRun run, String message, boolean heal) {
        if (run == null) return;
        DungeonDefinition definition = definitions.get(run.dungeonId());
        for (UUID participantId : run.participantIds()) {
            ServerPlayer participant = run.server().getPlayerList().getPlayer(participantId);
            if (participant == null) {
                releaseRun(participantId);
                continue;
            }
            if (definition != null
                && definition.loot().onFailure().equals("remove_run_loot")) {
                removeRunLoot(participant, run.lootLedger().removable(participantId));
            }
            if (heal) {
                Cobblemon.INSTANCE.getStorage().getParty(participant).heal();
            }
            returnPlayer(participant, message);
        }
        cleanupRun(run.server(), run);
    }

    private static void removeRunLoot(
        ServerPlayer player, List<ItemStack> recorded
    ) {
        int removed = 0;
        for (ItemStack stack : recorded) {
            int amount = Math.min(stack.getCount(), BagApi.count(player, stack));
            if (amount > 0 && BagApi.remove(player, stack, amount)) {
                removed += amount;
            }
        }
        if (removed > 0) {
            player.sendSystemMessage(Component.literal(
                "[던전] 도전 실패로 이번 실행의 전리품 " + removed + "개를 회수했습니다."
            ));
        }
    }

    private static void returnPlayer(ServerPlayer player, String message) {
        ReturnFrame frame = popReturnFrame(player);
        ActiveRun run = releaseRun(player.getUUID());
        if (frame == null) {
            ServerLevel fallback = player.getServer().getLevel(CobbleventureBootstrap.GENERATION_ONE);
            if (fallback != null) {
                BlockPos spawn = fallback.getSharedSpawnPos();
                player.teleportTo(
                    fallback, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                    player.getYRot(), player.getXRot()
                );
            }
            cleanupRun(player.getServer(), run);
            return;
        }
        ResourceLocation dimensionId = ResourceLocation.tryParse(frame.dimension());
        ServerLevel destination = dimensionId == null ? null : player.getServer().getLevel(
            ResourceKey.create(Registries.DIMENSION, dimensionId)
        );
        if (destination == null) {
            destination = player.getServer().getLevel(CobbleventureBootstrap.GENERATION_ONE);
        }
        if (destination == null) {
            cleanupRun(player.getServer(), run);
            return;
        }
        player.teleportTo(
            destination, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch()
        );
        cleanupRun(player.getServer(), run);
        player.sendSystemMessage(Component.literal(message));
    }

    private static boolean completionReached(ServerPlayer player, ActiveRun run) {
        DungeonDefinition definition = definitions.get(run.dungeonId());
        return definition != null && new ServerPlayerEventState(player)
            .flag(definition.completion().victoryFlag());
    }

    private static void completeRun(ServerPlayer player, ActiveRun run) {
        if (run.participantIds().stream().anyMatch(COMPLETING_RUNS::contains)) {
            return;
        }
        COMPLETING_RUNS.addAll(run.participantIds());
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) {
            failRun(run, "던전을 클리어했습니다.", false);
            return;
        }
        try {
            List<PendingReward> rewards = new ArrayList<>();
            for (UUID participantId : run.participantIds()) {
                ServerPlayer participant = player.getServer().getPlayerList().getPlayer(
                    participantId
                );
                if (participant == null) {
                    throw new IllegalStateException(
                        "Dungeon participant disconnected during completion: "
                            + participantId
                    );
                }
                int previousClears = DungeonClearProgress.clearCount(
                    participant.getPersistentData(), definition.id()
                );
                boolean firstClear = previousClears == 0;
                String rewardTable = firstClear
                    ? definition.rewards().firstClearTable()
                    : definition.rewards().repeatTable();
                List<ItemStack> items = new ArrayList<>(
                    generateClearRewards(participant, rewardTable)
                );
                items.addAll(run.lootLedger().pending(participantId));
                rewards.add(new PendingReward(participant, firstClear, items));
            }
            for (PendingReward reward : rewards) {
                ServerPlayer participant = reward.player();
                if (reward.firstClear()) {
                    ServerPlayerEventState state = new ServerPlayerEventState(participant);
                    for (String move : definition.rewards().firstClearFieldMoves()) {
                        state.grantFieldMove(move);
                    }
                }
                int clearCount = DungeonClearProgress.recordClear(
                    participant.getPersistentData(), definition.id()
                );
                returnPlayer(
                    participant,
                    definition.displayName() + " 클리어! 보상을 획득했습니다. ("
                        + clearCount + "회차)"
                );
                grantItems(participant, reward.items());
            }
        } catch (RuntimeException error) {
            COMPLETING_RUNS.removeAll(run.participantIds());
            throw error;
        }
    }

    private static List<ItemStack> generateClearRewards(
        ServerPlayer player, String lootTableId
    ) {
        if (lootTableId == null) {
            return List.of();
        }
        ResourceKey<LootTable> key = ResourceKey.create(
            Registries.LOOT_TABLE, ResourceLocation.parse(lootTableId)
        );
        LootTable lootTable = player.getServer().reloadableRegistries().getLootTable(key);
        if (lootTable == LootTable.EMPTY) {
            throw new IllegalStateException(
                "Dungeon clear reward loot table is missing: " + lootTableId
            );
        }
        LootParams params = new LootParams.Builder(player.serverLevel())
            .withParameter(LootContextParams.ORIGIN, player.position())
            .withParameter(LootContextParams.THIS_ENTITY, player)
            .create(LootContextParamSets.GIFT);
        return lootTable.getRandomItems(params);
    }

    private static void grantItems(ServerPlayer player, List<ItemStack> rewards) {
        if (BagApi.insertAll(player, rewards).complete()) {
            return;
        }
        for (ItemStack reward : rewards) {
            if (reward.isEmpty()) continue;
            ItemStack remainder = reward.copy();
            player.getInventory().add(remainder);
            if (!remainder.isEmpty()) {
                player.drop(remainder, false, true);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    private static void abandonRun(ServerPlayer player) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        failRun(run, "참가자가 이탈해 던전 도전이 종료되었습니다.", false);
    }

    private static void cleanupRun(MinecraftServer server, ActiveRun run) {
        if (run == null) return;
        if (ACTIVE_RUNS.values().stream().anyMatch(active -> active == run)) return;
        run.encounters().generatedTrainerIds.values().stream()
            .flatMap(List::stream)
            .forEach(DungeonGeneratedTrainerRuntime::unregister);
        run.encounters().generatedTrainerIds.clear();
        run.encounters().generatedEndLines.clear();
        ServerLevel level = server.getLevel(DUNGEONS);
        if (level != null) {
            clearSlot(level, run.origin(), run.size());
        }
        persistActiveRuns(server);
    }

    private static void clearSlot(ServerLevel level, BlockPos origin, BlockPos size) {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        AABB bounds = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX(), origin.getY() + size.getY(),
            origin.getZ() + size.getZ()
        );
        for (Entity entity : level.getEntitiesOfClass(Entity.class, bounds)) {
            if (!(entity instanceof ServerPlayer)) entity.discard();
        }
        BlockPos.betweenClosedStream(
            origin,
            origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1)
        ).forEach(position -> level.setBlock(
            position, Blocks.AIR.defaultBlockState(), 18
        ));
    }

    private static void pushReturnFrame(ServerPlayer player, BlockPos safeReturn) {
        ListTag stack = player.getPersistentData().getList(RETURN_STACK, Tag.TAG_COMPOUND);
        CompoundTag frame = new CompoundTag();
        frame.putString("dimension", player.serverLevel().dimension().location().toString());
        frame.putDouble("x", safeReturn.getX() + 0.5D);
        frame.putDouble("y", safeReturn.getY());
        frame.putDouble("z", safeReturn.getZ() + 0.5D);
        frame.putFloat("yaw", player.getYRot());
        frame.putFloat("pitch", player.getXRot());
        stack.add(frame);
        player.getPersistentData().put(RETURN_STACK, stack);
    }

    private static ReturnFrame popReturnFrame(ServerPlayer player) {
        ListTag stack = player.getPersistentData().getList(RETURN_STACK, Tag.TAG_COMPOUND);
        if (stack.isEmpty()) {
            return null;
        }
        CompoundTag frame = stack.getCompound(stack.size() - 1);
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) {
            player.getPersistentData().remove(RETURN_STACK);
        } else {
            player.getPersistentData().put(RETURN_STACK, stack);
        }
        return new ReturnFrame(
            frame.getString("dimension"),
            frame.getDouble("x"), frame.getDouble("y"), frame.getDouble("z"),
            frame.getFloat("yaw"), frame.getFloat("pitch")
        );
    }

    private static boolean hasReturnFrame(ServerPlayer player) {
        return !player.getPersistentData().getList(
            RETURN_STACK, Tag.TAG_COMPOUND
        ).isEmpty();
    }

    private static ActiveRun releaseRun(UUID playerId) {
        COMPLETING_RUNS.remove(playerId);
        ActiveRun removed = ACTIVE_RUNS.remove(playerId);
        if (removed != null && ACTIVE_RUNS.values().stream().noneMatch(
            active -> active == removed
        )) {
            ACTIVE_SLOTS.remove(removed.slot());
        }
        return removed;
    }

    private static synchronized void onPlayerLoggedIn(
        PlayerEvent.PlayerLoggedInEvent event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        ReconnectState reconnect = run == null ? null
            : run.reconnecting().remove(player.getUUID());
        if (run != null && reconnect != null) {
            long gameTime = player.getServer().overworld().getGameTime();
            if (gameTime >= reconnect.deadline()) {
                failRun(run, "재접속 유예 시간이 만료되어 던전 도전이 종료되었습니다.", false);
                return;
            }
            ServerLevel dungeonLevel = player.getServer().getLevel(DUNGEONS);
            if (dungeonLevel == null) {
                failRun(run, "던전 차원을 찾을 수 없어 도전이 종료되었습니다.", false);
                return;
            }
            Vec3 target = reconnectPosition(player, dungeonLevel, reconnect, run);
            INTERNAL_TELEPORTS.add(player.getUUID());
            try {
                player.teleportTo(
                    dungeonLevel, target.x, target.y, target.z,
                    reconnect.yaw(), reconnect.pitch()
                );
                player.fallDistance = 0.0F;
            } finally {
                INTERNAL_TELEPORTS.remove(player.getUUID());
            }
            notifyEncounterResult(run, "[던전] 참가자가 재접속해 도전을 계속합니다.");
            return;
        }
        if (!player.serverLevel().dimension().equals(DUNGEONS)
            || !hasReturnFrame(player)) return;
        returnPlayer(player, "중단된 던전에서 안전하게 복귀했습니다.");
    }

    private static synchronized void onPlayerLoggedOut(
        PlayerEvent.PlayerLoggedOutEvent event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run != null) {
            DungeonDefinition definition = definitions.get(run.dungeonId());
            String resumeMode = definition == null
                ? "full_reset" : definition.lifecycle().resumeMode();
            int graceSeconds = definition == null
                ? 0 : definition.lifecycle().reconnectGraceSeconds();
            if (resumeMode.equals("full_reset") || graceSeconds <= 0
                || BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
                failRun(run, "참가자의 연결이 끊겨 던전 도전이 종료되었습니다.", false);
            } else {
                long deadline = player.getServer().overworld().getGameTime()
                    + graceSeconds * 20L;
                Vec3 resumePosition = resumeMode.equals("checkpoint")
                    ? Vec3.atBottomCenterOf(run.activeCheckpoints().getOrDefault(
                        player.getUUID(), run.entry()
                    ))
                    : player.position();
                run.reconnecting().put(player.getUUID(), new ReconnectState(
                    resumePosition, player.getYRot(), player.getXRot(), deadline
                ));
                for (UUID participantId : run.participantIds()) {
                    if (participantId.equals(player.getUUID())) continue;
                    ServerPlayer partner = run.server().getPlayerList().getPlayer(
                        participantId
                    );
                    if (partner != null) {
                        partner.sendSystemMessage(Component.literal(
                            "[던전] 동료의 연결이 끊겼습니다. " + graceSeconds
                                + "초 동안 재접속을 기다립니다."
                        ));
                    }
                }
            }
        }
        INSIDE_ENTRANCES.remove(player.getUUID());
        PENDING_ENTRIES.remove(player.getUUID());
        ENTRY_QUEUE.remove(player.getUUID());
        QUEUED_ENTRIES.remove(player.getUUID());
    }

    private static synchronized void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        Set<ActiveRun> runs = Collections.newSetFromMap(new IdentityHashMap<>());
        runs.addAll(ACTIVE_RUNS.values());
        for (ActiveRun run : runs) {
            boolean expired = run.reconnecting().values().stream()
                .anyMatch(state -> gameTime >= state.deadline());
            if (expired) {
                failRun(
                    run,
                    "재접속 유예 시간이 만료되어 던전 도전이 종료되었습니다.",
                    false
                );
            }
            if (gameTime % 10L == 0L) {
                DungeonDefinition definition = definitions.get(run.dungeonId());
                if (definition != null) {
                    activateNearbyInvestigations(run, definition);
                    unlockSatisfiedGates(run, definition);
                }
            }
        }
        if (gameTime % 20L == 0L) {
            persistActiveRuns(event.getServer());
        }
    }

    private static void activateNearbyInvestigations(
        ActiveRun run, DungeonDefinition definition
    ) {
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            if (!objective.kind().equals("investigate")
                || run.completedObjectives().contains(objective.id())) continue;
            BlockPos position = run.objectivePositions().get(objective.id());
            if (position == null) continue;
            double radiusSquared = objective.activationRadius()
                * objective.activationRadius();
            for (UUID participantId : run.participantIds()) {
                ServerPlayer player = run.server().getPlayerList().getPlayer(participantId);
                if (player != null && player.serverLevel().dimension().equals(DUNGEONS)
                    && player.distanceToSqr(Vec3.atCenterOf(position))
                        <= radiusSquared) {
                    activateObjective(player, position);
                    break;
                }
            }
        }
    }

    private static void persistActiveRuns(MinecraftServer server) {
        Set<ActiveRun> runs = Collections.newSetFromMap(new IdentityHashMap<>());
        runs.addAll(ACTIVE_RUNS.values());
        DungeonRunSavedData.data(server).replace(
            runs.stream().map(DungeonSystem::snapshotRun).toList()
        );
    }

    private static CompoundTag snapshotRun(ActiveRun run) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("runId", run.runId());
        tag.putLong("seed", run.seed());
        tag.putString("dungeonId", run.dungeonId());
        tag.putInt("slot", run.slot());
        putPosition(tag, "origin", run.origin());
        putPosition(tag, "size", run.size());
        putPosition(tag, "entry", run.entry());
        putPosition(tag, "exit", run.exit());
        if (run.clearExit() != null) putPosition(tag, "clearExit", run.clearExit());
        putUuidSet(tag, "participants", run.participantIds());
        putBlockPositionMap(tag, "lootPositions", run.lootPositions());
        putBlockPositionMap(tag, "healingPositions", run.healingPositions());
        putBlockPositionMap(tag, "objectivePositions", run.objectivePositions());
        putStringSet(tag, "completedObjectives", run.completedObjectives());
        putCheckpointPositions(tag, run.checkpointPositions());
        putGateBounds(tag, run.gateBounds());
        putUuidPositionMap(tag, "activeCheckpoints", run.activeCheckpoints());
        putStringIntMap(tag, "healingUses", run.healingUses());
        putStringSet(tag, "openedGates", run.openedGates());
        Set<String> defeated = run.encounters().statusById.entrySet().stream()
            .filter(entry -> entry.getValue() == EncounterStatus.DEFEATED)
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        putStringSet(tag, "defeatedEncounters", defeated);
        putEncounterEntities(tag, run.encounters().encounterByEntity);
        putBlockPositionMap(tag, "encounterPositions", run.encounters().positionsById);
        putLootClaims(tag, run.lootClaims().snapshot());
        putItemMap(
            tag, "pendingLoot", run.lootLedger().pendingSnapshot(), run.server()
        );
        putItemMap(
            tag, "removableLoot", run.lootLedger().removableSnapshot(), run.server()
        );
        putResumePositions(tag, run);
        return tag;
    }

    private static void restorePersistedRuns(MinecraftServer server) {
        ServerLevel level = server.getLevel(DUNGEONS);
        if (level == null) return;
        long now = server.overworld().getGameTime();
        int restored = 0;
        for (CompoundTag tag : DungeonRunSavedData.data(server).snapshots()) {
            int reservedSlot = -1;
            try {
                String dungeonId = tag.getString("dungeonId");
                DungeonDefinition definition = definitions.get(dungeonId);
                if (definition == null) {
                    clearPersistedSlot(level, tag);
                    continue;
                }
                if (definition.lifecycle().resumeMode().equals("full_reset")) {
                    clearPersistedSlot(level, tag);
                    continue;
                }
                int slot = tag.getInt("slot");
                BlockPos origin = getPosition(tag, "origin");
                BlockPos size = getPosition(tag, "size");
                if (slot < 0 || slot >= MAX_SLOTS || !origin.equals(slotOrigin(slot))
                    || !ACTIVE_SLOTS.add(slot)) {
                    throw new IllegalStateException("Invalid or duplicate persisted slot");
                }
                reservedSlot = slot;
                Set<UUID> participants = getUuidSet(tag, "participants");
                if (participants.isEmpty()) {
                    ACTIVE_SLOTS.remove(slot);
                    reservedSlot = -1;
                    continue;
                }
                EncounterRuntime encounterRuntime = new EncounterRuntime(
                    getEncounterEntities(tag), definition.encounters(),
                    getBlockPositionMap(tag, "encounterPositions")
                );
                getStringSet(tag, "defeatedEncounters").forEach(id -> {
                    if (encounterRuntime.statusById.containsKey(id)) {
                        encounterRuntime.statusById.put(id, EncounterStatus.DEFEATED);
                    }
                });
                Map<UUID, BlockPos> activeCheckpoints = getUuidPositionMap(
                    tag, "activeCheckpoints"
                );
                Map<UUID, ResumePosition> resumePositions = getResumePositions(tag);
                Map<UUID, ReconnectState> reconnecting = new HashMap<>();
                long deadline = now + definition.lifecycle().reconnectGraceSeconds() * 20L;
                for (UUID participant : participants) {
                    ResumePosition resume = resumePositions.get(participant);
                    BlockPos checkpoint = activeCheckpoints.get(participant);
                    Vec3 position = definition.lifecycle().resumeMode().equals("checkpoint")
                        ? Vec3.atBottomCenterOf(checkpoint == null
                            ? getPosition(tag, "entry") : checkpoint)
                        : resume == null
                            ? Vec3.atBottomCenterOf(getPosition(tag, "entry"))
                            : resume.position();
                    reconnecting.put(participant, new ReconnectState(
                        position,
                        resume == null ? 0.0F : resume.yaw(),
                        resume == null ? 0.0F : resume.pitch(),
                        deadline
                    ));
                }
                ActiveRun run = new ActiveRun(
                    tag.getUUID("runId"), tag.getLong("seed"), server, dungeonId,
                    slot, origin, size, getPosition(tag, "entry"),
                    getPosition(tag, "exit"),
                    tag.contains("clearExit") ? getPosition(tag, "clearExit") : null,
                    now + 40L,
                    createRandomEncounterConfig(definition, origin, size, slot),
                    getStringIntMap(tag, "healingUses"), participants,
                    new HashMap<>(), encounterRuntime,
                    DungeonLootClaims.restore(getLootClaims(tag)),
                    DungeonLootLedger.restore(
                        getItemMap(tag, "pendingLoot", server),
                        getItemMap(tag, "removableLoot", server)
                    ),
                    getBlockPositionMap(tag, "lootPositions"),
                    getBlockPositionMap(tag, "healingPositions"),
                    getBlockPositionMap(tag, "objectivePositions"),
                    new HashSet<>(getStringSet(tag, "completedObjectives")),
                    getCheckpointPositions(tag), activeCheckpoints, reconnecting,
                    getGateBounds(tag, definition, origin),
                    new HashSet<>(getStringSet(tag, "openedGates")), new HashMap<>()
                );
                participants.forEach(participant -> ACTIVE_RUNS.put(participant, run));
                restored++;
                reservedSlot = -1;
            } catch (RuntimeException error) {
                if (reservedSlot >= 0) ACTIVE_SLOTS.remove(reservedSlot);
                clearPersistedSlot(level, tag);
                LOGGER.error("Discarding invalid persisted dungeon run", error);
            }
        }
        persistActiveRuns(server);
        if (restored > 0) {
            LOGGER.info("Restored persisted dungeon runs: {}", restored);
        }
    }

    private static void clearPersistedSlot(ServerLevel level, CompoundTag tag) {
        int slot = tag.getInt("slot");
        if (slot < 0 || slot >= MAX_SLOTS || !tag.contains("origin")
            || !tag.contains("size") || ACTIVE_SLOTS.contains(slot)) return;
        BlockPos origin = getPosition(tag, "origin");
        BlockPos size = getPosition(tag, "size");
        if (origin.equals(slotOrigin(slot))
            && size.getX() > 0 && size.getX() <= SLOT_SPACING
            && size.getY() > 0 && size.getY() <= 256
            && size.getZ() > 0 && size.getZ() <= SLOT_SPACING) {
            clearSlot(level, origin, size);
        }
    }

    private static void putPosition(CompoundTag owner, String key, BlockPos position) {
        CompoundTag value = new CompoundTag();
        value.putInt("x", position.getX());
        value.putInt("y", position.getY());
        value.putInt("z", position.getZ());
        owner.put(key, value);
    }

    private static BlockPos getPosition(CompoundTag owner, String key) {
        CompoundTag value = owner.getCompound(key);
        return new BlockPos(value.getInt("x"), value.getInt("y"), value.getInt("z"));
    }

    private static void putUuidSet(CompoundTag owner, String key, Set<UUID> values) {
        ListTag list = new ListTag();
        values.forEach(value -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("value", value);
            list.add(entry);
        });
        owner.put(key, list);
    }

    private static Set<UUID> getUuidSet(CompoundTag owner, String key) {
        Set<UUID> result = new HashSet<>();
        ListTag list = owner.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.hasUUID("value")) result.add(entry.getUUID("value"));
        }
        return Set.copyOf(result);
    }

    private static void putStringSet(CompoundTag owner, String key, Set<String> values) {
        owner.putString(key, String.join("\n", values));
    }

    private static Set<String> getStringSet(CompoundTag owner, String key) {
        String value = owner.getString(key);
        return value.isBlank() ? Set.of() : Set.of(value.split("\\n"));
    }

    private static void putStringIntMap(
        CompoundTag owner, String key, Map<String, Integer> values
    ) {
        CompoundTag map = new CompoundTag();
        values.forEach(map::putInt);
        owner.put(key, map);
    }

    private static Map<String, Integer> getStringIntMap(
        CompoundTag owner, String key
    ) {
        CompoundTag map = owner.getCompound(key);
        Map<String, Integer> result = new HashMap<>();
        map.getAllKeys().forEach(value -> result.put(value, map.getInt(value)));
        return result;
    }

    private static void putBlockPositionMap(
        CompoundTag owner, String key, Map<String, BlockPos> values
    ) {
        ListTag list = new ListTag();
        values.forEach((id, position) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            putPosition(entry, "position", position);
            list.add(entry);
        });
        owner.put(key, list);
    }

    private static Map<String, BlockPos> getBlockPositionMap(
        CompoundTag owner, String key
    ) {
        Map<String, BlockPos> result = new HashMap<>();
        ListTag list = owner.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            result.put(entry.getString("id"), getPosition(entry, "position"));
        }
        return Map.copyOf(result);
    }

    private static void putUuidPositionMap(
        CompoundTag owner, String key, Map<UUID, BlockPos> values
    ) {
        ListTag list = new ListTag();
        values.forEach((id, position) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            putPosition(entry, "position", position);
            list.add(entry);
        });
        owner.put(key, list);
    }

    private static Map<UUID, BlockPos> getUuidPositionMap(
        CompoundTag owner, String key
    ) {
        Map<UUID, BlockPos> result = new HashMap<>();
        ListTag list = owner.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.hasUUID("id")) {
                result.put(entry.getUUID("id"), getPosition(entry, "position"));
            }
        }
        return result;
    }

    private static void putCheckpointPositions(
        CompoundTag owner, Map<String, CheckpointPosition> values
    ) {
        ListTag list = new ListTag();
        values.forEach((id, checkpoint) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            putPosition(entry, "position", checkpoint.position());
            entry.putInt("radius", checkpoint.activationRadius());
            list.add(entry);
        });
        owner.put("checkpointPositions", list);
    }

    private static Map<String, CheckpointPosition> getCheckpointPositions(
        CompoundTag owner
    ) {
        Map<String, CheckpointPosition> result = new HashMap<>();
        ListTag list = owner.getList("checkpointPositions", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            result.put(entry.getString("id"), new CheckpointPosition(
                getPosition(entry, "position"), entry.getInt("radius")
            ));
        }
        return Map.copyOf(result);
    }

    private static void putGateBounds(
        CompoundTag owner, Map<String, GateBounds> values
    ) {
        ListTag list = new ListTag();
        values.forEach((id, bounds) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            putPosition(entry, "minimum", bounds.minimum());
            putPosition(entry, "maximum", bounds.maximum());
            list.add(entry);
        });
        owner.put("gateBounds", list);
    }

    private static Map<String, GateBounds> getGateBounds(
        CompoundTag owner,
        DungeonDefinition definition,
        BlockPos origin
    ) {
        Map<String, GateBounds> result = new HashMap<>();
        if (owner.contains("gateBounds")) {
            ListTag list = owner.getList("gateBounds", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                result.put(entry.getString("id"), new GateBounds(
                    getPosition(entry, "minimum"), getPosition(entry, "maximum")
                ));
            }
        } else {
            for (DungeonDefinition.Gate gate : definition.gates()) {
                if (gate.placement().equals("marker")) {
                    throw new IllegalStateException(
                        "Persisted marker gate has no resolved bounds: " + gate.id()
                    );
                }
                result.put(gate.id(), new GateBounds(
                    origin.offset(gate.minimum()), origin.offset(gate.maximum())
                ));
            }
        }
        if (!result.keySet().containsAll(
            definition.gates().stream().map(DungeonDefinition.Gate::id).toList()
        )) {
            throw new IllegalStateException("Persisted dungeon gate bounds are incomplete");
        }
        return Map.copyOf(result);
    }

    private static void putEncounterEntities(
        CompoundTag owner, Map<UUID, EncounterEntityRef> values
    ) {
        ListTag list = new ListTag();
        values.forEach((entityId, reference) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("entityId", entityId);
            entry.putString("encounterId", reference.encounterId());
            entry.putInt("opponentIndex", reference.opponentIndex());
            list.add(entry);
        });
        owner.put("encounterEntities", list);
    }

    private static Map<UUID, EncounterEntityRef> getEncounterEntities(CompoundTag owner) {
        Map<UUID, EncounterEntityRef> result = new HashMap<>();
        ListTag list = owner.getList("encounterEntities", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.hasUUID("entityId")) {
                result.put(entry.getUUID("entityId"), new EncounterEntityRef(
                    entry.getString("encounterId"), entry.getInt("opponentIndex")
                ));
            }
        }
        return result;
    }

    private static void putLootClaims(
        CompoundTag owner, Map<String, Set<UUID>> claims
    ) {
        ListTag list = new ListTag();
        claims.forEach((container, players) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("container", container);
            putUuidSet(entry, "players", players);
            list.add(entry);
        });
        owner.put("lootClaims", list);
    }

    private static Map<String, Set<UUID>> getLootClaims(CompoundTag owner) {
        Map<String, Set<UUID>> result = new HashMap<>();
        ListTag list = owner.getList("lootClaims", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            result.put(entry.getString("container"), getUuidSet(entry, "players"));
        }
        return result;
    }

    private static void putItemMap(
        CompoundTag owner,
        String key,
        Map<UUID, List<ItemStack>> values,
        MinecraftServer server
    ) {
        ListTag list = new ListTag();
        values.forEach((playerId, stacks) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("playerId", playerId);
            ListTag items = new ListTag();
            stacks.forEach(stack -> items.add(
                stack.save(server.registryAccess(), new CompoundTag())
            ));
            entry.put("items", items);
            list.add(entry);
        });
        owner.put(key, list);
    }

    private static Map<UUID, List<ItemStack>> getItemMap(
        CompoundTag owner, String key, MinecraftServer server
    ) {
        Map<UUID, List<ItemStack>> result = new HashMap<>();
        ListTag list = owner.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (!entry.hasUUID("playerId")) continue;
            List<ItemStack> stacks = new ArrayList<>();
            ListTag items = entry.getList("items", Tag.TAG_COMPOUND);
            for (int item = 0; item < items.size(); item++) {
                ItemStack stack = ItemStack.parseOptional(
                    server.registryAccess(), items.getCompound(item)
                );
                if (!stack.isEmpty()) stacks.add(stack);
            }
            result.put(entry.getUUID("playerId"), List.copyOf(stacks));
        }
        return result;
    }

    private static void putResumePositions(CompoundTag owner, ActiveRun run) {
        ListTag list = new ListTag();
        for (UUID participant : run.participantIds()) {
            ReconnectState disconnected = run.reconnecting().get(participant);
            ServerPlayer online = run.server().getPlayerList().getPlayer(participant);
            Vec3 position = disconnected != null ? disconnected.position()
                : online != null ? online.position()
                : Vec3.atBottomCenterOf(run.activeCheckpoints().getOrDefault(
                    participant, run.entry()
                ));
            CompoundTag entry = new CompoundTag();
            entry.putUUID("playerId", participant);
            entry.putDouble("x", position.x);
            entry.putDouble("y", position.y);
            entry.putDouble("z", position.z);
            entry.putFloat("yaw", disconnected != null ? disconnected.yaw()
                : online != null ? online.getYRot() : 0.0F);
            entry.putFloat("pitch", disconnected != null ? disconnected.pitch()
                : online != null ? online.getXRot() : 0.0F);
            list.add(entry);
        }
        owner.put("resumePositions", list);
    }

    private static Map<UUID, ResumePosition> getResumePositions(CompoundTag owner) {
        Map<UUID, ResumePosition> result = new HashMap<>();
        ListTag list = owner.getList("resumePositions", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (!entry.hasUUID("playerId")) continue;
            result.put(entry.getUUID("playerId"), new ResumePosition(
                new Vec3(entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")),
                entry.getFloat("yaw"), entry.getFloat("pitch")
            ));
        }
        return result;
    }

    private static Vec3 reconnectPosition(
        ServerPlayer player,
        ServerLevel level,
        ReconnectState reconnect,
        ActiveRun run
    ) {
        Vec3 saved = reconnect.position();
        if (insideRunBounds(saved, run.origin(), run.size())) {
            BlockPos floor = BlockPos.containing(saved).below();
            AABB moved = player.getBoundingBox().move(
                saved.x - player.getX(), saved.y - player.getY(), saved.z - player.getZ()
            );
            if (level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                && level.noCollision(player, moved)) {
                return saved;
            }
        }
        return Vec3.atBottomCenterOf(run.entry());
    }

    private static void discoverBuildingPlacements(
        ResourceManager resources,
        Map<String, DungeonEntranceRef> configuredEntrances,
        Map<String, String> placements
    ) {
        Map<ResourceLocation, Resource> metadata = resources.listResources(
            "structure_metadata",
            location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".structure.json")
        );
        for (Map.Entry<ResourceLocation, Resource> entry : metadata.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("anchors")) continue;
                for (JsonElement element : root.getAsJsonArray("anchors")) {
                    JsonObject anchor = element.getAsJsonObject();
                    if (!anchor.has("type")
                        || !anchor.get("type").getAsString().equals("dungeon_entrance")) {
                        continue;
                    }
                    if (!anchor.has("entrance_id")) {
                        throw new IllegalStateException(
                            "Building dungeon entrance is missing entrance_id: " + entry.getKey()
                        );
                    }
                    String entranceId = anchor.get("entrance_id").getAsString();
                    if (!configuredEntrances.containsKey(entranceId)) {
                        throw new IllegalStateException(
                            "Building references missing dungeon entrance: "
                                + entry.getKey() + " -> " + entranceId
                        );
                    }
                    String previous = placements.putIfAbsent(
                        entranceId, entry.getKey().toString()
                    );
                    if (previous != null) {
                        throw new IllegalStateException(
                            "Dungeon entrance is placed more than once: " + entranceId
                                + " (" + previous + " / " + entry.getKey() + ")"
                        );
                    }
                }
            } catch (IOException | RuntimeException error) {
                if (error instanceof IllegalStateException state) throw state;
                throw new IllegalStateException(
                    "Invalid building dungeon entrance metadata: " + entry.getKey(), error
                );
            }
        }
    }

    private static List<CaveDungeonPlacement> discoverCavePlacements(
        ResourceManager resources,
        Map<String, DungeonEntranceRef> configuredEntrances,
        Map<String, String> placements
    ) {
        List<CaveDungeonPlacement> result = new ArrayList<>();
        Map<ResourceLocation, Resource> caves = resources.listResources(
            "caves", location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> entry : caves.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("dungeon_entrances")) continue;
                String dimension = root.getAsJsonObject("dimension")
                    .get("id").getAsString();
                for (JsonElement element : root.getAsJsonArray("dungeon_entrances")) {
                    JsonObject placement = element.getAsJsonObject();
                    String entranceId = placement.get("entrance_id").getAsString();
                    if (!configuredEntrances.containsKey(entranceId)) {
                        throw new IllegalStateException(
                            "Cave references missing dungeon entrance: "
                                + entry.getKey() + " -> " + entranceId
                        );
                    }
                    String previous = placements.putIfAbsent(
                        entranceId, entry.getKey().toString()
                    );
                    if (previous != null) {
                        throw new IllegalStateException(
                            "Dungeon entrance is placed more than once: " + entranceId
                                + " (" + previous + " / " + entry.getKey() + ")"
                        );
                    }
                    result.add(new CaveDungeonPlacement(
                        entranceId,
                        dimension,
                        jsonBlockPosition(placement.getAsJsonObject("position")),
                        jsonBlockPosition(placement.getAsJsonObject("safe_spawn"))
                    ));
                }
            } catch (IOException | RuntimeException error) {
                if (error instanceof IllegalStateException state) throw state;
                throw new IllegalStateException(
                    "Invalid cave dungeon entrance: " + entry.getKey(), error
                );
            }
        }
        return List.copyOf(result);
    }

    private static BlockPos jsonBlockPosition(JsonObject position) {
        return new BlockPos(
            position.get("x").getAsInt(), position.get("y").getAsInt(),
            position.get("z").getAsInt()
        );
    }

    private static void activateCavePlacements(
        MinecraftServer server, List<CaveDungeonPlacement> placements
    ) {
        for (CaveDungeonPlacement placement : placements) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(placement.dimension());
            ServerLevel level = dimensionId == null ? null : server.getLevel(
                ResourceKey.create(Registries.DIMENSION, dimensionId)
            );
            if (level == null) {
                throw new IllegalStateException(
                    "Cave dungeon entrance dimension is unavailable: "
                        + placement.dimension()
                );
            }
            placeCaveEntranceMarker(level, placement.trigger());
            ACTIVE_ENTRANCES.put(
                placement.entranceId(),
                new PlacedEntrance(
                    placement.entranceId(), level.dimension(),
                    placement.trigger(), placement.safeReturn()
                )
            );
        }
    }

    private static void placeCaveEntranceMarker(ServerLevel level, BlockPos trigger) {
        BlockPos floor = trigger.below();
        for (int x = -2; x <= 2; x++) {
            level.setBlock(floor.offset(x, 0, 0), Blocks.CUT_COPPER.defaultBlockState(), 3);
        }
        level.setBlock(floor, Blocks.LODESTONE.defaultBlockState(), 3);
        for (int x : new int[] {-2, 2}) {
            for (int y = 1; y <= 3; y++) {
                level.setBlock(floor.offset(x, y, 0), Blocks.OXIDIZED_CUT_COPPER.defaultBlockState(), 3);
            }
        }
        for (int x = -2; x <= 2; x++) {
            level.setBlock(floor.offset(x, 3, 0), Blocks.CUT_COPPER.defaultBlockState(), 3);
        }
        level.setBlock(floor.offset(0, 4, 0), Blocks.LIGHTNING_ROD.defaultBlockState(), 3);
    }

    private static StructureAnchor readStructureAnchor(
        ResourceManager resources,
        WorldStructureSystem.WorldStructure structure,
        String anchorId
    ) {
        ResourceLocation structureId = ResourceLocation.parse(structure.structure());
        ResourceLocation metadataId = ResourceLocation.fromNamespaceAndPath(
            structureId.getNamespace(),
            "structure_metadata/" + structureId.getPath() + ".structure.json"
        );
        Resource resource = resources.getResource(metadataId).orElseThrow(() ->
            new IllegalStateException(
                "Dungeon entrance structure metadata is missing: " + metadataId
            )
        );
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, JsonObject> anchors = root.getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> anchor.has("id"))
                .collect(Collectors.toUnmodifiableMap(
                    anchor -> anchor.get("id").getAsString(), anchor -> anchor
                ));
            JsonObject anchor = anchors.get(anchorId);
            if (anchor == null) {
                throw new IllegalStateException(
                    "Dungeon entrance structure anchor is missing: "
                        + structure.id() + " -> " + anchorId
                );
            }
            var position = anchor.getAsJsonArray("position");
            return new StructureAnchor(
                new BlockPos(
                    position.get(0).getAsInt(),
                    position.get(1).getAsInt(),
                    position.get(2).getAsInt()
                ),
                Direction.byName(anchor.get("facing").getAsString())
            );
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException(
                "Invalid dungeon entrance structure metadata: " + metadataId, error
            );
        }
    }

    private static String anchorKey(String structure, String anchorId) {
        return structure + "#" + anchorId;
    }

    private static double distanceSquared(Vec3 position, BlockPos target) {
        return target.distToCenterSqr(position.x, position.y, position.z);
    }

    record DungeonEntranceRef(
        DungeonDefinition definition,
        DungeonDefinition.Entrance entrance
    ) {}

    private record StructureAnchor(BlockPos position, Direction facing) {
        private StructureAnchor {
            if (facing == null || !facing.getAxis().isHorizontal()) {
                throw new IllegalStateException("Dungeon entrance anchor must face horizontally");
            }
        }
    }

    private record PlacedEntrance(
        String entranceId,
        ResourceKey<Level> dimension,
        BlockPos trigger,
        BlockPos safeReturn
    ) {}

    private record PendingEntry(DungeonEntranceRef ref, PlacedEntrance placement) {}

    private record QueuedEntry(
        PendingEntry pending,
        long expiresAt,
        double stayRadiusSquared
    ) {}

    private record MatchedEntry(ServerPlayer player, PendingEntry pending) {}

    private record PendingReward(
        ServerPlayer player,
        boolean firstClear,
        List<ItemStack> items
    ) {}

    private record PreparedTerrain(
        BlockPos size,
        BlockPos entryPosition,
        BlockPos exitPosition,
        Map<DungeonPieceLayout.MarkerKey, BlockPos> markers,
        long seed
    ) {}

    private record CheckpointPosition(BlockPos position, int activationRadius) {}

    private record GateBounds(BlockPos minimum, BlockPos maximum) {}

    private record ActiveRun(
        UUID runId,
        long seed,
        MinecraftServer server,
        String dungeonId,
        int slot,
        BlockPos origin,
        BlockPos size,
        BlockPos entry,
        BlockPos exit,
        BlockPos clearExit,
        long teleportCooldownUntil,
        PursuitEncounterSystem.Config randomEncounters,
        Map<String, Integer> healingUses,
        Set<UUID> participantIds,
        Map<UUID, Long> tetherWarningUntil,
        EncounterRuntime encounters,
        DungeonLootClaims lootClaims,
        DungeonLootLedger lootLedger,
        Map<String, BlockPos> lootPositions,
        Map<String, BlockPos> healingPositions,
        Map<String, BlockPos> objectivePositions,
        Set<String> completedObjectives,
        Map<String, CheckpointPosition> checkpointPositions,
        Map<UUID, BlockPos> activeCheckpoints,
        Map<UUID, ReconnectState> reconnecting,
        Map<String, GateBounds> gateBounds,
        Set<String> openedGates,
        Map<UUID, Long> objectiveMessageAfter
    ) {}

    private record ReconnectState(
        Vec3 position,
        float yaw,
        float pitch,
        long deadline
    ) {}

    private record ResumePosition(Vec3 position, float yaw, float pitch) {}

    private enum EncounterStatus {
        AVAILABLE,
        STARTING,
        ACTIVE,
        DEFEATED
    }

    private static final class EncounterRuntime {
        private final Map<UUID, EncounterEntityRef> encounterByEntity;
        private final Map<String, BlockPos> positionsById;
        private final Map<String, EncounterStatus> statusById = new HashMap<>();
        private final Map<UUID, String> battleToEncounter = new HashMap<>();
        private final Map<String, List<String>> generatedTrainerIds = new HashMap<>();
        private final Map<String, String> generatedEndLines = new HashMap<>();
        private String pendingEncounterId;
        private long pendingExpiresAt;
        private Set<UUID> pendingPlayers = Set.of();

        private EncounterRuntime(
            Map<UUID, EncounterEntityRef> encounterByEntity,
            List<DungeonDefinition.Encounter> encounters,
            Map<String, BlockPos> positionsById
        ) {
            this.encounterByEntity = new HashMap<>(encounterByEntity);
            this.positionsById = Map.copyOf(positionsById);
            encounters.forEach(encounter ->
                statusById.put(encounter.id(), EncounterStatus.AVAILABLE)
            );
        }
    }

    private record EncounterEntityRef(String encounterId, int opponentIndex) {}

    private record SpawnedEncounters(
        Map<UUID, EncounterEntityRef> entities,
        Map<String, BlockPos> positions
    ) {}

    private record CaveDungeonPlacement(
        String entranceId,
        String dimension,
        BlockPos trigger,
        BlockPos safeReturn
    ) {}

    private record ReturnFrame(
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {}
}
