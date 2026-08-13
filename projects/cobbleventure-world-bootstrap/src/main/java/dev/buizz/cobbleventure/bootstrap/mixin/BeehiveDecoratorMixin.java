package dev.buizz.cobbleventure.bootstrap.mixin;

import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents natural bee nests from being attached to generated trees. */
@Mixin(BeehiveDecorator.class)
abstract class BeehiveDecoratorMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$skipNaturalBeeNest(
        TreeDecorator.Context context,
        CallbackInfo callback
    ) {
        callback.cancel();
    }
}
