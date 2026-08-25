package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DungeonClearProgressTest {
    private static final String DUNGEON_ID = "cobbleventure:dungeon/test";

    @Test
    void recordsIndependentClearCounts() {
        CompoundTag playerData = new CompoundTag();

        assertEquals(0, DungeonClearProgress.clearCount(playerData, DUNGEON_ID));
        assertEquals(1, DungeonClearProgress.recordClear(playerData, DUNGEON_ID));
        assertEquals(2, DungeonClearProgress.recordClear(playerData, DUNGEON_ID));
        assertEquals(2, DungeonClearProgress.clearCount(playerData, DUNGEON_ID));
        assertEquals(
            0,
            DungeonClearProgress.clearCount(playerData, "cobbleventure:dungeon/other")
        );
    }

    @Test
    void importsALegacyVictoryFlagOnlyOnce() {
        CompoundTag playerData = new CompoundTag();

        assertEquals(
            1,
            DungeonClearProgress.importLegacyClear(playerData, DUNGEON_ID, true)
        );
        assertEquals(
            1,
            DungeonClearProgress.importLegacyClear(playerData, DUNGEON_ID, true)
        );
    }
}
