package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Prevents BCA template air and resolved Jigsaw connectors from excavating the
 * generated ground while still allowing air above the surface to clear plants.
 */
public final class TerrainAirPreservationProcessor extends BlockIgnoreProcessor {
    public static final TerrainAirPreservationProcessor INSTANCE =
        new TerrainAirPreservationProcessor();

    private TerrainAirPreservationProcessor() {
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
        if (!current.state().is(Blocks.AIR)) {
            return current;
        }
        BlockPos position = current.pos();
        Integer baseHeight = TownPlacementHeightContext.resolve(
            position.getX(), position.getZ(), Heightmap.Types.WORLD_SURFACE_WG
        );
        if (baseHeight != null && position.getY() < baseHeight
            && !level.getBlockState(position).isAir()) {
            return null;
        }
        return current;
    }
}
