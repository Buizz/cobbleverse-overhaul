package dev.buizz.cobbleventure.bootstrap;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class CasinoInteriorStructureTest {
    @Test
    void authoredTeleportDestinationsHaveFloorSupport() throws Exception {
        CompoundTag structure;
        try (InputStream input = getClass().getResourceAsStream(
            "/data/cobbleventure/structure/interiors/casino.nbt"
        )) {
            structure = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        }

        assertFalse(stateAt(structure, 23, 0, 2).endsWith(":air"));
        assertFalse(stateAt(structure, 34, 0, 16).endsWith(":air"));
        assertFalse(
            stateAt(structure, 33, 0, 16).endsWith(":air"),
            () -> "Nearest supported floor positions: " + supportedNear(structure, 33, 16)
        );
    }

    private static String stateAt(CompoundTag structure, int x, int y, int z) {
        ListTag palette = structure.getList("palette", CompoundTag.TAG_COMPOUND);
        for (CompoundTag block : structure.getList("blocks", CompoundTag.TAG_COMPOUND)
            .stream().map(CompoundTag.class::cast).toList()) {
            ListTag position = block.getList("pos", CompoundTag.TAG_INT);
            if (position.getInt(0) == x && position.getInt(1) == y
                && position.getInt(2) == z) {
                return palette.getCompound(block.getInt("state")).getString("Name");
            }
        }
        return "minecraft:air";
    }

    private static List<String> supportedNear(CompoundTag structure, int centerX, int centerZ) {
        List<String> result = new ArrayList<>();
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                if (!stateAt(structure, x, 0, z).endsWith(":air")) {
                    result.add(x + ",1," + z);
                }
            }
        }
        return result.stream()
            .sorted((left, right) -> Integer.compare(
                distance(left, centerX, centerZ), distance(right, centerX, centerZ)
            ))
            .limit(20)
            .toList();
    }

    private static int distance(String position, int centerX, int centerZ) {
        String[] values = position.split(",");
        int dx = Integer.parseInt(values[0]) - centerX;
        int dz = Integer.parseInt(values[2]) - centerZ;
        return dx * dx + dz * dz;
    }
}
