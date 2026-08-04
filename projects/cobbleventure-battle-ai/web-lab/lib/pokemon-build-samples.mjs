const STAT_KEYS = ["hp", "atk", "def", "spa", "spd", "spe"];

function boundedStats(value, fallback, maximum) {
  return Object.fromEntries(
    STAT_KEYS.map((stat) => {
      const candidate = Number(value?.[stat] ?? fallback);
      return [
        stat,
        Number.isFinite(candidate)
          ? Math.min(maximum, Math.max(0, Math.trunc(candidate)))
          : fallback,
      ];
    }),
  );
}

export function normalizePokemonBuildSample(sample) {
  if (!sample || typeof sample !== "object") return null;
  const moves = Array.isArray(sample.moves)
    ? sample.moves.slice(0, 4).map((move) => String(move ?? ""))
    : [];
  const species = String(sample.species ?? "").trim();
  if (!species || moves.filter(Boolean).length === 0) return null;

  return {
    schemaVersion: 1,
    id: String(sample.id ?? species),
    source: sample.source === "pkmnchamps" ? "pkmnchamps" : "pokesample",
    format: sample.format === "champions" ? "champions" : "sv",
    battleStyle: sample.battleStyle === "double" ? "double" : "single",
    title: String(sample.title ?? species),
    species,
    speciesLabel: String(sample.speciesLabel ?? species),
    level: Math.min(100, Math.max(1, Math.trunc(Number(sample.level ?? 50)))),
    ability: String(sample.ability ?? ""),
    abilityLabel: String(sample.abilityLabel ?? sample.ability ?? ""),
    heldItem: String(sample.heldItem ?? ""),
    heldItemLabel: String(sample.heldItemLabel ?? sample.heldItem ?? ""),
    nature: String(sample.nature ?? ""),
    natureLabel: String(sample.natureLabel ?? sample.nature ?? ""),
    tera: String(sample.tera ?? ""),
    moves: [...moves, "", "", "", ""].slice(0, 4),
    moveLabels: Array.isArray(sample.moveLabels)
      ? [...sample.moveLabels.map(String), "", "", "", ""].slice(0, 4)
      : [...moves, "", "", "", ""].slice(0, 4),
    ivs: boundedStats(sample.ivs, 31, 31),
    evs: boundedStats(sample.evs, 0, 252),
    tags: Array.isArray(sample.tags) ? sample.tags.map(String) : [],
    sourceUrl: String(sample.sourceUrl ?? ""),
    sourceStats:
      sample.sourceStats && typeof sample.sourceStats === "object"
        ? sample.sourceStats
        : null,
  };
}

export function applyPokemonBuildSample(currentPokemon, rawSample) {
  const sample = normalizePokemonBuildSample(rawSample);
  if (!sample) return currentPokemon;
  return {
    ...currentPokemon,
    species: sample.species,
    level: sample.level,
    ability: sample.ability,
    heldItem: sample.heldItem,
    nature: sample.nature,
    ivs: sample.ivs,
    evs: sample.evs,
    tera: sample.tera,
    dynamax: false,
    gmax: false,
    moves: sample.moves,
  };
}
