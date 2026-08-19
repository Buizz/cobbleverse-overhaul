import {
  normalizeSharedBattleCommandsJson,
  normalizeSharedBattleStateJson,
} from "./shared-ai-core.mjs";

const WEB_KEYS = "__cobbleventureWebKeys";
const jsonClone = (value) =>
  value === undefined ? undefined : JSON.parse(JSON.stringify(value));

function packedAttributes(source, knownKeys) {
  const attributes = {};
  for (const [key, value] of Object.entries(source ?? {})) {
    if (!knownKeys.has(key) && value !== undefined) attributes[key] = jsonClone(value);
  }
  attributes[WEB_KEYS] = Object.keys(source ?? {});
  return attributes;
}

function restoredAttributes(attributes = {}) {
  const base = jsonClone(attributes ?? {});
  const present = new Set(Array.isArray(base[WEB_KEYS]) ? base[WEB_KEYS] : []);
  delete base[WEB_KEYS];
  return { base, present };
}

function assignPresent(result, present, key, value) {
  if (present.has(key)) result[key] = value;
}

const EFFECT_KEYS = new Set([
  "id", "turns", "layers", "sourceSide", "sourceSlot", "values", "flags",
]);

function toSharedEffect(effect) {
  if (effect == null) return null;
  return {
    id: String(effect.id ?? ""),
    turns: Number(effect.turns ?? 0),
    layers: Number(effect.layers ?? 0),
    sourceSide: effect.sourceSide ?? null,
    sourceSlot: effect.sourceSlot ?? null,
    values: effect.values ?? {},
    flags: effect.flags ?? {},
    attributes: packedAttributes(effect, EFFECT_KEYS),
  };
}

function fromSharedEffect(effect) {
  if (effect == null) return null;
  const { base, present } = restoredAttributes(effect.attributes);
  assignPresent(base, present, "id", effect.id);
  assignPresent(base, present, "turns", effect.turns);
  assignPresent(base, present, "layers", effect.layers);
  assignPresent(base, present, "sourceSide", effect.sourceSide ?? null);
  assignPresent(base, present, "sourceSlot", effect.sourceSlot ?? null);
  assignPresent(base, present, "values", effect.values ?? {});
  assignPresent(base, present, "flags", effect.flags ?? {});
  return base;
}

function toEffectMap(effects) {
  return Object.fromEntries(
    Object.entries(effects ?? {}).map(([id, effect]) => [id, toSharedEffect(effect)]),
  );
}

function fromEffectMap(effects) {
  return Object.fromEntries(
    Object.entries(effects ?? {}).map(([id, effect]) => [id, fromSharedEffect(effect)]),
  );
}

const SECONDARY_KEYS = new Set([
  "chance", "status", "volatileStatus", "boosts", "selfBoosts",
]);

function toSharedSecondary(secondary) {
  return {
    chance: Number(secondary.chance ?? 100),
    status: String(secondary.status ?? ""),
    volatileStatus: String(secondary.volatileStatus ?? ""),
    boosts: secondary.boosts ?? {},
    selfBoosts: secondary.selfBoosts ?? {},
    attributes: packedAttributes(secondary, SECONDARY_KEYS),
  };
}

function fromSharedSecondary(secondary) {
  const { base, present } = restoredAttributes(secondary.attributes);
  for (const key of SECONDARY_KEYS) assignPresent(base, present, key, secondary[key]);
  return base;
}

const MOVE_KEYS = new Set([
  "id", "name", "type", "category", "power", "accuracy", "priority", "maxPp", "pp",
  "target", "contact", "punch", "powder", "sound", "status", "selfStatus",
  "volatileStatus", "boosts", "selfBoosts", "heal", "drain", "recoil", "weather",
  "terrain", "pseudoWeather", "sideCondition", "slotCondition", "multihit",
  "multiaccuracy", "willCrit", "selfSwitch", "forceSwitch", "fixedDamage",
  "dynamicDamage", "dynamicPower", "secondaries",
]);

const toSharedFraction = (fraction) =>
  fraction == null ? null : { numerator: Number(fraction[0]), denominator: Number(fraction[1]) };
const fromSharedFraction = (fraction) =>
  fraction == null ? null : [fraction.numerator, fraction.denominator];

function toSharedMove(move) {
  const multiHit = Array.isArray(move.multihit) ? move.multihit : null;
  return {
    id: String(move.id ?? ""),
    name: String(move.name ?? ""),
    type: String(move.type ?? "Normal"),
    category: String(move.category ?? "Status"),
    power: Number(move.power ?? 0),
    accuracy: move.accuracy === true ? 100 : Number(move.accuracy ?? 100),
    alwaysHits: move.accuracy === true,
    priority: Number(move.priority ?? 0),
    maxPp: Number(move.maxPp ?? move.pp ?? 1),
    pp: Number(move.pp ?? 0),
    target: String(move.target ?? "normal"),
    contact: move.contact === true,
    punch: move.punch === true,
    powder: move.powder === true,
    sound: move.sound === true,
    status: String(move.status ?? ""),
    selfStatus: String(move.selfStatus ?? ""),
    volatileStatus: String(move.volatileStatus ?? ""),
    boosts: move.boosts ?? {},
    selfBoosts: move.selfBoosts ?? {},
    heal: toSharedFraction(move.heal),
    drain: toSharedFraction(move.drain),
    recoil: toSharedFraction(move.recoil),
    weather: String(move.weather ?? ""),
    terrain: String(move.terrain ?? ""),
    pseudoWeather: String(move.pseudoWeather ?? ""),
    sideCondition: String(move.sideCondition ?? ""),
    slotCondition: String(move.slotCondition ?? ""),
    multiHitMinimum: multiHit?.[0] ?? null,
    multiHitMaximum: multiHit?.[1] ?? null,
    multiAccuracy: move.multiaccuracy === true,
    willCrit: move.willCrit === true,
    selfSwitch: move.selfSwitch === true,
    forceSwitch: move.forceSwitch === true,
    fixedDamage: move.fixedDamage ?? null,
    dynamicDamage: move.dynamicDamage === true,
    dynamicPower: move.dynamicPower === true,
    secondaries: (move.secondaries ?? []).map(toSharedSecondary),
    effectState: { attributes: packedAttributes(move, MOVE_KEYS) },
  };
}

function fromSharedMove(move) {
  const { base, present } = restoredAttributes(move.effectState?.attributes);
  for (const key of [
    "id", "name", "type", "category", "power", "priority", "maxPp", "pp", "target",
    "contact", "punch", "powder", "sound", "status", "selfStatus", "volatileStatus",
    "boosts", "selfBoosts", "weather", "terrain", "pseudoWeather", "sideCondition",
    "slotCondition", "willCrit", "selfSwitch", "forceSwitch",
    "dynamicDamage", "dynamicPower",
  ]) assignPresent(base, present, key, move[key]);
  assignPresent(base, present, "fixedDamage", move.fixedDamage ?? null);
  assignPresent(base, present, "accuracy", move.alwaysHits ? true : move.accuracy);
  assignPresent(base, present, "heal", fromSharedFraction(move.heal));
  assignPresent(base, present, "drain", fromSharedFraction(move.drain));
  assignPresent(base, present, "recoil", fromSharedFraction(move.recoil));
  assignPresent(
    base,
    present,
    "multihit",
    move.multiHitMinimum == null ? null : [move.multiHitMinimum, move.multiHitMaximum],
  );
  assignPresent(base, present, "multiaccuracy", move.multiAccuracy);
  assignPresent(base, present, "secondaries", (move.secondaries ?? []).map(fromSharedSecondary));
  return base;
}

const POKEMON_KEYS = new Set([
  "id", "name", "baseSpecies", "level", "types", "originalTypes", "gender", "ability",
  "baseAbility", "item", "stats", "baseMaximumHp", "hp", "fainted", "status",
  "statusTurns", "toxicCounter", "boosts", "volatiles", "abilityState", "activeTurns",
  "lastMoveSucceeded", "consecutiveMove", "protectCounter", "lockedMove", "choiceLock",
  "chargingMove", "teraType", "configuredTeraType", "terastallized", "stellarBoostedTypes",
  "hasDynamaxed", "dynamaxTurns", "dynamaxMode", "moves",
]);

function toSharedPokemon(pokemon) {
  return {
    id: String(pokemon.id ?? ""),
    name: String(pokemon.name ?? ""),
    baseSpecies: String(pokemon.baseSpecies ?? ""),
    level: Number(pokemon.level ?? 50),
    types: pokemon.types ?? ["Normal"],
    originalTypes: pokemon.originalTypes ?? pokemon.types ?? ["Normal"],
    gender: String(pokemon.gender ?? ""),
    ability: String(pokemon.ability ?? ""),
    baseAbility: String(pokemon.baseAbility ?? ""),
    item: String(pokemon.item ?? ""),
    stats: pokemon.stats,
    baseMaximumHp: Number(pokemon.baseMaximumHp ?? pokemon.stats?.hp ?? 1),
    hp: Number(pokemon.hp ?? 0),
    fainted: pokemon.fainted === true,
    status: String(pokemon.status ?? ""),
    statusTurns: Number(pokemon.statusTurns ?? 0),
    toxicCounter: Number(pokemon.toxicCounter ?? 0),
    boosts: pokemon.boosts ?? {},
    volatiles: toEffectMap(pokemon.volatiles),
    abilityState: toSharedEffect(pokemon.abilityState ?? {}),
    activeTurns: Number(pokemon.activeTurns ?? 0),
    lastMoveSucceeded: pokemon.lastMoveSucceeded ?? null,
    consecutiveMoveId: String(pokemon.consecutiveMove?.id ?? ""),
    consecutiveMoveCount: Number(pokemon.consecutiveMove?.count ?? 0),
    protectCounter: Number(pokemon.protectCounter ?? 0),
    lockedMove: toSharedEffect(pokemon.lockedMove),
    choiceLock: toSharedEffect(pokemon.choiceLock),
    chargingMove: toSharedEffect(pokemon.chargingMove),
    teraType: pokemon.teraType ?? null,
    configuredTeraType: String(pokemon.configuredTeraType ?? "Normal"),
    terastallized: pokemon.terastallized === true,
    stellarBoostedTypes: pokemon.stellarBoostedTypes ?? [],
    hasDynamaxed: pokemon.hasDynamaxed === true,
    dynamaxTurns: Number(pokemon.dynamaxTurns ?? 0),
    dynamaxMode: pokemon.dynamaxMode ?? null,
    moves: (pokemon.moves ?? []).map(toSharedMove),
    effectState: { attributes: packedAttributes(pokemon, POKEMON_KEYS) },
  };
}

function fromSharedPokemon(pokemon) {
  const { base, present } = restoredAttributes(pokemon.effectState?.attributes);
  for (const key of [
    "id", "name", "baseSpecies", "level", "types", "originalTypes", "gender", "ability",
    "baseAbility", "item", "stats", "baseMaximumHp", "hp", "fainted", "status",
    "statusTurns", "toxicCounter", "boosts", "activeTurns", "protectCounter",
    "configuredTeraType", "terastallized", "stellarBoostedTypes", "hasDynamaxed",
    "dynamaxTurns",
  ]) assignPresent(base, present, key, pokemon[key]);
  assignPresent(base, present, "lastMoveSucceeded", pokemon.lastMoveSucceeded ?? null);
  assignPresent(base, present, "teraType", pokemon.teraType ?? null);
  assignPresent(base, present, "dynamaxMode", pokemon.dynamaxMode ?? null);
  assignPresent(base, present, "volatiles", fromEffectMap(pokemon.volatiles));
  assignPresent(base, present, "abilityState", fromSharedEffect(pokemon.abilityState));
  assignPresent(base, present, "consecutiveMove", {
    id: pokemon.consecutiveMoveId,
    count: pokemon.consecutiveMoveCount,
  });
  assignPresent(base, present, "lockedMove", fromSharedEffect(pokemon.lockedMove));
  assignPresent(base, present, "choiceLock", fromSharedEffect(pokemon.choiceLock));
  assignPresent(base, present, "chargingMove", fromSharedEffect(pokemon.chargingMove));
  assignPresent(base, present, "moves", (pokemon.moves ?? []).map(fromSharedMove));
  return base;
}

const SIDE_KEYS = new Set([
  "name", "active", "bag", "itemUsesRemaining", "usedGimmicks", "gimmickResources",
  "conditions", "team",
]);

function toSharedSide(side) {
  return {
    name: String(side.name ?? ""),
    active: Number(side.active ?? 0),
    bag: side.bag ?? [],
    itemUsesRemaining: Number(side.itemUsesRemaining ?? 0),
    usedGimmicks: side.usedGimmicks ?? {},
    gimmickResources: side.gimmickResources ?? {},
    conditions: toEffectMap(side.conditions),
    team: (side.team ?? []).map(toSharedPokemon),
    effectState: { attributes: packedAttributes(side, SIDE_KEYS) },
  };
}

function fromSharedSide(side) {
  const { base, present } = restoredAttributes(side.effectState?.attributes);
  for (const key of [
    "name", "active", "bag", "itemUsesRemaining", "usedGimmicks", "gimmickResources",
  ]) assignPresent(base, present, key, side[key]);
  assignPresent(base, present, "conditions", fromEffectMap(side.conditions));
  assignPresent(base, present, "team", (side.team ?? []).map(fromSharedPokemon));
  return base;
}

const FIELD_KEYS = new Set(["weather", "terrain", "pseudoWeather"]);

function toSharedField(field = {}) {
  return {
    weather: toSharedEffect(field.weather),
    terrain: toSharedEffect(field.terrain),
    pseudoWeather: toEffectMap(field.pseudoWeather),
    effectState: { attributes: packedAttributes(field, FIELD_KEYS) },
  };
}

function fromSharedField(field) {
  const { base, present } = restoredAttributes(field.effectState?.attributes);
  assignPresent(base, present, "weather", fromSharedEffect(field.weather));
  assignPresent(base, present, "terrain", fromSharedEffect(field.terrain));
  assignPresent(base, present, "pseudoWeather", fromEffectMap(field.pseudoWeather));
  return base;
}

const EVENT_KEYS = new Set([
  "turn", "type", "side", "sourceSide", "pokemon", "sourcePokemon", "targetPokemon",
  "move", "ability", "item", "effect", "slot", "remainingHp", "maximumHp", "status",
  "winner", "message",
]);

function toSharedEvent(event) {
  const result = { attributes: packedAttributes(event, EVENT_KEYS) };
  for (const key of EVENT_KEYS) if (event[key] !== undefined) result[key] = event[key];
  return result;
}

function fromSharedEvent(event) {
  const { base, present } = restoredAttributes(event.attributes);
  for (const key of EVENT_KEYS) assignPresent(base, present, key, event[key] ?? null);
  return base;
}

const STATE_KEYS = new Set([
  "engine", "seed", "rngState", "turn", "status", "winner", "gimmickProfile", "field",
  "manualFaintSwitchSides", "strictMoveEffectValidation", "sides", "events", "futureAttacks",
  "lastSuccessfulMove",
]);

function mapWebState(state) {
  return {
    engine: {
      id: String(state.engine?.id ?? "cobbleventure-simple"),
      version: String(state.engine?.version ?? "0"),
    },
    seed: Number(state.seed ?? 0),
    rngState: state.rngState ?? null,
    turn: Number(state.turn ?? 0),
    status: String(state.status ?? "running"),
    winner: state.winner ?? null,
    gimmickProfile: String(state.gimmickProfile ?? "cobbleventure_all"),
    field: toSharedField(state.field),
    manualFaintSwitchSides: state.manualFaintSwitchSides ?? [],
    strictMoveEffectValidation: state.strictMoveEffectValidation === true,
    sides: (state.sides ?? []).map(toSharedSide),
    events: (state.events ?? []).map(toSharedEvent),
    futureAttacks: (state.futureAttacks ?? []).map(toSharedEffect),
    lastSuccessfulMove: state.lastSuccessfulMove == null
      ? null
      : toSharedMove(state.lastSuccessfulMove),
    attributes: packedAttributes(state, STATE_KEYS),
  };
}

export function toSharedBattleState(webState) {
  return JSON.parse(normalizeSharedBattleStateJson(JSON.stringify(mapWebState(webState))));
}

export function fromSharedBattleState(sharedState) {
  const { base, present } = restoredAttributes(sharedState.attributes);
  for (const key of [
    "engine", "seed", "rngState", "turn", "status", "winner", "gimmickProfile",
    "manualFaintSwitchSides", "strictMoveEffectValidation",
  ]) assignPresent(base, present, key, sharedState[key] ?? null);
  assignPresent(base, present, "field", fromSharedField(sharedState.field));
  assignPresent(base, present, "sides", (sharedState.sides ?? []).map(fromSharedSide));
  assignPresent(base, present, "events", (sharedState.events ?? []).map(fromSharedEvent));
  assignPresent(base, present, "futureAttacks", (sharedState.futureAttacks ?? []).map(fromSharedEffect));
  assignPresent(
    base,
    present,
    "lastSuccessfulMove",
    sharedState.lastSuccessfulMove == null ? null : fromSharedMove(sharedState.lastSuccessfulMove),
  );
  return base;
}

export function toSharedTurnCommands(webCommands, sharedState) {
  const commands = webCommands.map((command, side) => ({
    side,
    kind: command.item ? "item" : Number.isInteger(Number(command.switch)) ? "switch" : "move",
    moveSlot: command.move ?? null,
    switchSlot: command.switch ?? null,
    item: command.item ?? null,
    itemTargetSlot: command.itemTarget ?? null,
    selfSwitchSlot: command.selfSwitchSlot ?? null,
    gimmick: String(command.gimmick ?? ""),
    teraType: String(command.teraType ?? ""),
    attributes: packedAttributes(command, new Set([
      "move", "switch", "item", "itemTarget", "selfSwitchSlot", "gimmick", "teraType",
    ])),
  }));
  return JSON.parse(normalizeSharedBattleCommandsJson(
    JSON.stringify(sharedState),
    JSON.stringify({ commands }),
  ));
}

export function fromSharedTurnCommands(sharedCommands) {
  return sharedCommands.commands.map((command) => {
    const { base, present } = restoredAttributes(command.attributes);
    assignPresent(base, present, "move", command.moveSlot);
    assignPresent(base, present, "switch", command.switchSlot);
    assignPresent(base, present, "item", command.item);
    assignPresent(base, present, "itemTarget", command.itemTargetSlot);
    assignPresent(base, present, "selfSwitchSlot", command.selfSwitchSlot);
    assignPresent(base, present, "gimmick", command.gimmick);
    assignPresent(base, present, "teraType", command.teraType);
    return base;
  });
}

export function roundTripWebBattleState(webState) {
  return fromSharedBattleState(toSharedBattleState(webState));
}
