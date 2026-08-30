package dev.buizz.cobbleventure.adventure.daycare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DaycareJobTest {
    @Test
    void roundTripsMultiplePokemonEggsTimingAndFacility() {
        DaycareJob original = job().addPokemon(stored("third"), 3_000L)
            .afterEggCheck(8_000L, tag("egg", "first-egg"))
            .afterEggCheck(11_000L, tag("egg", "second-egg"));

        DaycareJob restored = DaycareJob.load(original.save());

        assertEquals(original.jobId(), restored.jobId());
        assertEquals(original.ownerId(), restored.ownerId());
        assertEquals(3, restored.pokemonCount());
        assertEquals("first", restored.pokemon(0).data().getString("name"));
        assertEquals("third", restored.pokemon(2).data().getString("name"));
        assertEquals(2, restored.eggCount());
        assertEquals("second-egg", restored.eggStacks().get(1).getString("egg"));
        assertEquals(6_000L, restored.feePaid());
        assertEquals(11_000L, restored.nextEggCheckAtMillis());
        assertEquals(ResourceLocation.parse("cobbleventure:kanto"), restored.facilityDimension());
        assertEquals(new BlockPos(12, 65, -8), restored.paddockCenter());
    }

    @Test
    void copiesMutableNbtAtEveryBoundary() {
        CompoundTag first = tag("name", "first");
        DaycareJob.StoredPokemon stored = new DaycareJob.StoredPokemon(UUID.randomUUID(), first);
        DaycareJob job = new DaycareJob(
            UUID.randomUUID(), UUID.randomUUID(), List.of(stored),
            1_000L, 5_000L, 3_000L, List.of(),
            ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO
        );

        first.putString("name", "mutated-before-read");
        CompoundTag exposed = job.pokemon(0).data();
        exposed.putString("name", "mutated-after-read");

        assertEquals("first", job.pokemon(0).data().getString("name"));
        assertNotSame(exposed, job.pokemon(0).data());
    }

    @Test
    void supportsSixOccupantsAndRejectsASeventh() {
        List<DaycareJob.StoredPokemon> occupants = new ArrayList<>();
        for (int index = 0; index < DaycareJob.MAX_POKEMON; index++) {
            occupants.add(stored("pokemon-" + index));
        }
        DaycareJob full = new DaycareJob(
            UUID.randomUUID(), UUID.randomUUID(), occupants,
            1_000L, 5_000L, 18_000L, List.of(),
            ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO
        );

        assertEquals(6, full.pokemonCount());
        assertThrows(IllegalStateException.class, () -> full.addPokemon(stored("seventh"), 3_000L));
    }

    @Test
    void migratesLegacyFixedParentPairAndEgg() {
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("jobId", UUID.randomUUID());
        legacy.putUUID("ownerId", UUID.randomUUID());
        legacy.putUUID("parentAId", UUID.randomUUID());
        legacy.putUUID("parentBId", UUID.randomUUID());
        legacy.put("parentA", tag("name", "legacy-a"));
        legacy.put("parentB", tag("name", "legacy-b"));
        legacy.putLong("acceptedAtMillis", 1_000L);
        legacy.putLong("readyAtMillis", 5_000L);
        legacy.putLong("feePaid", 3_000L);
        legacy.put("eggStack", tag("egg", "legacy-egg"));
        legacy.putString("facilityDimension", "cobbleventure:kanto");
        legacy.putLong("paddockCenter", new BlockPos(1, 2, 3).asLong());

        DaycareJob migrated = DaycareJob.load(legacy);

        assertEquals(2, migrated.pokemonCount());
        assertEquals("legacy-a", migrated.pokemon(0).data().getString("name"));
        assertEquals(1, migrated.eggCount());
        assertEquals("legacy-egg", migrated.eggStacks().getFirst().getString("egg"));
    }

    @Test
    void readinessUsesStoredWallClockDeadline() {
        DaycareJob job = job();

        assertFalse(job.isEggCheckReady(4_999L));
        assertTrue(job.isEggCheckReady(5_000L));
        assertEquals(2_000L, job.readyNow(2_000L).nextEggCheckAtMillis());
    }

    @Test
    void gameTimeTrainingDataStartsSafelyFromCurrentWallClock() {
        CompoundTag legacy = stored("legacy-trained").save();
        legacy.putBoolean("training", true);
        legacy.remove("trainingStartedAtMillis");
        legacy.putLong("trainingStartedAtGameTime", 1L);

        DaycareJob.StoredPokemon loaded = DaycareJob.StoredPokemon.load(legacy);
        assertEquals(0, DaycarePolicy.accruedTrainingExperience(loaded, 50_000L));

        DaycareJob.StoredPokemon initialized = loaded.initializeTrainingClock(50_000L);
        assertEquals(50_000L, initialized.trainingStartedAtMillis());
        assertEquals(0, DaycarePolicy.accruedTrainingExperience(initialized, 50_000L));
    }

    private static DaycareJob job() {
        return new DaycareJob(
            UUID.randomUUID(), UUID.randomUUID(), List.of(stored("first"), stored("second")),
            1_000L, 5_000L, 3_000L, List.of(),
            ResourceLocation.parse("cobbleventure:kanto"), new BlockPos(12, 65, -8)
        );
    }

    private static DaycareJob.StoredPokemon stored(String name) {
        return new DaycareJob.StoredPokemon(UUID.randomUUID(), tag("name", name));
    }

    private static CompoundTag tag(String key, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        return tag;
    }
}
