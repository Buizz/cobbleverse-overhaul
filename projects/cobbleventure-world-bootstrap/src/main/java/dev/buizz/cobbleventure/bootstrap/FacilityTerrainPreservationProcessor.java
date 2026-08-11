package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Keeps imported facility terrain and air padding from replacing the generated floor. */
public final class FacilityTerrainPreservationProcessor extends BlockIgnoreProcessor {
    private static final List<Block> TEMPLATE_TERRAIN = List.of(
        Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
        Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUD, Blocks.DIRT_PATH,
        Blocks.FARMLAND, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.CLAY,
        Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET,
        Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
        Blocks.DEAD_BUSH, Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID,
        Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP,
        Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY,
        Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.SUNFLOWER,
        Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY
    );
    private final int groundY;

    FacilityTerrainPreservationProcessor(int groundY) {
        super(List.of());
        this.groundY = groundY;
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
        BlockState state = current.state();
        if (state.is(Blocks.AIR) && current.pos().getY() <= groundY
            && !level.getBlockState(current.pos()).isAir()) {
            return null;
        }
        if (TEMPLATE_TERRAIN.stream().anyMatch(state::is)) {
            return null;
        }
        return current;
    }
}
