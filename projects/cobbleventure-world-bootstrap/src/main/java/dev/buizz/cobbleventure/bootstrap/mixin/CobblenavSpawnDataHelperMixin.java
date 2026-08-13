package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.HabitatSpawnRules;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kotlin.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Cobblenav compatibility without making the navigation mod mandatory. */
@Pseudo
@Mixin(targets = "com.metacontent.cobblenav.spawndata.SpawnDataHelper", remap = false)
public abstract class CobblenavSpawnDataHelperMixin {
    @Inject(method = "checkPlayerSpawns", at = @At("RETURN"), cancellable = true, require = 0)
    private void cobbleventure$filterAuthoredHabitat(
        ServerPlayer player, String bucketName,
        CallbackInfoReturnable<Object> callback
    ) {
        Set<ResourceLocation> allowed = HabitatSpawnRules.allowedSpecies(
            player.serverLevel(), player.getX(), player.getZ()
        );
        if (allowed == null || !(callback.getReturnValue() instanceof Pair<?, ?> pair)
            || !(pair.getSecond() instanceof List<?> original)) {
            return;
        }
        List<Object> filtered = new ArrayList<>(original.size());
        for (Object checkedSpawn : original) {
            String detailId = spawnDetailId(checkedSpawn);
            if (detailId != null && HabitatSpawnRules.allowsSpawnDetail(allowed, detailId)) {
                filtered.add(checkedSpawn);
            }
        }
        callback.setReturnValue(new Pair<>(pair.getFirst(), List.copyOf(filtered)));
    }

    private static String spawnDetailId(Object checkedSpawn) {
        try {
            Method getData = checkedSpawn.getClass().getMethod("getData");
            Object data = getData.invoke(checkedSpawn);
            Method getId = data.getClass().getMethod("getId");
            return (String) getId.invoke(data);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }
}
