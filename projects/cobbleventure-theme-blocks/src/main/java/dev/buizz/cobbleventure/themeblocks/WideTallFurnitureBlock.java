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
import net.minecraft.world.phys.shapes.VoxelShape;

/** A directional furniture block occupying two blocks of width and two blocks of height. */
final class WideTallFurnitureBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<WideTallFurnitureBlock> CODEC =
        simpleCodec(WideTallFurnitureBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final IntegerProperty WIDTH = IntegerProperty.create("width", 0, 1);
    static final IntegerProperty HEIGHT = IntegerProperty.create("height", 0, 1);
    private static final VoxelShape[][][] SHAPES = createShapes();

    WideTallFurnitureBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
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
            for (int width = 0; width < 2; width++) {
                level.setBlock(
                    positions.get(height * 2 + width),
                    defaultBlockState()
                        .setValue(FACING, facing)
                        .setValue(WIDTH, width)
                        .setValue(HEIGHT, height),
                    Block.UPDATE_ALL
                );
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
        builder.add(FACING, WIDTH, HEIGHT);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos position, CollisionContext context
    ) {
        return SHAPES[state.getValue(HEIGHT)][state.getValue(WIDTH)]
            [directionIndex(state.getValue(FACING))];
    }

    private static List<BlockPos> positions(BlockPos core, Direction facing) {
        BlockPos right = core.relative(facing.getClockWise());
        return List.of(core, right, core.above(), right.above());
    }

    private static BlockPos corePosition(BlockPos position, BlockState state) {
        BlockPos lower = position.below(state.getValue(HEIGHT));
        return state.getValue(WIDTH) == 1
            ? lower.relative(state.getValue(FACING).getCounterClockWise())
            : lower;
    }

    private static VoxelShape[][][] createShapes() {
        VoxelShape[][][] shapes = new VoxelShape[2][2][4];
        Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int height = 0; height < 2; height++) {
            double minY = 0.0D;
            double maxY = height == 0 ? 16.0D : 13.0D;
            for (int width = 0; width < 2; width++) {
                double minX = width == 0 ? 2.0D : 0.0D;
                double maxX = width == 0 ? 16.0D : 14.0D;
                for (Direction facing : directions) {
                    shapes[height][width][directionIndex(facing)] =
                        rotatedBox(facing, minX, minY, 2.0D, maxX, maxY, 15.0D);
                }
            }
        }
        return shapes;
    }

    private static VoxelShape rotatedBox(
        Direction facing,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    ) {
        return switch (facing) {
            case EAST -> box(16.0D - maxZ, minY, minX, 16.0D - minZ, maxY, maxX);
            case SOUTH -> box(16.0D - maxX, minY, 16.0D - maxZ, 16.0D - minX, maxY, 16.0D - minZ);
            case WEST -> box(minZ, minY, 16.0D - maxX, maxZ, maxY, 16.0D - minX);
            default -> box(minX, minY, minZ, maxX, maxY, maxZ);
        };
    }

    private static int directionIndex(Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
