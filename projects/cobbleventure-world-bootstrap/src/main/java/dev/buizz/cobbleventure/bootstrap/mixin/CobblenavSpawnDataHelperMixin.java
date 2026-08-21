package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.HabitatSpawnRules;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Map<ResourceLocation, Integer> authored = HabitatSpawnRules.authoredEncounterWeights(
            player.serverLevel(), player.getX(), player.getY(), player.getZ()
        );
        if (authored != null) {
            callback.setReturnValue(authoredSpawnList(player, bucketName, callback.getReturnValue(), authored));
            return;
        }
        Set<ResourceLocation> allowed = HabitatSpawnRules.allowedSpecies(
            player.serverLevel(), player.getX(), player.getY(), player.getZ()
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

    /**
     * Builds CobbleNav rows from existing Cobblemon spawn details without registering those
     * details in the current dimension. This is display data only and cannot cause a spawn.
     */
    private static Object authoredSpawnList(
        ServerPlayer player, String bucketName, Object original,
        Map<ResourceLocation, Integer> authored
    ) {
        if (!(original instanceof Pair<?, ?> pair)) return original;
        try {
            Class<?> helperClass = Class.forName("com.metacontent.cobblenav.spawndata.SpawnDataHelper");
            Object helper = helperClass.getField("INSTANCE").get(null);
            @SuppressWarnings("unchecked")
            Map<String, List<String>> idsBySpecies = (Map<String, List<String>>) helperClass
                .getMethod("getSpawnDetailIdBySpecies").invoke(helper);
            Object pool = kotlinProperty(
                Class.forName("com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools"),
                "WORLD_SPAWN_POOL"
            );
            Map<String, Object> detailsById = new HashMap<>();
            if (pool instanceof Iterable<?> details) {
                for (Object detail : details) {
                    detailsById.put((String) detail.getClass().getMethod("getId").invoke(detail), detail);
                }
            }
            Method collect = null;
            for (Method method : helperClass.getMethods()) {
                if (method.getName().equals("collect") && method.getParameterCount() == 3) {
                    collect = method;
                    break;
                }
            }
            if (collect == null) return original;
            Class<?> checkedClass = Class.forName("com.metacontent.cobblenav.spawndata.CheckedSpawnData");
            Constructor<?> checkedConstructor = checkedClass.getConstructors()[0];
            List<Object> rows = new ArrayList<>();
            int totalWeight = authored.values().stream().mapToInt(Integer::intValue).sum();
            for (Map.Entry<ResourceLocation, Integer> entry : authored.entrySet()) {
                List<String> detailIds = idsBySpecies.getOrDefault(
                    entry.getKey().toString(),
                    idsBySpecies.getOrDefault(entry.getKey().getPath(), List.of())
                );
                Object detail = detailIds.stream().map(detailsById::get).filter(candidate ->
                    candidate != null && bucketName.equals(detailBucket(candidate))
                ).findFirst().orElse(null);
                if (detail == null) continue;
                Object data = collect.invoke(helper, detail, List.of(), player);
                if (data != null) rows.add(checkedConstructor.newInstance(
                    data, totalWeight == 0 ? 0.0F : (float) entry.getValue() / totalWeight
                ));
            }
            return new Pair<>(pair.getFirst(), List.copyOf(rows));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return original;
        }
    }

    private static String detailBucket(Object detail) {
        try {
            Object bucket = detail.getClass().getMethod("getBucket").invoke(detail);
            return (String) bucket.getClass().getMethod("getName").invoke(bucket);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return "";
        }
    }

    private static Object kotlinProperty(Class<?> owner, String property)
        throws ReflectiveOperationException {
        Object instance = owner.getField("INSTANCE").get(null);
        try {
            return owner.getMethod("get" + property).invoke(instance);
        } catch (NoSuchMethodException ignored) {
            Field field = owner.getField(property);
            return field.get(instance);
        }
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
