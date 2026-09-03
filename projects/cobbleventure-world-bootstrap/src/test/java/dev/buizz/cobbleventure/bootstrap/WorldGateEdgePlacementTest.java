package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexGrid;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class WorldGateEdgePlacementTest {
    private static final HexGrid GRID = new HexGrid(
        100, new CobbleventureBootstrap.BlockPoint(0, 64, 0)
    );
    private static final HexCoord ANCHOR = new HexCoord(0, 0);

    @Test
    void oldMapGuideIsReplacedOnlyAtViridianGate() {
        Set<String> mapGuide = Set.of("cves_binding/cobbleventure/rewards/feature_map_guide");
        assertTrue(WorldGateSystem.isObsoleteGateNpc("viridian_gate", mapGuide));
        assertFalse(WorldGateSystem.isObsoleteGateNpc("starter_town_north_gate", mapGuide));
        assertFalse(WorldGateSystem.isObsoleteGateNpc("viridian_gate",
            Set.of("cves_binding/cobbleventure/story/viridian_gatekeeper")));
    }

    @Test
    void legacyDefaultGateResourceResolvesToThePackagedNbt() {
        var objects = JsonParser.parseString("""
            [{
              "id": "legacy_gate",
              "type": "gate",
              "anchor": {"q": 0, "r": 0},
              "resource": "cobbleventure:gate/default",
              "properties": {}
            }]
            """).getAsJsonArray();

        assertEquals(
            "cobbleventure:gate/default_gate",
            WorldGateSystem.parse(objects).getFirst().structure()
        );
    }

    @Test
    void eastAndWestUseTheirSingleFaceCenter() {
        assertEquals(
            new CobbleventureBootstrap.Point(87, 0),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "east", ignored -> false)
        );
        assertEquals(
            new CobbleventureBootstrap.Point(-87, 0),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "west", ignored -> false)
        );
    }

    @Test
    void eastAndWestNaturalGateShouldersCanReachTheirOuterBarrier() {
        assertTrue(WorldGateSystem.gateHasOpenFace(
            "east", offset -> offset.equals(new HexCoord(1, 0))
        ));
        assertTrue(WorldGateSystem.gateHasOpenFace(
            "west", offset -> offset.equals(new HexCoord(-1, 0))
        ));
    }

    @Test
    void northAlwaysStaysCenteredBetweenBothFaces() {
        assertEquals(
            new CobbleventureBootstrap.Point(0, -100),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "north", ignored -> true)
        );
    }

    @Test
    void northUsesTheOnlyOpenFace() {
        assertEquals(
            new CobbleventureBootstrap.Point(-44, -75),
            WorldGateSystem.gateEdgeCenter(
                GRID, ANCHOR, "north", offset -> offset.equals(new HexCoord(0, -1))
            )
        );
    }

    @Test
    void southAlwaysStaysCenteredBetweenBothFaces() {
        assertEquals(
            new CobbleventureBootstrap.Point(0, 100),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "south", ignored -> true)
        );
    }

    @Test
    void obstacleSpanMatchesTheSelectedHexBoundary() {
        assertEquals(30, WorldGateSystem.gateBoundaryHalfLength(64, "east", 1, 3));
        assertEquals(26, WorldGateSystem.gateBoundaryHalfLength(64, "north", 1, 3));
        assertEquals(53, WorldGateSystem.gateBoundaryHalfLength(64, "north", 2, 3));
    }

    @Test
    void naturalObstacleUsesAShallowBoundaryBand() {
        assertEquals(6, WorldGateSystem.naturalGateBoundaryDepth(64, 2));
        assertEquals(9, WorldGateSystem.naturalGateBoundaryDepth(64, 7));
    }

    @Test
    void gateApproachNeverChangesMoreThanOneBlockAtATime() {
        assertEquals(65, WorldGateSystem.nextGateApproachY(64, 70));
        assertEquals(63, WorldGateSystem.nextGateApproachY(64, 58));
        assertEquals(64, WorldGateSystem.nextGateApproachY(64, 64));
    }

    @Test
    void gateDialogueStopsJustOutsideTheWholeTriggerWithoutRecentering() {
        var gate = denialGate("east", "gate_npc");
        var center = new CobbleventureBootstrap.Point(0, 0);
        Vec3 approach = new Vec3(2.3D, 64.5D, 1.25D);
        Vec3 stopped = WorldGateSystem.gateDenialStopPosition(GRID, gate, center, approach);

        assertEquals(2.8D, stopped.x, 1.0E-6D);
        assertEquals(approach.y, stopped.y);
        assertEquals(approach.z, stopped.z);
        assertTrue(stopped.distanceTo(approach) < 0.6D);
        assertFalse(WorldGateSystem.insideGateTriggerZone(stopped.x, stopped.z, 2.15D, 7));
        assertEquals(stopped, WorldGateSystem.gateDenialStopPosition(GRID, gate, center, stopped));
    }

    @Test
    void talkingFromASafeApproachDoesNotMoveThePlayerTowardTheGate() {
        Vec3 approach = new Vec3(6.0D, 64.5D, 1.25D);
        assertEquals(approach, WorldGateSystem.gateDenialStopPosition(
            GRID, denialGate("east", "gate_npc"),
            new CobbleventureBootstrap.Point(0, 0), approach
        ));
    }

    @Test
    void hexGateDenialKeepsTheActualBypassLocationInsteadOfTheGateCenter() {
        for (String placement : List.of("npc", "gate_npc", "gate")) {
            Vec3 approach = new Vec3(-86.4D, 64.5D, 35.0D);
            var center = new CobbleventureBootstrap.Point(-87, 0);
            Vec3 stopped = WorldGateSystem.gateDenialStopPosition(
                GRID, denialGate("west", placement), center, approach
            );
            assertEquals(approach, stopped, placement);
            assertEquals(GRID.worldToHex(approach.x, approach.z), GRID.worldToHex(stopped.x, stopped.z));
        }
    }

    @Test
    void insetNorthSouthGateStopsOnTheSameHexAndPreservesStairHeight() {
        for (String facing : List.of("north", "south")) {
            var gate = denialGate(facing, "gate_npc");
            var center = new CobbleventureBootstrap.Point(0, facing.equals("north") ? -84 : 84);
            for (double side : new double[] {-1.0D, 1.0D}) {
                Vec3 approach = new Vec3(1.25D, 64.5D, center.z() + side * 2.3D);
                Vec3 stopped = WorldGateSystem.gateDenialStopPosition(GRID, gate, center, approach);
                assertEquals(approach.x, stopped.x);
                assertEquals(approach.y, stopped.y);
                assertEquals(GRID.worldToHex(approach.x, approach.z), GRID.worldToHex(stopped.x, stopped.z));
                assertFalse(WorldGateSystem.insideGateTriggerZone(
                    stopped.z - center.z(), stopped.x, 2.15D, 7
                ));
            }
        }
    }

    @Test
    void gateHoldLetsGravitySettleWithoutVerticalTeleportCorrections() {
        Vec3 stopped = new Vec3(2.8D, 65.0D, 1.25D);
        assertFalse(WorldGateSystem.gateHoldNeedsCorrection(stopped, stopped));
        assertFalse(WorldGateSystem.gateHoldNeedsCorrection(new Vec3(2.8D, 64.5D, 1.25D), stopped));
        assertTrue(WorldGateSystem.gateHoldNeedsCorrection(new Vec3(2.6D, 64.5D, 1.25D), stopped));
    }

    private static WorldGateSystem.Gate denialGate(String facing, String placement) {
        return WorldGateSystem.parse(JsonParser.parseString("""
            [{
              "id": "denial_test_gate", "type": "gate",
              "anchor": {"q": 0, "r": 0},
              "properties": {
                "facing": "%s", "center_placement": "%s",
                "npc": "easy_npc:preset/encounter/test.npc.snbt",
                "wall_thickness": 5, "passage_width": 7
              }
            }]
            """.formatted(facing, placement)).getAsJsonArray()).getFirst();
    }

    @Test
    void gateStructureUsesTheLowerOfItsTwoRoadEntrances() {
        assertEquals(
            61,
            WorldGateSystem.lowestRoadAlignedGateOriginY(
                64, List.of(65, 61)
            )
        );
        assertEquals(
            64,
            WorldGateSystem.lowestRoadAlignedGateOriginY(64, List.of())
        );
    }

    @Test
    void gateApproachUsesStairsThatRiseTowardTheHigherNeighbor() {
        assertEquals(
            Direction.SOUTH,
            WorldGateSystem.gateApproachAscent(65, 64, 63, Direction.NORTH)
        );
        assertEquals(
            Direction.NORTH,
            WorldGateSystem.gateApproachAscent(63, 64, 65, Direction.NORTH)
        );
        assertEquals(
            null,
            WorldGateSystem.gateApproachAscent(64, 64, 64, Direction.NORTH)
        );
    }

    @Test
    void gateApproachCoversTheWholeAuthoredEntranceWidth() {
        BlockPos anchor = new BlockPos(14, 1, 2);
        WorldGateSystem.GateApproachWidth width =
            WorldGateSystem.contiguousGateApproachWidth(
                anchor, Direction.EAST, Set.of(
                    new BlockPos(13, 1, 2),
                    new BlockPos(15, 1, 2),
                    new BlockPos(16, 1, 2)
                )
            );

        assertEquals(-1, width.minimumLateral());
        assertEquals(2, width.maximumLateral());
    }

    @Test
    void wideGateTriggerCoversTheWholeAuthoredOpening() {
        assertTrue(WorldGateSystem.crossedGateOpening(
            3.0D, 15.4D, 1.0D, 15.4D, 2.15D, 31
        ));
        assertTrue(WorldGateSystem.crossedGateOpening(
            -3.0D, -15.4D, -1.0D, -15.4D, 2.15D, 31
        ));
        assertFalse(WorldGateSystem.crossedGateOpening(
            3.0D, 17.0D, 1.0D, 17.0D, 2.15D, 31
        ));
    }

    @Test
    void diagonalMovementUsesItsGatePlaneIntersection() {
        assertTrue(WorldGateSystem.crossedGateOpening(
            3.0D, 4.0D, 1.0D, 8.0D, 2.15D, 11
        ));
        assertFalse(WorldGateSystem.crossedGateOpening(
            3.0D, 9.0D, 1.0D, 4.0D, 2.15D, 11
        ));
    }

    @Test
    void lockedPlayerAlreadyInsideTheWideOpeningCannotUseATrackingGap() {
        assertTrue(WorldGateSystem.insideGateTriggerZone(
            0.0D, 15.9D, 2.15D, 31
        ));
        assertTrue(WorldGateSystem.insideGateTriggerZone(
            2.4D, -15.9D, 2.15D, 31
        ));
        assertFalse(WorldGateSystem.insideGateTriggerZone(
            0.0D, 17.0D, 2.15D, 31
        ));
        assertFalse(WorldGateSystem.insideGateTriggerZone(
            3.0D, 0.0D, 2.15D, 31
        ));
    }

    @Test
    void gateDialogueApproachFollowsTheRoadInsteadOfACircle() {
        assertTrue(WorldGateSystem.insideGateDialogueApproach(
            7.9D, 6.4D, 11
        ));
        assertFalse(WorldGateSystem.insideGateDialogueApproach(
            0.0D, 6.6D, 11
        ));
        assertFalse(WorldGateSystem.insideGateDialogueApproach(
            8.1D, 0.0D, 11
        ));
    }

    @Test
    void gateBlocksTheAuthoredHexBoundaryInBothDirections() {
        HexCoord anchor = new HexCoord(0, 0);

        assertTrue(WorldGateSystem.crossedGateHexBoundary(
            anchor, "east", anchor, new HexCoord(1, 0)
        ));
        assertTrue(WorldGateSystem.crossedGateHexBoundary(
            anchor, "east", new HexCoord(1, 0), anchor
        ));
        assertTrue(WorldGateSystem.crossedGateHexBoundary(
            anchor, "south", anchor, new HexCoord(-1, 1)
        ));
        assertTrue(WorldGateSystem.crossedGateHexBoundary(
            anchor, "south", anchor, new HexCoord(0, 1)
        ));
    }

    @Test
    void gateDoesNotReactToMovementWithinOrAlongOtherHexEdges() {
        HexCoord anchor = new HexCoord(0, 0);

        assertFalse(WorldGateSystem.crossedGateHexBoundary(
            anchor, "east", anchor, anchor
        ));
        assertFalse(WorldGateSystem.crossedGateHexBoundary(
            anchor, "east", anchor, new HexCoord(0, 1)
        ));
        assertFalse(WorldGateSystem.crossedGateHexBoundary(
            anchor, "south", new HexCoord(-1, 1), new HexCoord(0, 1)
        ));
    }

    @Test
    void viridianHexBoundaryIsGuardedForEveryOrdinaryGateType() {
        for (String placement : List.of("gate", "gate_npc", "npc", "pokemon")) {
            var objects = JsonParser.parseString("""
                [{
                  "id": "viridian_gate",
                  "type": "gate",
                  "anchor": {"q": -5, "r": 4},
                  "rotation": 1,
                  "resource": "cobbleventure:gate/default_gate",
                  "properties": {
                    "facing": "west",
                    "center_placement": "%s",
                    "passage_width": 7
                  }
                }]
                  """.formatted(placement)).getAsJsonArray();
            if (placement.equals("pokemon")) objects.get(0).getAsJsonObject().getAsJsonObject("properties")
                .add("pokemon", JsonParser.parseString("""
                    {"species":"cobblemon:snorlax","level":30,"collision":{"width":7,"height":2,"depth":4}}
                    """));
            var gate = WorldGateSystem.parse(objects).getFirst();
            HexCoord citySide = new HexCoord(-5, 4);
            HexCoord leagueSide = new HexCoord(-6, 4);

            assertTrue(WorldGateSystem.crossedGateHexBoundary(gate, citySide, leagueSide), placement);
            assertTrue(WorldGateSystem.crossedGateHexBoundary(gate, leagueSide, citySide), placement);
            assertFalse(WorldGateSystem.crossedGateHexBoundary(gate, citySide, citySide), placement);
            assertFalse(WorldGateSystem.crossedGateHexBoundary(gate, citySide, new HexCoord(-5, 5)), placement);
        }
    }
}
