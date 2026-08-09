package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.TownPlacementHeightContext;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlatLevelSource.class)
abstract class FlatLevelSourceMixin {
    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$useRenderedTerrainHeight(
        int x,
        int z,
        Heightmap.Types heightmap,
        LevelHeightAccessor level,
        RandomState randomState,
        CallbackInfoReturnable<Integer> callback
    ) {
        Integer height = TownPlacementHeightContext.resolve(x, z, heightmap);
        if (height != null) {
            callback.setReturnValue(height);
        }
    }
}
