export function toAiApiObservationDraft({
  battleId,
  seed,
  format,
  engine,
  side,
  turn,
  activePokemon,
  opponentPokemon,
  candidates = [],
}) {
  return {
    schemaVersion: 1,
    source: "web-lab",
    battleId: battleId ?? null,
    seed: seed ?? null,
    format: format ?? "singles",
    engine: engine ?? "unknown",
    side: side ?? "player",
    turn: Number(turn ?? 0),
    activePokemon: normalizePokemon(activePokemon),
    opponentPokemon: normalizePokemon(opponentPokemon),
    candidates: candidates.map(normalizeCandidate),
  };
}

function normalizePokemon(pokemon) {
  if (!pokemon) return null;
  return {
    id: pokemon.id ?? pokemon.speciesId ?? pokemon.species ?? null,
    name: pokemon.name ?? pokemon.species ?? null,
    hp: Number(pokemon.hp ?? pokemon.currentHp ?? 0),
    maxHp: Number(pokemon.maxHp ?? 0),
    status: pokemon.status ?? null,
    types: Array.isArray(pokemon.types) ? pokemon.types : [],
    boosts: pokemon.boosts ?? {},
    item: pokemon.item ?? null,
    ability: pokemon.ability ?? null,
  };
}

function normalizeCandidate(candidate) {
  return {
    type: candidate.type ?? "move",
    id: candidate.id ?? candidate.moveId ?? candidate.switchId ?? null,
    label: candidate.label ?? candidate.name ?? null,
    target: candidate.target ?? null,
    priority: Number(candidate.priority ?? 0),
    metadata: candidate.metadata ?? {},
  };
}
