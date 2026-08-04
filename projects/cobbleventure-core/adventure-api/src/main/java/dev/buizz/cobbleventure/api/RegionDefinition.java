package dev.buizz.cobbleventure.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record RegionDefinition(
    int schemaVersion,
    String id,
    String dimension,
    RegionBounds bounds,
    List<String> biomePool,
    BoundaryDefinition boundary,
    List<RegionConnection> connections,
    Map<String, BlockPosition> anchors,
    Optional<String> spawnProfile
) {
}
