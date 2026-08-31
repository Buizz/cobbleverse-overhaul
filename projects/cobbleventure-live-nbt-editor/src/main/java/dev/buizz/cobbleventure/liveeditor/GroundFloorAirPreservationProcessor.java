package dev.buizz.cobbleventure.liveeditor;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Prevents local Y=0 air in an NBT from replacing the existing world block. */
final class GroundFloorAirPreservationProcessor extends BlockIgnoreProcessor {
    static final GroundFloorAirPreservationProcessor INSTANCE =
        new GroundFloorAirPreservationProcessor();

    private GroundFloorAirPreservationProcessor() {
        super(List.of());
    }

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level,
        BlockPos origin,
        BlockPos pivot,
        StructureTemplate.StructureBlockInfo original,
        StructureTemplate.StructureBlockInfo current,
        StructurePlaceSettings settings
    ) {
        return original.pos().getY() == 0 && current.state().isAir()
            ? null : current;
    }
}
