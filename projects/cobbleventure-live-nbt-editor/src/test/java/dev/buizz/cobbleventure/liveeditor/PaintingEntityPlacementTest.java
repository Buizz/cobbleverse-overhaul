package dev.buizz.cobbleventure.liveeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

final class PaintingEntityPlacementTest {
    @Test
    void usesWallAttachmentBlockInsteadOfPaintingCenter() {
        CompoundTag structure = new CompoundTag();
        ListTag entities = new ListTag();
        CompoundTag wrapper = new CompoundTag();
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:painting");
        wrapper.put("nbt", nbt);
        wrapper.put("blockPos", ints(14, 6, 15));
        wrapper.put("pos", doubles(14.5D, 7.0D, 15.96875D));
        entities.add(wrapper);
        structure.put("entities", entities);

        PaintingEntityPlacement.repairStructure(structure);

        ListTag position = wrapper.getList("pos", Tag.TAG_DOUBLE);
        assertEquals(14.0D, position.getDouble(0));
        assertEquals(6.0D, position.getDouble(1));
        assertEquals(15.0D, position.getDouble(2));
    }

    @Test
    void leavesOtherEntitiesAtTheirExactPosition() {
        CompoundTag structure = new CompoundTag();
        ListTag entities = new ListTag();
        CompoundTag wrapper = new CompoundTag();
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:item_frame");
        wrapper.put("nbt", nbt);
        wrapper.put("blockPos", ints(1, 2, 3));
        wrapper.put("pos", doubles(1.5D, 2.5D, 3.5D));
        entities.add(wrapper);
        structure.put("entities", entities);

        PaintingEntityPlacement.repairStructure(structure);

        assertEquals(2.5D, wrapper.getList("pos", Tag.TAG_DOUBLE).getDouble(1));
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag values = new ListTag();
        values.add(IntTag.valueOf(x));
        values.add(IntTag.valueOf(y));
        values.add(IntTag.valueOf(z));
        return values;
    }

    private static ListTag doubles(double x, double y, double z) {
        ListTag values = new ListTag();
        values.add(DoubleTag.valueOf(x));
        values.add(DoubleTag.valueOf(y));
        values.add(DoubleTag.valueOf(z));
        return values;
    }
}
