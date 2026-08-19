package dev.buizz.cobbleventure.adventure.event;

/** Reports a build artifact that does not satisfy the CVES Runtime IR contract. */
public final class EventScriptFormatException extends IllegalArgumentException {
    public EventScriptFormatException(String message) {
        super(message);
    }

    public EventScriptFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
