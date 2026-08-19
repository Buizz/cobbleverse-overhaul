package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/** Atomically reloads compiled CVES scripts from data/&lt;namespace&gt;/event_script. */
public final class EventScriptRepository extends SimplePreparableReloadListener<
    Map<ResourceLocation, JsonElement>
> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EventScriptRepository INSTANCE = new EventScriptRepository();
    private static final String DIRECTORY = "event_script";

    private volatile Map<String, EventScript> scripts = Map.of();

    public EventScriptRepository() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventScriptRepository::onAddReloadListeners);
    }

    public static EventScriptRepository instance() {
        return INSTANCE;
    }

    public Optional<EventScript> find(String scriptId) {
        return Optional.ofNullable(scripts.get(scriptId));
    }

    public Map<String, EventScript> scripts() {
        return scripts;
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(
        ResourceManager resources, ProfilerFiller profiler
    ) {
        FileToIdConverter converter = FileToIdConverter.json(DIRECTORY);
        Map<ResourceLocation, JsonElement> documents = new LinkedHashMap<>();
        converter.listMatchingResources(resources).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                ResourceLocation resourceId = converter.fileToId(entry.getKey());
                try (Reader reader = entry.getValue().openAsReader()) {
                    documents.put(resourceId, JsonParser.parseReader(reader));
                } catch (IOException | JsonParseException error) {
                    throw new EventScriptFormatException(
                        "event_script/" + resourceId + ": JSON을 읽을 수 없습니다.", error
                    );
                }
            });
        return Map.copyOf(documents);
    }

    @Override
    protected void apply(
        Map<ResourceLocation, JsonElement> documents,
        ResourceManager resources,
        ProfilerFiller profiler
    ) {
        replace(documents);
        LOGGER.info("Loaded {} CVES event scripts", scripts.size());
    }

    void replace(Map<ResourceLocation, JsonElement> documents) {
        Map<String, EventScript> loaded = new LinkedHashMap<>();
        documents.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                ResourceLocation resourceId = entry.getKey();
                String expectedScriptId = resourceId.getNamespace()
                    + ":event_script/" + resourceId.getPath();
                EventScript script;
                try {
                    script = EventScriptLoader.parse(entry.getValue().toString());
                } catch (EventScriptFormatException error) {
                    throw new EventScriptFormatException(
                        "event_script/" + resourceId + ": " + error.getMessage(), error
                    );
                }
                if (!script.scriptId().equals(expectedScriptId)) {
                    throw new EventScriptFormatException(
                        "event_script/" + resourceId + ": script_id는 리소스 경로와 같은 "
                            + expectedScriptId + "여야 합니다."
                    );
                }
                if (loaded.putIfAbsent(script.scriptId(), script) != null) {
                    throw new EventScriptFormatException(
                        "중복 event script ID입니다: " + script.scriptId()
                    );
                }
            });
        scripts = Map.copyOf(loaded);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
