package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Relocates absolute coordinates embedded in saved Create elevator state. */
final class CreateElevatorEntityPlacementProcessor extends BlockIgnoreProcessor {
    private static final ResourceLocation ELEVATOR_CONTACT =
        ResourceLocation.fromNamespaceAndPath("create", "elevator_contact");
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
            current.nbt, current.pos, current.blockPos, settings,
            contactRange(template, origin, settings)
        );
        return relocated == current.nbt
            ? current
            : new StructureTemplate.StructureEntityInfo(
                current.pos, current.blockPos, relocated
            );
    }

    private static int[] contactRange(
        StructureTemplate template, BlockPos origin, StructurePlaceSettings settings
    ) {
        Block contact = BuiltInRegistries.BLOCK.get(ELEVATOR_CONTACT);
        if (!BuiltInRegistries.BLOCK.getKey(contact).equals(ELEVATOR_CONTACT)) return null;
        List<StructureTemplate.StructureBlockInfo> contacts =
            template.filterBlocks(origin, settings, contact);
        if (contacts.isEmpty()) return null;
        int minimum = contacts.stream().mapToInt(info -> info.pos().getY()).min()
            .orElseThrow();
        int maximum = contacts.stream().mapToInt(info -> info.pos().getY()).max()
            .orElseThrow();
        return new int[] {minimum, maximum};
    }

}
