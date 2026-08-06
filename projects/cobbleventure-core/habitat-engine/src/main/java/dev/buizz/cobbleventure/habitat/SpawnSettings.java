package dev.buizz.cobbleventure.habitat;

import java.util.Set;

public record SpawnSettings(
    int generation,
    String temperature,
    String humidity,
    String weather,
    String time,
    Set<String> rarities,
    boolean includeSecondary
) {
    public SpawnSettings {
        rarities = rarities == null ? Set.of() : Set.copyOf(rarities);
    }
}
