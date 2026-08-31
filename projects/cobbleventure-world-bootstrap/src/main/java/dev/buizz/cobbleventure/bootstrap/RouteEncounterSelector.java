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
        return forCell(cell, routes, settlementCells, false);
    }

    static ConnectionPath forCell(
        HexCoord cell, List<ConnectionPath> routes, Set<HexCoord> settlementCells,
        boolean allowExplicitSettlementOverride
    ) {
        if (allowExplicitSettlementOverride) {
            ConnectionPath explicit = select(cell, routes, true);
            if (explicit != null) {
                return explicit;
            }
        }
        if (settlementCells.contains(cell)) {
            return null;
        }
        ConnectionPath explicit = select(cell, routes, true);
        if (explicit != null) {
            return explicit;
        }
        return select(cell, routes, false);
    }

    private static ConnectionPath select(
        HexCoord cell, List<ConnectionPath> routes, boolean explicitEncounterArea
    ) {
        ConnectionPath selected = null;
        for (ConnectionPath route : routes) {
            List<HexCoord> candidates = explicitEncounterArea
                ? route.encounterCells() : route.cells();
            if (!candidates.contains(cell)) {
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
