package dev.buizz.cobbleventure.playermenu.client;

/** Size policy shared by both trainer portraits in the battle intro. */
final class BattleIntroPortraitLayout {
    static final float PORTRAIT_SCALE = 0.8F;

    private BattleIntroPortraitLayout() {}

    static int scaleForHeight(int availableHeight) {
        int originalScale = Math.max(42, Math.min(96, availableHeight - 8));
        return Math.round(originalScale * PORTRAIT_SCALE);
    }
}
