package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GatePokemonCollisionsTest {
    private static final AABB PLAYER = new AABB(0, 0, 0, 0.6, 1.8, 0.6);
    private static final AABB GATE = new AABB(1, 0, -1, 4, 2, 2);
    private static final AABB SWEPT = PLAYER.expandTowards(new Vec3(2, 0, 0));

    private static GatePokemonNetwork.View view(boolean hidden) {
        return new GatePokemonNetwork.View(UUID.randomUUID(), GATE, hidden, "sleep");
    }

    @Test void emptyDeferredLithiumListStillBlocksMovement() {
        List<VoxelShape> deferred = new ArrayList<>();
        var result = GatePokemonCollisions.append(deferred, List.of(view(false)), SWEPT);
        assertEquals(0.4, Shapes.collide(Direction.Axis.X, PLAYER, result, 2), 1.0e-8);
        assertTrue(deferred.isEmpty());
        // Lithium can append its deferred entity collisions to this list later.
        result.add(Shapes.block());
        assertEquals(2, result.size());
    }

    @Test void vanillaObstaclesRemainIntactAndOriginalListIsNotModified() {
        VoxelShape existing = Shapes.create(new AABB(-2, 0, -2, -1, 1, -1));
        List<VoxelShape> original = List.of(existing);
        var result = GatePokemonCollisions.append(original, List.of(view(false)), SWEPT);
        assertSame(existing, result.getFirst());
        assertEquals(2, result.size());
        assertEquals(List.of(existing), original);
    }

    @Test void completedOrDistantGatesDoNotBlockOrCopyTheList() {
        List<VoxelShape> original = new ArrayList<>();
        assertSame(original, GatePokemonCollisions.append(original, List.of(view(true)), SWEPT));
        assertSame(original, GatePokemonCollisions.append(original, List.of(view(false)), SWEPT.move(20, 0, 0)));
        assertSame(original, GatePokemonCollisions.append(original, List.of(), SWEPT));
    }

    @Test void gateRemainsAnObstacleDuringStepUpAndSupportsStandingOnTop() {
        var result = GatePokemonCollisions.append(List.of(), List.of(view(false)), SWEPT);
        assertEquals(0.4, Shapes.collide(Direction.Axis.X, PLAYER.move(0, 0.6, 0), result, 2), 1.0e-8);
        assertEquals(-0.5, Shapes.collide(Direction.Axis.Y, PLAYER.move(2, 2.5, 0), result, -2), 1.0e-8);
    }
}
