package dev.buizz.cobbleventure.api;

import java.util.Optional;

public record RegionConnection(
    String target,
    String gateId,
    Optional<String> requirement
) {
}
