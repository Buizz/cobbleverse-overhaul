package dev.buizz.cobbleventure.liveeditor;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Converts only authored excavation markers to destructive air during test placement. */
final class ExplicitAirPlacementProcessor extends BlockIgnoreProcessor {
    private static final ExplicitAirPlacementProcessor INSTANCE =
        new ExplicitAirPlacementProcessor();

    private ExplicitAirPlacementProcessor() {
        super(List.of());
    }

    static StructurePlaceSettings configure(
        StructureTemplate template, StructurePlaceSettings settings
    ) {
        boolean hasMarker = !template.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(),
            LiveEditorBlocks.excavationMarker()
        ).isEmpty();
        return hasMarker ? settings.addProcessor(INSTANCE) : settings;
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
        if (current.state().is(Blocks.AIR)) return null;
        if (current.state().is(LiveEditorBlocks.excavationMarker())) {
            return new StructureTemplate.StructureBlockInfo(
                current.pos(), Blocks.AIR.defaultBlockState(), null
            );
        }
        return current;
    }
}
