package dev.buizz.cobbleventure.adventure.event;

/** Stops a session when validated IR cannot be evaluated safely at runtime. */
public final class EventRuntimeException extends IllegalStateException {
    public EventRuntimeException(String message) {
        super(message);
    }

    public EventRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
