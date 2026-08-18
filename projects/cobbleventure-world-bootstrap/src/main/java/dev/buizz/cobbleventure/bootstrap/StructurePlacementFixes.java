package dev.buizz.cobbleventure.bootstrap;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Repairs modded block state that can be disturbed while a structure template is placed. */
final class StructurePlacementFixes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, ResourceLocation> FRIDGE_LOWER_BY_UPPER = Map.of(
        id("cobblefurnies", "light_freezer"), id("cobblefurnies", "light_fridge"),
        id("cobblefurnies", "dark_freezer"), id("cobblefurnies", "dark_fridge")
    );
    private static final Map<ResourceLocation, ResourceLocation> FRIDGE_UPPER_BY_LOWER = Map.of(
        id("cobblefurnies", "light_fridge"), id("cobblefurnies", "light_freezer"),
        id("cobblefurnies", "dark_fridge"), id("cobblefurnies", "dark_freezer")
    );
    private StructurePlacementFixes() {
    }

    static void afterPlacement(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        Vec3i size = template.getSize(settings.getRotation());
        repairFridges(level, origin, size);
        restoreCopycatMaterials(level, origin, template, settings);
        synchronizeBlockEntities(level, origin, size);
    }

    /**
     * Structure placement can leave Create and Copycats+ block entities with their default
     * material even though the source template still contains the authored material NBT.
     * Reapply that source data before the final update packet is sent to clients.
     */
    private static void restoreCopycatMaterials(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        int restored = 0;
        // Resolve this after registries have finished loading. Keeping this list in a static
        // field can capture only Create's blocks when this helper is initialized before
        // Copycats+ finishes registering its multi-state blocks.
        for (Block copycatBlock : copycatBlocks()) {
            for (StructureTemplate.StructureBlockInfo info
                : template.filterBlocks(origin, settings, copycatBlock)) {
                CompoundTag sourceData = info.nbt();
                if (sourceData == null) {
                    continue;
                }
                BlockState state = level.getBlockState(info.pos());
                if (!state.is(copycatBlock)) {
                    continue;
                }
                try {
                    // Copycats+ multi-state entities keep per-part material/model caches.
                    // Loading NBT into the empty instance made by StructureTemplate can leave
                    // those caches on the black copycat base. Recreate the entity from the
                    // authored NBT so its storage is initialized before the data is read.
                    BlockEntity restoredEntity = BlockEntity.loadStatic(
                        info.pos(), state, sourceData.copy(), level.registryAccess()
                    );
                    if (restoredEntity == null) {
                        LOGGER.warn(
                            "Copycat material NBT could not create a block entity at {} for {}",
                            info.pos(), BuiltInRegistries.BLOCK.getKey(copycatBlock)
                        );
                        continue;
                    }
                    level.removeBlockEntity(info.pos());
                    level.setBlockEntity(restoredEntity);
                    restoredEntity.setChanged();
                    level.sendBlockUpdated(info.pos(), state, state, 16);
                    level.getChunkSource().blockChanged(info.pos());
                    restored++;
                } catch (RuntimeException error) {
                    LOGGER.warn(
                        "Failed to restore copycat material at {} for {}",
                        info.pos(), BuiltInRegistries.BLOCK.getKey(copycatBlock), error
                    );
                }
            }
        }
        if (restored > 0) {
            LOGGER.debug("Restored {} copycat material block entities at {}", restored, origin);
        }
    }

    private static List<Block> copycatBlocks() {
        return BuiltInRegistries.BLOCK.entrySet().stream()
            .filter(entry -> isCopycatBlock(entry.getKey().location()))
            .map(Map.Entry::getValue)
            .toList();
    }

    private static void repairFridges(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos cursor : BlockPos.betweenClosed(origin, end)) {
            BlockPos position = cursor.immutable();
            BlockState reference = level.getBlockState(position);
            ResourceLocation referenceId = BuiltInRegistries.BLOCK.getKey(reference.getBlock());
            ResourceLocation expectedId = FRIDGE_LOWER_BY_UPPER.get(referenceId);
            Direction direction = Direction.DOWN;
            if (expectedId == null) {
                expectedId = FRIDGE_UPPER_BY_LOWER.get(referenceId);
                direction = Direction.UP;
            }
            if (expectedId == null) {
                continue;
            }

            BlockPos counterpart = position.relative(direction);
            ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(counterpart).getBlock()
            );
            if (currentId.equals(expectedId) || !level.getBlockState(counterpart).isAir()) {
                continue;
            }

            BlockState repaired = matchingState(expectedId, reference);
            if (level.setBlock(counterpart, repaired, 3)) {
                LOGGER.info("Repaired structure fridge half at {} with {}", counterpart, expectedId);
            }
        }
    }

    private static BlockState matchingState(ResourceLocation blockId, BlockState reference) {
        BlockState state = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && reference.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(
                BlockStateProperties.HORIZONTAL_FACING,
                reference.getValue(BlockStateProperties.HORIZONTAL_FACING)
            );
        }
        if (state.hasProperty(BlockStateProperties.OPEN)
            && reference.hasProperty(BlockStateProperties.OPEN)) {
            state = state.setValue(
                BlockStateProperties.OPEN,
                reference.getValue(BlockStateProperties.OPEN)
            );
        }
        return state;
    }

    private static void synchronizeBlockEntities(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos cursor : BlockPos.betweenClosed(origin, end)) {
            BlockPos position = cursor.immutable();
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null) {
                continue;
            }
            blockEntity.setChanged();
            BlockState state = level.getBlockState(position);
            level.sendBlockUpdated(position, state, state, 3);
        }
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static boolean isCopycatBlock(ResourceLocation blockId) {
        return blockId.getNamespace().equals("copycats")
            || blockId.getNamespace().equals("create") && blockId.getPath().contains("copycat");
    }
}
