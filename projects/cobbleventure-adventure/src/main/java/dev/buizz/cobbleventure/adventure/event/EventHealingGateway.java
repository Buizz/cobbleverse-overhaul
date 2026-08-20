package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-independent boundary for repeatable Pokemon Center healing. */
@FunctionalInterface
public interface EventHealingGateway {
    OpenResult heal(HealingRequest request);

    record HealingRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId
    ) {
        public HealingRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + "가 필요합니다.");
            }
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("heal_party token이 필요합니다.");
            }
        }
    }
}
