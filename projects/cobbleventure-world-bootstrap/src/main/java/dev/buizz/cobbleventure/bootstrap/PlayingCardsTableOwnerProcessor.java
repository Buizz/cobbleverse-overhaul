package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Supplies the owner data that Playing Cards normally adds during player placement. */
final class PlayingCardsTableOwnerProcessor extends BlockIgnoreProcessor {
    static final PlayingCardsTableOwnerProcessor INSTANCE =
        new PlayingCardsTableOwnerProcessor();

    private static final ResourceLocation POKER_TABLE = ResourceLocation.fromNamespaceAndPath(
        "playingcards", "poker_table"
    );
    private PlayingCardsTableOwnerProcessor() {
        super(List.of());
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level,
        BlockPos origin,
        BlockPos pivot,
        StructureTemplate.StructureBlockInfo original,
        StructureTemplate.StructureBlockInfo current,
        StructurePlaceSettings settings
    ) {
        if (!BuiltInRegistries.BLOCK.getKey(current.state().getBlock()).equals(POKER_TABLE)) {
            return current;
        }
        CompoundTag ownerData = PlayingCardsTableOwnerData.withOwner(current.nbt());
        return ownerData == current.nbt()
            ? current
            : new StructureTemplate.StructureBlockInfo(
                current.pos(), current.state(), ownerData
            );
    }

}
