package dev.buizz.cobbleventure.bootstrap;

final class NbtGroundFloorRules {
    private NbtGroundFloorRules() {
    }

    static boolean shouldPreserveWorldBlock(int localY, boolean air) {
        return localY == 0 && air;
    }
}
