package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Owns the single looping Cobbleventure BGM instance on the client. */
public final class LoopingMusic {
    private static final float DEFAULT_VOLUME = 0.6F;
    private static final int TRANSITION_TICKS = 30;
    private static FadingLoopingMusic current;
    private static boolean authoredMusicActive;

    private LoopingMusic() {}

    public static void play(ResourceLocation soundEvent) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            authoredMusicActive = true;
            // Vanilla music is managed separately from ordinary MUSIC sounds.
            // Stop its current instance before starting Cobbleventure's loop;
            // MusicManagerMixin prevents it from scheduling another one.
            minecraft.getMusicManager().stopPlaying();
            if (current != null
                && current.soundEvent().equals(soundEvent)
                && minecraft.getSoundManager().isActive(current)) {
                current.fadeIn();
                return;
            }
            if (current != null && minecraft.getSoundManager().isActive(current)) {
                current.fadeOut();
            }
            current = new FadingLoopingMusic(soundEvent, DEFAULT_VOLUME, TRANSITION_TICKS);
            minecraft.getSoundManager().play(current);
        });
    }

    public static boolean isActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            authoredMusicActive = false;
            current = null;
            return false;
        }
        return authoredMusicActive;
    }
}
