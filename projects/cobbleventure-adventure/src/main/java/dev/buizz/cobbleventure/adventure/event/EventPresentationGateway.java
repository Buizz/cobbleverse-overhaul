package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Opens one visual, timing, sound, or particle presentation await. */
@FunctionalInterface
public interface EventPresentationGateway {
    OpenResult open(PresentationRequest request);

    enum Kind { FADE, WAIT, SOUND, EFFECT }
    enum FadeColor { BLACK, WHITE }

    record PresentationRequest(
        EventSessionKey sessionKey,
        String sourceDigest,
        String instructionId,
        String operationId,
        Kind kind,
        String resourceId,
        FadeColor fadeColor,
        double durationSeconds
    ) {
        public PresentationRequest {
            Objects.requireNonNull(sessionKey, "sessionKey");
            requireText(sourceDigest, "sourceDigest");
            requireText(instructionId, "instructionId");
            requireText(operationId, "operationId");
            Objects.requireNonNull(kind, "kind");
            if (!Double.isFinite(durationSeconds) || durationSeconds < 0
                || durationSeconds > 3600) {
                throw new IllegalArgumentException("연출 시간은 0~3600초여야 합니다.");
            }
            if ((kind == Kind.SOUND || kind == Kind.EFFECT)
                != (resourceId != null)) {
                throw new IllegalArgumentException("sound/effect에만 resource ID가 필요합니다.");
            }
            if ((kind == Kind.FADE) != (fadeColor != null)) {
                throw new IllegalArgumentException("fade에만 색상이 필요합니다.");
            }
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
