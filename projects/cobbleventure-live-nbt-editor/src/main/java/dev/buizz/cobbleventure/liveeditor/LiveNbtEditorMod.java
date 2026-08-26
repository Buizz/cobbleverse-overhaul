package dev.buizz.cobbleventure.liveeditor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

@Mod(LiveNbtEditorMod.MOD_ID)
@EventBusSubscriber(modid = LiveNbtEditorMod.MOD_ID)
public final class LiveNbtEditorMod {
    public static final String MOD_ID = "cobbleventure_live_nbt_editor";
    private static final String PREPARE_PROPERTY = "cobbleventure.liveEditor.prepareWorld";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final ResourceKey<Level> EDIT_LEVEL = ResourceKey.create(
        Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "edit_world")
    );
    private static final ResourceKey<Level> TEST_LEVEL = ResourceKey.create(
        Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "test_world")
    );
    static final BlockPos ORIGIN = new BlockPos(0, 65, 0);
    private static final int MAX_SIZE = 256;
    private static final int FLOOR_MARGIN = 16;
    private static LiveState active;
    private static JsonObject activeMetadata;
    private static int pollTicks;
    private static int shutdownTicks = -1;
    private static boolean editFloorPrepared;
    private static JsonObject pendingOpenCommand;
    private static String pendingOpenRevision;

    public LiveNbtEditorMod(IEventBus modBus) {
        LiveEditorBlocks.register(modBus);
        LiveEditorNetwork.register(modBus);
        if (FMLEnvironment.dist.isClient()) {
            dev.buizz.cobbleventure.liveeditor.client.LiveEditorClient.register(modBus);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        server.setDefaultGameType(GameType.CREATIVE);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        configureLevel(server.overworld(), server);
        if (server.getLevel(EDIT_LEVEL) != null) configureLevel(server.getLevel(EDIT_LEVEL), server);
        if (server.getLevel(TEST_LEVEL) != null) configureLevel(server.getLevel(TEST_LEVEL), server);
        Filesystem.ensureLiveDirectories(server);
        active = readState(server);
        activeMetadata = active == null ? null : readActiveMetadata(server, active);
        pollTicks = 0;
        editFloorPrepared = false;
        pendingOpenCommand = null;
        pendingOpenRevision = null;
        if (Boolean.getBoolean(PREPARE_PROPERTY)) {
            server.saveEverything(true, true, true);
            shutdownTicks = 20;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (++pollTicks >= 10) {
            pollTicks = 0;
            processPending(server);
        }
        if (shutdownTicks >= 0 && --shutdownTicks == 0) {
            server.saveEverything(true, true, true);
            server.halt(false);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        active = null;
        activeMetadata = null;
        editFloorPrepared = false;
        pendingOpenCommand = null;
        pendingOpenRevision = null;
        shutdownTicks = -1;
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        player.setGameMode(GameType.CREATIVE);
        server.getPlayerList().op(player.getGameProfile());
        server.getCommands().sendCommands(player);
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] Content Studio에서 NBT를 선택하면 전용 차원에 즉시 반영됩니다."
        ));
        LiveEditorTools.preparePlayer(player);
        LiveEditorNetwork.sendSnapshot(player);
        if (pendingOpenCommand != null && pendingOpenRevision != null) {
            LiveEditorNetwork.requestOpenDecision(
                player, pendingOpenRevision, active == null ? "" : active.id(),
                requiredString(pendingOpenCommand, "id")
            );
        }
        if (active != null) {
            teleportPlayer(server, player);
            repairExistingPlayingCardsTableOwners(requireEditLevel(server), player);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(LiveEditorTools.appendCommands(
            Commands.literal("cobbleventure_live")
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("sync").executes(context -> sync(context.getSource())))
                .then(Commands.literal("save").executes(context -> save(context.getSource())))
                .then(Commands.literal("tp").executes(context -> teleport(context.getSource())))
                .then(Commands.literal("test")
                    .then(Commands.literal("place")
                        .executes(context -> placeForTesting(context.getSource())))
                    .then(Commands.literal("tp")
                        .executes(context -> teleportToTest(context.getSource()))))
        ));
    }

    private static void configureLevel(ServerLevel level, MinecraftServer server) {
        level.setDayTime(1000L);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
    }

    private static int status(CommandSourceStack source) {
        if (active == null) {
            source.sendSuccess(() -> Component.literal("[Live NBT Editor] 활성 NBT 없음"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "[Live NBT Editor] " + active.id() + " · " + format(active.size())
                + " · " + active.source()
        ), false);
        return 1;
    }

    private static int sync(CommandSourceStack source) {
        processPending(source.getServer());
        return status(source);
    }

    private static int save(CommandSourceStack source) {
        try {
            saveActive(source.getServer(), "command");
            source.sendSuccess(() -> Component.literal("[Live NBT Editor] 저장 완료"), true);
            return 1;
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("[Live NBT Editor] " + error.getMessage()));
            return 0;
        }
    }

    private static int teleport(CommandSourceStack source) {
        try {
            if (active == null) throw new IllegalStateException("먼저 웹에서 NBT를 여세요.");
            teleportPlayer(source.getServer(), source.getPlayerOrException());
            return 1;
        } catch (Exception error) {
            source.sendFailure(Component.literal("[Live NBT Editor] " + error.getMessage()));
            return 0;
        }
    }

    private static void processPending(MinecraftServer server) {
        Path commandPath = Filesystem.liveRoot(server).resolve("command.json");
        if (!Files.isRegularFile(commandPath)) {
            pendingOpenCommand = null;
            pendingOpenRevision = null;
            return;
        }
        try {
            JsonObject command = JsonParser.parseString(
                Files.readString(commandPath, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            String action = requiredString(command, "action");
            String revision = requiredString(command, "revision");
            if ("open".equals(action) && active != null) {
                if (!revision.equals(pendingOpenRevision)) {
                    pendingOpenCommand = command.deepCopy();
                    pendingOpenRevision = revision;
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        LiveEditorNetwork.requestOpenDecision(
                            player, revision, active.id(), requiredString(command, "id")
                        );
                    }
                }
                return;
            }
            switch (action) {
                case "open" -> open(server, command, revision, false);
                case "save" -> saveActive(server, revision);
                case "resize" -> resize(server, readSize(command), revision);
                case "test_place" -> placeForTesting(server, revision);
                default -> throw new IllegalArgumentException("지원하지 않는 명령: " + action);
            }
            Files.deleteIfExists(commandPath);
        } catch (Exception error) {
            writeError(server, error);
            try {
                Files.move(commandPath, Filesystem.liveRoot(server).resolve("command.failed.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    static void resolveOpenDecision(
        MinecraftServer server, String revision, String decision, ServerPlayer player
    ) {
        if (pendingOpenCommand == null || !revision.equals(pendingOpenRevision)) {
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 이미 처리되었거나 만료된 전환 요청입니다."
            ));
            return;
        }
        Path commandPath = Filesystem.liveRoot(server).resolve("command.json");
        try {
            if ("cancel".equals(decision)) {
                Files.deleteIfExists(commandPath);
                announce(server, "NBT 전환을 취소했습니다.");
            } else if ("save".equals(decision) || "discard".equals(decision)) {
                open(server, pendingOpenCommand, revision, "save".equals(decision));
                Files.deleteIfExists(commandPath);
            } else {
                throw new IllegalArgumentException("알 수 없는 전환 선택: " + decision);
            }
            pendingOpenCommand = null;
            pendingOpenRevision = null;
        } catch (Exception error) {
            writeError(server, error);
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 전환 실패: " + error.getMessage()
            ));
        }
    }

    static void saveFromShortcut(MinecraftServer server, ServerPlayer player) {
        try {
            saveActive(server, "shortcut-" + System.currentTimeMillis());
            player.sendSystemMessage(Component.literal("[Live NBT Editor] 저장 완료"));
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 저장 실패: " + error.getMessage()
            ));
        }
    }

    private static void open(
        MinecraftServer server, JsonObject command, String revision, boolean saveCurrent
    )
        throws IOException {
        Path input = Filesystem.liveRoot(server).resolve("inbox/active.nbt");
        CompoundTag serialized = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        PlayingCardsEntityLinks.repairStructure(serialized);
        String requestedId = requiredString(command, "id");
        if (active != null && saveCurrent) {
            ListTag elevatorRecovery = active.id().equals(requestedId)
                ? serialized.getList("entities", Tag.TAG_COMPOUND) : null;
            saveActive(server, "before-" + revision, elevatorRecovery);
        }
        ServerLevel level = requireEditLevel(server);
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), serialized);
        Vec3i requested = readSize(command);
        LiveState nextState = new LiveState(
            requestedId, requiredString(command, "source"), revision,
            command.has("source_digest") ? command.get("source_digest").getAsString() : "",
            requested, template.getSize(), active == null ? 0 : active.testPlacements()
        );
        JsonObject nextMetadata = readMetadata(
            Filesystem.liveRoot(server).resolve("inbox/active.structure.json"), nextState
        );
        if (!editFloorPrepared) {
            clearLoadedLegacyBounds(level);
            editFloorPrepared = true;
        }
        if (active != null) {
            clearBounds(level, active.size());
        }
        prepareFloor(level, ORIGIN, requested);
        clearSlot(level, active == null ? requested : max(active.size(), requested));
        boolean placed = template.placeInWorld(
            level, ORIGIN, ORIGIN, placementSettings(),
            RandomSource.create(level.getSeed() ^ ORIGIN.asLong()), 2
        );
        if (!placed) throw new IllegalStateException("NBT를 편집 차원에 배치하지 못했습니다.");
        PlayingCardsEntityLinks.relinkPlacedEntities(level, ORIGIN, template.getSize());
        syncPlacedBlockEntities(level, ORIGIN, template.getSize());
        active = nextState;
        activeMetadata = nextMetadata;
        drawBounds(level, requested);
        writeState(server);
        writeDraftMetadata(server);
        announce(server, "웹 변경 감지: " + active.id() + " · " + format(active.size()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LiveEditorNetwork.sendSnapshot(player);
            teleportPlayer(server, player);
        }
    }

    private static void resize(MinecraftServer server, Vec3i size, String revision) {
        if (active == null) throw new IllegalStateException("크기를 바꿀 NBT가 없습니다.");
        ServerLevel level = requireEditLevel(server);
        clearBounds(level, active.size());
        prepareFloor(level, ORIGIN, size);
        active = new LiveState(active.id(), active.source(), revision, active.sourceDigest(),
            size, active.sourceSize(), active.testPlacements());
        drawBounds(level, size);
        writeState(server);
        announce(server, "편집 범위 변경: " + format(size));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LiveEditorNetwork.sendSnapshot(player);
        }
    }

    private static void saveActive(MinecraftServer server, String revision) {
        saveActive(server, revision, null);
    }

    private static void saveActive(
        MinecraftServer server, String revision, ListTag elevatorRecovery
    ) {
        if (active == null) throw new IllegalStateException("저장할 NBT가 없습니다.");
        ServerLevel level = requireEditLevel(server);
        StructureTemplate template = captureTemplate(
            level, ORIGIN, active.size(), elevatorRecovery
        );
        template.setAuthor("Cobbleventure Live NBT Editor");
        Path outbox = Filesystem.liveRoot(server).resolve("outbox");
        try {
            Files.createDirectories(outbox);
            Path resultPath = outbox.resolve("result.json");
            Files.deleteIfExists(resultPath);
            Path temporary = outbox.resolve(".active.nbt.tmp");
            Path nbtPath = outbox.resolve("active.nbt");
            Path metadataPath = outbox.resolve("active.structure.json");
            NbtIo.writeCompressed(template.save(new CompoundTag()), temporary);
            Files.move(temporary, nbtPath,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writeSavedMetadata(server);
            JsonObject result = new JsonObject();
            result.addProperty("schema_version", 1);
            result.addProperty("status", "saved");
            result.addProperty("revision", revision);
            result.addProperty("id", active.id());
            result.addProperty("source", active.source());
            result.add("size", sizeJson(active.size()));
            result.addProperty("nbt_digest", sha256(nbtPath));
            result.addProperty("metadata_digest", sha256(metadataPath));
            writeJson(resultPath, result);
        } catch (IOException error) {
            throw new IllegalStateException("NBT 저장에 실패했습니다.", error);
        }
    }

    private static int placeForTesting(CommandSourceStack source) {
        try {
            placeForTesting(source.getServer(), "command");
            return 1;
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("[Live NBT Editor] " + error.getMessage()));
            return 0;
        }
    }

    private static void placeForTesting(MinecraftServer server, String revision) {
        if (active == null) throw new IllegalStateException("테스트할 활성 NBT가 없습니다.");
        ServerLevel editLevel = requireEditLevel(server);
        ServerLevel testLevel = requireTestLevel(server);
        StructureTemplate template = captureTemplate(editLevel, ORIGIN, active.size());
        int index = active.testPlacements();
        BlockPos destination = new BlockPos((index % 5) * 320, 65, (index / 5) * 320);
        prepareFloor(testLevel, destination, active.size());
        boolean placed = template.placeInWorld(
            testLevel, destination, destination,
            ExplicitAirPlacementProcessor.configure(template, placementSettings()),
            RandomSource.create(testLevel.getSeed() ^ destination.asLong()), 2
        );
        if (!placed) throw new IllegalStateException("테스트 차원 배치에 실패했습니다.");
        PlayingCardsEntityLinks.relinkPlacedEntities(testLevel, destination, active.size());
        syncPlacedBlockEntities(testLevel, destination, active.size());
        active = new LiveState(active.id(), active.source(), revision, active.sourceDigest(),
            active.size(), active.sourceSize(), index + 1);
        writeState(server);
        announce(server, "테스트 차원 배치: " + active.id() + " · " + destination.toShortString());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.teleportTo(testLevel, destination.getX() - 3.5D, destination.getY() + 1.0D,
                destination.getZ() - 3.5D, 45.0F, 0.0F);
        }
    }

    private static int teleportToTest(CommandSourceStack source) {
        try {
            ServerLevel level = requireTestLevel(source.getServer());
            ServerPlayer player = source.getPlayerOrException();
            player.teleportTo(level, -3.5D, 66.0D, -3.5D, 45.0F, 0.0F);
            return 1;
        } catch (Exception error) {
            source.sendFailure(Component.literal("[Live NBT Editor] " + error.getMessage()));
            return 0;
        }
    }

    private static void clearSlot(ServerLevel level, Vec3i size) {
        BlockPos from = ORIGIN.offset(-1, 0, -1);
        BlockPos to = ORIGIN.offset(size.getX(), size.getY() - 1, size.getZ());
        for (Entity entity : level.getEntities(null, new AABB(
            from.getX(), from.getY(), from.getZ(),
            to.getX() + 1, to.getY() + 1, to.getZ() + 1
        ))) {
            if (!(entity instanceof ServerPlayer)) entity.discard();
        }
        for (BlockPos cursor : BlockPos.betweenClosed(from, to)) {
            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static StructurePlaceSettings placementSettings() {
        return new StructurePlaceSettings()
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
            .addProcessor(PlayingCardsEntityPlacementProcessor.INSTANCE)
            .addProcessor(CreateElevatorEntityPlacementProcessor.INSTANCE);
    }

    private static StructureTemplate captureTemplate(
        ServerLevel level, BlockPos origin, Vec3i size
    ) {
        return captureTemplate(level, origin, size, null);
    }

    private static StructureTemplate captureTemplate(
        ServerLevel level, BlockPos origin, Vec3i size, ListTag elevatorRecovery
    ) {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, origin, size, false, Blocks.STRUCTURE_VOID);
        CompoundTag serialized = template.save(new CompoundTag());
        List<Entity> entities = exportableEntities(level, origin, size);
        ListTag entityList = new ListTag();
        int elevators = 0;
        for (Entity entity : entities) {
            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            CompoundTag entityData = entity.saveWithoutId(new CompoundTag());
            entityData.putString("id", entityId.toString());
            if (!PlayingCardsEntityLinks.isDeck(entityId.toString())) {
                entityData.remove("UUID");
            }

            CompoundTag entityInfo = new CompoundTag();
            ListTag position = new ListTag();
            position.add(DoubleTag.valueOf(entity.getX() - origin.getX()));
            position.add(DoubleTag.valueOf(entity.getY() - origin.getY()));
            position.add(DoubleTag.valueOf(entity.getZ() - origin.getZ()));
            entityInfo.put("pos", position);
            BlockPos relative = entity.blockPosition().subtract(origin);
            ListTag blockPosition = new ListTag();
            blockPosition.add(IntTag.valueOf(relative.getX()));
            blockPosition.add(IntTag.valueOf(relative.getY()));
            blockPosition.add(IntTag.valueOf(relative.getZ()));
            entityInfo.put("blockPos", blockPosition);
            entityInfo.put("nbt", entityData);
            entityList.add(entityInfo);
            if (isElevatorContraption(entityData, entityId)) elevators++;
        }
        serialized.put("entities", entityList);
        int runningPulleys = countRunningElevatorPulleys(serialized);
        if (runningPulleys > 0 && elevators == 0 && elevatorRecovery != null) {
            for (int index = 0; index < elevatorRecovery.size(); index++) {
                CompoundTag candidate = elevatorRecovery.getCompound(index);
                CompoundTag entityData = candidate.getCompound("nbt");
                ResourceLocation entityId = ResourceLocation.tryParse(
                    entityData.getString("id")
                );
                if (entityId != null && isElevatorContraption(entityData, entityId)) {
                    entityList.add(candidate.copy());
                    elevators++;
                }
            }
            serialized.put("entities", entityList);
        }
        if (runningPulleys > 0 && elevators == 0) {
            throw new IllegalStateException(
                "작동 중인 Create 엘리베이터 풀리는 있지만 객실 엔티티가 없습니다. "
                    + "웹에서 원본 NBT를 현재 작업에 덮어쓴 뒤 다시 시도하세요."
            );
        }
        PlayingCardsEntityLinks.repairStructure(serialized);
        sanitizeCreateKineticNetworks(serialized);
        template.load(level.holderLookup(Registries.BLOCK), serialized);
        return template;
    }

    private static List<Entity> exportableEntities(
        ServerLevel level, BlockPos origin, Vec3i size
    ) {
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        AABB bounds = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            end.getX() + 1, end.getY() + 1, end.getZ() + 1
        );
        List<Entity> result = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ServerPlayer) continue;
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (isCreateContraption(id)) {
                CompoundTag entityData = entity.saveWithoutId(new CompoundTag());
                CompoundTag contraption = entityData.getCompound("Contraption");
                if (!contraption.getString("Type").equals("create:elevator")) {
                    if (entity.getBoundingBox().intersects(bounds)) {
                        throw new IllegalStateException(
                            "저장 범위에 엘리베이터가 아닌 Create Contraption이 있습니다. "
                                + "장치를 해체한 뒤 다시 저장하세요."
                        );
                    }
                    continue;
                }
                BlockPos pulley = entity.blockPosition().offset(
                    readNbtBlockPos(entityData, "ControllerRelative")
                );
                if (!inside(pulley, origin, end)) continue;
                if (!entity.getPassengers().isEmpty()) {
                    throw new IllegalStateException(
                        "엘리베이터에 승객이 있습니다. 모두 내린 뒤 다시 저장하세요."
                    );
                }
                validateElevatorBlocks(entity.blockPosition(), contraption, origin, end);
                ResourceLocation pulleyId = BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(pulley).getBlock()
                );
                if (!pulleyId.equals(ResourceLocation.fromNamespaceAndPath(
                    "create", "elevator_pulley"
                ))) {
                    throw new IllegalStateException(
                        "Create 엘리베이터의 풀리가 현재 NBT 저장 범위 안에 없습니다."
                    );
                }
                result.add(entity);
            } else if (entity.getBoundingBox().intersects(bounds)) {
                result.add(entity);
            }
        }
        return List.copyOf(result);
    }

    private static void validateElevatorBlocks(
        BlockPos anchor, CompoundTag contraption, BlockPos origin, BlockPos end
    ) {
        ListTag blocks = contraption.getCompound("Blocks")
            .getList("BlockList", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            BlockPos position = anchor.offset(BlockPos.of(
                blocks.getCompound(index).getLong("Pos")
            ));
            if (!inside(position, origin, end)) {
                throw new IllegalStateException(
                    "Create 엘리베이터 객실이 현재 NBT 저장 범위를 벗어납니다."
                );
            }
        }
    }

    private static BlockPos readNbtBlockPos(CompoundTag tag, String key) {
        if (tag.contains(key, Tag.TAG_COMPOUND)) {
            CompoundTag value = tag.getCompound(key);
            String x = value.contains("X", Tag.TAG_ANY_NUMERIC) ? "X" : "x";
            String y = value.contains("Y", Tag.TAG_ANY_NUMERIC) ? "Y" : "y";
            String z = value.contains("Z", Tag.TAG_ANY_NUMERIC) ? "Z" : "z";
            if (value.contains(x, Tag.TAG_ANY_NUMERIC)
                && value.contains(y, Tag.TAG_ANY_NUMERIC)
                && value.contains(z, Tag.TAG_ANY_NUMERIC)) {
                return new BlockPos(value.getInt(x), value.getInt(y), value.getInt(z));
            }
        }
        if (tag.contains(key, Tag.TAG_INT_ARRAY)) {
            int[] values = tag.getIntArray(key);
            if (values.length == 3) return new BlockPos(values[0], values[1], values[2]);
        }
        ListTag values = tag.getList(key, Tag.TAG_INT);
        if (values.size() == 3) {
            return new BlockPos(values.getInt(0), values.getInt(1), values.getInt(2));
        }
        if (tag.contains(key, Tag.TAG_LONG)) return BlockPos.of(tag.getLong(key));
        throw new IllegalStateException("Create 엘리베이터 컨트롤러 좌표를 읽을 수 없습니다.");
    }

    private static int countRunningElevatorPulleys(CompoundTag structure) {
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        int count = 0;
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag blockEntity = blocks.getCompound(index).getCompound("nbt");
            if (blockEntity.getString("id").equals("create:elevator_pulley")
                && blockEntity.getBoolean("Running")) count++;
        }
        return count;
    }

    private static void sanitizeCreateKineticNetworks(CompoundTag structure) {
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag blockEntity = blocks.getCompound(index).getCompound("nbt");
            if (!blockEntity.getString("id").equals("create:elevator_pulley")) continue;
            blockEntity.remove("Source");
            blockEntity.remove("Network");
            blockEntity.remove("Owner");
            blockEntity.remove("NeedsSpeedUpdate");
        }
    }

    private static boolean isCreateContraption(ResourceLocation id) {
        return id.getNamespace().equals("create") && id.getPath().contains("contraption");
    }

    private static boolean isElevatorContraption(
        CompoundTag entityData, ResourceLocation id
    ) {
        return isCreateContraption(id)
            && entityData.getCompound("Contraption").getString("Type")
                .equals("create:elevator");
    }

    private static boolean inside(BlockPos position, BlockPos origin, BlockPos end) {
        return position.getX() >= origin.getX() && position.getX() <= end.getX()
            && position.getY() >= origin.getY() && position.getY() <= end.getY()
            && position.getZ() >= origin.getZ() && position.getZ() <= end.getZ();
    }

    private static void syncPlacedBlockEntities(
        ServerLevel level, BlockPos origin, Vec3i size
    ) {
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) return;
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos position : BlockPos.betweenClosed(origin, end)) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null) continue;
            blockEntity.setChanged();
            BlockState state = level.getBlockState(position);
            level.sendBlockUpdated(position, state, state, 3);
        }
    }

    private static void repairExistingPlayingCardsTableOwners(
        ServerLevel level, ServerPlayer owner
    ) {
        if (active == null) return;
        var pokerTable = BuiltInRegistries.BLOCK.get(
            PlayingCardsTableOwnerProcessor.POKER_TABLE
        );
        if (!BuiltInRegistries.BLOCK.getKey(pokerTable)
            .equals(PlayingCardsTableOwnerProcessor.POKER_TABLE)) return;
        int repaired = 0;
        BlockPos end = ORIGIN.offset(
            active.size().getX() - 1,
            active.size().getY() - 1,
            active.size().getZ() - 1
        );
        for (BlockPos position : BlockPos.betweenClosed(ORIGIN, end)) {
            if (!level.getBlockState(position).is(pokerTable)) continue;
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null) continue;
            try {
                Object currentOwner = blockEntity.getClass().getMethod("getOwnerID")
                    .invoke(blockEntity);
                if (currentOwner != null) continue;
                blockEntity.getClass()
                    .getMethod("setOwner", net.minecraft.world.entity.player.Player.class)
                    .invoke(blockEntity, owner);
                blockEntity.setChanged();
                BlockState state = level.getBlockState(position);
                level.sendBlockUpdated(position, state, state, 3);
                repaired++;
            } catch (ReflectiveOperationException ignored) {}
        }
        if (repaired > 0) {
            owner.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 포커 테이블 카드 엔티티 " + repaired + "개를 복구했습니다."
            ));
        }
    }

    private static void clearBounds(ServerLevel level, Vec3i size) {
        for (int x = -1; x <= size.getX(); x++) {
            level.setBlock(ORIGIN.offset(x, -1, -1), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(x, -1, size.getZ()), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
        }
        for (int z = 0; z < size.getZ(); z++) {
            level.setBlock(ORIGIN.offset(-1, -1, z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(size.getX(), -1, z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
        }
    }

    private static void clearLoadedLegacyBounds(ServerLevel level) {
        for (int x = -1; x <= MAX_SIZE; x++) {
            for (int z = -1; z <= MAX_SIZE; z++) {
                BlockPos position = ORIGIN.offset(x, -1, z);
                if (!level.hasChunkAt(position)) continue;
                BlockState state = level.getBlockState(position);
                if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                    || state.is(Blocks.BLACK_CONCRETE) || state.is(Blocks.YELLOW_CONCRETE)) {
                    level.setBlock(position, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void prepareFloor(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos from = origin.offset(-FLOOR_MARGIN, -1, -FLOOR_MARGIN);
        BlockPos to = origin.offset(
            size.getX() + FLOOR_MARGIN,
            -1,
            size.getZ() + FLOOR_MARGIN
        );
        for (BlockPos position : BlockPos.betweenClosed(from, to)) {
            if (!level.getBlockState(position).is(Blocks.LIGHT_GRAY_CONCRETE)) {
                level.setBlock(position, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
            }
        }
    }

    private static void drawBounds(ServerLevel level, Vec3i size) {
        for (int x = -1; x <= size.getX(); x++) {
            setBorder(level, ORIGIN.offset(x, -1, -1));
            setBorder(level, ORIGIN.offset(x, -1, size.getZ()));
        }
        for (int z = 0; z < size.getZ(); z++) {
            setBorder(level, ORIGIN.offset(-1, -1, z));
            setBorder(level, ORIGIN.offset(size.getX(), -1, z));
        }
    }

    private static void setBorder(ServerLevel level, BlockPos position) {
        level.setBlock(position, ((position.getX() + position.getZ()) & 1) == 0
            ? Blocks.BLACK_CONCRETE.defaultBlockState()
            : Blocks.YELLOW_CONCRETE.defaultBlockState(), 2);
    }

    private static void teleportPlayer(MinecraftServer server, ServerPlayer player) {
        player.teleportTo(requireEditLevel(server), ORIGIN.getX() - 3.5D, ORIGIN.getY() + 1.0D,
            ORIGIN.getZ() - 3.5D, 45.0F, 0.0F);
    }

    private static ServerLevel requireEditLevel(MinecraftServer server) {
        ServerLevel level = server.getLevel(EDIT_LEVEL);
        if (level == null) throw new IllegalStateException("전용 편집 차원을 찾을 수 없습니다.");
        return level;
    }

    private static ServerLevel requireTestLevel(MinecraftServer server) {
        ServerLevel level = server.getLevel(TEST_LEVEL);
        if (level == null) throw new IllegalStateException("테스트 차원을 찾을 수 없습니다.");
        return level;
    }

    private static LiveState readState(MinecraftServer server) {
        Path path = Filesystem.liveRoot(server).resolve("state.json");
        if (!Files.isRegularFile(path)) return null;
        try {
            JsonObject value = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            return new LiveState(requiredString(value, "id"), requiredString(value, "source"),
                requiredString(value, "revision"), value.get("source_digest").getAsString(),
                readSize(value), readSize(value.getAsJsonArray("source_size")),
                value.has("test_placements") ? value.get("test_placements").getAsInt() : 0);
        } catch (Exception error) {
            return null;
        }
    }

    private static void writeState(MinecraftServer server) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", 1);
        value.addProperty("id", active.id());
        value.addProperty("source", active.source());
        value.addProperty("revision", active.revision());
        value.addProperty("source_digest", active.sourceDigest());
        value.add("size", sizeJson(active.size()));
        value.add("source_size", sizeJson(active.sourceSize()));
        value.addProperty("test_placements", active.testPlacements());
        writeJson(Filesystem.liveRoot(server).resolve("state.json"), value);
    }

    private static void writeError(MinecraftServer server, Exception error) {
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("status", "error");
        result.addProperty("message", error.getMessage() == null ? error.toString() : error.getMessage());
        writeJson(Filesystem.liveRoot(server).resolve("outbox/result.json"), result);
        announce(server, "동기화 실패: " + result.get("message").getAsString());
    }

    private static void writeJson(Path target, JsonObject value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling("." + target.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(value) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new IllegalStateException("상태 파일을 쓰지 못했습니다: " + target, error);
        }
    }

    static boolean hasActiveStructure() {
        return active != null;
    }

    static String activeStructureId() {
        return active == null ? "" : active.id();
    }

    static Vec3i activeStructureSize() {
        return active == null ? new Vec3i(0, 0, 0) : active.size();
    }

    static JsonObject activeStructureMetadata() {
        if (active == null) throw new IllegalStateException("먼저 웹에서 NBT를 여세요.");
        if (activeMetadata == null) activeMetadata = defaultMetadata(active);
        return activeMetadata;
    }

    static void editorMetadataChanged(MinecraftServer server) {
        writeDraftMetadata(server);
        announce(server, "앵커 메타데이터 변경: " + activeStructureId());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LiveEditorNetwork.sendSnapshot(player);
        }
    }

    static int selectWorldEditRegion(ServerPlayer player) {
        if (active == null) throw new IllegalStateException("먼저 웹에서 NBT를 여세요.");
        if (!player.serverLevel().dimension().equals(EDIT_LEVEL)) {
            throw new IllegalStateException("NBT 편집 차원에서만 영역을 선택할 수 있습니다.");
        }
        BlockPos start = ORIGIN;
        BlockPos end = start.offset(
            active.size().getX() - 1,
            active.size().getY() - 1,
            active.size().getZ() - 1
        );
        try {
            Class<?> adapterClass = Class.forName(
                "com.sk89q.worldedit.neoforge.NeoForgeAdapter"
            );
            Class<?> worldClass = Class.forName("com.sk89q.worldedit.world.World");
            Class<?> actorClass = Class.forName(
                "com.sk89q.worldedit.extension.platform.Actor"
            );
            Class<?> ownerClass = Class.forName(
                "com.sk89q.worldedit.session.SessionOwner"
            );
            Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Class<?> selectorClass = Class.forName(
                "com.sk89q.worldedit.regions.selector.CuboidRegionSelector"
            );
            Class<?> selectorInterface = Class.forName(
                "com.sk89q.worldedit.regions.RegionSelector"
            );
            Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
            Object actor = adapterClass.getMethod("adaptPlayer", ServerPlayer.class)
                .invoke(null, player);
            Object world = adapterClass.getMethod("adapt", ServerLevel.class)
                .invoke(null, player.serverLevel());
            Object minimum = vectorClass.getMethod(
                "at", int.class, int.class, int.class
            ).invoke(null, start.getX(), start.getY(), start.getZ());
            Object maximum = vectorClass.getMethod(
                "at", int.class, int.class, int.class
            ).invoke(null, end.getX(), end.getY(), end.getZ());
            Object selector = selectorClass
                .getConstructor(worldClass, vectorClass, vectorClass)
                .newInstance(world, minimum, maximum);
            Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
            Object sessionManager = worldEditClass.getMethod("getSessionManager")
                .invoke(worldEdit);
            Object session = sessionManager.getClass().getMethod("get", ownerClass)
                .invoke(sessionManager, actor);
            session.getClass().getMethod(
                "setRegionSelector", worldClass, selectorInterface
            ).invoke(session, world, selector);
            boolean selected = (boolean)session.getClass()
                .getMethod("isSelectionDefined", worldClass).invoke(session, world);
            if (!selected) {
                throw new IllegalStateException("WorldEdit 편집 세션에 영역이 등록되지 않았습니다.");
            }
            session.getClass().getMethod("dispatchCUISelection", actorClass)
                .invoke(session, actor);
        } catch (IllegalStateException error) {
            throw error;
        } catch (ReflectiveOperationException | LinkageError error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException(
                "WorldEdit 영역 선택 실패: " + cause.getMessage(), cause
            );
        }
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] WorldEdit 선택 완료: " + active.id() + " · "
                + start.toShortString() + " ~ " + end.toShortString()
        ));
        return 1;
    }

    private static JsonObject readActiveMetadata(MinecraftServer server, LiveState state) {
        Path liveRoot = Filesystem.liveRoot(server);
        Path draft = liveRoot.resolve("active.structure.json");
        return readMetadata(Files.isRegularFile(draft)
            ? draft : liveRoot.resolve("inbox/active.structure.json"), state);
    }

    private static JsonObject readMetadata(Path path, LiveState state) {
        if (Files.isRegularFile(path)) {
            try {
                JsonObject value = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)
                ).getAsJsonObject();
                if (value.has("structure")
                    && !state.source().equals(value.get("structure").getAsString())) {
                    throw new IllegalStateException(
                        "NBT와 마커 정보가 서로 다릅니다: " + state.source()
                            + " / " + value.get("structure").getAsString()
                    );
                }
                if (!value.has("schema_version")) value.addProperty("schema_version", 1);
                value.addProperty("structure", state.source());
                if (!value.has("anchors") || !value.get("anchors").isJsonArray()) {
                    value.add("anchors", new JsonArray());
                }
                return value;
            } catch (IllegalStateException error) {
                throw error;
            } catch (Exception ignored) {}
        }
        return defaultMetadata(state);
    }

    private static JsonObject defaultMetadata(LiveState state) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", 1);
        value.addProperty("structure", state.source());
        value.add("anchors", new JsonArray());
        return value;
    }

    private static void writeDraftMetadata(MinecraftServer server) {
        if (active == null || activeMetadata == null) return;
        writeJson(Filesystem.liveRoot(server).resolve("active.structure.json"), activeMetadata);
    }

    private static void writeSavedMetadata(MinecraftServer server) {
        if (active == null || activeMetadata == null) return;
        writeJson(Filesystem.liveRoot(server).resolve("outbox/active.structure.json"), activeMetadata);
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key)) throw new IllegalArgumentException(key + " 값이 없습니다.");
        return value.get(key).getAsString();
    }

    private static Vec3i readSize(JsonObject value) {
        return readSize(value.getAsJsonArray("size"));
    }

    private static Vec3i readSize(JsonArray value) {
        if (value == null || value.size() != 3) throw new IllegalArgumentException("size는 정수 3개여야 합니다.");
        Vec3i size = new Vec3i(value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt());
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1
            || size.getX() > MAX_SIZE || size.getY() > MAX_SIZE || size.getZ() > MAX_SIZE) {
            throw new IllegalArgumentException("size는 각 축 1~" + MAX_SIZE + "여야 합니다.");
        }
        return size;
    }

    private static JsonArray sizeJson(Vec3i size) {
        JsonArray value = new JsonArray();
        value.add(size.getX()); value.add(size.getY()); value.add(size.getZ());
        return value;
    }

    private static Vec3i max(Vec3i left, Vec3i right) {
        return new Vec3i(Math.max(left.getX(), right.getX()),
            Math.max(left.getY(), right.getY()), Math.max(left.getZ(), right.getZ()));
    }

    private static String format(Vec3i size) {
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", error);
        }
    }

    private static void announce(MinecraftServer server, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal("[Live NBT Editor] " + message));
        }
    }

    private record LiveState(String id, String source, String revision, String sourceDigest,
                             Vec3i size, Vec3i sourceSize, int testPlacements) {}

    private static final class Filesystem {
        static Path liveRoot(MinecraftServer server) {
            return server.getWorldPath(LevelResource.ROOT)
                .resolve("generated/cobbleventure_builder/live");
        }
        static void ensureLiveDirectories(MinecraftServer server) {
            try {
                Files.createDirectories(liveRoot(server).resolve("inbox"));
                Files.createDirectories(liveRoot(server).resolve("outbox"));
            } catch (IOException error) {
                throw new IllegalStateException("라이브 브리지 폴더를 만들지 못했습니다.", error);
            }
        }
    }
}
