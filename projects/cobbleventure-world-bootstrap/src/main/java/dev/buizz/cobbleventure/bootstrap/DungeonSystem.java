package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.buizz.cobbleventure.adventure.PokemonCenterDefeatReturn;
import dev.buizz.cobbleventure.adventure.event.ServerPlayerEventState;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import dev.buizz.cobbleventure.playermenu.BagApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import org.slf4j.Logger;

/** Validates dungeon content and runs the first fixed-template solo prototype. */
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
    private static final Map<UUID, ActiveRun> ACTIVE_RUNS = new HashMap<>();
    private static final Set<Integer> ACTIVE_SLOTS = new HashSet<>();
    private static final Set<UUID> COMPLETING_RUNS = new HashSet<>();

    private DungeonSystem() {}

    static void register(IEventBus modBus) {
        DungeonGuideNetwork.register(modBus);
        PokemonCenterDefeatReturn.setDefeatRecoveryOverride(
            DungeonSystem::handlePartyWipe
        );
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onTeleport);
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(DungeonSystem::onPlayerLoggedOut);
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
        ACTIVE_RUNS.clear();
        ACTIVE_SLOTS.clear();
        COMPLETING_RUNS.clear();
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
                returnPlayer(
                    player,
                    "던전 경계를 벗어나 도전이 종료되었습니다. 입구로 복귀합니다."
                );
                return;
            }
            if (run != null && completionReached(player, run)) {
                completeRun(player, run);
                return;
            }
            if (run != null && gameTime >= run.teleportCooldownUntil()
                && distanceSquared(player.position(), run.exit()) <= EXIT_RADIUS_SQUARED) {
                returnPlayer(player, "던전에서 나왔습니다.");
            }
            return;
        }
        if (run != null) {
            if (escapeActionsBlocked(run)) {
                returnPlayer(
                    player,
                    "외부 이동이 감지되어 던전 도전이 종료되었습니다."
                );
            } else {
                abandonRun(player);
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
        if (run == null || !escapeActionsBlocked(run)) {
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
            Cobblemon.INSTANCE.getStorage().getParty(player).heal();
        }
        boolean usePokemonCenter = lifecycle.wipeReturn().equals("pokemon_center");
        returnPlayer(
            player,
            usePokemonCenter
                ? "던전 도전에 실패했습니다. 포켓몬센터로 후송됩니다."
                : "던전 도전에 실패해 입구로 복귀했습니다."
        );
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
                currentPartyLevel
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
        startSoloRun(player, pending);
    }

    private static void startSoloRun(ServerPlayer player, PendingEntry pending) {
        DungeonDefinition definition = pending.ref().definition();
        if (!definition.terrain().mode().equals("fixed_template")) {
            player.sendSystemMessage(Component.literal(
                "현재 프로토타입은 고정 NBT 던전만 입장할 수 있습니다."
            ));
            return;
        }
        ServerLevel dungeonLevel = player.getServer().getLevel(DUNGEONS);
        if (dungeonLevel == null) {
            player.sendSystemMessage(Component.literal("던전 차원을 찾을 수 없습니다."));
            return;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.sendSystemMessage(Component.literal(
                "배틀 중에는 던전에 입장할 수 없습니다."
            ));
            return;
        }
        DungeonEntryEligibility.Evaluation eligibility = DungeonEntryEligibility.evaluate(
            definition.eligibility(), definition.difficulty(), partySnapshot(player)
        );
        if (!eligibility.allowed()) {
            player.sendSystemMessage(Component.literal(
                eligibilityMessage(definition, eligibility)
            ));
            return;
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
            player.sendSystemMessage(Component.literal("이미 클리어한 던전입니다."));
            return;
        }
        int slot = allocateSlot();
        if (slot < 0) {
            player.sendSystemMessage(Component.literal("사용 가능한 던전 슬롯이 없습니다."));
            return;
        }
        BlockPos origin = slotOrigin(slot);
        BlockPos size = BlockPos.ZERO;
        PursuitEncounterSystem.Config randomEncounters;
        try {
            size = prepareFixedTemplate(dungeonLevel, definition, origin);
            placeHealingStations(dungeonLevel, definition, origin, size);
            placeLootContainers(dungeonLevel, definition, origin);
            spawnEncounters(dungeonLevel, definition, origin);
            randomEncounters = createRandomEncounterConfig(
                definition, origin, size, slot
            );
        } catch (RuntimeException error) {
            ACTIVE_SLOTS.remove(slot);
            if (!size.equals(BlockPos.ZERO)) {
                clearSlot(dungeonLevel, origin, size);
            }
            LOGGER.error("Dungeon instance preparation failed: {}", definition.id(), error);
            player.sendSystemMessage(Component.literal(
                "던전 준비에 실패했습니다. 서버 로그를 확인하세요."
            ));
            return;
        }
        pushReturnFrame(player, pending.placement().safeReturn());
        if (definition.completion().repeatable()) {
            state.setFlag(definition.completion().victoryFlag(), false);
        }
        BlockPos entry = origin.offset(definition.terrain().entryPosition());
        BlockPos exit = origin.offset(definition.terrain().exitPosition());
        long cooldown = dungeonLevel.getGameTime() + 40L;
        player.teleportTo(
            dungeonLevel,
            entry.getX() + 0.5D, entry.getY(), entry.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
        if (player.serverLevel() != dungeonLevel) {
            popReturnFrame(player);
            ACTIVE_SLOTS.remove(slot);
            clearSlot(dungeonLevel, origin, size);
            player.sendSystemMessage(Component.literal(
                "던전 내부로 이동하지 못해 준비된 슬롯을 초기화했습니다."
            ));
            return;
        }
        ACTIVE_RUNS.put(
            player.getUUID(),
            new ActiveRun(
                definition.id(), slot, origin, size, entry, exit, cooldown,
                randomEncounters, new HashMap<>()
            )
        );
        player.sendSystemMessage(Component.literal(
            definition.displayName() + " 도전을 시작합니다."
        ));
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

    private static void spawnEncounters(
        ServerLevel level, DungeonDefinition definition, BlockPos origin
    ) {
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            BlockPos position = origin.offset(encounter.position());
            if (!CobbleventureBootstrap.spawnRegionalNpc(
                level, encounter.npc(), position, encounter.yaw(), "interact"
            )) {
                throw new IllegalStateException(
                    "Dungeon NPC placement failed: " + encounter.id()
                );
            }
        }
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
            blockEntity.setLootTable(lootTable);
            blockEntity.setLootTableSeed(runSeed ^ container.id().hashCode());
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

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
            || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)
            || !useHealingStation(player, event.getPos())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
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
        if (!COMPLETING_RUNS.add(player.getUUID())) {
            return;
        }
        DungeonDefinition definition = definitions.get(run.dungeonId());
        if (definition == null) {
            returnPlayer(player, "던전을 클리어했습니다.");
            return;
        }
        try {
            int previousClears = DungeonClearProgress.clearCount(
                player.getPersistentData(), definition.id()
            );
            boolean firstClear = previousClears == 0;
            String rewardTable = firstClear
                ? definition.rewards().firstClearTable()
                : definition.rewards().repeatTable();
            List<ItemStack> itemRewards = generateClearRewards(player, rewardTable);
            ServerPlayerEventState state = new ServerPlayerEventState(player);
            if (firstClear) {
                for (String move : definition.rewards().firstClearFieldMoves()) {
                    state.grantFieldMove(move);
                }
            }
            int clearCount = DungeonClearProgress.recordClear(
                player.getPersistentData(), definition.id()
            );
            returnPlayer(
                player,
                definition.displayName() + " 클리어! 보상을 획득했습니다. ("
                    + clearCount + "회차)"
            );
            grantClearItems(player, itemRewards);
        } catch (RuntimeException error) {
            COMPLETING_RUNS.remove(player.getUUID());
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

    private static void grantClearItems(ServerPlayer player, List<ItemStack> rewards) {
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
        popReturnFrame(player);
        ActiveRun run = releaseRun(player.getUUID());
        cleanupRun(player.getServer(), run);
    }

    private static void cleanupRun(MinecraftServer server, ActiveRun run) {
        if (run == null) return;
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
        if (removed != null) {
            ACTIVE_SLOTS.remove(removed.slot());
        }
        return removed;
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !player.serverLevel().dimension().equals(DUNGEONS)
            || !hasReturnFrame(player)) {
            return;
        }
        returnPlayer(player, "중단된 던전에서 안전하게 복귀했습니다.");
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        cleanupRun(player.getServer(), releaseRun(player.getUUID()));
        INSIDE_ENTRANCES.remove(player.getUUID());
        PENDING_ENTRIES.remove(player.getUUID());
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

    private record ActiveRun(
        String dungeonId,
        int slot,
        BlockPos origin,
        BlockPos size,
        BlockPos entry,
        BlockPos exit,
        long teleportCooldownUntil,
        PursuitEncounterSystem.Config randomEncounters,
        Map<String, Integer> healingUses
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
