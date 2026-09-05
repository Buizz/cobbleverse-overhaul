package dev.buizz.cobbleventure.playermenu;

/** Pure state rules used by the server-side authored music coordinator. */
final class MusicPlaybackPolicy {
    private MusicPlaybackPolicy() {}

    static boolean shouldInvalidatePlayingTrack(
        String removedContext, String playingTrack
    ) {
        return removedContext != null && removedContext.equals(playingTrack);
    }
}
