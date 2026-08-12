package dev.buizz.cobbleventure.habitat;

import java.util.Set;

public record SpawnSettings(
    int generation,
    String series,
    int habitatVariant,
    String temperature,
    String humidity,
    String weather,
    String time,
    Set<String> rarities,
    boolean includeSecondary
) {
    public SpawnSettings {
        series = series == null ? "" : series;
        rarities = rarities == null ? Set.of() : Set.copyOf(rarities);
    }

    public SpawnSettings withHabitatVariant(int variant) {
        return new SpawnSettings(
            generation, series, variant, temperature, humidity, weather, time, rarities, includeSecondary
        );
    }

    public SpawnSettings(
        int generation,
        String series,
        String temperature,
        String humidity,
        String weather,
        String time,
        Set<String> rarities,
        boolean includeSecondary
    ) {
        this(generation, series, 0, temperature, humidity, weather, time, rarities, includeSecondary);
    }

    public SpawnSettings(
        int generation,
        String temperature,
        String humidity,
        String weather,
        String time,
        Set<String> rarities,
        boolean includeSecondary
    ) {
        this(generation, "", 0, temperature, humidity, weather, time, rarities, includeSecondary);
    }
}
