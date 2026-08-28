package dev.buizz.cobbleventure.themeblocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ProfessorLabResearchDeviceOneBlock extends AbstractRocketMachineBlock {
    private static final MapCodec<ProfessorLabResearchDeviceOneBlock> CODEC =
        simpleCodec(ProfessorLabResearchDeviceOneBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
        box(1.0D, 0.0D, 1.0D, 15.0D, 3.0D, 15.0D),
        box(2.0D, 3.0D, 2.0D, 14.0D, 10.0D, 14.0D),
        box(3.0D, 10.0D, 3.0D, 13.0D, 14.0D, 13.0D),
        box(5.0D, 14.0D, 5.0D, 11.0D, 15.0D, 11.0D)
    );

    ProfessorLabResearchDeviceOneBlock(Properties properties) {
        super(properties, SHAPE, SHAPE, SHAPE, SHAPE);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
