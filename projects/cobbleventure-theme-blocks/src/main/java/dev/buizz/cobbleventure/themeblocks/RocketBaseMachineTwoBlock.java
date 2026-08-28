package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.shapes.Shapes;

final class RocketBaseMachineTwoBlock extends AbstractRocketMachineBlock {
    private static final MapCodec<RocketBaseMachineTwoBlock> CODEC =
        simpleCodec(RocketBaseMachineTwoBlock::new);

    RocketBaseMachineTwoBlock(Properties properties) {
        super(
            properties,
            box(1.0D, 0.0D, 3.0D, 15.0D, 16.0D, 15.0D),
            box(1.0D, 0.0D, 1.0D, 13.0D, 16.0D, 15.0D),
            box(1.0D, 0.0D, 1.0D, 15.0D, 16.0D, 13.0D),
            box(3.0D, 0.0D, 1.0D, 15.0D, 16.0D, 15.0D),
            Shapes.or(
                box(1.0D, 0.0D, 3.0D, 15.0D, 7.0D, 15.0D),
                box(2.0D, 7.0D, 10.0D, 14.0D, 14.0D, 15.0D)
            ),
            Shapes.or(
                box(1.0D, 0.0D, 1.0D, 13.0D, 7.0D, 15.0D),
                box(1.0D, 7.0D, 2.0D, 6.0D, 14.0D, 14.0D)
            ),
            Shapes.or(
                box(1.0D, 0.0D, 1.0D, 15.0D, 7.0D, 13.0D),
                box(2.0D, 7.0D, 1.0D, 14.0D, 14.0D, 6.0D)
            ),
            Shapes.or(
                box(3.0D, 0.0D, 1.0D, 15.0D, 7.0D, 15.0D),
                box(10.0D, 7.0D, 2.0D, 15.0D, 14.0D, 14.0D)
            )
        );
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
