package dev.buizz.cobbleventure.region;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import dev.buizz.cobbleventure.api.RegionDefinition;

import java.io.IOException;
import java.nio.file.Path;

public final class RegionDefinitionLoader {
    private final ObjectMapper objectMapper;

    public RegionDefinitionLoader() {
        objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public RegionDefinition load(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), RegionDefinition.class);
    }

    public RegionDefinition load(String json) throws IOException {
        return objectMapper.readValue(json, RegionDefinition.class);
    }
}
