package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.TownPlacementHeightContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
abstract class JigsawPlacementPlacerMixin {
    @Inject(method = "readPoolKey", at = @At("RETURN"), cancellable = true)
    private static void cobbleventure$selectHousePool(
        StructureBlockInfo block,
        PoolAliasLookup aliases,
        CallbackInfoReturnable<ResourceKey<StructureTemplatePool>> callback
    ) {
        callback.setReturnValue(TownPlacementHeightContext.remapHousePool(
            callback.getReturnValue()
        ));
    }
}
