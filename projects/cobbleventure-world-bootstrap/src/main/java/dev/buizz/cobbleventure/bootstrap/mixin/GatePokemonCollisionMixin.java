package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.GatePokemonSystem;
import dev.buizz.cobbleventure.bootstrap.GatePokemonCollisions;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds fixed actor shapes to vanilla movement/step collision on both logical sides. */
@Mixin(Entity.class)
abstract class GatePokemonCollisionMixin {
    // Lithium redirects getEntityCollisions to defer its work. Append to the stored
    // list instead, preserving its redirects and sharing these shapes with step-up.
    @ModifyVariable(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("STORE"), ordinal = 0, require = 1)
    private List<VoxelShape> cobbleventure$gateShapes(List<VoxelShape> original, Vec3 movement) {
        Entity mover = (Entity) (Object) this;
        if (!(mover instanceof Player player) || player.isSpectator()) return original;
        AABB swept = mover.getBoundingBox().expandTowards(movement);
        return GatePokemonCollisions.append(original, GatePokemonSystem.views(mover), swept);
    }
}
