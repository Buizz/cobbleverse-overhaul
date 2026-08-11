package dev.buizz.cobbleventure.structurebuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(StructureBuilderMod.MOD_ID)
@EventBusSubscriber(modid = StructureBuilderMod.MOD_ID)
public final class StructureBuilderMod {
    public static final String MOD_ID = "cobbleventure_structure_builder";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREPARE_PROPERTY = "cobbleventure.builder.prepareWorld";
    private static final String DATA_FILE = "cobbleventure_structure_builder";
    private static final ResourceLocation CATALOG = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure_builder", "structure_builder/catalog.json"
    );
    private static final int ORIGIN_X = -320;
    private static final int ORIGIN_Z = -280;
    private static int shutdownTicks = -1;

    public StructureBuilderMod() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean(PREPARE_PROPERTY)) {
            return;
        }
        configureWorld(event.getServer());
        event.getServer().saveEverything(true, true, true);
        shutdownTicks = 20;
        LOGGER.info("Blank Cobbleventure structure builder world is ready for packaging");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (shutdownTicks < 0) {
            return;
        }
        if (--shutdownTicks == 0) {
            event.getServer().saveEverything(true, true, true);
            event.getServer().halt(false);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        shutdownTicks = -1;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || player.serverLevel() != server.overworld()) {
            return;
        }
        configureWorld(server);
        player.setGameMode(GameType.CREATIVE);
        try {
            Catalog catalog = loadCatalog(server);
            BuilderData data = data(server);
            if (!data.prepared) {
                prepareLayout(server.overworld(), catalog, data);
                player.sendSystemMessage(Component.literal(
                    "[Structure Builder] 구조물 " + catalog.entries().size()
                        + "개를 건축 부지에 배치했습니다."
                ));
            } else if (!data.catalogHash.equals(catalog.catalogHash())) {
                player.sendSystemMessage(Component.literal(
                    "[Structure Builder] 원본 NBT가 변경되었습니다. 편집 내용을 보존했으며 "
                        + "필요할 때 /cobbleventure_builder load confirm을 사용하세요."
                ));
            }
            BlockPos spawn = spawnPosition(data.groundY);
            player.teleportTo(
                server.overworld(), spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                0.0F, 0.0F
            );
        } catch (BuilderException error) {
            player.sendSystemMessage(Component.literal(
                "[Structure Builder] 초기화 실패: " + error.getMessage()
            ));
            LOGGER.error("Structure builder initialization failed", error);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cobbleventure_builder")
                .then(Commands.literal("status")
                    .executes(context -> status(context.getSource())))
                .then(Commands.literal("load")
                    .then(Commands.literal("confirm")
                        .executes(context -> loadAll(context.getSource()))))
                .then(Commands.literal("save")
                    .then(Commands.literal("all")
                        .executes(context -> saveAll(context.getSource())))
                    .then(Commands.argument("structure", StringArgumentType.greedyString())
                        .executes(context -> saveOne(
                            context.getSource(),
                            StringArgumentType.getString(context, "structure")
                        ))))
                .then(Commands.literal("tp")
                    .then(Commands.argument("structure", StringArgumentType.greedyString())
                        .executes(context -> teleportToPlot(
                            context.getSource(),
                            StringArgumentType.getString(context, "structure")
                        ))))
        );
    }

    private static int status(CommandSourceStack source) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] 구조물=" + catalog.entries().size()
                        + ", 배치=" + data.prepared
                        + ", 카탈로그="
                        + (data.catalogHash.equals(catalog.catalogHash()) ? "최신" : "변경됨")
                ),
                false
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int loadAll(CommandSourceStack source) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            prepareLayout(source.getServer().overworld(), catalog, data);
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] 원본 NBT로 모든 부지를 다시 불러왔습니다."
                ),
                true
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int saveAll(CommandSourceStack source) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            requirePrepared(data);
            int saved = 0;
            for (PlannedEntry planned : plan(catalog, data.groundY)) {
                export(source.getServer().overworld(), planned);
                saved++;
            }
            int count = saved;
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] " + count + "개 부지를 NBT로 내보냈습니다."
                ),
                true
            );
            return saved;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int saveOne(CommandSourceStack source, String requested) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            requirePrepared(data);
            PlannedEntry planned = find(catalog, data.groundY, requested);
            export(source.getServer().overworld(), planned);
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] NBT 내보내기 완료: " + planned.entry().source()
                ),
                true
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int teleportToPlot(
        CommandSourceStack source, String requested
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            requirePrepared(data);
            PlannedEntry planned = find(catalog, data.groundY, requested);
            player.teleportTo(
                source.getServer().overworld(),
                planned.cellX() + catalog.cellSize() / 2.0D,
                data.groundY + 1.0D,
                planned.cellZ() + 4.5D,
                180.0F, 0.0F
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int fail(CommandSourceStack source, BuilderException error) {
        source.sendFailure(Component.literal("[Structure Builder] " + error.getMessage()));
        return 0;
    }

    private static void requirePrepared(BuilderData data) {
        if (!data.prepared) {
            throw new BuilderException("건축 부지가 아직 생성되지 않았습니다.");
        }
    }

    private static PlannedEntry find(Catalog catalog, int groundY, String requested) {
        return plan(catalog, groundY).stream()
            .filter(planned -> planned.entry().label().equals(requested)
                || planned.entry().source().equals(requested)
                || planned.entry().exportId().equals(requested))
            .findFirst()
            .orElseThrow(() -> new BuilderException(
                "구조물을 찾을 수 없습니다: " + requested
            ));
    }

    private static void configureWorld(MinecraftServer server) {
        server.setDefaultGameType(GameType.CREATIVE);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        ServerLevel level = server.overworld();
        level.setDayTime(6000L);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
    }

    private static void prepareLayout(
        ServerLevel level, Catalog catalog, BuilderData data
    ) {
        int groundY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0
        ) - 1;
        int rows = catalog.rows();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < catalog.columns(); column++) {
                outlineCell(
                    level,
                    ORIGIN_X + column * catalog.cellSize(),
                    ORIGIN_Z + row * catalog.cellSize(),
                    groundY,
                    catalog.cellSize(),
                    (row + column) % 2 == 0
                );
            }
        }
        for (PlannedEntry planned : plan(catalog, groundY)) {
            placeSource(level, planned);
        }
        data.complete(catalog.catalogHash(), groundY);
        BlockPos spawn = spawnPosition(groundY);
        level.setDefaultSpawnPos(spawn, 0.0F);
        level.getServer().saveEverything(true, true, true);
    }

    private static void placeSource(ServerLevel level, PlannedEntry planned) {
        ResourceLocation id = ResourceLocation.parse(planned.entry().structureId());
        var template = level.getStructureManager().get(id).orElseThrow(
            () -> new BuilderException("패키징된 원본 NBT가 없습니다: " + id)
        );
        Vec3i actual = template.getSize();
        Vec3i expected = planned.entry().size();
        if (!actual.equals(expected)) {
            throw new BuilderException(
                "카탈로그와 NBT 크기가 다릅니다: " + id
                    + " (카탈로그 " + expected + ", NBT " + actual + ")"
            );
        }
        loadChunks(level, planned.origin(), expected);
        boolean placed = template.placeInWorld(
            level, planned.origin(), planned.origin(), new StructurePlaceSettings(),
            RandomSource.create(level.getSeed() ^ planned.origin().asLong()), 2
        );
        if (!placed) {
            throw new BuilderException("NBT 배치에 실패했습니다: " + id);
        }
        placeLabel(level, planned);
    }

    private static void export(ServerLevel level, PlannedEntry planned) {
        ResourceLocation exportId = ResourceLocation.parse(planned.entry().exportId());
        var manager = level.getStructureManager();
        var template = manager.getOrCreate(exportId);
        template.fillFromWorld(
            level, planned.origin(), planned.entry().size(), false, Blocks.STRUCTURE_VOID
        );
        template.setAuthor("Cobbleventure Structure Builder");
        if (!manager.save(exportId)) {
            throw new BuilderException("NBT 파일 저장에 실패했습니다: " + exportId);
        }
    }

    private static List<PlannedEntry> plan(Catalog catalog, int groundY) {
        List<PlannedEntry> result = new ArrayList<>(catalog.entries().size());
        int entryIndex = 0;
        for (int row = 0; row < catalog.rows() && entryIndex < catalog.entries().size(); row++) {
            for (int column = 0; column < catalog.columns(); column++) {
                if ((row + column) % 2 != 0) {
                    continue;
                }
                Entry entry = catalog.entries().get(entryIndex++);
                int cellX = ORIGIN_X + column * catalog.cellSize();
                int cellZ = ORIGIN_Z + row * catalog.cellSize();
                result.add(new PlannedEntry(
                    entry, row, column, cellX, cellZ,
                    new BlockPos(
                        cellX + (catalog.cellSize() - entry.size().getX()) / 2,
                        groundY + 1,
                        cellZ + (catalog.cellSize() - entry.size().getZ()) / 2
                    )
                ));
            }
        }
        return List.copyOf(result);
    }

    private static void outlineCell(
        ServerLevel level, int cellX, int cellZ, int groundY,
        int cellSize, boolean occupied
    ) {
        var border = occupied
            ? Blocks.BLACK_CONCRETE.defaultBlockState()
            : Blocks.WHITE_CONCRETE.defaultBlockState();
        for (int offset = 0; offset < cellSize; offset++) {
            level.setBlock(new BlockPos(cellX + offset, groundY, cellZ), border, 2);
            level.setBlock(
                new BlockPos(cellX + offset, groundY, cellZ + cellSize - 1), border, 2
            );
            level.setBlock(new BlockPos(cellX, groundY, cellZ + offset), border, 2);
            level.setBlock(
                new BlockPos(cellX + cellSize - 1, groundY, cellZ + offset), border, 2
            );
        }
    }

    private static void placeLabel(ServerLevel level, PlannedEntry planned) {
        BlockPos signPosition = new BlockPos(
            planned.cellX() + 40,
            planned.origin().getY(),
            planned.origin().getZ() - 3
        );
        level.setBlock(signPosition, Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(signPosition) instanceof SignBlockEntity sign) {
            Vec3i size = planned.entry().size();
            SignText text = sign.getFrontText()
                .setMessage(0, Component.literal(planned.entry().label()))
                .setMessage(1, Component.literal(
                    size.getX() + "x" + size.getY() + "x" + size.getZ()
                ))
                .setMessage(2, Component.literal(planned.entry().category()))
                .setMessage(3, Component.literal(
                    "row " + planned.row() + " / col " + planned.column()
                ));
            sign.setText(text, true);
            sign.setChanged();
        }
    }

    private static void loadChunks(ServerLevel level, BlockPos origin, Vec3i size) {
        int minChunkX = origin.getX() >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkX = (origin.getX() + size.getX() - 1) >> 4;
        int maxChunkZ = (origin.getZ() + size.getZ() - 1) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static BlockPos spawnPosition(int groundY) {
        return new BlockPos(0, groundY + 1, ORIGIN_Z - 20);
    }

    private static Catalog loadCatalog(MinecraftServer server) {
        Optional<Resource> resource = server.getResourceManager().getResource(CATALOG);
        if (resource.isEmpty()) {
            throw new BuilderException("구조물 카탈로그가 없습니다: " + CATALOG);
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int schemaVersion = root.get("schema_version").getAsInt();
            if (schemaVersion != 1) {
                throw new BuilderException("지원하지 않는 카탈로그 버전: " + schemaVersion);
            }
            int columns = root.get("columns").getAsInt();
            int cellSize = root.get("cell_size").getAsInt();
            String catalogHash = root.get("catalog_hash").getAsString();
            JsonArray values = root.getAsJsonArray("entries");
            List<Entry> entries = new ArrayList<>(values.size());
            Map<String, Boolean> ids = new LinkedHashMap<>();
            for (JsonElement value : values) {
                JsonObject entry = value.getAsJsonObject();
                JsonArray size = entry.getAsJsonArray("size");
                Entry parsed = new Entry(
                    entry.get("source").getAsString(),
                    entry.get("structure").getAsString(),
                    entry.get("export").getAsString(),
                    entry.get("label").getAsString(),
                    entry.get("category").getAsString(),
                    new Vec3i(
                        size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt()
                    )
                );
                if (ids.put(parsed.exportId(), true) != null) {
                    throw new BuilderException("중복 내보내기 ID: " + parsed.exportId());
                }
                entries.add(parsed);
            }
            return new Catalog(catalogHash, columns, cellSize, List.copyOf(entries));
        } catch (IOException | RuntimeException error) {
            if (error instanceof BuilderException builderError) {
                throw builderError;
            }
            throw new BuilderException("구조물 카탈로그를 읽지 못했습니다.", error);
        }
    }

    private static BuilderData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BuilderData::new, BuilderData::load), DATA_FILE
        );
    }

    private record Catalog(
        String catalogHash, int columns, int cellSize, List<Entry> entries
    ) {
        int rows() {
            return (entries.size() * 2 + columns - 1) / columns;
        }
    }

    private record Entry(
        String source, String structureId, String exportId,
        String label, String category, Vec3i size
    ) {
    }

    private record PlannedEntry(
        Entry entry, int row, int column, int cellX, int cellZ, BlockPos origin
    ) {
    }

    private static final class BuilderData extends SavedData {
        private boolean prepared;
        private String catalogHash = "";
        private int groundY;

        static BuilderData load(CompoundTag tag, HolderLookup.Provider registries) {
            BuilderData data = new BuilderData();
            data.prepared = tag.getBoolean("prepared");
            data.catalogHash = tag.getString("catalogHash");
            data.groundY = tag.getInt("groundY");
            return data;
        }

        void complete(String hash, int groundY) {
            this.prepared = true;
            this.catalogHash = hash;
            this.groundY = groundY;
            setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("prepared", prepared);
            tag.putString("catalogHash", catalogHash);
            tag.putInt("groundY", groundY);
            return tag;
        }
    }

    private static final class BuilderException extends RuntimeException {
        BuilderException(String message) {
            super(message);
        }

        BuilderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
