package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-independent boundary for the existing starter roulette service. */
@FunctionalInterface
public interface EventStarterRouletteGateway {
    OpenResult open(RouletteRequest request);

    record RouletteRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId
    ) {
        public RouletteRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            if (sourceDigest == null || sourceDigest.isBlank()) {
                throw new IllegalArgumentException("sourceDigest가 필요합니다.");
            }
            if (instructionId == null || instructionId.isBlank()) {
                throw new IllegalArgumentException("instructionId가 필요합니다.");
            }
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("starter roulette token이 필요합니다.");
            }
        }
    }
}
