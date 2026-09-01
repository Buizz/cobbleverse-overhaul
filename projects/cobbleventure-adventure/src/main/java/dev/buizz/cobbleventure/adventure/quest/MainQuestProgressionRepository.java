package dev.buizz.cobbleventure.adventure.quest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/** Reloads data/cobbleventure/main_quest/progression.json atomically. */
public final class MainQuestProgressionRepository extends
    SimplePreparableReloadListener<Optional<JsonObject>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation RESOURCE = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure", "main_quest/progression.json"
    );
    private static final MainQuestProgressionRepository INSTANCE =
        new MainQuestProgressionRepository();
    private volatile MainQuestProgression progression = MainQuestProgression.disabled();

    private MainQuestProgressionRepository() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MainQuestProgressionRepository::onAddReloadListeners);
    }

    public static MainQuestProgression progression() {
        return INSTANCE.progression;
    }

    @Override
    protected Optional<JsonObject> prepare(ResourceManager resources, ProfilerFiller profiler) {
        return resources.getResource(RESOURCE).map(resource -> {
            try (Reader reader = resource.openAsReader()) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException error) {
                throw new IllegalStateException("메인 퀘스트 진행 문서를 읽을 수 없습니다.", error);
            }
        });
    }

    @Override
    protected void apply(
        Optional<JsonObject> document, ResourceManager resources, ProfilerFiller profiler
    ) {
        progression = document.map(MainQuestProgression::parse)
            .orElseGet(MainQuestProgression::disabled);
        LOGGER.info("Loaded {} authored main quest progression steps", progression.steps().size());
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
