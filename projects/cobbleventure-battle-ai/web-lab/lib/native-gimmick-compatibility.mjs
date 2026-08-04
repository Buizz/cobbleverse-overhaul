const normalizeGimmick = (gimmick) =>
  gimmick === "gigantamax" ? "dynamax" : String(gimmick ?? "");

// Rows are already-used transformations and columns are newly requested ones.
// Keep rule changes here so the engine, AI, and interactive PvE UI stay aligned.
export const NATIVE_GIMMICK_COMPATIBILITY = Object.freeze({
  mega: Object.freeze({
    mega: false,
    zmove: false,
    dynamax: false,
    terastallize: true,
  }),
  zmove: Object.freeze({
    mega: false,
    zmove: false,
    dynamax: false,
    terastallize: false,
  }),
  dynamax: Object.freeze({
    mega: false,
    zmove: false,
    dynamax: false,
    terastallize: false,
  }),
  terastallize: Object.freeze({
    mega: false,
    zmove: false,
    dynamax: false,
    terastallize: false,
  }),
});

export function activePokemonGimmicks(pokemon) {
  const active = [];
  if (pokemon?.megaEvolved === true) active.push("mega");
  if (pokemon?.hasDynamaxed === true || Number(pokemon?.dynamaxTurns ?? 0) > 0) {
    active.push("dynamax");
  }
  if (pokemon?.terastallized === true) active.push("terastallize");
  return active;
}

export function pokemonGimmickConflict(pokemon, requestedGimmick) {
  const requested = normalizeGimmick(requestedGimmick);
  return (
    activePokemonGimmicks(pokemon).find(
      (active) =>
        NATIVE_GIMMICK_COMPATIBILITY[active]?.[requested] === false,
    ) ?? null
  );
}

export function canPokemonCombineGimmick(pokemon, requestedGimmick) {
  return pokemonGimmickConflict(pokemon, requestedGimmick) == null;
}
