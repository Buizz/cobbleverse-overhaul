package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/** Loads representation-neutral NPC bindings from data/&lt;namespace&gt;/npc_event_binding. */
public final class EventNpcBindingRepository extends SimplePreparableReloadListener<
    Map<ResourceLocation, JsonElement>
> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EventNpcBindingRepository INSTANCE = new EventNpcBindingRepository();
    private static final String DIRECTORY = "npc_event_binding";
    private static final Set<String> FIELDS = Set.of("schema_version", "script_id");
    private static final Pattern SCRIPT_ID = Pattern.compile(
        "[a-z0-9_.-]+:event_script/[a-z0-9_./-]+"
    );

    private volatile Map<String, EventNpcBinding> bindingsByTag = Map.of();

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventNpcBindingRepository::onAddReloadListeners);
    }

    public static EventNpcBindingRepository instance() {
        return INSTANCE;
    }

    public Optional<EventNpcBinding> findByEntityTags(Set<String> entityTags) {
        EventNpcBinding match = null;
        for (String tag : entityTags) {
            EventNpcBinding candidate = bindingsByTag.get(tag);
            if (candidate == null) continue;
            if (match != null && !match.equals(candidate)) {
                throw new EventRuntimeException(
                    "NPC에 CVES 바인딩 태그가 여러 개 있습니다: "
                        + match.bindingId() + ", " + candidate.bindingId()
                );
            }
            match = candidate;
        }
        return Optional.ofNullable(match);
    }

    public Map<String, EventNpcBinding> bindingsByTag() {
        return bindingsByTag;
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
                ResourceLocation id = converter.fileToId(entry.getKey());
                try (Reader reader = entry.getValue().openAsReader()) {
                    documents.put(id, JsonParser.parseReader(reader));
                } catch (IOException | JsonParseException error) {
                    throw new EventScriptFormatException(
                        DIRECTORY + "/" + id + ": JSON을 읽을 수 없습니다.", error
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
        LOGGER.info("Loaded {} CVES NPC bindings", bindingsByTag.size());
    }

    void replace(Map<ResourceLocation, JsonElement> documents) {
        Map<String, EventNpcBinding> loaded = new LinkedHashMap<>();
        documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation id = entry.getKey();
            String path = DIRECTORY + "/" + id;
            JsonObject object = requireObject(entry.getValue(), path);
            for (String field : object.keySet()) {
                if (!FIELDS.contains(field)) {
                    throw new EventScriptFormatException(path + ": 알 수 없는 필드입니다: " + field);
                }
            }
            int version = requireInt(object, "schema_version", path);
            if (version != 1) {
                throw new EventScriptFormatException(
                    path + ": 지원하지 않는 schema_version입니다: " + version
                );
            }
            String scriptId = requireString(object, "script_id", path);
            if (ResourceLocation.tryParse(scriptId) == null || !SCRIPT_ID.matcher(scriptId).matches()) {
                throw new EventScriptFormatException(
                    path + ": namespace:event_script/path 형식이 아닌 script_id입니다: " + scriptId
                );
            }
            String bindingId = id.getNamespace() + ":" + id.getPath();
            String entityTag = "cves_binding/" + id.getNamespace() + "/" + id.getPath();
            EventNpcBinding binding = new EventNpcBinding(bindingId, entityTag, scriptId);
            if (loaded.putIfAbsent(entityTag, binding) != null) {
                throw new EventScriptFormatException(path + ": 중복 entity tag입니다: " + entityTag);
            }
        });
        bindingsByTag = Map.copyOf(loaded);
    }

    private static JsonObject requireObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new EventScriptFormatException(path + ": JSON object여야 합니다.");
        }
        return value.getAsJsonObject();
    }

    private static int requireInt(JsonObject value, String field, String path) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            throw new EventScriptFormatException(path + ": " + field + "는 정수여야 합니다.");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new EventScriptFormatException(path + ": " + field + "는 정수여야 합니다.", error);
        }
    }

    private static String requireString(JsonObject value, String field, String path) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString()
            || element.getAsString().isBlank()) {
            throw new EventScriptFormatException(path + ": " + field + "는 문자열이어야 합니다.");
        }
        return element.getAsString();
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
