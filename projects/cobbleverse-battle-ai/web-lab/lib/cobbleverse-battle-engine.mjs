const ENGINE_ID = "cobbleverse-simple";
import {
  DYNAMAX_BLOCKED_WEIGHT_MOVES,
  resolveDynamicPower,
  resolveDynamicPostHit,
  SUPPORTED_DYNAMIC_POWER_MOVES,
} from "./native-dynamic-power.mjs";
import { resolveNativeMaxMove } from "./native-max-moves.mjs";
import {
  analyzeTeamProfile,
  aiDecisionReason,
  createAiRng,
  scoreAiMoveCandidate,
  selectAiGimmick,
  selectAiMoveCandidate,
  selectAiSwitchCandidate,
  toAiActionCandidate,
} from "./common-battle-ai.mjs";

const ENGINE_VERSION = "0.9.6";
const DEFAULT_MAX_TURNS = 200;
const DEFAULT_FIELD_DURATION = 5;
const GIMMICK_KINDS = ["mega", "zmove", "dynamax", "terastallize"];
const PRE_MOVE_GIMMICKS = new Set(["mega", "dynamax", "terastallize"]);
const SIDE_CONDITION_DURATIONS = {
  auroraveil: 5,
  craftyshield: 1,
  lightscreen: 5,
  luckychant: 5,
  matblock: 1,
  quickguard: 1,
  reflect: 5,
  safeguard: 5,
  tailwind: 4,
  wideguard: 1,
};
const LAYERED_SIDE_CONDITIONS = {
  spikes: 3,
  toxicspikes: 2,
};
const CHARGING_MOVES = new Set([
  "bounce",
  "dig",
  "dive",
  "electroshot",
  "fly",
  "freezeshock",
  "iceburn",
  "meteorbeam",
  "phantomforce",
  "razorwind",
  "shadowforce",
  "skullbash",
  "skyattack",
  "skydrop",
  "solarbeam",
  "solarblade",
]);
const BINDING_VOLATILES = new Set([
  "bind",
  "clamp",
  "firespin",
  "infestation",
  "magmastorm",
  "sandtomb",
  "snaptrap",
  "thundercage",
  "whirlpool",
  "wrap",
]);
const SELF_DESTRUCT_MOVES = new Set([
  "explosion",
  "mistyexplosion",
  "selfdestruct",
]);
const ROLLING_LOCK_MOVES = new Set(["iceball", "rollout"]);
const RAMPAGE_LOCK_MOVES = new Set(["outrage", "petaldance", "thrash"]);
const CHOICE_LOCK_ITEMS = new Set(["choiceband", "choicescarf", "choicespecs"]);
const LOADED_DICE_ITEMS = new Set(["loadeddice"]);
const AI_RECOVERY_MOVES = new Set([
  "recover",
  "roost",
  "softboiled",
  "slackoff",
  "morningsun",
  "synthesis",
  "moonlight",
  "rest",
  "wish",
]);
const AI_PROTECTIVE_MOVES = new Set([
  "protect",
  "detect",
  "kingsshield",
  "spikyshield",
  "banefulbunker",
  "burningbulwark",
  "obstruct",
  "silktrap",
]);
const IMPLEMENTED_ABILITIES = new Set([
  "adaptability",
  "guts",
  "hugepower",
  "immunity",
  "insomnia",
  "intimidate",
  "levitate",
  "limber",
  "multiscale",
  "owntempo",
  "minus",
  "plus",
  "pressure",
  "purepower",
  "shadowshield",
  "simple",
  "skilllink",
  "thickfat",
  "vitalspirit",
  "waterveil",
]);
const INTENTIONAL_NO_EFFECT_ABILITIES = new Set([
  "runaway",
]);
const SUPPORTED_ABILITIES = new Set([
  ...IMPLEMENTED_ABILITIES,
  ...INTENTIONAL_NO_EFFECT_ABILITIES,
]);
const STATUS_IMMUNITY_ABILITIES = {
  brn: new Set(["waterveil"]),
  par: new Set(["limber"]),
  psn: new Set(["immunity"]),
  tox: new Set(["immunity"]),
  slp: new Set(["insomnia", "vitalspirit"]),
};

const TRAPPING_VOLATILES = new Set([
  "block",
  "fairylock",
  "ingrain",
  "jawlock",
  "meanlook",
  "spiderweb",
  "thousandwaves",
  "trapped",
]);
const BOOST_STATS = [
  "attack",
  "defence",
  "specialAttack",
  "specialDefence",
  "speed",
  "accuracy",
  "evasion",
];
const STAT_ALIASES = {
  atk: "attack",
  attack: "attack",
  def: "defence",
  defense: "defence",
  defence: "defence",
  spa: "specialAttack",
  specialattack: "specialAttack",
  spd: "specialDefence",
  specialdefense: "specialDefence",
  specialdefence: "specialDefence",
  spe: "speed",
  speed: "speed",
  accuracy: "accuracy",
  evasion: "evasion",
};

const TYPE_CHART = {
  Normal: { Rock: 0.5, Ghost: 0, Steel: 0.5 },
  Fire: {
    Fire: 0.5,
    Water: 0.5,
    Grass: 2,
    Ice: 2,
    Bug: 2,
    Rock: 0.5,
    Dragon: 0.5,
    Steel: 2,
  },
  Water: {
    Fire: 2,
    Water: 0.5,
    Grass: 0.5,
    Ground: 2,
    Rock: 2,
    Dragon: 0.5,
  },
  Electric: {
    Water: 2,
    Electric: 0.5,
    Grass: 0.5,
    Ground: 0,
    Flying: 2,
    Dragon: 0.5,
  },
  Grass: {
    Fire: 0.5,
    Water: 2,
    Grass: 0.5,
    Poison: 0.5,
    Ground: 2,
    Flying: 0.5,
    Bug: 0.5,
    Rock: 2,
    Dragon: 0.5,
    Steel: 0.5,
  },
  Ice: {
    Fire: 0.5,
    Water: 0.5,
    Grass: 2,
    Ice: 0.5,
    Ground: 2,
    Flying: 2,
    Dragon: 2,
    Steel: 0.5,
  },
  Fighting: {
    Normal: 2,
    Ice: 2,
    Poison: 0.5,
    Flying: 0.5,
    Psychic: 0.5,
    Bug: 0.5,
    Rock: 2,
    Ghost: 0,
    Dark: 2,
    Steel: 2,
    Fairy: 0.5,
  },
  Poison: {
    Grass: 2,
    Poison: 0.5,
    Ground: 0.5,
    Rock: 0.5,
    Ghost: 0.5,
    Steel: 0,
    Fairy: 2,
  },
  Ground: {
    Fire: 2,
    Electric: 2,
    Grass: 0.5,
    Poison: 2,
    Flying: 0,
    Bug: 0.5,
    Rock: 2,
    Steel: 2,
  },
  Flying: {
    Electric: 0.5,
    Grass: 2,
    Fighting: 2,
    Bug: 2,
    Rock: 0.5,
    Steel: 0.5,
  },
  Psychic: {
    Fighting: 2,
    Poison: 2,
    Psychic: 0.5,
    Dark: 0,
    Steel: 0.5,
  },
  Bug: {
    Fire: 0.5,
    Grass: 2,
    Fighting: 0.5,
    Poison: 0.5,
    Flying: 0.5,
    Psychic: 2,
    Ghost: 0.5,
    Dark: 2,
    Steel: 0.5,
    Fairy: 0.5,
  },
  Rock: {
    Fire: 2,
    Ice: 2,
    Fighting: 0.5,
    Ground: 0.5,
    Flying: 2,
    Bug: 2,
    Steel: 0.5,
  },
  Ghost: { Normal: 0, Psychic: 2, Ghost: 2, Dark: 0.5 },
  Dragon: { Dragon: 2, Steel: 0.5, Fairy: 0 },
  Dark: { Fighting: 0.5, Psychic: 2, Ghost: 2, Dark: 0.5, Fairy: 0.5 },
  Steel: {
    Fire: 0.5,
    Water: 0.5,
    Electric: 0.5,
    Ice: 2,
    Rock: 2,
    Steel: 0.5,
    Fairy: 2,
  },
  Fairy: {
    Fire: 0.5,
    Fighting: 2,
    Poison: 0.5,
    Dragon: 2,
    Dark: 2,
    Steel: 0.5,
  },
};

function clone(value) {
  return structuredClone(value);
}

function assertFinitePositive(value, path) {
  if (!Number.isFinite(Number(value)) || Number(value) <= 0) {
    throw new Error(`${path} must be a positive number`);
  }
  return Number(value);
}

function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function cleanDisplayName(value) {
  if (!value) return "";
  if (typeof value === "object") {
    return String(
      value.name ?? value.id ?? value.fullname ?? value.species ?? "",
    ).trim();
  }
  return String(value).trim();
}

function normalizeOptionalStats(stats) {
  if (!stats || typeof stats !== "object") return null;
  const normalized = {};
  for (const stat of [
    "attack",
    "defence",
    "specialAttack",
    "specialDefence",
    "speed",
  ]) {
    const value = Number(stats[stat]);
    if (Number.isFinite(value) && value > 0) normalized[stat] = Math.floor(value);
  }
  return Object.keys(normalized).length ? normalized : null;
}

function normalizeBoosts(boosts) {
  if (!boosts || typeof boosts !== "object") return {};
  return Object.fromEntries(
    Object.entries(boosts)
      .map(([stat, amount]) => [
        STAT_ALIASES[cleanId(stat)],
        Number(amount),
      ])
      .filter(
        ([stat, amount]) =>
          BOOST_STATS.includes(stat) &&
          Number.isFinite(amount) &&
          amount !== 0,
      ),
  );
}

function normalizeFraction(value) {
  if (!Array.isArray(value) || value.length < 2) return null;
  const numerator = Number(value[0]);
  const denominator = Number(value[1]);
  if (
    !Number.isFinite(numerator) ||
    !Number.isFinite(denominator) ||
    denominator <= 0
  ) {
    return null;
  }
  return [numerator, denominator];
}

function normalizeSecondary(effect) {
  if (!effect || typeof effect !== "object") return null;
  return {
    chance: Math.max(0, Math.min(100, Number(effect.chance ?? 100))),
    status: cleanId(effect.status),
    volatileStatus: cleanId(effect.volatileStatus),
    boosts: normalizeBoosts(effect.boosts),
    selfBoosts: normalizeBoosts(effect.self?.boosts),
  };
}

function normalizeMove(move, path) {
  const category = String(move?.category ?? "Status");
  if (!["Physical", "Special", "Status"].includes(category)) {
    throw new Error(`${path}.category is invalid`);
  }
  const target = String(move?.target ?? "normal");
  const targetsSelf = target === "self";
  const directBoosts = normalizeBoosts(move?.boosts);
  const nestedSelfBoosts = normalizeBoosts(move?.self?.boosts);
  const directStatus = cleanId(move?.status);
  return {
    id: String(move?.id ?? "").trim(),
    name: String(move?.name ?? move?.id ?? "").trim(),
    type: String(move?.type ?? "Normal"),
    category,
    power: Math.max(0, Number(move?.power ?? 0)),
    accuracy:
      move?.accuracy === true
        ? true
        : Math.max(0, Math.min(100, Number(move?.accuracy ?? 100))),
    priority: Number(move?.priority ?? 0),
    maxPp: Math.max(1, Number(move?.pp ?? move?.maxPp ?? 1)),
    pp: Math.max(1, Number(move?.pp ?? move?.maxPp ?? 1)),
    target,
    critRatio: Math.max(1, Number(move?.critRatio ?? 1)),
    status: targetsSelf ? "" : directStatus,
    selfStatus: targetsSelf ? directStatus : "",
    volatileStatus: cleanId(move?.volatileStatus),
    boosts: targetsSelf ? {} : directBoosts,
    selfBoosts: {
      ...(targetsSelf ? directBoosts : {}),
      ...nestedSelfBoosts,
    },
    heal: normalizeFraction(move?.heal),
    drain: normalizeFraction(move?.drain),
    recoil: normalizeFraction(move?.recoil),
    weather: cleanId(move?.weather),
    terrain: cleanId(move?.terrain),
    pseudoWeather: cleanId(move?.pseudoWeather),
    sideCondition: cleanId(move?.sideCondition),
    slotCondition: cleanId(move?.slotCondition),
    multihit: Array.isArray(move?.multihit)
      ? move.multihit.map(Number).slice(0, 2)
      : Number.isFinite(Number(move?.multihit))
        ? [Number(move.multihit), Number(move.multihit)]
        : null,
    multiaccuracy: Boolean(move?.multiaccuracy),
    willCrit: Boolean(move?.willCrit),
    selfSwitch: Boolean(move?.selfSwitch),
    forceSwitch: Boolean(move?.forceSwitch),
    fixedDamage: move?.fixedDamage ?? move?.damage ?? null,
    dynamicDamage: Boolean(move?.dynamicDamage ?? move?.damageCallback),
    dynamicPower: Boolean(move?.dynamicPower ?? move?.basePowerCallback),
    secondaries: [
      ...(Array.isArray(move?.secondaries) ? move.secondaries : []),
      ...(move?.secondary ? [move.secondary] : []),
    ]
      .map(normalizeSecondary)
      .filter(Boolean),
  };
}

function normalizePokemon(pokemon, path) {
  const stats = pokemon?.stats ?? {};
  const gimmicks =
    pokemon?.gimmicks && typeof pokemon.gimmicks === "object"
      ? pokemon.gimmicks
      : {};
  const moves = Array.isArray(pokemon?.moves)
    ? pokemon.moves.map((move, index) =>
        normalizeMove(move, `${path}.moves[${index}]`),
      )
    : [];
  if (moves.length === 0) throw new Error(`${path} requires at least one move`);
  const maximumHp = assertFinitePositive(stats.hp, `${path}.stats.hp`);
  return {
    id: String(pokemon?.id ?? pokemon?.name ?? "").trim(),
    name: String(pokemon?.name ?? pokemon?.id ?? "").trim(),
    level: Math.max(1, Math.min(100, Number(pokemon?.level ?? 50))),
    types: Array.isArray(pokemon?.types)
      ? pokemon.types.map(String).slice(0, 2)
      : ["Normal"],
    originalTypes: Array.isArray(pokemon?.types)
      ? pokemon.types.map(String).slice(0, 2)
      : ["Normal"],
    ability: cleanId(pokemon?.ability),
    item: cleanId(pokemon?.item),
    gimmicks: {
      megaStone:
        gimmicks.megaStone && typeof gimmicks.megaStone === "object"
          ? {
              item: cleanId(gimmicks.megaStone.item ?? pokemon?.item),
              evolves: cleanId(gimmicks.megaStone.evolves),
              form: cleanDisplayName(gimmicks.megaStone.form),
              types: Array.isArray(gimmicks.megaStone.types)
                ? gimmicks.megaStone.types.map(String).filter(Boolean).slice(0, 2)
                : [],
              ability: cleanId(gimmicks.megaStone.ability),
              stats: normalizeOptionalStats(gimmicks.megaStone.stats),
            }
          : null,
      zCrystal:
        gimmicks.zCrystal && typeof gimmicks.zCrystal === "object"
          ? {
              item: cleanId(gimmicks.zCrystal.item ?? pokemon?.item),
              move: cleanId(gimmicks.zCrystal.move),
              moveType: cleanId(gimmicks.zCrystal.moveType),
              moveFrom: cleanId(gimmicks.zCrystal.moveFrom),
            }
          : null,
      canDynamax: true,
      forceDynamax:
        gimmicks.forceDynamax === true ||
        gimmicks.dynamax === true ||
        gimmicks.gmax === true,
      gigantamax: gimmicks.gigantamax === true || gimmicks.gmax === true,
      teraType: String(
        gimmicks.teraType ??
          gimmicks.tera ??
          pokemon?.types?.[0] ??
          "Normal",
      ).trim(),
    },
    weightKg: Math.max(0.1, Number(pokemon?.weightKg ?? 100)),
    friendship: Math.max(
      0,
      Math.min(255, Number(pokemon?.friendship ?? 255)),
    ),
    stats: {
      hp: maximumHp,
      attack: assertFinitePositive(stats.attack, `${path}.stats.attack`),
      defence: assertFinitePositive(stats.defence, `${path}.stats.defence`),
      specialAttack: assertFinitePositive(
        stats.specialAttack,
        `${path}.stats.specialAttack`,
      ),
      specialDefence: assertFinitePositive(
        stats.specialDefence,
        `${path}.stats.specialDefence`,
      ),
      speed: assertFinitePositive(stats.speed, `${path}.stats.speed`),
    },
    hp: maximumHp,
    fainted: false,
    status: "",
    statusTurns: 0,
    toxicCounter: 0,
    ateBerry: Boolean(pokemon?.ateBerry),
    lastItem: cleanId(pokemon?.lastItem),
    usedItem: cleanId(pokemon?.usedItem),
    consumedItem: cleanId(pokemon?.consumedItem),
    timesHit: 0,
    turnState: {
      acted: false,
      damageTaken: 0,
      lastDamage: null,
    },
    activeTurns: 0,
    lastMoveSucceeded: null,
    consecutiveMove: {
      id: "",
      count: 0,
    },
    lockedMove: null,
    choiceLock: null,
    chargingMove: null,
    volatiles: {},
    boosts: Object.fromEntries(BOOST_STATS.map((stat) => [stat, 0])),
    teraType: null,
    configuredTeraType: String(
      gimmicks.teraType ??
        gimmicks.tera ??
        pokemon?.types?.[0] ??
        "Normal",
    ).trim(),
    terastallized: false,
    dynamaxTurns: 0,
    dynamaxMode: null,
    baseMaximumHp: maximumHp,
    moves,
  };
}

function normalizeSide(side, index) {
  const team = Array.isArray(side?.team)
    ? side.team.map((pokemon, pokemonIndex) =>
        normalizePokemon(pokemon, `sides[${index}].team[${pokemonIndex}]`),
      )
    : [];
  if (team.length === 0) throw new Error(`sides[${index}] requires a team`);
  return {
    name: String(side?.name ?? `Side ${index + 1}`),
    active: 0,
    usedGimmicks: {
      mega: false,
      zmove: false,
      dynamax: false,
      terastallize: false,
    },
    gimmickResources: Object.fromEntries(
      GIMMICK_KINDS.map((gimmick) => [gimmick, "available"]),
    ),
    conditions: {},
    team,
  };
}

function createRng(seed, restoredState = false) {
  let state = restoredState
    ? Number(seed) >>> 0
    : (Number(seed) ^ 0x9e3779b9) >>> 0;
  return {
    next() {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      state >>>= 0;
      return state / 0x1_0000_0000;
    },
    snapshot() {
      return state;
    },
  };
}

export function typeMultiplier(attackType, defenderTypes) {
  return defenderTypes.reduce(
    (result, defenceType) =>
      result * (TYPE_CHART[attackType]?.[defenceType] ?? 1),
    1,
  );
}

function moveEffectiveness(move, defenderTypes) {
  let effectiveness = typeMultiplier(move.type, defenderTypes);
  if (cleanId(move.id) === "freezedry" && defenderTypes.includes("Water")) {
    effectiveness *= 4;
  }
  if (cleanId(move.id) === "flyingpress") {
    effectiveness *= typeMultiplier("Flying", defenderTypes);
  }
  return effectiveness;
}

function stageMultiplier(stage) {
  const bounded = Math.max(-6, Math.min(6, Number(stage ?? 0)));
  return bounded >= 0 ? (2 + bounded) / 2 : 2 / (2 - bounded);
}

function activeAbility(pokemon) {
  return pokemon.volatiles?.gastroacid ? "" : pokemon.ability;
}

function doublesPhysicalAttack(ability) {
  return ["hugepower", "purepower"].includes(cleanId(ability));
}

function statusBlockedByAbility(pokemon, status) {
  const ability = activeAbility(pokemon);
  return Boolean(STATUS_IMMUNITY_ABILITIES[status]?.has(ability));
}

function abilityDamageModifier(defender, move) {
  const ability = activeAbility(defender);
  if (
    (ability === "multiscale" || ability === "shadowshield") &&
    defender.hp >= defender.stats.hp
  ) {
    return 0.5;
  }
  if (ability === "thickfat" && (move.type === "Fire" || move.type === "Ice")) {
    return 0.5;
  }
  return 1;
}

function validateSupportedAbilities(sides) {
  const unsupported = [];
  for (const [sideIndex, side] of sides.entries()) {
    for (const [pokemonIndex, pokemon] of side.team.entries()) {
      const ability = cleanId(pokemon.ability);
      if (ability && !SUPPORTED_ABILITIES.has(ability)) {
        unsupported.push(
          `sides[${sideIndex}].team[${pokemonIndex}] ${pokemon.name}: ${ability}`,
        );
      }
      const megaAbility = cleanId(pokemon.gimmicks?.megaStone?.ability);
      if (megaAbility && !SUPPORTED_ABILITIES.has(megaAbility)) {
        unsupported.push(
          `sides[${sideIndex}].team[${pokemonIndex}] ${pokemon.name} mega ability: ${megaAbility}`,
        );
      }
    }
  }
  if (unsupported.length > 0) {
    throw new Error(
      `Unsupported ability in cobbleverse-simple strict validation: ${unsupported.join("; ")}`,
    );
  }
}

function effectiveStat(pokemon, stat, options = {}) {
  let stage = pokemon.boosts?.[stat] ?? 0;
  if (options.ignoreNegative && stage < 0) stage = 0;
  if (options.ignorePositive && stage > 0) stage = 0;
  let value = pokemon.stats[stat] * stageMultiplier(stage);
  if (stat === "attack") {
    if (pokemon.status === "brn" && activeAbility(pokemon) !== "guts") value *= 0.5;
    if (doublesPhysicalAttack(activeAbility(pokemon))) value *= 2;
    if (pokemon.item === "choiceband") value *= 1.5;
  }
  if (stat === "specialAttack" && pokemon.item === "choicespecs") value *= 1.5;
  if (stat === "specialDefence" && pokemon.item === "assaultvest") value *= 1.5;
  if (stat === "speed") {
    if (pokemon.status === "par") value *= 0.5;
    if (pokemon.item === "choicescarf") value *= 1.5;
  }
  return Math.max(1, value);
}

function hasSideCondition(state, sideIndex, condition) {
  return Number(state.sides[sideIndex]?.conditions?.[condition]?.turns ?? 0) > 0;
}

function removeSideCondition(state, sideIndex, condition, source, pokemon = null) {
  if (!state.sides[sideIndex]?.conditions?.[condition]) return false;
  delete state.sides[sideIndex].conditions[condition];
  state.events.push({
    turn: state.turn,
    type: "side_condition_end",
    side: sideIndex,
    pokemon: pokemon?.name,
    effect: condition,
    source,
    reason: "removed",
  });
  return true;
}

function removeHazardsAndTerrain(state, sideIndex, source, pokemon) {
  let removed = false;
  for (const condition of ["stealthrock", "spikes", "toxicspikes", "stickyweb"]) {
    removed =
      removeSideCondition(state, sideIndex, condition, source, pokemon) ||
      removed;
  }
  return removed;
}

function removeAllHazards(state, source, pokemon) {
  return state.sides.reduce(
    (removed, _, sideIndex) =>
      removeHazardsAndTerrain(state, sideIndex, source, pokemon) || removed,
    false,
  );
}

function isGrounded(pokemon) {
  return (
    !pokemon.types.includes("Flying") &&
    activeAbility(pokemon) !== "levitate" &&
    pokemon.item !== "airballoon"
  );
}

function effectiveSpeed(pokemon, state = null, sideIndex = null) {
  let speed = effectiveStat(pokemon, "speed");
  if (
    state &&
    Number.isInteger(sideIndex) &&
    hasSideCondition(state, sideIndex, "tailwind")
  ) {
    speed *= 2;
  }
  return Math.floor(speed);
}

function damageBase(attacker, defender, move, options = {}) {
  const physical = move.category === "Physical";
  const attack = effectiveStat(
    attacker,
    physical ? "attack" : "specialAttack",
    { ignoreNegative: options.critical },
  );
  let defence = effectiveStat(
    defender,
    physical ? "defence" : "specialDefence",
    { ignorePositive: options.critical },
  );
  if (
    !physical &&
    cleanId(options.state?.field?.weather?.id) === "sandstorm" &&
    defender.types.includes("Rock")
  ) {
    defence *= 1.5;
  }
  return Math.floor(
    (((Math.floor((2 * attacker.level) / 5) + 2) *
      move.power *
      attack) /
      defence) /
      50 +
      2,
  );
}

function fieldDamageModifier(
  state,
  attackerSide,
  attacker,
  defenderSide,
  defender,
  move,
  critical = false,
) {
  let modifier = abilityDamageModifier(defender, move);
  if (!state) return modifier;
  const weather = cleanId(state.field?.weather?.id);
  if (weather === "sunnyday" || weather === "desolateland") {
    if (move.type === "Fire") modifier *= 1.5;
    if (move.type === "Water") modifier *= 0.5;
  } else if (weather === "raindance" || weather === "primordialsea") {
    if (move.type === "Water") modifier *= 1.5;
    if (move.type === "Fire") modifier *= 0.5;
  }

  const terrain = cleanId(state.field?.terrain?.id);
  if (isGrounded(attacker)) {
    if (terrain === "electricterrain" && move.type === "Electric") {
      modifier *= 1.3;
    } else if (terrain === "grassyterrain" && move.type === "Grass") {
      modifier *= 1.3;
    } else if (terrain === "psychicterrain" && move.type === "Psychic") {
      modifier *= 1.3;
    }
  }
  if (
    terrain === "mistyterrain" &&
    isGrounded(defender) &&
    move.type === "Dragon"
  ) {
    modifier *= 0.5;
  }
  if (attacker.volatiles?.helpinghand && move.power > 0) {
    modifier *= 1.5;
  }
  if (defender.volatiles?.tarshot && move.type === "Fire") {
    modifier *= 2;
  }
  if (!critical && Number.isInteger(defenderSide)) {
    if (hasSideCondition(state, defenderSide, "auroraveil")) {
      modifier *= 0.5;
    } else if (
      move.category === "Physical" &&
      hasSideCondition(state, defenderSide, "reflect")
    ) {
      modifier *= 0.5;
    } else if (
      move.category === "Special" &&
      hasSideCondition(state, defenderSide, "lightscreen")
    ) {
      modifier *= 0.5;
    }
  }
  return modifier;
}

export function calculateDamageRange(attacker, defender, move, context = {}) {
  if (
    (move.category === "Status" || move.power <= 0) &&
    fixedDamageAmount(move, attacker, defender) === null
  ) {
    return { minimum: 0, maximum: 0, stab: 1, effectiveness: 1 };
  }
  const sameType = attacker.types.includes(move.type);
  const originalSameType = attacker.originalTypes?.includes(move.type);
  const stab = sameType
    ? attacker.terastallized && originalSameType
      ? 2
      : activeAbility(attacker) === "adaptability"
        ? 2
        : 1.5
    : 1;
  const effectiveness = moveEffectiveness(move, defender.types);
  const base = damageBase(attacker, defender, move, context);
  const itemModifier = attacker.item === "lifeorb" ? 1.3 : 1;
  const fieldModifier = fieldDamageModifier(
    context.state,
    context.attackerSide,
    attacker,
    context.defenderSide,
    defender,
    move,
    context.critical,
  );
  return {
    minimum:
      effectiveness === 0
        ? 0
        : Math.max(
            1,
            Math.floor(
              base *
                stab *
                effectiveness *
                itemModifier *
                fieldModifier *
                0.85,
            ),
          ),
    maximum:
      effectiveness === 0
        ? 0
        : Math.max(
            1,
            Math.floor(
              base * stab * effectiveness * itemModifier * fieldModifier,
            ),
          ),
    stab,
    effectiveness,
    itemModifier,
    fieldModifier,
  };
}

export function createSimpleBattle(setup) {
  if (!Array.isArray(setup?.sides) || setup.sides.length !== 2) {
    throw new Error("A simple battle requires exactly two sides");
  }
  const sides = setup.sides.map(normalizeSide);
  if (setup.strictAbilityValidation === true) {
    validateSupportedAbilities(sides);
  }
  const state = {
    engine: { id: ENGINE_ID, version: ENGINE_VERSION },
    seed: Number(setup.seed ?? 0) >>> 0,
    rngState: null,
    turn: 0,
    status: "running",
    winner: null,
    gimmickProfile: String(setup.gimmickProfile ?? "cobbleverse_all"),
    field: {
      weather: null,
      terrain: null,
      pseudoWeather: {},
    },
    manualFaintSwitchSides: Array.isArray(setup.manualFaintSwitchSides)
      ? setup.manualFaintSwitchSides.map(Number).filter(Number.isInteger)
      : [],
    strictMoveEffectValidation: setup.strictMoveEffectValidation === true,
    sides,
    events: sides.map((side, sideIndex) => ({
      turn: 0,
      type: "switch",
      side: sideIndex,
      pokemon: side.team[0].name,
      slot: 1,
      remainingHp: side.team[0].hp,
      maximumHp: side.team[0].stats.hp,
      status: side.team[0].status,
    })),
    futureAttacks: [],
    lastSuccessfulMove: null,
    aiTrace: [],
    warnings: [],
  };
  for (let sideIndex = 0; sideIndex < state.sides.length; sideIndex += 1) {
    applyEntryAbilities(state, sideIndex, activePokemon(state, sideIndex));
  }
  return state;
}

function activePokemon(state, sideIndex) {
  const side = state.sides[sideIndex];
  return side.team[side.active];
}

function applyEntryAbilities(state, sideIndex, pokemon) {
  if (!pokemon || pokemon.fainted) return;
  const ability = activeAbility(pokemon);
  if (ability !== "intimidate") return;
  const targetSide = sideIndex === 0 ? 1 : 0;
  const target = activePokemon(state, targetSide);
  if (!target || target.fainted) return;
  state.events.push({
    turn: state.turn,
    type: "ability_activate",
    side: sideIndex,
    pokemon: pokemon.name,
    ability,
    targetSide,
    target: target.name,
  });
  applyBoosts(state, targetSide, target, { attack: -1 }, ability);
}

function usableMove(pokemon, requestedSlot) {
  const slot = Number(requestedSlot);
  const requested = pokemon.moves[slot - 1];
  if (requested && requested.pp > 0) return { move: requested, slot };
  const fallbackIndex = pokemon.moves.findIndex((move) => move.pp > 0);
  if (fallbackIndex < 0) return null;
  return { move: pokemon.moves[fallbackIndex], slot: fallbackIndex + 1 };
}

function isMoveDisabledByVolatile(pokemon, move) {
  const moveId = cleanId(move?.id);
  if (!moveId) return false;
  const disabledMove = cleanId(pokemon.volatiles?.disable?.moveId);
  return disabledMove && disabledMove === moveId;
}

function isMoveImprisoned(state, side, move) {
  const opponent = activePokemon(state, side === 0 ? 1 : 0);
  if (!opponent?.volatiles?.imprison) return false;
  const moveId = cleanId(move?.id);
  return opponent.moves.some((candidate) => cleanId(candidate.id) === moveId);
}

function usableMoveRespectingVolatiles(pokemon, requestedSlot) {
  const selection = usableMove(pokemon, requestedSlot);
  if (selection && !isMoveDisabledByVolatile(pokemon, selection.move)) {
    return selection;
  }
  const fallbackIndex = pokemon.moves.findIndex(
    (move) => move.pp > 0 && !isMoveDisabledByVolatile(pokemon, move),
  );
  if (fallbackIndex < 0) return selection;
  return { move: pokemon.moves[fallbackIndex], slot: fallbackIndex + 1 };
}

function lockedMoveSelection(pokemon) {
  const encoreMove = cleanId(pokemon.volatiles?.encore?.moveId);
  if (encoreMove) {
    const encoreIndex = pokemon.moves.findIndex(
      (move) => cleanId(move.id) === encoreMove && move.pp > 0,
    );
    if (encoreIndex >= 0) {
      return {
        move: pokemon.moves[encoreIndex],
        slot: encoreIndex + 1,
        lockSource: "encore",
        preventsSwitch: false,
        noPpCost: false,
      };
    }
    delete pokemon.volatiles.encore;
  }
  if (pokemon.lockedMove?.id) {
    const index = pokemon.moves.findIndex(
      (move) => cleanId(move.id) === cleanId(pokemon.lockedMove.id) && move.pp > 0,
    );
    if (index < 0) {
      pokemon.lockedMove = null;
      return null;
    }
    return {
      move: pokemon.moves[index],
      slot: index + 1,
      lockSource: pokemon.lockedMove.kind ?? "move",
      preventsSwitch: true,
      noPpCost: true,
    };
  }
  if (CHOICE_LOCK_ITEMS.has(cleanId(pokemon.item)) && pokemon.choiceLock?.id) {
    const index = pokemon.moves.findIndex(
      (move) => cleanId(move.id) === cleanId(pokemon.choiceLock.id) && move.pp > 0,
    );
    if (index < 0) return null;
    return {
      move: pokemon.moves[index],
      slot: index + 1,
      lockSource: "choice",
      preventsSwitch: false,
      noPpCost: false,
    };
  }
  if (!CHOICE_LOCK_ITEMS.has(cleanId(pokemon.item))) {
    pokemon.choiceLock = null;
  }
  return null;
}

function chargingMoveSelection(pokemon) {
  if (!pokemon.chargingMove?.id) return null;
  const index = pokemon.moves.findIndex(
    (move) => cleanId(move.id) === cleanId(pokemon.chargingMove.id),
  );
  if (index < 0) {
    pokemon.chargingMove = null;
    return null;
  }
  return { move: pokemon.moves[index], slot: index + 1 };
}

function buildActions(state, commands, rng) {
  return [0, 1].map((side) => {
      const pokemon = activePokemon(state, side);
      const chargingSelection = chargingMoveSelection(pokemon);
      if (chargingSelection) {
        return {
          kind: "move",
          side,
          pokemon,
          selected: chargingSelection,
          locked: true,
          lockSource: "charging",
          noPpCost: true,
          chargingRelease: true,
          gimmick: "",
          teraType: "",
          priority: chargingSelection.move.priority ?? 0,
          speed: effectiveSpeed(pokemon, state, side),
          tie: rng.next(),
        };
      }
      const lockedSelection = lockedMoveSelection(pokemon);
      const switchSlot = Number(commands[side]?.switch);
      if (Number.isInteger(switchSlot)) {
        if (lockedSelection?.preventsSwitch) {
          throw new Error(
            `Side ${side + 1} cannot switch while locked into ${lockedSelection.move.name}`,
          );
        }
        const target = state.sides[side].team[switchSlot - 1];
        if (!target || target.fainted || switchSlot - 1 === state.sides[side].active) {
          throw new Error(`Side ${side + 1} cannot switch to slot ${switchSlot}`);
        }
        if (isTrappedByVolatile(pokemon)) {
          throw new Error(`Side ${side + 1} cannot switch while trapped`);
        }
        return {
          kind: "switch",
          side,
          pokemon,
          switchSlot,
          selected: null,
          priority: 10_000,
          speed: effectiveSpeed(pokemon, state, side),
          tie: rng.next(),
        };
      }
      const selected =
        lockedSelection ??
        usableMoveRespectingVolatiles(pokemon, commands[side]?.move);
      return {
        kind: "move",
        side,
        pokemon,
        selected,
        locked: Boolean(lockedSelection),
        lockSource: lockedSelection?.lockSource ?? "",
        noPpCost: Boolean(lockedSelection?.noPpCost),
        gimmick: lockedSelection
          ? ""
          : String(commands[side]?.gimmick ?? ""),
        teraType: String(commands[side]?.teraType ?? ""),
        priority: selected?.move.priority ?? 0,
        speed: effectiveSpeed(pokemon, state, side),
        tie: rng.next(),
      };
    });
}

function sortActions(state, actions) {
  const trickRoom =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  return actions.sort((left, right) => {
    const priority = right.priority - left.priority;
    if (priority !== 0) return priority;
    const speed = trickRoom
      ? left.speed - right.speed
      : right.speed - left.speed;
    return speed || left.tie - right.tie;
  });
}

function markPursuitIntercepts(actions) {
  for (const action of actions) {
    if (
      action.kind !== "move" ||
      cleanId(action.selected?.move?.id) !== "pursuit"
    ) {
      continue;
    }
    const targetAction = actions.find(
      (candidate) =>
        candidate.side !== action.side && candidate.kind === "switch",
    );
    if (!targetAction) continue;
    action.pursuitTargetSwitch = true;
    action.priority = 10_001;
  }
}

function effectiveMovePriority(state, action) {
  if (action.kind === "switch") return 10_000;
  const move = action.selected?.move;
  if (
    cleanId(move?.id) === "grassyglide" &&
    cleanId(state.field?.terrain?.id) === "grassyterrain" &&
    isGrounded(activePokemon(state, action.side))
  ) {
    return 1;
  }
  if (cleanId(move?.id) === "thunderclap") return 1;
  return move?.priority ?? 0;
}

function rejectGimmick(state, action, reason) {
  const gimmick = action.gimmick;
  state.events.push({
    turn: state.turn,
    type: "gimmick_rejected",
    side: action.side,
    pokemon: action.pokemon.name,
    gimmick,
    reason,
    item: action.pokemon.item,
    teraType: action.pokemon.configuredTeraType || null,
  });
  action.gimmick = "";
  action.gimmickReserved = false;
}

function validateGimmickRequest(action) {
  const pokemon = action.pokemon;
  const gimmicks = pokemon.gimmicks ?? {};

  if (action.gimmick === "mega") {
    if (pokemon.dynamaxTurns > 0) return "mega_blocked_by_dynamax";
    const stone = gimmicks.megaStone;
    if (!stone || !stone.item || stone.item !== pokemon.item) {
      return "mega_stone_required";
    }
    const speciesIds = new Set([cleanId(pokemon.id), cleanId(pokemon.name)]);
    if (stone.evolves && !speciesIds.has(stone.evolves)) {
      return "mega_stone_incompatible";
    }
    action.megaForm = cleanDisplayName(stone.form);
  }

  if (action.gimmick === "zmove") {
    const crystal = gimmicks.zCrystal;
    if (!crystal || !crystal.item || crystal.item !== pokemon.item) {
      return "z_crystal_required";
    }
    const move = action.selected?.move;
    if (crystal.moveFrom && crystal.moveFrom !== cleanId(move?.id)) {
      return "z_crystal_incompatible";
    }
    if (crystal.moveType && crystal.moveType !== cleanId(move?.type)) {
      return "z_crystal_incompatible";
    }
    action.zMove = crystal.move;
  }

  if (action.gimmick === "dynamax") {
    if (pokemon.megaEvolved) return "dynamax_blocked_by_mega";
    if (gimmicks.canDynamax !== true) return "dynamax_unavailable";
    action.dynamaxMode =
      gimmicks.gigantamax === true ? "gigantamax" : "dynamax";
  }

  if (action.gimmick === "terastallize") {
    if (pokemon.dynamaxTurns > 0) return "tera_blocked_by_dynamax";
    const configuredType = String(
      pokemon.configuredTeraType ?? gimmicks.teraType ?? "",
    ).trim();
    if (!configuredType) return "tera_type_required";
    if (
      action.teraType &&
      cleanId(action.teraType) !== cleanId(configuredType)
    ) {
      return "tera_type_mismatch";
    }
    action.teraType = configuredType;
  }

  return "";
}

function canMegaEvolvePokemon(pokemon) {
  const stone = pokemon?.gimmicks?.megaStone;
  if (!stone || !stone.item || stone.item !== pokemon?.item) return false;
  const speciesIds = new Set([cleanId(pokemon.id), cleanId(pokemon.name)]);
  return !stone.evolves || speciesIds.has(stone.evolves);
}

function reserveGimmick(state, action) {
  const gimmick = action.gimmick;
  if (!gimmick) return;
  if (!GIMMICK_KINDS.includes(gimmick)) {
    rejectGimmick(state, action, "unsupported_gimmick");
    return;
  }
  if (!action.selected || action.locked) {
    rejectGimmick(
      state,
      action,
      action.locked ? "move_locked" : "no_usable_move",
    );
    return;
  }
  const side = state.sides[action.side];
  if (side.gimmickResources[gimmick] !== "available") {
    rejectGimmick(state, action, "resource_unavailable");
    return;
  }
  const validationError = validateGimmickRequest(action);
  if (validationError) {
    rejectGimmick(state, action, validationError);
    return;
  }
  side.gimmickResources[gimmick] = "reserved";
  action.gimmickReserved = true;
  state.events.push({
    turn: state.turn,
    type: "gimmick_reserved",
    side: action.side,
    pokemon: action.pokemon.name,
    gimmick,
    item: action.pokemon.item,
    teraType: action.teraType || null,
    dynamaxMode: action.dynamaxMode || null,
  });
}

function consumeGimmick(state, action) {
  if (!action.gimmickReserved || !action.gimmick) return;
  const side = state.sides[action.side];
  side.gimmickResources[action.gimmick] = "consumed";
  side.usedGimmicks[action.gimmick] = true;
  action.gimmickReserved = false;
  state.events.push({
    turn: state.turn,
    type: "gimmick_consumed",
    side: action.side,
    pokemon: action.pokemon.name,
    gimmick: action.gimmick,
  });
}

function releaseGimmick(state, action, reason) {
  if (!action.gimmickReserved || !action.gimmick) return;
  const side = state.sides[action.side];
  if (side.gimmickResources[action.gimmick] === "reserved") {
    side.gimmickResources[action.gimmick] = "available";
  }
  action.gimmickReserved = false;
  state.events.push({
    turn: state.turn,
    type: "gimmick_released",
    side: action.side,
    pokemon: action.pokemon.name,
    gimmick: action.gimmick,
    reason,
  });
}

function emitGimmickActivation(state, action, pokemon) {
  const event = {
    turn: state.turn,
    side: action.side,
    pokemon: pokemon.name,
    gimmick: action.gimmick,
    teraType: pokemon.teraType,
    megaForm: action.megaForm || null,
    dynamaxMode: pokemon.dynamaxMode,
  };
  state.events.push({ ...event, type: "gimmick_activated" });
  state.events.push({ ...event, type: "gimmick" });
}

function activatePreMoveGimmick(state, action) {
  if (
    !action.gimmickReserved ||
    !PRE_MOVE_GIMMICKS.has(action.gimmick)
  ) {
    return;
  }
  const pokemon = activePokemon(state, action.side);
  if (pokemon.fainted || state.status !== "running") {
    releaseGimmick(state, action, "pokemon_unavailable");
    return;
  }

  if (action.gimmick === "mega") {
    const stone = pokemon.gimmicks?.megaStone ?? {};
    const megaForm = cleanDisplayName(action.megaForm || stone.form);
    if (megaForm) {
      pokemon.baseSpeciesName = pokemon.baseSpeciesName || pokemon.name;
      pokemon.name = megaForm;
      pokemon.id = cleanId(megaForm);
      action.megaForm = megaForm;
    }
    if (Array.isArray(stone.types) && stone.types.length) {
      pokemon.types = stone.types.map(String).filter(Boolean).slice(0, 2);
      pokemon.originalTypes = pokemon.types.slice();
    }
    if (stone.ability) {
      pokemon.ability = stone.ability;
    }
    if (stone.stats) {
      for (const [stat, value] of Object.entries(stone.stats)) {
        pokemon.stats[stat] = value;
      }
    } else {
      for (const stat of [
        "attack",
        "defence",
        "specialAttack",
        "specialDefence",
        "speed",
      ]) {
        pokemon.stats[stat] = Math.max(1, Math.floor(pokemon.stats[stat] * 1.1));
      }
    }
    pokemon.megaEvolved = true;
  } else if (action.gimmick === "dynamax") {
    pokemon.hp *= 2;
    pokemon.stats.hp *= 2;
    pokemon.dynamaxTurns = 3;
    pokemon.dynamaxMode = action.dynamaxMode;
  } else if (action.gimmick === "terastallize") {
    pokemon.teraType = pokemon.configuredTeraType;
    pokemon.types = [pokemon.teraType];
    pokemon.terastallized = true;
  }

  emitGimmickActivation(state, action, pokemon);
  consumeGimmick(state, action);
  action.gimmickActivated = true;
}

function prepareActionOrder(state, commands, rng) {
  const actions = buildActions(state, commands, rng);
  for (const action of actions) {
    if (action.kind === "move") reserveGimmick(state, action);
  }
  for (const action of actions) {
    if (action.kind === "move") activatePreMoveGimmick(state, action);
  }
  for (const action of actions) {
    action.priority = effectiveMovePriority(state, action);
    action.speed = effectiveSpeed(
      activePokemon(state, action.side),
      state,
      action.side,
    );
  }
  markPursuitIntercepts(actions);
  return sortActions(state, actions);
}

function applyEntryHazards(state, sideIndex, pokemon) {
  const conditions = state.sides[sideIndex].conditions;
  for (const wishId of ["healingwish", "lunardance"]) {
    const wish = conditions[wishId];
    if (!wish || pokemon.fainted) continue;
    pokemon.hp = pokemon.stats.hp;
    pokemon.status = "";
    pokemon.statusTurns = 0;
    pokemon.toxicCounter = 0;
    state.events.push({
      turn: state.turn,
      type: "slot_condition_end",
      side: sideIndex,
      pokemon: pokemon.name,
      effect: wishId,
      source: wish.source,
      remainingHp: pokemon.hp,
      maximumHp: pokemon.stats.hp,
    });
    delete conditions[wishId];
  }
  const hazardDamage = (amount, source) => {
    const applied = Math.min(pokemon.hp, Math.max(1, Math.floor(amount)));
    pokemon.hp -= applied;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: sideIndex,
      pokemon: pokemon.name,
      source,
      cause: "entry_hazard",
      damage: applied,
      remainingHp: pokemon.hp,
      maximumHp: pokemon.stats.hp,
      effectiveness: 1,
    });
    return markFainted(state, sideIndex, pokemon);
  };

  if (conditions.stealthrock && !pokemon.fainted) {
    const effectiveness = typeMultiplier("Rock", pokemon.types);
    if (
      effectiveness > 0 &&
      hazardDamage((pokemon.stats.hp / 8) * effectiveness, "stealthrock")
    ) {
      return;
    }
  }
  if (conditions.spikes && isGrounded(pokemon) && !pokemon.fainted) {
    const layers = conditions.spikes.layers;
    const divisor = layers === 1 ? 8 : layers === 2 ? 6 : 4;
    if (hazardDamage(pokemon.stats.hp / divisor, "spikes")) return;
  }
  if (conditions.toxicspikes && isGrounded(pokemon) && !pokemon.fainted) {
    if (pokemon.types.includes("Poison")) {
      delete conditions.toxicspikes;
      state.events.push({
        turn: state.turn,
        type: "side_condition_end",
        side: sideIndex,
        pokemon: pokemon.name,
        effect: "toxicspikes",
        reason: "absorbed",
      });
    } else {
      applyStatus(
        state,
        sideIndex,
        pokemon,
        conditions.toxicspikes.layers >= 2 ? "tox" : "psn",
        null,
        "toxicspikes",
      );
    }
  }
  if (conditions.stickyweb && isGrounded(pokemon) && !pokemon.fainted) {
    applyBoosts(
      state,
      sideIndex,
      pokemon,
      { speed: -1 },
      "stickyweb",
    );
  }
}

function switchActivePokemon(state, sideIndex, switchSlot, options = {}) {
  const side = state.sides[sideIndex];
  const outgoing = side.team[side.active];
  endDynamax(state, sideIndex, outgoing, "switch");
  outgoing.boosts = Object.fromEntries(
    BOOST_STATS.map((stat) => [stat, 0]),
  );
  outgoing.consecutiveMove = { id: "", count: 0 };
  outgoing.lockedMove = null;
  outgoing.choiceLock = null;
  outgoing.chargingMove = null;
  outgoing.volatiles = {};
  if (outgoing.status === "tox") {
    outgoing.toxicCounter = 1;
  }
  side.active = switchSlot - 1;
  side.team[side.active].activeTurns = 0;
  state.events.push({
    turn: state.turn,
    type: "switch",
    side: sideIndex,
    pokemon: side.team[side.active].name,
    slot: switchSlot,
    remainingHp: side.team[side.active].hp,
    maximumHp: side.team[side.active].stats.hp,
    status: side.team[side.active].status,
    automatic: Boolean(options.automatic),
    forced: Boolean(options.forced) || undefined,
    source: options.source,
    selection: options.selection,
  });
  applyEntryHazards(state, sideIndex, side.team[side.active]);
  applyEntryAbilities(state, sideIndex, side.team[side.active]);
}

function executeSwitch(state, action) {
  switchActivePokemon(state, action.side, action.switchSlot);
}

function executeSelfSwitch(state, sideIndex, source) {
  if (state.sides[sideIndex].team[state.sides[sideIndex].active].fainted) {
    return false;
  }
  const next = bestFaintReplacement(state, sideIndex);
  if (!Number.isInteger(next) || next < 0) return false;
  switchActivePokemon(state, sideIndex, next + 1, {
    automatic: true,
    forced: true,
    source,
    selection: "self_switch",
  });
  return true;
}

function executeForceSwitch(state, sideIndex, source) {
  if (state.sides[sideIndex].team[state.sides[sideIndex].active].fainted) {
    return false;
  }
  const next = bestFaintReplacement(state, sideIndex);
  if (!Number.isInteger(next) || next < 0) return false;
  switchActivePokemon(state, sideIndex, next + 1, {
    automatic: true,
    forced: true,
    source,
    selection: "force_switch",
  });
  return true;
}

function itemCanBeStolen(item) {
  return Boolean(cleanId(item));
}

function isConsumableBattleItem(item) {
  const id = cleanId(item);
  return id.endsWith("berry") || id.endsWith("gem");
}

function removeTargetItem(state, sideIndex, pokemon, source) {
  if (!pokemon.item) return "";
  const removedItem = pokemon.item;
  pokemon.item = "";
  pokemon.lastItem = removedItem;
  state.events.push({
    turn: state.turn,
    type: "item_removed",
    side: sideIndex,
    pokemon: pokemon.name,
    item: removedItem,
    source,
  });
  return removedItem;
}

function stealTargetItem(state, attackerSide, attacker, defenderSide, defender, source) {
  if (attacker.item || !itemCanBeStolen(defender.item)) return false;
  const stolenItem = removeTargetItem(state, defenderSide, defender, source);
  if (!stolenItem) return false;
  attacker.item = stolenItem;
  state.events.push({
    turn: state.turn,
    type: "item_stolen",
    side: attackerSide,
    pokemon: attacker.name,
    item: stolenItem,
    source,
  });
  return true;
}

function swapHeldItems(state, attackerSide, attacker, defenderSide, defender, source) {
  if (!itemCanBeStolen(attacker.item) && !itemCanBeStolen(defender.item)) {
    return false;
  }
  const attackerItem = attacker.item || "";
  const defenderItem = defender.item || "";
  attacker.item = defenderItem;
  defender.item = attackerItem;
  state.events.push({
    turn: state.turn,
    type: "items_swapped",
    source,
    leftSide: attackerSide,
    leftPokemon: attacker.name,
    leftItem: attacker.item,
    rightSide: defenderSide,
    rightPokemon: defender.name,
    rightItem: defender.item,
  });
  return true;
}

function recycleHeldItem(state, side, pokemon, source) {
  const item = cleanId(pokemon.lastItem || pokemon.usedItem || pokemon.consumedItem);
  if (pokemon.item || !item) return false;
  pokemon.item = item;
  pokemon.lastItem = "";
  pokemon.usedItem = "";
  pokemon.consumedItem = "";
  state.events.push({
    turn: state.turn,
    type: "item_restored",
    side,
    pokemon: pokemon.name,
    item,
    source,
  });
  return true;
}

function transformGimmickMove(state, action, move) {
  const pokemon = activePokemon(state, action.side);
  if (action.gimmick === "zmove" && action.gimmickReserved) {
    emitGimmickActivation(state, action, pokemon);
    consumeGimmick(state, action);
    action.gimmickActivated = true;
  }
  if (action.gimmick === "zmove" && action.gimmickActivated) {
    if (move.category === "Status") return move;
    return {
      ...move,
      name: action.zMove || `Z-${move.name}`,
      power: Math.max(100, move.power * 1.5),
    };
  }
  if (pokemon.dynamaxTurns > 0) {
    const maxMove = resolveNativeMaxMove(pokemon, move);
    const isStatus = move.category === "Status";
    return {
      ...move,
      id: maxMove.id,
      name: maxMove.name,
      accuracy: true,
      priority: 0,
      power:
        isStatus
          ? 0
          : Math.max(90, Math.min(150, move.power * 1.35)),
      target: isStatus ? "self" : move.target,
      critRatio: 1,
      status: "",
      selfStatus: "",
      volatileStatus: maxMove.volatileStatus ?? "",
      boosts: maxMove.boosts ?? {},
      selfBoosts: maxMove.selfBoosts ?? {},
      heal: null,
      drain: null,
      recoil: null,
      weather: maxMove.weather ?? "",
      terrain: maxMove.terrain ?? "",
      pseudoWeather: maxMove.pseudoWeather ?? "",
      sideCondition: maxMove.sideCondition ?? "",
      slotCondition: "",
      multihit: null,
      multiaccuracy: false,
      willCrit: false,
      selfSwitch: false,
      forceSwitch: false,
      fixedDamage: null,
      dynamicDamage: false,
      dynamicPower: false,
      secondaries: [],
      sourceMoveId: move.id,
      isMaxMove: true,
    };
  }
  if (move.category === "Status") return move;
  return move;
}

function solarChargeWeather(state) {
  return cleanId(state.field?.weather?.id);
}

function shouldChargeMove(state, move, action) {
  if (action.chargingRelease) return false;
  const moveId = cleanId(move.id);
  if (!CHARGING_MOVES.has(moveId)) return false;
  const weather = solarChargeWeather(state);
  if (
    ["solarbeam", "solarblade"].includes(moveId) &&
    ["sunnyday", "desolateland"].includes(weather)
  ) {
    return false;
  }
  if (moveId === "electroshot" && ["raindance", "primordialsea"].includes(weather)) {
    return false;
  }
  return true;
}

function chargeAdjustedMove(state, move) {
  const moveId = cleanId(move.id);
  const weather = solarChargeWeather(state);
  if (
    ["solarbeam", "solarblade"].includes(moveId) &&
    ["raindance", "primordialsea", "sandstorm", "hail", "snow"].includes(weather)
  ) {
    return { ...move, power: Math.max(1, Math.floor(move.power / 2)) };
  }
  return move;
}

function beginChargeMove(state, action, attacker, move, slot) {
  const moveId = cleanId(move.id);
  attacker.chargingMove = { id: moveId, slot, source: move.name };
  if (moveId === "meteorbeam" || moveId === "electroshot") {
    applyBoosts(state, action.side, attacker, { specialAttack: 1 }, move.name);
  } else if (moveId === "skullbash") {
    applyBoosts(state, action.side, attacker, { defence: 1 }, move.name);
  }
  state.events.push({
    turn: state.turn,
    type: "charge_start",
    side: action.side,
    pokemon: attacker.name,
    move: move.name,
  });
  return true;
}

function eventStat(stat) {
  return {
    attack: "atk",
    defence: "def",
    specialAttack: "spa",
    specialDefence: "spd",
    speed: "spe",
    accuracy: "accuracy",
    evasion: "evasion",
  }[stat];
}

function effectiveAccuracy(attacker, defender, move, state = null) {
  if (move.accuracy === true) return 100;
  const weather = cleanId(state?.field?.weather?.id);
  if (cleanId(move.id) === "blizzard" && ["hail", "snow"].includes(weather)) {
    return 100;
  }
  if (
    ["thunder", "hurricane"].includes(cleanId(move.id)) &&
    ["raindance", "primordialsea"].includes(weather)
  ) {
    return 100;
  }
  if (
    ["thunder", "hurricane"].includes(cleanId(move.id)) &&
    ["sunnyday", "desolateland"].includes(weather)
  ) {
    return 50;
  }
  const accuracyStage = attacker.boosts?.accuracy ?? 0;
  const evasionStage = defender.boosts?.evasion ?? 0;
  return Math.max(
    1,
    Math.min(
      100,
      move.accuracy *
        stageMultiplier(accuracyStage) /
        stageMultiplier(evasionStage),
    ),
  );
}

function weatherBallMove(state, move) {
  if (cleanId(move.id) !== "weatherball") return move;
  const weather = cleanId(state.field?.weather?.id);
  const weatherTypes = {
    sunnyday: "Fire",
    desolateland: "Fire",
    raindance: "Water",
    primordialsea: "Water",
    hail: "Ice",
    snow: "Ice",
    sandstorm: "Rock",
  };
  const type = weatherTypes[weather];
  if (!type) return move;
  return {
    ...move,
    type,
    power: Math.max(move.power, 50) * 2,
  };
}

function heldItemType(item) {
  const id = cleanId(item);
  const direct = {
    burn_drive: "Fire",
    burndrive: "Fire",
    chill_drive: "Ice",
    chilldrive: "Ice",
    douse_drive: "Water",
    dousedrive: "Water",
    shock_drive: "Electric",
    shockdrive: "Electric",
    dracomemory: "Dragon",
    dragonmemory: "Dragon",
    dreadplate: "Dark",
    earthplate: "Ground",
    fairymemory: "Fairy",
    fightingmemory: "Fighting",
    firememory: "Fire",
    fistplate: "Fighting",
    flameplate: "Fire",
    icicleplate: "Ice",
    icememory: "Ice",
    insectplate: "Bug",
    ironplate: "Steel",
    meadowplate: "Grass",
    mindplate: "Psychic",
    pixieplate: "Fairy",
    poisonmemory: "Poison",
    rockmemory: "Rock",
    skyplate: "Flying",
    splashplate: "Water",
    spookyplate: "Ghost",
    steelmemory: "Steel",
    stoneplate: "Rock",
    toxicplate: "Poison",
    watermemory: "Water",
    zapplate: "Electric",
    occaberry: "Fire",
    passhoberry: "Water",
    wacanberry: "Electric",
    rindoberry: "Grass",
    yacheberry: "Ice",
    chopleberry: "Fighting",
    kebiaberry: "Poison",
    shucaberry: "Ground",
    cobaberry: "Flying",
    payapaberry: "Psychic",
    tangaberry: "Bug",
    chartiberry: "Rock",
    kasibberry: "Ghost",
    habanberry: "Dragon",
    colburberry: "Dark",
    babiriberry: "Steel",
    chilanberry: "Normal",
    roselliberry: "Fairy",
  };
  if (direct[id]) return direct[id];
  for (const type of Object.keys(TYPE_CHART)) {
    if (id === `${cleanId(type)}memory`) return type;
  }
  return "";
}

function terrainPulseMove(state, move) {
  if (cleanId(move.id) !== "terrainpulse") return move;
  const terrain = cleanId(state.field?.terrain?.id);
  const typeByTerrain = {
    electricterrain: "Electric",
    grassyterrain: "Grass",
    mistyterrain: "Fairy",
    psychicterrain: "Psychic",
  };
  const type = typeByTerrain[terrain];
  if (!type) return move;
  return {
    ...move,
    type,
    power: Math.max(move.power, 50) * 2,
  };
}

function growthMove(state, move) {
  if (cleanId(move.id) !== "growth") return move;
  const weather = cleanId(state.field?.weather?.id);
  if (!["sunnyday", "desolateland"].includes(weather)) return move;
  return {
    ...move,
    selfBoosts: Object.fromEntries(
      Object.entries(move.selfBoosts ?? {}).map(([stat, amount]) => [
        stat,
        amount > 0 ? amount * 2 : amount,
      ]),
    ),
  };
}

function terrainModifiedMove(state, attacker, move) {
  const moveId = cleanId(move.id);
  const terrain = cleanId(state.field?.terrain?.id);
  if (
    moveId === "expandingforce" &&
    terrain === "psychicterrain" &&
    isGrounded(attacker)
  ) {
    return { ...move, power: Math.floor(move.power * 1.5) };
  }
  return move;
}

function canReceiveStatus(
  pokemon,
  status,
  state = null,
  side = null,
  sourceSide = null,
) {
  if (!status || pokemon.status || pokemon.fainted) return false;
  if (state && Number.isInteger(side)) {
    const terrain = cleanId(state.field?.terrain?.id);
    if (
      isGrounded(pokemon) &&
      (terrain === "mistyterrain" ||
        (terrain === "electricterrain" && status === "slp"))
    ) {
      return false;
    }
    if (
      hasSideCondition(state, side, "safeguard") &&
      sourceSide !== side
    ) {
      return false;
    }
  }
  if (status === "brn" && pokemon.types.includes("Fire")) return false;
  if (status === "par" && pokemon.types.includes("Electric")) return false;
  if (statusBlockedByAbility(pokemon, status)) return false;
  if (
    (status === "psn" || status === "tox") &&
    (pokemon.types.includes("Poison") || pokemon.types.includes("Steel"))
  ) {
    return false;
  }
  if (status === "frz" && pokemon.types.includes("Ice")) return false;
  return true;
}

function applyStatus(
  state,
  side,
  pokemon,
  status,
  rng,
  source,
  sourceSide = null,
) {
  if (!canReceiveStatus(pokemon, status, state, side, sourceSide)) return false;
  pokemon.status = status;
  pokemon.statusTurns =
    status === "slp" ? 1 + Math.floor(rng.next() * 3) : 0;
  pokemon.toxicCounter = status === "tox" ? 1 : 0;
  state.events.push({
    turn: state.turn,
    type: "status",
    side,
    pokemon: pokemon.name,
    status,
    source,
  });
  return true;
}

function canRest(state, side, pokemon) {
  if (pokemon.fainted) return false;
  if (pokemon.hp >= pokemon.stats.hp && !pokemon.status) return false;
  const originalStatus = pokemon.status;
  pokemon.status = "";
  const canSleep = canReceiveStatus(pokemon, "slp", state, side, side);
  pokemon.status = originalStatus;
  return canSleep;
}

function applyRest(state, side, pokemon, source) {
  if (!canRest(state, side, pokemon)) return false;
  const previousStatus = pokemon.status;
  pokemon.status = "slp";
  pokemon.statusTurns = 2;
  pokemon.toxicCounter = 0;
  if (previousStatus && previousStatus !== "slp") {
    state.events.push({
      turn: state.turn,
      type: "status_cured",
      side,
      pokemon: pokemon.name,
      status: previousStatus,
      source,
    });
  }
  state.events.push({
    turn: state.turn,
    type: "status",
    side,
    pokemon: pokemon.name,
    status: "slp",
    source,
  });
  healPokemon(state, side, pokemon, pokemon.stats.hp, source);
  return true;
}

function curePokemonStatus(state, side, pokemon, source, allowedStatuses = null) {
  if (!pokemon.status || pokemon.fainted) return false;
  if (allowedStatuses && !allowedStatuses.includes(pokemon.status)) return false;
  const previousStatus = pokemon.status;
  pokemon.status = "";
  pokemon.statusTurns = 0;
  pokemon.toxicCounter = 0;
  state.events.push({
    turn: state.turn,
    type: "status_cured",
    side,
    pokemon: pokemon.name,
    status: previousStatus,
    source,
  });
  return true;
}

function cureSideStatuses(state, side, source, allowedStatuses = null) {
  return state.sides[side].team.reduce(
    (changed, pokemon) =>
      curePokemonStatus(state, side, pokemon, source, allowedStatuses) || changed,
    false,
  );
}

function applySelfHpCost(state, side, pokemon, source, fraction = [1, 2]) {
  if (pokemon.fainted || pokemon.hp <= 0) return false;
  const damage = Math.min(
    pokemon.hp,
    Math.max(1, Math.ceil((pokemon.stats.hp * fraction[0]) / fraction[1])),
  );
  pokemon.hp -= damage;
  state.events.push({
    turn: state.turn,
    type: "damage",
    side,
    pokemon: pokemon.name,
    source,
    cause: "hp_cost",
    damage,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    effectiveness: 1,
  });
  markFainted(state, side, pokemon);
  return true;
}

function applyProtectBlockEffect(
  state,
  defenderSide,
  defender,
  attackerSide,
  attacker,
  blockedMove,
  rng,
) {
  const protectSource = cleanId(defender.volatiles?.protect?.source);
  if (blockedMove.category !== "Physical" || blockedMove.power <= 0) return false;
  if (protectSource === "kingsshield") {
    return applyBoosts(
      state,
      attackerSide,
      attacker,
      { attack: -1 },
      defender.volatiles.protect.source,
    );
  }
  if (protectSource === "burningbulwark") {
    return applyStatus(
      state,
      attackerSide,
      attacker,
      "brn",
      rng,
      defender.volatiles.protect.source,
      defenderSide,
    );
  }
  if (protectSource === "banefulbunker") {
    return applyStatus(
      state,
      attackerSide,
      attacker,
      "psn",
      rng,
      defender.volatiles.protect.source,
      defenderSide,
    );
  }
  if (protectSource === "spikyshield") {
    return (
      applyDirectDamage(
        state,
        attackerSide,
        attacker,
        Math.max(1, Math.floor(attacker.stats.hp / 8)),
        defender.volatiles.protect.source,
        "protect_contact",
      ) > 0
    );
  }
  if (protectSource === "obstruct") {
    return applyBoosts(
      state,
      attackerSide,
      attacker,
      { defence: -2 },
      defender.volatiles.protect.source,
    );
  }
  if (protectSource === "silktrap") {
    return applyBoosts(
      state,
      attackerSide,
      attacker,
      { speed: -1 },
      defender.volatiles.protect.source,
    );
  }
  return false;
}

function volatileDuration(id) {
  return {
    aquaring: null,
    attract: null,
    confusion: 4,
    charge: 2,
    disable: 4,
    electrify: 1,
    embargo: 5,
    endure: 1,
    encore: 3,
    flinch: 1,
    followme: 1,
    gastroacid: null,
    grudge: null,
    healblock: 5,
    helpinghand: 1,
    imprison: null,
    ingrain: null,
    laserfocus: 2,
    lockon: 2,
    magiccoat: 1,
    magnetrise: 5,
    mindreader: 2,
    miracleeye: null,
    minimize: null,
    nightmare: null,
    noretreat: null,
    octolock: null,
    powder: 1,
    protect: 1,
    powershift: null,
    powertrick: null,
    ragepowder: 1,
    smackdown: null,
    taunt: 3,
    tarshot: null,
    telekinesis: 3,
    torment: null,
    uproar: 3,
    yawn: 2,
    perishsong: 4,
  }[id] ?? null;
}

function applyVolatileStatus(state, side, pokemon, id, source, sourceSide = null) {
  const normalized = cleanId(id);
  if (!normalized || pokemon.fainted || pokemon.volatiles[normalized]) {
    return false;
  }
  if (normalized === "confusion" && activeAbility(pokemon) === "owntempo") {
    return false;
  }
  const turns = volatileDuration(normalized);
  pokemon.volatiles[normalized] = Number.isFinite(turns)
    ? { id: normalized, turns }
    : { id: normalized };
  pokemon.volatiles[normalized].source = source;
  pokemon.volatiles[normalized].sourceSide = sourceSide;
  if (BINDING_VOLATILES.has(normalized)) {
    pokemon.volatiles[normalized].turns = pokemon.volatiles[normalized].turns ?? 4;
  }
  if (normalized === "perishsong") {
    pokemon.volatiles[normalized].count = 3;
  }
  state.events.push({
    turn: state.turn,
    type: "volatile_start",
    side,
    pokemon: pokemon.name,
    effect: normalized,
    duration: turns,
    source,
  });
  return true;
}

function applyLeechSeed(state, sourceSide, targetSide, target, source) {
  if (target.types.includes("Grass")) return false;
  if (!applyVolatileStatus(state, targetSide, target, "leechseed", source)) {
    return false;
  }
  target.volatiles.leechseed.sourceSide = sourceSide;
  target.volatiles.leechseed.source = source;
  return true;
}

function applyYawn(state, sourceSide, targetSide, target, source) {
  if (!canReceiveStatus(target, "slp", state, targetSide, sourceSide)) {
    return false;
  }
  if (!applyVolatileStatus(state, targetSide, target, "yawn", source)) {
    return false;
  }
  target.volatiles.yawn.sourceSide = sourceSide;
  target.volatiles.yawn.source = source;
  return true;
}

function applyBellyDrum(state, side, pokemon, source) {
  if ((pokemon.boosts.attack ?? 0) >= 6) return false;
  const cost = Math.floor(pokemon.stats.hp / 2);
  if (pokemon.hp <= cost) return false;
  pokemon.hp -= cost;
  state.events.push({
    turn: state.turn,
    type: "damage",
    side,
    pokemon: pokemon.name,
    source,
    cause: "hp_cost",
    damage: cost,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    effectiveness: 1,
  });
  return applyBoosts(state, side, pokemon, { attack: 12 }, source);
}

function applySubstitute(state, side, pokemon, source) {
  if (pokemon.volatiles?.substitute) return false;
  const cost = Math.floor(pokemon.stats.hp / 4);
  if (cost <= 0 || pokemon.hp <= cost) return false;
  pokemon.hp -= cost;
  pokemon.volatiles.substitute = {
    id: "substitute",
    hp: cost,
  };
  state.events.push({
    turn: state.turn,
    type: "damage",
    side,
    pokemon: pokemon.name,
    source,
    cause: "hp_cost",
    damage: cost,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    effectiveness: 1,
  });
  state.events.push({
    turn: state.turn,
    type: "volatile_start",
    side,
    pokemon: pokemon.name,
    effect: "substitute",
    hp: cost,
    source,
  });
  return true;
}

function applyCurse(state, actionSide, attacker, defenderSide, defender, source) {
  if (attacker.types.includes("Ghost")) {
    if (attacker.hp <= Math.floor(attacker.stats.hp / 2)) return false;
    const cost = Math.floor(attacker.stats.hp / 2);
    attacker.hp -= cost;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: actionSide,
      pokemon: attacker.name,
      source,
      cause: "hp_cost",
      damage: cost,
      remainingHp: attacker.hp,
      maximumHp: attacker.stats.hp,
      effectiveness: 1,
    });
    return applyVolatileStatus(state, defenderSide, defender, "curse", source);
  }
  const lowered = applyBoosts(state, actionSide, attacker, { speed: -1 }, source);
  const raised = applyBoosts(
    state,
    actionSide,
    attacker,
    { attack: 1, defence: 1 },
    source,
  );
  return lowered || raised;
}

function changeAbility(state, side, pokemon, ability, source, reason = "changed") {
  const nextAbility = cleanId(ability);
  if (!nextAbility || pokemon.ability === nextAbility) return false;
  const previousAbility = pokemon.ability;
  pokemon.ability = nextAbility;
  state.events.push({
    turn: state.turn,
    type: "ability_change",
    side,
    pokemon: pokemon.name,
    previousAbility,
    ability: nextAbility,
    source,
    reason,
  });
  return true;
}

function applyTransform(state, side, pokemon, target, source) {
  if (pokemon.volatiles?.transform || target.fainted) return false;
  const previousName = pokemon.name;
  pokemon.volatiles.transform = {
    id: "transform",
    previousName,
    target: target.name,
  };
  pokemon.name = target.name;
  pokemon.id = target.id;
  pokemon.types = [...target.types];
  pokemon.originalTypes = [...target.originalTypes];
  pokemon.ability = target.ability;
  pokemon.weightKg = target.weightKg;
  for (const stat of [
    "attack",
    "defence",
    "specialAttack",
    "specialDefence",
    "speed",
  ]) {
    pokemon.stats[stat] = target.stats[stat];
  }
  pokemon.boosts = { ...target.boosts };
  pokemon.moves = target.moves.map((move) => ({
    ...clone(move),
    maxPp: Math.min(5, move.maxPp ?? 5),
    pp: Math.min(5, move.maxPp ?? move.pp ?? 5),
  }));
  state.events.push({
    turn: state.turn,
    type: "transform",
    side,
    pokemon: previousName,
    target: target.name,
    source,
  });
  return true;
}

function addPokemonType(state, side, pokemon, type, source) {
  if (pokemon.types.includes(type)) return false;
  pokemon.types = [...pokemon.types, type];
  state.events.push({
    turn: state.turn,
    type: "type_change",
    side,
    pokemon: pokemon.name,
    types: [...pokemon.types],
    source,
  });
  return true;
}

function setPokemonTypes(state, side, pokemon, types, source) {
  const nextTypes = [...new Set(types.filter(Boolean))];
  if (nextTypes.length === 0) return false;
  if (
    pokemon.types.length === nextTypes.length &&
    pokemon.types.every((type, index) => type === nextTypes[index])
  ) {
    return false;
  }
  pokemon.types = nextTypes;
  state.events.push({
    turn: state.turn,
    type: "type_change",
    side,
    pokemon: pokemon.name,
    types: [...pokemon.types],
    source,
  });
  return true;
}

function applyMimic(state, side, pokemon, target, source, slot) {
  const copiedMove =
    target.lastMove && cleanId(target.lastMove.id) !== "mimic"
      ? target.lastMove
      : target.moves.find((move) => cleanId(move.id) !== "mimic");
  if (!copiedMove) return false;
  const moveIndex = Math.max(0, Number(slot ?? 1) - 1);
  pokemon.moves[moveIndex] = {
    ...clone(copiedMove),
    pp: 5,
    maxPp: 5,
  };
  state.events.push({
    turn: state.turn,
    type: "move_copied",
    side,
    pokemon: pokemon.name,
    source,
    move: copiedMove.name,
    moveId: copiedMove.id,
    slot: moveIndex + 1,
  });
  return true;
}

function calledMoveFrom(sourceMove, calledMove, overrides = {}) {
  return {
    ...clone(calledMove),
    calledBy: sourceMove.name,
    pp: sourceMove.pp,
    maxPp: sourceMove.maxPp,
    ...overrides,
  };
}

function defaultCallableMove(name = "Tackle") {
  return {
    id: cleanId(name) || "tackle",
    name,
    type: "Normal",
    category: "Physical",
    power: name === "Tri Attack" ? 80 : 40,
    accuracy: true,
    pp: 1,
    maxPp: 1,
    priority: 0,
    boosts: {},
    selfBoosts: {},
    secondaries: [],
  };
}

function callMove(state, side, pokemon, sourceMove, calledMove, overrides = {}) {
  if (!calledMove) return null;
  const move = calledMoveFrom(sourceMove, calledMove, overrides);
  state.events.push({
    turn: state.turn,
    type: "called_move",
    side,
    pokemon: pokemon.name,
    source: sourceMove.name,
    move: move.name,
    moveId: move.id,
  });
  return move;
}

function suppressAbility(state, side, pokemon, source) {
  if (!pokemon.ability || pokemon.volatiles?.gastroacid) return false;
  if (!applyVolatileStatus(state, side, pokemon, "gastroacid", source)) {
    return false;
  }
  pokemon.volatiles.gastroacid.previousAbility = pokemon.ability;
  state.events.push({
    turn: state.turn,
    type: "ability_suppressed",
    side,
    pokemon: pokemon.name,
    ability: pokemon.ability,
    source,
  });
  return true;
}

function applyDisable(state, side, pokemon, source) {
  const lastMove = pokemon.lastMove;
  const disabledMoveId = cleanId(lastMove?.id);
  if (!disabledMoveId || pokemon.moves.every((move) => cleanId(move.id) !== disabledMoveId)) {
    return false;
  }
  if (!applyVolatileStatus(state, side, pokemon, "disable", source)) {
    return false;
  }
  pokemon.volatiles.disable.moveId = disabledMoveId;
  pokemon.volatiles.disable.move = lastMove.name;
  return true;
}

function applyEncore(state, side, pokemon, source) {
  const lastMove = pokemon.lastMove;
  const encoredMoveId = cleanId(lastMove?.id);
  if (
    !encoredMoveId ||
    pokemon.moves.every((move) => cleanId(move.id) !== encoredMoveId) ||
    ["encore", "mimic", "sketch", "struggle"].includes(encoredMoveId)
  ) {
    return false;
  }
  if (!applyVolatileStatus(state, side, pokemon, "encore", source)) {
    return false;
  }
  pokemon.volatiles.encore.moveId = encoredMoveId;
  pokemon.volatiles.encore.move = lastMove.name;
  return true;
}

function reduceLastMovePp(state, side, pokemon, amount, source) {
  const lastMoveId = cleanId(pokemon.lastMove?.id);
  if (!lastMoveId) return false;
  const move = pokemon.moves.find((candidate) => cleanId(candidate.id) === lastMoveId);
  if (!move || move.pp <= 0) return false;
  const reduced = Math.min(move.pp, Math.max(1, Math.floor(amount)));
  move.pp -= reduced;
  state.events.push({
    turn: state.turn,
    type: "pp_reduced",
    side,
    pokemon: pokemon.name,
    move: move.name,
    moveId: move.id,
    amount: reduced,
    remainingPp: move.pp,
    source,
  });
  return true;
}

function swapBoosts(state, leftSide, leftPokemon, rightSide, rightPokemon, stats, source) {
  let changed = false;
  for (const stat of stats) {
    const left = leftPokemon.boosts[stat] ?? 0;
    const right = rightPokemon.boosts[stat] ?? 0;
    if (left === right) continue;
    leftPokemon.boosts[stat] = right;
    rightPokemon.boosts[stat] = left;
    changed = true;
  }
  if (!changed) return false;
  state.events.push({
    turn: state.turn,
    type: "boosts_swapped",
    source,
    leftSide,
    leftPokemon: leftPokemon.name,
    rightSide,
    rightPokemon: rightPokemon.name,
    stats: stats.map(eventStat),
  });
  return true;
}

function swapAllBoosts(state, leftSide, leftPokemon, rightSide, rightPokemon, source) {
  const leftBoosts = { ...leftPokemon.boosts };
  leftPokemon.boosts = { ...rightPokemon.boosts };
  rightPokemon.boosts = leftBoosts;
  state.events.push({
    turn: state.turn,
    type: "boosts_swapped",
    source,
    leftSide,
    leftPokemon: leftPokemon.name,
    rightSide,
    rightPokemon: rightPokemon.name,
    stats: BOOST_STATS.map(eventStat),
  });
  return true;
}

function splitStats(state, leftSide, leftPokemon, rightSide, rightPokemon, stats, source) {
  for (const stat of stats) {
    const averaged = Math.max(
      1,
      Math.floor((leftPokemon.stats[stat] + rightPokemon.stats[stat]) / 2),
    );
    leftPokemon.stats[stat] = averaged;
    rightPokemon.stats[stat] = averaged;
  }
  state.events.push({
    turn: state.turn,
    type: "stats_split",
    source,
    leftSide,
    leftPokemon: leftPokemon.name,
    rightSide,
    rightPokemon: rightPokemon.name,
    stats,
  });
  return true;
}

function swapStats(state, leftSide, leftPokemon, rightSide, rightPokemon, stats, source) {
  for (const stat of stats) {
    const left = leftPokemon.stats[stat];
    leftPokemon.stats[stat] = rightPokemon.stats[stat];
    rightPokemon.stats[stat] = left;
  }
  state.events.push({
    turn: state.turn,
    type: "stats_swapped",
    source,
    leftSide,
    leftPokemon: leftPokemon.name,
    rightSide,
    rightPokemon: rightPokemon.name,
    stats,
  });
  return true;
}

function swapSideConditions(state, leftSide, rightSide, source) {
  const leftConditions = state.sides[leftSide].conditions;
  state.sides[leftSide].conditions = state.sides[rightSide].conditions;
  state.sides[rightSide].conditions = leftConditions;
  state.events.push({
    turn: state.turn,
    type: "side_conditions_swapped",
    source,
    leftSide,
    rightSide,
  });
  return true;
}

function applyStrengthSap(state, attackerSide, attacker, defenderSide, defender, source) {
  const healAmount = Math.max(1, effectiveStat(defender, "attack"));
  const healed = healPokemon(state, attackerSide, attacker, healAmount, source) > 0;
  const lowered = applyBoosts(state, defenderSide, defender, { attack: -1 }, source);
  return healed || lowered;
}

function applyNoRetreat(state, side, pokemon, source) {
  if (pokemon.volatiles?.noretreat) return false;
  const boosted = applyBoosts(
    state,
    side,
    pokemon,
    {
      attack: 1,
      defence: 1,
      specialAttack: 1,
      specialDefence: 1,
      speed: 1,
    },
    source,
  );
  const trapped = applyVolatileStatus(state, side, pokemon, "noretreat", source);
  return boosted || trapped;
}

function canUseLastResort(pokemon) {
  const otherMoves = pokemon.moves
    .map((move) => cleanId(move.id))
    .filter((id) => id && id !== "lastresort");
  if (otherMoves.length === 0) return false;
  const history = new Set(pokemon.moveHistory ?? []);
  return otherMoves.every((id) => history.has(id));
}

function clearDefogEffects(state, userSide, targetSide, user, source) {
  let changed = false;
  for (const sideIndex of [userSide, targetSide]) {
    for (const condition of [
      "spikes",
      "stealthrock",
      "stickyweb",
      "toxicspikes",
      "auroraveil",
      "lightscreen",
      "reflect",
      "safeguard",
      "mist",
    ]) {
      changed =
        removeSideCondition(state, sideIndex, condition, source, user) || changed;
    }
  }
  return changed;
}

function applyHealPulse(state, side, pokemon, source) {
  return healPokemon(
    state,
    side,
    pokemon,
    Math.max(1, Math.floor(pokemon.stats.hp / 2)),
    source,
  ) > 0;
}

function setWish(state, side, pokemon, source) {
  if (state.sides[side].conditions.wish) return false;
  state.sides[side].conditions.wish = {
    id: "wish",
    turns: 2,
    heal: Math.max(1, Math.floor(pokemon.stats.hp / 2)),
    source,
    pokemon: pokemon.name,
  };
  state.events.push({
    turn: state.turn,
    type: "slot_condition_start",
    side,
    pokemon: pokemon.name,
    effect: "wish",
    duration: 2,
    source,
  });
  return true;
}

function isTrappedByVolatile(pokemon) {
  return Object.keys(pokemon.volatiles ?? {}).some((id) =>
    TRAPPING_VOLATILES.has(cleanId(id)),
  );
}

function weatherRecoveryFraction(state) {
  const weather = cleanId(state.field?.weather?.id);
  if (["sunnyday", "desolateland"].includes(weather)) return [2, 3];
  if (
    ["raindance", "primordialsea", "sandstorm", "snow", "hail"].includes(
      weather,
    )
  ) {
    return [1, 4];
  }
  return [1, 2];
}

function applyWeatherRecoveryMove(state, side, pokemon, source) {
  return (
    healPokemon(
      state,
      side,
      pokemon,
      fractionAmount(pokemon.stats.hp, weatherRecoveryFraction(state)),
      source,
    ) > 0
  );
}

function setFieldEffect(state, side, pokemon, kind, id, source) {
  const normalized = cleanId(id);
  if (!normalized) return false;
  let turns = DEFAULT_FIELD_DURATION;
  if (kind === "terrain" && pokemon.item === "terrainextender") turns = 8;
  if (kind === "weather") {
    const weatherRocks = {
      sunnyday: "heatrock",
      raindance: "damprock",
      sandstorm: "smoothrock",
      snow: "icyrock",
      hail: "icyrock",
    };
    if (pokemon.item === weatherRocks[normalized]) turns = 8;
  }
  if (kind === "pseudoWeather") {
    state.field.pseudoWeather[normalized] = { id: normalized, turns };
  } else {
    state.field[kind] = { id: normalized, turns };
  }
  state.events.push({
    turn: state.turn,
    type: "field_start",
    side,
    pokemon: pokemon.name,
    fieldKind: kind,
    effect: normalized,
    duration: turns,
    source,
  });
  return true;
}

function setSideCondition(state, side, pokemon, condition, source) {
  const id = cleanId(condition);
  if (!id) return false;
  const maximumLayers = LAYERED_SIDE_CONDITIONS[id];
  if (maximumLayers) {
    const previous = Number(state.sides[side].conditions[id]?.layers ?? 0);
    const layers = Math.min(maximumLayers, previous + 1);
    if (layers === previous) return false;
    state.sides[side].conditions[id] = { id, layers, turns: null };
    state.events.push({
      turn: state.turn,
      type: "side_condition_start",
      side,
      pokemon: pokemon.name,
      effect: id,
      layers,
      source,
    });
    return true;
  }
  if (id === "stealthrock" || id === "stickyweb") {
    if (state.sides[side].conditions[id]) return false;
    state.sides[side].conditions[id] = { id, layers: 1, turns: null };
    state.events.push({
      turn: state.turn,
      type: "side_condition_start",
      side,
      pokemon: pokemon.name,
      effect: id,
      layers: 1,
      source,
    });
    return true;
  }
  let turns = SIDE_CONDITION_DURATIONS[id] ?? DEFAULT_FIELD_DURATION;
  if (
    ["auroraveil", "lightscreen", "reflect"].includes(id) &&
    pokemon.item === "lightclay"
  ) {
    turns = 8;
  }
  state.sides[side].conditions[id] = { id, turns };
  state.events.push({
    turn: state.turn,
    type: "side_condition_start",
    side,
    pokemon: pokemon.name,
    effect: id,
    duration: turns,
    source,
  });
  return true;
}

function applyBoosts(state, side, pokemon, boosts, source) {
  let changed = false;
  for (const [stat, amount] of Object.entries(boosts ?? {})) {
    if (!BOOST_STATS.includes(stat) || !Number.isFinite(amount)) continue;
    const modifiedAmount = activeAbility(pokemon) === "simple" ? amount * 2 : amount;
    const previous = pokemon.boosts[stat] ?? 0;
    const next = Math.max(-6, Math.min(6, previous + modifiedAmount));
    const applied = next - previous;
    if (applied === 0) continue;
    pokemon.boosts[stat] = next;
    if (applied < 0) {
      pokemon.turnState ??= {};
      pokemon.turnState.statsLowered = true;
    }
    changed = true;
    state.events.push({
      turn: state.turn,
      type: "stat_change",
      side,
      pokemon: pokemon.name,
      stat: eventStat(stat),
      amount: applied,
      stage: next,
      source,
    });
  }
  return changed;
}

function resetBoosts(state, side, pokemon, source) {
  const changed = BOOST_STATS.some((stat) => (pokemon.boosts[stat] ?? 0) !== 0);
  if (!changed) return false;
  pokemon.boosts = Object.fromEntries(BOOST_STATS.map((stat) => [stat, 0]));
  state.events.push({
    turn: state.turn,
    type: "stat_reset",
    side,
    pokemon: pokemon.name,
    source,
  });
  return true;
}

function breakProtectiveScreens(state, side, source, pokemon) {
  let removed = false;
  for (const condition of ["auroraveil", "lightscreen", "reflect"]) {
    removed =
      removeSideCondition(state, side, condition, source, pokemon) || removed;
  }
  return removed;
}

function healPokemon(state, side, pokemon, amount, source) {
  if (pokemon.volatiles?.healblock) {
    state.events.push({
      turn: state.turn,
      type: "heal_blocked",
      side,
      pokemon: pokemon.name,
      source,
    });
    return 0;
  }
  const healed = Math.max(
    0,
    Math.min(pokemon.stats.hp - pokemon.hp, Math.floor(amount)),
  );
  if (healed <= 0 || pokemon.fainted) return 0;
  pokemon.hp += healed;
  state.events.push({
    turn: state.turn,
    type: "heal",
    side,
    pokemon: pokemon.name,
    source,
    amount: healed,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
  });
  return healed;
}

function applyDirectDamage(state, side, pokemon, amount, source, cause = "move") {
  if (pokemon.fainted || pokemon.hp <= 0) return 0;
  const damage = Math.max(0, Math.min(pokemon.hp, Math.floor(amount)));
  if (damage <= 0) return 0;
  pokemon.hp -= damage;
  pokemon.turnState.damageTaken += damage;
  pokemon.turnState.lastDamage = {
    amount: damage,
    category: "Special",
    move: source,
    source,
    sourceSide: null,
  };
  state.events.push({
    turn: state.turn,
    type: "damage",
    side,
    pokemon: pokemon.name,
    source,
    cause,
    damage,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    effectiveness: 1,
  });
  markFainted(state, side, pokemon);
  return damage;
}

function reviveBenchPokemon(state, side, source) {
  const target = state.sides[side].team.find((pokemon) => pokemon.fainted);
  if (!target) return false;
  target.fainted = false;
  target.hp = Math.max(1, Math.floor(target.stats.hp / 2));
  target.status = "";
  target.statusTurns = 0;
  state.events.push({
    turn: state.turn,
    type: "revive",
    side,
    pokemon: target.name,
    source,
    remainingHp: target.hp,
    maximumHp: target.stats.hp,
  });
  return true;
}

function applyShedTail(state, side, pokemon, source) {
  if (pokemon.hp <= Math.floor(pokemon.stats.hp / 2)) return false;
  const cost = Math.floor(pokemon.stats.hp / 2);
  pokemon.hp -= cost;
  state.events.push({
    turn: state.turn,
    type: "damage",
    side,
    pokemon: pokemon.name,
    source,
    cause: "self_cost",
    damage: cost,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    effectiveness: 1,
  });
  const switched = executeSelfSwitch(state, side, source);
  if (!switched) return true;
  const incoming = activePokemon(state, side);
  incoming.volatiles.substitute = {
    id: "substitute",
    hp: Math.max(1, Math.floor(incoming.stats.hp / 4)),
    source,
  };
  state.events.push({
    turn: state.turn,
    type: "volatile_start",
    side,
    pokemon: incoming.name,
    effect: "substitute",
    source,
    hp: incoming.volatiles.substitute.hp,
  });
  return true;
}

function fractionAmount(maximum, fraction) {
  return fraction
    ? Math.max(1, Math.floor((maximum * fraction[0]) / fraction[1]))
    : 0;
}

function applyMoveEffect(
  state,
  attackerSide,
  attacker,
  defenderSide,
  defender,
  effect,
  rng,
  source,
) {
  let applied = false;
  if (effect.status) {
    applied =
      applyStatus(
        state,
        defenderSide,
        defender,
        effect.status,
        rng,
        source,
        attackerSide,
      ) ||
      applied;
  }
  if (Object.keys(effect.boosts ?? {}).length) {
    applied =
      applyBoosts(
        state,
        defenderSide,
        defender,
        effect.boosts,
        source,
      ) || applied;
  }
  if (Object.keys(effect.selfBoosts ?? {}).length) {
    applied =
      applyBoosts(
        state,
        attackerSide,
        attacker,
        effect.selfBoosts,
        source,
      ) || applied;
  }
  return applied;
}

function canAct(state, side, pokemon, rng, options = {}) {
  if (pokemon.volatiles?.flinch) {
    delete pokemon.volatiles.flinch;
    state.events.push({
      turn: state.turn,
      type: "cant_move",
      side,
      pokemon: pokemon.name,
      status: "flinch",
      source: "flinch",
    });
    return false;
  }
  if (pokemon.volatiles?.attract && rng.next() < 0.5) {
    state.events.push({
      turn: state.turn,
      type: "cant_move",
      side,
      pokemon: pokemon.name,
      status: "attract",
      source: pokemon.volatiles.attract.source || "Attract",
    });
    return false;
  }
  if (pokemon.volatiles?.confusion) {
    state.events.push({
      turn: state.turn,
      type: "volatile_activate",
      side,
      pokemon: pokemon.name,
      effect: "confusion",
    });
    if (rng.next() < 1 / 3) {
      const damage = Math.min(
        pokemon.hp,
        Math.max(
          1,
          Math.floor(
            damageBase(pokemon, pokemon, {
              type: "Normal",
              category: "Physical",
              power: 40,
            }),
          ),
        ),
      );
      pokemon.hp -= damage;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side,
        pokemon: pokemon.name,
        source: "confusion",
        cause: "volatile",
        damage,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      markFainted(state, side, pokemon);
      return false;
    }
  }
  if (pokemon.status === "slp" && !options.allowSleepAction) {
    if (pokemon.statusTurns > 0) {
      pokemon.statusTurns -= 1;
      state.events.push({
        turn: state.turn,
        type: "cant_move",
        side,
        pokemon: pokemon.name,
        status: "slp",
      });
      return false;
    }
    pokemon.status = "";
    state.events.push({
      turn: state.turn,
      type: "status_cured",
      side,
      pokemon: pokemon.name,
      status: "slp",
    });
  }
  if (pokemon.status === "frz") {
    if (rng.next() >= 0.2) {
      state.events.push({
        turn: state.turn,
        type: "cant_move",
        side,
        pokemon: pokemon.name,
        status: "frz",
      });
      return false;
    }
    pokemon.status = "";
    state.events.push({
      turn: state.turn,
      type: "status_cured",
      side,
      pokemon: pokemon.name,
      status: "frz",
    });
  }
  if (pokemon.status === "par" && rng.next() < 0.25) {
    state.events.push({
      turn: state.turn,
      type: "cant_move",
      side,
      pokemon: pokemon.name,
      status: "par",
    });
    return false;
  }
  return true;
}

function markFainted(state, side, pokemon) {
  if (pokemon.hp > 0 || pokemon.fainted) return false;
  pokemon.hp = 0;
  endDynamax(state, side, pokemon, "faint");
  pokemon.fainted = true;
  state.sides[side].lastFaintedTurn = state.turn;
  pokemon.consecutiveMove = { id: "", count: 0 };
  pokemon.lockedMove = null;
  pokemon.choiceLock = null;
  pokemon.chargingMove = null;
  pokemon.volatiles = {};
  state.events.push({
    turn: state.turn,
    type: "faint",
    side,
    pokemon: pokemon.name,
  });
  return true;
}

function applyCrashDamage(state, side, pokemon, source) {
  if (pokemon.fainted || pokemon.hp <= 0) return false;
  const damage = Math.min(pokemon.hp, Math.max(1, Math.floor(pokemon.stats.hp / 2)));
  pokemon.hp -= damage;
  state.events.push({
    turn: state.turn,
    type: "damage",
    side,
    pokemon: pokemon.name,
    source,
    cause: "crash",
    damage,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    effectiveness: 1,
  });
  markFainted(state, side, pokemon);
  return true;
}

function hasCrashOnFailure(move) {
  return ["axekick", "highjumpkick", "jumpkick", "supercellslam"].includes(cleanId(move?.id));
}

function hitCountForMove(move, attacker, rng) {
  if (!move.multihit) return 1;
  const minimum = Math.max(1, Math.floor(move.multihit[0] ?? 1));
  const maximum = Math.max(minimum, Math.floor(move.multihit[1] ?? minimum));
  if (activeAbility(attacker) === "skilllink") return maximum;
  if (minimum === maximum) return minimum;
  if (minimum === 2 && maximum === 5 && LOADED_DICE_ITEMS.has(cleanId(attacker.item))) {
    return rng.next() < 0.5 ? 4 : 5;
  }
  if (minimum === 2 && maximum === 5) {
    const roll = rng.next();
    if (roll < 3 / 8) return 2;
    if (roll < 6 / 8) return 3;
    if (roll < 7 / 8) return 4;
    return 5;
  }
  return minimum + Math.floor(rng.next() * (maximum - minimum + 1));
}

function fixedDamageAmount(move, attacker, defender) {
  const moveId = cleanId(move.id);
  const configured = move.fixedDamage;
  if (
    configured === "level" ||
    moveId === "nightshade" ||
    moveId === "seismictoss"
  ) {
    return attacker.level;
  }
  if (moveId === "dragonrage") return 40;
  if (moveId === "sonicboom") return 20;
  if (moveId === "psywave") return Math.max(1, attacker.level);
  if (["fissure", "guillotine", "horndrill", "sheercold"].includes(moveId)) {
    return defender.hp;
  }
  if (["ruination", "superfang", "naturesmadness"].includes(moveId)) {
    return Math.max(1, Math.floor(defender.hp / 2));
  }
  if (moveId === "guardianofalola") {
    return Math.max(1, Math.floor((defender.hp * 3) / 4));
  }
  if (moveId === "endeavor") {
    return attacker.hp < defender.hp ? defender.hp - attacker.hp : 0;
  }
  if (moveId === "counter") {
    const lastDamage = attacker.turnState?.lastDamage;
    return lastDamage?.category === "Physical" ? lastDamage.amount * 2 : 0;
  }
  if (moveId === "mirrorcoat") {
    const lastDamage = attacker.turnState?.lastDamage;
    return lastDamage?.category === "Special" ? lastDamage.amount * 2 : 0;
  }
  if (moveId === "metalburst") {
    const lastDamage = attacker.turnState?.lastDamage;
    return lastDamage?.amount > 0 ? Math.floor(lastDamage.amount * 1.5) : 0;
  }
  if (moveId === "comeuppance") {
    const lastDamage = attacker.turnState?.lastDamage;
    return lastDamage?.amount > 0 ? Math.floor(lastDamage.amount * 1.5) : 0;
  }
  if (moveId === "finalgambit") return attacker.hp;
  if (configured !== null && configured !== undefined && Number.isFinite(Number(configured))) {
    return Math.max(1, Math.floor(Number(configured)));
  }
  return null;
}

function recordMoveResult(state, side, pokemon, move, slot, succeeded, rng = null) {
  const moveId = cleanId(move?.id);
  const rollingMove = ROLLING_LOCK_MOVES.has(moveId);
  const rampageMove = RAMPAGE_LOCK_MOVES.has(moveId);
  const repeatedLockMove = rollingMove || rampageMove;
  pokemon.lastMoveSucceeded = Boolean(succeeded);
  if (succeeded && move) {
    pokemon.lastMove = clone(move);
    if (!["copycat", "metronome", "mimic", "mirrormove", "sleeptalk"].includes(moveId)) {
      state.lastSuccessfulMove = clone(move);
    }
    pokemon.moveHistory ??= [];
    if (!pokemon.moveHistory.includes(moveId)) {
      pokemon.moveHistory.push(moveId);
    }
  }
  if (pokemon.fainted) {
    pokemon.consecutiveMove = { id: "", count: 0 };
    pokemon.lockedMove = null;
    pokemon.choiceLock = null;
    return;
  }
  if (!CHOICE_LOCK_ITEMS.has(cleanId(pokemon.item))) {
    pokemon.choiceLock = null;
  } else if (
    succeeded &&
    moveId &&
    moveId !== "struggle" &&
    !pokemon.choiceLock?.id
  ) {
    pokemon.choiceLock = {
      id: moveId,
      slot,
      item: cleanId(pokemon.item),
    };
  }
  if (
    succeeded &&
    moveId &&
    pokemon.consecutiveMove?.id === moveId
  ) {
    pokemon.consecutiveMove.count += 1;
  } else if (succeeded && moveId) {
    pokemon.consecutiveMove = { id: moveId, count: 1 };
  } else {
    pokemon.consecutiveMove = { id: "", count: 0 };
  }
  if (!repeatedLockMove) return;

  const maximum =
    rollingMove
      ? 5
      : Number(pokemon.lockedMove?.maximum) ||
        2 + Math.floor((rng?.next?.() ?? 0) * 2);
  if (succeeded && pokemon.consecutiveMove.count < maximum) {
    pokemon.lockedMove = {
      id: moveId,
      slot,
      kind: rollingMove ? "rolling" : "rampage",
      maximum,
    };
    state.events.push({
      turn: state.turn,
      type: "move_lock",
      side,
      pokemon: pokemon.name,
      move: move.name,
      count: pokemon.consecutiveMove.count,
      maximum,
    });
    return;
  }

  const hadLock = Boolean(pokemon.lockedMove);
  pokemon.lockedMove = null;
  if (succeeded) {
    pokemon.consecutiveMove = { id: "", count: 0 };
    if (rampageMove) {
      applyVolatileStatus(state, side, pokemon, "confusion", move.name);
    }
  }
  if (hadLock || !succeeded) {
    state.events.push({
      turn: state.turn,
      type: "move_lock_end",
      side,
      pokemon: pokemon.name,
      move: move?.name,
      reason: succeeded ? "completed" : "interrupted",
    });
  }
}

function executeMove(state, action, rng) {
  const attacker = activePokemon(state, action.side);
  const defenderSide = action.side === 0 ? 1 : 0;
  const defender = activePokemon(state, defenderSide);
  if (attacker.fainted || state.status !== "running") return false;
  if (!action.selected) {
    state.events.push({
      turn: state.turn,
      type: "no_pp",
      side: action.side,
      pokemon: attacker.name,
    });
    return false;
  }

  let { move: sourceMove, slot } = action.selected;
  const sourceMoveId = cleanId(sourceMove.id);
  if (
    !canAct(state, action.side, attacker, rng, {
      allowSleepAction: sourceMoveId === "sleeptalk" || sourceMoveId === "snore",
    })
  ) {
    return false;
  }
  if (!action.noPpCost) sourceMove.pp -= 1;
  if (
    !action.noPpCost &&
    activeAbility(defender) === "pressure" &&
    sourceMove.pp > 0 &&
    !["self", "allyside"].includes(cleanId(sourceMove.target))
  ) {
    sourceMove.pp -= 1;
    state.events.push({
      turn: state.turn,
      type: "pp_reduced",
      side: action.side,
      pokemon: attacker.name,
      move: sourceMove.name,
      moveId: sourceMove.id,
      amount: 1,
      remainingPp: sourceMove.pp,
      source: "pressure",
    });
  }
  if (sourceMoveId === "snore" && attacker.status !== "slp") {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: sourceMove.name,
      reason: "Snore requires the user to be asleep.",
    });
    return false;
  }
  if (sourceMoveId === "sleeptalk") {
    if (attacker.status !== "slp") {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: sourceMove.name,
        reason: "Sleep Talk requires the user to be asleep.",
      });
      return false;
    }
    const callableMoves = attacker.moves.filter((move) => {
      const id = cleanId(move.id);
      return move.pp > 0 && !["sleeptalk", "assist", "metronome"].includes(id);
    });
    if (callableMoves.length === 0) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: sourceMove.name,
        reason: "Sleep Talk could not call another usable move.",
      });
      return false;
    }
    const calledMove = callableMoves[Math.floor(rng.next() * callableMoves.length)];
    state.events.push({
      turn: state.turn,
      type: "called_move",
      side: action.side,
      pokemon: attacker.name,
      source: sourceMove.name,
      move: calledMove.name,
      moveId: calledMove.id,
    });
    sourceMove = calledMove;
    slot = attacker.moves.indexOf(calledMove) + 1;
  }
  if (sourceMoveId === "copycat") {
    const calledMove = callMove(
      state,
      action.side,
      attacker,
      sourceMove,
      state.lastSuccessfulMove,
    );
    if (!calledMove) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: sourceMove.name,
        reason: "Copycat could not find a previous successful move.",
      });
      return false;
    }
    sourceMove = calledMove;
  }
  if (sourceMoveId === "mirrormove") {
    const calledMove = callMove(
      state,
      action.side,
      attacker,
      sourceMove,
      defender.lastMove,
    );
    if (!calledMove) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: sourceMove.name,
        reason: "Mirror Move requires the target to have used a move.",
      });
      return false;
    }
    sourceMove = calledMove;
  }
  if (sourceMoveId === "mefirst") {
    const defenderAction = state.currentActions?.find(
      (candidate) => candidate.side === defenderSide,
    );
    const targetMove = defenderAction?.selected?.move;
    if (
      !targetMove ||
      defender.turnState.acted ||
      targetMove.category === "Status" ||
      targetMove.power <= 0
    ) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: sourceMove.name,
        reason: "Me First requires the target to be preparing a damaging move.",
      });
      return false;
    }
    sourceMove = callMove(
      state,
      action.side,
      attacker,
      sourceMove,
      targetMove,
      { power: Math.floor(targetMove.power * 1.5) },
    );
  }
  if (sourceMoveId === "metronome") {
    sourceMove = callMove(
      state,
      action.side,
      attacker,
      sourceMove,
      defaultCallableMove("Tackle"),
    );
  }
  if (sourceMoveId === "naturepower") {
    const terrain = cleanId(state.field?.terrain?.id);
    const natureMove =
      terrain === "electricterrain"
        ? defaultCallableMove("Thunderbolt")
        : terrain === "grassyterrain"
          ? { ...defaultCallableMove("Energy Ball"), type: "Grass", category: "Special", power: 90 }
          : terrain === "psychicterrain"
            ? { ...defaultCallableMove("Psychic"), type: "Psychic", category: "Special", power: 90 }
            : terrain === "mistyterrain"
              ? { ...defaultCallableMove("Moonblast"), type: "Fairy", category: "Special", power: 95 }
              : { ...defaultCallableMove("Tri Attack"), category: "Special" };
    sourceMove = callMove(state, action.side, attacker, sourceMove, natureMove);
  }
  let move = transformGimmickMove(state, action, sourceMove);
  if (attacker.volatiles?.electrify) {
    move = { ...move, type: "Electric" };
    delete attacker.volatiles.electrify;
  }
  if (cleanId(move.id) === "aurawheel") {
    move = {
      ...move,
      type: cleanId(attacker.species || attacker.id).includes("morpekohangry")
        ? "Dark"
        : "Electric",
    };
  }
  if (cleanId(move.id) === "ragingbull") {
    const type =
      cleanId(attacker.species || attacker.id).includes("paldeablaze")
        ? "Fire"
        : cleanId(attacker.species || attacker.id).includes("paldeaaqua")
          ? "Water"
          : "Normal";
    move = { ...move, type };
  }
  if (cleanId(move.id) === "struggle") {
    move = { ...move, type: "Typeless" };
  }
  if (cleanId(move.id) === "naturalgift") {
    const giftType = heldItemType(attacker.item);
    move = { ...move, type: giftType || "Normal" };
  }
  if (
    action.pursuitTargetSwitch &&
    cleanId(move.id) === "pursuit" &&
    !move.dynamicPower
  ) {
    move = { ...move, power: Math.max(1, move.power * 2) };
  }
  if (cleanId(move.id) === "terablast" && attacker.terastallized) {
    const teraType = attacker.teraType || attacker.types[0] || "Normal";
    const usesPhysicalAttack =
      effectiveStat(attacker, "attack") > effectiveStat(attacker, "specialAttack");
    move = {
      ...move,
      type: teraType,
      category: usesPhysicalAttack ? "Physical" : "Special",
      selfBoosts:
        cleanId(teraType) === "stellar"
          ? {
              ...move.selfBoosts,
              attack: (move.selfBoosts?.attack ?? 0) - 1,
              specialAttack: (move.selfBoosts?.specialAttack ?? 0) - 1,
            }
          : move.selfBoosts,
    };
  }
  if (cleanId(move.id) === "photongeyser") {
    const usesPhysicalAttack =
      effectiveStat(attacker, "attack") > effectiveStat(attacker, "specialAttack");
    move = {
      ...move,
      category: usesPhysicalAttack ? "Physical" : "Special",
    };
  }
  if (cleanId(move.id) === "shellsidearm") {
    const physicalDamage = damageBase(attacker, defender, {
      ...move,
      category: "Physical",
    });
    const specialDamage = damageBase(attacker, defender, {
      ...move,
      category: "Special",
    });
    move = {
      ...move,
      category: physicalDamage > specialDamage ? "Physical" : "Special",
    };
  }
  if (cleanId(move.id) === "ivycudgel") {
    const itemType = {
      cornerstone: "Rock",
      cornerstonemask: "Rock",
      hearthflame: "Fire",
      hearthflamemask: "Fire",
      wellspring: "Water",
      wellspringmask: "Water",
    }[cleanId(attacker.item)];
    if (itemType) move = { ...move, type: itemType };
  }
  if (["judgment", "multiattack", "technoblast"].includes(cleanId(move.id))) {
    const itemType = heldItemType(attacker.item);
    if (itemType) move = { ...move, type: itemType };
  }
  if (cleanId(move.id) === "revelationdance") {
    move = { ...move, type: attacker.types[0] || move.type };
  }
  if (cleanId(move.id) === "terastarstorm" && attacker.terastallized) {
    move = { ...move, type: attacker.teraType || move.type };
  }
  move = weatherBallMove(state, move);
  move = terrainPulseMove(state, move);
  move = growthMove(state, move);
  move = terrainModifiedMove(state, attacker, move);
  move = chargeAdjustedMove(state, move);
  state.events.push({
    turn: state.turn,
    type: "move",
    side: action.side,
    pokemon: attacker.name,
    move: move.name,
    moveId: move.id,
    slot,
  });
  state.turnMoves ??= [];
  state.turnMoves.push({ side: action.side, id: cleanId(move.id), move: move.name });

  if (shouldChargeMove(state, move, action)) {
    return beginChargeMove(state, action, attacker, move, slot);
  }
  if (action.chargingRelease) {
    attacker.chargingMove = null;
  }

  if (attacker.volatiles?.taunt && move.category === "Status") {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Taunt prevents status moves.",
      source: "taunt",
    });
    return false;
  }

  if (isMoveDisabledByVolatile(attacker, move)) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Disable prevents this move.",
      source: "disable",
    });
    return false;
  }

  if (isMoveImprisoned(state, action.side, move)) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Imprison prevents this move.",
      source: "imprison",
    });
    return false;
  }

  if (
    attacker.volatiles?.torment &&
    cleanId(attacker.lastMove?.id) === cleanId(move.id)
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Torment prevents using the same move twice in a row.",
      source: "torment",
    });
    return false;
  }

  if (cleanId(move.id) === "suckerpunch") {
    const defenderAction = state.currentActions?.find(
      (candidate) => candidate.side === defenderSide,
    );
    const defenderAlreadyActed = activePokemon(state, defenderSide).turnState.acted;
    if (
      defenderAlreadyActed ||
      defenderAction?.kind !== "move" ||
      defenderAction.selected?.move.category === "Status"
    ) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        reason: "Sucker Punch only works before the target uses an attacking move.",
      });
      return false;
    }
  }

  if (cleanId(move.id) === "thunderclap") {
    const defenderAction = state.currentActions?.find(
      (candidate) => candidate.side === defenderSide,
    );
    const targetMove = defenderAction?.selected?.move;
    if (
      defender.turnState.acted ||
      !targetMove ||
      targetMove.category === "Status" ||
      targetMove.power <= 0
    ) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        reason: "Thunderclap only works before the target uses an attacking move.",
      });
      return false;
    }
  }

  if (cleanId(move.id) === "upperhand") {
    const defenderAction = state.currentActions?.find(
      (candidate) => candidate.side === defenderSide,
    );
    const targetPriority = Number(defenderAction?.priority ?? 0);
    if (defender.turnState.acted || targetPriority <= 0) {
      state.events.push({
        turn: state.turn,
        type: "move_failed",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        reason: "Upper Hand only works against a target using a priority move.",
      });
      return false;
    }
  }

  if (
    defender.volatiles?.protect &&
    move.target !== "self" &&
    cleanId(move.id) !== "hyperspacefury"
  ) {
    const protectSource = defender.volatiles.protect.source || "protect";
    const protectSourceId = cleanId(protectSource);
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: ["burningbulwark", "kingsshield", "maxguard"].includes(
        protectSourceId,
      )
        ? protectSource
        : "protect",
    });
    applyProtectBlockEffect(
      state,
      defenderSide,
      defender,
      action.side,
      attacker,
      move,
      rng,
    );
    if (hasCrashOnFailure(move)) {
      applyCrashDamage(state, action.side, attacker, move.name);
    }
    return false;
  }

  if (
    move.priority > 0 &&
    ((cleanId(state.field?.terrain?.id) === "psychicterrain" &&
      isGrounded(defender)) ||
      hasSideCondition(state, defenderSide, "quickguard"))
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source:
        cleanId(state.field?.terrain?.id) === "psychicterrain"
          ? "psychicterrain"
          : "quickguard",
      });
    return false;
  }

  if (
    hasSideCondition(state, defenderSide, "craftyshield") &&
    move.category === "Status" &&
    move.target !== "self"
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "craftyshield",
    });
    return false;
  }

  if (
    hasSideCondition(state, defenderSide, "matblock") &&
    move.category !== "Status" &&
    move.power > 0
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "matblock",
    });
    return false;
  }

  if (
    hasSideCondition(state, defenderSide, "wideguard") &&
    String(move.target).includes("all")
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "wideguard",
    });
    return false;
  }

  if (attacker.volatiles?.powder && move.type === "Fire" && move.power > 0) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Powder explodes when the user attempts a Fire-type move.",
      source: "powder",
    });
    applyDirectDamage(
      state,
      action.side,
      attacker,
      Math.max(1, Math.floor(attacker.stats.hp / 4)),
      "Powder",
      "volatile",
    );
    return false;
  }

  const defenderAction = state.currentActions?.find(
    (candidate) => candidate.side === defenderSide,
  );
  if (
    defenderAction?.kind === "move" &&
    !defender.turnState.acted &&
    cleanId(defenderAction.selected?.move?.id) === "beakblast" &&
    move.category === "Physical" &&
    move.power > 0
  ) {
    applyStatus(
      state,
      action.side,
      attacker,
      "brn",
      rng,
      "Beak Blast",
      defenderSide,
    );
  }

  if (cleanId(move.id) === "fakeout" && attacker.activeTurns > 0) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Fake Out only works on the user's first active turn.",
    });
    return false;
  }

  if (cleanId(move.id) === "firstimpression" && attacker.activeTurns > 0) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "First Impression only works on the user's first active turn.",
    });
    return false;
  }

  if (cleanId(move.id) === "poltergeist" && !defender.item) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Poltergeist requires the target to hold an item.",
    });
    return false;
  }

  if (cleanId(move.id) === "dreameater" && defender.status !== "slp") {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Dream Eater requires a sleeping target.",
    });
    return false;
  }

  if (cleanId(move.id) === "belch" && !attacker.ateBerry) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Belch requires the user to have eaten a Berry.",
    });
    return false;
  }

  if (cleanId(move.id) === "doubleshock" && !attacker.types.includes("Electric")) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Double Shock requires the user to be Electric-type.",
    });
    return false;
  }

  if (cleanId(move.id) === "burnup" && !attacker.types.includes("Fire")) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Burn Up requires the user to be Fire-type.",
    });
    return false;
  }

  if (cleanId(move.id) === "steelroller" && !state.field?.terrain) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Steel Roller requires active terrain.",
    });
    return false;
  }

  if (cleanId(move.id) === "lastresort" && !canUseLastResort(attacker)) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Last Resort requires every other move to have been used.",
    });
    return false;
  }

  if (cleanId(move.id) === "fling" && !attacker.item) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Fling requires the user to hold an item.",
    });
    return false;
  }

  if (
    cleanId(move.id) === "synchronoise" &&
    !attacker.types.some((type) => defender.types.includes(type))
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Synchronoise requires the target to share a type with the user.",
    });
    return false;
  }

  if (
    ["spitup", "swallow"].includes(cleanId(move.id)) &&
    !attacker.volatiles?.stockpile?.count
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: `${move.name} requires Stockpile.`,
    });
    return false;
  }

  if (cleanId(move.id) === "darkvoid" && cleanId(attacker.id) !== "darkrai") {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Dark Void can only be used by Darkrai.",
    });
    return false;
  }

  if (cleanId(move.id) === "focuspunch" && attacker.turnState.damageTaken > 0) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Focus Punch fails if the user was hit before moving.",
    });
    return false;
  }

  if (
    defender.volatiles?.substitute &&
    move.category === "Status" &&
    move.target !== "self"
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "substitute",
    });
    return false;
  }

  if (
    !move.multiaccuracy &&
    move.accuracy !== true &&
    rng.next() * 100 >= effectiveAccuracy(attacker, defender, move, state)
  ) {
    state.events.push({
      turn: state.turn,
      type: "miss",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
    });
    if (hasCrashOnFailure(move)) {
      applyCrashDamage(state, action.side, attacker, move.name);
    }
    return false;
  }

  if (
    (move.category === "Status" || move.power <= 0) &&
    fixedDamageAmount(move, attacker, defender) === null
  ) {
    let applied = false;
    let handled = false;
    if (cleanId(move.id) === "rest") {
      handled = true;
      applied = applyRest(state, action.side, attacker, move.name) || applied;
    }
    if (
      [
        "banefulbunker",
        "burningbulwark",
        "detect",
        "kingsshield",
        "maxguard",
        "obstruct",
        "protect",
        "silktrap",
        "spikyshield",
      ].includes(cleanId(move.id))
    ) {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "protect",
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "endure") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "endure",
          move.name,
        ) || applied;
    }
    if (["moonlight", "morningsun", "synthesis"].includes(cleanId(move.id))) {
      handled = true;
      applied =
        applyWeatherRecoveryMove(state, action.side, attacker, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "splash") {
      handled = true;
      applied = true;
    }
    if (["afteryou", "allyswitch", "quash"].includes(cleanId(move.id))) {
      handled = true;
      state.events.push({
        turn: state.turn,
        type: "move_utility",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        targetSide: defenderSide,
        target: defender.name,
      });
      applied = true;
    }
    if (cleanId(move.id) === "clangoroussoul") {
      handled = true;
      if (attacker.hp > Math.floor(attacker.stats.hp / 3)) {
        applied =
          applySelfHpCost(state, action.side, attacker, move.name, [1, 3]) ||
          applied;
        applied =
          applyBoosts(
            state,
            action.side,
            attacker,
            {
              attack: 1,
              defence: 1,
              specialAttack: 1,
              specialDefence: 1,
              speed: 1,
            },
            move.name,
          ) || applied;
      }
    }
    if (cleanId(move.id) === "filletaway") {
      handled = true;
      if (attacker.hp > Math.floor(attacker.stats.hp / 2)) {
        applied =
          applySelfHpCost(state, action.side, attacker, move.name, [1, 2]) ||
          applied;
        applied =
          applyBoosts(
            state,
            action.side,
            attacker,
            { attack: 2, specialAttack: 2, speed: 2 },
            move.name,
          ) || applied;
      }
    }
    if (cleanId(move.id) === "flowershield") {
      handled = true;
      for (const [boostSide, boostPokemon] of [
        [action.side, attacker],
        [defenderSide, defender],
      ]) {
        if (boostPokemon.types.includes("Grass")) {
          applied =
            applyBoosts(state, boostSide, boostPokemon, { defence: 1 }, move.name) ||
            applied;
        }
      }
    }
    if (["gearup", "magneticflux"].includes(cleanId(move.id))) {
      handled = true;
      const ability = activeAbility(attacker);
      if (["plus", "minus"].includes(ability)) {
        const boosts =
          cleanId(move.id) === "gearup"
            ? { attack: 1, specialAttack: 1 }
            : { defence: 1, specialDefence: 1 };
        applied =
          applyBoosts(state, action.side, attacker, boosts, move.name) ||
          applied;
      }
    }
    if (cleanId(move.id) === "geomancy") {
      handled = true;
      applied =
        applyBoosts(
          state,
          action.side,
          attacker,
          { specialAttack: 2, specialDefence: 2, speed: 2 },
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "stuffcheeks") {
      handled = true;
      if (cleanId(attacker.item).endsWith("berry")) {
        removeTargetItem(state, action.side, attacker, move.name);
        attacker.consumedItem = attacker.lastItem;
        applied =
          applyBoosts(state, action.side, attacker, { defence: 2 }, move.name) ||
          true;
      }
    }
    if (cleanId(move.id) === "teatime") {
      handled = true;
      for (const [teaSide, teaPokemon] of [
        [action.side, attacker],
        [defenderSide, defender],
      ]) {
        if (cleanId(teaPokemon.item).endsWith("berry")) {
          removeTargetItem(state, teaSide, teaPokemon, move.name);
          teaPokemon.consumedItem = teaPokemon.lastItem;
          applied = true;
        }
      }
    }
    if (cleanId(move.id) === "bestow") {
      handled = true;
      if (attacker.item && !defender.item) {
        defender.item = attacker.item;
        attacker.item = "";
        state.events.push({
          turn: state.turn,
          type: "item_given",
          side: action.side,
          pokemon: attacker.name,
          targetSide: defenderSide,
          target: defender.name,
          item: defender.item,
          source: move.name,
        });
        applied = true;
      }
    }
    if (cleanId(move.id) === "doodle") {
      handled = true;
      applied =
        changeAbility(
          state,
          action.side,
          attacker,
          activeAbility(defender),
          move.name,
          "doodle",
        ) || applied;
    }
    if (cleanId(move.id) === "rototiller") {
      handled = true;
      for (const [boostSide, boostPokemon] of [
        [action.side, attacker],
        [defenderSide, defender],
      ]) {
        if (boostPokemon.types.includes("Grass") && isGrounded(boostPokemon)) {
          applied =
            applyBoosts(
              state,
              boostSide,
              boostPokemon,
              { attack: 1, specialAttack: 1 },
              move.name,
            ) || applied;
        }
      }
    }
    if (cleanId(move.id) === "tidyup") {
      handled = true;
      applied = removeAllHazards(state, move.name, attacker) || applied;
      applied =
        applyBoosts(
          state,
          action.side,
          attacker,
          { attack: 1, speed: 1 },
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "bellydrum") {
      handled = true;
      applied = applyBellyDrum(state, action.side, attacker, move.name) || applied;
    }
    if (cleanId(move.id) === "substitute") {
      handled = true;
      applied = applySubstitute(state, action.side, attacker, move.name) || applied;
    }
    if (cleanId(move.id) === "curse") {
      handled = true;
      applied =
        applyCurse(state, action.side, attacker, defenderSide, defender, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "charge") {
      handled = true;
      applied =
        applyVolatileStatus(state, action.side, attacker, "charge", move.name) ||
        applied;
      applied =
        applyBoosts(
          state,
          action.side,
          attacker,
          { specialDefence: 1 },
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "perishsong") {
      handled = true;
      for (const [perishSide, perishPokemon] of [
        [action.side, attacker],
        [defenderSide, defender],
      ]) {
        applied =
          applyVolatileStatus(
            state,
            perishSide,
            perishPokemon,
            "perishsong",
            move.name,
          ) || applied;
      }
    }
    if (["futuresight", "doomdesire"].includes(cleanId(move.id))) {
      handled = true;
      const futureMoveId = cleanId(move.id);
      state.futureAttacks ??= [];
      if (
        !state.futureAttacks.some(
          (entry) => entry.targetSide === defenderSide && entry.id === futureMoveId,
        )
      ) {
        state.futureAttacks.push({
          id: futureMoveId,
          source: move.name,
          sourcePokemon: attacker.name,
          sourceSide: action.side,
          targetSide: defenderSide,
          turns: 2,
          move: {
            id: futureMoveId,
            name: move.name,
            type: futureMoveId === "doomdesire" ? "Steel" : "Psychic",
            category: "Special",
            power: futureMoveId === "doomdesire" ? Math.max(140, move.power) : Math.max(120, move.power),
          },
        });
        state.events.push({
          turn: state.turn,
          type: "future_attack_start",
          side: action.side,
          pokemon: attacker.name,
          targetSide: defenderSide,
          source: move.name,
        });
        applied = true;
      }
    }
    if (cleanId(move.id) === "revivalblessing") {
      handled = true;
      applied = reviveBenchPokemon(state, action.side, move.name) || applied;
    }
    if (cleanId(move.id) === "shedtail") {
      handled = true;
      applied = applyShedTail(state, action.side, attacker, move.name) || applied;
    }
    if (cleanId(move.id) === "stockpile") {
      handled = true;
      const current = Math.max(0, Number(attacker.volatiles?.stockpile?.count ?? 0));
      if (current < 3) {
        attacker.volatiles.stockpile = {
          id: "stockpile",
          count: current + 1,
          source: move.name,
        };
        state.events.push({
          turn: state.turn,
          type: "volatile_start",
          side: action.side,
          pokemon: attacker.name,
          effect: "stockpile",
          source: move.name,
          count: attacker.volatiles.stockpile.count,
        });
        applied = true;
      }
    }
    if (cleanId(move.id) === "bide") {
      handled = true;
      const bide = attacker.volatiles?.bide;
      if (!bide) {
        attacker.volatiles.bide = {
          id: "bide",
          count: 1,
          stored: 0,
          source: move.name,
        };
        attacker.lockedMove = { id: "bide", slot };
        state.events.push({
          turn: state.turn,
          type: "volatile_start",
          side: action.side,
          pokemon: attacker.name,
          effect: "bide",
          source: move.name,
        });
        applied = true;
      } else if (bide.count < 2) {
        bide.count += 1;
        attacker.lockedMove = { id: "bide", slot };
        state.events.push({
          turn: state.turn,
          type: "volatile_activate",
          side: action.side,
          pokemon: attacker.name,
          effect: "bide",
          count: bide.count,
          stored: bide.stored ?? 0,
          source: move.name,
        });
        applied = true;
      } else {
        const damage = Math.max(0, Number(bide.stored ?? 0) * 2);
        delete attacker.volatiles.bide;
        attacker.lockedMove = null;
        state.events.push({
          turn: state.turn,
          type: "volatile_end",
          side: action.side,
          pokemon: attacker.name,
          effect: "bide",
          source: move.name,
          stored: bide.stored ?? 0,
        });
        if (damage > 0) {
          applyDirectDamage(state, defenderSide, defender, damage, move.name);
          applied = true;
        }
      }
    }
    if (cleanId(move.id) === "swallow") {
      handled = true;
      const count = Math.max(0, Number(attacker.volatiles?.stockpile?.count ?? 0));
      if (count > 0) {
        const divisor = count >= 3 ? 1 : count === 2 ? 2 : 4;
        applied =
          healPokemon(
            state,
            action.side,
            attacker,
            Math.max(1, Math.floor(attacker.stats.hp / divisor)),
            move.name,
          ) > 0 || applied;
        delete attacker.volatiles.stockpile;
        state.events.push({
          turn: state.turn,
          type: "volatile_end",
          side: action.side,
          pokemon: attacker.name,
          effect: "stockpile",
          source: move.name,
        });
      }
    }
    if (cleanId(move.id) === "followme" || cleanId(move.id) === "ragepowder") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          cleanId(move.id),
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "gastroacid") {
      handled = true;
      applied =
        suppressAbility(state, defenderSide, defender, move.name) || applied;
    }
    if (cleanId(move.id) === "disable") {
      handled = true;
      applied = applyDisable(state, defenderSide, defender, move.name) || applied;
    }
    if (cleanId(move.id) === "encore") {
      handled = true;
      applied = applyEncore(state, defenderSide, defender, move.name) || applied;
    }
    if (cleanId(move.id) === "spite") {
      handled = true;
      applied =
        reduceLastMovePp(state, defenderSide, defender, 4, move.name) || applied;
    }
    if (cleanId(move.id) === "strengthsap") {
      handled = true;
      applied =
        applyStrengthSap(state, action.side, attacker, defenderSide, defender, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "defog") {
      handled = true;
      applied = clearDefogEffects(state, action.side, defenderSide, attacker, move.name) || applied;
      applied =
        applyBoosts(state, defenderSide, defender, { evasion: -1 }, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "powerswap") {
      handled = true;
      applied =
        swapBoosts(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          ["attack", "specialAttack"],
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "guardswap") {
      handled = true;
      applied =
        swapBoosts(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          ["defence", "specialDefence"],
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "heartswap") {
      handled = true;
      applied =
        swapAllBoosts(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "guardsplit") {
      handled = true;
      applied =
        splitStats(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          ["defence", "specialDefence"],
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "powersplit") {
      handled = true;
      applied =
        splitStats(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          ["attack", "specialAttack"],
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "speedswap") {
      handled = true;
      applied =
        swapStats(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          ["speed"],
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "powershift") {
      handled = true;
      const previousAttack = attacker.stats.attack;
      attacker.stats.attack = attacker.stats.defence;
      attacker.stats.defence = previousAttack;
      attacker.volatiles.powershift = {
        id: "powershift",
        source: move.name,
      };
      state.events.push({
        turn: state.turn,
        type: "stats_swapped",
        side: action.side,
        pokemon: attacker.name,
        source: move.name,
        stats: ["attack", "defence"],
      });
      applied = true;
    }
    if (cleanId(move.id) === "powertrick") {
      handled = true;
      const previousAttack = attacker.stats.attack;
      attacker.stats.attack = attacker.stats.defence;
      attacker.stats.defence = previousAttack;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "powertrick",
          move.name,
        ) || true;
    }
    if (cleanId(move.id) === "trick" || cleanId(move.id) === "switcheroo") {
      handled = true;
      applied =
        swapHeldItems(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "recycle") {
      handled = true;
      applied = recycleHeldItem(state, action.side, attacker, move.name) || applied;
    }
    if (cleanId(move.id) === "healblock") {
      handled = true;
      applied =
        applyVolatileStatus(state, defenderSide, defender, "healblock", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "imprison") {
      handled = true;
      applied =
        applyVolatileStatus(state, action.side, attacker, "imprison", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "noretreat") {
      handled = true;
      applied = applyNoRetreat(state, action.side, attacker, move.name) || applied;
    }
    if (cleanId(move.id) === "aquaring") {
      handled = true;
      applied =
        applyVolatileStatus(state, action.side, attacker, "aquaring", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "helpinghand") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "helpinghand",
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "laserfocus") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "laserfocus",
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "minimize") {
      handled = true;
      applied =
        applyVolatileStatus(state, action.side, attacker, "minimize", move.name) ||
        applied;
      applied =
        applyBoosts(state, action.side, attacker, { evasion: 2 }, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "tarshot") {
      handled = true;
      applied =
        applyVolatileStatus(state, defenderSide, defender, "tarshot", move.name) ||
        applied;
      applied =
        applyBoosts(state, defenderSide, defender, { speed: -1 }, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "attract") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          "attract",
          move.name,
          action.side,
        ) || applied;
    }
    if (cleanId(move.id) === "electrify") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          "electrify",
          move.name,
          action.side,
        ) || applied;
    }
    if (cleanId(move.id) === "embargo") {
      handled = true;
      applied =
        applyVolatileStatus(state, defenderSide, defender, "embargo", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "grudge") {
      handled = true;
      applied =
        applyVolatileStatus(state, action.side, attacker, "grudge", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "nightmare") {
      handled = true;
      if (defender.status === "slp") {
        applied =
          applyVolatileStatus(state, defenderSide, defender, "nightmare", move.name) ||
          applied;
      }
    }
    if (cleanId(move.id) === "octolock") {
      handled = true;
      applied =
        applyVolatileStatus(state, defenderSide, defender, "octolock", move.name) ||
        applied;
    }
    if (["lockon", "mindreader"].includes(cleanId(move.id))) {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          cleanId(move.id),
          move.name,
          action.side,
        ) || applied;
    }
    if (["magiccoat", "snatch"].includes(cleanId(move.id))) {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          cleanId(move.id),
          move.name,
          action.side,
        ) || applied;
    }
    if (cleanId(move.id) === "spotlight") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          "spotlight",
          move.name,
          action.side,
        ) || applied;
    }
    if (cleanId(move.id) === "uproar") {
      handled = true;
      applied =
        applyVolatileStatus(state, action.side, attacker, "uproar", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "magicpowder") {
      handled = true;
      applied =
        setPokemonTypes(state, defenderSide, defender, ["Psychic"], move.name) ||
        applied;
    }
    if (cleanId(move.id) === "reflecttype") {
      handled = true;
      applied =
        setPokemonTypes(state, action.side, attacker, defender.types, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "conversion") {
      handled = true;
      const nextType =
        attacker.moves.find((candidate) => candidate.type)?.type ||
        attacker.types[0] ||
        "Normal";
      applied =
        setPokemonTypes(state, action.side, attacker, [nextType], move.name) ||
        applied;
    }
    if (cleanId(move.id) === "conversion2") {
      handled = true;
      const lastAttackType = defender.lastMove?.type || "Normal";
      const resistingType =
        Object.keys(TYPE_CHART).find(
          (type) => (TYPE_CHART[lastAttackType]?.[type] ?? 1) < 1,
        ) || "Normal";
      applied =
        setPokemonTypes(state, action.side, attacker, [resistingType], move.name) ||
        applied;
    }
    if (cleanId(move.id) === "camouflage") {
      handled = true;
      const terrain = cleanId(state.field?.terrain?.id);
      const type =
        terrain === "electricterrain"
          ? "Electric"
          : terrain === "grassyterrain"
            ? "Grass"
            : terrain === "mistyterrain"
              ? "Fairy"
              : terrain === "psychicterrain"
                ? "Psychic"
                : "Normal";
      applied =
        setPokemonTypes(state, action.side, attacker, [type], move.name) ||
        applied;
    }
    if (cleanId(move.id) === "psychup") {
      handled = true;
      attacker.boosts = { ...defender.boosts };
      state.events.push({
        turn: state.turn,
        type: "boosts_copied",
        side: action.side,
        pokemon: attacker.name,
        target: defender.name,
        source: move.name,
      });
      applied = true;
    }
    if (cleanId(move.id) === "acupressure") {
      handled = true;
      const stat = BOOST_STATS.find((candidate) => attacker.boosts[candidate] < 6);
      if (stat) {
        applied =
          applyBoosts(state, action.side, attacker, { [stat]: 2 }, move.name) ||
          applied;
      }
    }
    if (cleanId(move.id) === "autotomize") {
      handled = true;
      attacker.weightKg = Math.max(0.1, attacker.weightKg - 100);
      applied =
        applyBoosts(state, action.side, attacker, { speed: 2 }, move.name) ||
        applied;
      state.events.push({
        turn: state.turn,
        type: "weight_change",
        side: action.side,
        pokemon: attacker.name,
        weightKg: attacker.weightKg,
        source: move.name,
      });
      applied = true;
    }
    if (cleanId(move.id) === "courtchange") {
      handled = true;
      applied = swapSideConditions(state, action.side, defenderSide, move.name) || applied;
    }
    if (cleanId(move.id) === "corrosivegas") {
      handled = true;
      applied = removeTargetItem(state, action.side, attacker, move.name) || applied;
      applied = removeTargetItem(state, defenderSide, defender, move.name) || applied;
    }
    if (cleanId(move.id) === "topsyturvy") {
      handled = true;
      defender.boosts = Object.fromEntries(
        Object.entries(defender.boosts).map(([stat, amount]) => [
          stat,
          -Number(amount || 0),
        ]),
      );
      state.events.push({
        turn: state.turn,
        type: "boosts_inverted",
        side: defenderSide,
        pokemon: defender.name,
        source: move.name,
      });
      applied = true;
    }
    if (cleanId(move.id) === "simplebeam") {
      handled = true;
      applied =
        changeAbility(
          state,
          defenderSide,
          defender,
          "simple",
          move.name,
          "simplebeam",
        ) || applied;
    }
    if (cleanId(move.id) === "entrainment") {
      handled = true;
      applied =
        changeAbility(
          state,
          defenderSide,
          defender,
          activeAbility(attacker),
          move.name,
          "entrainment",
        ) || applied;
    }
    if (cleanId(move.id) === "skillswap") {
      handled = true;
      const leftAbility = attacker.ability;
      const rightAbility = defender.ability;
      if (leftAbility || rightAbility) {
        attacker.ability = rightAbility;
        defender.ability = leftAbility;
        state.events.push({
          turn: state.turn,
          type: "abilities_swapped",
          source: move.name,
          leftSide: action.side,
          leftPokemon: attacker.name,
          leftAbility: attacker.ability,
          rightSide: defenderSide,
          rightPokemon: defender.name,
          rightAbility: defender.ability,
        });
        applied = true;
      }
    }
    if (cleanId(move.id) === "painsplit") {
      handled = true;
      const shared = Math.floor((attacker.hp + defender.hp) / 2);
      attacker.hp = Math.max(1, Math.min(attacker.stats.hp, shared));
      defender.hp = Math.max(1, Math.min(defender.stats.hp, shared));
      state.events.push({
        turn: state.turn,
        type: "hp_shared",
        source: move.name,
        leftSide: action.side,
        leftPokemon: attacker.name,
        leftHp: attacker.hp,
        rightSide: defenderSide,
        rightPokemon: defender.name,
        rightHp: defender.hp,
      });
      applied = true;
    }
    if (cleanId(move.id) === "psychoshift") {
      handled = true;
      if (attacker.status && canReceiveStatus(defender, attacker.status, state, defenderSide, action.side)) {
        const shifted = attacker.status;
        applied =
          applyStatus(state, defenderSide, defender, shifted, rng, move.name, action.side) ||
          applied;
        attacker.status = "";
        attacker.statusTurns = 0;
        attacker.toxicCounter = 0;
        state.events.push({
          turn: state.turn,
          type: "status_cured",
          side: action.side,
          pokemon: attacker.name,
          status: shifted,
          source: move.name,
        });
      }
    }
    if (cleanId(move.id) === "purify") {
      handled = true;
      if (defender.status) {
        const status = defender.status;
        defender.status = "";
        defender.statusTurns = 0;
        defender.toxicCounter = 0;
        state.events.push({
          turn: state.turn,
          type: "status_cured",
          side: defenderSide,
          pokemon: defender.name,
          status,
          source: move.name,
        });
        applied =
          healPokemon(
            state,
            action.side,
            attacker,
            Math.max(1, Math.floor(attacker.stats.hp / 2)),
            move.name,
          ) > 0 || true;
      }
    }
    if (cleanId(move.id) === "takeheart") {
      handled = true;
      applied = curePokemonStatus(state, action.side, attacker, move.name) || applied;
      applied =
        applyBoosts(
          state,
          action.side,
          attacker,
          { specialAttack: 1, specialDefence: 1 },
          move.name,
        ) || applied;
    }
    if (["shoreup", "floralhealing"].includes(cleanId(move.id))) {
      handled = true;
      const healFraction =
        cleanId(move.id) === "shoreup" &&
        cleanId(state.field?.weather?.id) === "sandstorm"
          ? [2, 3]
          : cleanId(move.id) === "floralhealing" &&
              cleanId(state.field?.terrain?.id) === "grassyterrain"
            ? [2, 3]
            : [1, 2];
      applied =
        healPokemon(
          state,
          action.side,
          attacker,
          fractionAmount(attacker.stats.hp, healFraction),
          move.name,
        ) > 0 || applied;
    }
    if (["craftyshield", "matblock", "wideguard"].includes(cleanId(move.id))) {
      handled = true;
      applied =
        setSideCondition(state, action.side, attacker, cleanId(move.id), move.name) ||
        applied;
    }
    if (cleanId(move.id) === "batonpass") {
      handled = true;
      const passedBoosts = { ...attacker.boosts };
      const switched = executeSelfSwitch(state, action.side, move.name);
      if (switched) {
        activePokemon(state, action.side).boosts = passedBoosts;
        state.events.push({
          turn: state.turn,
          type: "boosts_passed",
          side: action.side,
          pokemon: activePokemon(state, action.side).name,
          source: move.name,
        });
        applied = true;
      }
    }
    if (["healingwish", "lunardance"].includes(cleanId(move.id))) {
      handled = true;
      state.sides[action.side].conditions[cleanId(move.id)] = {
        id: cleanId(move.id),
        turns: null,
        source: move.name,
      };
      state.events.push({
        turn: state.turn,
        type: "slot_condition_start",
        side: action.side,
        pokemon: attacker.name,
        effect: cleanId(move.id),
        source: move.name,
      });
      attacker.hp = 0;
      markFainted(state, action.side, attacker);
      applied = true;
    }
    if (cleanId(move.id) === "teleport") {
      handled = true;
      applied = executeSelfSwitch(state, action.side, move.name) || applied;
    }
    if (cleanId(move.id) === "miracleeye") {
      handled = true;
      applied =
        applyVolatileStatus(state, defenderSide, defender, "miracleeye", move.name) ||
        applied;
    }
    if (["foresight", "odorsleuth"].includes(cleanId(move.id))) {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          cleanId(move.id),
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "telekinesis") {
      handled = true;
      applied =
        applyVolatileStatus(state, defenderSide, defender, "telekinesis", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "worryseed") {
      handled = true;
      applied =
        changeAbility(
          state,
          defenderSide,
          defender,
          "insomnia",
          move.name,
          "worryseed",
        ) || applied;
    }
    if (cleanId(move.id) === "roleplay") {
      handled = true;
      applied =
        changeAbility(
          state,
          action.side,
          attacker,
          activeAbility(defender),
          move.name,
          "copied",
        ) || applied;
    }
    if (cleanId(move.id) === "transform") {
      handled = true;
      applied =
        applyTransform(state, action.side, attacker, defender, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "mimic") {
      handled = true;
      applied =
        applyMimic(state, action.side, attacker, defender, move.name, slot) ||
        applied;
    }
    if (cleanId(move.id) === "healpulse") {
      handled = true;
      applied = applyHealPulse(state, defenderSide, defender, move.name) || applied;
    }
    if (cleanId(move.id) === "wish") {
      handled = true;
      applied = setWish(state, action.side, attacker, move.name) || applied;
    }
    if (["aromatherapy", "healbell"].includes(cleanId(move.id))) {
      handled = true;
      applied = cureSideStatuses(state, action.side, move.name) || applied;
    }
    if (cleanId(move.id) === "refresh") {
      handled = true;
      applied =
        curePokemonStatus(state, action.side, attacker, move.name, [
          "brn",
          "par",
          "psn",
          "tox",
        ]) || applied;
    }
    if (cleanId(move.id) === "junglehealing") {
      handled = true;
      applied =
        curePokemonStatus(state, action.side, attacker, move.name) || applied;
      applied =
        healPokemon(
          state,
          action.side,
          attacker,
          Math.max(1, Math.floor(attacker.stats.hp / 4)),
          move.name,
        ) > 0 || applied;
    }
    if (cleanId(move.id) === "lunarblessing") {
      handled = true;
      applied =
        curePokemonStatus(state, action.side, attacker, move.name) || applied;
      applied =
        healPokemon(
          state,
          action.side,
          attacker,
          Math.max(1, Math.floor(attacker.stats.hp / 4)),
          move.name,
        ) > 0 || applied;
    }
    if (cleanId(move.id) === "teeterdance") {
      handled = true;
      applied =
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          "confusion",
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "soak") {
      handled = true;
      applied =
        setPokemonTypes(state, defenderSide, defender, ["Water"], move.name) ||
        applied;
    }
    if (cleanId(move.id) === "forestscurse") {
      handled = true;
      applied =
        addPokemonType(state, defenderSide, defender, "Grass", move.name) ||
        applied;
    }
    if (cleanId(move.id) === "trickortreat") {
      handled = true;
      applied =
        addPokemonType(state, defenderSide, defender, "Ghost", move.name) ||
        applied;
    }
    if (["celebrate", "happyhour"].includes(cleanId(move.id))) {
      handled = true;
      applied = true;
    }
    if (cleanId(move.id) === "venomdrench") {
      handled = true;
      if (["psn", "tox"].includes(defender.status)) {
        applied =
          applyBoosts(
          state,
          defenderSide,
          defender,
          { attack: -1, specialAttack: -1, speed: -1 },
          move.name,
          ) || applied;
      }
    }
    if (cleanId(move.id) === "leechseed") {
      handled = true;
      applied =
        applyLeechSeed(state, action.side, defenderSide, defender, move.name) ||
        applied;
    }
    if (cleanId(move.id) === "yawn") {
      handled = true;
      applied =
        applyYawn(state, action.side, defenderSide, defender, move.name) ||
        applied;
    }
    if (move.selfStatus) {
      handled = true;
      applied =
        applyStatus(
          state,
          action.side,
          attacker,
          move.selfStatus,
          rng,
          move.name,
          action.side,
        ) || applied;
    }
    if (move.status) {
      handled = true;
      applied =
        applyStatus(
          state,
          defenderSide,
          defender,
          move.status,
          rng,
          move.name,
          action.side,
        ) || applied;
    }
    if (Object.keys(move.boosts).length && cleanId(move.id) !== "venomdrench") {
      handled = true;
      applied =
        applyBoosts(
          state,
          defenderSide,
          defender,
          move.boosts,
          move.name,
        ) || applied;
    }
    if (Object.keys(move.selfBoosts).length) {
      handled = true;
      applied =
        applyBoosts(
          state,
          action.side,
          attacker,
          move.selfBoosts,
          move.name,
        ) || applied;
    }
    if (move.volatileStatus) {
      handled = true;
      const targetsSelf = move.target === "self";
      applied =
        applyVolatileStatus(
          state,
          targetsSelf ? action.side : defenderSide,
          targetsSelf ? attacker : defender,
          move.volatileStatus,
          move.name,
        ) || applied;
    }
    if (cleanId(move.id) === "defensecurl") {
      handled = true;
      attacker.volatiles.defensecurl = true;
      applied = true;
      state.events.push({
        turn: state.turn,
        type: "volatile_start",
        side: action.side,
        pokemon: attacker.name,
        effect: "defensecurl",
        source: move.name,
      });
    }
    if (cleanId(move.id) === "haze") {
      handled = true;
      applied = true;
      applied =
        resetBoosts(state, action.side, attacker, move.name) ||
        resetBoosts(state, defenderSide, defender, move.name) ||
        applied;
    }
    if (move.heal) {
      handled = true;
      applied =
        healPokemon(
          state,
          action.side,
          attacker,
          fractionAmount(attacker.stats.hp, move.heal),
          move.name,
        ) > 0 || applied;
    }
    if (move.weather) {
      handled = true;
      applied =
        setFieldEffect(
          state,
          action.side,
          attacker,
          "weather",
          move.weather,
          move.name,
        ) || applied;
    }
    if (move.terrain) {
      handled = true;
      applied =
        setFieldEffect(
          state,
          action.side,
          attacker,
          "terrain",
          move.terrain,
          move.name,
        ) || applied;
    }
    if (move.pseudoWeather) {
      handled = true;
      applied =
        setFieldEffect(
          state,
          action.side,
          attacker,
          "pseudoWeather",
          move.pseudoWeather,
          move.name,
        ) || applied;
    }
    if (move.sideCondition) {
      handled = true;
      if (
        cleanId(move.sideCondition) === "auroraveil" &&
        !["hail", "snow"].includes(cleanId(state.field?.weather?.id))
      ) {
        applied = false || applied;
      } else {
      const targetSide =
        move.target === "allySide" || move.target === "self"
          ? action.side
          : defenderSide;
      applied =
        setSideCondition(
          state,
          targetSide,
          attacker,
          move.sideCondition,
          move.name,
        ) || applied;
      }
    }
    if (move.forceSwitch) {
      handled = true;
      applied = executeForceSwitch(state, defenderSide, move.name) || applied;
    }
    if (!applied) {
      state.events.push({
        turn: state.turn,
        type: handled ? "move_failed" : "unsupported_effect",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        reason: handled
          ? "The move had no effect in the current battle state."
          : "This move effect is not in the native effect catalog yet.",
      });
    }
    if (applied && move.selfSwitch) {
      executeSelfSwitch(state, action.side, move.name);
    }
    return applied;
  }

  if (
    move.dynamicPower &&
    !SUPPORTED_DYNAMIC_POWER_MOVES.has(cleanId(move.id))
  ) {
    state.events.push({
      turn: state.turn,
      type: "unsupported_effect",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "This dynamic power formula is not implemented yet.",
    });
    return false;
  }
  if (move.dynamicDamage && fixedDamageAmount(move, attacker, defender) === null) {
    state.events.push({
      turn: state.turn,
      type: "unsupported_effect",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "This dynamic damage formula is not implemented yet.",
    });
    return false;
  }
  if (
    defender.dynamaxTurns > 0 &&
    DYNAMAX_BLOCKED_WEIGHT_MOVES.has(cleanId(move.id))
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Weight-based moves fail against Dynamaxed targets.",
    });
    return false;
  }

  const requestedHits = hitCountForMove(move, attacker, rng);
  let landedHits = 0;
  let totalDamage = 0;
  for (let hit = 1; hit <= requestedHits && defender.hp > 0; hit += 1) {
    if (
      move.multiaccuracy &&
      move.accuracy !== true &&
      rng.next() * 100 >= effectiveAccuracy(attacker, defender, move, state)
    ) {
      state.events.push({
        turn: state.turn,
        type: "miss",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        hit,
      });
      break;
    }
    const dynamicPower = resolveDynamicPower(move, {
      state,
      attackerSide: action.side,
      defenderSide,
      attacker,
      defender,
      attackerSpeed: effectiveSpeed(attacker, state, action.side),
      defenderSpeed: effectiveSpeed(defender, state, defenderSide),
      effectiveness: moveEffectiveness(move, defender.types),
      hit,
      rng,
    });
    const hitMove = dynamicPower.supported
      ? { ...move, power: dynamicPower.power }
      : move;
    const chargeBoosted =
      hit === 1 &&
      attacker.volatiles?.charge &&
      cleanId(hitMove.type) === "electric" &&
      hitMove.power > 0;
    const chargedHitMove = chargeBoosted
      ? { ...hitMove, power: hitMove.power * 2 }
      : hitMove;
    if (chargeBoosted) {
      delete attacker.volatiles.charge;
      state.events.push({
        turn: state.turn,
        type: "volatile_activate",
        side: action.side,
        pokemon: attacker.name,
        effect: "charge",
        move: move.name,
      });
    }
    if (hit === 1 && ["brickbreak", "psychicfangs"].includes(cleanId(move.id))) {
      breakProtectiveScreens(state, defenderSide, move.name, attacker);
    }
    if (
      move.dynamicPower ||
      (dynamicPower.supported &&
        (chargedHitMove.power !== move.power ||
          dynamicPower.reason !== "base_power"))
    ) {
      state.events.push({
        turn: state.turn,
        type: "dynamic_power",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        power: chargedHitMove.power,
        reason: dynamicPower.reason,
        hit,
      });
    }
    const fixedDamage = fixedDamageAmount(chargedHitMove, attacker, defender);
    const critRatio = move.critRatio + (attacker.volatiles?.focusenergy ? 2 : 0);
    const criticalChance =
      critRatio >= 3 ? 1 : critRatio === 2 ? 1 / 8 : 1 / 24;
    const critical =
      fixedDamage === null &&
      (move.willCrit || attacker.volatiles?.laserfocus || rng.next() < criticalChance);
    if (attacker.volatiles?.laserfocus && fixedDamage === null) {
      delete attacker.volatiles.laserfocus;
      state.events.push({
        turn: state.turn,
        type: "volatile_end",
        side: action.side,
        pokemon: attacker.name,
        effect: "laserfocus",
        source: move.name,
      });
    }
    const range = calculateDamageRange(attacker, defender, chargedHitMove, {
      state,
      attackerSide: action.side,
      defenderSide,
      critical,
    });
    const fixedEffectiveness =
      fixedDamage === null ? null : moveEffectiveness(chargedHitMove, defender.types);
    const randomFactor = fixedDamage === null ? 0.85 + rng.next() * 0.15 : 1;
    const criticalModifier = critical ? 1.5 : 1;
    let damage =
      fixedDamage !== null
        ? fixedEffectiveness === 0
          ? 0
          : Math.min(defender.hp, fixedDamage)
        : range.effectiveness === 0
        ? 0
        : Math.max(
            1,
            Math.min(
              defender.hp,
              Math.floor(
                damageBase(attacker, defender, chargedHitMove, {
                  critical,
                  state,
                  defenderSide,
                }) *
                  range.stab *
                  range.effectiveness *
                  range.itemModifier *
                  range.fieldModifier *
                  criticalModifier *
                  randomFactor,
              ),
            ),
          );
    if (
      damage >= defender.hp &&
      defender.hp > 1 &&
      (cleanId(move.id) === "falseswipe" || defender.volatiles?.endure)
    ) {
      damage = defender.hp - 1;
      state.events.push({
        turn: state.turn,
        type: "damage_prevented",
        side: defenderSide,
        pokemon: defender.name,
        source: cleanId(move.id) === "falseswipe" ? move.name : "endure",
        remainingHp: defender.hp,
      });
    }
    const hitEffectiveness =
      fixedDamage === null ? range.effectiveness : fixedEffectiveness;
    if (hitEffectiveness === 0) {
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: defenderSide,
        pokemon: defender.name,
        source: attacker.name,
        move: move.name,
        damage: 0,
        remainingHp: defender.hp,
        maximumHp: defender.stats.hp,
        stab: range.stab,
        effectiveness: 0,
        randomFactor: Math.round(randomFactor * 10_000) / 10_000,
        critical: false,
        hit,
        hits: requestedHits,
      });
      break;
    }
    let appliedDamage = damage;
    const substitute = defender.volatiles?.substitute;
    const substituteBlockedHit = Boolean(
      damage > 0 && substitute && move.target !== "self",
    );
    if (substituteBlockedHit) {
      appliedDamage = Math.min(damage, substitute.hp);
      substitute.hp -= appliedDamage;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: defenderSide,
        pokemon: defender.name,
        source: "substitute",
        cause: "substitute",
        move: move.name,
        damage: appliedDamage,
        remainingHp: defender.hp,
        maximumHp: defender.stats.hp,
        substituteHp: Math.max(0, substitute.hp),
        effectiveness: range.effectiveness,
        hit,
      });
      if (substitute.hp <= 0) {
        delete defender.volatiles.substitute;
        state.events.push({
          turn: state.turn,
          type: "volatile_end",
          side: defenderSide,
          pokemon: defender.name,
          effect: "substitute",
          source: move.name,
        });
      }
    } else {
      defender.hp -= damage;
      if (damage > 0) {
        defender.turnState.damageTaken += damage;
        defender.turnState.lastDamage = {
          amount: damage,
          category: move.category,
          move: move.name,
          source: attacker.name,
          sourceSide: action.side,
        };
        if (defender.volatiles?.bide) {
          defender.volatiles.bide.stored =
            Math.max(0, Number(defender.volatiles.bide.stored ?? 0)) + damage;
        }
        defender.timesHit += 1;
      }
    }
    landedHits += 1;
    totalDamage += appliedDamage;
    if (critical && damage > 0) {
      state.events.push({
        turn: state.turn,
        type: "critical",
        side: defenderSide,
        pokemon: defender.name,
        source: attacker.name,
        move: move.name,
        hit,
      });
    }
    if (!substituteBlockedHit) {
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: defenderSide,
        pokemon: defender.name,
        source: attacker.name,
        move: move.name,
        damage,
        remainingHp: defender.hp,
        maximumHp: defender.stats.hp,
        stab: range.stab,
        effectiveness:
          fixedDamage === null ? range.effectiveness : fixedEffectiveness,
        randomFactor: Math.round(randomFactor * 10_000) / 10_000,
        critical,
        hit,
        hits: requestedHits,
      });
    }
  }
  if (requestedHits > 1 && landedHits > 0) {
    state.events.push({
      turn: state.turn,
      type: "multi_hit",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      hits: landedHits,
      damage: totalDamage,
    });
  }
  if (totalDamage > 0 && move.drain) {
    healPokemon(
      state,
      action.side,
      attacker,
      fractionAmount(totalDamage, move.drain),
      move.name,
    );
  }
  if (totalDamage > 0 && move.recoil && !attacker.fainted) {
    const recoil = Math.min(
      attacker.hp,
      fractionAmount(totalDamage, move.recoil),
    );
    attacker.hp -= recoil;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: action.side,
      pokemon: attacker.name,
      source: move.name,
      cause: "recoil",
      damage: recoil,
      remainingHp: attacker.hp,
      maximumHp: attacker.stats.hp,
      effectiveness: 1,
    });
  }
  if (totalDamage > 0 && attacker.item === "lifeorb" && !attacker.fainted) {
    const recoil = Math.min(attacker.hp, Math.max(1, Math.floor(attacker.stats.hp / 10)));
    attacker.hp -= recoil;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: action.side,
      pokemon: attacker.name,
      source: "Life Orb",
      cause: "item",
      damage: recoil,
      remainingHp: attacker.hp,
      maximumHp: attacker.stats.hp,
      effectiveness: 1,
    });
  }
  if (
    landedHits > 0 &&
    ["mindblown", "steelbeam"].includes(cleanId(move.id)) &&
    !attacker.fainted
  ) {
    applySelfHpCost(state, action.side, attacker, move.name, [1, 2]);
  }
  if (landedHits > 0 && cleanId(move.id) === "finalgambit" && !attacker.fainted) {
    attacker.hp = 0;
    markFainted(state, action.side, attacker);
  }
  if (
    landedHits > 0 &&
    SELF_DESTRUCT_MOVES.has(cleanId(move.id)) &&
    !attacker.fainted
  ) {
    const selfDamage = attacker.hp;
    attacker.hp = 0;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: action.side,
      pokemon: attacker.name,
      source: move.name,
      cause: "self_destruct",
      damage: selfDamage,
      remainingHp: 0,
      maximumHp: attacker.stats.hp,
      effectiveness: 1,
    });
    markFainted(state, action.side, attacker);
  }
  const curedStatus =
    totalDamage > 0 ? resolveDynamicPostHit(move, defender) : "";
  if (curedStatus) {
    defender.status = "";
    defender.statusTurns = 0;
    defender.toxicCounter = 0;
    state.events.push({
      turn: state.turn,
      type: "status_cured",
      side: defenderSide,
      pokemon: defender.name,
      status: curedStatus,
      source: move.name,
    });
  }
  if (landedHits > 0 && cleanId(move.id) === "clearsmog") {
    resetBoosts(state, defenderSide, defender, move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "hyperspacefury") {
    applyBoosts(state, action.side, attacker, { defence: -1 }, move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "fakeout") {
    applyVolatileStatus(state, defenderSide, defender, "flinch", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "upperhand") {
    applyVolatileStatus(state, defenderSide, defender, "flinch", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "saltcure" && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "saltcure", move.name);
  }
  if (
    landedHits > 0 &&
    move.volatileStatus &&
    BINDING_VOLATILES.has(cleanId(move.volatileStatus)) &&
    !defender.fainted
  ) {
    applyVolatileStatus(
      state,
      defenderSide,
      defender,
      move.volatileStatus,
      move.name,
    );
  }
  if (landedHits > 0 && cleanId(move.id) === "thousandwaves" && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "thousandwaves", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "jawlock" && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "jawlock", move.name);
    applyVolatileStatus(state, action.side, attacker, "jawlock", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "rapidspin") {
    removeHazardsAndTerrain(state, action.side, move.name, attacker);
  }
  if (landedHits > 0 && cleanId(move.id) === "ceaselessedge") {
    setSideCondition(state, defenderSide, attacker, "spikes", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "stoneaxe") {
    setSideCondition(state, defenderSide, attacker, "stealthrock", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "doubleshock") {
    const nextTypes = attacker.types.filter((type) => type !== "Electric");
    setPokemonTypes(
      state,
      action.side,
      attacker,
      nextTypes.length ? nextTypes : ["Normal"],
      move.name,
    );
  }
  if (landedHits > 0 && cleanId(move.id) === "burnup") {
    const nextTypes = attacker.types.filter((type) => type !== "Fire");
    setPokemonTypes(
      state,
      action.side,
      attacker,
      nextTypes.length ? nextTypes : ["Normal"],
      move.name,
    );
  }
  if (landedHits > 0 && cleanId(move.id) === "smackdown" && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "smackdown", move.name);
  }
  if (
    landedHits > 0 &&
    cleanId(move.id) === "thousandarrows" &&
    !defender.fainted
  ) {
    applyVolatileStatus(state, defenderSide, defender, "smackdown", move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "sappyseed" && !defender.fainted) {
    applyLeechSeed(state, action.side, defenderSide, defender, move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "gmaxsnooze" && !defender.fainted) {
    applyYawn(state, action.side, defenderSide, defender, move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "orderup" && !attacker.fainted) {
    applyBoosts(state, action.side, attacker, { attack: 1 }, move.name);
  }
  if (
    landedHits > 0 &&
    ["relicsong", "polarflare"].includes(cleanId(move.id)) &&
    !attacker.fainted
  ) {
    state.events.push({
      turn: state.turn,
      type: "form_hint",
      side: action.side,
      pokemon: attacker.name,
      source: move.name,
      note: "native-engine-form-change-placeholder",
    });
  }
  if (landedHits > 0 && cleanId(move.id) === "fling" && attacker.item) {
    removeTargetItem(state, action.side, attacker, move.name);
  }
  if (
    landedHits > 0 &&
    cleanId(move.id) === "naturalgift" &&
    cleanId(attacker.item).endsWith("berry")
  ) {
    removeTargetItem(state, action.side, attacker, move.name);
  }
  if (
    landedHits > 0 &&
    cleanId(move.id) === "coreenforcer" &&
    defender.turnState?.acted &&
    !defender.fainted
  ) {
    suppressAbility(state, defenderSide, defender, move.name);
  }
  if (landedHits > 0 && cleanId(move.id) === "spitup") {
    delete attacker.volatiles.stockpile;
    state.events.push({
      turn: state.turn,
      type: "volatile_end",
      side: action.side,
      pokemon: attacker.name,
      effect: "stockpile",
      source: move.name,
    });
  }
  if (landedHits > 0 && cleanId(move.id) === "icespinner") {
    for (const terrain of ["electricterrain", "grassyterrain", "mistyterrain", "psychicterrain"]) {
      if (cleanId(state.field?.terrain?.id) !== terrain) continue;
      state.events.push({
        turn: state.turn,
        type: "field_end",
        fieldKind: "terrain",
        effect: terrain,
        source: move.name,
      });
      state.field.terrain = null;
      break;
    }
  }
  if (landedHits > 0 && ["steelroller", "freezyfrost"].includes(cleanId(move.id))) {
    if (state.field?.terrain) {
      state.events.push({
        turn: state.turn,
        type: "field_end",
        fieldKind: "terrain",
        effect: state.field.terrain.id,
        source: move.name,
      });
      state.field.terrain = null;
    }
    if (cleanId(move.id) === "freezyfrost") {
      resetBoosts(state, action.side, attacker, move.name);
      resetBoosts(state, defenderSide, defender, move.name);
    }
  }
  if (landedHits > 0 && cleanId(move.id) === "mortalspin") {
    removeHazardsAndTerrain(state, action.side, move.name, attacker);
    if (!defender.fainted) {
      applyStatus(state, defenderSide, defender, "psn", rng, move.name, action.side);
    }
  }
  if (landedHits > 0 && cleanId(move.id) === "knockoff" && defender.item) {
    removeTargetItem(state, defenderSide, defender, move.name);
  }
  if (
    landedHits > 0 &&
    ["covet", "thief"].includes(cleanId(move.id))
  ) {
    stealTargetItem(state, action.side, attacker, defenderSide, defender, move.name);
  }
  if (
    landedHits > 0 &&
    ["bugbite", "incinerate"].includes(cleanId(move.id)) &&
    isConsumableBattleItem(defender.item)
  ) {
    removeTargetItem(state, defenderSide, defender, move.name);
  }
  if (
    landedHits > 0 &&
    cleanId(move.id) === "pluck" &&
    isConsumableBattleItem(defender.item)
  ) {
    removeTargetItem(state, defenderSide, defender, move.name);
  }
  for (const secondary of move.secondaries) {
    if (defender.hp <= 0) break;
    if (rng.next() * 100 >= secondary.chance) continue;
    applyMoveEffect(
      state,
      action.side,
      attacker,
      defenderSide,
      defender,
      secondary,
      rng,
      move.name,
    );
  }
  if (!defender.fainted && Object.keys(move.selfBoosts).length) {
    applyBoosts(
      state,
      action.side,
      attacker,
      move.selfBoosts,
      move.name,
    );
  }
  const defenderHadDestinyBond = Boolean(defender.volatiles?.destinybond);
  const defenderHadGrudge = Boolean(defender.volatiles?.grudge);
  const defenderFainted = markFainted(state, defenderSide, defender);
  if (defenderFainted && cleanId(move.id) === "fellstinger" && !attacker.fainted) {
    applyBoosts(state, action.side, attacker, { attack: 3 }, move.name);
  }
  if (
    defenderFainted &&
    defenderHadDestinyBond &&
    !attacker.fainted &&
    totalDamage > 0
  ) {
    attacker.hp = 0;
    state.events.push({
      turn: state.turn,
      type: "volatile_activate",
      side: defenderSide,
      pokemon: defender.name,
      effect: "destinybond",
      target: attacker.name,
      source: move.name,
    });
    markFainted(state, action.side, attacker);
  }
  if (defenderFainted && defenderHadGrudge && sourceMove?.pp >= 0) {
    sourceMove.pp = 0;
    state.events.push({
      turn: state.turn,
      type: "pp_depleted",
      side: action.side,
      pokemon: attacker.name,
      move: sourceMove.name,
      moveId: sourceMove.id,
      source: "Grudge",
    });
  }
  markFainted(state, action.side, attacker);
  if (totalDamage > 0 && move.forceSwitch) {
    executeForceSwitch(state, defenderSide, move.name);
  }
  if (totalDamage > 0 && move.selfSwitch) {
    executeSelfSwitch(state, action.side, move.name);
  }
  return totalDamage > 0;
}

function bestFaintReplacement(state, sideIndex) {
  const side = state.sides[sideIndex];
  const opponent = activePokemon(state, sideIndex === 0 ? 1 : 0);
  const selected = selectAiSwitchCandidate(
    side.team
    .map((pokemon, index) => {
      if (pokemon.fainted) return null;
      const bestDamage = pokemon.moves.reduce((best, move) => {
        const range = calculateDamageRange(pokemon, opponent, move);
        const accuracy = move.accuracy === true ? 1 : move.accuracy / 100;
        return Math.max(best, ((range.minimum + range.maximum) / 2) * accuracy);
      }, 0);
      return {
        slot: index + 1,
        name: pokemon.name,
        expectedDamage: bestDamage,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        ...fieldSwitchSynergy(state, sideIndex, pokemon, opponent),
      };
    })
    .filter(Boolean),
    {
      difficulty: "expert",
      strategy: "balanced",
      rng: createAiRng(state.seed, sideIndex, state.turn * 31),
    },
  );
  return selected ? selected.slot - 1 : undefined;
}

function advanceFaintedSides(state) {
  for (let sideIndex = 0; sideIndex < state.sides.length; sideIndex += 1) {
    const side = state.sides[sideIndex];
    if (!side.team[side.active].fainted) continue;
    if (state.manualFaintSwitchSides.includes(sideIndex)) continue;
    const next = bestFaintReplacement(state, sideIndex);
    if (next >= 0) {
      side.active = next;
      state.events.push({
        turn: state.turn,
        type: "switch",
        side: sideIndex,
        pokemon: side.team[next].name,
        slot: next + 1,
        remainingHp: side.team[next].hp,
        maximumHp: side.team[next].stats.hp,
        status: side.team[next].status,
        automatic: true,
        selection: "matchup_score",
      });
      applyEntryHazards(state, sideIndex, side.team[next]);
    }
  }
  const defeated = state.sides
    .map((side, index) => ({
      index,
      defeated: side.team.every((pokemon) => pokemon.fainted),
    }))
    .filter((entry) => entry.defeated);
  if (defeated.length === 0) return;
  state.status = defeated.length === 2 ? "tie" : "completed";
  state.winner =
    defeated.length === 2
      ? null
      : state.sides[defeated[0].index === 0 ? 1 : 0].name;
  state.events.push({
    turn: state.turn,
    type: state.status === "tie" ? "tie" : "win",
    winner: state.winner,
  });
}

export function replaceFaintedPokemon(previousState, sideIndex, switchSlot) {
  if (previousState.status !== "running") {
    throw new Error("The battle is already finished");
  }
  const state = clone(previousState);
  const side = state.sides[sideIndex];
  if (!side) throw new Error(`Side ${sideIndex + 1} does not exist`);
  if (!side.team[side.active].fainted) {
    throw new Error(`Side ${sideIndex + 1} does not require a replacement`);
  }
  const targetIndex = Number(switchSlot) - 1;
  const target = side.team[targetIndex];
  if (!target || target.fainted || targetIndex === side.active) {
    throw new Error(`Side ${sideIndex + 1} cannot switch to slot ${switchSlot}`);
  }
  side.active = targetIndex;
  target.activeTurns = 0;
  state.events.push({
    turn: state.turn,
    type: "switch",
    side: sideIndex,
    pokemon: target.name,
    slot: Number(switchSlot),
    remainingHp: target.hp,
    maximumHp: target.stats.hp,
    status: target.status,
    automatic: false,
    forced: true,
  });
  applyEntryHazards(state, sideIndex, target);
  applyEntryAbilities(state, sideIndex, target);
  advanceFaintedSides(state);
  return state;
}

function applyFutureAttacks(state) {
  if (!Array.isArray(state.futureAttacks) || state.futureAttacks.length === 0) {
    return;
  }
  const remaining = [];
  for (const entry of state.futureAttacks) {
    entry.turns -= 1;
    if (entry.turns > 0) {
      remaining.push(entry);
      continue;
    }
    const target = activePokemon(state, entry.targetSide);
    if (!target || target.fainted) continue;
    const sourceSide = state.sides[entry.sourceSide] ? entry.sourceSide : entry.targetSide;
    const source =
      state.sides[sourceSide].team.find(
        (pokemon) => pokemon.name === entry.sourcePokemon,
      ) ?? activePokemon(state, sourceSide);
    const attackMove = {
      accuracy: true,
      critRatio: 1,
      drain: null,
      recoil: null,
      secondaries: [],
      selfBoosts: {},
      boosts: {},
      ...entry.move,
    };
    const range = calculateDamageRange(source, target, attackMove, {
      state,
      attackerSide: sourceSide,
      defenderSide: entry.targetSide,
    });
    const damage =
      range.effectiveness === 0
        ? 0
        : Math.max(
            1,
            Math.min(
              target.hp,
              Math.floor(
                damageBase(source, target, attackMove, {
                  state,
                  defenderSide: entry.targetSide,
                }) *
                  range.stab *
                  range.effectiveness *
                  range.itemModifier *
                  range.fieldModifier,
              ),
            ),
          );
    state.events.push({
      turn: state.turn,
      type: "future_attack",
      side: entry.targetSide,
      pokemon: target.name,
      source: entry.source,
      sourcePokemon: entry.sourcePokemon,
      damage,
      effectiveness: range.effectiveness,
    });
    applyDirectDamage(state, entry.targetSide, target, damage, entry.source, "future_attack");
  }
  state.futureAttacks = remaining;
}

function applyEndTurnEffects(state) {
  applyFutureAttacks(state);
  for (const [sideIndex, side] of state.sides.entries()) {
    const pokemon = side.team[side.active];
    if (pokemon.fainted) continue;
    const wish = side.conditions.wish;
    if (wish?.turns === 1) {
      healPokemon(
        state,
        sideIndex,
        pokemon,
        wish.heal,
        wish.source || "Wish",
      );
      delete side.conditions.wish;
      state.events.push({
        turn: state.turn,
        type: "slot_condition_end",
        side: sideIndex,
        pokemon: pokemon.name,
        effect: "wish",
        source: wish.source || "Wish",
      });
    }
    let damage = 0;
    let source = "";
    if (pokemon.status === "brn") {
      damage = Math.max(1, Math.floor(pokemon.stats.hp / 16));
      source = "brn";
    } else if (pokemon.status === "psn") {
      damage = Math.max(1, Math.floor(pokemon.stats.hp / 8));
      source = "psn";
    } else if (pokemon.status === "tox") {
      damage = Math.max(
        1,
        Math.floor(
          (pokemon.stats.hp * Math.max(1, pokemon.toxicCounter)) / 16,
        ),
      );
      pokemon.toxicCounter = Math.min(15, pokemon.toxicCounter + 1);
      source = "tox";
    }
    if (damage > 0) {
      const applied = Math.min(pokemon.hp, damage);
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source,
        cause: "status",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    const weather = cleanId(state.field?.weather?.id);
    if (
      weather === "sandstorm" &&
      !["Rock", "Ground", "Steel"].some((type) =>
        pokemon.types.includes(type),
      ) &&
      !["magicguard", "overcoat", "sandforce", "sandrush", "sandveil"].includes(
        activeAbility(pokemon),
      )
    ) {
      const applied = Math.min(
        pokemon.hp,
        Math.max(1, Math.floor(pokemon.stats.hp / 16)),
      );
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: "sandstorm",
        cause: "weather",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    if (
      cleanId(state.field?.terrain?.id) === "grassyterrain" &&
      isGrounded(pokemon)
    ) {
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Math.max(1, Math.floor(pokemon.stats.hp / 16)),
        "grassyterrain",
      );
    }
    if (pokemon.volatiles?.ingrain) {
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Math.max(1, Math.floor(pokemon.stats.hp / 16)),
        pokemon.volatiles.ingrain.source || "Ingrain",
      );
    }
    if (pokemon.volatiles?.aquaring) {
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Math.max(1, Math.floor(pokemon.stats.hp / 16)),
        pokemon.volatiles.aquaring.source || "Aqua Ring",
      );
    }
    if (pokemon.volatiles?.leechseed) {
      const applied = Math.min(
        pokemon.hp,
        Math.max(1, Math.floor(pokemon.stats.hp / 8)),
      );
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: "Leech Seed",
        cause: "volatile",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      const sourceSide = pokemon.volatiles.leechseed.sourceSide;
      const leecher =
        Number.isInteger(sourceSide) && state.sides[sourceSide]
          ? activePokemon(state, sourceSide)
          : null;
      if (leecher && !leecher.fainted) {
        healPokemon(
          state,
          sourceSide,
          leecher,
          applied,
          pokemon.volatiles.leechseed.source || "Leech Seed",
        );
      }
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    if (pokemon.volatiles?.curse) {
      const applied = Math.min(
        pokemon.hp,
        Math.max(1, Math.floor(pokemon.stats.hp / 4)),
      );
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: "Curse",
        cause: "volatile",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    if (pokemon.volatiles?.nightmare && pokemon.status === "slp") {
      const applied = Math.min(
        pokemon.hp,
        Math.max(1, Math.floor(pokemon.stats.hp / 4)),
      );
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: pokemon.volatiles.nightmare.source || "Nightmare",
        cause: "volatile",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    if (pokemon.volatiles?.saltcure) {
      const saltCureDivisor = pokemon.types.some((type) =>
        ["Water", "Steel"].includes(type),
      )
        ? 4
        : 8;
      const applied = Math.min(
        pokemon.hp,
        Math.max(1, Math.floor(pokemon.stats.hp / saltCureDivisor)),
      );
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: pokemon.volatiles.saltcure.source || "Salt Cure",
        cause: "volatile",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    if (pokemon.volatiles?.octolock) {
      applyBoosts(
        state,
        sideIndex,
        pokemon,
        { defence: -1, specialDefence: -1 },
        pokemon.volatiles.octolock.source || "Octolock",
      );
    }
    if (pokemon.volatiles?.perishsong) {
      const perish = pokemon.volatiles.perishsong;
      perish.count = Number.isFinite(perish.count) ? perish.count - 1 : 3;
      state.events.push({
        turn: state.turn,
        type: "volatile_activate",
        side: sideIndex,
        pokemon: pokemon.name,
        effect: "perishsong",
        count: perish.count,
        source: perish.source || "Perish Song",
      });
      if (perish.count <= 0) {
        pokemon.hp = 0;
        if (markFainted(state, sideIndex, pokemon)) continue;
      }
    }
    const binding = Object.values(pokemon.volatiles ?? {}).find((volatile) =>
      BINDING_VOLATILES.has(cleanId(volatile?.id)),
    );
    if (binding) {
      const applied = Math.min(
        pokemon.hp,
        Math.max(1, Math.floor(pokemon.stats.hp / 8)),
      );
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: binding.source || binding.id,
        cause: "volatile",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (markFainted(state, sideIndex, pokemon)) continue;
    }
    if (pokemon.item === "leftovers") {
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Math.max(1, Math.floor(pokemon.stats.hp / 16)),
        "Leftovers",
      );
    } else if (
      pokemon.item === "blacksludge" &&
      pokemon.types.includes("Poison")
    ) {
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Math.max(1, Math.floor(pokemon.stats.hp / 16)),
        "Black Sludge",
      );
    }
  }
}

function advanceTimedEffects(state, rng) {
  for (const kind of ["weather", "terrain"]) {
    const effect = state.field[kind];
    if (!effect) continue;
    effect.turns -= 1;
    if (effect.turns > 0) continue;
    state.events.push({
      turn: state.turn,
      type: "field_end",
      fieldKind: kind,
      effect: effect.id,
    });
    state.field[kind] = null;
  }
  for (const [id, effect] of Object.entries(state.field.pseudoWeather)) {
    effect.turns -= 1;
    if (effect.turns > 0) continue;
    state.events.push({
      turn: state.turn,
      type: "field_end",
      fieldKind: "pseudoWeather",
      effect: id,
    });
    delete state.field.pseudoWeather[id];
  }
  for (const [sideIndex, side] of state.sides.entries()) {
    for (const [id, condition] of Object.entries(side.conditions)) {
      if (!Number.isFinite(condition.turns)) continue;
      condition.turns -= 1;
      if (condition.turns > 0) continue;
      state.events.push({
        turn: state.turn,
        type: "side_condition_end",
        side: sideIndex,
        effect: id,
      });
      delete side.conditions[id];
    }
    for (const pokemon of side.team) {
      for (const [id, volatile] of Object.entries(pokemon.volatiles ?? {})) {
        if (!Number.isFinite(volatile?.turns)) continue;
        volatile.turns -= 1;
        if (volatile.turns > 0) continue;
        if (id === "yawn") {
          applyStatus(
            state,
            sideIndex,
            pokemon,
            "slp",
            rng,
            volatile.source || "Yawn",
            volatile.sourceSide,
          );
        }
        delete pokemon.volatiles[id];
        state.events.push({
          turn: state.turn,
          type: "volatile_end",
          side: sideIndex,
          pokemon: pokemon.name,
          effect: id,
        });
      }
    }
  }
}

function endDynamax(state, sideIndex, pokemon, reason) {
  if (
    pokemon.dynamaxTurns <= 0 &&
    pokemon.stats.hp === pokemon.baseMaximumHp
  ) {
    return false;
  }
  const previousMaximum = pokemon.stats.hp;
  pokemon.stats.hp = pokemon.baseMaximumHp;
  pokemon.hp =
    pokemon.hp <= 0
      ? 0
      : Math.min(
          pokemon.stats.hp,
          Math.max(
            1,
            Math.ceil((pokemon.hp / previousMaximum) * pokemon.stats.hp),
          ),
        );
  const dynamaxMode = pokemon.dynamaxMode;
  pokemon.dynamaxTurns = 0;
  pokemon.dynamaxMode = null;
  state.events.push({
    turn: state.turn,
    type: "dynamax_end",
    side: sideIndex,
    pokemon: pokemon.name,
    reason,
    dynamaxMode,
  });
  return true;
}

function expireDynamax(state) {
  for (const [sideIndex, side] of state.sides.entries()) {
    for (const pokemon of side.team) {
      if (pokemon.dynamaxTurns <= 0) continue;
      pokemon.dynamaxTurns -= 1;
      if (pokemon.dynamaxTurns > 0) continue;
      endDynamax(state, sideIndex, pokemon, "duration");
    }
  }
}

export function resolveSimpleTurn(previousState, commands) {
  if (previousState.status !== "running") {
    throw new Error("The battle is already finished");
  }
  if (!Array.isArray(commands) || commands.length !== 2) {
    throw new Error("Exactly two commands are required");
  }
  const state = clone(previousState);
  const hasRngState = state.rngState !== null && state.rngState !== undefined;
  const rng = createRng(
    hasRngState ? state.rngState : state.seed,
    hasRngState,
  );
  state.turn += 1;
  for (const side of state.sides) {
    for (const pokemon of side.team) {
      pokemon.turnState = {
        acted: false,
        damageTaken: 0,
        lastDamage: null,
        statsLowered: false,
      };
    }
  }
  state.events.push({ turn: state.turn, type: "turn" });
  const actions = prepareActionOrder(state, commands, rng);
  state.currentActions = actions;
  for (const action of actions) {
    if (action.kind === "switch") {
      executeSwitch(state, action);
    } else {
      const actedPokemon = activePokemon(state, action.side);
      const succeeded = executeMove(state, action, rng);
      recordMoveResult(
        state,
        action.side,
        actedPokemon,
        action.selected?.move,
        action.selected?.slot,
        succeeded,
        rng,
      );
      releaseGimmick(state, action, "action_not_executed");
    }
    activePokemon(state, action.side).turnState.acted = true;
  }
  delete state.currentActions;
  delete state.turnMoves;
  if (state.strictMoveEffectValidation) {
    const unsupported = state.events.find(
      (event) => event.turn === state.turn && event.type === "unsupported_effect",
    );
    if (unsupported) {
      throw new Error(
        `Unsupported move effect in cobbleverse-simple strict validation: ${unsupported.move} - ${unsupported.reason}`,
      );
    }
  }
  applyEndTurnEffects(state);
  advanceTimedEffects(state, rng);
  expireDynamax(state);
  for (const side of state.sides) {
    const pokemon = side.team[side.active];
    if (!pokemon?.fainted) {
      pokemon.activeTurns = Math.max(0, Number(pokemon.activeTurns ?? 0)) + 1;
    }
  }
  advanceFaintedSides(state);
  state.rngState = rng.snapshot();
  return state;
}

function strongestMovePower(pokemon) {
  return Math.max(
    0,
    ...pokemon.moves
      .filter((move) => move.category !== "Status")
      .map((move) => Number(move.power ?? 0)),
  );
}

function offensiveStat(pokemon) {
  return Math.max(
    effectiveStat(pokemon, "attack"),
    effectiveStat(pokemon, "specialAttack"),
  );
}

function trickRoomThreatValue(state, sideIndex, pokemon, opposingSpeed) {
  if (!pokemon || pokemon.fainted || pokemon.hp <= 0) return 0;
  const speed = effectiveSpeed(pokemon, state, sideIndex);
  const role = analyzeTeamProfile([pokemon]).roles[0];
  const aceScore = Math.max(
    Number(role?.roleScores?.ace ?? 0),
    Number(role?.roleScores?.setupSweeper ?? 0),
  );
  const offense = offensiveStat(pokemon);
  const power = strongestMovePower(pokemon);
  const slowBonus = speed < opposingSpeed ? 1 : speed <= 70 ? 0.6 : 0;
  if (slowBonus <= 0) return 0;
  return slowBonus * (aceScore + offense / 80 + power / 70);
}

function averageAliveSpeed(state, sideIndex) {
  const alive = state.sides[sideIndex].team.filter(
    (pokemon) => !pokemon.fainted && pokemon.hp > 0,
  );
  if (alive.length === 0) return 0;
  return (
    alive.reduce(
      (sum, pokemon) => sum + effectiveSpeed(pokemon, state, sideIndex),
      0,
    ) / alive.length
  );
}

function trickRoomContext(
  state,
  sideIndex,
  defenderSide,
  pokemon,
  defender,
  incomingDamageRatio,
) {
  const activeSpeed = effectiveSpeed(pokemon, state, sideIndex);
  const defenderSpeed = effectiveSpeed(defender, state, defenderSide);
  const ownThreatValue = state.sides[sideIndex].team.reduce(
    (sum, member) =>
      sum + trickRoomThreatValue(state, sideIndex, member, defenderSpeed),
    0,
  );
  const opponentThreatValue = state.sides[defenderSide].team.reduce(
    (sum, member) =>
      sum + trickRoomThreatValue(state, defenderSide, member, activeSpeed),
    0,
  );
  const slowAceCount = state.sides[sideIndex].team.filter(
    (member) =>
      trickRoomThreatValue(state, sideIndex, member, defenderSpeed) >= 3.5,
  ).length;
  return {
    trickRoomActive: Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0,
    trickRoomAdvantage:
      Math.round((ownThreatValue - opponentThreatValue) * 100) / 100,
    slowAceCount,
    activeIsSlower: activeSpeed < defenderSpeed,
    activeIsFaster: activeSpeed > defenderSpeed,
    canSurviveToSetRoom: incomingDamageRatio < 1,
    ownAverageSpeed: Math.round(averageAliveSpeed(state, sideIndex) * 100) / 100,
    opponentAverageSpeed:
      Math.round(averageAliveSpeed(state, defenderSide) * 100) / 100,
  };
}

function hasMoveType(pokemon, type) {
  const wanted = cleanId(type);
  return pokemon.moves.some(
    (move) => move.category !== "Status" && cleanId(move.type) === wanted,
  );
}

function fieldSwitchSynergy(state, sideIndex, pokemon, opponent) {
  const weather = cleanId(state.field?.weather?.id);
  const terrain = cleanId(state.field?.terrain?.id);
  const trickRoomActive =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  const opponentSpeed = effectiveSpeed(opponent, state, sideIndex === 0 ? 1 : 0);
  const speed = effectiveSpeed(pokemon, state, sideIndex);
  let value = 0;
  const reasons = [];

  if (trickRoomActive) {
    const threat = trickRoomThreatValue(state, sideIndex, pokemon, opponentSpeed);
    if (threat >= 3.5) {
      value += Math.min(55, 18 + threat * 8);
      reasons.push("트릭룸에서 느린 공격 자원이 선공권을 얻습니다.");
    } else if (speed > opponentSpeed) {
      value -= 18;
      reasons.push("트릭룸에서는 빠른 포켓몬의 선공권 가치가 낮아집니다.");
    }
  }

  if (["raindance", "primordialsea"].includes(weather)) {
    if (cleanId(pokemon.ability) === "swiftswim") {
      value += 34;
      reasons.push("비에서 쓱쓱으로 스피드가 크게 올라갑니다.");
    }
    if (["raindish", "dryskin"].includes(cleanId(pokemon.ability))) {
      value += 14;
      reasons.push("비에서 회복/유지력 이득을 얻습니다.");
    }
    if (pokemon.types.includes("Water") || hasMoveType(pokemon, "Water")) {
      value += 16;
      reasons.push("비에서 물 타입 공격 압박이 강해집니다.");
    }
    if (pokemon.types.includes("Fire") || hasMoveType(pokemon, "Fire")) {
      value -= 10;
      reasons.push("비에서 불꽃 타입 공격 가치가 낮아집니다.");
    }
  }

  if (["sunnyday", "desolateland"].includes(weather)) {
    if (cleanId(pokemon.ability) === "chlorophyll") {
      value += 34;
      reasons.push("쾌청에서 엽록소로 스피드가 크게 올라갑니다.");
    }
    if (cleanId(pokemon.ability) === "solarpower") {
      value += 18;
      reasons.push("쾌청에서 선파워 화력 이득을 얻습니다.");
    }
    if (pokemon.types.includes("Fire") || hasMoveType(pokemon, "Fire")) {
      value += 16;
      reasons.push("쾌청에서 불꽃 타입 공격 압박이 강해집니다.");
    }
    if (pokemon.types.includes("Water") || hasMoveType(pokemon, "Water")) {
      value -= 10;
      reasons.push("쾌청에서 물 타입 공격 가치가 낮아집니다.");
    }
  }

  if (weather === "sandstorm") {
    if (cleanId(pokemon.ability) === "sandrush") {
      value += 32;
      reasons.push("모래바람에서 모래헤치기로 스피드가 크게 올라갑니다.");
    }
    if (["sandforce", "sandveil"].includes(cleanId(pokemon.ability))) {
      value += 14;
      reasons.push("모래바람 특성 이득을 얻습니다.");
    }
    if (pokemon.types.includes("Rock")) {
      value += 12;
      reasons.push("모래바람에서 바위 타입 특수내구 이득이 있습니다.");
    }
  }

  if (["snow", "hail"].includes(weather)) {
    if (cleanId(pokemon.ability) === "slushrush") {
      value += 32;
      reasons.push("눈/싸라기눈에서 눈치우기로 스피드가 크게 올라갑니다.");
    }
    if (pokemon.types.includes("Ice") || hasMoveType(pokemon, "Ice")) {
      value += 10;
      reasons.push("눈/싸라기눈과 얼음 타입 운영 궁합이 있습니다.");
    }
  }

  const terrainBoosts = {
    electricterrain: "Electric",
    grassyterrain: "Grass",
    psychicterrain: "Psychic",
    mistyterrain: "Fairy",
  };
  const terrainType = terrainBoosts[terrain];
  if (terrainType && (pokemon.types.includes(terrainType) || hasMoveType(pokemon, terrainType))) {
    value += terrain === "mistyterrain" ? 8 : 12;
    reasons.push(`${terrainType} 타입 필드 효과를 활용할 수 있습니다.`);
  }

  return {
    fieldSynergyValue: Math.round(value * 100) / 100,
    fieldSynergyLabel:
      trickRoomActive ? "trickroom" : weather || terrain || "",
    fieldSynergyReason: reasons[0] ?? "",
  };
}

function aiMoveIdSet(pokemon) {
  return new Set(pokemon.moves.map((move) => cleanId(move.id)));
}

function aiSustainTurnBonus(pokemon) {
  const moveIds = aiMoveIdSet(pokemon);
  let bonus = 0;
  if ([...AI_RECOVERY_MOVES].some((id) => moveIds.has(id))) bonus += 1.25;
  if ([...AI_PROTECTIVE_MOVES].some((id) => moveIds.has(id))) bonus += 0.75;
  if (moveIds.has("substitute")) bonus += 0.5;
  if (pokemon.volatiles?.substitute?.hp > 0) bonus += 0.5;
  return bonus;
}

function estimatedSurvivalTurns(pokemon, incomingDamage) {
  const damage = Math.max(1, Number(incomingDamage) || 1);
  const rawTurns = pokemon.hp / damage;
  return Math.round(Math.min(6, rawTurns + aiSustainTurnBonus(pokemon)) * 100) / 100;
}

function saltCureResidualDamage(target) {
  const divisor = target.types.some((type) => ["Water", "Steel"].includes(type))
    ? 4
    : 8;
  return Math.max(1, Math.floor(target.stats.hp / divisor));
}

function aiDisplayMoveData(pokemon, move) {
  if (pokemon.dynamaxTurns <= 0) return move;
  const maxMove = resolveNativeMaxMove(pokemon, move);
  const isStatus = move.category === "Status";
  return {
    ...move,
    id: maxMove.id,
    name: maxMove.name,
    accuracy: true,
    priority: 0,
    power: isStatus ? 0 : Math.max(90, Math.min(150, move.power * 1.35)),
    target: isStatus ? "self" : move.target,
    status: "",
    selfStatus: "",
    volatileStatus: maxMove.volatileStatus ?? "",
    boosts: maxMove.boosts ?? {},
    selfBoosts: maxMove.selfBoosts ?? {},
    heal: null,
    drain: null,
    recoil: null,
    weather: maxMove.weather ?? "",
    terrain: maxMove.terrain ?? "",
    pseudoWeather: maxMove.pseudoWeather ?? "",
    sideCondition: maxMove.sideCondition ?? "",
    slotCondition: "",
    multihit: null,
    multiaccuracy: false,
    willCrit: false,
    selfSwitch: false,
    forceSwitch: false,
    fixedDamage: null,
    dynamicDamage: false,
    dynamicPower: false,
    secondaries: [],
    selfDestruct: false,
  };
}

function automaticMoveCandidates(
  state,
  sideIndex,
  strategy = "balanced",
  difficulty = "standard",
) {
  const pokemon = activePokemon(state, sideIndex);
  const defenderSide = sideIndex === 0 ? 1 : 0;
  const defender = activePokemon(state, defenderSide);
  const opponentHazards = Object.fromEntries(
    ["stealthrock", "spikes", "toxicspikes", "stickyweb"].map((id) => [
      id,
      Number(state.sides[defenderSide]?.conditions?.[id]?.layers ?? 0),
    ]),
  );
  const livingOpponents = state.sides[defenderSide].team.filter(
    (member) => !member.fainted && member.hp > 0,
  ).length;
  const opponentBestDamage = defender.moves.reduce((best, move) => {
    const range = calculateDamageRange(defender, pokemon, move);
    const accuracy = move.accuracy === true ? 1 : move.accuracy / 100;
    return Math.max(best, ((range.minimum + range.maximum) / 2) * accuracy);
  }, 0);
  const incomingDamageRatio =
    pokemon.hp > 0 ? opponentBestDamage / pokemon.hp : 1;
  const survivalTurns = estimatedSurvivalTurns(pokemon, opponentBestDamage);
  const activeRoleProfile = analyzeTeamProfile([
    {
      ...pokemon,
      moves: pokemon.moves.filter((move) => !SELF_DESTRUCT_MOVES.has(cleanId(move.id))),
    },
  ]).roles[0];
  const activeRoleScore = activeRoleProfile?.roles[0]?.score ?? 0;
  const roomContext = trickRoomContext(
    state,
    sideIndex,
    defenderSide,
    pokemon,
    defender,
    incomingDamageRatio,
  );
  return pokemon.moves
    .map((move, index) => {
      const displayMove = aiDisplayMoveData(pokemon, move);
      const range = calculateDamageRange(pokemon, defender, displayMove);
      const accuracy =
        displayMove.accuracy === true ? 1 : displayMove.accuracy / 100;
      const statusWeights = {
        slp: 48,
        tox: 42,
        brn: 34,
        par: 30,
        psn: 24,
        frz: 55,
      };
      const majorStatusValue =
        displayMove.status && canReceiveStatus(defender, displayMove.status)
          ? statusWeights[displayMove.status] ?? 18
          : 0;
      const selfBoostValue = Object.values(displayMove.selfBoosts).reduce(
        (sum, amount) => sum + Math.max(0, amount) * 15,
        0,
      );
      const targetDropValue = Object.values(displayMove.boosts).reduce(
        (sum, amount) => sum + Math.max(0, -amount) * 13,
        0,
      );
      const missingHp = Math.max(0, pokemon.stats.hp - pokemon.hp);
      const recoveryValue = displayMove.heal
        ? Math.min(
            missingHp,
            fractionAmount(pokemon.stats.hp, displayMove.heal),
          ) * 0.75
        : 0;
      const secondaryValue = displayMove.secondaries.reduce((sum, effect) => {
        const chance = effect.chance / 100;
        const status =
          effect.status && canReceiveStatus(defender, effect.status)
            ? statusWeights[effect.status] ?? 18
            : 0;
        const boosts =
          Object.values(effect.selfBoosts).reduce(
            (value, amount) => value + Math.max(0, amount) * 12,
            0,
          ) +
          Object.values(effect.boosts).reduce(
            (value, amount) => value + Math.max(0, -amount) * 10,
            0,
          );
        return sum + (status + boosts) * chance;
      }, 0);
      const statusResidualCandidates = [
        ...(displayMove.status &&
        canReceiveStatus(defender, displayMove.status, state, defenderSide, sideIndex)
          ? [{ status: displayMove.status, chance: 100 }]
          : []),
        ...displayMove.secondaries
          .filter(
            (effect) =>
              effect.status &&
              canReceiveStatus(
                defender,
                effect.status,
                state,
                defenderSide,
                sideIndex,
              ),
          )
          .map((effect) => ({ status: effect.status, chance: effect.chance })),
      ];
      const statusBlocked =
        Boolean(displayMove.status) &&
        !canReceiveStatus(defender, displayMove.status, state, defenderSide, sideIndex);
      const tacticalValue =
        majorStatusValue +
        selfBoostValue +
        targetDropValue +
        recoveryValue +
        secondaryValue;
      const expectedDamage =
        ((range.minimum + range.maximum) / 2) * accuracy;
      const baseCandidate = {
        ...displayMove,
        expectedDamage,
        tacticalValue,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        incomingDamageRatio,
        opponentHp: defender.hp,
        opponentHazards,
        opponentVolatiles: defender.volatiles ?? {},
        opponentStatus: defender.status,
        statusBlocked,
        statusResidualCandidates,
        livingOpponents,
        turn: state.turn + 1,
        expectedSurvivalTurns: survivalTurns,
        sustainTurnBonus: aiSustainTurnBonus(pokemon),
        saltCureResidualDamage: saltCureResidualDamage(defender),
        opponentMaxHp: defender.stats.hp,
        ...roomContext,
        activeRoleScore,
        activePrimaryRole: activeRoleProfile?.primaryRole ?? "support",
        selfSacrifice: SELF_DESTRUCT_MOVES.has(cleanId(displayMove.id)),
        koChance:
          range.maximum < defender.hp
            ? "none"
            : range.minimum >= defender.hp
              ? "guaranteed"
              : "possible",
      };
      return {
        slot: index + 1,
        id: displayMove.id,
        name: displayMove.name,
        type: displayMove.type,
        category: displayMove.category,
        power: displayMove.power,
        accuracy: displayMove.accuracy,
        priority: displayMove.priority,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        incomingDamageRatio,
        opponentHp: defender.hp,
        score: scoreAiMoveCandidate(baseCandidate, difficulty, strategy),
        expectedDamage,
        tacticalValue,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        incomingDamageRatio,
        opponentHp: defender.hp,
        opponentHazards,
        opponentVolatiles: defender.volatiles ?? {},
        opponentStatus: defender.status,
        statusBlocked,
        statusResidualCandidates,
        livingOpponents,
        turn: state.turn + 1,
        expectedSurvivalTurns: survivalTurns,
        sustainTurnBonus: aiSustainTurnBonus(pokemon),
        saltCureResidualDamage: saltCureResidualDamage(defender),
        opponentMaxHp: defender.stats.hp,
        ...roomContext,
        activeRoleScore,
        activePrimaryRole: activeRoleProfile?.primaryRole ?? "support",
        selfSacrifice: SELF_DESTRUCT_MOVES.has(cleanId(displayMove.id)),
        koChance: baseCandidate.koChance,
        pp: move.pp,
      };
    })
    .filter((candidate) => candidate.pp > 0)
    .sort((left, right) => right.score - left.score || left.slot - right.slot);
}

export function automaticMoveSlot(
  state,
  sideIndex,
  difficulty,
  strategy = "balanced",
  candidates = automaticMoveCandidates(state, sideIndex, strategy, difficulty),
) {
  const lockedSelection = lockedMoveSelection(activePokemon(state, sideIndex));
  if (lockedSelection) return lockedSelection.slot;
  const selected = selectAiMoveCandidate(
    candidates,
    {
      difficulty,
      strategy,
      rng: createAiRng(state.seed, sideIndex, state.turn * 17),
    },
  );
  return selected?.slot ?? 1;
}

function isConfiguredDynamaxPokemon(pokemon) {
  return (
    pokemon?.gimmicks?.forceDynamax === true ||
    pokemon?.gimmicks?.gigantamax === true
  );
}

function canUseDynamaxFallback(side) {
  const configured = side.team.filter(isConfiguredDynamaxPokemon);
  return (
    configured.length > 0 &&
    configured.every((pokemon) => pokemon.fainted || pokemon.hp <= 0)
  );
}

function chooseSimpleAiDecision(
  state,
  sideIndex,
  difficulty = "standard",
  strategy = "balanced",
) {
  const side = state.sides[sideIndex];
  const pokemon = activePokemon(state, sideIndex);
  const lockedSelection = lockedMoveSelection(pokemon);
  const moveCandidates = automaticMoveCandidates(
    state,
    sideIndex,
    strategy,
    difficulty,
  );
  const selectedMove = selectAiMoveCandidate(moveCandidates, {
    difficulty,
    strategy,
    rng: createAiRng(state.seed, sideIndex, state.turn * 17),
  });
  const forcedMove =
    lockedSelection &&
    moveCandidates.find((candidate) => candidate.slot === lockedSelection.slot);
  const chosenMove = forcedMove ?? selectedMove;
  const dynamaxFallback =
    canUseDynamaxFallback(side) && !canMegaEvolvePokemon(pokemon);
  const gimmick = selectAiGimmick({
    active: {
      canDynamax:
        side.gimmickResources.dynamax === "available" &&
        pokemon.dynamaxTurns <= 0 &&
        pokemon.megaEvolved !== true,
      hpPercent: pokemon.hp / pokemon.stats.hp,
      incomingDamageRatio: chosenMove?.incomingDamageRatio,
      opponentHp: chosenMove?.opponentHp,
    },
    configured: {
      gimmicks: {
        dynamax:
          pokemon.gimmicks?.forceDynamax === true || dynamaxFallback,
      },
    },
    selectedMove: chosenMove,
    moveCandidates,
    forceDynamax: pokemon.gimmicks?.forceDynamax === true,
    alreadyUsed: side.usedGimmicks,
  }).id;
  return {
    command: {
      move: chosenMove?.slot ?? automaticMoveSlot(
        state,
        sideIndex,
        difficulty,
        strategy,
        moveCandidates,
      ),
      ...(gimmick && !lockedSelection ? { gimmick } : {}),
    },
    selectedMove: chosenMove,
    moveCandidates,
  };
}

export function chooseSimpleAiCommand(
  state,
  sideIndex,
  difficulty = "standard",
  strategy = "balanced",
) {
  return chooseSimpleAiDecision(state, sideIndex, difficulty, strategy).command;
}

export function runSimpleBattle(setup, options = {}) {
  const maxTurns = Number(options.maxTurns ?? DEFAULT_MAX_TURNS);
  const difficulty = String(options.difficulty ?? "standard");
  const aiProfiles = [0, 1].map((index) => ({
    difficulty: options.aiProfiles?.[index]?.difficulty ?? difficulty,
    strategy: options.aiProfiles?.[index]?.strategy ?? "balanced",
  }));
  let state = createSimpleBattle(setup);
  while (state.status === "running" && state.turn < maxTurns) {
    const decisions = state.sides.map((_, sideIndex) => {
      const profile = aiProfiles[sideIndex];
      const decision = chooseSimpleAiDecision(
        state,
        sideIndex,
        profile.difficulty,
        profile.strategy,
      );
      const command = decision.command;
      const active = activePokemon(state, sideIndex);
      const candidates = decision.moveCandidates.map((candidate) => ({
        ...toAiActionCandidate(
          {
            ...candidate,
            score: Math.round(candidate.score * 100) / 100,
          },
          {
            type: "move",
            difficulty: profile.difficulty,
            strategy: profile.strategy,
          },
        ),
        score: Math.round(candidate.score * 100) / 100,
        selected: candidate.slot === decision.selectedMove?.slot,
      }));
      return {
        command,
        trace: {
          turn: state.turn + 1,
          side: sideIndex,
          sideName: state.sides[sideIndex].name,
          species: active.name,
          kind: "move",
          difficulty: profile.difficulty,
          strategy: profile.strategy,
          chosenAction:
            candidates.find((candidate) => candidate.selected)?.name ?? "",
          gimmick: command.gimmick ?? "",
          reason: aiDecisionReason(profile.strategy, command.gimmick ?? ""),
          candidates,
          aiModel: "common-battle-ai",
        },
      };
    });
    state.aiTrace.push(...decisions.map((decision) => decision.trace));
    const commands = decisions.map((decision) => decision.command);
    state = resolveSimpleTurn(state, commands);
  }
  if (state.status === "running") {
    state.status = "turn_limit";
    state.events.push({ turn: state.turn, type: "turn_limit" });
  }
  return state;
}
