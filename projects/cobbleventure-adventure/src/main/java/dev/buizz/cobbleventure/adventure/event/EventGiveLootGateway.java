package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-independent boundary for idempotent loot-table rewards. */
@FunctionalInterface
public interface EventGiveLootGateway {
    OpenResult grant(GrantRequest request);

    record GrantRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        String operationId,
        String lootTableId,
        int rollCount,
        boolean showNotification
    ) {
        public GrantRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
            requireText(operationId, "operationId");
            requireText(lootTableId, "lootTableId");
            if (rollCount <= 0) {
                throw new IllegalArgumentException("rollCount는 1 이상이어야 합니다.");
            }
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
                throw new IllegalArgumentException("give_loot token이 필요합니다.");
            }
        }
    }
}
