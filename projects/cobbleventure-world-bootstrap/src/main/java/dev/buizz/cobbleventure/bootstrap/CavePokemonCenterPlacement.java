package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import net.minecraft.core.Direction;

/** Pure placement geometry for a Pokemon Center beside a cave approach road. */
final class CavePokemonCenterPlacement {
    private static final double ALONG_ROAD_DISTANCE = 28.0D;
    private static final double ROAD_SIDE_DISTANCE = 12.0D;

    private CavePokemonCenterPlacement() {}

    static Site resolve(
        CobbleventureBootstrap.Point entranceCenter,
        CobbleventureBootstrap.Point preferredCenter,
        List<CobbleventureBootstrap.Point> centerline,
        boolean entranceAtStart
    ) {
        double preferredX = preferredCenter.x() - entranceCenter.x();
        double preferredZ = preferredCenter.z() - entranceCenter.z();
        if (centerline.size() < 2) {
            double length = Math.max(1.0D, Math.hypot(preferredX, preferredZ));
            var center = new CobbleventureBootstrap.Point(
                entranceCenter.x() + (int) Math.round(
                    preferredX / length * ALONG_ROAD_DISTANCE
                ),
                entranceCenter.z() + (int) Math.round(
                    preferredZ / length * ALONG_ROAD_DISTANCE
                )
            );
            return new Site(
                center, entranceCenter,
                horizontalDirection(
                    entranceCenter.x() - center.x(), entranceCenter.z() - center.z()
                )
            );
        }

        var endpoint = entranceAtStart ? centerline.getFirst() : centerline.getLast();
        var adjacent = entranceAtStart ? centerline.get(1)
            : centerline.get(centerline.size() - 2);
        double roadX = adjacent.x() - endpoint.x();
        double roadZ = adjacent.z() - endpoint.z();
        double roadLength = Math.hypot(roadX, roadZ);
        if (roadLength < 1.0D) {
            return resolve(entranceCenter, preferredCenter, List.of(), entranceAtStart);
        }
        roadX /= roadLength;
        roadZ /= roadLength;

        double sideX = -roadZ;
        double sideZ = roadX;
        if (sideX * preferredX + sideZ * preferredZ < 0.0D) {
            sideX = -sideX;
            sideZ = -sideZ;
        }
        var roadPoint = new CobbleventureBootstrap.Point(
            entranceCenter.x() + (int) Math.round(roadX * ALONG_ROAD_DISTANCE),
            entranceCenter.z() + (int) Math.round(roadZ * ALONG_ROAD_DISTANCE)
        );
        var center = new CobbleventureBootstrap.Point(
            roadPoint.x() + (int) Math.round(sideX * ROAD_SIDE_DISTANCE),
            roadPoint.z() + (int) Math.round(sideZ * ROAD_SIDE_DISTANCE)
        );
        return new Site(
            center, roadPoint,
            horizontalDirection(roadPoint.x() - center.x(), roadPoint.z() - center.z())
        );
    }

    private static Direction horizontalDirection(double x, double z) {
        return Math.abs(x) >= Math.abs(z)
            ? x >= 0.0D ? Direction.EAST : Direction.WEST
            : z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    record Site(
        CobbleventureBootstrap.Point center,
        CobbleventureBootstrap.Point roadPoint,
        Direction roadFacing
    ) {}
}
