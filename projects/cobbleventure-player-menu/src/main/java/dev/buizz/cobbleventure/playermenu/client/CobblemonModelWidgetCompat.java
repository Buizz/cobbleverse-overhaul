package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

final class CobblemonModelWidgetCompat {
    private static final Constructor<ModelWidget> CONSTRUCTOR = resolveConstructor();
    private static final boolean SUPPORTS_BLOCK_LIGHT = CONSTRUCTOR.getParameterCount() == 11;

    private CobblemonModelWidgetCompat() {
    }

    static ModelWidget create(
        int x,
        int y,
        int width,
        int height,
        RenderablePokemon pokemon,
        float baseScale,
        float rotationY,
        double offsetY,
        boolean playCryOnClick,
        boolean shouldFollowCursor
    ) {
        try {
            if (SUPPORTS_BLOCK_LIGHT) {
                return CONSTRUCTOR.newInstance(
                    x, y, width, height, pokemon, baseScale, rotationY, offsetY,
                    playCryOnClick, shouldFollowCursor, 13
                );
            }
            return CONSTRUCTOR.newInstance(
                x, y, width, height, pokemon, baseScale, rotationY, offsetY,
                playCryOnClick, shouldFollowCursor
            );
        } catch (InstantiationException | IllegalAccessException exception) {
            throw new IllegalStateException("Cobblemon ModelWidget을 생성할 수 없습니다.", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Cobblemon ModelWidget 생성자가 실패했습니다.", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static Constructor<ModelWidget> resolveConstructor() {
        Class<?>[] sharedParameters = {
            int.class, int.class, int.class, int.class, RenderablePokemon.class,
            float.class, float.class, double.class, boolean.class, boolean.class
        };
        try {
            return ModelWidget.class.getConstructor(
                int.class, int.class, int.class, int.class, RenderablePokemon.class,
                float.class, float.class, double.class, boolean.class, boolean.class,
                int.class
            );
        } catch (NoSuchMethodException ignored) {
            try {
                return ModelWidget.class.getConstructor(sharedParameters);
            } catch (NoSuchMethodException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
