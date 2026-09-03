package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

final class ForestGateHeightTest {
    private static final Path CONTENT = Path.of(
        "../../content-projects/cobbleventure-main/content"
    );

    @Test
    void southGateUsesRaisedRoadInsteadOfTheBuriedDefaultFloor() throws IOException {
        JsonObject forest = viridianForest();
        // Actual NBT: road (15,0,2), forest entry (15,3,6).
        // Placing the entry at (0,160) puts the playable road anchor at (0,156).
        CompoundTag nbt = NbtIo.readCompressed(
            CONTENT.resolve("structures/forest_gate/forest_gate.nbt"),
            NbtAccounter.unlimitedHeap()
        );
        ListTag blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
        CompoundTag anchor = null;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (block.getCompound("nbt").getString("name").equals("cobbleventure:road_anchor")
                && block.getList("pos", Tag.TAG_INT).getInt(2) == 2) anchor = block;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(anchor);
        int localAnchorY = anchor.getList("pos", Tag.TAG_INT).getInt(1);
        assertEquals(0, localAnchorY);
        assertEquals(70, ForestDimensionGenerator.plannedSurfaceY(forest, 0, 156));
        assertEquals(70, WorldGateSystem.forestGateRoadAlignedOriginY(
            forest, 0, 156, localAnchorY
        ));
        assertEquals(2, WorldGateSystem.forestGateRoadAlignedOriginY(
            forest, 0, 156, localAnchorY
        ) - (forest.getAsJsonObject("dimension").getAsJsonObject("origin").get("y").getAsInt() - 1));
    }

    @Test
    void northGateKeepsItsUnraisedRoadHeight() throws IOException {
        assertEquals(68, WorldGateSystem.forestGateRoadAlignedOriginY(
            viridianForest(), 0, -156, 0
        ));
    }

    @Test
    void subtractsTheRoadAnchorOffsetInsteadOfAssumingTheBottomLayer() throws IOException {
        assertEquals(67, WorldGateSystem.forestGateRoadAlignedOriginY(
            viridianForest(), 0, 156, 3
        ));
    }

    @Test
    void usesRoadLocationAndSupportsMovedForestOrigins() throws IOException {
        JsonObject forest = viridianForest();
        // Portal/default terrain is level 68 here, while the adjacent authored road is 70.
        assertEquals(68, ForestDimensionGenerator.plannedSurfaceY(forest, 0, 180));
        assertEquals(70, WorldGateSystem.forestGateRoadAlignedOriginY(forest, 0, 156, 0));
        JsonObject origin = forest.getAsJsonObject("dimension").getAsJsonObject("origin");
        origin.addProperty("x", 1000);
        origin.addProperty("z", -2000);
        origin.addProperty("y", 89);
        assertEquals(90, WorldGateSystem.forestGateRoadAlignedOriginY(
            forest, 1000, -1844, 0
        ));
    }

    private static JsonObject viridianForest() throws IOException {
        return JsonParser.parseString(Files.readString(
            CONTENT.resolve("forests/generation_1/viridian_forest.json")
        )).getAsJsonObject();
    }
}
