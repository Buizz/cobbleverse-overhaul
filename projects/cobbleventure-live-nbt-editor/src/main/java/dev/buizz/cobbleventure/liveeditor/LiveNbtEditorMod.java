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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
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
    private static final ResourceKey<Level> EDIT_LEVEL = ResourceKey.create(
        Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "edit_world")
    );
    private static final ResourceKey<Level> TEST_LEVEL = ResourceKey.create(
        Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "test_world")
    );
    private static final BlockPos ORIGIN = new BlockPos(0, 65, 0);
    private static final int MAX_SIZE = 256;
    private static LiveState active;
    private static int pollTicks;
    private static int shutdownTicks = -1;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        server.setDefaultGameType(GameType.CREATIVE);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        configureLevel(server.overworld(), server);
        if (server.getLevel(EDIT_LEVEL) != null) configureLevel(server.getLevel(EDIT_LEVEL), server);
        if (server.getLevel(TEST_LEVEL) != null) configureLevel(server.getLevel(TEST_LEVEL), server);
        active = readState(server);
        pollTicks = 0;
        if (Boolean.getBoolean(PREPARE_PROPERTY)) {
            Filesystem.ensureLiveDirectories(server);
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
        if (active != null && event.getServer().getLevel(EDIT_LEVEL) != null) {
            try { saveActive(event.getServer(), "server-stop"); } catch (RuntimeException ignored) {}
        }
        active = null;
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
        if (active != null) teleportPlayer(server, player);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
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
        );
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
        if (!Files.isRegularFile(commandPath)) return;
        try {
            JsonObject command = JsonParser.parseString(
                Files.readString(commandPath, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            String action = requiredString(command, "action");
            String revision = requiredString(command, "revision");
            switch (action) {
                case "open" -> open(server, command, revision);
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

    private static void open(MinecraftServer server, JsonObject command, String revision)
        throws IOException {
        if (active != null && (!command.has("preserve_current")
            || command.get("preserve_current").getAsBoolean())) {
            saveActive(server, "before-" + revision);
        }
        ServerLevel level = requireEditLevel(server);
        Path input = Filesystem.liveRoot(server).resolve("inbox/active.nbt");
        CompoundTag serialized = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), serialized);
        Vec3i requested = readSize(command);
        clearSlot(level, active == null ? requested : max(active.size(), requested));
        boolean placed = template.placeInWorld(
            level, ORIGIN, ORIGIN, new StructurePlaceSettings(),
            RandomSource.create(level.getSeed() ^ ORIGIN.asLong()), 2
        );
        if (!placed) throw new IllegalStateException("NBT를 편집 차원에 배치하지 못했습니다.");
        active = new LiveState(
            requiredString(command, "id"), requiredString(command, "source"), revision,
            command.has("source_digest") ? command.get("source_digest").getAsString() : "",
            requested, template.getSize(), active == null ? 0 : active.testPlacements()
        );
        drawBounds(level, requested);
        writeState(server);
        copyMetadata(Filesystem.liveRoot(server).resolve("inbox/active.structure.json"),
            Filesystem.liveRoot(server).resolve("outbox/active.structure.json"));
        announce(server, "웹 변경 감지: " + active.id() + " · " + format(active.size()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) teleportPlayer(server, player);
    }

    private static void resize(MinecraftServer server, Vec3i size, String revision) {
        if (active == null) throw new IllegalStateException("크기를 바꿀 NBT가 없습니다.");
        ServerLevel level = requireEditLevel(server);
        clearBounds(level, active.size());
        active = new LiveState(active.id(), active.source(), revision, active.sourceDigest(),
            size, active.sourceSize(), active.testPlacements());
        drawBounds(level, size);
        writeState(server);
        announce(server, "편집 범위 변경: " + format(size));
    }

    private static void saveActive(MinecraftServer server, String revision) {
        if (active == null) throw new IllegalStateException("저장할 NBT가 없습니다.");
        ServerLevel level = requireEditLevel(server);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, ORIGIN, active.size(), true, Blocks.STRUCTURE_VOID);
        template.setAuthor("Cobbleventure Live NBT Editor");
        Path outbox = Filesystem.liveRoot(server).resolve("outbox");
        try {
            Files.createDirectories(outbox);
            Path temporary = outbox.resolve(".active.nbt.tmp");
            NbtIo.writeCompressed(template.save(new CompoundTag()), temporary);
            Files.move(temporary, outbox.resolve("active.nbt"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            JsonObject result = new JsonObject();
            result.addProperty("schema_version", 1);
            result.addProperty("status", "saved");
            result.addProperty("revision", revision);
            result.addProperty("id", active.id());
            result.addProperty("source", active.source());
            result.add("size", sizeJson(active.size()));
            writeJson(outbox.resolve("result.json"), result);
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
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(editLevel, ORIGIN, active.size(), true, Blocks.STRUCTURE_VOID);
        int index = active.testPlacements();
        BlockPos destination = new BlockPos((index % 5) * 320, 65, (index / 5) * 320);
        boolean placed = template.placeInWorld(
            testLevel, destination, destination, new StructurePlaceSettings(),
            RandomSource.create(testLevel.getSeed() ^ destination.asLong()), 2
        );
        if (!placed) throw new IllegalStateException("테스트 차원 배치에 실패했습니다.");
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

    private static void clearBounds(ServerLevel level, Vec3i size) {
        for (int x = -1; x <= size.getX(); x++) {
            level.setBlock(ORIGIN.offset(x, -1, -1), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(x, -1, size.getZ()), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        for (int z = 0; z < size.getZ(); z++) {
            level.setBlock(ORIGIN.offset(-1, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(size.getX(), -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
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

    private static void copyMetadata(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else Files.deleteIfExists(target);
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
