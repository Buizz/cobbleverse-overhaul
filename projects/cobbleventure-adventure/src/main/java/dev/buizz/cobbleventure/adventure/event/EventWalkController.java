package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Deterministic 20 TPS relative-walk state machine independent from Minecraft entity APIs. */
final class EventWalkController {
    enum Status { RUNNING, ARRIVED, COLLISION, FALL_RISK, TIMED_OUT }

    record Position(double x, double y, double z) {
        Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("walk 위치는 유한해야 합니다.");
            }
        }
    }

    record Step(Status status, String failureReason) {
        Step {
            Objects.requireNonNull(status, "status");
            if ((status == Status.RUNNING || status == Status.ARRIVED)
                != (failureReason == null)) {
                throw new IllegalArgumentException("walk 실패 상태에만 실패 원인이 필요합니다.");
            }
        }

        boolean terminal() {
            return status != Status.RUNNING;
        }
    }

    interface Agent {
        Position position();
        boolean canOccupy(Position position);
        boolean hasSupport(Position position);
        void moveTo(Position position);
        void setInputLocked(boolean locked);
    }

    private static final double ARRIVAL_EPSILON = 0.03D;
    private final Position target;
    private final double blocksPerTick;
    private final boolean lockInput;
    private final EventMovementGateway.Collision collision;
    private final long expiresAtEpochMilli;
    private boolean inputLocked;
    private boolean finished;

    EventWalkController(
        Position origin,
        EventLocationRef.Relative relative,
        EventMovementGateway.Options options,
        long expiresAtEpochMilli
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(relative, "relative");
        Objects.requireNonNull(options, "options");
        if (options.mode() != EventMovementGateway.Mode.WALK) {
            throw new IllegalArgumentException("WALK option만 walk controller에 사용할 수 있습니다.");
        }
        target = new Position(
            origin.x() + relative.x(),
            origin.y() + relative.y(),
            origin.z() + relative.z()
        );
        blocksPerTick = options.speed() / 20D;
        lockInput = options.lockInput();
        collision = options.collision();
        this.expiresAtEpochMilli = expiresAtEpochMilli;
    }

    Step tick(Agent agent, long nowEpochMilli) {
        Objects.requireNonNull(agent, "agent");
        if (finished) throw new IllegalStateException("종료된 walk를 다시 실행할 수 없습니다.");
        if (lockInput && !inputLocked) {
            agent.setInputLocked(true);
            inputLocked = true;
        }
        if (expiresAtEpochMilli > 0 && nowEpochMilli >= expiresAtEpochMilli) {
            return finish(agent, Status.TIMED_OUT, EventMovementFailureReason.MOVEMENT_TIMEOUT);
        }
        Position current = agent.position();
        double x = target.x() - current.x();
        double y = target.y() - current.y();
        double z = target.z() - current.z();
        double distance = Math.sqrt(x * x + y * y + z * z);
        if (distance <= ARRIVAL_EPSILON) {
            agent.moveTo(target);
            return finish(agent, Status.ARRIVED, null);
        }
        double scale = Math.min(blocksPerTick, distance) / distance;
        Position next = new Position(
            current.x() + x * scale,
            current.y() + y * scale,
            current.z() + z * scale
        );
        if (collision == EventMovementGateway.Collision.STOP && !agent.canOccupy(next)) {
            return finish(agent, Status.COLLISION, EventMovementFailureReason.COLLISION);
        }
        if (!agent.hasSupport(next)) {
            return finish(agent, Status.FALL_RISK, EventMovementFailureReason.FALL_RISK);
        }
        agent.moveTo(next);
        if (distance <= blocksPerTick) {
            return finish(agent, Status.ARRIVED, null);
        }
        return new Step(Status.RUNNING, null);
    }

    void cancel(Agent agent) {
        if (finished) return;
        finished = true;
        unlock(agent);
    }

    private Step finish(Agent agent, Status status, String failureReason) {
        finished = true;
        unlock(agent);
        return new Step(status, failureReason);
    }

    private void unlock(Agent agent) {
        if (inputLocked) {
            agent.setInputLocked(false);
            inputLocked = false;
        }
    }
}
