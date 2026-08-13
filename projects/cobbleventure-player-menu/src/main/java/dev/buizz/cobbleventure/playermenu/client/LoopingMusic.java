package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Owns the single looping Cobbleventure BGM instance on the client. */
public final class LoopingMusic {
    private static SoundInstance current;

    private LoopingMusic() {}

    public static void play(ResourceLocation soundEvent) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (current != null) minecraft.getSoundManager().stop(current);
            current = new SimpleSoundInstance(
                soundEvent,
                SoundSource.MUSIC,
                1.0F,
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
}
