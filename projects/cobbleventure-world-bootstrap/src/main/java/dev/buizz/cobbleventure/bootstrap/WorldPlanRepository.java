package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;

/** Loads and caches immutable packaged world plans used by native world generation. */
final class WorldPlanRepository {
    private static final String DATA_ROOT = "/data/cobbleventure/";
    private static final Map<Long, HexWorldPlan> WORLDS =
        new ConcurrentHashMap<>();

    private WorldPlanRepository() {}

    static HexWorldPlan load(long seed) {
        return WORLDS.computeIfAbsent(seed, WorldPlanRepository::read);
    }

    private static HexWorldPlan read(long seed) {
        JsonObject world = readJson("hex_worlds/generation_1.json");
        JsonObject boundaryProfiles = readJson("catalogs/boundary-profiles.json");
        Map<String, Integer> townRadii = new LinkedHashMap<>();
        for (JsonElement element : world.getAsJsonArray("settlements")) {
            String settlementId = element.getAsJsonObject().get("settlement").getAsString();
            String slug = settlementId.substring(settlementId.lastIndexOf('/') + 1);
            JsonObject settlement = readJson("settlements/generation_1/" + slug + ".json");
            townRadii.put(settlementId, settlement.get("town_radius_cells").getAsInt());
        }
        return CobbleventureBootstrap.parseHexWorldPlan(
            world, Map.copyOf(townRadii),
            WorldPlanParser.boundaryProfiles(boundaryProfiles), seed
        );
    }

    private static JsonObject readJson(String path) {
        String resourcePath = DATA_ROOT + path;
        try (InputStream stream = WorldPlanRepository.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException(
                    "Missing native world generation resource: " + resourcePath
                );
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException(
                "Invalid native world generation resource: " + resourcePath, error
            );
        }
    }
}
