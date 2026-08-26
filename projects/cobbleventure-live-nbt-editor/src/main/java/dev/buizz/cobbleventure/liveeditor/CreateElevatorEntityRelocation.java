package dev.buizz.cobbleventure.liveeditor;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/** Pure NBT coordinate relocation used before a Create elevator entity is instantiated. */
final class CreateElevatorEntityRelocation {
    private CreateElevatorEntityRelocation() {}

    static CompoundTag relocate(
        CompoundTag source,
        Vec3 placedEntityPosition,
        BlockPos placedEntityBlockPosition,
        StructurePlaceSettings settings
    ) {
        return relocate(
            source, placedEntityPosition, placedEntityBlockPosition, settings, null
        );
    }

    static CompoundTag relocate(
        CompoundTag source,
        Vec3 placedEntityPosition,
        BlockPos placedEntityBlockPosition,
        StructurePlaceSettings settings,
        int[] contactRange
    ) {
        CompoundTag sourceContraption = source.getCompound("Contraption");
        if (!source.getString("id").equals("create:stationary_contraption")
            || !sourceContraption.getString("Type").equals("create:elevator")) return source;
        Vec3 authoredEntityPosition = readVec3(source, "Pos");
        if (authoredEntityPosition == null) return source;

        CompoundTag relocated = source.copy();
        CompoundTag contraption = relocated.getCompound("Contraption");
        BlockPos authoredEntityBlockPosition = BlockPos.containing(authoredEntityPosition);
        BlockPos authoredAnchor = NbtUtils.readBlockPos(contraption, "Anchor")
            .orElse(authoredEntityBlockPosition);
        BlockPos relativeAnchor = authoredAnchor.subtract(authoredEntityBlockPosition);
        BlockPos placedAnchor = placedEntityBlockPosition.offset(
            transformRelative(relativeAnchor, settings)
        );
        contraption.put("Anchor", NbtUtils.writeBlockPos(placedAnchor));
        int verticalOffset = (int)Math.round(placedEntityPosition.y - authoredEntityPosition.y);
        if (contactRange != null) {
            contraption.putInt("MinContactY", contactRange[0]);
            contraption.putInt("MaxContactY", contactRange[1]);
        } else {
            relocateAbsoluteY(contraption, "MinContactY", verticalOffset);
            relocateAbsoluteY(contraption, "MaxContactY", verticalOffset);
        }

        ListTag actors = contraption.getList("Actors", Tag.TAG_COMPOUND);
        for (int index = 0; index < actors.size(); index++) {
            CompoundTag actor = actors.getCompound(index);
            Vec3 authoredActorPosition = readVec3(actor, "Position");
            if (authoredActorPosition == null) continue;
            Vec3 relative = authoredActorPosition.subtract(authoredEntityPosition);
            writeVec3(
                actor, "Position",
                placedEntityPosition.add(transformRelative(relative, settings))
            );
        }
        NbtUtils.readBlockPos(relocated, "ControllerRelative").ifPresent(relative ->
            relocated.put(
                "ControllerRelative",
                NbtUtils.writeBlockPos(transformRelative(relative, settings))
            )
        );
        relocateColumn(contraption, settings);
        return relocated;
    }

    private static void relocateAbsoluteY(CompoundTag tag, String key, int offset) {
        if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) tag.putInt(key, tag.getInt(key) + offset);
    }

    private static void relocateColumn(
        CompoundTag contraption, StructurePlaceSettings settings
    ) {
        if (!contraption.contains("Column", Tag.TAG_COMPOUND)) return;
        CompoundTag column = contraption.getCompound("Column");
        BlockPos transformed = transformRelative(
            new BlockPos(column.getInt("X"), 0, column.getInt("Z")), settings
        );
        column.putInt("X", transformed.getX());
        column.putInt("Z", transformed.getZ());
        Direction side = Direction.byName(column.getString("Side").toLowerCase(Locale.ROOT));
        if (side != null) {
            Direction transformedSide = settings.getRotation().rotate(
                settings.getMirror().mirror(side)
            );
            column.putString("Side", transformedSide.getName().toUpperCase(Locale.ROOT));
        }
    }

    private static BlockPos transformRelative(
        BlockPos position, StructurePlaceSettings settings
    ) {
        return StructureTemplate.transform(
            position, settings.getMirror(), settings.getRotation(), BlockPos.ZERO
        );
    }

    private static Vec3 transformRelative(Vec3 vector, StructurePlaceSettings settings) {
        double x = vector.x;
        double z = vector.z;
        switch (settings.getMirror()) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> {}
        }
        return switch (settings.getRotation()) {
            case COUNTERCLOCKWISE_90 -> new Vec3(z, vector.y, -x);
            case CLOCKWISE_90 -> new Vec3(-z, vector.y, x);
            case CLOCKWISE_180 -> new Vec3(-x, vector.y, -z);
            default -> new Vec3(x, vector.y, z);
        };
    }

    private static Vec3 readVec3(CompoundTag parent, String key) {
        ListTag values = parent.getList(key, Tag.TAG_DOUBLE);
        return values.size() == 3
            ? new Vec3(values.getDouble(0), values.getDouble(1), values.getDouble(2))
            : null;
    }

    private static void writeVec3(CompoundTag parent, String key, Vec3 value) {
        ListTag values = new ListTag();
        values.add(DoubleTag.valueOf(value.x));
        values.add(DoubleTag.valueOf(value.y));
        values.add(DoubleTag.valueOf(value.z));
        parent.put(key, values);
    }
}
