package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-independent boundary for idempotent event item rewards. */
@FunctionalInterface
public interface EventGiveItemGateway {
    OpenResult grant(GrantRequest request);

    record GrantRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        String operationId,
        String itemId,
        int count,
        boolean showNotification
    ) {
        public GrantRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
            requireText(operationId, "operationId");
            requireText(itemId, "itemId");
            if (count <= 0) throw new IllegalArgumentException("count는 1 이상이어야 합니다.");
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
                throw new IllegalArgumentException("give_item token이 필요합니다.");
            }
        }
    }
}
