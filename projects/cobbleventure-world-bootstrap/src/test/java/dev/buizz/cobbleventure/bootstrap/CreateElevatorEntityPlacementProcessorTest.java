package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class CreateElevatorEntityPlacementProcessorTest {
    @Test
    void relocatesAuthoredAbsoluteCoordinatesWithoutMutatingTheTemplate() {
        CompoundTag source = elevatorEntity();
        Vec3 target = new Vec3(1039.0, 29.0, 2019.0);

        CompoundTag relocated = CreateElevatorEntityRelocation.relocate(
            source, target, BlockPos.containing(target), new StructurePlaceSettings()
        );

        assertEquals(
            new BlockPos(1039, 29, 2019),
            NbtUtils.readBlockPos(relocated.getCompound("Contraption"), "Anchor").orElseThrow()
        );
        assertEquals(
            new Vec3(1039.5, 26.5, 2017.5),
            actorPosition(relocated)
        );
        assertEquals(
            new BlockPos(0, 30, 0),
            NbtUtils.readBlockPos(relocated, "ControllerRelative").orElseThrow()
        );
        assertEquals(-381, relocated.getCompound("Contraption").getInt("MinContactY"));
        assertEquals(69, relocated.getCompound("Contraption").getInt("MaxContactY"));
        assertEquals(
            new BlockPos(663, 10, -245),
            NbtUtils.readBlockPos(source.getCompound("Contraption"), "Anchor").orElseThrow()
        );
        assertEquals(new Vec3(663.5, 7.5, -246.5), actorPosition(source));
    }

    @Test
    void rotatesRelativeCreateCoordinatesWithTheStructure() {
        CompoundTag source = elevatorEntity();
        Vec3 target = new Vec3(100.0, 20.0, 200.0);
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(Rotation.CLOCKWISE_90);

        CompoundTag relocated = CreateElevatorEntityRelocation.relocate(
            source, target, BlockPos.containing(target), settings
        );

        assertEquals(new Vec3(101.5, 17.5, 200.5), actorPosition(relocated));
        CompoundTag column = relocated.getCompound("Contraption").getCompound("Column");
        assertEquals(3, column.getInt("X"));
        assertEquals(2, column.getInt("Z"));
        assertEquals("WEST", column.getString("Side"));
    }

    @Test
    void leavesUnrelatedEntitiesUntouched() {
        CompoundTag source = new CompoundTag();
        source.putString("id", "minecraft:armor_stand");

        assertSame(
            source,
            CreateElevatorEntityRelocation.relocate(
                source, Vec3.ZERO, BlockPos.ZERO, new StructurePlaceSettings()
            )
        );
    }

    private static CompoundTag elevatorEntity() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "create:stationary_contraption");
        entity.put("Pos", vec3(663.0, 10.0, -245.0));
        entity.put("ControllerRelative", NbtUtils.writeBlockPos(new BlockPos(0, 30, 0)));

        CompoundTag contraption = new CompoundTag();
        contraption.putString("Type", "create:elevator");
        contraption.put("Anchor", NbtUtils.writeBlockPos(new BlockPos(663, 10, -245)));
        CompoundTag column = new CompoundTag();
        column.putInt("X", 2);
        column.putInt("Z", -3);
        column.putString("Side", "SOUTH");
        contraption.put("Column", column);
        contraption.putInt("MinContactY", -400);
        contraption.putInt("MaxContactY", 50);

        CompoundTag actor = new CompoundTag();
        actor.put("Position", vec3(663.5, 7.5, -246.5));
        ListTag actors = new ListTag();
        actors.add(actor);
        contraption.put("Actors", actors);
        entity.put("Contraption", contraption);
        return entity;
    }

    private static Vec3 actorPosition(CompoundTag entity) {
        ListTag values = entity.getCompound("Contraption")
            .getList("Actors", CompoundTag.TAG_COMPOUND)
            .getCompound(0)
            .getList("Position", DoubleTag.TAG_DOUBLE);
        return new Vec3(values.getDouble(0), values.getDouble(1), values.getDouble(2));
    }

    private static ListTag vec3(double x, double y, double z) {
        ListTag values = new ListTag();
        values.add(DoubleTag.valueOf(x));
        values.add(DoubleTag.valueOf(y));
        values.add(DoubleTag.valueOf(z));
        return values;
    }
}
