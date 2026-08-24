package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Transport boundary for a server-bounded integer input prompt. */
@FunctionalInterface
public interface EventNumberInputGateway {
    OpenResult open(NumberInputRequest request);

    record NumberInputRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        int minimum,
        int maximum
    ) {
        public NumberInputRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            if (sourceDigest == null || sourceDigest.isBlank()) throw new IllegalArgumentException("sourceDigest is required");
            if (instructionId == null || instructionId.isBlank()) throw new IllegalArgumentException("instructionId is required");
            if (minimum > maximum) throw new IllegalArgumentException("minimum must not exceed maximum");
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("number input token is required");
        }
    }
}
