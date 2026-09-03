package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownUnbundler;
import dev.buizz.cobbleventure.adventure.ShowdownEmptySlotsPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GraalShowdownUnbundler.class, remap = false)
public abstract class ShowdownEmptySlotsMixin {
    @Inject(method = "attemptUnbundle", at = @At("RETURN"))
    private void cobbleventure$patchEmptySlotsBeforeEngineLoads(CallbackInfo callback) {
        ShowdownEmptySlotsPatch.apply();
    }
}
