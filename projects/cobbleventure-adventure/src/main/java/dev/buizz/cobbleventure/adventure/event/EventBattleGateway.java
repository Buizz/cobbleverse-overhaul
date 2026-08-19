package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-independent boundary for starting one authored trainer battle. */
@FunctionalInterface
public interface EventBattleGateway {
    OpenResult open(BattleRequest request);

    record BattleRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        String operationId,
        String battleId
    ) {
        public BattleRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
            requireText(operationId, "operationId");
            requireText(battleId, "battleId");
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
                throw new IllegalArgumentException("battle await token이 필요합니다.");
            }
        }
    }
}
