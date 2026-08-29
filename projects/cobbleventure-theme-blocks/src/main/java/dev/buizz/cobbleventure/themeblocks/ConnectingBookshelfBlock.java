package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ConnectingBookshelfBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<ConnectingBookshelfBlock> CODEC =
        simpleCodec(ConnectingBookshelfBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final BooleanProperty LEFT = BooleanProperty.create("left");
    static final BooleanProperty RIGHT = BooleanProperty.create("right");
    private static final VoxelShape NORTH_SHAPE = box(0.0D, 0.0D, 2.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape EAST_SHAPE = box(0.0D, 0.0D, 0.0D, 14.0D, 16.0D, 16.0D);
    private static final VoxelShape SOUTH_SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 14.0D);
    private static final VoxelShape WEST_SHAPE = box(2.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    ConnectingBookshelfBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(LEFT, false)
            .setValue(RIGHT, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos position = context.getClickedPos();
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(LEFT, connectsTo(context.getLevel(), position.relative(leftDirection(facing)), facing))
            .setValue(RIGHT, connectsTo(context.getLevel(), position.relative(rightDirection(facing)), facing));
    }

    @Override
    protected BlockState updateShape(
        BlockState state,
        Direction direction,
        BlockState neighborState,
        LevelAccessor level,
        BlockPos position,
        BlockPos neighborPosition
    ) {
        Direction facing = state.getValue(FACING);
        if (direction == leftDirection(facing)) {
            return state.setValue(LEFT, connectsTo(neighborState, facing));
        }
        if (direction == rightDirection(facing)) {
            return state.setValue(RIGHT, connectsTo(neighborState, facing));
        }
        return super.updateShape(state, direction, neighborState, level, position, neighborPosition);
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
            .setValue(LEFT, state.getValue(RIGHT))
            .setValue(RIGHT, state.getValue(LEFT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT, RIGHT);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos position, CollisionContext context
    ) {
        return switch (state.getValue(FACING)) {
            case EAST -> EAST_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    private boolean connectsTo(BlockGetter level, BlockPos position, Direction facing) {
        return connectsTo(level.getBlockState(position), facing);
    }

    private boolean connectsTo(BlockState state, Direction facing) {
        return state.is(this) && state.getValue(FACING) == facing;
    }

    private static Direction leftDirection(Direction facing) {
        return facing.getClockWise();
    }

    private static Direction rightDirection(Direction facing) {
        return facing.getCounterClockWise();
    }
}
