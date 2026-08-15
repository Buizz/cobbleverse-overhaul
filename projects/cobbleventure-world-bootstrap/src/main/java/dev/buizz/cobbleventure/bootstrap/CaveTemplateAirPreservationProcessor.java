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
 * Keeps cave entrance NBT air from excavating the surrounding mountain. Cave
 * authors use temporary barrier blocks for intentional openings; those blocks
 * are removed explicitly after the template has been placed.
 */
public final class CaveTemplateAirPreservationProcessor extends BlockIgnoreProcessor {
    public static final CaveTemplateAirPreservationProcessor INSTANCE =
        new CaveTemplateAirPreservationProcessor();

    private CaveTemplateAirPreservationProcessor() {
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
        return current.state().is(Blocks.AIR) ? null : current;
    }
}
