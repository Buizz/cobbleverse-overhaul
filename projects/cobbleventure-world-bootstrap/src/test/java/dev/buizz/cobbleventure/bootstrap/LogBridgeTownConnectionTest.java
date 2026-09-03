package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap.Point;

final class LogBridgeTownConnectionTest {
    @Test
    void extendsVermilionDeckAcrossTheWaterGapToTheCompiledRoad() {
        // Triangle-down town center (1265,340), compiled exit (0,32).
        // The old regional centerline stops at (1265,405), still over water.
        List<Point> regional = List.of(new Point(1265, 405), new Point(1265, 463));
        Point townRoad = new Point(1265, 372);
        List<Point> deck = RegionalRouteGeometry.connectLogBridgeTownRoads(
            regional, townRoad, null
        );
        assertEquals(List.of(townRoad, regional.getFirst(), regional.getLast()), deck);
        assertEquals(new Point(1265, 405), regional.getFirst());
        for (int z = townRoad.z(); z <= regional.getLast().z(); z++) {
            assertTrue(covers(deck, new Point(1265, z)), "Missing deck at z=" + z);
        }
    }

    @Test
    void connectsBothEndsInAllCardinalOrientationsWithoutChangingInteriorBends() {
        for (int rotation = 0; rotation < 4; rotation++) {
            int turns = rotation;
            List<Point> regional = List.of(
                new Point(0, 0), new Point(20, 0), new Point(20, 20)
            ).stream().map(point -> rotate(point, turns)).toList();
            Point fromRoad = rotate(new Point(-15, -8), turns);
            Point toRoad = rotate(new Point(30, 35), turns);
            List<Point> deck = RegionalRouteGeometry.connectLogBridgeTownRoads(
                regional, fromRoad, toRoad
            );
            assertEquals(fromRoad, deck.getFirst());
            assertEquals(toRoad, deck.getLast());
            assertEquals(regional, deck.subList(2, 5));
            for (int i = 1; i < deck.size(); i++) {
                Point a = deck.get(i - 1), b = deck.get(i);
                assertTrue(a.x() == b.x() || a.z() == b.z());
            }
        }
    }

    @Test
    void leavesUnconnectedEndsAndAlreadyConnectedDecksUnchanged() {
        List<Point> regional = List.of(new Point(0, 0), new Point(20, 0));
        assertEquals(regional, RegionalRouteGeometry.connectLogBridgeTownRoads(
            regional, null, null
        ));
        assertEquals(regional, RegionalRouteGeometry.connectLogBridgeTownRoads(
            regional, regional.getFirst(), regional.getLast()
        ));
        List<Point> connected = RegionalRouteGeometry.connectLogBridgeTownRoads(
            regional, new Point(-10, 0), new Point(30, 0)
        );
        assertEquals(connected, RegionalRouteGeometry.connectLogBridgeTownRoads(
            connected, new Point(-10, 0), new Point(30, 0)
        ));
    }

    @Test
    void usesWoodOnDeepAndShallowWaterButNotOnDryShore() {
        for (int groundY = 44; groundY < 64; groundY++) {
            assertTrue(RegionalRouteGeometry.logBridgeUsesWood(groundY, 64));
        }
        for (int groundY = 64; groundY <= 80; groundY++) {
            assertFalse(RegionalRouteGeometry.logBridgeUsesWood(groundY, groundY));
        }
        assertFalse(RegionalRouteGeometry.logBridgeUsesWood(69, 64));
    }

    @Test
    void keepsDryRoadAtGroundLevelAndOnlyRaisesAscendingStairs() {
        for (int groundY = 64; groundY <= 80; groundY++) {
            assertEquals(groundY, RegionalRouteGeometry.logBridgeLandSurfaceY(groundY, false));
            assertEquals(groundY + 1, RegionalRouteGeometry.logBridgeLandSurfaceY(groundY, true));
        }
    }

    @Test
    void locatesOldRaisedLandDecksForRemovalWithoutUsingThatHeightForNewRoads() {
        for (int groundY = 64; groundY <= 80; groundY++) {
            int oldDeckY = RegionalRouteGeometry.legacyLogBridgeDeckY(64, groundY);
            assertEquals(groundY + 1, oldDeckY);
            assertEquals(oldDeckY - 1,
                RegionalRouteGeometry.logBridgeLandSurfaceY(groundY, false));
            assertEquals(oldDeckY,
                RegionalRouteGeometry.logBridgeLandSurfaceY(groundY, true));
        }
    }

    private static Point rotate(Point point, int turns) {
        for (int i = 0; i < turns; i++) point = new Point(-point.z(), point.x());
        return point;
    }

    private static boolean covers(List<Point> points, Point point) {
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            if (point.x() >= Math.min(a.x(), b.x()) && point.x() <= Math.max(a.x(), b.x())
                && point.z() >= Math.min(a.z(), b.z()) && point.z() <= Math.max(a.z(), b.z())) {
                return true;
            }
        }
        return false;
    }
}
