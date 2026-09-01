package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("center", structures.getFirst().placementAnchor());
        assertEquals("dungeon_entry", structures.getFirst().dungeonConnections().getFirst().anchorId());
    }

    @Test
    void parsesRoadAlignedBuildingPlacement() {
        var structure = WorldStructureSystem.parse(
            JsonParser.parseString("""
                [{
                  "id": "bill_house",
                  "type": "structure",
                  "anchor": {"q": 10, "r": -5},
                  "resource": "cobbleventure:houses/bill_house",
                  "rotation": 3,
                  "properties": {"placement_anchor": "road_anchor"}
                }]
                """).getAsJsonArray()
        ).getFirst();

        assertEquals(3, structure.rotation());
        assertEquals("road_anchor", structure.placementAnchor());
    }

    @Test
    void parsesDoorAlignedBuildingPlacement() {
        var structure = WorldStructureSystem.parse(
            JsonParser.parseString("""
                [{
                  "id": "daycare",
                  "type": "structure",
                  "anchor": {"q": 6, "r": 0},
                  "resource": "cobbleventure:placeholder/daycare",
                  "rotation": 0,
                  "properties": {"placement_anchor": "door"}
                }]
                """).getAsJsonArray()
        ).getFirst();

        assertEquals("door", structure.placementAnchor());
    }

    @Test
    void explicitCenterPlacementOverridesAuthoredEntranceAnchors() {
        var structure = WorldStructureSystem.parse(
            JsonParser.parseString("""
                [{
                  "id": "indigo_plateau",
                  "type": "structure",
                  "anchor": {"q": -6, "r": -2},
                  "resource": "cobbleventure:league/kanto_league",
                  "rotation": 0,
                  "properties": {
                    "teleportable": true,
                    "center_placement": true,
                    "placement_anchor": "road_anchor"
                  }
                }]
                """).getAsJsonArray()
        ).getFirst();

        assertEquals("center", structure.placementAnchor());
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

    @Test
    void movesRoadAlignedEntranceOffTheRoadCenterline() {
        var center = new CobbleventureBootstrap.Point(100, 200);

        assertEquals(
            new CobbleventureBootstrap.Point(100, 193),
            WorldStructureSystem.offsetEntranceFromRoadCenter(
                center, Direction.SOUTH, 7
            )
        );
        assertEquals(
            new CobbleventureBootstrap.Point(107, 200),
            WorldStructureSystem.offsetEntranceFromRoadCenter(
                center, Direction.WEST, 7
            )
        );
    }

    @Test
    void movesTheEntireBuildingFootprintBesideAHorizontalRoad() {
        var placement = WorldStructureSystem.roadClearingPlacementPoint(
            List.of(
                new CobbleventureBootstrap.Point(0, 0),
                new CobbleventureBootstrap.Point(100, 0)
            ),
            new CobbleventureBootstrap.Point(50, 0),
            Direction.SOUTH,
            new BlockPos(10, 0, 0),
            new Vec3i(21, 12, 15),
            Rotation.NONE,
            6.0D
        );

        int structureMaximumZ = placement.z() + 14;
        assertTrue(placement.z() < 0);
        assertTrue(structureMaximumZ <= -6);
    }

    @Test
    void usesTheRoadNormalInsteadOfFollowingTheRoadDirection() {
        var placement = WorldStructureSystem.roadClearingPlacementPoint(
            List.of(
                new CobbleventureBootstrap.Point(0, -50),
                new CobbleventureBootstrap.Point(0, 50)
            ),
            new CobbleventureBootstrap.Point(0, 0),
            Direction.WEST,
            new BlockPos(0, 0, 7),
            new Vec3i(21, 12, 15),
            Rotation.NONE,
            6.0D
        );

        assertTrue(placement.x() >= 6);
    }
}
