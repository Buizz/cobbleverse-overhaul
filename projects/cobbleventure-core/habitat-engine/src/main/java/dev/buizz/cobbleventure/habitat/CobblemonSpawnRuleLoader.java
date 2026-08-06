package dev.buizz.cobbleventure.habitat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Path;

public final class CobblemonSpawnRuleLoader {
    private final ObjectMapper objectMapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CobblemonSpawnRuleCatalog load(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), CobblemonSpawnRuleCatalog.class);
    }

    public CobblemonSpawnRuleCatalog load(String json) throws IOException {
        return objectMapper.readValue(json, CobblemonSpawnRuleCatalog.class);
    }
}
