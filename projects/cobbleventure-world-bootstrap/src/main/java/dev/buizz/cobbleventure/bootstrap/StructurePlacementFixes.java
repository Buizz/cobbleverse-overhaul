package dev.buizz.cobbleventure.bootstrap;

import com.mojang.logging.LogUtils;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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

    static void afterPlacement(ServerLevel level, BlockPos origin, Vec3i size) {
        repairFridges(level, origin, size);
        synchronizeBlockEntities(level, origin, size);
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
}
