package dev.buizz.cobbleventure.bootstrap;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(CobbleventureBootstrap.MOD_ID)
public final class CobbleventureBootstrap {
    public static final String MOD_ID = "cobbleventure_bootstrap";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_world_bootstrap";
    private static final int VILLAGE_OFFSET = 32;
    private static final int VILLAGE_CHUNK_RADIUS = 9;
    private static final int EXPECTED_SURFACE_Y = 69;
    private static final String INTEGRATION_TEST_PROPERTY = "cobbleventure.testStarterTown";
    private static final String PLAYER_STARTED = "cobbleventureGenerationOneStarted";
    private static final ResourceKey<Level> GENERATION_ONE =
        ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "generation_1")
        );
    private static final ResourceKey<net.minecraft.world.level.biome.Biome> STARTER_BIOME =
        ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "starter_plains")
        );

    public CobbleventureBootstrap(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            throw new IllegalStateException("Cobbleventure generation_1 dimension is missing");
        }

        BlockPos surface = surfacePosition(level, 0, 0);
        if (!level.getBiome(surface).is(STARTER_BIOME)) {
            throw new IllegalStateException("Cobbleventure starter_plains biome is missing at spawn");
        }
        if (surface.getY() != EXPECTED_SURFACE_Y) {
            throw new IllegalStateException(
                "Cobbleventure generation_1 surface height must be "
                    + EXPECTED_SURFACE_Y + ", but was " + surface.getY()
            );
        }
        if (!level.getBlockState(new BlockPos(0, 68, 0)).is(Blocks.GRASS_BLOCK)
            || !level.getBlockState(new BlockPos(0, 64, 0)).is(Blocks.BEDROCK)
            || !level.getBlockState(new BlockPos(0, 63, 0)).isAir()) {
            throw new IllegalStateException(
                "Cobbleventure generation_1 must have grass over bedrock with empty space below"
            );
        }
        LOGGER.info(
            "Cobbleventure generation_1 ready: biome={}, surfaceY={}",
            STARTER_BIOME.location(),
            surface.getY()
        );

        if (Boolean.getBoolean(INTEGRATION_TEST_PROPERTY)) {
            BlockPos villagePos = surfacePosition(level, VILLAGE_OFFSET, VILLAGE_OFFSET);
            if (!placeStarterTown(level, villagePos)) {
                throw new IllegalStateException("Cobbleventure starter town integration placement failed");
            }
            LOGGER.info("Cobbleventure starter town integration placement succeeded at {}", villagePos);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ServerLevel overworld = player.getServer().overworld();
        ServerLevel generationOne = player.getServer().getLevel(GENERATION_ONE);
        if (generationOne == null) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] generation_1 전용 차원을 불러오지 못했습니다."
            ));
            return;
        }

        BootstrapSavedData data = overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (!data.isComplete()) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 전용 시작 바이옴과 마을을 준비하고 있습니다..."
            ));
            if (!initializeWorld(generationOne, player, data)) {
                return;
            }
        }

        if (!player.getPersistentData().getBoolean(PLAYER_STARTED)) {
            movePlayerToStart(player, generationOne, data.spawnPos());
        }
    }

    private static boolean initializeWorld(
        ServerLevel level,
        ServerPlayer firstPlayer,
        BootstrapSavedData data
    ) {
        BlockPos spawnPos = surfacePosition(level, 0, 0);
        BlockPos villagePos = surfacePosition(
            level,
            spawnPos.getX() + VILLAGE_OFFSET,
            spawnPos.getZ() + VILLAGE_OFFSET
        );

        level.getChunk(spawnPos);
        level.getChunk(villagePos);
        level.setDefaultSpawnPos(spawnPos, 0.0F);
        if (!placeStarterTown(level, villagePos)) {
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 전용 시작 차원은 생성했지만 시작 마을 배치에 실패했습니다."
            ));
            return false;
        }

        data.complete(spawnPos, villagePos);
        firstPlayer.sendSystemMessage(Component.literal(
            "[Cobbleventure] 전용 시작 바이옴에 체육관 마을을 생성했습니다."
        ));
        return true;
    }

    private static boolean placeStarterTown(ServerLevel level, BlockPos villagePos) {
        loadChunkSquare(level, villagePos, VILLAGE_CHUNK_RADIUS);
        try {
            int placed = level.getServer().getCommands().getDispatcher().execute(
                "place structure cobbleventure:starter_town/village ~ ~ ~",
                level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(Vec3.atLowerCornerOf(villagePos))
                .withPermission(4)
                .withSuppressedOutput()
            );
            if (placed != 0) {
                return true;
            }
        } catch (CommandSyntaxException error) {
            LOGGER.error(
                "Starter town command failed in {} at {} (biome={}): {}",
                level.dimension().location(),
                villagePos,
                level.getBiome(villagePos).unwrapKey().map(ResourceKey::location).orElse(null),
                error.getRawMessage().getString()
            );
            return false;
        }
        LOGGER.error(
            "Starter town placement returned 0 in {} at {} (biome={})",
            level.dimension().location(),
            villagePos,
            level.getBiome(villagePos).unwrapKey().map(ResourceKey::location).orElse(null)
        );
        return false;
    }

    private static void loadChunkSquare(ServerLevel level, BlockPos center, int radius) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void movePlayerToStart(
        ServerPlayer player,
        ServerLevel level,
        BlockPos spawnPos
    ) {
        player.teleportTo(
            level,
            spawnPos.getX() + 0.5D,
            spawnPos.getY() + 1.0D,
            spawnPos.getZ() + 0.5D,
            0.0F,
            0.0F
        );
        player.setRespawnPosition(GENERATION_ONE, spawnPos, 0.0F, true, false);
        player.getPersistentData().putBoolean(PLAYER_STARTED, true);
    }

    private static BlockPos surfacePosition(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    static final class BootstrapSavedData extends SavedData {
        private boolean complete;
        private BlockPos spawnPos = BlockPos.ZERO;
        private BlockPos villagePos = BlockPos.ZERO;

        static BootstrapSavedData create() {
            return new BootstrapSavedData();
        }

        static BootstrapSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
            BootstrapSavedData data = create();
            data.complete = tag.getBoolean("complete");
            data.spawnPos = new BlockPos(tag.getInt("spawnX"), tag.getInt("spawnY"), tag.getInt("spawnZ"));
            data.villagePos = new BlockPos(
                tag.getInt("villageX"),
                tag.getInt("villageY"),
                tag.getInt("villageZ")
            );
            return data;
        }

        boolean isComplete() {
            return complete;
        }

        BlockPos spawnPos() {
            return spawnPos;
        }

        void complete(BlockPos spawnPos, BlockPos villagePos) {
            this.complete = true;
            this.spawnPos = spawnPos.immutable();
            this.villagePos = villagePos.immutable();
            setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("complete", complete);
            tag.putInt("spawnX", spawnPos.getX());
            tag.putInt("spawnY", spawnPos.getY());
            tag.putInt("spawnZ", spawnPos.getZ());
            tag.putInt("villageX", villagePos.getX());
            tag.putInt("villageY", villagePos.getY());
            tag.putInt("villageZ", villagePos.getZ());
            return tag;
        }
    }
}
