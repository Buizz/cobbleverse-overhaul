package dev.buizz.cobbleventure.bootstrap;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable, versioned storage for resumable dungeon run snapshots. */
final class DungeonRunSavedData extends SavedData {
    private static final String DATA_FILE = "cobbleventure_dungeon_runs";
    private static final int VERSION = 1;
    private final Map<UUID, CompoundTag> snapshots = new LinkedHashMap<>();

    static DungeonRunSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(DungeonRunSavedData::new, DungeonRunSavedData::load),
            DATA_FILE
        );
    }

    static DungeonRunSavedData load(
        CompoundTag root, HolderLookup.Provider registries
    ) {
        DungeonRunSavedData data = new DungeonRunSavedData();
        if (root.getInt("version") != VERSION) {
            return data;
        }
        ListTag runs = root.getList("runs", Tag.TAG_COMPOUND);
        for (int index = 0; index < runs.size(); index++) {
            CompoundTag run = runs.getCompound(index);
            if (!run.hasUUID("runId")) continue;
            data.snapshots.put(run.getUUID("runId"), run.copy());
        }
        return data;
    }

    List<CompoundTag> snapshots() {
        return snapshots.values().stream().map(CompoundTag::copy).toList();
    }

    void replace(Collection<CompoundTag> current) {
        Map<UUID, CompoundTag> replacement = new LinkedHashMap<>();
        for (CompoundTag run : current) {
            if (!run.hasUUID("runId")) {
                throw new IllegalArgumentException("Dungeon run snapshot has no runId");
            }
            replacement.put(run.getUUID("runId"), run.copy());
        }
        if (snapshots.equals(replacement)) return;
        snapshots.clear();
        snapshots.putAll(replacement);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("version", VERSION);
        ListTag runs = new ListTag();
        snapshots.values().stream().map(CompoundTag::copy).forEach(runs::add);
        root.put("runs", runs);
        return root;
    }
}
