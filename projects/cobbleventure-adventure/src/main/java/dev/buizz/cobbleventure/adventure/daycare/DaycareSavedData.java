package dev.buizz.cobbleventure.adventure.daycare;

import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

/** Overworld-owned durable storage for daycare parents and completed eggs. */
final class DaycareSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_daycare_jobs";
    private static final int DATA_VERSION = 2;

    private final Map<UUID, DaycareJob> jobs = new LinkedHashMap<>();

    static DaycareSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(DaycareSavedData::new, DaycareSavedData::load),
            DATA_FILE
        );
    }

    private static DaycareSavedData load(
        CompoundTag tag, HolderLookup.Provider registries
    ) {
        DaycareSavedData data = new DaycareSavedData();
        int version = tag.getInt("dataVersion");
        if (version != 1 && version != DATA_VERSION) {
            LOGGER.error("Unsupported daycare data version {}; jobs were not loaded", version);
            return data;
        }
        ListTag entries = tag.getList("jobs", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            try {
                DaycareJob job = DaycareJob.load(entries.getCompound(index));
                if (data.jobs.putIfAbsent(job.ownerId(), job) != null) {
                    LOGGER.error("Duplicate daycare job for player {} was ignored", job.ownerId());
                }
            } catch (RuntimeException error) {
                LOGGER.error("Invalid daycare job at index {} was ignored", index, error);
            }
        }
        return data;
    }

    synchronized Optional<DaycareJob> find(UUID ownerId) {
        return Optional.ofNullable(jobs.get(ownerId));
    }

    synchronized List<DaycareJob> snapshotJobs() {
        return List.copyOf(jobs.values());
    }

    synchronized boolean create(DaycareJob job) {
        if (jobs.putIfAbsent(job.ownerId(), job) != null) {
            return false;
        }
        setDirty();
        return true;
    }

    synchronized void replace(DaycareJob job) {
        if (!jobs.containsKey(job.ownerId())) {
            throw new IllegalStateException("교체할 키우미 작업이 없습니다.");
        }
        jobs.put(job.ownerId(), job);
        setDirty();
    }

    synchronized boolean remove(UUID ownerId, UUID expectedJobId) {
        DaycareJob current = jobs.get(ownerId);
        if (current == null || !current.jobId().equals(expectedJobId)) {
            return false;
        }
        jobs.remove(ownerId);
        setDirty();
        return true;
    }

    @Override
    public synchronized CompoundTag save(
        CompoundTag tag, HolderLookup.Provider registries
    ) {
        tag.putInt("dataVersion", DATA_VERSION);
        ListTag entries = new ListTag();
        for (DaycareJob job : jobs.values()) {
            entries.add(job.save());
        }
        tag.put("jobs", entries);
        return tag;
    }
}
