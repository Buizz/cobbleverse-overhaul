package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class PokemonTowerGraveBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<PokemonTowerGraveBlock> CODEC = simpleCodec(PokemonTowerGraveBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape NORTH_SOUTH_SHAPE = Shapes.or(
        box(1.0D, 0.0D, 3.0D, 15.0D, 2.0D, 13.0D),
        box(2.0D, 2.0D, 4.0D, 14.0D, 4.0D, 12.0D),
        box(4.0D, 4.0D, 6.0D, 12.0D, 12.0D, 10.0D),
        box(5.0D, 12.0D, 6.0D, 11.0D, 14.0D, 10.0D),
        box(6.0D, 14.0D, 6.0D, 10.0D, 15.0D, 10.0D)
    );
    private static final VoxelShape EAST_WEST_SHAPE = Shapes.or(
        box(3.0D, 0.0D, 1.0D, 13.0D, 2.0D, 15.0D),
        box(4.0D, 2.0D, 2.0D, 12.0D, 4.0D, 14.0D),
        box(6.0D, 4.0D, 4.0D, 10.0D, 12.0D, 12.0D),
        box(6.0D, 12.0D, 5.0D, 10.0D, 14.0D, 11.0D),
        box(6.0D, 14.0D, 6.0D, 10.0D, 15.0D, 10.0D)
    );

    PokemonTowerGraveBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos position, CollisionContext context
    ) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X
            ? EAST_WEST_SHAPE
            : NORTH_SOUTH_SHAPE;
    }
}
