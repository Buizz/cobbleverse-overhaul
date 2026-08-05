package dev.buizz.cobbleventure.bootstrap;

import com.mojang.datafixers.util.Pair;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@Mod(CobbleventureBootstrap.MOD_ID)
public final class CobbleventureBootstrap {
    public static final String MOD_ID = "cobbleventure_bootstrap";
    private static final String DATA_FILE = "cobbleventure_world_bootstrap";
    private static final int SEARCH_RADIUS = 8192;
    private static final int HORIZONTAL_STEP = 32;
    private static final int VERTICAL_STEP = 64;
    private static final int VILLAGE_OFFSET = 32;
    private static final TagKey<Biome> STARTER_BIOMES = TagKey.create(
        Registries.BIOME,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "starter_biomes")
    );

    public CobbleventureBootstrap(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedIn);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ServerLevel overworld = player.serverLevel().getServer().overworld();
        BootstrapSavedData data = overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (data.isComplete()) {
            return;
        }

        player.sendSystemMessage(Component.literal("[Cobbleventure] 시작용 평원을 찾고 있습니다..."));
        if (!initializeWorld(overworld, player, data)) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 반경 " + SEARCH_RADIUS + "블록 안에서 시작용 평원을 찾지 못했습니다."
            ));
        }
    }

    private static boolean initializeWorld(
        ServerLevel level,
        ServerPlayer firstPlayer,
        BootstrapSavedData data
    ) {
        Predicate<Holder<Biome>> acceptedBiome = biome -> biome.is(STARTER_BIOMES);
        Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(
            acceptedBiome,
            level.getSharedSpawnPos(),
            SEARCH_RADIUS,
            HORIZONTAL_STEP,
            VERTICAL_STEP
        );
        if (result == null) {
            return false;
        }

        BlockPos biomePos = result.getFirst();
        BlockPos spawnPos = surfacePosition(level, biomePos.getX(), biomePos.getZ());
        BlockPos villagePos = surfacePosition(
            level,
            spawnPos.getX() + VILLAGE_OFFSET,
            spawnPos.getZ() + VILLAGE_OFFSET
        );

        level.getChunk(spawnPos);
        level.getChunk(villagePos);
        level.setDefaultSpawnPos(spawnPos, 0.0F);
        firstPlayer.teleportTo(
            spawnPos.getX() + 0.5D,
            spawnPos.getY() + 1.0D,
            spawnPos.getZ() + 0.5D
        );

        int placed;
        try {
            placed = level.getServer().getCommands().getDispatcher().execute(
                "place structure cobbleventure:starter_town/village ~ ~ ~",
                level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(Vec3.atLowerCornerOf(villagePos))
                .withPermission(4)
                .withSuppressedOutput()
            );
        } catch (CommandSyntaxException error) {
            placed = 0;
        }
        if (placed == 0) {
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 평원 스폰은 지정했지만 시작 마을 배치에 실패했습니다."
            ));
            return false;
        }

        data.complete(spawnPos, villagePos);
        firstPlayer.sendSystemMessage(Component.literal(
            "[Cobbleventure] 평원에 시작 지점과 체육관 마을을 생성했습니다."
        ));
        return true;
    }

    private static BlockPos surfacePosition(ServerLevel level, int x, int z) {
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
