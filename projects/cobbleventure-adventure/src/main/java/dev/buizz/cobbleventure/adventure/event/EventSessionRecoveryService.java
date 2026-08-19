package dev.buizz.cobbleventure.adventure.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Audits persisted sessions and performs only unambiguous stable-ID upgrades. */
public final class EventSessionRecoveryService {
    public enum Status {
        CURRENT,
        LEGACY_UPGRADABLE,
        RELOCATABLE,
        TERMINAL_RESTARTABLE,
        LEGACY_DIGEST_MISMATCH,
        INCOMPATIBLE,
        SCRIPT_MISSING
    }

    public record Diagnosis(
        EventSessionKey key,
        EventSession.Status sessionStatus,
        Status status,
        String storedDigest,
        String currentDigest,
        String detail
    ) {
        public Diagnosis {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(sessionStatus, "sessionStatus");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(storedDigest, "storedDigest");
            Objects.requireNonNull(detail, "detail");
        }

        public boolean requiresOperatorAction() {
            return status == Status.LEGACY_DIGEST_MISMATCH
                || status == Status.INCOMPATIBLE
                || status == Status.SCRIPT_MISSING;
        }
    }

    public record UpgradeResult(int upgraded, int unchanged, int blocked) {
        public UpgradeResult {
            if (upgraded < 0 || unchanged < 0 || blocked < 0) {
                throw new IllegalArgumentException("복구 집계는 0 이상이어야 합니다.");
            }
        }
    }

    private EventSessionRecoveryService() {}

    public static List<Diagnosis> audit(
        EventSessionStore store, Map<String, EventScript> scripts
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(scripts, "scripts");
        List<Diagnosis> result = new ArrayList<>();
        for (EventSession session : store.sessions()) {
            result.add(diagnose(session, scripts.get(session.key().scriptId())));
        }
        result.sort(Comparator
            .comparing((Diagnosis value) -> value.key().playerId().toString())
            .thenComparing(value -> value.key().npcId().toString())
            .thenComparing(value -> value.key().scriptId())
            .thenComparing(value -> value.key().triggerInstance()));
        return List.copyOf(result);
    }

    public static Diagnosis diagnose(EventSession session, EventScript script) {
        Objects.requireNonNull(session, "session");
        if (script == null) {
            return diagnosis(
                session, Status.SCRIPT_MISSING, null,
                "현재 데이터팩에서 script ID를 찾을 수 없습니다."
            );
        }
        if (isTerminal(session.status())) {
            return diagnosis(
                session, Status.TERMINAL_RESTARTABLE, script,
                "종료 세션은 다음 상호작용에서 현재 page로 다시 시작할 수 있습니다."
            );
        }
        boolean sameDigest = session.sourceDigest().equals(script.sourceDigest());
        if (!session.hasCompleteInstructionAnchors()) {
            return diagnosis(
                session,
                sameDigest ? Status.LEGACY_UPGRADABLE : Status.LEGACY_DIGEST_MISMATCH,
                script,
                sameDigest
                    ? "현재 숫자 주소에서 안정 instruction anchor를 채울 수 있습니다."
                    : "안정 instruction anchor 없이 digest가 변경되어 자동 복구할 수 없습니다."
            );
        }
        if (sameDigest) {
            return diagnosis(session, Status.CURRENT, script, "현재 script와 일치합니다.");
        }
        try {
            EventSession probe = EventSession.fromJson(session.toJson());
            probe.relocate(script, null);
            return diagnosis(
                session, Status.RELOCATABLE, script,
                "안정 instruction ID로 현재 script에 재배치할 수 있습니다."
            );
        } catch (RuntimeException error) {
            return diagnosis(
                session, Status.INCOMPATIBLE, script,
                "안정 ID 재배치가 거부되었습니다: " + error.getMessage()
            );
        }
    }

    public static UpgradeResult upgradeSafe(
        EventSessionStore store, Map<String, EventScript> scripts
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(scripts, "scripts");
        int upgraded = 0;
        int unchanged = 0;
        int blocked = 0;
        for (EventSession session : List.copyOf(store.sessions())) {
            EventScript script = scripts.get(session.key().scriptId());
            Diagnosis diagnosis = diagnose(session, script);
            if (diagnosis.status() == Status.LEGACY_UPGRADABLE
                || diagnosis.status() == Status.RELOCATABLE) {
                try {
                    session.relocate(script, null);
                    store.save(session);
                    upgraded++;
                } catch (RuntimeException error) {
                    blocked++;
                }
            } else if (diagnosis.requiresOperatorAction()) {
                blocked++;
            } else {
                unchanged++;
            }
        }
        return new UpgradeResult(upgraded, unchanged, blocked);
    }

    public static boolean discard(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        return store.remove(key);
    }

    private static Diagnosis diagnosis(
        EventSession session, Status status, EventScript script, String detail
    ) {
        return new Diagnosis(
            session.key(), session.status(), status, session.sourceDigest(),
            script == null ? null : script.sourceDigest(), detail
        );
    }

    private static boolean isTerminal(EventSession.Status status) {
        return status == EventSession.Status.COMPLETED
            || status == EventSession.Status.FAILED
            || status == EventSession.Status.CANCELLED;
    }
}
