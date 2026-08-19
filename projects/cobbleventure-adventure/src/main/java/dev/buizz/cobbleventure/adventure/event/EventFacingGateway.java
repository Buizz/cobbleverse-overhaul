package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Applies one server-authoritative player or NPC facing change. */
@FunctionalInterface
public interface EventFacingGateway {
    void face(FacingRequest request);

    enum Subject { PLAYER, NPC }
    enum Direction { NORTH, SOUTH, EAST, WEST, PLAYER, NPC }

    record FacingRequest(
        EventSessionKey sessionKey,
        String instructionId,
        Subject subject,
        Direction direction
    ) {
        public FacingRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            if (instructionId == null || instructionId.isBlank()) {
                throw new IllegalArgumentException("instructionId가 필요합니다.");
            }
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(direction, "direction");
        }
    }
}
