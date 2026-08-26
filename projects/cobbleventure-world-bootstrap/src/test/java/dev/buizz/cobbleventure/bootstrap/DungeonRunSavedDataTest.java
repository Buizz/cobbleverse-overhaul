package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

final class DungeonRunSavedDataTest {
    @Test
    void roundTripsVersionedRunSnapshotsWithoutSharingMutableTags() {
        UUID runId = UUID.randomUUID();
        CompoundTag snapshot = new CompoundTag();
        snapshot.putUUID("runId", runId);
        snapshot.putString("dungeonId", "cobbleventure:dungeon/test");

        DungeonRunSavedData source = new DungeonRunSavedData();
        source.replace(List.of(snapshot));
        snapshot.putString("dungeonId", "changed:outside");

        CompoundTag serialized = source.save(new CompoundTag(), null);
        DungeonRunSavedData restored = DungeonRunSavedData.load(serialized, null);

        assertEquals(1, restored.snapshots().size());
        assertEquals(runId, restored.snapshots().getFirst().getUUID("runId"));
        assertEquals(
            "cobbleventure:dungeon/test",
            restored.snapshots().getFirst().getString("dungeonId")
        );
    }

    @Test
    void rejectsSnapshotWithoutRunIdentity() {
        DungeonRunSavedData data = new DungeonRunSavedData();
        assertThrows(
            IllegalArgumentException.class,
            () -> data.replace(List.of(new CompoundTag()))
        );
    }
}
