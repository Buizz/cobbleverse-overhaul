package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Owns the single looping Cobbleventure BGM instance on the client. */
public final class LoopingMusic {
    private static final float DEFAULT_VOLUME = 0.6F;
    private static SoundInstance current;
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
            if (current != null) minecraft.getSoundManager().stop(current);
            current = new SimpleSoundInstance(
                soundEvent,
                SoundSource.MUSIC,
                DEFAULT_VOLUME,
                1.0F,
                RandomSource.create(),
                true,
                0,
                SoundInstance.Attenuation.NONE,
                0.0D,
                0.0D,
                0.0D,
                true
            );
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
