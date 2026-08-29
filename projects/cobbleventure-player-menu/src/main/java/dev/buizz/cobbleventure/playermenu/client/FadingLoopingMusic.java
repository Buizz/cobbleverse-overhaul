package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** A single streamed music pass that can cross-fade without restarting abruptly. */
final class FadingLoopingMusic extends AbstractTickableSoundInstance {
    private final ResourceLocation soundEvent;
    private final float targetVolume;
    private final float volumeStep;
    private boolean fadingOut;

    FadingLoopingMusic(ResourceLocation soundEvent, float targetVolume, int transitionTicks) {
        super(
            SoundEvent.createVariableRangeEvent(soundEvent),
            SoundSource.MUSIC,
            RandomSource.create()
        );
        this.soundEvent = soundEvent;
        this.targetVolume = targetVolume;
        this.volumeStep = targetVolume / Math.max(1, transitionTicks);
        this.volume = 0.0F;
        this.pitch = 1.0F;
        // Long BGM entries are streamed. Minecraft/OpenAL's native looping flag can
        // rewind only the currently queued stream buffer, which repeats the intro
        // instead of the complete OGG. LoopingMusic starts a fresh pass after this
        // instance naturally finishes, so this instance itself must never loop.
        this.looping = false;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
    }

    ResourceLocation soundEvent() {
        return soundEvent;
    }

    void fadeIn() {
        fadingOut = false;
    }

    void fadeOut() {
        fadingOut = true;
    }

    @Override
    public boolean canStartSilent() {
        // Cross-fades intentionally begin at zero volume. Without this opt-in the
        // sound engine drops the instance before its first tick can fade it in.
        return true;
    }

    @Override
    public void tick() {
        if (fadingOut) {
            volume = Math.max(0.0F, volume - volumeStep);
            if (volume <= 0.0F) stop();
            return;
        }
        volume = Math.min(targetVolume, volume + volumeStep);
    }
}
