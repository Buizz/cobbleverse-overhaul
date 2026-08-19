package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

/** Overworld SavedData adapter for persistable CVES event sessions. */
public final class SavedEventSessionStore extends SavedData implements EventSessionStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_event_sessions";
    private static final int DATA_VERSION = 1;

    private final Map<EventSessionKey, EventSession> sessions = new LinkedHashMap<>();

    public static SavedEventSessionStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(SavedEventSessionStore::new, SavedEventSessionStore::load),
            DATA_FILE
        );
    }

    static SavedEventSessionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SavedEventSessionStore store = new SavedEventSessionStore();
        int version = tag.contains("dataVersion", Tag.TAG_INT)
            ? tag.getInt("dataVersion")
            : 0;
        if (version != DATA_VERSION) {
            LOGGER.error("Unsupported CVES session data version {}; sessions were not loaded", version);
            return store;
        }
        ListTag values = tag.getList("sessions", Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) {
            CompoundTag value = values.getCompound(index);
            try {
                EventSession session = EventSession.fromJson(
                    JsonParser.parseString(value.getString("json")).getAsJsonObject()
                );
                if (store.sessions.putIfAbsent(session.key(), session) != null) {
                    LOGGER.error("Duplicate persisted CVES session ignored: {}", session.key());
                }
            } catch (RuntimeException error) {
                LOGGER.error("Invalid persisted CVES session at index {} was ignored", index, error);
            }
        }
        return store;
    }

    @Override
    public synchronized Optional<EventSession> find(EventSessionKey key) {
        return Optional.ofNullable(sessions.get(key));
    }

    @Override
    public synchronized EventSession putIfAbsent(EventSession session) {
        EventSession existing = sessions.putIfAbsent(session.key(), session);
        if (existing == null) {
            setDirty();
            return session;
        }
        return existing;
    }

    @Override
    public synchronized void save(EventSession session) {
        sessions.put(session.key(), session);
        setDirty();
    }

    @Override
    public synchronized boolean remove(EventSessionKey key) {
        if (sessions.remove(key) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public synchronized Collection<EventSession> sessions() {
        return List.copyOf(sessions.values());
    }

    @Override
    public synchronized CompoundTag save(
        CompoundTag tag, HolderLookup.Provider registries
    ) {
        tag.putInt("dataVersion", DATA_VERSION);
        ListTag values = new ListTag();
        for (EventSession session : sessions.values()) {
            CompoundTag value = new CompoundTag();
            value.putString("json", session.toJson().toString());
            values.add(value);
        }
        tag.put("sessions", values);
        return tag;
    }
}
