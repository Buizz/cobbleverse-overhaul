package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.api.item.PokemonSelectingItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class CobblemonSelectingItemCompat {
    private static final Method USE_METHOD = resolveUseMethod();
    private static final boolean SUPPORTS_IGNORE_SHIFT = USE_METHOD.getParameterCount() == 4;

    private CobblemonSelectingItemCompat() {
    }

    @SuppressWarnings("unchecked")
    static InteractionResultHolder<ItemStack> use(
        PokemonSelectingItem item,
        ServerPlayer player,
        ItemStack stack
    ) {
        try {
            Object result = SUPPORTS_IGNORE_SHIFT
                ? USE_METHOD.invoke(null, item, player, stack, false)
                : USE_METHOD.invoke(null, item, player, stack);
            return (InteractionResultHolder<ItemStack>) result;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cobblemon PokemonSelectingItem을 호출할 수 없습니다.", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Cobblemon PokemonSelectingItem 호출이 실패했습니다.", cause);
        }
    }

    private static Method resolveUseMethod() {
        try {
            return PokemonSelectingItem.class.getMethod(
                "access$use$jd",
                PokemonSelectingItem.class,
                ServerPlayer.class,
                ItemStack.class,
                boolean.class
            );
        } catch (NoSuchMethodException ignored) {
            try {
                return PokemonSelectingItem.class.getMethod(
                    "access$use$jd",
                    PokemonSelectingItem.class,
                    ServerPlayer.class,
                    ItemStack.class
                );
            } catch (NoSuchMethodException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
