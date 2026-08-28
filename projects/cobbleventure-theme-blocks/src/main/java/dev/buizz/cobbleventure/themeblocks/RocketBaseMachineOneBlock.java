package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

final class RocketBaseMachineOneBlock extends AbstractRocketMachineBlock {
    private static final MapCodec<RocketBaseMachineOneBlock> CODEC =
        simpleCodec(RocketBaseMachineOneBlock::new);

    RocketBaseMachineOneBlock(Properties properties) {
        super(
            properties,
            box(3.0D, 0.0D, 3.0D, 13.0D, 16.0D, 14.0D),
            box(2.0D, 0.0D, 3.0D, 13.0D, 16.0D, 13.0D),
            box(3.0D, 0.0D, 2.0D, 13.0D, 16.0D, 13.0D),
            box(3.0D, 0.0D, 3.0D, 14.0D, 16.0D, 13.0D)
        );
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
