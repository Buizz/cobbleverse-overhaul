package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

final class RocketBaseMachineOneBlock extends AbstractRocketMachineBlock {
    private static final MapCodec<RocketBaseMachineOneBlock> CODEC =
        simpleCodec(RocketBaseMachineOneBlock::new);

    RocketBaseMachineOneBlock(Properties properties) {
        super(
            properties,
            box(0.0D, 0.0D, 0.5D, 16.0D, 16.0D, 16.0D),
            box(0.0D, 0.0D, 0.0D, 15.5D, 16.0D, 16.0D),
            box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 15.5D),
            box(0.5D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
            box(0.0D, 0.0D, 0.5D, 16.0D, 16.0D, 16.0D),
            box(0.0D, 0.0D, 0.0D, 15.5D, 16.0D, 16.0D),
            box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 15.5D),
            box(0.5D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D)
        );
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
