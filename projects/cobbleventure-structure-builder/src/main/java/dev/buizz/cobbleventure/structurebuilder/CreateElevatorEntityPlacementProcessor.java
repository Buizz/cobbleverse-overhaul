package dev.buizz.cobbleventure.structurebuilder;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Relocates absolute coordinates embedded in saved Create elevator state. */
final class CreateElevatorEntityPlacementProcessor extends BlockIgnoreProcessor {
    static final CreateElevatorEntityPlacementProcessor INSTANCE =
        new CreateElevatorEntityPlacementProcessor();

    private CreateElevatorEntityPlacementProcessor() {
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
        CompoundTag relocated = CreateElevatorEntityRelocation.relocate(
            current.nbt, current.pos, current.blockPos, settings
        );
        return relocated == current.nbt
            ? current
            : new StructureTemplate.StructureEntityInfo(
                current.pos, current.blockPos, relocated
            );
    }

}
