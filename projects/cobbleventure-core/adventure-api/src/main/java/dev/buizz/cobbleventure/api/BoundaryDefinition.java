package dev.buizz.cobbleventure.api;

import java.util.Optional;

public record BoundaryDefinition(
    BoundaryType type,
    Optional<String> template,
    String protectionProfile
) {
}
