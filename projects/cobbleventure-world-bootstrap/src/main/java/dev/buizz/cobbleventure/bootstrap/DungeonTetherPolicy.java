package dev.buizz.cobbleventure.bootstrap;

/** Pure distance classification shared by runtime tether checks and tests. */
final class DungeonTetherPolicy {
    private DungeonTetherPolicy() {}

    static Zone classify(
        double distanceSquared, int warningDistance, int maximumDistance
    ) {
        if (distanceSquared > (double) maximumDistance * maximumDistance) {
            return Zone.EXCEEDED;
        }
        if (distanceSquared > (double) warningDistance * warningDistance) {
            return Zone.WARNING;
        }
        return Zone.TOGETHER;
    }

    enum Zone {
        TOGETHER,
        WARNING,
        EXCEEDED
    }
}
