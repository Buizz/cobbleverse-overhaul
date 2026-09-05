package dev.buizz.cobbleventure.playermenu.client;

/** Pure timing rule for deciding when a streamed BGM pass really ended. */
final class MusicLoopPolicy {
    private MusicLoopPolicy() {}

    static boolean shouldRestartPass(int elapsedTicks, boolean active) {
        return elapsedTicks >= 20 * 30 && !active;
    }
}
