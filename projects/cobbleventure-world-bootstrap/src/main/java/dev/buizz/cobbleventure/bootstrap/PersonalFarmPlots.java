package dev.buizz.cobbleventure.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Protects curated adventure dimensions and provides one isolated farm chunk per player.
 */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID)
public final class PersonalFarmPlots {
    public static final ResourceKey<Level> FARM_PLOTS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "farm_plots")
    );

    private static final String DATA_FILE = "cobbleventure_personal_farm_plots";
    private static final String RETURN_PREFIX = "cobbleventureFarmReturn";
    private static final int PLOT_SPACING_CHUNKS = 32;
    private static final int PLOTS_PER_ROW = 1024;
    private static final int PLATFORM_BEDROCK_Y = 63;
    private static final int PLATFORM_DIRT_Y = 64;
    private static final int PLATFORM_SURFACE_Y = 65;
    private static final int ENTRY_Y = PLATFORM_SURFACE_Y + 1;

    private PersonalFarmPlots() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cobbleventure_farm")
                .executes(context -> enterFarm(context.getSource().getPlayerOrException()))
        );
        dispatcher.register(
            Commands.literal("cobbleventure_return")
                .executes(context -> returnFromFarm(context.getSource().getPlayerOrException()))
        );
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !isSurvival(player)) {
            return;
        }

        if (isProtectedAdventureDimension(player.level().dimension())
            || !mayEditFarmBlock(player, event.getPos())) {
            event.setCanceled(true);
            player.displayClientMessage(
                Component.literal("[Cobbleventure] 이곳의 블록은 변경할 수 없습니다."),
                true
            );
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isSurvival(player)) {
            return;
        }

        if (isProtectedAdventureDimension(player.level().dimension())
            || !mayEditFarmBlock(player, event.getPos())) {
            event.setCanceled(true);
            player.displayClientMessage(
                Component.literal("[Cobbleventure] 이곳에는 블록을 설치할 수 없습니다."),
                true
            );
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !isSurvival(player)
            || !player.level().dimension().equals(FARM_PLOTS)
            || player.tickCount % 10 != 0) {
            return;
        }

        ServerLevel level = player.serverLevel();
        ChunkPos ownedPlot = plotData(player).plotFor(player.getUUID());
        ChunkPos currentChunk = player.chunkPosition();
        if (!ownedPlot.equals(currentChunk) || player.getY() < PLATFORM_BEDROCK_Y) {
            BlockPos entry = entryPosition(ownedPlot);
            player.teleportTo(
                level,
                entry.getX() + 0.5D,
                entry.getY(),
                entry.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot()
            );
            player.displayClientMessage(
                Component.literal("[Cobbleventure] 자신의 개인 공간으로 돌아왔습니다."),
                true
            );
        }
    }

    private static int enterFarm(ServerPlayer player) {
        ServerLevel farm = player.getServer().getLevel(FARM_PLOTS);
        if (farm == null) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 개인 공간 차원을 불러오지 못했습니다."
            ));
            return 0;
        }
        if (player.level().dimension().equals(FARM_PLOTS)) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 이미 개인 공간에 있습니다."
            ));
            return 0;
        }

        rememberReturnPosition(player);
        PlotData data = plotData(player);
        ChunkPos plot = data.plotFor(player.getUUID());
        if (!data.isInitialized(player.getUUID())) {
            initializePlot(farm, plot);
            data.markInitialized(player.getUUID());
        }

        BlockPos entry = entryPosition(plot);
        player.teleportTo(
            farm,
            entry.getX() + 0.5D,
            entry.getY(),
            entry.getZ() + 0.5D,
            player.getYRot(),
            player.getXRot()
        );
        player.sendSystemMessage(Component.literal(
            "[Cobbleventure] 개인 1청크 공간으로 이동했습니다."
        ));
        return 1;
    }

    private static int returnFromFarm(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        String dimensionId = persistentData.getString(RETURN_PREFIX + "Dimension");
        if (dimensionId.isBlank()) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 저장된 귀환 위치가 없습니다."
            ));
            return 0;
        }

        ResourceLocation dimensionLocation = ResourceLocation.tryParse(dimensionId);
        if (dimensionLocation == null) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 저장된 귀환 차원이 올바르지 않습니다."
            ));
            return 0;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
        ServerLevel destination = player.getServer().getLevel(dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 저장된 귀환 차원을 불러오지 못했습니다."
            ));
            return 0;
        }

        player.teleportTo(
            destination,
            persistentData.getDouble(RETURN_PREFIX + "X"),
            persistentData.getDouble(RETURN_PREFIX + "Y"),
            persistentData.getDouble(RETURN_PREFIX + "Z"),
            persistentData.getFloat(RETURN_PREFIX + "Yaw"),
            persistentData.getFloat(RETURN_PREFIX + "Pitch")
        );
        clearReturnPosition(persistentData);
        player.sendSystemMessage(Component.literal("[Cobbleventure] 이전 위치로 돌아왔습니다."));
        return 1;
    }

    private static boolean isSurvival(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    private static boolean isProtectedAdventureDimension(ResourceKey<Level> dimension) {
        ResourceLocation location = dimension.location();
        if (!location.getNamespace().equals("cobbleventure")) {
            return false;
        }
        String path = location.getPath();
        return path.startsWith("generation_")
            || path.equals("dungeons")
            || path.equals("forests");
    }

    private static boolean mayEditFarmBlock(ServerPlayer player, BlockPos pos) {
        if (!player.level().dimension().equals(FARM_PLOTS)) {
            return true;
        }
        return plotData(player).plotFor(player.getUUID()).equals(new ChunkPos(pos));
    }

    private static PlotData plotData(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(PlotData::new, PlotData::load),
            DATA_FILE
        );
    }

    private static void initializePlot(ServerLevel level, ChunkPos plot) {
        level.getChunk(plot.x, plot.z);
        int minX = plot.getMinBlockX();
        int minZ = plot.getMinBlockZ();
        for (int x = minX; x <= plot.getMaxBlockX(); x++) {
            for (int z = minZ; z <= plot.getMaxBlockZ(); z++) {
                level.setBlockAndUpdate(new BlockPos(x, PLATFORM_BEDROCK_Y, z), Blocks.BEDROCK.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, PLATFORM_DIRT_Y, z), Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, PLATFORM_SURFACE_Y, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
    }

    private static BlockPos entryPosition(ChunkPos plot) {
        return new BlockPos(plot.getMiddleBlockX(), ENTRY_Y, plot.getMiddleBlockZ());
    }

    private static void rememberReturnPosition(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.putString(
            RETURN_PREFIX + "Dimension",
            player.level().dimension().location().toString()
        );
        persistentData.putDouble(RETURN_PREFIX + "X", player.getX());
        persistentData.putDouble(RETURN_PREFIX + "Y", player.getY());
        persistentData.putDouble(RETURN_PREFIX + "Z", player.getZ());
        persistentData.putFloat(RETURN_PREFIX + "Yaw", player.getYRot());
        persistentData.putFloat(RETURN_PREFIX + "Pitch", player.getXRot());
    }

    private static void clearReturnPosition(CompoundTag persistentData) {
        persistentData.remove(RETURN_PREFIX + "Dimension");
        persistentData.remove(RETURN_PREFIX + "X");
        persistentData.remove(RETURN_PREFIX + "Y");
        persistentData.remove(RETURN_PREFIX + "Z");
        persistentData.remove(RETURN_PREFIX + "Yaw");
        persistentData.remove(RETURN_PREFIX + "Pitch");
    }

    static final class PlotData extends SavedData {
        private final Map<UUID, PlotRecord> plots = new HashMap<>();
        private int nextIndex;

        static PlotData load(CompoundTag tag, HolderLookup.Provider registries) {
            PlotData data = new PlotData();
            data.nextIndex = tag.getInt("nextIndex");
            ListTag entries = tag.getList("plots", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                if (!entry.hasUUID("owner")) {
                    continue;
                }
                data.plots.put(
                    entry.getUUID("owner"),
                    new PlotRecord(entry.getInt("index"), entry.getBoolean("initialized"))
                );
            }
            return data;
        }

        ChunkPos plotFor(UUID owner) {
            PlotRecord existing = plots.get(owner);
            if (existing != null) {
                return chunkForIndex(existing.index());
            }

            int index = nextIndex++;
            plots.put(owner, new PlotRecord(index, false));
            setDirty();
            return chunkForIndex(index);
        }

        boolean isInitialized(UUID owner) {
            PlotRecord record = plots.get(owner);
            return record != null && record.initialized();
        }

        void markInitialized(UUID owner) {
            PlotRecord record = plots.get(owner);
            if (record != null && !record.initialized()) {
                plots.put(owner, new PlotRecord(record.index(), true));
                setDirty();
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("nextIndex", nextIndex);
            ListTag entries = new ListTag();
            for (Map.Entry<UUID, PlotRecord> plot : plots.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("owner", plot.getKey());
                entry.putInt("index", plot.getValue().index());
                entry.putBoolean("initialized", plot.getValue().initialized());
                entries.add(entry);
            }
            tag.put("plots", entries);
            return tag;
        }

        private static ChunkPos chunkForIndex(int index) {
            int gridX = index % PLOTS_PER_ROW;
            int gridZ = index / PLOTS_PER_ROW;
            return new ChunkPos(gridX * PLOT_SPACING_CHUNKS, gridZ * PLOT_SPACING_CHUNKS);
        }
    }

    private record PlotRecord(int index, boolean initialized) {
    }
}
