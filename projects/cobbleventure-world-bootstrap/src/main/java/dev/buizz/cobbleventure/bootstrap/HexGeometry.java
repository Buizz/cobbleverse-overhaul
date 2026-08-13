package dev.buizz.cobbleventure.bootstrap;

import java.util.Set;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexBounds;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;

/** Pure coordinate conversions for the authored pointy-top hex grid. */
final class HexGeometry {
    private HexGeometry() {}

    static CobbleventureBootstrap.Point worldCenter(
        int radius, CobbleventureBootstrap.BlockPoint origin, HexCoord cell
    ) {
        int x = (int) Math.round(
            origin.x() + radius * Math.sqrt(3.0D) * (cell.q() + cell.r() / 2.0D)
        );
        int z = (int) Math.round(origin.z() + radius * 1.5D * cell.r());
        return new CobbleventureBootstrap.Point(x, z);
    }

    static HexCoord worldToHex(
        int radius, CobbleventureBootstrap.BlockPoint origin, double x, double z
    ) {
        double localX = x - origin.x();
        double localZ = z - origin.z();
        double qValue = (Math.sqrt(3.0D) / 3.0D * localX - localZ / 3.0D) / radius;
        double rValue = (2.0D / 3.0D * localZ) / radius;
        double sValue = -qValue - rValue;
        int q = (int) Math.round(qValue);
        int r = (int) Math.round(rValue);
        int s = (int) Math.round(sValue);
        double qDifference = Math.abs(q - qValue);
        double rDifference = Math.abs(r - rValue);
        double sDifference = Math.abs(s - sValue);
        if (qDifference > rDifference && qDifference > sDifference) q = -r - s;
        else if (rDifference > sDifference) r = -q - s;
        return new HexCoord(q, r);
    }

    static HexBounds bounds(
        int radius, CobbleventureBootstrap.BlockPoint origin,
        Set<HexCoord> cells
    ) {
        if (cells.isEmpty()) throw new IllegalStateException("Hex world contains no cells");
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (HexCoord cell : cells) {
            CobbleventureBootstrap.Point center = worldCenter(radius, origin, cell);
            minX = Math.min(minX, center.x() - radius);
            minZ = Math.min(minZ, center.z() - radius);
            maxX = Math.max(maxX, center.x() + radius);
            maxZ = Math.max(maxZ, center.z() + radius);
        }
        return new HexBounds(minX, minZ, maxX, maxZ);
    }
}
