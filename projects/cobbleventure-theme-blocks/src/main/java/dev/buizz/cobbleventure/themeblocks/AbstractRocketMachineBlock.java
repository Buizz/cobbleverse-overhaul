package dev.buizz.cobbleventure.themeblocks;

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

abstract class AbstractRocketMachineBlock extends HorizontalDirectionalBlock {
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final IntegerProperty HEIGHT = IntegerProperty.create("height", 0, 1);

    private final VoxelShape[] lowerShapes;
    private final VoxelShape[] upperShapes;

    AbstractRocketMachineBlock(
        Properties properties,
        VoxelShape northShape,
        VoxelShape eastShape,
        VoxelShape southShape,
        VoxelShape westShape,
        VoxelShape upperNorthShape,
        VoxelShape upperEastShape,
        VoxelShape upperSouthShape,
        VoxelShape upperWestShape
    ) {
        super(properties);
        this.lowerShapes = new VoxelShape[] {northShape, eastShape, southShape, westShape};
        this.upperShapes = new VoxelShape[] {
            upperNorthShape, upperEastShape, upperSouthShape, upperWestShape
        };
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(HEIGHT, 0));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos upperPosition = context.getClickedPos().above();
        if (context.getLevel().isOutsideBuildHeight(upperPosition)
            || !context.getLevel().getBlockState(upperPosition).canBeReplaced()) {
            return null;
        }
        return defaultBlockState()
            .setValue(FACING, context.getHorizontalDirection().getOpposite())
            .setValue(HEIGHT, 0);
    }

    @Override
    public void setPlacedBy(
        Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack
    ) {
        super.setPlacedBy(level, position, state, placer, stack);
        if (!level.isClientSide()) {
            level.setBlock(
                position.above(),
                defaultBlockState()
                    .setValue(FACING, state.getValue(FACING))
                    .setValue(HEIGHT, 1),
                Block.UPDATE_ALL
            );
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos position, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockPos otherPosition = state.getValue(HEIGHT) == 0 ? position.above() : position.below();
            if (level.getBlockState(otherPosition).is(this)) {
                level.removeBlock(otherPosition, false);
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
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HEIGHT);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos position, CollisionContext context
    ) {
        VoxelShape[] shapes = state.getValue(HEIGHT) == 0 ? lowerShapes : upperShapes;
        return shapes[switch (state.getValue(FACING)) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        }];
    }
}
