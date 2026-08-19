package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Applies the shared immediate/await/idempotency contract to executable IR instructions. */
public final class EventExecution {
    public enum DispatchResult {
        ADVANCED, WAITING, SKIPPED_COMPLETED_OPERATION, TERMINAL
    }

    private EventExecution() {}

    public static DispatchResult dispatch(
        EventSession session,
        EventScript.Instruction instruction,
        EventCommandAdapter adapter
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(adapter, "adapter");
        if (session.status() != EventSession.Status.RUNNING) {
            throw new IllegalStateException("RUNNING 세션만 명령을 실행할 수 있습니다.");
        }
        if (session.programCounter() != instruction.address()) {
            throw new IllegalArgumentException("세션 PC와 명령 주소가 다릅니다.");
        }
        if (!isAdapterInstruction(instruction.operation())) {
            throw new IllegalArgumentException(
                "어댑터 실행 명령이 아닙니다: " + instruction.operation()
            );
        }

        Integer next = instruction.nextAddress();
        String operationId = instruction.operationId();
        if (session.hasCompletedOperation(operationId)) {
            if (instruction.resultVariable() != null) {
                session.completedOperationResult(operationId).ifPresent(
                    result -> session.putLocal(instruction.resultVariable(), result)
                );
            }
            if (next == null) {
                session.finish();
                return DispatchResult.TERMINAL;
            }
            session.advance(next);
            return DispatchResult.SKIPPED_COMPLETED_OPERATION;
        }

        EventCommandAdapter.StartResult result = Objects.requireNonNull(
            adapter.start(new EventCommandAdapter.CommandContext(
                session.key(), session.sourceDigest(), instruction, session.locals()
            )),
            "명령 어댑터 결과"
        );
        if (result instanceof EventCommandAdapter.Waiting waiting) {
            if (!instruction.awaitsResult() || instruction.resumeAddress() == null) {
                throw new IllegalStateException("await가 아닌 명령은 WAITING을 반환할 수 없습니다.");
            }
            session.beginAwait(
                instruction.command() == null ? instruction.operation() : instruction.command(),
                waiting.token(),
                operationId,
                instruction.resumeAddress(),
                instruction.resultVariable(),
                waiting.expiresAtEpochMilli()
            );
            return DispatchResult.WAITING;
        }
        if (next == null) {
            if (result instanceof EventCommandAdapter.Completed) {
                session.finish();
                return DispatchResult.TERMINAL;
            }
            throw new IllegalStateException("실패 결과를 저장할 다음 주소가 없습니다.");
        }
        if (result instanceof EventCommandAdapter.Completed completed) {
            session.completeInstruction(
                operationId, instruction.resultVariable(), completed.result(), next
            );
        } else if (result instanceof EventCommandAdapter.Failed failed) {
            session.completeInstruction(
                EventSession.CompletionKind.FAILED,
                operationId,
                instruction.resultVariable(),
                failed.result(),
                next
            );
        } else if (result instanceof EventCommandAdapter.Cancelled cancelled) {
            session.completeInstruction(
                EventSession.CompletionKind.CANCELLED,
                operationId,
                instruction.resultVariable(),
                cancelled.result(),
                next
            );
        } else {
            throw new IllegalStateException("알 수 없는 명령 어댑터 결과입니다.");
        }
        return session.status() == EventSession.Status.RUNNING
            ? DispatchResult.ADVANCED
            : DispatchResult.TERMINAL;
    }

    private static boolean isAdapterInstruction(String operation) {
        return operation.equals("say")
            || operation.equals("narrate")
            || operation.equals("command");
    }
}
