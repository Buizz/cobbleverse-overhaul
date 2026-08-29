package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

final class GlowWindowBlock extends HorizontalDirectionalBlock {
    private static final MapCodec<GlowWindowBlock> CODEC = simpleCodec(GlowWindowBlock::new);
    static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final IntegerProperty PART = IntegerProperty.create("part", 0, 1);

    GlowWindowBlock(Properties properties) {
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
        for (BlockPos position : positions(core)) {
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
        List<BlockPos> positions = positions(position);
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
            BlockPos core = state.getValue(PART) == 1 ? position.below() : position;
            for (BlockPos partPosition : positions(core)) {
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
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    private static List<BlockPos> positions(BlockPos core) {
        return List.of(core, core.above());
    }
}
