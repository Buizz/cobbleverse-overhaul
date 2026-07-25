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
  const observation = toAiBattleObservation({
    battleId,
    seed,
    format,
    engine,
    side,
    turn,
    activePokemon,
    opponentActivePokemon: opponentPokemon,
    legalActions: candidates,
  });
  return {
    ...observation,
    format: observation.battleType.format,
    engine: observation.battleType.engine,
    activePokemon: normalizePokemon(activePokemon),
    opponentPokemon: normalizePokemon(opponentPokemon),
    candidates: candidates.map(normalizeCandidate),
  };
}

export function toAiBattleObservation({
  battleId,
  seed,
  format,
  engine,
  side,
  turn,
  battleType,
  activePokemon = [],
  benchPokemon = [],
  opponentActivePokemon = [],
  opponentBenchKnownInfo = [],
  field = {},
  weather = null,
  terrain = null,
  sideConditions = {},
  legalActions = [],
  revealedInfo = {},
  history = [],
}) {
  const normalizedBattleType = normalizeBattleType({
    battleType,
    format,
    engine,
  });
  return {
    schemaVersion: 1,
    source: "web-lab",
    battleId: battleId ?? null,
    seed: seed ?? null,
    side: side ?? "player",
    turn: Number(turn ?? 0),
    battleType: normalizedBattleType,
    activePokemon: normalizePokemonList(activePokemon),
    benchPokemon: normalizePokemonList(benchPokemon),
    opponentActivePokemon: normalizePokemonList(opponentActivePokemon),
    opponentBenchKnownInfo: normalizePokemonList(opponentBenchKnownInfo),
    field: normalizeField(field),
    weather: weather ? normalizeWeather(weather) : null,
    terrain: terrain ? normalizeTerrain(terrain) : null,
    sideConditions: normalizeSideConditions(sideConditions),
    legalActions: legalActions.map(normalizeLegalAction),
    revealedInfo: normalizeRevealedInfo(revealedInfo),
    history: Array.isArray(history) ? history.map(normalizeHistoryEntry) : [],
  };
}

export function showdownRequestToAiBattleObservation({
  request,
  battleId,
  seed,
  side = "player",
  turn = 0,
  battleType = null,
  engine = "showdown",
  opponentActivePokemon = [],
  opponentBenchKnownInfo = [],
  revealedInfo = {},
  history = [],
}) {
  const team = Array.isArray(request?.side?.pokemon)
    ? request.side.pokemon
    : [];
  const activeSlots = Array.isArray(request?.active) ? request.active : [];
  const activePokemon = team
    .filter((pokemon) => pokemon.active)
    .map(showdownPokemonToObservedPokemon);
  const benchPokemon = team
    .filter((pokemon) => !pokemon.active)
    .map(showdownPokemonToObservedPokemon);
  const legalActions = request?.forceSwitch?.some(Boolean)
    ? benchPokemon
        .filter((pokemon) => !pokemon.fainted)
        .map((pokemon) => ({
          type: "switch",
          switchId: pokemon.id,
          name: pokemon.name,
          slot: pokemon.slot,
        }))
    : activeSlots.flatMap((active, activeIndex) =>
        (Array.isArray(active?.moves) ? active.moves : []).map((move, moveIndex) => ({
          type: "move",
          moveId: move.id,
          name: move.move ?? move.name ?? move.id,
          slot: moveIndex + 1,
          activeSlot: activeIndex + 1,
          target: move.target ?? null,
          disabled: move.disabled === true,
          pp: move.pp,
        })),
      );

  return toAiBattleObservation({
    battleId,
    seed,
    side,
    turn,
    battleType: {
      ...(battleType ?? {}),
      mode: battleType?.mode ?? modeFromActiveSlots(activeSlots.length),
      engine,
    },
    activePokemon,
    benchPokemon,
    opponentActivePokemon,
    opponentBenchKnownInfo,
    legalActions,
    revealedInfo,
    history,
  });
}

export function simpleStateToAiBattleObservation({
  state,
  sideIndex,
  battleId = state?.battleId ?? null,
  seed = state?.seed ?? null,
  turn = state?.turn ?? 0,
  battleType = null,
  engine = "cobbleverse-simple",
  revealedInfo = {},
  history = state?.events ?? [],
}) {
  const side = state?.sides?.[sideIndex] ?? { team: [], active: 0 };
  const opponent = state?.sides?.[sideIndex === 0 ? 1 : 0] ?? {
    team: [],
    active: 0,
  };
  const activePokemon = side.team
    .map((pokemon, index) => ({ pokemon, index }))
    .filter(({ index }) => index === side.active)
    .map(({ pokemon, index }) =>
      simplePokemonToObservedPokemon(pokemon, index, true),
    );
  const benchPokemon = side.team
    .map((pokemon, index) => ({ pokemon, index }))
    .filter(({ index }) => index !== side.active)
    .map(({ pokemon, index }) =>
      simplePokemonToObservedPokemon(pokemon, index, false),
    );
  const opponentActivePokemon = opponent.team
    .map((pokemon, index) => ({ pokemon, index }))
    .filter(({ index }) => index === opponent.active)
    .map(({ pokemon, index }) =>
      simplePokemonToObservedPokemon(pokemon, index, true),
    );
  const opponentBenchKnownInfo = opponent.team
    .map((pokemon, index) => ({ pokemon, index }))
    .filter(({ index }) => index !== opponent.active)
    .map(({ pokemon, index }) =>
      simplePokemonToObservedPokemon(pokemon, index, false),
    );
  const active = side.team?.[side.active];
  const legalActions = [
    ...(active?.fainted
      ? []
      : (active?.moves ?? []).map((move, index) => ({
          type: "move",
          moveId: move.id,
          name: move.name,
          slot: index + 1,
          disabled: move.pp <= 0,
          pp: move.pp,
          priority: move.priority,
        }))),
    ...benchPokemon
      .filter((pokemon) => !pokemon.fainted)
      .map((pokemon) => ({
        type: "switch",
        switchId: pokemon.id,
        name: pokemon.name,
        slot: pokemon.slot,
      })),
  ];

  return toAiBattleObservation({
    battleId,
    seed,
    side: `p${sideIndex + 1}`,
    turn,
    battleType: {
      ...(battleType ?? {}),
      mode: battleType?.mode ?? "single",
      engine,
    },
    activePokemon,
    benchPokemon,
    opponentActivePokemon,
    opponentBenchKnownInfo,
    field: {
      conditions: state?.field?.conditions ?? {},
      pseudoWeather: state?.field?.pseudoWeather ?? {},
    },
    weather: state?.weather ?? null,
    terrain: state?.terrain ?? null,
    sideConditions: {
      self: side.sideConditions ?? {},
      opponent: opponent.sideConditions ?? {},
    },
    legalActions,
    revealedInfo,
    history,
  });
}

function normalizeBattleType({ battleType, format, engine }) {
  const source = battleType ?? {};
  const rawMode = String(source.mode ?? format ?? "single").toLowerCase();
  const mode = rawMode.includes("triple")
    ? "triple"
    : rawMode.includes("double")
      ? "double"
      : "single";
  return {
    mode,
    activeSlotsPerSide:
      Number(source.activeSlotsPerSide) || (mode === "triple" ? 3 : mode === "double" ? 2 : 1),
    format: format ?? source.format ?? `${mode}s`,
    ruleset: Array.isArray(source.ruleset) ? source.ruleset : [],
    engine: engine ?? source.engine ?? "unknown",
  };
}

function modeFromActiveSlots(activeSlots) {
  if (activeSlots >= 3) return "triple";
  if (activeSlots === 2) return "double";
  return "single";
}

function parseShowdownCondition(condition) {
  const text = String(condition ?? "");
  if (text.includes(" fnt")) {
    return { hp: 0, maxHp: 0, status: null, fainted: true };
  }
  const [hpText, status] = text.split(" ");
  const [hp, maxHp] = hpText.split("/").map(Number);
  return {
    hp: Number.isFinite(hp) ? hp : 0,
    maxHp: Number.isFinite(maxHp) ? maxHp : 0,
    status: status ?? null,
    fainted: false,
  };
}

function showdownPokemonToObservedPokemon(pokemon) {
  const condition = parseShowdownCondition(pokemon.condition);
  const species = String(pokemon.details ?? pokemon.species ?? pokemon.name ?? "")
    .split(",")[0]
    .trim();
  return {
    id: pokemon.ident ?? species,
    slot: pokemon.slot ?? null,
    name: species,
    species,
    hp: condition.hp,
    maxHp: condition.maxHp,
    status: condition.status,
    fainted: condition.fainted,
    active: pokemon.active === true,
    item: pokemon.item ?? null,
    ability: pokemon.ability ?? null,
    moves: Array.isArray(pokemon.moves)
      ? pokemon.moves.map((move) => ({ id: move, name: move, revealed: true }))
      : [],
  };
}

function simplePokemonToObservedPokemon(pokemon, index, active) {
  return {
    id: pokemon.id ?? pokemon.name ?? null,
    slot: index + 1,
    name: pokemon.name ?? pokemon.id ?? null,
    hp: Number(pokemon.hp ?? 0),
    maxHp: Number(pokemon.stats?.hp ?? pokemon.maxHp ?? 0),
    status: pokemon.status || null,
    types: Array.isArray(pokemon.types) ? pokemon.types : [],
    boosts: pokemon.boosts ?? {},
    item: pokemon.item ?? null,
    ability: pokemon.ability ?? null,
    moves: Array.isArray(pokemon.moves)
      ? pokemon.moves.map((move) => ({
          id: move.id,
          name: move.name,
          pp: Number(move.pp ?? 0),
          revealed: true,
        }))
      : [],
    active,
    fainted: pokemon.fainted === true,
  };
}

function normalizePokemonList(pokemon) {
  if (Array.isArray(pokemon)) return pokemon.map(normalizePokemon).filter(Boolean);
  const normalized = normalizePokemon(pokemon);
  return normalized ? [normalized] : [];
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
    moves: Array.isArray(pokemon.moves)
      ? pokemon.moves.map((move) => ({
          id: move.id ?? move.moveId ?? null,
          name: move.name ?? move.label ?? move.id ?? null,
          pp: Number(move.pp ?? 0),
          revealed: move.revealed ?? true,
        }))
      : [],
    active: pokemon.active ?? undefined,
    fainted: pokemon.fainted ?? String(pokemon.condition ?? "").endsWith(" fnt"),
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

function normalizeLegalAction(action) {
  return {
    type: action.type ?? "move",
    id: action.id ?? action.moveId ?? action.switchId ?? null,
    label: action.label ?? action.name ?? null,
    slot: action.slot ?? null,
    target: action.target ?? null,
    legal: action.legal ?? !action.disabled,
    disabled: action.disabled === true,
    pp: action.pp === undefined ? null : Number(action.pp),
    priority: Number(action.priority ?? 0),
    metadata: action.metadata ?? {},
  };
}

function normalizeField(field) {
  return {
    conditions: field.conditions ?? {},
    pseudoWeather: field.pseudoWeather ?? {},
    raw: field.raw ?? undefined,
  };
}

function normalizeWeather(weather) {
  if (typeof weather === "string") return { id: weather };
  return {
    id: weather.id ?? weather.name ?? null,
    turnsRemaining: weather.turnsRemaining ?? null,
  };
}

function normalizeTerrain(terrain) {
  if (typeof terrain === "string") return { id: terrain };
  return {
    id: terrain.id ?? terrain.name ?? null,
    turnsRemaining: terrain.turnsRemaining ?? null,
  };
}

function normalizeSideConditions(sideConditions) {
  return {
    self: sideConditions.self ?? {},
    opponent: sideConditions.opponent ?? {},
  };
}

function normalizeRevealedInfo(revealedInfo) {
  return {
    opponentSpecies: Array.isArray(revealedInfo.opponentSpecies)
      ? revealedInfo.opponentSpecies
      : [],
    opponentMoves: revealedInfo.opponentMoves ?? {},
    opponentItems: revealedInfo.opponentItems ?? {},
    opponentAbilities: revealedInfo.opponentAbilities ?? {},
  };
}

function normalizeHistoryEntry(entry) {
  return {
    turn: Number(entry.turn ?? 0),
    type: entry.type ?? "event",
    actor: entry.actor ?? null,
    target: entry.target ?? null,
    detail: entry.detail ?? null,
  };
}
