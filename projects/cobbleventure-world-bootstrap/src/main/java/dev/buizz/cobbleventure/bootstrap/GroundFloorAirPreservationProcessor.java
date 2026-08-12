package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Keeps terrain below an authored building footprint intact when the bottom
 * layer of its Structure NBT contains air padding. Air above local Y=0 still
 * clears vegetation and blocks exactly as authored.
 */
public final class GroundFloorAirPreservationProcessor extends BlockIgnoreProcessor {
    public static final GroundFloorAirPreservationProcessor INSTANCE =
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
        if (original.pos().getY() == 0 && current.state().is(Blocks.AIR)) {
            return null;
        }
        return current;
    }
}
