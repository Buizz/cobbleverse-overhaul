package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.CobbleventurePlayerMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Owns the single looping Cobbleventure BGM instance on the client. */
@EventBusSubscriber(modid = CobbleventurePlayerMenu.MOD_ID, value = Dist.CLIENT)
public final class LoopingMusic {
    private static final float DEFAULT_VOLUME = 0.6F;
    private static final int TRANSITION_TICKS = 30;
    private static FadingLoopingMusic current;
    private static ResourceLocation requestedSoundEvent;
    private static int ticksSinceStart;
    private static boolean authoredMusicActive;

    private LoopingMusic() {}

    public static void play(ResourceLocation soundEvent) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            authoredMusicActive = true;
            requestedSoundEvent = soundEvent;
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
            startPass(minecraft, soundEvent);
        });
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clearState();
            return;
        }
        if (!authoredMusicActive || requestedSoundEvent == null || current == null) return;
        ticksSinceStart++;
        if (MusicLoopPolicy.shouldRestartPass(
            ticksSinceStart, minecraft.getSoundManager().isActive(current)
        )) {
            // Restart only after the streamed OGG has completed as a whole. This
            // avoids OpenAL's partial-buffer loop while keeping authored BGM continuous.
            startPass(minecraft, requestedSoundEvent);
        }
    }

    public static boolean isActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clearState();
            return false;
        }
        return authoredMusicActive;
    }

    public static void stop() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            authoredMusicActive = false;
            requestedSoundEvent = null;
            if (current != null && minecraft.getSoundManager().isActive(current)) {
                current.fadeOut();
            }
            current = null;
            ticksSinceStart = 0;
        });
    }

    private static void startPass(Minecraft minecraft, ResourceLocation soundEvent) {
        current = new FadingLoopingMusic(soundEvent, DEFAULT_VOLUME, TRANSITION_TICKS);
        ticksSinceStart = 0;
        minecraft.getSoundManager().play(current);
    }

    private static void clearState() {
        authoredMusicActive = false;
        requestedSoundEvent = null;
        current = null;
        ticksSinceStart = 0;
    }
}
