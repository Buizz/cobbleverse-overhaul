package dev.buizz.cobbleventure.adventure.daycare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DaycareJobTest {
    @Test
    void roundTripsParentsTimingFeeAndEgg() {
        DaycareJob original = job().withEgg(tag("egg", "encrypted-result"));

        DaycareJob restored = DaycareJob.load(original.save());

        assertEquals(original.jobId(), restored.jobId());
        assertEquals(original.ownerId(), restored.ownerId());
        assertEquals(original.parentAId(), restored.parentAId());
        assertEquals(original.parentBId(), restored.parentBId());
        assertEquals(1_000L, restored.acceptedAtMillis());
        assertEquals(5_000L, restored.readyAtMillis());
        assertEquals(3_000L, restored.feePaid());
        assertEquals("first", restored.parentA().getString("name"));
        assertEquals("second", restored.parentB().getString("name"));
        assertEquals("encrypted-result", restored.eggStack().getString("egg"));
    }

    @Test
    void copiesMutableNbtAtEveryBoundary() {
        CompoundTag first = tag("name", "first");
        DaycareJob job = new DaycareJob(
            UUID.randomUUID(), UUID.randomUUID(), first, tag("name", "second"),
            UUID.randomUUID(), UUID.randomUUID(), 1_000L, 5_000L, 3_000L, null
        );

        first.putString("name", "mutated-before-read");
        CompoundTag exposed = job.parentA();
        exposed.putString("name", "mutated-after-read");

        assertEquals("first", job.parentA().getString("name"));
        assertNotSame(exposed, job.parentA());
    }

    @Test
    void eggCanOnlyBeCommittedOnce() {
        DaycareJob job = job();

        assertFalse(job.hasEgg());
        DaycareJob completed = job.withEgg(tag("egg", "one"));
        assertTrue(completed.hasEgg());
        assertThrows(
            IllegalStateException.class,
            () -> completed.withEgg(tag("egg", "two"))
        );
    }

    @Test
    void readinessUsesStoredWallClockDeadline() {
        DaycareJob job = job();

        assertFalse(job.isTimeReady(4_999L));
        assertTrue(job.isTimeReady(5_000L));
        assertEquals(2_000L, job.readyNow(2_000L).readyAtMillis());
    }

    @Test
    void roundTripsProjectionFacility() {
        DaycareJob original = new DaycareJob(
            UUID.randomUUID(), UUID.randomUUID(),
            tag("name", "first"), tag("name", "second"),
            UUID.randomUUID(), UUID.randomUUID(),
            1_000L, 5_000L, 3_000L, null,
            ResourceLocation.parse("cobbleventure:kanto"), new BlockPos(12, 65, -8)
        );

        DaycareJob restored = DaycareJob.load(original.save());

        assertEquals(ResourceLocation.parse("cobbleventure:kanto"), restored.facilityDimension());
        assertEquals(new BlockPos(12, 65, -8), restored.paddockCenter());
    }

    private static DaycareJob job() {
        return new DaycareJob(
            UUID.randomUUID(), UUID.randomUUID(),
            tag("name", "first"), tag("name", "second"),
            UUID.randomUUID(), UUID.randomUUID(),
            1_000L, 5_000L, 3_000L, null
        );
    }

    private static CompoundTag tag(String key, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        return tag;
    }
}
