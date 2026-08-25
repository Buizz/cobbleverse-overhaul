package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.nbt.CompoundTag;

/** Persistent per-player dungeon clear history, separate from run objective signals. */
final class DungeonClearProgress {
    private static final String CLEAR_COUNTS = "cobbleventureDungeonClearCounts";

    private DungeonClearProgress() {}

    static int clearCount(CompoundTag playerData, String dungeonId) {
        return Math.max(0, playerData.getCompound(CLEAR_COUNTS).getInt(dungeonId));
    }

    static int importLegacyClear(
        CompoundTag playerData, String dungeonId, boolean legacyVictoryFlag
    ) {
        int current = clearCount(playerData, dungeonId);
        if (current == 0 && legacyVictoryFlag) {
            return recordClear(playerData, dungeonId);
        }
        return current;
    }

    static int recordClear(CompoundTag playerData, String dungeonId) {
        CompoundTag counts = playerData.getCompound(CLEAR_COUNTS);
        int current = Math.max(0, counts.getInt(dungeonId));
        int updated = current == Integer.MAX_VALUE ? current : current + 1;
        counts.putInt(dungeonId, updated);
        playerData.put(CLEAR_COUNTS, counts);
        return updated;
    }
}
