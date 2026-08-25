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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;
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
        for (String entranceId : byEntrance.keySet()) {
            if (!placements.containsKey(entranceId)) {
                throw new IllegalStateException(
                    "Dungeon entrance has no world placement: " + entranceId
                );
            }
        }
        definitions = loaded;
        entrances = Map.copyOf(byEntrance);
        structureAnchors = Map.copyOf(anchors);
        ACTIVE_ENTRANCES.clear();
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
            }
            return;
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
        if (!definition.terrain().mode().equals("fixed_template")) {
            return "현재 프로토타입은 고정 NBT 던전만 입장할 수 있습니다.";
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
        BlockPos clearExit = null;
        PursuitEncounterSystem.Config randomEncounters;
        Map<UUID, EncounterEntityRef> encounterByEntity;
        try {
            size = prepareFixedTemplate(dungeonLevel, definition, origin);
            placeGates(dungeonLevel, definition, origin, size);
            clearExit = placeClearExit(dungeonLevel, definition, origin, size);
            placeHealingStations(dungeonLevel, definition, origin, size);
            placeLootContainers(dungeonLevel, definition, origin);
            encounterByEntity = spawnEncounters(
                dungeonLevel, definition, origin
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
        BlockPos entry = origin.offset(definition.terrain().entryPosition());
        BlockPos exit = origin.offset(definition.terrain().exitPosition());
        long cooldown = dungeonLevel.getGameTime() + 40L;
        Set<UUID> participantIds = entries.stream()
            .map(matched -> matched.player().getUUID())
            .collect(Collectors.toUnmodifiableSet());
        ActiveRun run = new ActiveRun(
            player.getServer(), definition.id(), slot, origin, size, entry, exit,
            clearExit, cooldown, randomEncounters, new HashMap<>(), participantIds,
            new HashMap<>(),
            new EncounterRuntime(encounterByEntity, definition.encounters()),
            new DungeonLootClaims(), new DungeonLootLedger(), new HashMap<>(),
            new HashSet<>()
        );
        entries.forEach(matched -> ACTIVE_RUNS.put(matched.player().getUUID(), run));
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

    private static Map<UUID, EncounterEntityRef> spawnEncounters(
        ServerLevel level, DungeonDefinition definition, BlockPos origin
    ) {
        Map<UUID, EncounterEntityRef> spawned = new HashMap<>();
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            BlockPos authoredPosition = origin.offset(encounter.position());
            for (int index = 0; index < encounter.npcs().size(); index++) {
                int opponentIndex = index;
                BlockPos position = encounterNpcPosition(
                    level, authoredPosition, encounter.yaw(), opponentIndex
                );
                Set<UUID> existing = easyNpcIds(level, position);
                if (!CobbleventureBootstrap.spawnRegionalNpc(
                    level, encounter.npcs().get(opponentIndex), position,
                    encounter.yaw(), "interact"
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
                        && !existing.contains(candidate.getUUID())
                ).stream().min(java.util.Comparator.comparingDouble(
                    candidate -> candidate.distanceToSqr(Vec3.atCenterOf(position))
                )).orElseThrow(() -> new IllegalStateException(
                    "Dungeon NPC entity could not be identified: "
                        + encounter.id() + "[" + opponentIndex + "]"
                ));
                spawned.put(
                    entity.getUUID(), new EncounterEntityRef(
                        encounter.id(), opponentIndex
                    )
                );
            }
        }
        return Map.copyOf(spawned);
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

    private static Set<UUID> easyNpcIds(ServerLevel level, BlockPos position) {
        return level.getEntitiesOfClass(
            Entity.class,
            new AABB(position).inflate(6.0D, 10.0D, 6.0D),
            DungeonSystem::isEasyNpc
        ).stream().map(Entity::getUUID).collect(Collectors.toSet());
    }

    private static boolean isEasyNpc(Entity entity) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return type != null && type.getNamespace().equals("easy_npc");
    }

    private static void placeLootContainers(
        ServerLevel level, DungeonDefinition definition, BlockPos origin
    ) {
        ResourceKey<LootTable> lootTable = ResourceKey.create(
            Registries.LOOT_TABLE,
            ResourceLocation.parse(definition.loot().lootTable())
        );
        long runSeed = level.getRandom().nextLong() ^ origin.asLong();
        for (DungeonDefinition.LootContainer container : definition.loot().containers()) {
            BlockPos position = origin.offset(container.position());
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
    }

    private static void placeHealingStations(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        BlockPos size
    ) {
        for (DungeonDefinition.HealingStation station
            : definition.support().healingStations()) {
            BlockPos relative = station.position();
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
            if (block == Blocks.AIR
                || !level.setBlock(origin.offset(relative), block.defaultBlockState(), 3)) {
                throw new IllegalStateException(
                    "Dungeon healing station placement failed: " + station.id()
                );
            }
        }
    }

    private static BlockPos placeClearExit(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        BlockPos size
    ) {
        DungeonDefinition.Completion completion = definition.completion();
        if (!completion.returnTrigger().equals("clear_exit")) {
            return null;
        }
        BlockPos relative = completion.clearExitPosition();
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

    private static void placeGates(
        ServerLevel level,
        DungeonDefinition definition,
        BlockPos origin,
        BlockPos size
    ) {
        for (DungeonDefinition.Gate gate : definition.gates()) {
            BlockPos maximum = gate.maximum();
            if (maximum.getX() >= size.getX() || maximum.getY() >= size.getY()
                || maximum.getZ() >= size.getZ()) {
                throw new IllegalStateException(
                    "Dungeon gate exceeds the template: " + gate.id()
                );
            }
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
                origin.offset(gate.minimum()), origin.offset(gate.maximum())
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
                && !useHealingStation(player, event.getPos()))) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static synchronized boolean claimDungeonLoot(
        ServerPlayer player, BlockPos position
    ) {
        ActiveRun run = ACTIVE_RUNS.get(player.getUUID());
        if (run == null || !player.serverLevel().dimension().equals(DUNGEONS)) {
            return false;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null || definition.loot().ownership().equals("run_shared")) {
            return false;
        }
        DungeonDefinition.LootContainer container = definition.loot().containers()
            .stream()
            .filter(candidate -> run.origin().offset(candidate.position()).equals(position))
            .findFirst().orElse(null);
        if (container == null) return false;
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
            rewards = generateDungeonLoot(player, definition.loot().lootTable(), position);
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
        EncounterEntityRef entityRef = run.encounters().encounterByEntity.get(
            opponent.getUUID()
        );
        if (entityRef == null) return false;
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
            .filter(candidate -> players.containsAll(candidate.participantIds()))
            .findFirst().orElse(null);
        if (run == null) return;
        EncounterRuntime runtime = run.encounters();
        String encounterId = runtime.pendingEncounterId;
        runtime.pendingEncounterId = null;
        runtime.statusById.put(encounterId, EncounterStatus.ACTIVE);
        runtime.battleToEncounter.put(event.getBattle().getBattleId(), encounterId);
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
        boolean won = winners.containsAll(run.participantIds());
        run.encounters().statusById.put(
            encounterId, won ? EncounterStatus.DEFEATED : EncounterStatus.AVAILABLE
        );
        DungeonDefinition definition = definitions.get(run.dungeonId());
        DungeonDefinition.Encounter encounter = definition == null ? null
            : definition.encounters().stream()
                .filter(candidate -> candidate.id().equals(encounterId))
                .findFirst().orElse(null);
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
            ? "[던전] 협력 전투에서 승리했습니다."
            : "[던전] 협력 전투에서 패배했습니다.");
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
                || !gate.requires().stream().allMatch(required ->
                    run.encounters().statusById.get(required)
                        == EncounterStatus.DEFEATED
                )) {
                continue;
            }
            run.openedGates().add(gate.id());
            BlockPos minimum = run.origin().offset(gate.minimum());
            BlockPos maximum = run.origin().offset(gate.maximum());
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

    private static synchronized void onBattleFled(BattleFledEvent event) {
        ActiveRun run = runForBattle(event.getBattle().getBattleId());
        if (run == null) return;
        String encounterId = run.encounters().battleToEncounter.remove(
            event.getBattle().getBattleId()
        );
        run.encounters().statusById.put(encounterId, EncounterStatus.AVAILABLE);
        notifyEncounterResult(run, "[던전] 협력 전투가 중단되었습니다.");
    }

    private static ActiveRun runForBattle(UUID battleId) {
        return ACTIVE_RUNS.values().stream()
            .filter(run -> run.encounters().battleToEncounter.containsKey(battleId))
            .findFirst().orElse(null);
    }

    private static void notifyEncounterResult(ActiveRun run, String message) {
        for (UUID participantId : run.participantIds()) {
            ServerPlayer player = run.server().getPlayerList().getPlayer(participantId);
            if (player != null) player.sendSystemMessage(Component.literal(message));
        }
    }

    private static boolean expirePendingEncounter(ActiveRun run, long gameTime) {
        EncounterRuntime runtime = run.encounters();
        if (runtime.pendingEncounterId == null
            || gameTime < runtime.pendingExpiresAt) return false;
        runtime.statusById.put(runtime.pendingEncounterId, EncounterStatus.AVAILABLE);
        runtime.pendingEncounterId = null;
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
            .filter(candidate -> run.origin().offset(candidate.position()).equals(position))
            .findFirst().orElse(null);
        if (station == null) return false;
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
        ServerLevel level = server.getLevel(DUNGEONS);
        if (level != null) {
            clearSlot(level, run.origin(), run.size());
        }
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
            int graceSeconds = definition == null
                ? 0 : definition.lifecycle().reconnectGraceSeconds();
            if (graceSeconds <= 0
                || BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
                failRun(run, "참가자의 연결이 끊겨 던전 도전이 종료되었습니다.", false);
            } else {
                long deadline = player.getServer().overworld().getGameTime()
                    + graceSeconds * 20L;
                run.reconnecting().put(player.getUUID(), new ReconnectState(
                    player.position(), player.getYRot(), player.getXRot(), deadline
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
        }
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

    private record ActiveRun(
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
        Map<UUID, ReconnectState> reconnecting,
        Set<String> openedGates
    ) {}

    private record ReconnectState(
        Vec3 position,
        float yaw,
        float pitch,
        long deadline
    ) {}

    private enum EncounterStatus {
        AVAILABLE,
        STARTING,
        ACTIVE,
        DEFEATED
    }

    private static final class EncounterRuntime {
        private final Map<UUID, EncounterEntityRef> encounterByEntity;
        private final Map<String, EncounterStatus> statusById = new HashMap<>();
        private final Map<UUID, String> battleToEncounter = new HashMap<>();
        private String pendingEncounterId;
        private long pendingExpiresAt;

        private EncounterRuntime(
            Map<UUID, EncounterEntityRef> encounterByEntity,
            List<DungeonDefinition.Encounter> encounters
        ) {
            this.encounterByEntity = Map.copyOf(encounterByEntity);
            encounters.forEach(encounter ->
                statusById.put(encounter.id(), EncounterStatus.AVAILABLE)
            );
        }
    }

    private record EncounterEntityRef(String encounterId, int opponentIndex) {}

    private record ReturnFrame(
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {}
}
