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
 * Makes authored NBT air non-destructive. Only excavation marker blocks are
 * converted to real air and therefore remove existing terrain.
 */
public final class ExplicitAirPlacementProcessor extends BlockIgnoreProcessor {
    public static final ExplicitAirPlacementProcessor INSTANCE =
        new ExplicitAirPlacementProcessor();

    private ExplicitAirPlacementProcessor() {
        super(List.of());
    }

    public static StructurePlaceSettings configure(
        StructureTemplate template, StructurePlaceSettings settings
    ) {
        boolean explicitAir = !template.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(),
            StructureMarkerBlocks.EXCAVATION_MARKER.get()
        ).isEmpty();
        return explicitAir ? settings.addProcessor(INSTANCE) : settings;
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
        if (current.state().is(Blocks.AIR)) {
            return null;
        }
        if (current.state().is(StructureMarkerBlocks.EXCAVATION_MARKER.get())) {
            return new StructureTemplate.StructureBlockInfo(
                current.pos(), Blocks.AIR.defaultBlockState(), null
            );
        }
        return current;
    }
}
