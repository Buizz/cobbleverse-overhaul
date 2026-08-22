package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.ConnectionPath;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import java.util.List;
import java.util.Set;

/** Matches the route-tile ownership and overlap priority shown on the regional map. */
final class RouteEncounterSelector {
    private RouteEncounterSelector() {}

    static ConnectionPath forCell(
        HexCoord cell, List<ConnectionPath> routes, Set<HexCoord> settlementCells
    ) {
        if (settlementCells.contains(cell)) {
            return null;
        }
        ConnectionPath selected = null;
        for (ConnectionPath route : routes) {
            if (!route.cells().contains(cell)) {
                continue;
            }
            if (selected == null
                || !selected.surfaceStyle().equals("water")
                    && route.surfaceStyle().equals("water")) {
                selected = route;
            }
        }
        return selected;
    }
}
