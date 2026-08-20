package dev.buizz.cobbleventure.adventure.event;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Measures scripted NPC range against the NPC's interactable bounds. */
final class EventNpcInteractionRange {
    private static final double DIRECT_CLICK_TOLERANCE = 0.25D;

    private EventNpcInteractionRange() {}

    static double directClickRange(double scriptedRange, double playerInteractionRange) {
        return Math.max(scriptedRange, playerInteractionRange) + DIRECT_CLICK_TOLERANCE;
    }

    static boolean contains(Vec3 playerPosition, AABB targetBounds, double range) {
        return distanceToBoundsSqr(playerPosition, targetBounds) <= range * range;
    }

    static double distanceToBoundsSqr(Vec3 point, AABB bounds) {
        double dx = axisDistance(point.x(), bounds.minX, bounds.maxX);
        double dy = axisDistance(point.y(), bounds.minY, bounds.maxY);
        double dz = axisDistance(point.z(), bounds.minZ, bounds.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    static double distanceToBounds(Vec3 point, AABB bounds) {
        return Math.sqrt(distanceToBoundsSqr(point, bounds));
    }

    private static double axisDistance(double value, double minimum, double maximum) {
        if (value < minimum) return minimum - value;
        if (value > maximum) return value - maximum;
        return 0.0D;
    }
}
