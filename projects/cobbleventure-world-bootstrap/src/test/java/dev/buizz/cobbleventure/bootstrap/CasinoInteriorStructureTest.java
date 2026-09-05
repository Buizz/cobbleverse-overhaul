package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class CasinoInteriorStructureTest {
    @Test
    void authoredNpcPositionsHaveFloorSupport() throws Exception {
        CompoundTag structure;
        try (InputStream input = getClass().getResourceAsStream(
            "/data/cobbleventure/structure/interiors/casino.nbt"
        )) {
            assertNotNull(input);
            structure = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        }

        try (InputStream input = getClass().getResourceAsStream(
            "/data/cobbleventure/structure_metadata/interiors/casino.structure.json"
        )) {
            assertNotNull(input);
            var metadata = JsonParser.parseReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            for (var anchorElement : metadata.getAsJsonArray("anchors")) {
                var anchor = anchorElement.getAsJsonObject();
                if (!"npc_position".equals(anchor.get("type").getAsString())) {
                    continue;
                }
                var position = anchor.getAsJsonArray("position");
                int x = position.get(0).getAsInt();
                int y = position.get(1).getAsInt();
                int z = position.get(2).getAsInt();
                assertFalse(
                    stateAt(structure, x, y - 1, z).endsWith(":air"),
                    () -> "Unsupported casino NPC position "
                        + anchor.get("label") + ": " + x + "," + y + "," + z
                        + "; nearest supported positions: "
                        + supportedNear(structure, x, y - 1, z)
                );
            }
        }
    }

    @Test
    void authoredTeleportDestinationsHaveFloorSupport() throws Exception {
        CompoundTag structure;
        try (InputStream input = getClass().getResourceAsStream(
            "/data/cobbleventure/structure/interiors/casino.nbt"
        )) {
            structure = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        }

        try (InputStream input = getClass().getResourceAsStream(
            "/data/cobbleventure/structure_metadata/interiors/casino.structure.json"
        )) {
            assertNotNull(input);
            var metadata = JsonParser.parseReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            for (var anchorElement : metadata.getAsJsonArray("anchors")) {
                var anchor = anchorElement.getAsJsonObject();
                if (!anchor.has("safe_spawn")) {
                    continue;
                }
                var spawn = anchor.getAsJsonArray("safe_spawn");
                int x = spawn.get(0).getAsInt();
                int y = spawn.get(1).getAsInt();
                int z = spawn.get(2).getAsInt();
                assertFalse(
                    stateAt(structure, x, y - 1, z).endsWith(":air"),
                    () -> "Unsupported safe_spawn for casino anchor "
                        + anchor.get("id") + ": " + x + "," + y + "," + z
                        + "; nearest supported positions: "
                        + supportedNear(structure, x, y - 1, z)
                );
            }
        }
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

    private static List<String> supportedNear(
        CompoundTag structure, int centerX, int y, int centerZ
    ) {
        List<String> result = new ArrayList<>();
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                if (!stateAt(structure, x, y, z).endsWith(":air")) {
                    result.add(x + "," + (y + 1) + "," + z);
                }
            }
        }
        return result.stream()
            .sorted((left, right) -> Integer.compare(
                distance(left, centerX, centerZ), distance(right, centerX, centerZ)
            ))
            .limit(12)
            .toList();
    }

    private static int distance(String position, int centerX, int centerZ) {
        String[] values = position.split(",");
        int dx = Integer.parseInt(values[0]) - centerX;
        int dz = Integer.parseInt(values[2]) - centerZ;
        return dx * dx + dz * dz;
    }
}
