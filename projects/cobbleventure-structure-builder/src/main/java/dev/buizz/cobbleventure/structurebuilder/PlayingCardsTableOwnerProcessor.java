package dev.buizz.cobbleventure.structurebuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
    private static final UUID SYSTEM_OWNER = UUID.nameUUIDFromBytes(
        "cobbleventure:playingcards_table_owner".getBytes(StandardCharsets.UTF_8)
    );
    private static final String SYSTEM_OWNER_NAME = "Cobbleventure Casino";

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
        CompoundTag ownerData = withOwner(current.nbt());
        return ownerData == current.nbt()
            ? current
            : new StructureTemplate.StructureBlockInfo(
                current.pos(), current.state(), ownerData
            );
    }

    static CompoundTag withOwner(CompoundTag source) {
        if (source != null && source.contains("OwnerID", Tag.TAG_INT_ARRAY)) {
            return source;
        }
        CompoundTag result = source == null ? new CompoundTag() : source.copy();
        result.putUUID("OwnerID", SYSTEM_OWNER);
        if (!result.contains("OwnerName", Tag.TAG_STRING)
            || result.getString("OwnerName").isBlank()) {
            result.putString("OwnerName", SYSTEM_OWNER_NAME);
        }
        return result;
    }
}
