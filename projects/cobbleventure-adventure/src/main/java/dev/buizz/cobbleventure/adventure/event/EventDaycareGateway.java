package dev.buizz.cobbleventure.adventure.event;

/** Opens the daycare UI for the NPC that owns the active V5 event session. */
@FunctionalInterface
public interface EventDaycareGateway {
    void open(EventSessionKey sessionKey);
}
