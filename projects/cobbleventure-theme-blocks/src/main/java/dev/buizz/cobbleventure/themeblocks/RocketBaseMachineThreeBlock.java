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

final class RocketBaseMachineThreeBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<RocketBaseMachineThreeBlock> CODEC =
        simpleCodec(RocketBaseMachineThreeBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final IntegerProperty PART = IntegerProperty.create("part", 0, 19);

    /*
     * The rear body occupies local width 0..1, depth 0..1 and height 0..1.
     * The front body occupies local width 2..3, depth 0..1 and height 0..2.
     * Both JSON models are anchored on their middle height layer. The rear
     * connector slightly enters width 2, where it meets the front body.
     */
    private static final List<PartOffset> OFFSETS = createOffsets();
    static final int REAR_MODEL_PART = partAt(1, 0, 1);
    static final int FRONT_MODEL_PART = partAt(2, 0, 1);

    RocketBaseMachineThreeBlock(Properties properties) {
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
            if (context.getLevel().isOutsideBuildHeight(position)
                || !context.getLevel().getBlockState(position).canBeReplaced()) {
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
        Direction facing = state.getValue(FACING);
        List<BlockPos> positions = positions(position, facing);
        for (int part = 0; part < positions.size(); part++) {
            level.setBlock(
                positions.get(part),
                defaultBlockState().setValue(FACING, facing).setValue(PART, part),
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
        return mirror == Mirror.NONE
            ? state
            : state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos position, CollisionContext context
    ) {
        return Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    }

    private static List<PartOffset> createOffsets() {
        List<PartOffset> offsets = new ArrayList<>(20);
        // Rear body: 2 wide x 2 deep x 2 high.
        for (int height = 0; height < 2; height++) {
            for (int depth = 0; depth < 2; depth++) {
                for (int width = 0; width < 2; width++) {
                    offsets.add(new PartOffset(width, depth, height));
                }
            }
        }
        // Front body: 2 wide x 2 deep x 3 high, placed to the right of the rear body.
        for (int height = 0; height < 3; height++) {
            for (int depth = 0; depth < 2; depth++) {
                for (int width = 2; width < 4; width++) {
                    offsets.add(new PartOffset(width, depth, height));
                }
            }
        }
        return List.copyOf(offsets);
    }

    private static int partAt(int width, int depth, int height) {
        for (int part = 0; part < OFFSETS.size(); part++) {
            PartOffset offset = OFFSETS.get(part);
            if (offset.width == width && offset.depth == depth && offset.height == height) {
                return part;
            }
        }
        throw new IllegalArgumentException("No machine part at the requested offset");
    }

    private static List<BlockPos> positions(BlockPos core, Direction facing) {
        Direction right = facing.getClockWise();
        Direction back = facing.getOpposite();
        return OFFSETS.stream()
            .map(offset -> core
                .relative(right, offset.width)
                .relative(back, offset.depth)
                .above(offset.height))
            .toList();
    }

    private static BlockPos corePosition(BlockPos position, BlockState state) {
        PartOffset offset = OFFSETS.get(state.getValue(PART));
        Direction facing = state.getValue(FACING);
        return position
            .below(offset.height)
            .relative(facing.getCounterClockWise(), offset.width)
            .relative(facing, offset.depth);
    }

    private record PartOffset(int width, int depth, int height) {
    }
}
