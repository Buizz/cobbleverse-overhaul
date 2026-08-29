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
        long minimum,
        long maximum,
        Long currentBalance,
        Long unitPrice
    ) {
        public NumberInputRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            if (sourceDigest == null || sourceDigest.isBlank()) throw new IllegalArgumentException("sourceDigest is required");
            if (instructionId == null || instructionId.isBlank()) throw new IllegalArgumentException("instructionId is required");
            if (minimum > maximum) throw new IllegalArgumentException("minimum must not exceed maximum");
            if ((currentBalance == null) != (unitPrice == null)) {
                throw new IllegalArgumentException("currentBalance and unitPrice must be provided together");
            }
            if (currentBalance != null && (currentBalance < 0 || unitPrice < 1)) {
                throw new IllegalArgumentException("invalid price summary values");
            }
        }
    }

    record OpenResult(String token, long expiresAtEpochMilli) {
        public OpenResult {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("number input token is required");
        }
    }
}
