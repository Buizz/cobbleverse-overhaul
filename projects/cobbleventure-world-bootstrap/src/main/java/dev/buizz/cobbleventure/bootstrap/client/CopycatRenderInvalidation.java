package dev.buizz.cobbleventure.bootstrap.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/** Applies received Copycats material data through the same public setters used by interaction. */
public final class CopycatRenderInvalidation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Class<?>, Optional<SingleStateAccess>> ACCESS_BY_CLASS =
        new LinkedHashMap<>();

    private CopycatRenderInvalidation() {}

    public static void reapply(Collection<Long> packedPositions) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.levelRenderer == null) {
            return;
        }

        LinkedHashSet<SectionPos> dirtySections = new LinkedHashSet<>();
        for (long packedPosition : packedPositions) {
            BlockPos position = BlockPos.of(packedPosition);
            BlockEntity blockEntity = minecraft.level.getBlockEntity(position);
            if (blockEntity == null) {
                continue;
            }
            Optional<SingleStateAccess> access = ACCESS_BY_CLASS.computeIfAbsent(
                blockEntity.getClass(), CopycatRenderInvalidation::findSingleStateAccess
            );
            if (access.isEmpty()) {
                continue;
            }
            try {
                SingleStateAccess methods = access.get();
                Object currentMaterial = methods.getMaterial().invoke(blockEntity);
                if (!(currentMaterial instanceof BlockState material)) {
                    continue;
                }

                // Calling the setter even with the already received value is intentional:
                // Copycats performs its redraw/model-data lifecycle inside this public path.
                methods.setMaterial().invoke(blockEntity, material);
                if (methods.getConsumedItem() != null && methods.setConsumedItem() != null) {
                    Object currentItem = methods.getConsumedItem().invoke(blockEntity);
                    if (currentItem instanceof ItemStack itemStack) {
                        methods.setConsumedItem().invoke(blockEntity, itemStack);
                    }
                }
                dirtySections.add(SectionPos.of(position));
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn(
                    "Failed to apply received Copycats material at {}", position, exception
                );
            }
        }

        for (SectionPos section : dirtySections) {
            minecraft.levelRenderer.setSectionDirty(
                section.x(), section.y(), section.z()
            );
        }
    }

    private static Optional<SingleStateAccess> findSingleStateAccess(Class<?> blockEntityClass) {
        try {
            Method getMaterial = blockEntityClass.getMethod("getMaterial");
            Method setMaterial = blockEntityClass.getMethod("setMaterial", BlockState.class);
            Method getConsumedItem = findOptionalMethod(blockEntityClass, "getConsumedItem");
            Method setConsumedItem = findOptionalMethod(
                blockEntityClass, "setConsumedItem", ItemStack.class
            );
            return Optional.of(new SingleStateAccess(
                getMaterial, setMaterial, getConsumedItem, setConsumedItem
            ));
        } catch (NoSuchMethodException ignored) {
            // Multi-state Copycats use part-specific setters and are not the unassigned
            // single-state panels fixed by this synchronization path.
            return Optional.empty();
        }
    }

    private static Method findOptionalMethod(
        Class<?> blockEntityClass,
        String name,
        Class<?>... parameterTypes
    ) {
        try {
            return blockEntityClass.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private record SingleStateAccess(
        Method getMaterial,
        Method setMaterial,
        Method getConsumedItem,
        Method setConsumedItem
    ) {}
}
