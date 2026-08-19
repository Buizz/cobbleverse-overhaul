package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Opens one movement operation and returns the common CVES await token. */
@FunctionalInterface
public interface EventMovementGateway {
    OpenResult open(MovementRequest request);

    enum Subject { PLAYER, NPC }
    enum Mode { WALK, OFFSET_TELEPORT, TELEPORT }
    enum Collision { STOP, IGNORE }
    enum SafeLanding { REQUIRED, PREFERRED, DISABLED }
    enum Fade { BLACK, WHITE, NONE }

    record Options(
        Mode mode,
        double speed,
        boolean lockInput,
        Collision collision,
        SafeLanding safeLanding,
        boolean preloadChunks,
        Fade fade
    ) {
        public Options(SafeLanding safeLanding, boolean preloadChunks, Fade fade) {
            this(
                Mode.TELEPORT, 0.9D, false, Collision.STOP,
                safeLanding, preloadChunks, fade
            );
        }

        public Options {
            Objects.requireNonNull(mode, "mode");
            if (!Double.isFinite(speed) || speed <= 0 || speed > 20) {
                throw new IllegalArgumentException("movement speed는 0보다 크고 20 이하여야 합니다.");
            }
            Objects.requireNonNull(collision, "collision");
            Objects.requireNonNull(safeLanding, "safeLanding");
            Objects.requireNonNull(fade, "fade");
        }
    }

    record MovementRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        String operationId,
        Subject subject,
        EventLocationRef destination,
        Options options
    ) {
        public MovementRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
            requireText(operationId, "operationId");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(options, "options");
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            requireText(token, "token");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 필요합니다.");
        }
    }
}
