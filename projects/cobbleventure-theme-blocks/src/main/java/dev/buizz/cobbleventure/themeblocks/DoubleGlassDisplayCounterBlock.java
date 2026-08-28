package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class DoubleGlassDisplayCounterBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<DoubleGlassDisplayCounterBlock> CODEC =
        simpleCodec(DoubleGlassDisplayCounterBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final IntegerProperty PART = IntegerProperty.create("part", 0, 1);

    private static final VoxelShape NORTH_SHAPE = Shapes.or(
        box(0.0D, 0.0D, 2.0D, 16.0D, 14.0D, 16.0D),
        box(0.0D, 14.0D, 12.0D, 16.0D, 16.0D, 16.0D)
    );
    private static final VoxelShape EAST_SHAPE = Shapes.or(
        box(0.0D, 0.0D, 0.0D, 14.0D, 14.0D, 16.0D),
        box(0.0D, 14.0D, 0.0D, 4.0D, 16.0D, 16.0D)
    );
    private static final VoxelShape SOUTH_SHAPE = Shapes.or(
        box(0.0D, 0.0D, 0.0D, 16.0D, 14.0D, 14.0D),
        box(0.0D, 14.0D, 0.0D, 16.0D, 16.0D, 4.0D)
    );
    private static final VoxelShape WEST_SHAPE = Shapes.or(
        box(2.0D, 0.0D, 0.0D, 16.0D, 14.0D, 16.0D),
        box(12.0D, 14.0D, 0.0D, 16.0D, 16.0D, 16.0D)
    );

    DoubleGlassDisplayCounterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(PART, 0));
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
            if (!context.getLevel().getBlockState(position).canBeReplaced()) {
                return null;
            }
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(
        Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack
    ) {
        super.setPlacedBy(level, position, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        List<BlockPos> positions = positions(position, state.getValue(FACING));
        for (int part = 0; part < positions.size(); part++) {
            level.setBlock(
                positions.get(part),
                defaultBlockState().setValue(FACING, state.getValue(FACING)).setValue(PART, part),
                Block.UPDATE_ALL
            );
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
        return state
            .rotate(mirror.getRotation(state.getValue(FACING)))
            .setValue(PART, state.getValue(PART) ^ 1);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
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

    private static List<BlockPos> positions(BlockPos core, Direction facing) {
        return List.of(core, core.relative(facing.getCounterClockWise()));
    }

    private static BlockPos corePosition(BlockPos position, BlockState state) {
        return state.getValue(PART) == 1
            ? position.relative(state.getValue(FACING).getClockWise())
            : position;
    }
}
