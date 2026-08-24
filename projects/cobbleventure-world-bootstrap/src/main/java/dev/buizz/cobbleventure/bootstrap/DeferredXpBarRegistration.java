package dev.buizz.cobbleventure.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Defers XP Bar's Cobblemon event subscriptions until parallel mod construction is over. */
public final class DeferredXpBarRegistration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Object pendingHandler;

    private DeferredXpBarRegistration() {}

    public static synchronized void defer(Object handler) {
        pendingHandler = handler;
        LOGGER.info("Deferred Cobblemon XP Bar event registration until common setup");
    }

    static synchronized void register() {
        Object handler = pendingHandler;
        if (handler == null) {
            return;
        }
        pendingHandler = null;
        try {
            Method register = handler.getClass().getMethod("register");
            register.invoke(handler);
            LOGGER.info("Completed deferred Cobblemon XP Bar event registration");
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw new IllegalStateException(
                "Cobblemon XP Bar event registration method is unavailable", error
            );
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("Cobblemon XP Bar event registration failed", cause);
        }
    }
}
