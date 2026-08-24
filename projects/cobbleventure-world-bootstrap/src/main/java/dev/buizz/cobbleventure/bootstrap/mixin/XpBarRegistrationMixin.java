package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.DeferredXpBarRegistration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.cobblemonxpbar.XPBarMod", remap = false)
public abstract class XpBarRegistrationMixin {
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemonxpbar/server/ExperienceHandler;register()V"
        ),
        require = 0
    )
    private void cobbleventure$deferEventRegistration(@Coerce Object handler) {
        DeferredXpBarRegistration.defer(handler);
    }
}
