package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class GymRadarObjectiveTest {
    @Test
    void selectsNearestUnlockedUnclearedGymInTheCurrentDimension() {
        List<GymInteriorSystem.ObjectiveProgress> candidates = List.of(
            progress("cleared", true, true, true, 1.0D),
            progress("locked", false, false, true, 1.0D),
            progress("other_dimension", false, true, false, 1.0D),
            progress("far", false, true, true, 400.0D),
            progress("near", false, true, true, 25.0D)
        );
        assertEquals(4, GymInteriorSystem.currentObjectiveIndex(candidates));
    }

    @Test
    void returnsNoObjectiveWhenLeagueProgressionIsComplete() {
        assertEquals(-1, GymInteriorSystem.currentObjectiveIndex(List.of(
            progress("cleared", true, true, true, 1.0D),
            progress("locked", false, false, true, 1.0D)
        )));
    }

    private static GymInteriorSystem.ObjectiveProgress progress(
        String id, boolean cleared, boolean unlocked,
        boolean sameDimension, double distanceSquared
    ) {
        return new GymInteriorSystem.ObjectiveProgress(
            id, cleared, unlocked, sameDimension, distanceSquared
        );
    }
}
