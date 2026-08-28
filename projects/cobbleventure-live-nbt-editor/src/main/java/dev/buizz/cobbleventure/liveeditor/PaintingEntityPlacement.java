package dev.buizz.cobbleventure.liveeditor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class PaintingEntityPlacement {
    private static final String PAINTING_ID = "minecraft:painting";

    private PaintingEntityPlacement() {}

    static void repairStructure(CompoundTag structure) {
        ListTag entities = structure.getList("entities", Tag.TAG_COMPOUND);
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag entity = entities.getCompound(index);
            CompoundTag nbt = entity.getCompound("nbt");
            if (!PAINTING_ID.equals(nbt.getString("id"))) continue;
            ListTag blockPosition = entity.getList("blockPos", Tag.TAG_INT);
            if (blockPosition.size() != 3) continue;
            ListTag position = new ListTag();
            position.add(DoubleTag.valueOf(blockPosition.getInt(0)));
            position.add(DoubleTag.valueOf(blockPosition.getInt(1)));
            position.add(DoubleTag.valueOf(blockPosition.getInt(2)));
            entity.put("pos", position);
        }
    }
}
