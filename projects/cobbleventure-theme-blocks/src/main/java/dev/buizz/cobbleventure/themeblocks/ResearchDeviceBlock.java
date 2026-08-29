package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ResearchDeviceBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<ResearchDeviceBlock> CODEC =
        simpleCodec(ResearchDeviceBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final IntegerProperty DEPTH = IntegerProperty.create("depth", 0, 1);
    static final IntegerProperty WIDTH = IntegerProperty.create("width", 0, 1);
    static final IntegerProperty HEIGHT = IntegerProperty.create("height", 0, 1);
    private static final VoxelShape LOWER_SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape UPPER_SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    ResearchDeviceBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(DEPTH, 0)
            .setValue(WIDTH, 0)
            .setValue(HEIGHT, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos core = context.getClickedPos();
        for (BlockPos position : positions(core, facing)) {
            if (context.getLevel().isOutsideBuildHeight(position)
                || !context.getLevel().getBlockState(position).canBeReplaced()) {
                return null;
            }
        }
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(DEPTH, 0)
            .setValue(WIDTH, 0)
            .setValue(HEIGHT, 0);
    }

    @Override
    public void setPlacedBy(
        Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack
    ) {
        super.setPlacedBy(level, position, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        Direction facing = state.getValue(FACING);
        List<BlockPos> positions = positions(position, facing);
        for (int height = 0; height < 2; height++) {
            for (int depth = 0; depth < 2; depth++) {
                for (int width = 0; width < 2; width++) {
                    level.setBlock(
                        positions.get(height * 4 + depth * 2 + width),
                        defaultBlockState()
                            .setValue(FACING, facing)
                            .setValue(DEPTH, depth)
                            .setValue(WIDTH, width)
                            .setValue(HEIGHT, height),
                        Block.UPDATE_ALL
                    );
                }
            }
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos position, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockPos core = corePosition(position, state);
            for (BlockPos partPosition : positions(core, state.getValue(FACING))) {
                if (!partPosition.equals(position) && level.getBlockState(partPosition).is(this)) {
                    level.removeBlock(partPosition, false);
                }
            }
        }
        return super.playerWillDestroy(level, position, state, player);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) {
            return state;
        }
        return state
            .rotate(mirror.getRotation(state.getValue(FACING)))
            .setValue(WIDTH, state.getValue(WIDTH) ^ 1);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, DEPTH, WIDTH, HEIGHT);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos position, CollisionContext context
    ) {
        return state.getValue(HEIGHT) == 0 ? LOWER_SHAPE : UPPER_SHAPE;
    }

    private static List<BlockPos> positions(BlockPos core, Direction facing) {
        Direction depthDirection = facing.getOpposite();
        Direction widthDirection = facing.getClockWise();
        List<BlockPos> positions = new ArrayList<>(8);
        for (int height = 0; height < 2; height++) {
            for (int depth = 0; depth < 2; depth++) {
                for (int width = 0; width < 2; width++) {
                    positions.add(core
                        .relative(depthDirection, depth)
                        .relative(widthDirection, width)
                        .above(height));
                }
            }
        }
        return positions;
    }

    private static BlockPos corePosition(BlockPos position, BlockState state) {
        Direction facing = state.getValue(FACING);
        return position
            .below(state.getValue(HEIGHT))
            .relative(facing, state.getValue(DEPTH))
            .relative(facing.getCounterClockWise(), state.getValue(WIDTH));
    }
}
