function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function warningStatus(move) {
  const status = String(move?.status ?? "UNKNOWN").toUpperCase();
  return status === "SUPPORTED" ? null : status;
}

function supportDetail(move) {
  const details = [
    ...(move?.requirements ?? []),
    ...(move?.callbacks ?? []).map((callback) => `callback:${callback}`),
  ];
  return [...new Set(details)].join(", ");
}

export function createNativeMoveSupportIndex(coverage) {
  return new Map(
    (coverage?.moves ?? [])
      .map((move) => [cleanId(move?.id), move])
      .filter(([moveId]) => moveId),
  );
}

export function findNativeMoveSupportWarnings(scenario, coverage) {
  if (scenario?.battleEngine !== "cobbleverse") return [];

  const moveById = createNativeMoveSupportIndex(coverage);
  const warnings = [];
  for (const [sideIndex, side] of (scenario?.sides ?? []).entries()) {
    for (const [pokemonIndex, pokemon] of (side?.team ?? []).entries()) {
      for (const [moveIndex, rawMoveId] of (pokemon?.moveset ?? []).entries()) {
        const moveId = cleanId(rawMoveId);
        if (!moveId) continue;
        const move = moveById.get(moveId);
        const status = warningStatus(move);
        if (!status) continue;

        const statusLabel =
          status === "PARTIAL"
            ? "부분 지원"
            : status === "UNSUPPORTED"
              ? "미지원"
              : "지원 여부 미확인";
        const detail = supportDetail(move);
        const pokemonName =
          pokemon?.resolvedSpecies || pokemon?.species || `슬롯 ${pokemonIndex + 1}`;
        const moveName = move?.name || rawMoveId;
        warnings.push({
          path: `sides.${sideIndex}.team.${pokemonIndex}.moveset.${moveIndex}`,
          code:
            status === "PARTIAL"
              ? "native_move_partial"
              : status === "UNSUPPORTED"
                ? "native_move_unsupported"
                : "native_move_unknown",
          status,
          sideIndex,
          pokemonIndex,
          pokemon: pokemonName,
          moveId,
          moveName,
          message: `${side?.name || `${sideIndex + 1}P`}의 ${pokemonName}: ${moveName}은(는) 자체 엔진 ${statusLabel} 기술입니다.${detail ? ` 확인 필요: ${detail}` : ""}`,
        });
      }
    }
  }
  return warnings;
}
