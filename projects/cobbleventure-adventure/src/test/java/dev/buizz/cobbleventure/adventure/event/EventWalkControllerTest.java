package dev.buizz.cobbleventure.adventure.event;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventWalkControllerTest {
    @Test
    void walkArrivesAtDeterministicSpeedAndReleasesInputLock() {
        FakeAgent agent = new FakeAgent(new EventWalkController.Position(0, 64, 0));
        EventWalkController controller = controller(
            new EventLocationRef.Relative(2, 0, 0),
            options(20, true, EventMovementGateway.Collision.STOP),
            10_000
        );

        assertEquals(EventWalkController.Status.RUNNING, controller.tick(agent, 1).status());
        EventWalkController.Step completed = controller.tick(agent, 2);

        assertEquals(EventWalkController.Status.ARRIVED, completed.status());
        assertEquals(new EventWalkController.Position(2, 64, 0), agent.position());
        assertEquals(List.of(true, false), agent.inputLocks);
    }

    @Test
    void collisionStopsBeforeEnteringTheBlockedPositionAndUnlocksInput() {
        FakeAgent agent = new FakeAgent(new EventWalkController.Position(0, 64, 0));
        agent.canOccupy = false;
        EventWalkController controller = controller(
            new EventLocationRef.Relative(1, 0, 0),
            options(20, true, EventMovementGateway.Collision.STOP),
            10_000
        );

        EventWalkController.Step result = controller.tick(agent, 1);

        assertEquals(EventWalkController.Status.COLLISION, result.status());
        assertEquals("collision", result.failureReason());
        assertEquals(new EventWalkController.Position(0, 64, 0), agent.position());
        assertEquals(List.of(true, false), agent.inputLocks);
    }

    @Test
    void collisionIgnoreStillRejectsAPathWithoutFloorSupport() {
        FakeAgent agent = new FakeAgent(new EventWalkController.Position(0, 64, 0));
        agent.canOccupy = false;
        agent.hasSupport = false;
        EventWalkController controller = controller(
            new EventLocationRef.Relative(1, 0, 0),
            options(20, false, EventMovementGateway.Collision.IGNORE),
            10_000
        );

        EventWalkController.Step result = controller.tick(agent, 1);

        assertEquals(EventWalkController.Status.FALL_RISK, result.status());
        assertEquals("fall_risk", result.failureReason());
        assertTrue(agent.inputLocks.isEmpty());
    }

    @Test
    void timeoutAndExplicitCancellationAlwaysReleaseInputLock() {
        FakeAgent timedOutAgent = new FakeAgent(new EventWalkController.Position(0, 64, 0));
        EventWalkController timedOut = controller(
            new EventLocationRef.Relative(10, 0, 0),
            options(1, true, EventMovementGateway.Collision.STOP),
            5
        );
        EventWalkController.Step timeout = timedOut.tick(timedOutAgent, 5);
        assertEquals(EventWalkController.Status.TIMED_OUT, timeout.status());
        assertEquals(List.of(true, false), timedOutAgent.inputLocks);

        FakeAgent cancelledAgent = new FakeAgent(new EventWalkController.Position(0, 64, 0));
        EventWalkController cancelled = controller(
            new EventLocationRef.Relative(10, 0, 0),
            options(1, true, EventMovementGateway.Collision.STOP),
            100
        );
        assertFalse(cancelled.tick(cancelledAgent, 1).terminal());
        cancelled.cancel(cancelledAgent);
        assertEquals(List.of(true, false), cancelledAgent.inputLocks);
    }

    private static EventWalkController controller(
        EventLocationRef.Relative relative,
        EventMovementGateway.Options options,
        long expiresAt
    ) {
        return new EventWalkController(
            new EventWalkController.Position(0, 64, 0), relative, options, expiresAt
        );
    }

    private static EventMovementGateway.Options options(
        double speed,
        boolean lockInput,
        EventMovementGateway.Collision collision
    ) {
        return new EventMovementGateway.Options(
            EventMovementGateway.Mode.WALK,
            speed,
            lockInput,
            collision,
            EventMovementGateway.SafeLanding.REQUIRED,
            false,
            EventMovementGateway.Fade.NONE
        );
    }

    private static final class FakeAgent implements EventWalkController.Agent {
        private EventWalkController.Position position;
        private boolean canOccupy = true;
        private boolean hasSupport = true;
        private final List<Boolean> inputLocks = new ArrayList<>();

        private FakeAgent(EventWalkController.Position position) {
            this.position = position;
        }

        @Override public EventWalkController.Position position() { return position; }
        @Override public boolean canOccupy(EventWalkController.Position ignored) {
            return canOccupy;
        }
        @Override public boolean hasSupport(EventWalkController.Position ignored) {
            return hasSupport;
        }
        @Override public void moveTo(EventWalkController.Position value) { position = value; }
        @Override public void setInputLocked(boolean locked) { inputLocks.add(locked); }
    }
}
