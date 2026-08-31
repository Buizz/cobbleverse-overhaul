package dev.buizz.cobbleventure.bootstrap.mixin;

import com.mojang.datafixers.util.Either;
import dev.buizz.cobbleventure.bootstrap.GroundFloorAirPreservationProcessor;
import dev.buizz.cobbleventure.bootstrap.TerrainAirPreservationProcessor;
import dev.buizz.cobbleventure.bootstrap.TownPlacementHeightContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SinglePoolElement.class)
abstract class SinglePoolElementMixin {
    @Shadow @Final
    protected Either<ResourceLocation, StructureTemplate> template;

    @ModifyVariable(method = "place", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BlockPos cobbleventure$groundBcaSecondaryPiece(BlockPos position) {
        boolean lower = template.left()
            .map(TownPlacementHeightContext::shouldLowerTemplate)
            .orElse(false);
        return lower ? position.below() : position;
    }

    @Inject(method = "getSettings", at = @At("RETURN"))
    private void cobbleventure$preserveGroundBelowBcaDecor(
        Rotation rotation,
        BoundingBox box,
        LiquidSettings liquidSettings,
        boolean keepJigsaws,
        CallbackInfoReturnable<StructurePlaceSettings> callback
    ) {
        if (TownPlacementHeightContext.isActive()) {
            callback.getReturnValue().addProcessor(
                GroundFloorAirPreservationProcessor.INSTANCE
            );
        }
        boolean preserveTerrain = template.left()
            .map(TownPlacementHeightContext::shouldPreserveTerrain)
            .orElse(false);
        if (preserveTerrain) {
            callback.getReturnValue().addProcessor(TerrainAirPreservationProcessor.INSTANCE);
        }
    }
}
