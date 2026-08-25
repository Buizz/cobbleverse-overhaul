package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorldStructureSystemTest {
    @Test
    void parsesVillainBaseDungeonConnection() {
        var structures = WorldStructureSystem.parse(
            JsonParser.parseString("""
                [{
                  "id": "rocket_power_plant",
                  "type": "villain_base",
                  "anchor": {"q": 12, "r": -2},
                  "resource": "cobbleventure:placeholder/power_plant",
                  "rotation": 0,
                  "connections": [{
                    "from": "structure:dungeon_entry",
                    "target": {
                      "type": "dungeon",
                      "entrance_id": "cobbleventure:entrance/rocket_power_plant"
                    }
                  }]
                }]
                """).getAsJsonArray()
        );

        assertEquals(1, structures.size());
        assertEquals(12, structures.getFirst().anchor().q());
        assertEquals(-2, structures.getFirst().anchor().r());
        assertEquals("dungeon_entry", structures.getFirst().dungeonConnections().getFirst().anchorId());
    }

    @Test
    void rotatesTemplateOriginAroundMinimumFootprintCorner() {
        assertEquals(
            new BlockPos(131, 70, 200),
            WorldStructureSystem.rotatedTemplateOrigin(
                100, 70, 200, 48, 32, Rotation.CLOCKWISE_90
            )
        );
        assertEquals(
            new BlockPos(147, 70, 231),
            WorldStructureSystem.rotatedTemplateOrigin(
                100, 70, 200, 48, 32, Rotation.CLOCKWISE_180
            )
        );
    }
}
