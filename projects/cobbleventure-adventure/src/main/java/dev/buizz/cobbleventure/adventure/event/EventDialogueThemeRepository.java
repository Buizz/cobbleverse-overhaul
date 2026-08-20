package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/** Reloads the server-authoritative global dialogue presentation snapshot. */
public final class EventDialogueThemeRepository extends SimplePreparableReloadListener<String> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EventDialogueThemeRepository INSTANCE = new EventDialogueThemeRepository();
    private static final ResourceLocation GLOBAL = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure", "global"
    );
    private static final String DEFAULT_JSON = """
        {"schema_version":1,"font":{"resource":"minecraft:default","body_scale":1.0,"speaker_scale":1.0,"hint_scale":0.85},"panel":{"background":"#f8fbff","background_opacity":0.98,"border":"#72a8d4","inner_border":"#d9f4ff","border_width":3,"inner_border_width":2,"corner_radius":18,"shadow":"#24445f","shadow_opacity":0.45,"shadow_offset":3,"speaker_color":"#c52b2b","text_color":"#27323d","hint_color":"#57758e","page_color":"#72a8d4","height_ratio":0.333,"min_height":112,"max_height":166},"choice":{"panel_background":"#f8fbff","panel_opacity":0.98,"panel_border":"#72a8d4","panel_inner_border":"#d9f4ff","corner_radius":12,"panel_width":190,"panel_gap":8,"panel_padding":10,"selected_background":"#d9f4ff","hover_background":"#eaf7ff","background":"#f8fbff","selected_accent":"#4f8fc2","text_color":"#27323d","row_height":24},"menu":{"background":"#f8fbff","background_opacity":0.98,"border":"#72a8d4","inner_border":"#d9f4ff","corner_radius":14,"row_radius":7,"selected_background":"#d9f4ff","hover_background":"#eaf7ff","text_color":"#27323d","selected_text_color":"#173f5f","accent":"#4f8fc2"},"portrait":{"yaw_degrees":18.0,"pitch_degrees":-4.0,"scale":0.7,"background":"#0a1017","background_opacity":0.72,"accent":"#5e7789"}}
        """;

    private volatile String themeJson = DEFAULT_JSON;

    private EventDialogueThemeRepository() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventDialogueThemeRepository::onAddReloadListeners);
    }

    public static String snapshot() {
        return INSTANCE.themeJson;
    }

    @Override
    protected String prepare(ResourceManager resources, ProfilerFiller profiler) {
        FileToIdConverter converter = FileToIdConverter.json("dialogue_theme");
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> matches =
            converter.listMatchingResources(resources);
        var resource = matches.entrySet().stream()
            .filter(entry -> converter.fileToId(entry.getKey()).equals(GLOBAL))
            .map(Map.Entry::getValue)
            .findFirst();
        if (resource.isEmpty()) return DEFAULT_JSON;
        try (Reader reader = resource.get().openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!(parsed instanceof JsonObject object)
                || !object.has("schema_version") || object.get("schema_version").getAsInt() != 1) {
                throw new JsonParseException("schema_version은 1이어야 합니다.");
            }
            return parsed.toString();
        } catch (IOException | JsonParseException | IllegalStateException error) {
            throw new EventScriptFormatException(
                "dialogue_theme/cobbleventure:global을 읽을 수 없습니다.", error
            );
        }
    }

    @Override
    protected void apply(String prepared, ResourceManager resources, ProfilerFiller profiler) {
        themeJson = prepared;
        LOGGER.info("Loaded global CVES dialogue theme");
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
