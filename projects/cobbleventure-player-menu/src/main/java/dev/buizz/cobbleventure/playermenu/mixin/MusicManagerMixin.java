package dev.buizz.cobbleventure.playermenu.mixin;

import dev.buizz.cobbleventure.playermenu.client.LoopingMusic;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps vanilla background music from overlapping Cobbleventure's authored BGM. */
@Mixin(MusicManager.class)
abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$suppressVanillaMusic(CallbackInfo callback) {
        if (LoopingMusic.isActive()) callback.cancel();
    }
}
