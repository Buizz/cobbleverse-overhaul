package dev.buizz.cobbleventure.playermenu.client;

final class StarterRouletteLayout {
    private static final float MIN_MODEL_SCALE = 0.9F;
    private static final float MAX_MODEL_SCALE = 2.6F;

    private StarterRouletteLayout() {}

    static float modelScaleForSize(int size, int maximumSize) {
        return Math.clamp(
            MAX_MODEL_SCALE * size / (float) maximumSize,
            MIN_MODEL_SCALE,
            MAX_MODEL_SCALE
        );
    }
}
