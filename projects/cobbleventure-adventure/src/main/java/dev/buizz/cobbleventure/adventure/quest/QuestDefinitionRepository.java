package dev.buizz.cobbleventure.adventure.quest;

import com.google.gson.JsonElement;
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

/** Atomically reloads data/<namespace>/quest definitions. */
public final class QuestDefinitionRepository extends SimplePreparableReloadListener<
    Map<ResourceLocation, JsonElement>
> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final QuestDefinitionRepository INSTANCE = new QuestDefinitionRepository();
    private volatile Map<String, QuestDefinition> definitions = Map.of();

    private QuestDefinitionRepository() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(QuestDefinitionRepository::onAddReloadListeners);
    }

    public static QuestDefinitionRepository instance() { return INSTANCE; }

    public Optional<QuestDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Map<String, QuestDefinition> definitions() { return definitions; }

    @Override protected Map<ResourceLocation, JsonElement> prepare(
        ResourceManager resources, ProfilerFiller profiler
    ) {
        FileToIdConverter converter = FileToIdConverter.json("quest");
        Map<ResourceLocation, JsonElement> loaded = new LinkedHashMap<>();
        converter.listMatchingResources(resources).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                try (Reader reader = entry.getValue().openAsReader()) {
                    loaded.put(converter.fileToId(entry.getKey()), JsonParser.parseReader(reader));
                } catch (IOException error) {
                    throw new IllegalStateException("퀘스트를 읽을 수 없습니다: " + entry.getKey(), error);
                }
            });
        return Map.copyOf(loaded);
    }

    @Override protected void apply(
        Map<ResourceLocation, JsonElement> documents,
        ResourceManager resources,
        ProfilerFiller profiler
    ) {
        Map<String, QuestDefinition> loaded = new LinkedHashMap<>();
        documents.forEach((resourceId, value) -> {
            String id = resourceId.getNamespace() + ":quest/" + resourceId.getPath();
            QuestDefinition definition = QuestDefinition.parse(id, value.getAsJsonObject());
            if (loaded.putIfAbsent(id, definition) != null) {
                throw new IllegalStateException("중복 퀘스트 ID입니다: " + id);
            }
        });
        definitions = Map.copyOf(loaded);
        LOGGER.info("Loaded {} quest definitions", definitions.size());
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
