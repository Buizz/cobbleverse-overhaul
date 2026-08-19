package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-neutral gateway for selecting one authored map destination. */
@FunctionalInterface
public interface EventMapSelectionGateway {
    record SelectionRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId
    ) {
        public SelectionRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            Objects.requireNonNull(sourceDigest, "sourceDigest");
            Objects.requireNonNull(instructionId, "instructionId");
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("map selection token이 필요합니다.");
            }
        }
    }

    OpenResult open(SelectionRequest request);
}
