package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WildSpawnLevelingTest {
    @Test
    void naturalWaterSpawnsUseTheSurfEncounterPool() {
        assertEquals(
            AdventureWorldContext.WildEncounterMethod.SURF,
            WildSpawnLeveling.naturalEncounterMethod(true)
        );
        assertEquals(
            AdventureWorldContext.WildEncounterMethod.LAND,
            WildSpawnLeveling.naturalEncounterMethod(false)
        );
    }
}
