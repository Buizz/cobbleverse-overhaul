package dev.buizz.cobbleventure.liveeditor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Assigns placement-local deck UUIDs while keeping cards linked to the same deck. */
final class PlayingCardsEntityPlacementProcessor extends BlockIgnoreProcessor {
    static final PlayingCardsEntityPlacementProcessor INSTANCE =
        new PlayingCardsEntityPlacementProcessor();

    private PlayingCardsEntityPlacementProcessor() {
        super(List.of());
    }

    @Override
    public StructureTemplate.StructureEntityInfo processEntity(
        LevelReader level,
        BlockPos origin,
        StructureTemplate.StructureEntityInfo original,
        StructureTemplate.StructureEntityInfo current,
        StructurePlaceSettings settings,
        StructureTemplate template
    ) {
        CompoundTag relocated = PlayingCardsEntityLinks.relocate(current.nbt, origin);
        return relocated == current.nbt
            ? current
            : new StructureTemplate.StructureEntityInfo(
                current.pos, current.blockPos, relocated
            );
    }
}
