package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.playermenu.MusicPlayback;
import net.minecraft.server.level.ServerPlayer;

/** Restores the correct building BGM after doors, scripted teleports, and login placement. */
final class InteriorMusicSystem {
    private InteriorMusicSystem() {
    }

    static void sync(ServerPlayer player) {
        if (GymInteriorSystem.isInteriorDimension(player.serverLevel())) {
            MusicPlayback.enterInterior(player, GymInteriorSystem.interiorMusicTrack());
            return;
        }
        if (BuildingRuntimeSystem.isInteriorDimension(player.serverLevel())) {
            MusicPlayback.enterInterior(player, BuildingRuntimeSystem.interiorMusicTrackAt(player));
            return;
        }
        MusicPlayback.leaveInterior(player);
    }
}
