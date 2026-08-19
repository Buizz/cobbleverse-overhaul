const ENGINE_ID = "cobbleventure-simple";
import {
  DYNAMAX_BLOCKED_WEIGHT_MOVES,
  resolveDynamicPower,
  SUPPORTED_DYNAMIC_POWER_MOVES,
} from "./native-dynamic-power.mjs";
import {
  isNativeGigantamaxSpecies,
  resolveNativeMaxMove,
} from "./native-max-moves.mjs";
import {
  canPokemonCombineGimmick,
  pokemonGimmickConflict,
} from "./native-gimmick-compatibility.mjs";
import {
  analyzeTeamProfile,
  aiDecisionReason,
  buildThreatCounterMap,
  compareAiDecisionPolicies,
  createAiRng,
  estimateBattleWinProbability,
  evaluateOneTurnBattleState,
  evaluatePokemonRoleProgress,
  evaluateSetupLikelihood,
  evaluateSetupThreat,
  scoreAiMoveCandidate,
  scoreAiProjectedGimmickCandidate,
  scoreAiSwitchCandidate,
  selectAiGimmick,
  selectAiMoveCandidate,
  selectAiSwitchCandidate,
  selectWinProbabilityCandidate,
  toAiActionCandidate,
  toAiTraceCandidate,
} from "./common-battle-ai.mjs";
import {
  buildSharedBattleActionsJson,
  applySharedDamageJson,
  applySharedDirectDamageJson,
  applySharedBoostJson,
  applySharedFieldJson,
  applySharedSideConditionJson,
  applySharedStatusJson,
  applySharedVolatileJson,
  calculateSharedBaseDamage,
  calculateSharedModifiedDamage,
  decideTwoTurnJson,
  decideWinRateJson,
  deriveBatonPassFactsJson,
  deriveEntryHazardDamageJson,
  deriveHazardLayerFactsJson,
  deriveRecoveryFactsJson,
  deriveResidualPressureJson,
  deriveSaltCureDamageJson,
  deriveSharedSwitchMatchupObservationJson,
  evaluateSharedDamageFactorsJson,
  evaluateSharedBattleOutcomeJson,
  evaluateSharedDamagePipelineJson,
  evaluateSharedDamageStatsJson,
  evaluateSharedDynamaxExpiryJson,
  evaluateSharedEndTurnResidualJson,
  evaluateSharedEffectiveStatJson,
  evaluateSharedFaintReplacementJson,
  evaluateSharedForcedSwitchJson,
  evaluateSharedHitReactionsJson,
  evaluateSharedPostHitJson,
  evaluateSharedSwitchPhaseJson,
  evaluateSharedTimedEffectJson,
  extractBattleValueSideJson,
  generateSharedSearchActionsJson,
  legalSharedSearchCandidatesJson,
  orderSharedBattleActionsJson,
  rollSharedSecondaryJson,
  scoreSharedTrainerItemCandidateJson,
  transitionSharedSearchStateJson,
} from "./shared-ai-core.mjs";
import {
  toSharedBattleState,
  toSharedTurnCommands,
} from "./shared-battle-state-adapter.mjs";

const ENGINE_VERSION = "0.9.7";
const DEFAULT_MAX_TURNS = 200;
const DEFAULT_FIELD_DURATION = 5;
const SECOND_TURN_SEARCH_DISCOUNT = 0.72;
const GIMMICK_KINDS = ["mega", "zmove", "dynamax", "terastallize"];
const PRE_MOVE_GIMMICKS = new Set(["mega", "dynamax", "gigantamax", "terastallize"]);
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
const DAMP_BLOCKED_MOVES = new Set([...SELF_DESTRUCT_MOVES, "mindblown"]);
const ROLLING_LOCK_MOVES = new Set(["iceball", "rollout"]);
const RAMPAGE_LOCK_MOVES = new Set(["outrage", "petaldance", "thrash"]);
const NON_CONSECUTIVE_MOVES = new Set(["bloodmoon", "gigatonhammer"]);
const AROMA_VEIL_VOLATILES = new Set([
  "attract",
  "disable",
  "encore",
  "healblock",
  "taunt",
  "torment",
]);
const PERSISTENT_ABILITY_WEATHERS = new Set([
  "deltastream",
  "desolateland",
  "primordialsea",
]);
const FIRST_ACTIVE_TURN_MOVES = new Set(["fakeout", "firstimpression"]);
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
const AI_HAZARD_MOVES = new Set([
  "spikes",
  "stealthrock",
  "stickyweb",
  "toxicspikes",
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
const AI_STATUS_CONTROL_MOVES = new Set([
  "batonpass",
  "encore",
  "haze",
  "healbell",
  "aromatherapy",
  "substitute",
  "taunt",
]);
const AI_PHAZE_MOVES = new Set([
  "roar",
  "whirlwind",
  "dragontail",
  "circlethrow",
]);
const AI_REVEALED_SETUP_RESET_MOVES = new Set([
  "haze",
  "clearsmog",
  ...AI_PHAZE_MOVES,
]);
const CONSECUTIVE_PROTECTION_MOVES = new Set([
  ...AI_PROTECTIVE_MOVES,
  "endure",
  "maxguard",
]);
const IMPLEMENTED_ABILITIES = new Set([
  "adaptability",
  "aftermath",
  "airlock",
  "analytic",
  "anticipation",
  "arenatrap",
  "aromaveil",
  "aurabreak",
  "asoneglastrier",
  "asonespectrier",
  "baddreams",
  "battlearmor",
  "beadsofruin",
  "blaze",
  "cheekpouch",
  "chillingneigh",
  "competitive",
  "compoundeyes",
  "comatose",
  "contrary",
  "cottondown",
  "cursedbody",
  "cutecharm",
  "chlorophyll",
  "clearbody",
  "cloudnine",
  "dauntlessshield",
  "darkaura",
  "defeatist",
  "deltastream",
  "desolateland",
  "defiant",
  "download",
  "damp",
  "dragonsmaw",
  "disguise",
  "drizzle",
  "dryskin",
  "drought",
  "eartheater",
  "earlybird",
  "electricsurge",
  "effectspore",
  "fairyaura",
  "flamebody",
  "flashfire",
  "fluffy",
  "flowergift",
  "flowerveil",
  "forecast",
  "forewarn",
  "frisk",
  "furcoat",
  "galewings",
  "goodasgold",
  "gooey",
  "gorillatactics",
  "grassysurge",
  "gluttony",
  "grimneigh",
  "gulpmissile",
  "guts",
  "hadronengine",
  "hypercutter",
  "hugepower",
  "hydration",
  "harvest",
  "hustle",
  "heavymetal",
  "icebody",
  "immunity",
  "imposter",
  "illusion",
  "illuminate",
  "insomnia",
  "intimidate",
  "intrepidsword",
  "ironbarbs",
  "ironfist",
  "justified",
  "keeneye",
  "leafguard",
  "levitate",
  "limber",
  "lightmetal",
  "lightningrod",
  "liquidvoice",
  "liquidooze",
  "magicbounce",
  "magicguard",
  "magician",
  "magnetpull",
  "mindseye",
  "mirrorarmor",
  "moldbreaker",
  "multiscale",
  "multitype",
  "moxie",
  "naturalcure",
  "neutralizinggas",
  "beastboost",
  "innerfocus",
  "infiltrator",
  "noguard",
  "oblivious",
  "overcoat",
  "owntempo",
  "pickup",
  "pickpocket",
  "poisonheal",
  "poisonpoint",
  "poisonpuppeteer",
  "poisontouch",
  "minus",
  "plus",
  "pressure",
  "prankster",
  "primordialsea",
  "protosynthesis",
  "protean",
  "psychicsurge",
  "purifyingsalt",
  "purepower",
  "quarkdrive",
  "queenlymajesty",
  "quickdraw",
  "reckless",
  "regenerator",
  "rockhead",
  "roughskin",
  "rivalry",
  "ripen",
  "sandrush",
  "sandforce",
  "sandstream",
  "sandveil",
  "sapsipper",
  "scrappy",
  "serenegrace",
  "sharpness",
  "shadowshield",
  "shadowtag",
  "shedskin",
  "sheerforce",
  "shellarmor",
  "shielddust",
  "simple",
  "skilllink",
  "snowcloak",
  "slushrush",
  "sniper",
  "soundproof",
  "speedboost",
  "static",
  "steadfast",
  "stamina",
  "stench",
  "stancechange",
  "stickyhold",
  "sturdy",
  "strongjaw",
  "stormdrain",
  "supremeoverlord",
  "swarm",
  "sweetveil",
  "synchronize",
  "technician",
  "thermalexchange",
  "teraformzero",
  "terashell",
  "terashift",
  "teravolt",
  "thickfat",
  "tintedlens",
  "toughclaws",
  "trace",
  "toxicdebris",
  "unaware",
  "unburden",
  "unseenfist",
  "victorystar",
  "vitalspirit",
  "vesselofruin",
  "voltabsorb",
  "waterveil",
  "weakarmor",
  "waterabsorb",
  "wellbakedbody",
  "whitesmoke",
  "wonderguard",
  "overgrow",
  "torrent",
  "swiftswim",
  "snowwarning",
  "filter",
  "heatproof",
  "solidrock",
  "prismarmor",
  "orichalcumpulse",
  "armortail",
  "solarpower",
  "soulheart",
  "superluck",
  "swordofruin",
  "tabletsofruin",
  "tangledfeet",
  "transistor",
  "truant",
  "waterbubble",
  "zenmode",
  "embodyaspectcornerstone",
  "embodyaspecthearthflame",
  "embodyaspectteal",
  "embodyaspectwellspring",
]);
const INTENTIONAL_NO_EFFECT_ABILITIES = new Set([
  "ballfetch",
  "healer",
  "honeygather",
  "hospitality",
  "runaway",
  "telepathy",
  "unnerve",
]);
const SUPPORTED_ABILITIES = new Set([
  ...IMPLEMENTED_ABILITIES,
  ...INTENTIONAL_NO_EFFECT_ABILITIES,
]);
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
const BATON_PASS_VOLATILES = new Set([
  "aquaring",
  "ingrain",
  "substitute",
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

function normalizedGender(value) {
  const gender = cleanId(value);
  if (["m", "male"].includes(gender)) return "M";
  if (["f", "female"].includes(gender)) return "F";
  return "";
}

export function isSimpleAbilitySupported(ability) {
  const abilityId = cleanId(ability);
  return abilityId.length === 0 || SUPPORTED_ABILITIES.has(abilityId);
}

function canonicalTypeName(value) {
  const typeId = cleanId(value);
  return (
    [...Object.keys(TYPE_CHART), "Stellar", "Typeless"].find(
      (type) => cleanId(type) === typeId,
    ) ?? String(value ?? "").trim()
  );
}

export function isMoveTemporarilyDisabled(pokemon, move) {
  const moveId = cleanId(move?.id);
  return (
    (NON_CONSECUTIVE_MOVES.has(moveId) &&
      pokemon?.lastMoveSucceeded === true &&
      cleanId(pokemon?.lastMove?.id) === moveId) ||
    (FIRST_ACTIVE_TURN_MOVES.has(moveId) &&
      Number(pokemon?.activeTurns ?? 0) > 0) ||
    (moveId === "substitute" &&
      (Boolean(pokemon?.volatiles?.substitute) ||
        Number(pokemon?.hp ?? 0) <=
          Math.floor(Number(pokemon?.stats?.hp ?? 0) / 4)))
  );
}

export function isMoveBlockedByDynamaxTarget(move, target) {
  return (
    Number(target?.dynamaxTurns ?? 0) > 0 &&
    DYNAMAX_BLOCKED_WEIGHT_MOVES.has(cleanId(move?.id))
  );
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

function normalizeSpeciesForm(form) {
  if (!form || typeof form !== "object") return null;
  const stats = form.stats ?? {};
  const normalizedStats = {};
  for (const stat of [
    "hp",
    "attack",
    "defence",
    "specialAttack",
    "specialDefence",
    "speed",
  ]) {
    const value = Number(stats[stat]);
    if (Number.isFinite(value) && value > 0) normalizedStats[stat] = value;
  }
  return {
    id: cleanId(form.id ?? form.name),
    name: cleanDisplayName(form.name ?? form.id),
    types: Array.isArray(form.types)
      ? form.types.map(String).filter(Boolean).slice(0, 2)
      : [],
    ability: cleanId(form.ability),
    weightKg: Math.max(0.1, Number(form.weightKg ?? 100)),
    stats: normalizedStats,
  };
}

function pokemonFamilyId(pokemon) {
  return cleanId(
    pokemon?.baseSpecies ??
      pokemon?.baseSpeciesName ??
      pokemon?.id ??
      pokemon?.name,
  );
}

function isOgerponPokemon(pokemon) {
  return pokemonFamilyId(pokemon) === "ogerpon";
}

function isTerapagosPokemon(pokemon) {
  return pokemonFamilyId(pokemon) === "terapagos";
}

function ogerponTeraProfile(pokemon) {
  if (!isOgerponPokemon(pokemon)) return null;
  const speciesId = cleanId(pokemon.id ?? pokemon.name);
  const itemId = cleanId(pokemon.item);
  if (
    speciesId.includes("wellspring") ||
    itemId === "wellspringmask"
  ) {
    return {
      type: "Water",
      id: "ogerponwellspringtera",
      name: "Ogerpon-Wellspring-Tera",
      ability: "embodyaspectwellspring",
      boosts: { specialDefence: 1 },
    };
  }
  if (
    speciesId.includes("hearthflame") ||
    itemId === "hearthflamemask"
  ) {
    return {
      type: "Fire",
      id: "ogerponhearthflametera",
      name: "Ogerpon-Hearthflame-Tera",
      ability: "embodyaspecthearthflame",
      boosts: { attack: 1 },
    };
  }
  if (
    speciesId.includes("cornerstone") ||
    itemId === "cornerstonemask"
  ) {
    return {
      type: "Rock",
      id: "ogerponcornerstonetera",
      name: "Ogerpon-Cornerstone-Tera",
      ability: "embodyaspectcornerstone",
      boosts: { defence: 1 },
    };
  }
  return {
    type: "Grass",
    id: "ogerpontealtera",
    name: "Ogerpon-Teal-Tera",
    ability: "embodyaspectteal",
    boosts: { speed: 1 },
  };
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
  const directSelfBoosts = normalizeBoosts(move?.selfBoosts);
  const nestedSelfBoosts = normalizeBoosts(move?.self?.boosts);
  const directStatus = cleanId(move?.status);
  const moveId = cleanId(move?.id ?? move?.name);
  const canonicalMoveId = moveId === "heatstamp" ? "heatcrash" : moveId;
  const forcedMultihit =
    canonicalMoveId === "surgingstrikes"
      ? [3, 3]
      : Array.isArray(move?.multihit)
        ? move.multihit.map(Number).slice(0, 2)
        : Number.isFinite(Number(move?.multihit))
          ? [Number(move.multihit), Number(move.multihit)]
          : null;
  return {
    id:
      moveId === "heatstamp"
        ? canonicalMoveId
        : String(move?.id ?? "").trim(),
    name: String(move?.name ?? move?.id ?? "").trim(),
    type: String(move?.type ?? "Normal"),
    category,
    power: Math.max(0, Number(move?.power ?? 0)),
    accuracy:
      move?.accuracy === true
        ? true
        : Math.max(0, Math.min(100, Number(move?.accuracy ?? 100))),
    priority: Number(move?.priority ?? 0),
    contact: makesContact(move),
    punch: hasMoveFlag(move, "punch"),
    powder: Boolean(move?.powder === true || move?.flags?.powder === true),
    sound: Boolean(move?.sound === true || move?.flags?.sound === true),
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
      ...directSelfBoosts,
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
    multihit: forcedMultihit,
    multiaccuracy: Boolean(move?.multiaccuracy),
    willCrit: Boolean(move?.willCrit) || moveId === "surgingstrikes",
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
  const baseSpecies = String(
    pokemon?.baseSpecies ?? pokemon?.id ?? pokemon?.name ?? "",
  ).trim();
  const speciesForms = Object.fromEntries(
    Object.entries(pokemon?.speciesForms ?? {})
      .map(([key, form]) => [cleanId(key), normalizeSpeciesForm(form)])
      .filter(([, form]) => form),
  );
  const speciesIdentity = {
    baseSpecies,
    id: pokemon?.id,
    name: pokemon?.name,
    item: pokemon?.item,
  };
  const requiredTeraType = isTerapagosPokemon(speciesIdentity)
    ? "Stellar"
    : ogerponTeraProfile(speciesIdentity)?.type;
  const configuredTeraType = String(
    requiredTeraType ??
      gimmicks.teraType ??
      gimmicks.tera ??
      pokemon?.types?.[0] ??
      "Normal",
  ).trim();
  const normalizedAbility = cleanId(pokemon?.ability);
  const normalizedItem = cleanId(pokemon?.item);
  const configuredTypes = Array.isArray(pokemon?.types)
    ? pokemon.types.map(String).slice(0, 2)
    : ["Normal"];
  const multitypeType =
    normalizedAbility === "multitype" ? multitypePlateType(normalizedItem) : "";
  const battleTypes = multitypeType ? [multitypeType] : configuredTypes;
  return {
    id: String(pokemon?.id ?? pokemon?.name ?? "").trim(),
    name: String(pokemon?.name ?? pokemon?.id ?? "").trim(),
    baseSpecies,
    canEvolve: pokemon?.canEvolve === true,
    role: pokemon?.role,
    aiRole: pokemon?.aiRole,
    roles: Array.isArray(pokemon?.roles) ? [...pokemon.roles] : pokemon?.roles,
    aiRoles: Array.isArray(pokemon?.aiRoles)
      ? [...pokemon.aiRoles]
      : pokemon?.aiRoles,
    ace: pokemon?.ace,
    isAce: pokemon?.isAce,
    forceAce: pokemon?.forceAce,
    notAce: pokemon?.notAce,
    aiAce: pokemon?.aiAce,
    acePriority: pokemon?.acePriority,
    ai:
      pokemon?.ai && typeof pokemon.ai === "object"
        ? clone(pokemon.ai)
        : undefined,
    roleStats: normalizeOptionalStats(
      pokemon?.baseStats ?? pokemon?.roleStats,
    ),
    level: Math.max(1, Math.min(100, Number(pokemon?.level ?? 50))),
    types: battleTypes,
    originalTypes: battleTypes.slice(),
    gender: normalizedGender(pokemon?.gender),
    ability: normalizedAbility,
    baseAbility: normalizedAbility,
    item: normalizedItem,
    speciesForms,
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
              itemName: cleanDisplayName(gimmicks.zCrystal.itemName),
              move: cleanDisplayName(gimmicks.zCrystal.move),
              moveType: cleanId(gimmicks.zCrystal.moveType),
              moveFrom: cleanId(gimmicks.zCrystal.moveFrom),
              users: Array.isArray(gimmicks.zCrystal.users)
                ? gimmicks.zCrystal.users.map(cleanId).filter(Boolean)
                : [],
            }
          : null,
      canDynamax: true,
      forceDynamax:
        gimmicks.forceDynamax === true ||
        gimmicks.dynamax === true ||
        gimmicks.gmax === true,
      canGigantamax:
        gimmicks.canGigantamax === true ||
        isNativeGigantamaxSpecies(pokemon?.id ?? pokemon?.name),
      gigantamax: gimmicks.gigantamax === true || gimmicks.gmax === true,
      teraConfigured:
        gimmicks.teraConfigured === true ||
        (gimmicks.teraConfigured == null &&
          (gimmicks.teraType != null || gimmicks.tera != null)),
      teraType: configuredTeraType,
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
    abilityState: {},
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
    protectCounter: 0,
    lockedMove: null,
    choiceLock: null,
    chargingMove: null,
    volatiles: {},
    boosts: Object.fromEntries(BOOST_STATS.map((stat) => [stat, 0])),
    teraType: null,
    configuredTeraType,
    terastallized: false,
    stellarBoostedTypes: Array.isArray(pokemon?.stellarBoostedTypes)
      ? pokemon.stellarBoostedTypes.map(String)
      : [],
    hasDynamaxed: pokemon?.hasDynamaxed === true,
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
    bag: Array.isArray(side?.bag)
      ? side.bag
          .map((entry) => ({
            item: cleanId(entry?.item),
            quantity: Math.max(0, Math.floor(Number(entry?.quantity ?? 0))),
          }))
          .filter((entry) => entry.item && entry.quantity > 0)
      : [],
    itemUsesRemaining: Math.max(
      0,
      Math.floor(Number(side?.maxItemUses ?? 0)),
    ),
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
    restore(snapshot) {
      state = Number(snapshot) >>> 0;
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

function moveEffectiveness(move, defenderTypes, attacker = null, defender = null) {
  if (cleanId(move.type) === "stellar") {
    return defender?.terastallized === true ? 2 : 1;
  }
  if (absorbingAbilityForMove(defender, move, attacker)) return 0;
  let effectiveness = typeMultiplier(move.type, defenderTypes);
  if (
    effectiveness === 0 &&
    ["mindseye", "scrappy"].includes(activeAbility(attacker)) &&
    ["Normal", "Fighting"].includes(move.type) &&
    defenderTypes.includes("Ghost")
  ) {
    effectiveness = defenderTypes.reduce((result, defenceType) => {
      if (defenceType === "Ghost") return result;
      return result * (TYPE_CHART[move.type]?.[defenceType] ?? 1);
    }, 1);
  }
  if (cleanId(move.id) === "freezedry" && defenderTypes.includes("Water")) {
    effectiveness *= 4;
  }
  if (cleanId(move.id) === "flyingpress") {
    effectiveness *= typeMultiplier("Flying", defenderTypes);
  }
  if (
    effectiveness <= 1 &&
    cleanId(move.id) !== "struggle" &&
    activeAbility(defender) === "wonderguard" &&
    !ignoresDefenderAbility(attacker)
  ) {
    effectiveness = 0;
  }
  if (
    effectiveness >= 1 &&
    cleanId(move.id) !== "struggle" &&
    activeAbility(defender) === "terashell" &&
    cleanId(defender?.id ?? defender?.name) === "terapagosterastal" &&
    !ignoresDefenderAbility(attacker) &&
    (defender.hp >= defender.stats.hp ||
      defender.abilityState?.teraShellActive === true)
  ) {
    effectiveness = 0.5;
  }
  return effectiveness;
}

function stageMultiplier(stage) {
  const bounded = Math.max(-6, Math.min(6, Number(stage ?? 0)));
  return bounded >= 0 ? (2 + bounded) / 2 : 2 / (2 - bounded);
}

function activeAbility(pokemon) {
  return pokemon.volatiles?.gastroacid ||
    (pokemon.volatiles?.neutralizinggas && pokemon.ability !== "neutralizinggas")
    ? ""
    : pokemon.ability;
}

function hasActiveAbility(state, ability) {
  const id = cleanId(ability);
  return Boolean(
    state?.sides?.some((_, sideIndex) => {
      const pokemon = activePokemon(state, sideIndex);
      return pokemon && !pokemon.fainted && activeAbility(pokemon) === id;
    }),
  );
}

function isEffectivelyAsleep(pokemon) {
  return pokemon?.status === "slp" || activeAbility(pokemon) === "comatose";
}

function effectiveWeather(state) {
  if (!state) return "";
  const weatherSuppressed = state.sides?.some((_, sideIndex) => {
    const pokemon = activePokemon(state, sideIndex);
    return (
      pokemon &&
      !pokemon.fainted &&
      ["airlock", "cloudnine"].includes(activeAbility(pokemon))
    );
  });
  return weatherSuppressed ? "" : cleanId(state.field?.weather?.id);
}

function dampActive(state) {
  return Boolean(
    state?.sides?.some((_, sideIndex) => {
      const pokemon = activePokemon(state, sideIndex);
      return pokemon && !pokemon.fainted && activeAbility(pokemon) === "damp";
    }),
  );
}

function isDampBlockedMove(state, move) {
  return dampActive(state) && DAMP_BLOCKED_MOVES.has(cleanId(move?.id));
}

function isPrimordialSeaBlockedMove(state, move) {
  return (
    effectiveWeather(state) === "primordialsea" &&
    move?.category !== "Status" &&
    move?.type === "Fire" &&
    Number(move?.power ?? 0) > 0
  );
}

function isDesolateLandBlockedMove(state, move) {
  return (
    effectiveWeather(state) === "desolateland" &&
    move?.category !== "Status" &&
    move?.type === "Water" &&
    Number(move?.power ?? 0) > 0
  );
}

function doublesPhysicalAttack(ability) {
  return ["hugepower", "purepower"].includes(cleanId(ability));
}

function ignoresDefenderAbility(attacker) {
  return ["moldbreaker", "teravolt"].includes(
    cleanId(activeAbility(attacker)),
  );
}

function makesContact(move) {
  return Boolean(
    move?.contact === true ||
      move?.makesContact === true ||
      move?.flags?.contact === true,
  );
}

const sharedContactOutcomeCache = new Map();

function sharedContactOutcome(attacker, move) {
  const key = [
    cleanId(attacker?.item),
    makesContact(move) ? 1 : 0,
    hasMoveFlag(move, "punch") ? 1 : 0,
  ].join(":");
  const cached = sharedContactOutcomeCache.get(key);
  if (cached) return cached;
  const outcome = JSON.parse(
    evaluateSharedPostHitJson(
      JSON.stringify({
        moveContact: makesContact(move),
        movePunch: hasMoveFlag(move, "punch"),
        attackerItem: attacker?.item ?? "",
      }),
    ),
  );
  if (sharedContactOutcomeCache.size >= 16) {
    sharedContactOutcomeCache.delete(sharedContactOutcomeCache.keys().next().value);
  }
  sharedContactOutcomeCache.set(key, outcome);
  return outcome;
}

function makesEffectiveContact(attacker, move) {
  return sharedContactOutcome(attacker, move).effectiveContact;
}

function triggersContactPunishment(attacker, move) {
  return sharedContactOutcome(attacker, move).contactPunishment;
}

function hasMoveFlag(move, flag) {
  return Boolean(move?.[flag] === true || move?.flags?.[flag] === true);
}

function isSoundMove(move) {
  return Boolean(move?.sound === true || move?.flags?.sound === true);
}

function absorbingAbilityForMove(defender, move, attacker = null) {
  if (!defender || ignoresDefenderAbility(attacker)) return "";
  const ability = activeAbility(defender);
  const moveType = cleanId(move?.type);
  if (ability === "soundproof" && isSoundMove(move)) return ability;
  if (
    (moveType === "electric" &&
      ["lightningrod", "voltabsorb"].includes(ability)) ||
    (moveType === "water" &&
      ["dryskin", "stormdrain", "waterabsorb"].includes(ability)) ||
    (moveType === "fire" &&
      ["flashfire", "wellbakedbody"].includes(ability)) ||
    (moveType === "grass" && ability === "sapsipper") ||
    (moveType === "ground" && ["eartheater", "levitate"].includes(ability))
  ) {
    return ability;
  }
  return "";
}

const sharedSecondaryChanceCache = new Map();

function secondaryEffectChance(attacker, effect) {
  const chance = Number(effect?.chance ?? 100);
  const ability = activeAbility(attacker);
  const key = `${ability}:${chance}`;
  if (sharedSecondaryChanceCache.has(key)) {
    return sharedSecondaryChanceCache.get(key);
  }
  const result = JSON.parse(
    rollSharedSecondaryJson(
      JSON.stringify({
        chance,
        attackerAbility: ability,
        blocked: true,
      }),
    ),
  );
  if (sharedSecondaryChanceCache.size >= 32) {
    sharedSecondaryChanceCache.delete(sharedSecondaryChanceCache.keys().next().value);
  }
  sharedSecondaryChanceCache.set(key, result.effectiveChance);
  return result.effectiveChance;
}

function activateAbsorbingAbility(
  state,
  defenderSide,
  defender,
  attackerSide,
  attacker,
  move,
) {
  const ability = absorbingAbilityForMove(defender, move, attacker);
  if (!ability) return false;
  emitAbilityActivation(state, defenderSide, defender, ability, {
    targetSide: attackerSide,
    target: attacker.name,
    move: move.name,
  });
  if (["voltabsorb", "waterabsorb", "dryskin", "eartheater"].includes(ability)) {
    healPokemon(
      state,
      defenderSide,
      defender,
      Math.max(1, Math.floor(defender.stats.hp / 4)),
      ability,
    );
  } else if (["lightningrod", "stormdrain"].includes(ability)) {
    applyBoosts(
      state,
      defenderSide,
      defender,
      { specialAttack: 1 },
      ability,
    );
  } else if (ability === "sapsipper") {
    applyBoosts(state, defenderSide, defender, { attack: 1 }, ability);
  } else if (ability === "wellbakedbody") {
    applyBoosts(state, defenderSide, defender, { defence: 2 }, ability);
  } else if (ability === "flashfire") {
    defender.abilityState ??= {};
    defender.abilityState.flashFireBoosted = true;
  }
  return true;
}

function abilityModifiedMove(attacker, move) {
  if (activeAbility(attacker) === "liquidvoice" && isSoundMove(move)) {
    return { ...move, type: "Water" };
  }
  if (
    (activeAbility(attacker) === "stench" ||
      cleanId(attacker.item) === "kingsrock") &&
    move?.category !== "Status" &&
    Number(move?.power ?? 0) > 0 &&
    !(move.secondaries ?? []).some(
      (effect) => cleanId(effect.volatileStatus) === "flinch",
    )
  ) {
    return {
      ...move,
      secondaries: [
        ...(move.secondaries ?? []),
        { chance: 10, volatileStatus: "flinch" },
      ],
    };
  }
  return move;
}

function movePriorityForPokemon(pokemon, move) {
  const priority = Number(move?.priority ?? 0);
  if (
    activeAbility(pokemon) === "prankster" &&
    move?.category === "Status"
  ) {
    return priority + 1;
  }
  if (
    activeAbility(pokemon) === "galewings" &&
    move?.type === "Flying" &&
    pokemon.hp >= pokemon.stats.hp
  ) {
    return priority + 1;
  }
  return priority;
}

function priorityBlockingAbility(pokemon) {
  const ability = activeAbility(pokemon);
  return ["armortail", "queenlymajesty"].includes(ability) ? ability : "";
}

function preventsCriticalHit(defender, attacker = null) {
  return (
    !ignoresDefenderAbility(attacker) &&
    ["battlearmor", "shellarmor"].includes(activeAbility(defender))
  );
}

function criticalDamageModifier(attacker, defender, critical) {
  if (!critical || preventsCriticalHit(defender, attacker)) return 1;
  return activeAbility(attacker) === "sniper" ? 2.25 : 1.5;
}

function secondaryEffectBlockSource(attacker, defender, move) {
  return sharedPostHitOutcome(move, attacker, defender, 1, 1)
    .secondaryBlockSource;
}

function secondaryEffectsBlocked(attacker, defender, move) {
  return Boolean(secondaryEffectBlockSource(attacker, defender, move));
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
      `Unsupported ability in cobbleventure-simple strict validation: ${unsupported.join("; ")}`,
    );
  }
}

function paradoxBoostStat(pokemon, state = null) {
  const ability = activeAbility(pokemon);
  const weather = effectiveWeather(state);
  const terrain = cleanId(state?.field?.terrain?.id);
  const fieldActive =
    (ability === "protosynthesis" &&
      ["sunnyday", "desolateland"].includes(weather)) ||
    (ability === "quarkdrive" && terrain === "electricterrain");
  if (!fieldActive && pokemon.abilityState?.paradoxSource !== "boosterenergy") {
    return "";
  }
  if (pokemon.abilityState?.paradoxStat) return pokemon.abilityState.paradoxStat;
  return ["attack", "defence", "specialAttack", "specialDefence", "speed"]
    .sort((left, right) => pokemon.stats[right] - pokemon.stats[left])[0];
}

function sharedEffectiveStatPokemon(pokemon) {
  const statValue = (stat) => {
    const value = Number(pokemon?.stats?.[stat] ?? 1);
    return Number.isFinite(value) ? Math.max(1, Math.floor(value)) : 1;
  };
  const hp = Number(pokemon?.hp ?? 0);
  return {
    id: String(pokemon?.id ?? pokemon?.name ?? ""),
    baseSpecies: String(pokemon?.baseSpecies ?? ""),
    types: pokemon?.types ?? [],
    ability: String(pokemon?.ability ?? ""),
    item: String(pokemon?.item ?? ""),
    status: String(pokemon?.status ?? ""),
    hp: Number.isFinite(hp) ? Math.floor(hp) : 0,
    maximumHp: statValue("hp"),
    stats: {
      hp: statValue("hp"),
      attack: statValue("attack"),
      defence: statValue("defence"),
      specialAttack: statValue("specialAttack"),
      specialDefence: statValue("specialDefence"),
      speed: statValue("speed"),
    },
    boosts: pokemon?.boosts ?? {},
    dynamaxTurns: Number(pokemon?.dynamaxTurns ?? 0),
    canEvolve: pokemon?.canEvolve === true,
    gastroAcid: Boolean(pokemon?.volatiles?.gastroacid),
    neutralizingGasSuppressed: Boolean(
      pokemon?.volatiles?.neutralizinggas &&
        cleanId(pokemon?.ability) !== "neutralizinggas",
    ),
    paradoxSource: String(pokemon?.abilityState?.paradoxSource ?? ""),
    paradoxStat: String(pokemon?.abilityState?.paradoxStat ?? ""),
  };
}

const SHARED_EFFECTIVE_STAT_CACHE_LIMIT = 16384;
const sharedEffectiveStatCache = new Map();

function effectiveStat(pokemon, stat, options = {}) {
  if (!Number.isFinite(Number(pokemon?.stats?.[stat]))) return Number.NaN;
  const inputJson = JSON.stringify({
    pokemon: sharedEffectiveStatPokemon(pokemon),
    stat,
    weather: effectiveWeather(options.state),
    terrain: String(options.state?.field?.terrain?.id ?? ""),
    ignoreStages: options.ignoreStages === true,
    ignoreNegative: options.ignoreNegative === true,
    ignorePositive: options.ignorePositive === true,
  });
  const cached = sharedEffectiveStatCache.get(inputJson);
  if (cached != null) return cached;
  const value = JSON.parse(evaluateSharedEffectiveStatJson(inputJson)).value;
  if (sharedEffectiveStatCache.size >= SHARED_EFFECTIVE_STAT_CACHE_LIMIT) {
    sharedEffectiveStatCache.clear();
  }
  sharedEffectiveStatCache.set(inputJson, value);
  return value;
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
  let speed = effectiveStat(pokemon, "speed", { state });
  const weather = effectiveWeather(state);
  if (
    (activeAbility(pokemon) === "chlorophyll" &&
      ["sunnyday", "desolateland"].includes(weather)) ||
    (activeAbility(pokemon) === "sandrush" && weather === "sandstorm") ||
    (activeAbility(pokemon) === "slushrush" &&
      ["hail", "snow"].includes(weather)) ||
    (activeAbility(pokemon) === "swiftswim" &&
      ["raindance", "primordialsea"].includes(weather))
  ) {
    speed *= 2;
  }
  if (
    activeAbility(pokemon) === "unburden" &&
    pokemon.abilityState?.unburdenActivated === true
  ) {
    speed *= 2;
  }
  if (
    state &&
    Number.isInteger(sideIndex) &&
    hasSideCondition(state, sideIndex, "tailwind")
  ) {
    speed *= 2;
  }
  return Math.floor(speed);
}

function effectiveWeightPokemon(pokemon) {
  const ability = activeAbility(pokemon);
  if (!["lightmetal", "heavymetal"].includes(ability)) return pokemon;
  return {
    ...pokemon,
    weightKg: Math.max(
      0.1,
      Number(pokemon.weightKg ?? 100) * (ability === "heavymetal" ? 2 : 0.5),
    ),
  };
}

function hasChoiceLockEffect(pokemon) {
  return (
    CHOICE_LOCK_ITEMS.has(cleanId(pokemon.item)) ||
    (activeAbility(pokemon) === "gorillatactics" && pokemon.dynamaxTurns <= 0)
  );
}

function isStellarTerastallized(pokemon) {
  return (
    pokemon?.terastallized === true &&
    cleanId(pokemon?.teraType) === "stellar"
  );
}

function isTerapagosStellar(pokemon) {
  const speciesId = cleanId(
    pokemon?.species ?? pokemon?.id ?? pokemon?.name,
  );
  return (
    isStellarTerastallized(pokemon) &&
    (speciesId === "terapagos" || speciesId === "terapagosstellar")
  );
}

function teraModifiedMove(attacker, move) {
  if (!attacker?.terastallized || move?.teraResolved === true) return move;
  const moveId = cleanId(move?.id);
  if (moveId === "terablast") {
    const teraType = attacker.teraType || attacker.types?.[0] || "Normal";
    const usesPhysicalAttack =
      effectiveStat(attacker, "attack") >
      effectiveStat(attacker, "specialAttack");
    return {
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
      teraResolved: true,
    };
  }
  if (moveId === "terastarstorm" && isTerapagosStellar(attacker)) {
    const usesPhysicalAttack =
      effectiveStat(attacker, "attack") >
      effectiveStat(attacker, "specialAttack");
    return {
      ...move,
      type: "Stellar",
      category: usesPhysicalAttack ? "Physical" : "Special",
      teraResolved: true,
    };
  }
  return move;
}

function teraPowerAdjustedMove(attacker, move) {
  if (
    !attacker?.terastallized ||
    move?.category === "Status" ||
    move?.power <= 0 ||
    move?.power >= 60 ||
    Number(move?.priority ?? 0) > 0 ||
    move?.multihit ||
    move?.dynamicPower
  ) {
    return move;
  }
  const moveType = cleanId(move.type);
  const receivesTeraFloor = isStellarTerastallized(attacker)
    ? !new Set(
        (attacker.stellarBoostedTypes ?? []).map((type) => cleanId(type)),
      ).has(moveType)
    : moveType === cleanId(attacker.teraType);
  return receivesTeraFloor ? { ...move, power: 60, teraPowerFloor: true } : move;
}

function resolveEstimatedMovePower(
  attacker,
  defender,
  move,
  state,
  attackerSide,
  defenderSide,
) {
  move = teraModifiedMove(attacker, move);
  let estimatedMove = move;
  if (move.dynamicPower || SUPPORTED_DYNAMIC_POWER_MOVES.has(cleanId(move.id))) {
    const dynamicPower = resolveDynamicPower(move, {
      state,
      attackerSide,
      defenderSide,
      attacker: effectiveWeightPokemon(attacker),
      defender: effectiveWeightPokemon(defender),
      attackerSpeed: effectiveSpeed(attacker, state, attackerSide),
      defenderSpeed: effectiveSpeed(defender, state, defenderSide),
      effectiveness: moveEffectiveness(move, defender.types, attacker, defender),
    });
    if (dynamicPower.supported) {
      estimatedMove = { ...move, power: dynamicPower.power };
    }
  }
  return teraPowerAdjustedMove(attacker, estimatedMove);
}

function damageBase(attacker, defender, move, options = {}) {
  const stats = JSON.parse(
    evaluateSharedDamageStatsJson(
      JSON.stringify(sharedDamageStatInput(attacker, defender, move, options)),
    ),
  );
  const { attack, defence } = stats;
  if (
    ![attacker.level, move.power, attack, defence].every(Number.isFinite)
  ) {
    return Number.NaN;
  }
  return calculateSharedBaseDamage(
    attacker.level,
    move.power,
    attack,
    defence,
  );
}

function sharedDamageStatInput(attacker, defender, move, options = {}) {
  return {
    attacker: sharedEffectiveStatPokemon(attacker),
    defender: sharedEffectiveStatPokemon(defender),
    category: String(move?.category ?? "Physical"),
    weather: effectiveWeather(options.state),
    terrain: String(options.state?.field?.terrain?.id ?? ""),
    critical: options.critical === true,
  };
}

function sharedDamageTypePokemon(pokemon) {
  const hp = Number(pokemon?.hp ?? 0);
  const maximumHp = Number(pokemon?.stats?.hp ?? 1);
  return {
    id: String(pokemon?.id ?? pokemon?.name ?? ""),
    types: pokemon?.types ?? [],
    originalTypes: pokemon?.originalTypes ?? pokemon?.types ?? [],
    ability: String(pokemon?.ability ?? ""),
    item: String(pokemon?.item ?? ""),
    hp: Number.isFinite(hp) ? Math.floor(hp) : 0,
    maximumHp: Number.isFinite(maximumHp) ? Math.max(1, Math.floor(maximumHp)) : 1,
    terastallized: pokemon?.terastallized === true,
    teraType: String(pokemon?.teraType ?? ""),
    stellarBoostedTypes: pokemon?.stellarBoostedTypes ?? [],
    gastroAcid: Boolean(pokemon?.volatiles?.gastroacid),
    neutralizingGasSuppressed: Boolean(
      pokemon?.volatiles?.neutralizinggas &&
        cleanId(pokemon?.ability) !== "neutralizinggas",
    ),
    teraShellActive: pokemon?.abilityState?.teraShellActive === true,
  };
}

function sharedDamageTypeInput(attacker, defender, move, state) {
  return {
    attacker: sharedDamageTypePokemon(attacker),
    defender: sharedDamageTypePokemon(defender),
    move: {
      id: String(move?.id ?? ""),
      type: String(move?.type ?? "Normal"),
      sound: isSoundMove(move),
    },
    weather: effectiveWeather(state),
  };
}

function sharedDamageModifierPokemon(pokemon) {
  const hp = Number(pokemon?.hp ?? 0);
  const maximumHp = Number(pokemon?.stats?.hp ?? 1);
  const consecutiveMoveCount = Number(pokemon?.consecutiveMove?.count ?? 0);
  return {
    id: String(pokemon?.id ?? pokemon?.name ?? ""),
    familyId: pokemonFamilyId(pokemon),
    types: pokemon?.types ?? [],
    ability: String(pokemon?.ability ?? ""),
    item: String(pokemon?.item ?? ""),
    gender: String(pokemon?.gender ?? ""),
    hp: Number.isFinite(hp) ? Math.floor(hp) : 0,
    maximumHp: Number.isFinite(maximumHp) ? Math.max(1, Math.floor(maximumHp)) : 1,
    gastroAcid: Boolean(pokemon?.volatiles?.gastroacid),
    neutralizingGasSuppressed: Boolean(
      pokemon?.volatiles?.neutralizinggas &&
        cleanId(pokemon?.ability) !== "neutralizinggas",
    ),
    flashFireBoosted: pokemon?.abilityState?.flashFireBoosted === true,
    helpingHand: Boolean(pokemon?.volatiles?.helpinghand),
    tarShot: Boolean(pokemon?.volatiles?.tarshot),
    consecutiveMoveId: String(pokemon?.consecutiveMove?.id ?? ""),
    consecutiveMoveCount: Number.isFinite(consecutiveMoveCount)
      ? Math.floor(consecutiveMoveCount)
      : 0,
  };
}

function sharedDamageModifierInput(attacker, defender, move, context) {
  const defenderAlreadyActed =
    typeof context.defenderActed === "boolean"
      ? context.defenderActed
      : context.state?.currentActions
        ? defender.turnState?.acted === true
        : Number.isInteger(context.attackerSide) &&
            Number.isInteger(context.defenderSide) &&
            context.state
          ? effectiveSpeed(attacker, context.state, context.attackerSide) <
            effectiveSpeed(defender, context.state, context.defenderSide)
          : false;
  const attackerFaintedAllies =
    context.state && Number.isInteger(context.attackerSide)
      ? context.state.sides[context.attackerSide].team.filter(
          (pokemon) => pokemon !== attacker && (pokemon.fainted || pokemon.hp <= 0),
        ).length
      : 0;
  const activeAbilitySources = (context.state?.sides ?? []).map(
    (_, sideIndex) => {
      const pokemon = activePokemon(context.state, sideIndex);
      return {
        ability: String(pokemon?.ability ?? ""),
        fainted: pokemon?.fainted === true,
        gastroAcid: Boolean(pokemon?.volatiles?.gastroacid),
        neutralizingGasSuppressed: Boolean(
          pokemon?.volatiles?.neutralizinggas &&
            cleanId(pokemon?.ability) !== "neutralizinggas",
        ),
      };
    },
  );
  const defenderSideConditions = Number.isInteger(context.defenderSide)
    ? Object.entries(
        context.state?.sides?.[context.defenderSide]?.conditions ?? {},
      )
        .filter(([, condition]) => Number(condition?.turns ?? 0) > 0)
        .map(([id]) => id)
    : [];
  const movePower = Number(move?.power ?? 0);
  return {
    attacker: sharedDamageModifierPokemon(attacker),
    defender: sharedDamageModifierPokemon(defender),
    move: {
      id: String(move?.id ?? ""),
      type: String(move?.type ?? "Normal"),
      category: String(move?.category ?? "Status"),
      power: Number.isFinite(movePower) ? movePower : 0,
      contact: makesContact(move),
      punch: hasMoveFlag(move, "punch"),
      bite: hasMoveFlag(move, "bite"),
      slicing: hasMoveFlag(move, "slicing"),
      recoil: Boolean(move?.recoil),
      hasSecondaries: Array.isArray(move?.secondaries) && move.secondaries.length > 0,
    },
    weather: effectiveWeather(context.state),
    terrain: String(context.state?.field?.terrain?.id ?? ""),
    activeAbilitySources,
    defenderSideConditions,
    defenderAlreadyActed,
    attackerFaintedAllies,
    critical: context.critical === true,
  };
}

const SHARED_DAMAGE_PIPELINE_CACHE_LIMIT = 16384;
const sharedDamagePipelineCache = new Map();

function sharedDamageFactorsInput(attacker, defender, move, context) {
  return {
    type: sharedDamageTypeInput(attacker, defender, move, context.state),
    modifiers: sharedDamageModifierInput(attacker, defender, move, context),
  };
}

function sharedDamagePipelineResult(attacker, defender, move, context) {
  const inputJson = JSON.stringify({
    level: attacker.level,
    power: move.power,
    stats: sharedDamageStatInput(attacker, defender, move, context),
    factors: sharedDamageFactorsInput(attacker, defender, move, context),
  });
  const cached = sharedDamagePipelineCache.get(inputJson);
  if (cached) return cached;
  const result = JSON.parse(evaluateSharedDamagePipelineJson(inputJson));
  if (sharedDamagePipelineCache.size >= SHARED_DAMAGE_PIPELINE_CACHE_LIMIT) {
    sharedDamagePipelineCache.clear();
  }
  sharedDamagePipelineCache.set(inputJson, result);
  return result;
}

export function calculateDamageRange(attacker, defender, move, context = {}) {
  attacker = proteanPreviewPokemon(attacker, move);
  attacker = stanceChangePreviewPokemon(attacker, move);
  move = abilityModifiedMove(attacker, move);
  move = teraModifiedMove(attacker, move);
  move = teraPowerAdjustedMove(attacker, move);
  if (
    (move.category === "Status" || move.power <= 0) &&
    fixedDamageAmount(move, attacker, defender) === null
  ) {
    return { minimum: 0, maximum: 0, stab: 1, effectiveness: 1 };
  }
  if (!Number.isFinite(attacker.level) || !Number.isFinite(move.power)) {
    const factors = JSON.parse(
      evaluateSharedDamageFactorsJson(
        JSON.stringify(sharedDamageFactorsInput(attacker, defender, move, context)),
      ),
    );
    return {
      minimum: Number.NaN,
      maximum: Number.NaN,
      baseDamage: Number.NaN,
      totalModifier: Number.NaN,
      ...factors,
    };
  }
  const pipeline = sharedDamagePipelineResult(attacker, defender, move, context);
  return {
    minimum: pipeline.minimum,
    maximum: pipeline.maximum,
    baseDamage: pipeline.baseDamage,
    stab: pipeline.stab,
    effectiveness: pipeline.effectiveness,
    itemModifier: pipeline.itemModifier,
    abilityModifier: pipeline.abilityModifier,
    fieldModifier: pipeline.fieldModifier,
  };
}

export function calculateMovePreview(attacker, defender, move, context = {}) {
  move = abilityModifiedMove(attacker, move);
  move = teraModifiedMove(attacker, move);
  const estimatedMove = resolveEstimatedMovePower(
    attacker,
    defender,
    move,
    context.state,
    context.attackerSide,
    context.defenderSide,
  );
  if (isMoveBlockedByDynamaxTarget(estimatedMove, defender)) {
    return {
      move: estimatedMove,
      range: {
        minimum: 0,
        maximum: 0,
        stab: 1,
        effectiveness: 1,
        itemModifier: 1,
        abilityModifier: 1,
        fieldModifier: 1,
      },
    };
  }
  return {
    move: estimatedMove,
    range: calculateDamageRange(attacker, defender, estimatedMove, context),
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
    gimmickProfile: String(setup.gimmickProfile ?? "cobbleventure_all"),
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
      selection: "lead",
    })),
    futureAttacks: [],
    lastSuccessfulMove: null,
    aiTrace: [],
    warnings: [],
  };
  syncNeutralizingGas(state);
  for (let sideIndex = 0; sideIndex < state.sides.length; sideIndex += 1) {
    applyEntryAbilities(state, sideIndex, activePokemon(state, sideIndex));
  }
  return state;
}

function activePokemon(state, sideIndex) {
  const side = state.sides[sideIndex];
  return side.team[side.active];
}

function syncNeutralizingGas(state) {
  const active = [0, 1]
    .map((side) => ({ side, pokemon: activePokemon(state, side) }))
    .filter(({ pokemon }) => pokemon && !pokemon.fainted);
  const gasUsers = active.filter(
    ({ pokemon }) => activeAbility(pokemon) === "neutralizinggas",
  );
  for (const { pokemon } of active) {
    const shouldSuppress =
      pokemon.ability !== "neutralizinggas" &&
      gasUsers.some(({ pokemon: gasUser }) => gasUser !== pokemon);
    if (shouldSuppress) {
      pokemon.volatiles.neutralizinggas ??= {
        id: "neutralizinggas",
      };
    } else {
      delete pokemon.volatiles.neutralizinggas;
    }
  }
}

function emitAbilityActivation(state, side, pokemon, ability, details = {}) {
  state.events.push({
    turn: state.turn,
    type: "ability_activate",
    side,
    pokemon: pokemon.name,
    ability,
    ...details,
  });
}

function applySpeciesForm(
  state,
  sideIndex,
  pokemon,
  form,
  source,
  options = {},
) {
  if (!form) return false;
  const previousName = pokemon.name;
  const previousMaximumHp = pokemon.stats.hp;
  const previousDamage = Math.max(0, previousMaximumHp - pokemon.hp);
  const nextStats = { ...pokemon.stats, ...(form.stats ?? {}) };
  pokemon.id = form.id || pokemon.id;
  pokemon.name = form.name || pokemon.name;
  pokemon.ability = cleanId(form.ability || pokemon.ability);
  pokemon.weightKg = Math.max(
    0.1,
    Number(form.weightKg ?? pokemon.weightKg),
  );
  pokemon.stats = nextStats;
  pokemon.baseMaximumHp = nextStats.hp;
  pokemon.hp =
    pokemon.hp <= 0
      ? 0
      : Math.max(1, nextStats.hp - previousDamage);
  if (!options.preserveBattleTypes && form.types?.length) {
    pokemon.types = form.types.slice();
    pokemon.originalTypes = form.types.slice();
  }
  pokemon.currentForm = pokemon.id;
  pokemon.abilityState = {};
  state.events.push({
    turn: state.turn,
    type: "form_change",
    side: sideIndex,
    pokemon: pokemon.name,
    fromPokemon: previousName,
    form: pokemon.id,
    source,
    remainingHp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
  });
  return true;
}

function isZenModeForm(pokemon) {
  return Boolean(
    pokemon?.speciesForms?.zen &&
      cleanId(pokemon.id) === cleanId(pokemon.speciesForms.zen.id),
  );
}

function updateZenModeForm(state, sideIndex, pokemon) {
  if (
    !pokemon ||
    pokemon.fainted ||
    pokemon.dynamaxTurns > 0 ||
    activeAbility(pokemon) !== "zenmode"
  ) {
    return false;
  }
  const shouldBeZen = pokemon.hp <= Math.floor(pokemon.stats.hp / 2);
  const targetForm = shouldBeZen
    ? pokemon.speciesForms?.zen
    : pokemon.speciesForms?.base;
  if (!targetForm || cleanId(targetForm.id) === cleanId(pokemon.id)) return false;
  emitAbilityActivation(state, sideIndex, pokemon, "zenmode", {
    form: targetForm.id,
  });
  return applySpeciesForm(state, sideIndex, pokemon, targetForm, "zenmode");
}

function revertZenModeForm(state, sideIndex, pokemon) {
  const baseForm = pokemon?.speciesForms?.base;
  if (!baseForm || !isZenModeForm(pokemon)) return false;
  return applySpeciesForm(state, sideIndex, pokemon, baseForm, "zenmode");
}

function stanceChangeTargetForm(pokemon, move) {
  if (
    !pokemon ||
    pokemon.dynamaxTurns > 0 ||
    activeAbility(pokemon) !== "stancechange" ||
    pokemonFamilyId(pokemon) !== "aegislash"
  ) {
    return null;
  }
  const moveId = cleanId(move?.id);
  if (moveId === "kingsshield") return pokemon.speciesForms?.shield ?? null;
  if (move?.category !== "Status") return pokemon.speciesForms?.blade ?? null;
  return null;
}

function stanceChangePreviewPokemon(pokemon, move) {
  const form = stanceChangeTargetForm(pokemon, move);
  if (!form || cleanId(form.id) === cleanId(pokemon.id)) return pokemon;
  return {
    ...pokemon,
    id: form.id || pokemon.id,
    name: form.name || pokemon.name,
    types: form.types?.length ? form.types.slice() : pokemon.types,
    originalTypes: form.types?.length
      ? form.types.slice()
      : pokemon.originalTypes,
    stats: { ...pokemon.stats, ...(form.stats ?? {}) },
  };
}

function applyStanceChange(state, sideIndex, pokemon, move) {
  const form = stanceChangeTargetForm(pokemon, move);
  if (!form || cleanId(form.id) === cleanId(pokemon.id)) return false;
  emitAbilityActivation(state, sideIndex, pokemon, "stancechange", {
    move: move.name,
    form: form.id,
  });
  return applySpeciesForm(
    state,
    sideIndex,
    pokemon,
    form,
    "stancechange",
  );
}

function proteanTargetType(pokemon, move) {
  if (
    !pokemon ||
    pokemon.terastallized ||
    pokemon.dynamaxTurns > 0 ||
    activeAbility(pokemon) !== "protean" ||
    pokemon.abilityState?.proteanUsed === true ||
    !move?.type ||
    cleanId(move.id) === "struggle"
  ) {
    return "";
  }
  const alreadySameType =
    pokemon.types.length === 1 && cleanId(pokemon.types[0]) === cleanId(move.type);
  return alreadySameType ? "" : move.type;
}

function proteanPreviewPokemon(pokemon, move) {
  const type = proteanTargetType(pokemon, move);
  return type ? { ...pokemon, types: [type] } : pokemon;
}

function applyProtean(state, sideIndex, pokemon, move) {
  const type = proteanTargetType(pokemon, move);
  if (!type) return false;
  pokemon.abilityState ??= {};
  pokemon.abilityState.proteanUsed = true;
  emitAbilityActivation(state, sideIndex, pokemon, "protean", {
    move: move.name,
    type,
  });
  return setPokemonTypes(state, sideIndex, pokemon, [type], "protean");
}

function applyTeraShiftOnEntry(state, sideIndex, pokemon) {
  if (
    !isTerapagosPokemon(pokemon) ||
    pokemon.terastallized ||
    cleanId(pokemon.id) === "terapagosterastal"
  ) {
    return false;
  }
  const form =
    pokemon.speciesForms?.terastal ?? {
      id: "terapagosterastal",
      name: "Terapagos-Terastal",
      types: ["Normal"],
      ability: "terashell",
      weightKg: pokemon.weightKg,
      stats: pokemon.stats,
    };
  emitAbilityActivation(state, sideIndex, pokemon, "terashift");
  return applySpeciesForm(
    state,
    sideIndex,
    pokemon,
    form,
    "terashift",
  );
}

function clearTeraformZeroField(state, sideIndex, pokemon) {
  const cleared = [];
  for (const fieldKind of ["weather", "terrain"]) {
    const effect = state.field?.[fieldKind];
    if (!effect) continue;
    cleared.push({ fieldKind, effect: effect.id });
    state.field[fieldKind] = null;
  }
  if (cleared.length === 0) return false;
  emitAbilityActivation(state, sideIndex, pokemon, "teraformzero");
  for (const entry of cleared) {
    state.events.push({
      turn: state.turn,
      type: "field_end",
      side: sideIndex,
      pokemon: pokemon.name,
      source: "teraformzero",
      ...entry,
    });
  }
  return true;
}

function applySpeciesTerastallization(state, sideIndex, pokemon) {
  const ogerponProfile = ogerponTeraProfile(pokemon);
  if (ogerponProfile) {
    const form = pokemon.speciesForms?.tera ?? {
      ...ogerponProfile,
      types: pokemon.originalTypes,
      weightKg: pokemon.weightKg,
      stats: pokemon.stats,
    };
    applySpeciesForm(
      state,
      sideIndex,
      pokemon,
      {
        ...form,
        id: ogerponProfile.id,
        name: ogerponProfile.name,
        ability: ogerponProfile.ability,
      },
      "terastallize",
      { preserveBattleTypes: true },
    );
    pokemon.teraType = ogerponProfile.type;
    pokemon.types = [ogerponProfile.type];
    pokemon.terastallized = true;
    pokemon.stellarBoostedTypes = [];
    emitAbilityActivation(
      state,
      sideIndex,
      pokemon,
      ogerponProfile.ability,
    );
    applyBoosts(
      state,
      sideIndex,
      pokemon,
      ogerponProfile.boosts,
      ogerponProfile.ability,
    );
    return true;
  }
  pokemon.teraType = canonicalTypeName(pokemon.configuredTeraType);
  if (isTerapagosPokemon(pokemon)) {
    const form =
      pokemon.speciesForms?.stellar ?? {
        id: "terapagosstellar",
        name: "Terapagos-Stellar",
        types: ["Normal"],
        ability: "teraformzero",
        weightKg: pokemon.weightKg,
        stats: pokemon.stats,
      };
    applySpeciesForm(
      state,
      sideIndex,
      pokemon,
      form,
      "terastallize",
      { preserveBattleTypes: true },
    );
  }
  if (cleanId(pokemon.teraType) !== "stellar") {
    pokemon.types = [pokemon.teraType];
  }
  pokemon.terastallized = true;
  pokemon.stellarBoostedTypes = [];
  if (isTerapagosPokemon(pokemon)) {
    clearTeraformZeroField(state, sideIndex, pokemon);
  }
  return true;
}

function initializeParadoxAbility(state, sideIndex, pokemon, ability) {
  if (!["protosynthesis", "quarkdrive"].includes(ability)) return;
  const previousSource = pokemon.abilityState?.paradoxSource;
  const fieldStat = paradoxBoostStat(pokemon, state);
  const shouldConsumeBooster =
    !fieldStat && cleanId(pokemon.item) === "boosterenergy";
  if (shouldConsumeBooster) {
    pokemon.abilityState.paradoxSource = "boosterenergy";
    consumeHeldItem(state, sideIndex, pokemon, "Booster Energy");
  }
  const boostedStat = paradoxBoostStat(pokemon, state);
  if (!boostedStat) return;
  pokemon.abilityState.paradoxStat = boostedStat;
  pokemon.abilityState.paradoxSource = shouldConsumeBooster
    ? "boosterenergy"
    : "field";
  if (
    previousSource === pokemon.abilityState.paradoxSource &&
    pokemon.abilityState.paradoxStat === boostedStat
  ) {
    return;
  }
  emitAbilityActivation(state, sideIndex, pokemon, ability, {
    stat: eventStat(boostedStat),
    source: shouldConsumeBooster ? "Booster Energy" : "field",
  });
}

function updateForecastForms(state) {
  const weather = effectiveWeather(state);
  const weatherType =
    ["sunnyday", "desolateland"].includes(weather)
      ? "Fire"
      : ["raindance", "primordialsea"].includes(weather)
        ? "Water"
        : ["hail", "snow"].includes(weather)
          ? "Ice"
          : "";
  for (let sideIndex = 0; sideIndex < state.sides.length; sideIndex += 1) {
    const pokemon = activePokemon(state, sideIndex);
    if (!pokemon || pokemon.fainted || pokemon.terastallized) continue;
    const forecastActive = activeAbility(pokemon) === "forecast";
    if (!forecastActive && pokemon.abilityState?.forecastApplied !== true) continue;
    const nextTypes =
      forecastActive && weatherType
        ? [weatherType]
        : pokemon.originalTypes;
    if (
      pokemon.types.length === nextTypes.length &&
      pokemon.types.every((type, index) => type === nextTypes[index])
    ) {
      continue;
    }
    if (forecastActive) {
      emitAbilityActivation(state, sideIndex, pokemon, "forecast", {
        weather: weather || "none",
        types: [...nextTypes],
      });
    }
    setPokemonTypes(state, sideIndex, pokemon, nextTypes, "forecast");
    if (forecastActive && weatherType) {
      pokemon.abilityState.forecastApplied = true;
    } else {
      delete pokemon.abilityState.forecastApplied;
    }
  }
}

function applyEntryAbilities(state, sideIndex, pokemon, sharedPhase = null) {
  if (!pokemon || pokemon.fainted) return;
  const phase = sharedPhase ?? sharedSwitchPhaseOutcome(state, sideIndex, pokemon);
  const phaseOperation = (code, source = "") =>
    phase.operations.find(
      (operation) => operation.code === code && (!source || operation.source === source),
    );
  const initialAbility = activeAbility(pokemon);
  const initialAdapter = phaseOperation("entry_adapter", initialAbility);
  if (initialAdapter?.effect === "terashift") {
    applyTeraShiftOnEntry(state, sideIndex, pokemon);
  }
  if (initialAdapter?.effect === "forecast") {
    updateForecastForms(state);
  }
  const ability = activeAbility(pokemon);
  pokemon.abilityState ??= {};
  if (phaseOperation("entry_adapter", "neutralizinggas")) {
    emitAbilityActivation(state, sideIndex, pokemon, ability);
  }
  const entryBoost = phaseOperation("entry_boost", ability);
  if (entryBoost && ["intrepidsword", "dauntlessshield"].includes(ability)) {
    if (entryBoost.setState) pokemon.abilityState[entryBoost.setState] = true;
    emitAbilityActivation(state, sideIndex, pokemon, ability);
    applyBoosts(state, sideIndex, pokemon, entryBoost.boosts, ability);
  }
  if (entryBoost && ability.startsWith("embodyaspect")) {
    emitAbilityActivation(state, sideIndex, pokemon, ability);
    applyBoosts(state, sideIndex, pokemon, entryBoost.boosts, ability);
  }
  const entryWeather = phaseOperation("entry_weather", ability);
  if (entryWeather) {
    emitAbilityActivation(state, sideIndex, pokemon, ability);
    setFieldEffect(state, sideIndex, pokemon, "weather", entryWeather.effect, ability);
  }
  const entryTerrain = phaseOperation("entry_terrain", ability);
  if (entryTerrain) {
    emitAbilityActivation(state, sideIndex, pokemon, ability);
    setFieldEffect(state, sideIndex, pokemon, "terrain", entryTerrain.effect, ability);
  }
  if (phaseOperation("illusion", "illusion") && !pokemon.volatiles?.illusion) {
    const disguise = [...state.sides[sideIndex].team]
      .reverse()
      .find(
        (candidate) =>
          candidate !== pokemon && !candidate.fainted && candidate.hp > 0,
      );
    if (disguise) {
      pokemon.volatiles.illusion = {
        id: "illusion",
        displayedId: disguise.id,
        displayedName: disguise.name,
      };
      pokemon.displayName = disguise.name;
      emitAbilityActivation(state, sideIndex, pokemon, "illusion", {
        displayedPokemon: disguise.name,
      });
    }
  }
  const paradox = phaseOperation("entry_adapter", ability);
  if (paradox?.effect === "paradox") {
    const previousSource = pokemon.abilityState?.paradoxSource;
    const previousStat = pokemon.abilityState?.paradoxStat;
    if (paradox.consumeItem) consumeHeldItem(state, sideIndex, pokemon, "Booster Energy");
    pokemon.abilityState.paradoxStat = paradox.details?.stat ?? "";
    pokemon.abilityState.paradoxSource = paradox.details?.source ?? "";
    if (
      previousSource !== pokemon.abilityState.paradoxSource ||
      previousStat !== pokemon.abilityState.paradoxStat
    ) {
      emitAbilityActivation(state, sideIndex, pokemon, ability, {
        stat: eventStat(pokemon.abilityState.paradoxStat),
        source: pokemon.abilityState.paradoxSource === "boosterenergy"
          ? "Booster Energy"
          : "field",
      });
    }
  }
  const targetSide = sideIndex === 0 ? 1 : 0;
  const target = activePokemon(state, targetSide);
  if (!target || target.fainted) return;
  const adapter = phaseOperation("entry_adapter", ability);
  if (adapter?.effect === "imposter" && !pokemon.volatiles?.transform) {
    emitAbilityActivation(state, sideIndex, pokemon, ability, {
      targetSide,
      target: target.name,
    });
    applyTransform(state, sideIndex, pokemon, target, ability);
    return;
  }
  if (adapter?.effect === "trace") {
    const copiedAbility = cleanId(adapter.details?.copiedAbility);
    if (copiedAbility) {
      pokemon.abilityState.tracedOriginalAbility =
        cleanId(pokemon.baseAbility) || "trace";
      pokemon.abilityState.tracedAbility = copiedAbility;
      pokemon.ability = copiedAbility;
      emitAbilityActivation(state, sideIndex, pokemon, "trace", {
        targetSide,
        target: target.name,
        copiedAbility,
      });
      applyEntryAbilities(state, sideIndex, pokemon);
      return;
    }
  }
  if (adapter?.effect === "forewarn") {
    const threateningMove = target.moves.find(
      (move) => cleanId(move.id) === cleanId(adapter.details?.moveId),
    );
    if (threateningMove) {
      emitAbilityActivation(state, sideIndex, pokemon, "forewarn", {
        targetSide,
        target: target.name,
        move: threateningMove.name,
        moveId: cleanId(threateningMove.id),
        power: Number(adapter.details?.power ?? 0),
      });
    }
  }
  if (adapter?.effect === "anticipation") {
    const threateningMoves = String(adapter.details?.threateningMoves ?? "")
      .split(",")
      .filter(Boolean);
    if (threateningMoves.length > 0) {
      emitAbilityActivation(state, sideIndex, pokemon, "anticipation", {
        targetSide,
        target: target.name,
        threateningMoves,
      });
    }
  }
  if (adapter?.effect === "frisk") {
    emitAbilityActivation(state, sideIndex, pokemon, ability, {
      targetSide,
      target: target.name,
      item: adapter.details?.item ?? target.item,
    });
  }
  const download = phaseOperation("entry_boost", "download");
  if (download) {
    emitAbilityActivation(state, sideIndex, pokemon, ability, {
      targetSide,
      target: target.name,
    });
    applyBoosts(state, sideIndex, pokemon, download.boosts, ability);
    return;
  }
  const intimidate = phaseOperation("entry_boost", "intimidate");
  if (!intimidate) return;
  emitAbilityActivation(state, sideIndex, pokemon, ability, {
    targetSide,
    target: target.name,
  });
  applyBoosts(state, targetSide, target, intimidate.boosts, ability, sideIndex);
}

function beastBoostStat(pokemon) {
  if (!pokemon?.stats) return "";
  return ["attack", "defence", "specialAttack", "specialDefence", "speed"]
    .sort((left, right) => pokemon.stats[right] - pokemon.stats[left])[0];
}

function knockoutAbilityBoosts(ability, pokemon = null) {
  const id = cleanId(ability);
  if (id === "chillingneigh" || id === "asoneglastrier") {
    return { attack: 1 };
  }
  if (id === "grimneigh" || id === "asonespectrier") {
    return { specialAttack: 1 };
  }
  if (id === "beastboost") {
    const stat = beastBoostStat(pokemon);
    return stat ? { [stat]: 1 } : null;
  }
  if (id === "moxie") {
    return { attack: 1 };
  }
  return null;
}

function applyKnockoutAbility(state, sideIndex, pokemon, defeatedPokemon) {
  if (!pokemon || pokemon.fainted || !defeatedPokemon?.fainted) return false;
  const ability = activeAbility(pokemon);
  const boosts = knockoutAbilityBoosts(ability, pokemon);
  if (!boosts) return false;
  state.events.push({
    turn: state.turn,
    type: "ability_activate",
    side: sideIndex,
    pokemon: pokemon.name,
    ability,
    targetSide: sideIndex === 0 ? 1 : 0,
    target: defeatedPokemon.name,
  });
  return applyBoosts(state, sideIndex, pokemon, boosts, ability);
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
  if (hasChoiceLockEffect(pokemon) && pokemon.choiceLock?.id) {
    const index = pokemon.moves.findIndex(
      (move) => cleanId(move.id) === cleanId(pokemon.choiceLock.id) && move.pp > 0,
    );
    if (index < 0) return null;
    return {
      move: pokemon.moves[index],
      slot: index + 1,
      lockSource:
        activeAbility(pokemon) === "gorillatactics"
          ? "gorillatactics"
          : "choice",
      preventsSwitch: false,
      noPpCost: false,
    };
  }
  if (!hasChoiceLockEffect(pokemon)) {
    pokemon.choiceLock = null;
  }
  return null;
}

function buildActions(state, commands) {
  const sharedState = toSharedBattleState(state);
  const sharedCommands = toSharedTurnCommands(commands, sharedState);
  const built = JSON.parse(
    buildSharedBattleActionsJson(
      JSON.stringify(sharedState),
      JSON.stringify(sharedCommands),
    ),
  );
  for (const cleanup of built.cleanups) {
    const pokemon = activePokemon(state, cleanup.side);
    if (cleanup.clearEncore) delete pokemon.volatiles.encore;
    if (cleanup.clearLockedMove) pokemon.lockedMove = null;
    if (cleanup.clearChoiceLock) pokemon.choiceLock = null;
    if (cleanup.clearChargingMove) pokemon.chargingMove = null;
  }
  return built.actions.map((action) => {
    const pokemon = activePokemon(state, action.side);
    const selected = action.moveSlot == null
      ? null
      : { move: pokemon.moves[action.moveSlot - 1], slot: action.moveSlot };
    return {
      kind: action.kind,
      side: action.side,
      pokemon,
      item: action.item ?? "",
      targetSlot: action.itemTargetSlot ?? null,
      switchSlot: action.switchSlot ?? null,
      selected,
      selfSwitchSlot: action.selfSwitchSlot ?? null,
      locked: action.locked,
      lockSource: action.lockSource,
      noPpCost: action.noPpCost,
      chargingRelease: action.chargingRelease,
      gimmick: action.gimmick,
      teraType: action.teraType,
      priority:
        action.kind === "item"
          ? 10_002
          : action.kind === "switch"
            ? 10_000
            : selected?.move?.priority ?? 0,
    };
  });
}

function applySharedActionOrder(state, actions, rng) {
  const ordered = JSON.parse(
    orderSharedBattleActionsJson(
      JSON.stringify(toSharedBattleState(state)),
      JSON.stringify({
        rngState: rng.snapshot(),
        actions: actions.map((action, inputIndex) => ({
          inputIndex,
          side: action.side,
          kind: action.kind,
          moveSlot: action.selected?.slot ?? null,
        })),
      }),
    ),
  );
  rng.restore(ordered.rngState);
  const decisions = new Map(
    ordered.actions.map((decision) => [decision.inputIndex, decision]),
  );
  for (const [inputIndex, action] of actions.entries()) {
    const decision = decisions.get(inputIndex);
    Object.assign(action, decision);
    if (decision.quickDraw) {
      emitAbilityActivation(
        state,
        action.side,
        activePokemon(state, action.side),
        "quickdraw",
        { move: action.selected?.move?.name },
      );
    }
    if (decision.quickClaw) {
      const pokemon = activePokemon(state, action.side);
      state.events.push({
        turn: state.turn,
        type: "item_activate",
        side: action.side,
        pokemon: pokemon.name,
        item: "quickclaw",
        move: action.selected?.move?.name,
      });
    }
    if (decision.custap) {
      const pokemon = activePokemon(state, action.side);
      consumeHeldItem(state, action.side, pokemon, action.selected?.move?.name);
      markBerryEaten(pokemon, "custapberry");
    }
  }
  return ordered.actions.map((decision) => actions[decision.inputIndex]);
}

function nativeMaxMovePriority(pokemon, move) {
  const maxMove = resolveNativeMaxMove(pokemon, move);
  return cleanId(maxMove.id) === "maxguard" ? 4 : 0;
}

const TRAINER_BATTLE_ITEMS = {
  fullrestore: {
    name: "풀회복약",
    heal: "full",
    cureStatus: true,
  },
  potion: {
    name: "회복약",
    heal: 20,
    cureStatus: false,
  },
  fullheal: {
    name: "만병통치제",
    heal: 0,
    cureStatus: true,
  },
};

function trainerItemEntry(side, item) {
  return side.bag.find((entry) => cleanId(entry.item) === cleanId(item)) ?? null;
}

function trainerItemTarget(state, sideIndex, targetSlot = null) {
  const side = state.sides[sideIndex];
  const targetIndex = Number(targetSlot) - 1;
  if (Number.isInteger(targetIndex) && targetIndex >= 0) {
    return side.team[targetIndex] ?? null;
  }
  return activePokemon(state, sideIndex);
}

function canUseTrainerItem(state, sideIndex, item, targetSlot = null) {
  const side = state.sides[sideIndex];
  const pokemon = trainerItemTarget(state, sideIndex, targetSlot);
  const effect = TRAINER_BATTLE_ITEMS[cleanId(item)];
  const entry = trainerItemEntry(side, item);
  if (
    !effect ||
    !entry ||
    entry.quantity <= 0 ||
    side.itemUsesRemaining <= 0 ||
    !pokemon ||
    pokemon.fainted ||
    pokemon.hp <= 0
  ) {
    return false;
  }
  const canHeal =
    effect.heal !== 0 && pokemon.hp < pokemon.stats.hp;
  const canCure =
    effect.cureStatus &&
    (Boolean(pokemon.status) || Boolean(pokemon.volatiles?.confusion));
  return canHeal || canCure;
}

function executeTrainerItem(state, action) {
  const side = state.sides[action.side];
  const pokemon = trainerItemTarget(state, action.side, action.targetSlot);
  if (
    !pokemon ||
    !canUseTrainerItem(
      state,
      action.side,
      action.item,
      action.targetSlot,
    )
  ) {
    state.events.push({
      turn: state.turn,
      type: "item_failed",
      side: action.side,
      pokemon: pokemon?.name ?? action.pokemon?.name ?? "",
      item: action.item,
    });
    return false;
  }
  const effect = TRAINER_BATTLE_ITEMS[cleanId(action.item)];
  const entry = trainerItemEntry(side, action.item);
  entry.quantity -= 1;
  side.itemUsesRemaining -= 1;
  state.events.push({
    turn: state.turn,
    type: "trainer_item",
    side: action.side,
    pokemon: pokemon.name,
    item: action.item,
    itemName: effect.name,
    targetSlot: side.team.indexOf(pokemon) + 1,
    quantityRemaining: entry.quantity,
    usesRemaining: side.itemUsesRemaining,
  });
  if (effect.cureStatus && pokemon.status) {
    const status = pokemon.status;
    pokemon.status = "";
    pokemon.statusTurns = 0;
    pokemon.toxicCounter = 0;
    state.events.push({
      turn: state.turn,
      type: "status_cured",
      side: action.side,
      pokemon: pokemon.name,
      status,
      source: effect.name,
    });
  }
  if (effect.cureStatus && pokemon.volatiles?.confusion) {
    delete pokemon.volatiles.confusion;
    state.events.push({
      turn: state.turn,
      type: "volatile_end",
      side: action.side,
      pokemon: pokemon.name,
      effect: "confusion",
      source: effect.name,
    });
  }
  if (effect.heal !== 0) {
    healPokemon(
      state,
      action.side,
      pokemon,
      effect.heal === "full" ? pokemon.stats.hp : effect.heal,
      effect.name,
    );
  }
  return true;
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

function validateGimmickRequest(state, action) {
  const pokemon = action.pokemon;
  const gimmicks = pokemon.gimmicks ?? {};

  if (action.gimmick === "mega") {
    const conflict = pokemonGimmickConflict(pokemon, "mega");
    if (conflict === "dynamax") {
      return "mega_blocked_by_dynamax";
    }
    if (conflict === "terastallize") return "mega_blocked_by_tera";
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
    const speciesIds = new Set([cleanId(pokemon.id), cleanId(pokemon.name)]);
    if (
      crystal.users?.length &&
      !crystal.users.some((species) => speciesIds.has(cleanId(species)))
    ) {
      return "z_crystal_incompatible";
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

  if (action.gimmick === "dynamax" || action.gimmick === "gigantamax") {
    const conflict = pokemonGimmickConflict(pokemon, action.gimmick);
    if (conflict === "mega") return "dynamax_blocked_by_mega";
    if (conflict === "terastallize") return "dynamax_blocked_by_tera";
    if (gimmicks.canDynamax !== true) return "dynamax_unavailable";
    if (
      action.gimmick === "gigantamax" &&
      gimmicks.gigantamax !== true &&
      gimmicks.canGigantamax !== true
    ) {
      return "gigantamax_unavailable";
    }
    action.dynamaxMode = action.gimmick === "gigantamax" ? "gigantamax" : "dynamax";
  }

  if (action.gimmick === "terastallize") {
    const conflict = pokemonGimmickConflict(pokemon, "terastallize");
    if (conflict === "mega") return "tera_blocked_by_mega";
    if (conflict === "dynamax") {
      return "tera_blocked_by_dynamax";
    }
    if (!canPokemonUseTerastallization(state, action.side, pokemon)) {
      return "tera_reserved_for_configured_pokemon";
    }
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

function gimmickResource(gimmick) {
  return gimmick === "gigantamax" ? "dynamax" : gimmick;
}

function reserveGimmick(state, action) {
  const gimmick = action.gimmick;
  const resource = gimmickResource(gimmick);
  if (!gimmick) return;
  if (!GIMMICK_KINDS.includes(resource)) {
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
  if (side.gimmickResources[resource] !== "available") {
    rejectGimmick(state, action, "resource_unavailable");
    return;
  }
  const validationError = validateGimmickRequest(state, action);
  if (validationError) {
    rejectGimmick(state, action, validationError);
    return;
  }
  side.gimmickResources[resource] = "reserved";
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
  const resource = gimmickResource(action.gimmick);
  side.gimmickResources[resource] = "consumed";
  side.usedGimmicks[resource] = true;
  if (action.gimmick === "gigantamax") {
    side.usedGimmicks.gigantamax = true;
  }
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
  const resource = gimmickResource(action.gimmick);
  if (side.gimmickResources[resource] === "reserved") {
    side.gimmickResources[resource] = "available";
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
  } else if (action.gimmick === "dynamax" || action.gimmick === "gigantamax") {
    pokemon.hp *= 2;
    pokemon.stats.hp *= 2;
    pokemon.hasDynamaxed = true;
    pokemon.dynamaxTurns = 3;
    pokemon.dynamaxMode = action.dynamaxMode;
  } else if (action.gimmick === "terastallize") {
    applySpeciesTerastallization(state, action.side, pokemon);
  }

  emitGimmickActivation(state, action, pokemon);
  consumeGimmick(state, action);
  action.gimmickActivated = true;
}

function prepareActionOrder(state, commands, rng) {
  const actions = buildActions(state, commands);
  for (const action of actions) {
    if (action.kind === "move") reserveGimmick(state, action);
  }
  for (const action of actions) {
    if (action.kind === "move") activatePreMoveGimmick(state, action);
  }
  return applySharedActionOrder(state, actions, rng);
}

function sharedSwitchPhaseOutcome(state, sideIndex, pokemon, outgoing = null) {
  const conditions = state.sides[sideIndex].conditions;
  const opponent = activePokemon(state, sideIndex === 0 ? 1 : 0);
  const disguise = [...state.sides[sideIndex].team]
    .reverse()
    .find((candidate) => candidate !== pokemon && !candidate.fainted && candidate.hp > 0);
  return JSON.parse(
    evaluateSharedSwitchPhaseJson(
      JSON.stringify({
        outgoingHp: Number(outgoing?.hp ?? 0),
        outgoingMaximumHp: Number(outgoing?.stats?.hp ?? 1),
        outgoingAbility: outgoing ? activeAbility(outgoing) : "",
        outgoingStatus: outgoing?.status ?? "",
        outgoingFainted: Boolean(outgoing?.fainted),
        incomingHp: pokemon.hp,
        incomingMaximumHp: pokemon.stats.hp,
        incomingAbility: activeAbility(pokemon),
        incomingSpecies: pokemon.id ?? pokemon.baseSpecies ?? "",
        incomingItem: pokemon.item,
        incomingTypes: pokemon.types,
        incomingStats: Object.fromEntries(
          ["attack", "defence", "specialAttack", "specialDefence", "speed"]
            .map((stat) => [stat, Number(pokemon.stats?.[stat] ?? 0)]),
        ),
        incomingGrounded: isGrounded(pokemon),
        incomingCanPoison: canReceiveStatus(pokemon, "psn", state, sideIndex, null),
        incomingTerastallized: Boolean(pokemon.terastallized),
        incomingStateFlags: Object.entries(pokemon.abilityState ?? {})
          .filter(([, value]) => value === true)
          .map(([key]) => key),
        incomingStateValues: Object.fromEntries(
          Object.entries(pokemon.abilityState ?? {})
            .filter(([, value]) => typeof value === "string"),
        ),
        stealthRockLayers: Number(conditions.stealthrock?.layers ?? 0),
        spikesLayers: Number(conditions.spikes?.layers ?? 0),
        toxicSpikesLayers: Number(conditions.toxicspikes?.layers ?? 0),
        stickyWebLayers: Number(conditions.stickyweb?.layers ?? 0),
        healingWish: Boolean(conditions.healingwish),
        lunarDance: Boolean(conditions.lunardance),
        illusionDisguiseAvailable: Boolean(disguise),
        opponentAlive: Boolean(opponent && !opponent.fainted && opponent.hp > 0),
        opponentAbility: opponent ? activeAbility(opponent) : "",
        opponentDefence: opponent ? effectiveStat(opponent, "defence") : 0,
        opponentSpecialDefence: opponent ? effectiveStat(opponent, "specialDefence") : 0,
        opponentItem: opponent?.item ?? "",
        opponentMoves: (opponent?.moves ?? []).map((move) => ({
          id: cleanId(move.id),
          type: move.type,
          category: move.category,
          power: Number(move.power ?? 0),
          ohko: ["fissure", "guillotine", "horndrill", "sheercold"].includes(cleanId(move.id)),
          dynamicPower: Boolean(move.dynamicPower),
        })),
        weather: effectiveWeather(state),
        terrain: state.field?.terrain?.id ?? "",
      }),
    ),
  );
}

function applyEntryHazards(state, sideIndex, pokemon, sharedPhase = null) {
  const conditions = state.sides[sideIndex].conditions;
  const phase = sharedPhase ?? sharedSwitchPhaseOutcome(state, sideIndex, pokemon);
  for (const operation of phase.operations) {
    if (operation.code === "slot_heal") {
      pokemon.hp = pokemon.stats.hp;
      pokemon.status = "";
      pokemon.statusTurns = 0;
      pokemon.toxicCounter = 0;
      state.events.push({
        turn: state.turn,
        type: "slot_condition_end",
        side: sideIndex,
        pokemon: pokemon.name,
        effect: operation.source,
        source: conditions[operation.source]?.source,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
      });
      delete conditions[operation.source];
    }
    if (operation.code === "heavy_duty_boots") {
      state.events.push({
        turn: state.turn,
        type: "item_activate",
        side: sideIndex,
        pokemon: pokemon.name,
        item: "heavydutyboots",
        source: "entry_hazard",
      });
    }
    if (operation.code === "hazard_blocked") {
      emitAbilityActivation(state, sideIndex, pokemon, "magicguard", {
        source: operation.effect,
        cause: "entry_hazard",
      });
    }
    if (operation.code === "hazard_damage") {
      const applied = Math.min(pokemon.hp, operation.amount);
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: operation.source,
        cause: "entry_hazard",
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      if (pokemon.hp > 0) tryConsumePinchBerry(state, sideIndex, pokemon, operation.source);
      markFainted(state, sideIndex, pokemon);
    }
    if (operation.code === "absorb_toxic_spikes") {
      delete conditions.toxicspikes;
      state.events.push({
        turn: state.turn,
        type: "side_condition_end",
        side: sideIndex,
        pokemon: pokemon.name,
        effect: "toxicspikes",
        reason: "absorbed",
      });
    }
    if (operation.code === "entry_status" && !pokemon.fainted) {
      applyStatus(state, sideIndex, pokemon, operation.status, null, operation.source);
    }
    if (operation.code === "entry_boost" && operation.source === "stickyweb" && !pokemon.fainted) {
      applyBoosts(state, sideIndex, pokemon, operation.boosts, operation.source);
    }
  }
}

function switchActivePokemon(state, sideIndex, switchSlot, options = {}) {
  const side = state.sides[sideIndex];
  const outgoing = side.team[side.active];
  const incoming = side.team[switchSlot - 1];
  const sharedPhase = sharedSwitchPhaseOutcome(state, sideIndex, incoming, outgoing);
  const regenerator = sharedPhase.operations.find(
    (operation) => operation.code === "regenerator",
  );
  if (regenerator) {
    emitAbilityActivation(state, sideIndex, outgoing, "regenerator");
    healPokemon(
      state,
      sideIndex,
      outgoing,
      regenerator.amount,
      "regenerator",
    );
  }
  if (sharedPhase.operations.some((operation) => operation.code === "naturalcure")) {
    emitAbilityActivation(state, sideIndex, outgoing, "naturalcure");
    curePokemonStatus(state, sideIndex, outgoing, "naturalcure");
  }
  endPersistentAbilityWeather(state, sideIndex, outgoing, "switch");
  revertZenModeForm(state, sideIndex, outgoing);
  revertTransform(outgoing);
  if (outgoing.abilityState?.tracedAbility) {
    outgoing.ability =
      outgoing.abilityState.tracedOriginalAbility || outgoing.baseAbility || "trace";
    outgoing.abilityState = {};
  }
  endDynamax(state, sideIndex, outgoing, "switch");
  outgoing.boosts = Object.fromEntries(
    BOOST_STATS.map((stat) => [stat, 0]),
  );
  outgoing.consecutiveMove = { id: "", count: 0 };
  outgoing.protectCounter = 0;
  outgoing.lockedMove = null;
  outgoing.choiceLock = null;
  outgoing.chargingMove = null;
  delete outgoing.abilityState?.proteanUsed;
  delete outgoing.abilityState?.gulpMissileForm;
  delete outgoing.displayName;
  outgoing.volatiles = {};
  delete outgoing.abilityState?.unburdenActivated;
  if (outgoing.status === "tox") {
    outgoing.toxicCounter = 1;
  }
  side.active = switchSlot - 1;
  side.team[side.active].activeTurns = 0;
  syncNeutralizingGas(state);
  state.events.push({
    turn: state.turn,
    type: "switch",
    side: sideIndex,
    fromPokemon: outgoing.name,
    pokemon: side.team[side.active].name,
    slot: switchSlot,
    remainingHp: side.team[side.active].hp,
    maximumHp: side.team[side.active].stats.hp,
    status: side.team[side.active].status,
    automatic: Boolean(options.automatic),
    forced: Boolean(options.forced) || undefined,
    source: options.source,
    selection: options.selection ?? "manual_switch",
  });
  applyEntryHazards(state, sideIndex, side.team[side.active], sharedPhase);
  applyEntryAbilities(state, sideIndex, side.team[side.active], sharedPhase);
}

function executeSwitch(state, action) {
  switchActivePokemon(state, action.side, action.switchSlot);
}

function executeSelfSwitch(state, sideIndex, source, preferredSlot = null) {
  if (state.sides[sideIndex].team[state.sides[sideIndex].active].fainted) {
    return false;
  }
  const preferredIndex = Number(preferredSlot) - 1;
  const preferred = state.sides[sideIndex].team[preferredIndex];
  const fallback = bestFaintReplacement(state, sideIndex);
  const requested =
    Number.isInteger(preferredIndex) &&
    preferredIndex >= 0 &&
    preferredIndex !== state.sides[sideIndex].active &&
    preferred &&
    !preferred.fainted &&
    preferred.hp > 0
      ? preferredIndex
      : fallback;
  const next = JSON.parse(
    evaluateSharedForcedSwitchJson(
      JSON.stringify({
        activeSlot: state.sides[sideIndex].active,
        teamHp: state.sides[sideIndex].team.map((pokemon) => pokemon.hp),
        preferredSlot: requested,
      }),
    ),
  ).selectedSlot;
  if (!Number.isInteger(next) || next < 0) return false;
  switchActivePokemon(state, sideIndex, next + 1, {
    automatic: true,
    forced: true,
    source,
    selection: "self_switch",
  });
  return true;
}

function executeForceSwitch(state, sideIndex, source, rng) {
  const side = state.sides[sideIndex];
  if (side.team[side.active].fainted) {
    return false;
  }
  const selection = JSON.parse(
    evaluateSharedForcedSwitchJson(
      JSON.stringify({
        activeSlot: side.active,
        teamHp: side.team.map((pokemon) => pokemon.hp),
        randomSelection: true,
        rngState: rng?.snapshot?.() ?? 0,
      }),
    ),
  );
  rng?.restore?.(selection.rngState);
  const next = selection.selectedSlot;
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

function heldItemRemovalBlocked(state, sideIndex, pokemon, sourceSide, source) {
  if (!Number.isInteger(sourceSide) || sourceSide === sideIndex) return false;
  const sourcePokemon = activePokemon(state, sourceSide);
  if (
    activeAbility(pokemon) !== "stickyhold" ||
    ignoresDefenderAbility(sourcePokemon)
  ) {
    return false;
  }
  emitAbilityActivation(state, sideIndex, pokemon, "stickyhold", {
    source,
    targetSide: sourceSide,
    target: sourcePokemon?.name,
  });
  return true;
}

function activateUnburden(state, sideIndex, pokemon, item, source) {
  if (
    !item ||
    activeAbility(pokemon) !== "unburden" ||
    pokemon.abilityState?.unburdenActivated === true
  ) {
    return;
  }
  pokemon.abilityState ??= {};
  pokemon.abilityState.unburdenActivated = true;
  emitAbilityActivation(state, sideIndex, pokemon, "unburden", {
    item,
    source,
  });
}

function removeTargetItem(state, sideIndex, pokemon, source, sourceSide = null) {
  if (!pokemon.item) return "";
  if (heldItemRemovalBlocked(state, sideIndex, pokemon, sourceSide, source)) {
    return "";
  }
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
  activateUnburden(state, sideIndex, pokemon, removedItem, source);
  return removedItem;
}

function consumeHeldItem(state, sideIndex, pokemon, source) {
  if (!pokemon.item) return "";
  const consumedItem = pokemon.item;
  pokemon.item = "";
  pokemon.consumedItem = consumedItem;
  pokemon.usedItem = consumedItem;
  pokemon.lastItem = consumedItem;
  state.consumedItems ??= [];
  state.consumedItems.push({
    turn: state.turn,
    side: sideIndex,
    pokemon: pokemon.name,
    item: consumedItem,
  });
  state.events.push({
    turn: state.turn,
    type: "item_removed",
    side: sideIndex,
    pokemon: pokemon.name,
    item: consumedItem,
    source,
  });
  activateUnburden(state, sideIndex, pokemon, consumedItem, source);
  if (
    cleanId(consumedItem).endsWith("berry") &&
    activeAbility(pokemon) === "cheekpouch" &&
    !pokemon.fainted
  ) {
    emitAbilityActivation(state, sideIndex, pokemon, "cheekpouch", {
      item: consumedItem,
      source,
    });
    healPokemon(
      state,
      sideIndex,
      pokemon,
      Math.max(1, Math.floor(pokemon.stats.hp / 3)),
      "cheekpouch",
    );
  }
  return consumedItem;
}

function markBerryEaten(pokemon, item) {
  if (cleanId(item).endsWith("berry")) pokemon.ateBerry = true;
}

function tryConsumeLumBerry(state, sideIndex, pokemon, source) {
  const berry = cleanId(pokemon.item);
  const curesWithLum =
    berry === "lumberry" &&
    Boolean(pokemon.status || pokemon.volatiles?.confusion);
  const curesWithChesto = berry === "chestoberry" && pokemon.status === "slp";
  if (!curesWithLum && !curesWithChesto) {
    return false;
  }
  const curedStatus = pokemon.status;
  const curedConfusion = curesWithLum && Boolean(pokemon.volatiles?.confusion);
  consumeHeldItem(state, sideIndex, pokemon, source);
  markBerryEaten(pokemon, berry);
  if (curedStatus) curePokemonStatus(state, sideIndex, pokemon, berry);
  if (curedConfusion) {
    endVolatileStatus(state, sideIndex, pokemon, "confusion", berry);
  }
  return true;
}

function tryConsumeWhiteHerb(state, sideIndex, pokemon, source) {
  if (
    cleanId(pokemon.item) !== "whiteherb" ||
    !BOOST_STATS.some((stat) => Number(pokemon.boosts?.[stat] ?? 0) < 0)
  ) {
    return false;
  }
  consumeHeldItem(state, sideIndex, pokemon, source);
  for (const stat of BOOST_STATS) {
    const previous = Number(pokemon.boosts?.[stat] ?? 0);
    if (previous >= 0) continue;
    pokemon.boosts[stat] = 0;
    state.events.push({
      turn: state.turn,
      type: "stat_change",
      side: sideIndex,
      pokemon: pokemon.name,
      stat: eventStat(stat),
      amount: -previous,
      stage: 0,
      source: "whiteherb",
    });
  }
  return true;
}

function tryConsumePinchBerry(state, sideIndex, pokemon, source) {
  if (!pokemon.item || pokemon.fainted || pokemon.hp <= 0) return false;
  const berry = cleanId(pokemon.item);
  const healingBerries = {
    aguavberry: { fraction: [1, 3], pinch: true },
    figyberry: { fraction: [1, 3], pinch: true },
    iapapaberry: { fraction: [1, 3], pinch: true },
    magoberry: { fraction: [1, 3], pinch: true },
    oranberry: { amount: 10, pinch: false },
    sitrusberry: { fraction: [1, 4], pinch: false },
    wikiberry: { fraction: [1, 3], pinch: true },
    berryjuice: { amount: 20, pinch: false, berry: false },
  };
  const statBerries = {
    apicotberry: { specialDefence: 1 },
    ganlonberry: { defence: 1 },
    liechiberry: { attack: 1 },
    petayaberry: { specialAttack: 1 },
    salacberry: { speed: 1 },
  };
  if (!healingBerries[berry] && !statBerries[berry]) return false;
  const isPinchBerry = Boolean(healingBerries[berry]?.pinch || statBerries[berry]);
  const normalThreshold = Math.floor(pokemon.stats.hp / 4);
  const threshold = !isPinchBerry
    ? Math.floor(pokemon.stats.hp / 2)
    : activeAbility(pokemon) === "gluttony"
      ? Math.floor(pokemon.stats.hp / 2)
      : normalThreshold;
  if (pokemon.hp > threshold) return false;
  if (
    isPinchBerry &&
    activeAbility(pokemon) === "gluttony" &&
    pokemon.hp > normalThreshold
  ) {
    emitAbilityActivation(state, sideIndex, pokemon, "gluttony", {
      item: berry,
      source,
    });
  }
  consumeHeldItem(state, sideIndex, pokemon, berry);
  markBerryEaten(pokemon, berry);
  const ripenMultiplier =
    berry.endsWith("berry") && activeAbility(pokemon) === "ripen" ? 2 : 1;
  if (ripenMultiplier > 1) {
    emitAbilityActivation(state, sideIndex, pokemon, "ripen", {
      item: berry,
      source,
    });
  }
  if (healingBerries[berry]) {
    const berryEffect = healingBerries[berry];
    const healing = berryEffect.fraction
      ? fractionAmount(pokemon.stats.hp, berryEffect.fraction)
      : Number(berryEffect.amount ?? 0);
    return (
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Math.max(1, healing * ripenMultiplier),
        berry,
      ) > 0
    );
  }
  const boosts = Object.fromEntries(
    Object.entries(statBerries[berry]).map(([stat, amount]) => [
      stat,
      amount * ripenMultiplier,
    ]),
  );
  return applyBoosts(state, sideIndex, pokemon, boosts, berry);
}

function stealTargetItem(state, attackerSide, attacker, defenderSide, defender, source) {
  if (attacker.item || !itemCanBeStolen(defender.item)) return false;
  const stolenItem = removeTargetItem(
    state,
    defenderSide,
    defender,
    source,
    attackerSide,
  );
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
  if (
    heldItemRemovalBlocked(
      state,
      defenderSide,
      defender,
      attackerSide,
      source,
    )
  ) {
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
  if (attackerItem && !attacker.item) {
    activateUnburden(state, attackerSide, attacker, attackerItem, source);
  }
  if (defenderItem && !defender.item) {
    activateUnburden(state, defenderSide, defender, defenderItem, source);
  }
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
      priority: nativeMaxMovePriority(pokemon, move),
      power:
        isStatus
          ? 0
          : Math.max(90, Math.min(150, move.power * 1.35)),
      target: isStatus ? "self" : move.target,
      critRatio: 1,
      status: "",
      selfStatus: "",
      volatileStatus: maxMove.volatileStatus ?? "",
      bypassProtect: maxMove.bypassProtect === true,
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
  return effectiveWeather(state);
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
  applyChargeMoveBoost(state, action.side, attacker, move);
  state.events.push({
    turn: state.turn,
    type: "charge_start",
    side: action.side,
    pokemon: attacker.name,
    move: move.name,
  });
  return true;
}

function applyChargeMoveBoost(state, side, attacker, move) {
  const moveId = cleanId(move.id);
  if (moveId === "meteorbeam" || moveId === "electroshot") {
    applyBoosts(state, side, attacker, { specialAttack: 1 }, move.name);
  } else if (moveId === "skullbash") {
    applyBoosts(state, side, attacker, { defence: 1 }, move.name);
  }
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
  if (
    activeAbility(attacker) === "noguard" ||
    activeAbility(defender) === "noguard"
  ) {
    return 100;
  }
  const weather = effectiveWeather(state);
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
  const evasionStage =
    activeAbility(attacker) === "keeneye"
      ? Math.min(0, defender.boosts?.evasion ?? 0)
      : defender.boosts?.evasion ?? 0;
  let abilityModifier = 1;
  if (activeAbility(attacker) === "compoundeyes") abilityModifier *= 1.3;
  if (activeAbility(attacker) === "illuminate") abilityModifier *= 1.1;
  if (activeAbility(attacker) === "victorystar") abilityModifier *= 1.1;
  if (activeAbility(attacker) === "hustle" && move.category === "Physical") {
    abilityModifier *= 0.8;
  }
  if (cleanId(attacker.item) === "widelens") abilityModifier *= 1.1;
  if (cleanId(defender.item) === "brightpowder") abilityModifier *= 0.9;
  if (
    activeAbility(defender) === "sandveil" &&
    weather === "sandstorm" &&
    !ignoresDefenderAbility(attacker)
  ) {
    abilityModifier *= 0.8;
  }
  if (
    activeAbility(defender) === "snowcloak" &&
    ["hail", "snow"].includes(weather) &&
    !ignoresDefenderAbility(attacker)
  ) {
    abilityModifier *= 0.8;
  }
  if (
    activeAbility(defender) === "tangledfeet" &&
    defender.volatiles?.confusion &&
    !ignoresDefenderAbility(attacker)
  ) {
    abilityModifier *= 0.5;
  }
  return Math.max(
    1,
    Math.min(
      100,
      move.accuracy *
        abilityModifier *
        stageMultiplier(accuracyStage) /
        stageMultiplier(evasionStage),
    ),
  );
}

function expectedAccuracyFraction(attacker, defender, move, state = null) {
  return effectiveAccuracy(attacker, defender, move, state) / 100;
}

function weatherBallMove(state, move) {
  if (cleanId(move.id) !== "weatherball") return move;
  const weather = effectiveWeather(state);
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

function multitypePlateType(item) {
  const plateTypes = {
    dreadplate: "Dark",
    earthplate: "Ground",
    fistplate: "Fighting",
    flameplate: "Fire",
    icicleplate: "Ice",
    insectplate: "Bug",
    ironplate: "Steel",
    meadowplate: "Grass",
    mindplate: "Psychic",
    pixieplate: "Fairy",
    skyplate: "Flying",
    splashplate: "Water",
    spookyplate: "Ghost",
    stoneplate: "Rock",
    toxicplate: "Poison",
    zapplate: "Electric",
  };
  return plateTypes[cleanId(item)] ?? "";
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
  const weather = effectiveWeather(state);
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
  return sharedStatusApplication(
    pokemon,
    status,
    state,
    side,
    sourceSide,
  ).applied;
}

function sharedStatusApplication(
  pokemon,
  status,
  state = null,
  side = null,
  sourceSide = null,
  rngState = 0,
) {
  let flowerVeilProtected = false;
  let sweetVeilProtected = false;
  let leafGuardSun = false;
  let safeguardProtected = false;
  let terrain = "";
  if (state && Number.isInteger(side)) {
    terrain = cleanId(state.field?.terrain?.id);
    const sideAbilityPokemon = activePokemon(state, side);
    flowerVeilProtected = Boolean(
      pokemon.types.includes("Grass") &&
      activeAbility(sideAbilityPokemon) === "flowerveil" &&
      sourceSide !== side,
    );
    sweetVeilProtected = Boolean(
      status === "slp" &&
      activeAbility(sideAbilityPokemon) === "sweetveil",
    );
    leafGuardSun = Boolean(
      activeAbility(pokemon) === "leafguard" &&
      ["sunnyday", "desolateland"].includes(effectiveWeather(state)),
    );
    safeguardProtected = Boolean(
      hasSideCondition(state, side, "safeguard") &&
      sourceSide !== side,
    );
  }
  return JSON.parse(
    applySharedStatusJson(
      JSON.stringify({
        status,
        currentStatus: pokemon.status,
        fainted: pokemon.fainted,
        types: pokemon.types,
        ability: activeAbility(pokemon),
        terrain,
        grounded: isGrounded(pokemon),
        flowerVeilProtected,
        sweetVeilProtected,
        leafGuardSun,
        safeguardProtected,
        rngState,
      }),
    ),
  );
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
  const result = sharedStatusApplication(
    pokemon,
    status,
    state,
    side,
    sourceSide,
    rng?.snapshot?.() ?? 0,
  );
  rng?.restore?.(result.rngState);
  if (!result.applied) return false;
  pokemon.status = result.status;
  pokemon.statusTurns = result.statusTurns;
  pokemon.toxicCounter = result.toxicCounter;
  state.events.push({
    turn: state.turn,
    type: "status",
    side,
    pokemon: pokemon.name,
    status,
    source,
  });
  if (
    ["psn", "tox"].includes(status) &&
    Number.isInteger(sourceSide) &&
    sourceSide !== side
  ) {
    const sourcePokemon = activePokemon(state, sourceSide);
    if (
      sourcePokemon &&
      !sourcePokemon.fainted &&
      activeAbility(sourcePokemon) === "poisonpuppeteer" &&
      applyVolatileStatus(
        state,
        side,
        pokemon,
        "confusion",
        "poisonpuppeteer",
        sourceSide,
      )
    ) {
      emitAbilityActivation(state, sourceSide, sourcePokemon, "poisonpuppeteer", {
        targetSide: side,
        target: pokemon.name,
        status,
      });
    }
  }
  if (
    ["brn", "par", "psn", "tox"].includes(status) &&
    activeAbility(pokemon) === "synchronize" &&
    Number.isInteger(sourceSide) &&
    sourceSide !== side
  ) {
    const sourcePokemon = activePokemon(state, sourceSide);
    if (
      sourcePokemon &&
      !sourcePokemon.fainted &&
      !ignoresDefenderAbility(sourcePokemon) &&
      canReceiveStatus(sourcePokemon, status, state, sourceSide, side)
    ) {
      emitAbilityActivation(state, side, pokemon, "synchronize", {
        targetSide: sourceSide,
        target: sourcePokemon.name,
        status,
      });
      applyStatus(
        state,
        sourceSide,
        sourcePokemon,
        status,
        rng,
        "synchronize",
        side,
      );
    }
  }
  tryConsumeLumBerry(state, side, pokemon, source);
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
  tryConsumeLumBerry(state, side, pokemon, source);
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
  if (pokemon.hp > 0) {
    tryConsumePinchBerry(state, side, pokemon, source);
  }
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
  if (
    blockedMove.power <= 0 ||
    !triggersContactPunishment(attacker, blockedMove)
  ) {
    return false;
  }
  if (protectSource === "kingsshield") {
    return applyBoosts(
      state,
      attackerSide,
      attacker,
      { attack: -1 },
      defender.volatiles.protect.source,
      defenderSide,
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
      defenderSide,
    );
  }
  if (protectSource === "silktrap") {
    return applyBoosts(
      state,
      attackerSide,
      attacker,
      { speed: -1 },
      defender.volatiles.protect.source,
      defenderSide,
    );
  }
  return false;
}

function applyVolatileStatus(state, side, pokemon, id, source, sourceSide = null) {
  const aromaVeilPokemon = activePokemon(state, side);
  const sourcePokemon = Number.isInteger(sourceSide)
    ? activePokemon(state, sourceSide)
    : null;
  const normalized = cleanId(id);
  const result = JSON.parse(
    applySharedVolatileJson(
      JSON.stringify({
        id: normalized,
        fainted: pokemon.fainted,
        alreadyActive: Boolean(pokemon.volatiles[normalized]),
        ability: activeAbility(pokemon),
        sourceIsOpponent:
          Number.isInteger(sourceSide) && sourceSide !== side,
        aromaVeilProtected: activeAbility(aromaVeilPokemon) === "aromaveil",
        sourceItem: sourcePokemon?.item ?? "",
      }),
    ),
  );
  if (!result.applied) {
    if (result.emitBlockActivation) {
      const blocker = result.blockedBy === "aromaveil" ? aromaVeilPokemon : pokemon;
      emitAbilityActivation(state, side, blocker, result.blockedBy, {
        source,
        ...(result.blockedBy === "aromaveil" ? { target: pokemon.name } : {}),
      });
    }
    return false;
  }
  pokemon.volatiles[normalized] = Number.isFinite(result.turns)
    ? { id: normalized, turns: result.turns }
    : { id: normalized };
  pokemon.volatiles[normalized].source = source;
  pokemon.volatiles[normalized].sourceSide = sourceSide;
  if (Number.isFinite(result.perishCount)) {
    pokemon.volatiles[normalized].count = result.perishCount;
  }
  state.events.push({
    turn: state.turn,
    type: "volatile_start",
    side,
    pokemon: pokemon.name,
    effect: normalized,
    duration: result.eventDuration ?? null,
    source,
  });
  if (normalized === "confusion" && cleanId(source) !== "poisonpuppeteer") {
    tryConsumeLumBerry(state, side, pokemon, source);
  }
  return true;
}

function endVolatileStatus(state, side, pokemon, id, source) {
  const normalized = cleanId(id);
  if (!pokemon.volatiles?.[normalized]) return false;
  delete pokemon.volatiles[normalized];
  state.events.push({
    turn: state.turn,
    type: "volatile_end",
    side,
    pokemon: pokemon.name,
    effect: normalized,
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
  tryConsumePinchBerry(state, side, pokemon, source);
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
  tryConsumePinchBerry(state, side, pokemon, source);
  return true;
}

function applyCurse(state, actionSide, attacker, defenderSide, defender, source) {
  if (attacker.types.includes("Ghost")) {
    const cost = Math.floor(attacker.stats.hp / 2);
    const appliedCost = Math.min(attacker.hp, Math.max(1, cost));
    attacker.hp -= appliedCost;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: actionSide,
      pokemon: attacker.name,
      source,
      cause: "hp_cost",
      damage: appliedCost,
      remainingHp: attacker.hp,
      maximumHp: attacker.stats.hp,
      effectiveness: 1,
    });
    const cursed = applyVolatileStatus(
      state,
      defenderSide,
      defender,
      "curse",
      source,
    );
    markFainted(state, actionSide, attacker);
    return cursed || appliedCost > 0;
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
  syncNeutralizingGas(state);
  updateForecastForms(state);
  return true;
}

function applyTransform(state, side, pokemon, target, source) {
  if (pokemon.volatiles?.transform || target.fainted) return false;
  const previousName = pokemon.name;
  pokemon.volatiles.transform = {
    id: "transform",
    previousName,
    target: target.name,
    original: {
      id: pokemon.id,
      name: pokemon.name,
      types: [...pokemon.types],
      originalTypes: [...pokemon.originalTypes],
      ability: pokemon.ability,
      weightKg: pokemon.weightKg,
      stats: {
        attack: pokemon.stats.attack,
        defence: pokemon.stats.defence,
        specialAttack: pokemon.stats.specialAttack,
        specialDefence: pokemon.stats.specialDefence,
        speed: pokemon.stats.speed,
      },
      moves: clone(pokemon.moves),
    },
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
  syncNeutralizingGas(state);
  updateForecastForms(state);
  return true;
}

function revertTransform(pokemon) {
  const original = pokemon.volatiles?.transform?.original;
  if (!original) return false;
  pokemon.id = original.id;
  pokemon.name = original.name;
  pokemon.types = [...original.types];
  pokemon.originalTypes = [...original.originalTypes];
  pokemon.ability = original.ability;
  pokemon.weightKg = original.weightKg;
  Object.assign(pokemon.stats, original.stats);
  pokemon.moves = clone(original.moves);
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
  syncNeutralizingGas(state);
  updateForecastForms(state);
  return true;
}

function applyDisable(state, side, pokemon, source, sourceSide = null) {
  const lastMove = pokemon.lastMove;
  const disabledMoveId = cleanId(lastMove?.id);
  if (!disabledMoveId || pokemon.moves.every((move) => cleanId(move.id) !== disabledMoveId)) {
    return false;
  }
  if (!applyVolatileStatus(state, side, pokemon, "disable", source, sourceSide)) {
    return false;
  }
  pokemon.volatiles.disable.moveId = disabledMoveId;
  pokemon.volatiles.disable.move = lastMove.name;
  return true;
}

function applyEncore(state, side, pokemon, source, sourceSide = null) {
  const lastMove = pokemon.lastMove;
  const encoredMoveId = cleanId(lastMove?.id);
  if (
    !encoredMoveId ||
    pokemon.moves.every((move) => cleanId(move.id) !== encoredMoveId) ||
    ["encore", "mimic", "sketch", "struggle"].includes(encoredMoveId)
  ) {
    return false;
  }
  if (!applyVolatileStatus(state, side, pokemon, "encore", source, sourceSide)) {
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
  const lowered = applyBoosts(
    state,
    defenderSide,
    defender,
    { attack: -1 },
    source,
    attackerSide,
  );
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

function isPokemonTrapped(state, sideIndex, pokemon) {
  if (isTrappedByVolatile(pokemon)) return true;
  const opponent = activePokemon(state, sideIndex === 0 ? 1 : 0);
  const bypassesAbilityTrap =
    pokemon.types.includes("Ghost") || cleanId(pokemon.item) === "shedshell";
  if (opponent.fainted || bypassesAbilityTrap) return false;
  if (
    activeAbility(opponent) === "shadowtag" &&
    activeAbility(pokemon) !== "shadowtag"
  ) {
    return true;
  }
  if (activeAbility(opponent) === "arenatrap" && isGrounded(pokemon)) {
    return true;
  }
  return (
    activeAbility(opponent) === "magnetpull" &&
    pokemon.types.includes("Steel")
  );
}

function weatherRecoveryFraction(state) {
  const weather = effectiveWeather(state);
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
  const currentWeather = cleanId(state.field?.weather?.id);
  const result = JSON.parse(
    applySharedFieldJson(
      JSON.stringify({
        kind,
        id,
        currentWeather,
        sourceItem: pokemon.item,
      }),
    ),
  );
  const normalized = result.id;
  if (!result.applied) {
    if (!normalized) return false;
    state.events.push({
      turn: state.turn,
      type: "field_blocked",
      side,
      pokemon: pokemon.name,
      fieldKind: kind,
      effect: normalized,
      source: result.blockedBy,
    });
    return false;
  }
  const turns = result.turns ?? null;
  if (kind === "pseudoWeather") {
    state.field.pseudoWeather[normalized] = { id: normalized, turns };
  } else {
    state.field[kind] = {
      id: normalized,
      turns,
      sourceAbility: cleanId(source),
      sourceSide: side,
    };
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
  if (kind === "weather" || kind === "terrain") {
    if (kind === "weather") updateForecastForms(state);
    for (let activeSide = 0; activeSide < state.sides.length; activeSide += 1) {
      const active = activePokemon(state, activeSide);
      initializeParadoxAbility(
        state,
        activeSide,
        active,
        activeAbility(active),
      );
    }
  }
  return true;
}

function endPersistentAbilityWeather(state, side, pokemon, reason) {
  const weather = state.field?.weather;
  const weatherId = cleanId(weather?.id);
  if (
    !PERSISTENT_ABILITY_WEATHERS.has(weatherId) ||
    weather?.sourceSide !== side ||
    cleanId(weather?.sourceAbility) !== weatherId
  ) {
    return false;
  }
  state.field.weather = null;
  updateForecastForms(state);
  state.events.push({
    turn: state.turn,
    type: "field_end",
    side,
    pokemon: pokemon.name,
    fieldKind: "weather",
    effect: weatherId,
    source: reason,
  });
  return true;
}

function setSideCondition(state, side, pokemon, condition, source) {
  const requestedId = cleanId(condition);
  const existing = state.sides[side].conditions[requestedId];
  const result = JSON.parse(
    applySharedSideConditionJson(
      JSON.stringify({
        id: requestedId,
        previousLayers: Number(existing?.layers ?? 0),
        alreadyActive: Boolean(existing),
        sourceItem: pokemon.item,
      }),
    ),
  );
  if (!result.applied) return false;
  const id = result.id;
  if (Number.isFinite(result.layers)) {
    const layers = result.layers;
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
  const turns = result.turns;
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

function applyBoosts(state, side, pokemon, boosts, source, sourceSide = null) {
  let changed = false;
  let loweredByOpponent = false;
  for (const [stat, amount] of Object.entries(boosts ?? {})) {
    if (!BOOST_STATS.includes(stat) || !Number.isFinite(amount)) continue;
    const flowerVeilPokemon = activePokemon(state, side);
    const result = JSON.parse(
      applySharedBoostJson(
        JSON.stringify({
          stat,
          amount: Math.trunc(amount),
          currentStage: Number(pokemon.boosts[stat] ?? 0),
          ability: activeAbility(pokemon),
          item: pokemon.item,
          grassType: pokemon.types.includes("Grass"),
          flowerVeilProtected:
            activeAbility(flowerVeilPokemon) === "flowerveil",
          source,
          loweredByFoe:
            Number.isInteger(sourceSide) && sourceSide !== side,
        }),
      ),
    );
    if (result.action === "reflect") {
      const sourcePokemon = activePokemon(state, sourceSide);
      emitAbilityActivation(state, side, pokemon, "mirrorarmor", {
        source,
        targetSide: sourceSide,
        target: sourcePokemon?.name,
      });
      if (sourcePokemon && !sourcePokemon.fainted) {
        applyBoosts(
          state,
          sourceSide,
          sourcePokemon,
          { [stat]: result.appliedAmount },
          "mirrorarmor",
          side,
        );
      }
      continue;
    }
    if (result.action === "block") {
      if (result.blockedBy === "clearamulet") {
        state.events.push({
          turn: state.turn,
          type: "item_activate",
          side,
          pokemon: pokemon.name,
          item: "clearamulet",
          source,
        });
      } else {
        const blocker = result.blockedBy === "flowerveil" ? flowerVeilPokemon : pokemon;
        emitAbilityActivation(state, side, blocker, result.blockedBy, {
          source,
          ...(result.blockedBy === "flowerveil" ? { target: pokemon.name } : {}),
        });
      }
      continue;
    }
    if (result.action !== "apply") continue;
    const next = result.nextStage;
    const applied = result.appliedAmount;
    pokemon.boosts[stat] = next;
    if (applied < 0) {
      pokemon.turnState ??= {};
      pokemon.turnState.statsLowered = true;
      loweredByOpponent =
        loweredByOpponent ||
        result.loweredByFoe;
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
  if (changed) tryConsumeWhiteHerb(state, side, pokemon, source);
  if (
    loweredByOpponent &&
    activeAbility(pokemon) === "competitive" &&
    (pokemon.boosts.specialAttack ?? 0) < 6
  ) {
    state.events.push({
      turn: state.turn,
      type: "ability_activate",
      side,
      pokemon: pokemon.name,
      ability: "competitive",
      source,
    });
    applyBoosts(state, side, pokemon, { specialAttack: 2 }, "competitive");
  }
  if (
    loweredByOpponent &&
    activeAbility(pokemon) === "defiant" &&
    (pokemon.boosts.attack ?? 0) < 6
  ) {
    emitAbilityActivation(state, side, pokemon, "defiant", { source });
    applyBoosts(state, side, pokemon, { attack: 2 }, "defiant");
  }
  return changed;
}

function targetsPokemonWithStatusMove(move) {
  if (move?.category !== "Status") return false;
  return !["self", "allyside", "foeside", "all"].includes(cleanId(move.target));
}

function isPranksterBlocked(attacker, defender, move) {
  return (
    activeAbility(attacker) === "prankster" &&
    targetsPokemonWithStatusMove(move) &&
    defender.types.includes("Dark")
  );
}

function isAromaVeilBlockedMove(state, defenderSide, move) {
  if (!state || !Number.isInteger(defenderSide)) return false;
  const protector = activePokemon(state, defenderSide);
  const effect = cleanId(move?.volatileStatus || move?.id);
  return (
    activeAbility(protector) === "aromaveil" &&
    targetsPokemonWithStatusMove(move) &&
    AROMA_VEIL_VOLATILES.has(effect)
  );
}

function canMagicBounceMove(move) {
  if (move?.category !== "Status" || cleanId(move.target) === "self") return false;
  if (cleanId(move.id) === "curse") return false;
  return !move.weather && !move.terrain && !move.pseudoWeather;
}

function reflectStatusMove(
  state,
  attackerSide,
  attacker,
  defenderSide,
  defender,
  move,
  rng,
) {
  emitAbilityActivation(state, defenderSide, defender, "magicbounce", {
    targetSide: attackerSide,
    target: attacker.name,
    move: move.name,
  });
  state.events.push({
    turn: state.turn,
    type: "move_reflected",
    side: defenderSide,
    pokemon: defender.name,
    targetSide: attackerSide,
    target: attacker.name,
    move: move.name,
    source: "magicbounce",
  });
  if (move.sideCondition) {
    setSideCondition(state, attackerSide, defender, move.sideCondition, move.name);
  }
  if (move.status) {
    applyStatus(
      state,
      attackerSide,
      attacker,
      move.status,
      rng,
      move.name,
      defenderSide,
    );
  }
  if (Object.keys(move.boosts ?? {}).length) {
    applyBoosts(
      state,
      attackerSide,
      attacker,
      move.boosts,
      move.name,
      defenderSide,
    );
  }
  if (move.volatileStatus) {
    applyVolatileStatus(
      state,
      attackerSide,
      attacker,
      move.volatileStatus,
      move.name,
    );
  }
  if (move.forceSwitch) {
    executeForceSwitch(state, attackerSide, move.name, rng);
  }
  return true;
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
  const result = JSON.parse(
    applySharedDirectDamageJson(
      JSON.stringify({
        turn: state.turn,
        side,
        pokemon: pokemon.name,
        amount: Math.floor(amount),
        hp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        source,
        cause,
        magicGuard: activeAbility(pokemon) === "magicguard",
      }),
    ),
  );
  state.events.push(...result.events);
  const damage = result.damage;
  if (damage <= 0) return 0;
  pokemon.hp = result.remainingHp;
  pokemon.turnState.damageTaken += damage;
  pokemon.turnState.lastDamage = {
    amount: damage,
    category: "Special",
    move: source,
    source,
    sourceSide: null,
  };
  if (pokemon.hp > 0) {
    tryConsumePinchBerry(state, side, pokemon, source);
  }
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

function sharedPostHitOutcome(
  move,
  attacker,
  defender,
  totalDamage = 0,
  landedHits = 0,
) {
  return JSON.parse(
    evaluateSharedPostHitJson(
      JSON.stringify({
        moveId: move.id,
        moveName: move.name,
        moveType: move.type,
        moveCategory: move.category,
        moveContact: makesContact(move),
        movePunch: hasMoveFlag(move, "punch"),
        moveHasSecondaries: (move.secondaries ?? []).length > 0,
        moveVolatileStatus: move.volatileStatus ?? "",
        bindingVolatile: BINDING_VOLATILES.has(cleanId(move.volatileStatus)),
        drainNumerator: Number(move.drain?.[0] ?? 0),
        drainDenominator: Number(move.drain?.[1] ?? 1),
        recoilNumerator: Number(move.recoil?.[0] ?? 0),
        recoilDenominator: Number(move.recoil?.[1] ?? 1),
        landedHits,
        totalDamage,
        attackerHp: attacker.hp,
        attackerMaximumHp: attacker.stats.hp,
        attackerItem: attacker.item,
        attackerAbility: activeAbility(attacker),
        attackerFainted: attacker.fainted,
        defenderHp: defender.hp,
        defenderItem: defender.item,
        defenderAbility: activeAbility(defender),
        defenderStatus: defender.status,
        ignoresDefenderAbility: ignoresDefenderAbility(attacker),
        defenderItemRemovalBlocked:
          activeAbility(defender) === "stickyhold" &&
          !ignoresDefenderAbility(attacker),
      }),
    ),
  );
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
  if (effect.volatileStatus) {
    applied =
      applyVolatileStatus(
        state,
        defenderSide,
        defender,
        effect.volatileStatus,
        source,
        attackerSide,
      ) || applied;
  }
  if (Object.keys(effect.boosts ?? {}).length) {
    applied =
      applyBoosts(
        state,
        defenderSide,
        defender,
        effect.boosts,
        source,
        attackerSide,
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
    if (activeAbility(pokemon) === "steadfast") {
      emitAbilityActivation(state, side, pokemon, "steadfast");
      applyBoosts(state, side, pokemon, { speed: 1 }, "steadfast");
    }
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
      const amount = Math.min(
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
      const result = JSON.parse(
        applySharedDirectDamageJson(
          JSON.stringify({
            turn: state.turn,
            side,
            pokemon: pokemon.name,
            amount,
            hp: pokemon.hp,
            maximumHp: pokemon.stats.hp,
            source: "confusion",
            cause: "volatile",
            magicGuard: activeAbility(pokemon) === "magicguard",
          }),
        ),
      );
      state.events.push(...result.events);
      pokemon.hp = result.remainingHp;
      markFainted(state, side, pokemon);
      return false;
    }
  }
  if (pokemon.status === "slp" && !options.allowSleepAction) {
    if (pokemon.statusTurns > 0) {
      pokemon.statusTurns = Math.max(
        0,
        pokemon.statusTurns - (activeAbility(pokemon) === "earlybird" ? 2 : 1),
      );
      if (
        pokemon.statusTurns === 0 &&
        activeAbility(pokemon) === "earlybird"
      ) {
        pokemon.status = "";
        emitAbilityActivation(state, side, pokemon, "earlybird");
        state.events.push({
          turn: state.turn,
          type: "status_cured",
          side,
          pokemon: pokemon.name,
          status: "slp",
          source: "earlybird",
        });
      } else {
        state.events.push({
          turn: state.turn,
          type: "cant_move",
          side,
          pokemon: pokemon.name,
          status: "slp",
        });
        return false;
      }
    }
    if (pokemon.status === "slp") {
      pokemon.status = "";
      state.events.push({
        turn: state.turn,
        type: "status_cured",
        side,
        pokemon: pokemon.name,
        status: "slp",
      });
    }
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
  endPersistentAbilityWeather(state, side, pokemon, "faint");
  endDynamax(state, side, pokemon, "faint");
  pokemon.fainted = true;
  state.sides[side].lastFaintedTurn = state.turn;
  pokemon.consecutiveMove = { id: "", count: 0 };
  pokemon.protectCounter = 0;
  pokemon.lockedMove = null;
  pokemon.choiceLock = null;
  pokemon.chargingMove = null;
  delete pokemon.abilityState?.gulpMissileForm;
  pokemon.volatiles = {};
  syncNeutralizingGas(state);
  updateForecastForms(state);
  state.events.push({
    turn: state.turn,
    type: "faint",
    side,
    pokemon: pokemon.name,
  });
  for (const [otherSide, battleSide] of state.sides.entries()) {
    const witness = battleSide.team[battleSide.active];
    if (
      otherSide === side ||
      !witness ||
      witness === pokemon ||
      witness.fainted ||
      activeAbility(witness) !== "soulheart"
    ) {
      continue;
    }
    emitAbilityActivation(state, otherSide, witness, "soulheart", {
      faintedPokemon: pokemon.name,
    });
    applyBoosts(
      state,
      otherSide,
      witness,
      { specialAttack: 1 },
      "soulheart",
    );
  }
  return true;
}

function applyCrashDamage(state, side, pokemon, source) {
  if (pokemon.fainted || pokemon.hp <= 0) return false;
  if (activeAbility(pokemon) === "magicguard") {
    emitAbilityActivation(state, side, pokemon, "magicguard", {
      source,
      cause: "crash",
    });
    return false;
  }
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

function fixedDamageAmount(move, attacker, defender, rng = null) {
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
  if (moveId === "psywave") {
    const minimum = Math.max(1, Math.floor(attacker.level * 0.5));
    const maximum = Math.max(minimum, Math.floor(attacker.level * 1.5));
    return rng?.next
      ? minimum + Math.floor(rng.next() * (maximum - minimum + 1))
      : attacker.level;
  }
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

function recordMoveResult(
  state,
  side,
  pokemon,
  move,
  slot,
  succeeded,
  rng = null,
  resolvedMove = null,
) {
  const moveId = cleanId(move?.id);
  const protectionMoveId = cleanId(resolvedMove?.id ?? move?.id);
  const rollingMove = ROLLING_LOCK_MOVES.has(moveId);
  const rampageMove = RAMPAGE_LOCK_MOVES.has(moveId);
  const repeatedLockMove = rollingMove || rampageMove;
  pokemon.lastMoveSucceeded = Boolean(succeeded);
  pokemon.protectCounter =
    succeeded && CONSECUTIVE_PROTECTION_MOVES.has(protectionMoveId)
      ? Math.max(0, Number(pokemon.protectCounter ?? 0)) + 1
      : 0;
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
  if (!hasChoiceLockEffect(pokemon)) {
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
      ability:
        activeAbility(pokemon) === "gorillatactics" ? "gorillatactics" : "",
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

function consecutiveProtectionSucceeded(pokemon, moveId, rng) {
  if (!CONSECUTIVE_PROTECTION_MOVES.has(moveId)) return true;
  const previousSuccesses = Math.max(0, Number(pokemon?.protectCounter ?? 0));
  if (previousSuccesses <= 0) return true;
  return (rng?.next?.() ?? 0) < 1 / 3 ** previousSuccesses;
}

function isTruantLoafTurn(pokemon) {
  return (
    activeAbility(pokemon) === "truant" &&
    Math.max(0, Number(pokemon?.activeTurns ?? 0)) % 2 === 1
  );
}

function heldItemCriticalRatioBoost(pokemon) {
  if (cleanId(pokemon.item) === "scopelens") return 1;
  return ["farfetchd", "sirfetchd"].includes(
    cleanId(pokemon.baseSpecies || pokemon.id || pokemon.name),
  ) && cleanId(pokemon.item) === "medicinalleek"
    ? 2
    : 0;
}

function criticalHitChance(pokemon, move) {
  if (move?.willCrit || pokemon?.volatiles?.laserfocus) return 1;
  const ratio =
    Math.max(1, Number(move?.critRatio ?? 1)) +
    (pokemon?.volatiles?.focusenergy ? 2 : 0) +
    (activeAbility(pokemon) === "superluck" ? 1 : 0) +
    heldItemCriticalRatioBoost(pokemon);
  return ratio >= 3 ? 1 : ratio === 2 ? 1 / 8 : 1 / 24;
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
  if (isTruantLoafTurn(attacker)) {
    emitAbilityActivation(state, action.side, attacker, "truant", {
      move: sourceMove.name,
    });
    state.events.push({
      turn: state.turn,
      type: "cant_move",
      side: action.side,
      pokemon: attacker.name,
      move: sourceMove.name,
      ability: "truant",
    });
    return false;
  }
  if (
    !canAct(state, action.side, attacker, rng, {
      allowSleepAction: sourceMoveId === "sleeptalk" || sourceMoveId === "snore",
    })
  ) {
    return false;
  }
  if (
    attacker.dynamaxTurns <= 0 &&
    isMoveTemporarilyDisabled(attacker, sourceMove)
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: sourceMove.name,
      moveId: sourceMove.id,
      reason: `${sourceMove.name}은(는) 연속해서 사용할 수 없습니다.`,
    });
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
  const repeatedDestinyBond =
    sourceMoveId === "destinybond" &&
    Boolean(attacker.volatiles?.destinybond);
  endVolatileStatus(
    state,
    action.side,
    attacker,
    "destinybond",
    sourceMove.name,
  );
  endVolatileStatus(
    state,
    action.side,
    attacker,
    "grudge",
    sourceMove.name,
  );
  if (repeatedDestinyBond) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: sourceMove.name,
      reason: "Destiny Bond cannot succeed on consecutive uses.",
    });
    return false;
  }
  if (sourceMoveId === "snore" && !isEffectivelyAsleep(attacker)) {
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
    if (!isEffectivelyAsleep(attacker)) {
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
  action.resolvedMove = move;
  move = abilityModifiedMove(attacker, move);
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
  move = teraModifiedMove(attacker, move);
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
  move = weatherBallMove(state, move);
  move = terrainPulseMove(state, move);
  move = growthMove(state, move);
  move = terrainModifiedMove(state, attacker, move);
  move = chargeAdjustedMove(state, move);
  applyProtean(state, action.side, attacker, move);
  applyStanceChange(state, action.side, attacker, move);
  state.events.push({
    turn: state.turn,
    type: "move",
    side: action.side,
    pokemon: attacker.name,
    move: move.name,
    moveId: move.id,
    moveType: move.type,
    moveCategory: move.category,
    slot,
  });
  state.turnMoves ??= [];
  state.turnMoves.push({ side: action.side, id: cleanId(move.id), move: move.name });

  if (isPrimordialSeaBlockedMove(state, move)) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Primordial Sea extinguishes damaging Fire-type moves.",
      source: "primordialsea",
    });
    return false;
  }

  if (isDesolateLandBlockedMove(state, move)) {
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Desolate Land evaporates damaging Water-type moves.",
      source: "desolateland",
    });
    return false;
  }

  if (isDampBlockedMove(state, move)) {
    const dampSide = activeAbility(activePokemon(state, 0)) === "damp" ? 0 : 1;
    const dampPokemon = activePokemon(state, dampSide);
    emitAbilityActivation(state, dampSide, dampPokemon, "damp", {
      targetSide: action.side,
      target: attacker.name,
      move: move.name,
    });
    state.events.push({
      turn: state.turn,
      type: "move_failed",
      side: action.side,
      pokemon: attacker.name,
      move: move.name,
      reason: "Damp prevents explosive moves.",
      source: "damp",
    });
    return false;
  }

  if (shouldChargeMove(state, move, action)) {
    if (cleanId(attacker.item) === "powerherb") {
      consumeHeldItem(state, action.side, attacker, move.name);
      applyChargeMoveBoost(state, action.side, attacker, move);
      state.events.push({
        turn: state.turn,
        type: "charge_skipped",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        source: "powerherb",
      });
    } else {
      return beginChargeMove(state, action, attacker, move, slot);
    }
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
    const targetMove = defenderAction?.selected?.move;
    const targetPriority = Number(
      defenderAction?.priority ?? targetMove?.priority ?? 0,
    );
    if (
      defender.turnState.acted ||
      defenderAction?.kind !== "move" ||
      !targetMove ||
      targetMove.category === "Status" ||
      Number(targetMove.power ?? 0) <= 0 ||
      targetPriority <= 0
    ) {
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
    move.category !== "Status" &&
    cleanId(move.id) !== "hyperspacefury" &&
    move.bypassProtect !== true &&
    !(
      activeAbility(attacker) === "unseenfist" &&
      makesEffectiveContact(attacker, move)
    )
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
    Number(action.priority ?? move.priority ?? 0) > 0 &&
    !["self", "allyside"].includes(cleanId(move.target)) &&
    ((cleanId(state.field?.terrain?.id) === "psychicterrain" &&
      isGrounded(defender)) ||
      hasSideCondition(state, defenderSide, "quickguard") ||
      (priorityBlockingAbility(defender) &&
        !ignoresDefenderAbility(attacker)))
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source:
        priorityBlockingAbility(defender) &&
        !ignoresDefenderAbility(attacker)
          ? priorityBlockingAbility(defender)
          : cleanId(state.field?.terrain?.id) === "psychicterrain"
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

  if (cleanId(move.id) === "dreameater" && !isEffectivelyAsleep(defender)) {
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

  if (isPranksterBlocked(attacker, defender, move)) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "prankster-dark-immunity",
    });
    return false;
  }

  if (
    targetsPokemonWithStatusMove(move) &&
    activeAbility(defender) === "goodasgold" &&
    !ignoresDefenderAbility(attacker)
  ) {
    emitAbilityActivation(state, defenderSide, defender, "goodasgold", {
      targetSide: action.side,
      target: attacker.name,
      move: move.name,
    });
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "goodasgold",
    });
    return false;
  }

  if (
    move.powder &&
    move.target !== "self" &&
    defender.types.includes("Grass")
  ) {
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "grass-type-powder-immunity",
    });
    return false;
  }

  if (
    move.powder &&
    move.target !== "self" &&
    activeAbility(defender) === "overcoat" &&
    !ignoresDefenderAbility(attacker)
  ) {
    emitAbilityActivation(state, defenderSide, defender, "overcoat", {
      targetSide: action.side,
      target: attacker.name,
      move: move.name,
    });
    state.events.push({
      turn: state.turn,
      type: "move_blocked",
      side: defenderSide,
      pokemon: defender.name,
      move: move.name,
      source: "overcoat",
    });
    return false;
  }

  if (
    defender.volatiles?.substitute &&
    activeAbility(attacker) !== "infiltrator" &&
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
    move.target !== "self" &&
    activateAbsorbingAbility(
      state,
      defenderSide,
      defender,
      action.side,
      attacker,
      move,
    )
  ) {
    return true;
  }

  if (
    canMagicBounceMove(move) &&
    activeAbility(defender) === "magicbounce" &&
    !ignoresDefenderAbility(attacker)
  ) {
    return reflectStatusMove(
      state,
      action.side,
      attacker,
      defenderSide,
      defender,
      move,
      rng,
    );
  }

  if (
    (move.category === "Status" ||
      (move.power <= 0 &&
        !move.dynamicPower &&
        !SUPPORTED_DYNAMIC_POWER_MOVES.has(cleanId(move.id)))) &&
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
      if (consecutiveProtectionSucceeded(attacker, cleanId(move.id), rng)) {
        applied =
          applyVolatileStatus(
            state,
            action.side,
            attacker,
            "protect",
            move.name,
          ) || applied;
      }
    }
    if (cleanId(move.id) === "endure") {
      handled = true;
      if (consecutiveProtectionSucceeded(attacker, cleanId(move.id), rng)) {
        applied =
          applyVolatileStatus(
            state,
            action.side,
            attacker,
            "endure",
            move.name,
          ) || applied;
      }
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
        consumeHeldItem(state, action.side, attacker, move.name);
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
          consumeHeldItem(state, teaSide, teaPokemon, move.name);
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
      applied =
        applyDisable(state, defenderSide, defender, move.name, action.side) ||
        applied;
    }
    if (cleanId(move.id) === "encore") {
      handled = true;
      applied =
        applyEncore(state, defenderSide, defender, move.name, action.side) ||
        applied;
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
        applyBoosts(
          state,
          defenderSide,
          defender,
          { evasion: -1 },
          move.name,
          action.side,
        ) ||
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
        applyVolatileStatus(
          state,
          defenderSide,
          defender,
          "healblock",
          move.name,
          action.side,
        ) ||
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
      if (isEffectivelyAsleep(defender)) {
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
      applied =
        removeTargetItem(
          state,
          defenderSide,
          defender,
          move.name,
          action.side,
        ) || applied;
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
        effectiveWeather(state) === "sandstorm"
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
      const passedVolatiles = Object.fromEntries(
        Object.entries(attacker.volatiles ?? {})
          .filter(([id]) => BATON_PASS_VOLATILES.has(cleanId(id)))
          .map(([id, volatile]) => [id, clone(volatile)]),
      );
      const teamAnalysis = simpleTeamAnalysis(state, action.side);
      const aceIndex = teamAnalysis.roles.findIndex(
        (role) => role?.aceProfile?.qualifies === true,
      );
      const ace = state.sides[action.side].team[aceIndex];
      const preferredSlot =
        Number.isInteger(action.selfSwitchSlot) && action.selfSwitchSlot > 0
          ? action.selfSwitchSlot
          : aceIndex >= 0 &&
              ace !== attacker &&
              !ace?.fainted &&
              ace?.hp > 0
            ? aceIndex + 1
            : null;
      const switched = executeSelfSwitch(
        state,
        action.side,
        move.name,
        preferredSlot,
      );
      if (switched) {
        const incoming = activePokemon(state, action.side);
        incoming.boosts = passedBoosts;
        Object.assign(incoming.volatiles, passedVolatiles);
        state.events.push({
          turn: state.turn,
          type: "boosts_passed",
          side: action.side,
          pokemon: activePokemon(state, action.side).name,
          source: move.name,
          boosts: Object.fromEntries(
            Object.entries(passedBoosts)
              .filter(([, stage]) => Number(stage ?? 0) !== 0)
              .map(([stat, stage]) => [eventStat(stat), Number(stage)]),
          ),
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
          action.side,
        ) || applied;
    }
    if (cleanId(move.id) !== "curse" && Object.keys(move.selfBoosts).length) {
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
    if (
      cleanId(move.id) !== "curse" &&
      !CONSECUTIVE_PROTECTION_MOVES.has(cleanId(move.id)) &&
      move.volatileStatus
    ) {
      handled = true;
      const targetsSelf = move.target === "self";
      applied =
        applyVolatileStatus(
          state,
          targetsSelf ? action.side : defenderSide,
          targetsSelf ? attacker : defender,
          move.volatileStatus,
          move.name,
          action.side,
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
        !["hail", "snow"].includes(effectiveWeather(state))
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
      applied =
        executeForceSwitch(state, defenderSide, move.name, rng) || applied;
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
    if (
      applied &&
      move.selfSwitch &&
      !["batonpass", "shedtail"].includes(cleanId(move.id))
    ) {
      executeSelfSwitch(
        state,
        action.side,
        move.name,
        action.selfSwitchSlot,
      );
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
  if (isMoveBlockedByDynamaxTarget(move, defender)) {
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

  const teraShellActive =
    move.category !== "Status" &&
    cleanId(move.id) !== "struggle" &&
    activeAbility(defender) === "terashell" &&
    cleanId(defender.id ?? defender.name) === "terapagosterastal" &&
    defender.hp >= defender.stats.hp &&
    !ignoresDefenderAbility(attacker) &&
    moveEffectiveness(move, defender.types, attacker, {
      ...defender,
      ability: "",
    }) >= 1;
  if (teraShellActive) {
    defender.abilityState ??= {};
    defender.abilityState.teraShellActive = true;
    emitAbilityActivation(state, defenderSide, defender, "terashell", {
      targetSide: action.side,
      target: attacker.name,
      move: move.name,
    });
  }
  const requestedHits = hitCountForMove(move, attacker, rng);
  const activatedGemItem =
    move.category !== "Status" &&
    cleanId(attacker.item) === `${cleanId(move.type)}gem`
      ? attacker.item
      : "";
  let gemConsumed = false;
  let landedHits = 0;
  let totalDamage = 0;
  for (
    let hit = 1;
    hit <= requestedHits && defender.hp > 0 && attacker.hp > 0;
    hit += 1
  ) {
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
      attacker: effectiveWeightPokemon(attacker),
      defender: effectiveWeightPokemon(defender),
      attackerSpeed: effectiveSpeed(attacker, state, action.side),
      defenderSpeed: effectiveSpeed(defender, state, defenderSide),
      effectiveness: moveEffectiveness(move, defender.types, attacker, defender),
      hit,
      rng,
    });
    const hitMove = teraPowerAdjustedMove(
      attacker,
      dynamicPower.supported
        ? { ...move, power: dynamicPower.power }
        : move,
    );
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
    const fixedDamage = fixedDamageAmount(chargedHitMove, attacker, defender, rng);
    const criticalChance = criticalHitChance(attacker, move);
    const critical =
      fixedDamage === null &&
      !preventsCriticalHit(defender, attacker) &&
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
    const range = calculateDamageRange(
      activatedGemItem ? { ...attacker, item: activatedGemItem } : attacker,
      defender,
      chargedHitMove,
      {
      state,
      attackerSide: action.side,
      defenderSide,
      critical,
      },
    );
    const fixedEffectiveness =
      fixedDamage === null
        ? null
        : moveEffectiveness(chargedHitMove, defender.types, attacker, defender);
    const randomFactor = fixedDamage === null ? 0.85 + rng.next() * 0.15 : 1;
    const criticalModifier = criticalDamageModifier(
      attacker,
      defender,
      critical,
    );
    let damage =
      fixedDamage !== null
        ? fixedEffectiveness === 0
          ? 0
          : Math.min(defender.hp, fixedDamage)
        : calculateSharedModifiedDamage(
            range.baseDamage,
            range.stab,
            range.effectiveness,
            range.itemModifier,
            range.abilityModifier,
            range.fieldModifier,
            criticalModifier,
            randomFactor,
            defender.hp,
          );
    const hitEffectiveness =
      fixedDamage === null ? range.effectiveness : fixedEffectiveness;
    const substitute = defender.volatiles?.substitute;
    const application = JSON.parse(
      applySharedDamageJson(
        JSON.stringify({
          turn: state.turn,
          attackerSide: action.side,
          defenderSide,
          attackerName: attacker.name,
          defenderName: defender.name,
          moveId: move.id,
          moveName: move.name,
          moveType: move.type,
          moveTarget: move.target,
          damage,
          defenderHp: defender.hp,
          defenderMaximumHp: defender.stats.hp,
          substituteHp: substitute?.hp ?? null,
          endure: Boolean(defender.volatiles?.endure),
          sturdy: activeAbility(defender) === "sturdy",
          focusSash: cleanId(defender.item) === "focussash",
          focusBand: cleanId(defender.item) === "focusband",
          disguise:
            activeAbility(defender) === "disguise" &&
            defender.abilityState?.disguiseBusted !== true,
          attackerInfiltrator: activeAbility(attacker) === "infiltrator",
          ignoresDefenderAbility: ignoresDefenderAbility(attacker),
          stab: range.stab,
          effectiveness: hitEffectiveness,
          randomFactor,
          critical,
          hit,
          hits: requestedHits,
          rngState: rng.snapshot(),
        }),
      ),
    );
    rng.restore(application.rngState);
    if (application.consumeFocusSash) {
      consumeHeldItem(state, defenderSide, defender, "Focus Sash");
    }
    const substituteBlockedHit = application.substituteBlocked;
    const disguiseBlockedHit = application.disguiseBlocked;
    if (disguiseBlockedHit) {
      defender.abilityState ??= {};
      defender.abilityState.disguiseBusted = true;
    }
    damage = application.damage;
    const appliedDamage = application.appliedDamage;
    if (substituteBlockedHit) {
      substitute.hp = application.substituteHp;
      if (application.substituteEnded) {
        delete defender.volatiles.substitute;
      }
    } else {
      defender.hp = application.remainingHp;
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
    state.events.push(...application.events);
    if (application.immune) break;
    landedHits += 1;
    totalDamage += appliedDamage;
    if (!substituteBlockedHit) {
      const sharedHitReactionResult = JSON.parse(
        evaluateSharedHitReactionsJson(
          JSON.stringify({
            damage,
            attackerAlive: !attacker.fainted && attacker.hp > 0,
            defenderAlive: !defender.fainted && defender.hp > 0,
            attackerAbility: activeAbility(attacker),
            defenderAbility: activeAbility(defender),
            defenderItem: defender.item,
            moveId: move.id,
            moveType: move.type,
            moveCategory: move.category,
            superEffective: range.effectiveness > 1,
            contactPunishment: triggersContactPunishment(attacker, move),
            effectiveContact: makesEffectiveContact(attacker, move),
            ignoresDefenderAbility: ignoresDefenderAbility(attacker),
            attackerCanBurn: canReceiveStatus(attacker, "brn", state, action.side, defenderSide),
            attackerCanPoison: canReceiveStatus(attacker, "psn", state, action.side, defenderSide),
            attackerCanParalyze: canReceiveStatus(attacker, "par", state, action.side, defenderSide),
            attackerCanSleep: canReceiveStatus(attacker, "slp", state, action.side, defenderSide),
            defenderCanPoison: canReceiveStatus(defender, "psn", state, defenderSide, action.side),
            attackerGrassType: attacker.types.includes("Grass"),
            attackerOvercoat: activeAbility(attacker) === "overcoat",
            oppositeGender: Boolean(defender.gender && attacker.gender && defender.gender !== attacker.gender),
            attackerAlreadyAttracted: Boolean(attacker.volatiles?.attract),
            attackerAlreadyDisabled: Boolean(attacker.volatiles?.disable),
            moveIsMax: Boolean(move.isMaxMove),
            moveIsFuture: hasMoveFlag(move, "futuremove"),
            rngState: rng.snapshot(),
            attackerItem: attacker.item,
            defenderHasIllusion: Boolean(defender.volatiles?.illusion),
            defenderGulpMissileForm: defender.abilityState?.gulpMissileForm ?? "",
            attackerItemRemovalBlocked:
              activeAbility(attacker) === "stickyhold" &&
              !ignoresDefenderAbility(defender),
            defenderItemRemovalBlocked:
              activeAbility(defender) === "stickyhold" &&
              !ignoresDefenderAbility(attacker),
            attackerItemConsumedOnHit: Boolean(activatedGemItem && !gemConsumed),
            defenderItemConsumedOnHit:
              ["chartiberry", "colburberry", "yacheberry"].includes(cleanId(defender.item)) &&
              heldItemType(defender.item) === move.type &&
              range.effectiveness > 1,
          }),
        ),
      );
      rng.restore(sharedHitReactionResult.rngState);
      const sharedHitReaction = (code) =>
        sharedHitReactionResult.reactions.find((reaction) => reaction.code === code);
      if (damage > 0 && activatedGemItem && !gemConsumed) {
        consumeHeldItem(state, action.side, attacker, move.name);
        gemConsumed = true;
      }
      if (
        damage > 0 &&
        ["chartiberry", "colburberry", "yacheberry"].includes(
          cleanId(defender.item),
        ) &&
        heldItemType(defender.item) === move.type &&
        range.effectiveness > 1
      ) {
        const berryId = cleanId(defender.item);
        consumeHeldItem(state, defenderSide, defender, move.name);
        markBerryEaten(defender, berryId);
      }
      if (sharedHitReaction("weaknesspolicy")) {
        consumeHeldItem(state, defenderSide, defender, move.name);
        applyBoosts(
          state,
          defenderSide,
          defender,
          { attack: 2, specialAttack: 2 },
          "weaknesspolicy",
        );
      }
      if (sharedHitReaction("marangaberry")) {
        consumeHeldItem(state, defenderSide, defender, move.name);
        markBerryEaten(defender, "marangaberry");
        const ripenMultiplier = activeAbility(defender) === "ripen" ? 2 : 1;
        if (ripenMultiplier > 1) {
          emitAbilityActivation(state, defenderSide, defender, "ripen", {
            item: "marangaberry",
            source: move.name,
          });
        }
        applyBoosts(
          state,
          defenderSide,
          defender,
          { specialDefence: ripenMultiplier },
          "marangaberry",
        );
      }
      if (sharedHitReaction("illusion")) {
        const displayedPokemon = defender.volatiles.illusion.displayedName;
        delete defender.volatiles.illusion;
        delete defender.displayName;
        state.events.push({
          turn: state.turn,
          type: "illusion_end",
          side: defenderSide,
          pokemon: defender.name,
          displayedPokemon,
          source: move.name,
        });
      }
      if (sharedHitReaction("pickpocket")) {
        stealTargetItem(
          state,
          defenderSide,
          defender,
          action.side,
          attacker,
          "pickpocket",
        );
      }
      if (sharedHitReaction("rockyhelmet")) {
        applyDirectDamage(
          state,
          action.side,
          attacker,
          Math.max(1, Math.floor(attacker.stats.hp / 6)),
          "rockyhelmet",
          "item",
        );
      }
      if (sharedHitReaction("gooey")) {
        emitAbilityActivation(state, defenderSide, defender, "gooey", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyBoosts(
          state,
          action.side,
          attacker,
          { speed: -1 },
          "gooey",
          defenderSide,
        );
      }
      if (sharedHitReaction("cottondown")) {
        emitAbilityActivation(state, defenderSide, defender, "cottondown", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyBoosts(
          state,
          action.side,
          attacker,
          { speed: -1 },
          "cottondown",
          defenderSide,
        );
      }
      const gulpMissileReaction = sharedHitReaction("gulpmissile");
      if (gulpMissileReaction) {
        const gulpMissileForm = defender.abilityState?.gulpMissileForm;
        delete defender.abilityState.gulpMissileForm;
        emitAbilityActivation(state, defenderSide, defender, "gulpmissile", {
          form: gulpMissileForm,
          targetSide: action.side,
          target: attacker.name,
        });
        applyDirectDamage(
          state,
          action.side,
          attacker,
          Math.max(1, Math.floor(attacker.stats.hp / 4)),
          "gulpmissile",
          "ability",
        );
        if (!attacker.fainted && gulpMissileReaction.boosts?.defence) {
          applyBoosts(
            state,
            action.side,
            attacker,
            { defence: -1 },
            "gulpmissile",
            defenderSide,
          );
        } else if (!attacker.fainted && gulpMissileReaction.status) {
          applyStatus(
            state,
            action.side,
            attacker,
            gulpMissileReaction.status,
            rng,
            "gulpmissile",
            defenderSide,
          );
        }
      }
      if (sharedHitReaction("justified")) {
        emitAbilityActivation(state, defenderSide, defender, "justified", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyBoosts(state, defenderSide, defender, { attack: 1 }, "justified");
      }
      if (
        sharedHitReaction("magician") &&
        stealTargetItem(
          state,
          action.side,
          attacker,
          defenderSide,
          defender,
          "magician",
        )
      ) {
        emitAbilityActivation(state, action.side, attacker, "magician", {
          targetSide: defenderSide,
          target: defender.name,
          item: attacker.item,
        });
      }
      if (
        sharedHitReaction("cursedbody") &&
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "disable",
          "cursedbody",
          defenderSide,
        )
      ) {
        attacker.volatiles.disable.moveId = cleanId(move.id);
        attacker.volatiles.disable.move = move.name;
        emitAbilityActivation(state, defenderSide, defender, "cursedbody", {
          targetSide: action.side,
          target: attacker.name,
          move: move.name,
          moveId: cleanId(move.id),
        });
      }
      if (sharedHitReaction("poisonpoint")) {
        emitAbilityActivation(state, defenderSide, defender, "poisonpoint", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyStatus(
          state,
          action.side,
          attacker,
          "psn",
          rng,
          "poisonpoint",
          defenderSide,
        );
      }
      if (sharedHitReaction("static")) {
        state.events.push({
          turn: state.turn,
          type: "ability_activate",
          side: defenderSide,
          pokemon: defender.name,
          ability: "static",
          targetSide: action.side,
          target: attacker.name,
        });
        applyStatus(
          state,
          action.side,
          attacker,
          "par",
          rng,
          "static",
          defenderSide,
        );
      }
      if (sharedHitReaction("cutecharm")) {
        emitAbilityActivation(state, defenderSide, defender, "cutecharm", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyVolatileStatus(
          state,
          action.side,
          attacker,
          "attract",
          "cutecharm",
          defenderSide,
        );
      }
      if (sharedHitReaction("stamina")) {
        emitAbilityActivation(state, defenderSide, defender, "stamina", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyBoosts(state, defenderSide, defender, { defence: 1 }, "stamina");
      }
      if (sharedHitReaction("thermalexchange")) {
        emitAbilityActivation(state, defenderSide, defender, "thermalexchange", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyBoosts(
          state,
          defenderSide,
          defender,
          { attack: 1 },
          "thermalexchange",
        );
      }
      if (sharedHitReaction("weakarmor")) {
        emitAbilityActivation(state, defenderSide, defender, "weakarmor", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyBoosts(
          state,
          defenderSide,
          defender,
          { defence: -1, speed: 2 },
          "weakarmor",
        );
      }
      if (sharedHitReaction("toxicdebris")) {
        emitAbilityActivation(state, defenderSide, defender, "toxicdebris", {
          targetSide: action.side,
          target: attacker.name,
        });
        setSideCondition(
          state,
          action.side,
          defender,
          "toxicspikes",
          "toxicdebris",
        );
      }
      const contactDamageReaction =
        sharedHitReaction("roughskin") ?? sharedHitReaction("ironbarbs");
      if (contactDamageReaction) {
        const contactDamageAbility = contactDamageReaction.code;
        emitAbilityActivation(
          state,
          defenderSide,
          defender,
          contactDamageAbility,
          {
          targetSide: action.side,
          target: attacker.name,
          },
        );
        applyDirectDamage(
          state,
          action.side,
          attacker,
          Math.max(1, Math.floor(attacker.stats.hp / 8)),
          contactDamageAbility,
          "ability",
        );
      }
      if (sharedHitReaction("flamebody")) {
        emitAbilityActivation(state, defenderSide, defender, "flamebody", {
          targetSide: action.side,
          target: attacker.name,
        });
        applyStatus(
          state,
          action.side,
          attacker,
          "brn",
          rng,
          "flamebody",
          defenderSide,
        );
      }
      const effectSporeReaction = sharedHitReaction("effectspore");
      if (effectSporeReaction) {
        const effectSporeStatus = effectSporeReaction.status;
        if (
          applyStatus(
            state,
            action.side,
            attacker,
            effectSporeStatus,
            rng,
            "effectspore",
            defenderSide,
          )
        ) {
          emitAbilityActivation(state, defenderSide, defender, "effectspore", {
            targetSide: action.side,
            target: attacker.name,
            status: effectSporeStatus,
          });
        }
      }
      if (sharedHitReaction("poisontouch")) {
        emitAbilityActivation(state, action.side, attacker, "poisontouch", {
          targetSide: defenderSide,
          target: defender.name,
        });
        applyStatus(
          state,
          defenderSide,
          defender,
          "psn",
          rng,
          "poisontouch",
          action.side,
        );
      }
      if (disguiseBlockedHit && defender.hp > 0) {
        applyDirectDamage(
          state,
          defenderSide,
          defender,
          Math.max(1, Math.floor(defender.stats.hp / 8)),
          "disguise",
          "ability",
        );
      }
      if (damage > 0 && defender.hp > 0) {
        tryConsumePinchBerry(state, defenderSide, defender, move.name);
      }
    }
  }
  const postHit = sharedPostHitOutcome(
    move,
    attacker,
    defender,
    totalDamage,
    landedHits,
  );
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
  if (
    landedHits > 0 &&
    totalDamage > 0 &&
    isStellarTerastallized(attacker) &&
    !isTerapagosStellar(attacker)
  ) {
    const boostedTypes = new Set(
      (attacker.stellarBoostedTypes ?? []).map((type) => cleanId(type)),
    );
    if (!boostedTypes.has(cleanId(move.type))) {
      attacker.stellarBoostedTypes ??= [];
      attacker.stellarBoostedTypes.push(move.type);
      state.events.push({
        turn: state.turn,
        type: "stellar_boost_consumed",
        side: action.side,
        pokemon: attacker.name,
        move: move.name,
        moveType: move.type,
      });
    }
  }
  if (teraShellActive) {
    delete defender.abilityState.teraShellActive;
  }
  if (postHit.drainAmount > 0) {
    if (postHit.drainAsDamage) {
      emitAbilityActivation(state, defenderSide, defender, "liquidooze", {
        targetSide: action.side,
        target: attacker.name,
        move: move.name,
      });
      applyDirectDamage(
        state,
        action.side,
        attacker,
        postHit.drainAmount,
        "liquidooze",
        "ability",
      );
    } else {
      healPokemon(state, action.side, attacker, postHit.drainAmount, move.name);
    }
  }
  if (postHit.shellBellHealing > 0) {
    healPokemon(
      state,
      action.side,
      attacker,
      postHit.shellBellHealing,
      "shellbell",
    );
  }
  if (postHit.recoilDamage > 0 && !attacker.fainted) {
    const recoil = Math.min(attacker.hp, postHit.recoilDamage);
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
  if (postHit.lifeOrbDamage > 0 && !attacker.fainted) {
    const recoil = Math.min(attacker.hp, postHit.lifeOrbDamage);
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
  if (postHit.selfCost === "half_maximum_hp" && !attacker.fainted) {
    applySelfHpCost(state, action.side, attacker, move.name, [1, 2]);
  }
  if (postHit.selfCost === "all_current_hp" && !attacker.fainted) {
    attacker.hp = 0;
    markFainted(state, action.side, attacker);
  }
  if (postHit.selfCost === "self_destruct" && !attacker.fainted) {
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
  const curedStatus = postHit.curedStatus;
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
  const hasPostHitInstruction = (kind, effect = "") =>
    postHit.instructions.some(
      (instruction) =>
        instruction.kind === kind && (!effect || instruction.effect === effect),
    );
  const postHitBoosts = (stat, amount) =>
    postHit.instructions.some(
      (instruction) =>
        instruction.kind === "boost" && instruction.boosts?.[stat] === amount,
    );
  if (hasPostHitInstruction("reset_boosts")) {
    resetBoosts(state, defenderSide, defender, move.name);
  }
  if (postHitBoosts("defence", -1)) {
    applyBoosts(state, action.side, attacker, { defence: -1 }, move.name);
  }
  if (hasPostHitInstruction("volatile", "flinch")) {
    applyVolatileStatus(state, defenderSide, defender, "flinch", move.name);
  }
  if (hasPostHitInstruction("volatile_if_alive", "saltcure") && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "saltcure", move.name);
  }
  if (
    hasPostHitInstruction("volatile_if_alive", move.volatileStatus) &&
    !defender.fainted
  ) {
    applyVolatileStatus(
      state,
      defenderSide,
      defender,
      move.volatileStatus,
      move.name,
      action.side,
    );
  }
  if (hasPostHitInstruction("volatile_if_alive", "thousandwaves") && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "thousandwaves", move.name);
  }
  if (hasPostHitInstruction("volatile", "jawlock") && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "jawlock", move.name);
    applyVolatileStatus(state, action.side, attacker, "jawlock", move.name);
  }
  if (hasPostHitInstruction("clear_hazards")) {
    removeHazardsAndTerrain(state, action.side, move.name, attacker);
  }
  if (hasPostHitInstruction("side_condition", "spikes")) {
    setSideCondition(state, defenderSide, attacker, "spikes", move.name);
  }
  if (hasPostHitInstruction("side_condition", "stealthrock")) {
    setSideCondition(state, defenderSide, attacker, "stealthrock", move.name);
  }
  if (hasPostHitInstruction("remove_type", "electric")) {
    const nextTypes = attacker.types.filter((type) => type !== "Electric");
    setPokemonTypes(
      state,
      action.side,
      attacker,
      nextTypes.length ? nextTypes : ["Normal"],
      move.name,
    );
  }
  if (hasPostHitInstruction("remove_type", "fire")) {
    const nextTypes = attacker.types.filter((type) => type !== "Fire");
    setPokemonTypes(
      state,
      action.side,
      attacker,
      nextTypes.length ? nextTypes : ["Normal"],
      move.name,
    );
  }
  if (hasPostHitInstruction("volatile_if_alive", "smackdown") && !defender.fainted) {
    applyVolatileStatus(state, defenderSide, defender, "smackdown", move.name);
  }
  if (hasPostHitInstruction("leech_seed") && !defender.fainted) {
    applyLeechSeed(state, action.side, defenderSide, defender, move.name);
  }
  if (hasPostHitInstruction("yawn") && !defender.fainted) {
    applyYawn(state, action.side, defenderSide, defender, move.name);
  }
  if (postHitBoosts("attack", 1) && !attacker.fainted) {
    applyBoosts(state, action.side, attacker, { attack: 1 }, move.name);
  }
  if (
    hasPostHitInstruction("form_hint") &&
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
  if (hasPostHitInstruction("remove_attacker_item") && attacker.item) {
    removeTargetItem(state, action.side, attacker, move.name);
  }
  if (
    hasPostHitInstruction("consume_attacker_berry") &&
    cleanId(attacker.item).endsWith("berry")
  ) {
    consumeHeldItem(state, action.side, attacker, move.name);
  }
  if (
    hasPostHitInstruction("gulp_missile") &&
    !attacker.fainted
  ) {
    const form = postHit.instructions.find(
      (instruction) => instruction.kind === "gulp_missile",
    )?.effect;
    attacker.abilityState.gulpMissileForm = form;
    emitAbilityActivation(state, action.side, attacker, "gulpmissile", {
      form,
      move: move.name,
    });
  }
  if (
    hasPostHitInstruction("suppress_ability") &&
    defender.turnState?.acted &&
    !defender.fainted
  ) {
    suppressAbility(state, defenderSide, defender, move.name);
  }
  if (hasPostHitInstruction("end_stockpile")) {
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
  if (hasPostHitInstruction("clear_terrain") || hasPostHitInstruction("clear_terrain_and_boosts")) {
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
    if (hasPostHitInstruction("clear_terrain_and_boosts")) {
      resetBoosts(state, action.side, attacker, move.name);
      resetBoosts(state, defenderSide, defender, move.name);
    }
  }
  if (hasPostHitInstruction("mortal_spin")) {
    removeHazardsAndTerrain(state, action.side, move.name, attacker);
    if (!defender.fainted) {
      applyStatus(state, defenderSide, defender, "psn", rng, move.name, action.side);
    }
  }
  if (hasPostHitInstruction("item_removal_blocked")) {
    heldItemRemovalBlocked(
      state,
      defenderSide,
      defender,
      action.side,
      move.name,
    );
  }
  if (hasPostHitInstruction("remove_defender_item") && defender.item) {
    removeTargetItem(
      state,
      defenderSide,
      defender,
      move.name,
      action.side,
    );
  }
  if (
    hasPostHitInstruction("steal_defender_item")
  ) {
    stealTargetItem(state, action.side, attacker, defenderSide, defender, move.name);
  }
  if (
    hasPostHitInstruction("remove_consumable_defender_item") &&
    isConsumableBattleItem(defender.item)
  ) {
    removeTargetItem(
      state,
      defenderSide,
      defender,
      move.name,
      action.side,
    );
  }
  if (totalDamage > 0) {
    if (move.weather) {
      setFieldEffect(
        state,
        action.side,
        attacker,
        "weather",
        move.weather,
        move.name,
      );
    }
    if (move.terrain) {
      setFieldEffect(
        state,
        action.side,
        attacker,
        "terrain",
        move.terrain,
        move.name,
      );
    }
    if (move.pseudoWeather) {
      setFieldEffect(
        state,
        action.side,
        attacker,
        "pseudoWeather",
        move.pseudoWeather,
        move.name,
      );
    }
    if (move.sideCondition) {
      const targetSide =
        move.target === "allySide" || move.target === "self"
          ? action.side
          : defenderSide;
      setSideCondition(
        state,
        targetSide,
        attacker,
        move.sideCondition,
        move.name,
      );
    }
    if (defender.hp > 0 && Object.keys(move.boosts).length) {
      applyBoosts(
        state,
        defenderSide,
        defender,
        move.boosts,
        move.name,
        action.side,
      );
    }
  }
  const secondaryBlockSource = postHit.secondaryBlockSource;
  if (secondaryBlockSource === "shielddust") {
    emitAbilityActivation(
      state,
      defenderSide,
      defender,
      secondaryBlockSource,
      {
        targetSide: action.side,
        target: attacker.name,
        move: move.name,
      },
    );
  } else if (secondaryBlockSource === "covertcloak") {
    state.events.push({
      turn: state.turn,
      type: "item_activate",
      side: defenderSide,
      pokemon: defender.name,
      item: secondaryBlockSource,
      source: move.name,
    });
  }
  for (const secondary of move.secondaries) {
    if (defender.hp <= 0) break;
    const roll = JSON.parse(
      rollSharedSecondaryJson(
        JSON.stringify({
          chance: Number(secondary.chance ?? 100),
          attackerAbility: activeAbility(attacker),
          blocked: postHit.secondaryEffectsSuppressed,
          defenderFainted: defender.fainted,
          rngState: rng.snapshot(),
        }),
      ),
    );
    rng.restore(roll.rngState);
    if (!roll.triggered) continue;
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
  if (totalDamage > 0 && !defender.fainted && Object.keys(move.selfBoosts).length) {
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
    !attacker.fainted &&
    totalDamage > 0 &&
    triggersContactPunishment(attacker, move) &&
    activeAbility(defender) === "aftermath" &&
    !dampActive(state) &&
    !ignoresDefenderAbility(attacker)
  ) {
    emitAbilityActivation(state, defenderSide, defender, "aftermath", {
      targetSide: action.side,
      target: attacker.name,
    });
    applyDirectDamage(
      state,
      action.side,
      attacker,
      Math.max(1, Math.floor(attacker.stats.hp / 4)),
      "aftermath",
      "ability",
    );
  }
  if (defenderFainted && !attacker.fainted) {
    applyKnockoutAbility(state, action.side, attacker, defender);
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
    executeForceSwitch(state, defenderSide, move.name, rng);
  }
  if (totalDamage > 0 && move.selfSwitch) {
    executeSelfSwitch(
      state,
      action.side,
      move.name,
      action.selfSwitchSlot,
    );
  }
  return totalDamage > 0;
}

function bestFaintReplacement(state, sideIndex) {
  const side = state.sides[sideIndex];
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const opponent = activePokemon(state, opponentSide);
  const teamRoleAnalysis = analyzeTeamProfile(
    side.team.map((member) => aiRoleAnalysisMember(member)),
  );
  const selected = selectAiSwitchCandidate(
    side.team
    .map((pokemon, index) => {
      if (pokemon.fainted || pokemon.hp <= 0) return null;
      const slot = index + 1;
      const hazardDamage = predictedEntryHazardDamage(
        state,
        sideIndex,
        pokemon,
      );
      const hpAfterHazards = Math.max(0, pokemon.hp - hazardDamage);
      if (hpAfterHazards <= 0) return null;
      const targetAttack = bestAiAttackProfile(
        state,
        sideIndex,
        pokemon,
        opponentSide,
        opponent,
      );
      const targetIncoming = bestAiAttackProfile(
        state,
        opponentSide,
        opponent,
        sideIndex,
        pokemon,
      );
      const projectedMoveCandidates = projectedSwitchMoveCandidates(
        state,
        sideIndex,
        pokemon,
        opponentSide,
        opponent,
        hpAfterHazards,
        "expert",
        "balanced",
      );
      const projectedBestMove = projectedMoveCandidates[0] ?? null;
      const projectedExpectedDamage = Number(
        projectedBestMove?.expectedDamage ?? targetAttack.expectedDamage,
      );
      const projectedKnockoutBeforeActionProbability = Number(
        projectedBestMove?.opponentKnockoutBeforeActionProbability ?? 1,
      );
      const targetIncomingRatio =
        targetIncoming.expectedDamage / pokemon.stats.hp;
      const targetOutgoingRatio =
        opponent.hp > 0 ? projectedExpectedDamage / opponent.hp : 0;
      const targetActsFirst =
        targetAttack.priority > targetIncoming.priority ||
        (targetAttack.priority === targetIncoming.priority &&
          (Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0
            ? effectiveSpeed(pokemon, state, sideIndex) <
              effectiveSpeed(opponent, state, opponentSide)
            : effectiveSpeed(pokemon, state, sideIndex) >
              effectiveSpeed(opponent, state, opponentSide)));
      const canReachNextAction =
        projectedKnockoutBeforeActionProbability < 0.75;
      const canKoOnNextAction =
        canReachNextAction &&
        projectedBestMove?.koChance === "guaranteed";
      const priorityKo =
        Number(projectedBestMove?.priority ?? targetAttack.priority) >
          targetIncoming.priority &&
        projectedBestMove?.koChance === "guaranteed";
      const immediateKoBeforeOpponent =
        canReachNextAction &&
        projectedBestMove?.koChance === "guaranteed";
      const targetRoleProfile = teamRoleAnalysis.roles[index];
      let matchupValue = 0;
      matchupValue += targetOutgoingRatio * 45;
      matchupValue -= targetIncomingRatio * 55;
      matchupValue -= (hazardDamage / pokemon.stats.hp) * 100;
      if (targetIncoming.expectedDamage === 0) matchupValue += 24;
      if (canKoOnNextAction) matchupValue += 30;
      if (!canReachNextAction) matchupValue -= 90;
      const fieldSynergy = fieldSwitchSynergy(
        state,
        sideIndex,
        pokemon,
        opponent,
      );
      return {
        slot,
        id: pokemon.id,
        switchId: pokemon.id,
        name: `${pokemon.name}(으)로 교체`,
        active: false,
        fainted: false,
        forceSwitch: true,
        hpPercent: hpAfterHazards / pokemon.stats.hp,
        expectedDamage:
          Math.min(opponent.hp, projectedExpectedDamage) * 0.4,
        matchupValue,
        targetIncomingDamageRatio: targetIncomingRatio,
        targetOutgoingDamageRatio: targetOutgoingRatio,
        targetStatus: pokemon.status,
        speedAdvantage: targetActsFirst,
        hpAfterSwitchIn: hpAfterHazards,
        survivesSwitchIn: true,
        canReachNextAction,
        canKoOnNextAction,
        projectedBestMoveId:
          projectedBestMove?.id ?? targetAttack.moveId,
        projectedBestMoveName:
          projectedBestMove?.name ?? targetAttack.moveId,
        projectedBestMoveScore: projectedBestMove?.score,
        projectedKnockoutBeforeActionProbability,
        targetPrimaryRole:
          targetRoleProfile?.primaryRole ?? "support",
        targetRoleScore: targetRoleProfile?.roles?.[0]?.score ?? 0,
        targetAceScore: targetRoleProfile?.aceScore ?? 0,
        targetAceQualified:
          targetRoleProfile?.aceProfile?.qualifies === true,
        priorityKo,
        immediateKoBeforeOpponent,
        hazardDamage,
        hazardDamageRatio: hazardDamage / pokemon.stats.hp,
        ...fieldSynergy,
      };
    })
    .filter(Boolean),
    {
      difficulty: "expert",
      strategy: "balanced",
      rng: createAiRng(state.seed, sideIndex, state.turn * 31),
    },
  );
  if (selected) return selected.slot - 1;
  return side.team.findIndex(
    (pokemon, index) =>
      index !== side.active &&
      !pokemon.fainted &&
      pokemon.hp > 0,
  );
}

function advanceFaintedSides(state) {
  for (let sideIndex = 0; sideIndex < state.sides.length; sideIndex += 1) {
    const side = state.sides[sideIndex];
    const replacement = JSON.parse(
      evaluateSharedFaintReplacementJson(
        JSON.stringify({
          activeSlot: side.active,
          activeFainted: side.team[side.active].fainted,
          manualSelection: state.manualFaintSwitchSides.includes(sideIndex),
          teamHp: side.team.map((pokemon) => pokemon.hp),
          teamFainted: side.team.map((pokemon) => pokemon.fainted),
        }),
      ),
    );
    if (!replacement.automatic) continue;
    const next = bestFaintReplacement(state, sideIndex);
    if (replacement.eligibleSlots.includes(next)) {
      const faintedPokemon = side.team[side.active];
      side.active = next;
      side.team[next].activeTurns = 0;
      state.events.push({
        turn: state.turn,
        type: "switch",
        side: sideIndex,
        fromPokemon: faintedPokemon.name,
        pokemon: side.team[next].name,
        slot: next + 1,
        remainingHp: side.team[next].hp,
        maximumHp: side.team[next].stats.hp,
        status: side.team[next].status,
        automatic: true,
        forced: true,
        selection: "matchup_score",
      });
      applyEntryHazards(state, sideIndex, side.team[next]);
      applyEntryAbilities(state, sideIndex, side.team[next]);
    }
  }
  const outcome = JSON.parse(
    evaluateSharedBattleOutcomeJson(
      JSON.stringify({
        sideNames: state.sides.map((side) => side.name),
        faintedTeams: state.sides.map((side) =>
          side.team.map((pokemon) => pokemon.fainted),
        ),
      }),
    ),
  );
  if (!outcome.completed) return;
  state.status = outcome.status;
  state.winner = outcome.winner;
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
  const faintedPokemon = side.team[side.active];
  const target = side.team[targetIndex];
  const replacement = JSON.parse(
    evaluateSharedFaintReplacementJson(
      JSON.stringify({
        activeSlot: side.active,
        activeFainted: faintedPokemon.fainted,
        manualSelection: true,
        teamHp: side.team.map((pokemon) => pokemon.hp),
        teamFainted: side.team.map((pokemon) => pokemon.fainted),
      }),
    ),
  );
  if (!target || !replacement.eligibleSlots.includes(targetIndex)) {
    throw new Error(`Side ${sideIndex + 1} cannot switch to slot ${switchSlot}`);
  }
  side.active = targetIndex;
  target.activeTurns = 0;
  state.events.push({
    turn: state.turn,
    type: "switch",
    side: sideIndex,
    fromPokemon: faintedPokemon.name,
    pokemon: target.name,
    slot: Number(switchSlot),
    remainingHp: target.hp,
    maximumHp: target.stats.hp,
    status: target.status,
    automatic: false,
    forced: true,
    selection: "faint_replacement",
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
    const damage = calculateSharedModifiedDamage(
      range.baseDamage,
      range.stab,
      range.effectiveness,
      range.itemModifier,
      range.abilityModifier,
      range.fieldModifier,
      1,
      1,
      target.hp,
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

function sharedEndTurnResidualInput(state, sideIndex, pokemon) {
  const opposingSide = sideIndex === 0 ? 1 : sideIndex === 1 ? 0 : null;
  const opposingPokemon =
    opposingSide === null ? null : activePokemon(state, opposingSide);
  return {
    side: sideIndex,
    hp: pokemon.hp,
    maximumHp: pokemon.stats.hp,
    types: pokemon.types,
    status: pokemon.status,
    toxicCounter: pokemon.toxicCounter,
    ability: activeAbility(pokemon),
    item: pokemon.item,
    weather: effectiveWeather(state),
    terrain: state.field?.terrain?.id ?? "",
    grounded: isGrounded(pokemon),
    effectivelyAsleep: isEffectivelyAsleep(pokemon),
    opposingAbility: opposingPokemon ? activeAbility(opposingPokemon) : "",
    opposingFainted: opposingPokemon?.fainted ?? true,
    volatiles: Object.values(pokemon.volatiles ?? {}).map((volatile) => ({
      id: volatile.id,
      source: volatile.source ?? "",
      sourceSide: volatile.sourceSide ?? null,
      count: Number.isFinite(volatile.count) ? volatile.count : null,
    })),
  };
}

function sharedEndTurnResidual(state, sideIndex, pokemon) {
  return JSON.parse(
    evaluateSharedEndTurnResidualJson(
      JSON.stringify(sharedEndTurnResidualInput(state, sideIndex, pokemon)),
    ),
  );
}

function applySharedResidualOperations(state, sideIndex, pokemon, result) {
  for (const operation of result.operations ?? []) {
    if (operation.kind === "toxic_counter") {
      pokemon.toxicCounter = Number(operation.count ?? pokemon.toxicCounter);
      continue;
    }
    if (operation.kind === "heal") {
      if (["dryskin", "icebody", "poisonheal"].includes(operation.effect)) {
        emitAbilityActivation(
          state,
          sideIndex,
          pokemon,
          operation.effect,
          operation.effect === "poisonheal" ? { status: pokemon.status } : {},
        );
      }
      healPokemon(
        state,
        sideIndex,
        pokemon,
        Number(operation.amount ?? 0),
        operation.effect,
      );
      continue;
    }
    if (operation.kind === "boost") {
      if (operation.effect === "speedboost") {
        emitAbilityActivation(state, sideIndex, pokemon, "speedboost");
      }
      applyBoosts(
        state,
        sideIndex,
        pokemon,
        operation.boosts ?? {},
        operation.effect,
      );
      continue;
    }
    if (operation.kind === "perish_tick") {
      const perish = pokemon.volatiles?.perishsong;
      if (perish) perish.count = Number(operation.count ?? 0);
      state.events.push({
        turn: state.turn,
        type: "volatile_activate",
        side: sideIndex,
        pokemon: pokemon.name,
        effect: "perishsong",
        count: Number(operation.count ?? 0),
        source: operation.effect,
      });
      continue;
    }
    if (operation.kind === "faint") {
      pokemon.hp = 0;
      markFainted(state, sideIndex, pokemon);
      continue;
    }
    if (operation.kind === "drain") {
      const applied = Math.min(pokemon.hp, Number(operation.amount ?? 0));
      pokemon.hp -= applied;
      state.events.push({
        turn: state.turn,
        type: "damage",
        side: sideIndex,
        pokemon: pokemon.name,
        source: operation.effect,
        cause: operation.cause,
        damage: applied,
        remainingHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        effectiveness: 1,
      });
      const sourceSide = operation.sourceSide;
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
          operation.effect,
        );
      }
      markFainted(state, sideIndex, pokemon);
      continue;
    }
    if (operation.kind !== "damage") continue;
    if (["dryskin", "solarpower"].includes(operation.effect)) {
      emitAbilityActivation(state, sideIndex, pokemon, operation.effect);
    }
    if (operation.effect === "baddreams") {
      const opposingSide = sideIndex === 0 ? 1 : 0;
      const opposingPokemon = activePokemon(state, opposingSide);
      emitAbilityActivation(
        state,
        opposingSide,
        opposingPokemon,
        "baddreams",
        { targetSide: sideIndex, target: pokemon.name },
      );
    }
    const applied = Math.min(pokemon.hp, Number(operation.amount ?? 0));
    pokemon.hp -= applied;
    state.events.push({
      turn: state.turn,
      type: "damage",
      side: sideIndex,
      pokemon: pokemon.name,
      source: operation.effect,
      cause: operation.cause,
      damage: applied,
      remainingHp: pokemon.hp,
      maximumHp: pokemon.stats.hp,
      effectiveness: 1,
    });
    markFainted(state, sideIndex, pokemon);
  }
  pokemon.toxicCounter = Number(result.toxicCounter ?? pokemon.toxicCounter);
}

function applyEndTurnEffects(state, rng) {
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
    const weather = effectiveWeather(state);
    if (
      activeAbility(pokemon) === "shedskin" &&
      pokemon.status &&
      rng.next() < 0.33
    ) {
      emitAbilityActivation(state, sideIndex, pokemon, "shedskin", {
        status: pokemon.status,
      });
      curePokemonStatus(state, sideIndex, pokemon, "shedskin");
    }
    if (
      activeAbility(pokemon) === "hydration" &&
      ["raindance", "primordialsea"].includes(weather) &&
      pokemon.status
    ) {
      emitAbilityActivation(state, sideIndex, pokemon, "hydration");
      curePokemonStatus(state, sideIndex, pokemon, "hydration");
    }
    const residual = sharedEndTurnResidual(state, sideIndex, pokemon);
    applySharedResidualOperations(state, sideIndex, pokemon, residual);
    if (pokemon.fainted) continue;

    if (
      cleanId(pokemon.item) === "toxicorb" &&
      canReceiveStatus(pokemon, "tox", state, sideIndex, sideIndex)
    ) {
      applyStatus(
        state,
        sideIndex,
        pokemon,
        "tox",
        rng,
        "Toxic Orb",
        sideIndex,
      );
    } else if (
      cleanId(pokemon.item) === "flameorb" &&
      canReceiveStatus(pokemon, "brn", state, sideIndex, sideIndex)
    ) {
      applyStatus(
        state,
        sideIndex,
        pokemon,
        "brn",
        rng,
        "Flame Orb",
        sideIndex,
      );
    }
    if (
      activeAbility(pokemon) === "harvest" &&
      !pokemon.item &&
      cleanId(pokemon.consumedItem).endsWith("berry") &&
      (["sunnyday", "desolateland"].includes(weather) || rng.next() < 0.5)
    ) {
      const harvestedItem = cleanId(pokemon.consumedItem);
      pokemon.item = harvestedItem;
      pokemon.consumedItem = "";
      const consumedIndex = (state.consumedItems ?? []).findLastIndex(
        (entry) =>
          entry.side === sideIndex &&
          entry.pokemon === pokemon.name &&
          cleanId(entry.item) === harvestedItem,
      );
      if (consumedIndex >= 0) state.consumedItems.splice(consumedIndex, 1);
      emitAbilityActivation(state, sideIndex, pokemon, "harvest", {
        item: harvestedItem,
      });
      state.events.push({
        turn: state.turn,
        type: "item_received",
        side: sideIndex,
        pokemon: pokemon.name,
        item: harvestedItem,
        source: "harvest",
      });
    }
    if (activeAbility(pokemon) === "pickup" && !pokemon.item) {
      const pickupIndex = (state.consumedItems ?? []).findLastIndex(
        (entry) => entry.turn === state.turn && entry.item,
      );
      if (pickupIndex >= 0) {
        const [pickedUp] = state.consumedItems.splice(pickupIndex, 1);
        pokemon.item = pickedUp.item;
        emitAbilityActivation(state, sideIndex, pokemon, "pickup", {
          item: pickedUp.item,
          sourcePokemon: pickedUp.pokemon,
        });
        state.events.push({
          turn: state.turn,
          type: "item_received",
          side: sideIndex,
          pokemon: pokemon.name,
          item: pickedUp.item,
          source: "pickup",
        });
      }
    }
  }
  state.consumedItems = (state.consumedItems ?? []).filter(
    (entry) => entry.turn >= state.turn,
  );
  for (const [sideIndex, side] of state.sides.entries()) {
    updateZenModeForm(state, sideIndex, side.team[side.active]);
  }
}
function sharedTimedEffect(kind, id, turns) {
  return JSON.parse(
    evaluateSharedTimedEffectJson(
      JSON.stringify({ kind, id, turns: Number(turns) }),
    ),
  );
}

function advanceTimedEffects(state, rng) {
  for (const kind of ["weather", "terrain"]) {
    const effect = state.field[kind];
    if (!effect || !Number.isFinite(effect.turns)) continue;
    const result = sharedTimedEffect(kind, effect.id, effect.turns);
    effect.turns = result.remainingTurns;
    if (!result.ended) {
      state.events.push({
        turn: state.turn,
        type: "field_tick",
        fieldKind: kind,
        effect: effect.id,
        remainingTurns: effect.turns,
      });
      continue;
    }
    state.events.push({
      turn: state.turn,
      type: "field_end",
      fieldKind: kind,
      effect: effect.id,
    });
    state.field[kind] = null;
    if (kind === "weather") updateForecastForms(state);
    for (let sideIndex = 0; sideIndex < state.sides.length; sideIndex += 1) {
      const pokemon = activePokemon(state, sideIndex);
      initializeParadoxAbility(
        state,
        sideIndex,
        pokemon,
        activeAbility(pokemon),
      );
    }
  }
  for (const [id, effect] of Object.entries(state.field.pseudoWeather)) {
    const result = sharedTimedEffect("pseudoWeather", id, effect.turns);
    effect.turns = result.remainingTurns;
    if (!result.ended) {
      state.events.push({
        turn: state.turn,
        type: "field_tick",
        fieldKind: "pseudoWeather",
        effect: id,
        remainingTurns: effect.turns,
      });
      continue;
    }
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
      const result = sharedTimedEffect("sideCondition", id, condition.turns);
      condition.turns = result.remainingTurns;
      if (!result.ended) continue;
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
        const result = sharedTimedEffect("volatile", id, volatile.turns);
        volatile.turns = result.remainingTurns;
        if (!result.ended) continue;
        if (result.triggerStatus) {
          applyStatus(
            state,
            sideIndex,
            pokemon,
            result.triggerStatus,
            rng,
            volatile.source || id,
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
      const result = JSON.parse(
        evaluateSharedDynamaxExpiryJson(
          JSON.stringify({
            hp: pokemon.hp,
            maximumHp: pokemon.stats.hp,
            baseMaximumHp: pokemon.baseMaximumHp,
            remainingTurns: pokemon.dynamaxTurns,
          }),
        ),
      );
      pokemon.dynamaxTurns = result.remainingTurns;
      if (!result.ended) continue;
      const dynamaxMode = pokemon.dynamaxMode;
      pokemon.hp = result.hp;
      pokemon.stats.hp = result.maximumHp;
      pokemon.dynamaxMode = null;
      state.events.push({
        turn: state.turn,
        type: "dynamax_end",
        side: sideIndex,
        pokemon: pokemon.name,
        reason: "duration",
        dynamaxMode,
      });
    }
  }
}

function turnResolutionState(previousState, compactHistoryTurns = null) {
  if (!Number.isInteger(compactHistoryTurns)) {
    return clone(previousState);
  }
  const historyTurns = Math.max(0, compactHistoryTurns);
  const minimumTurn = Math.max(0, previousState.turn - historyTurns + 1);
  return clone({
    ...previousState,
    events: (previousState.events ?? []).filter(
      (event) => Number(event.turn ?? 0) >= minimumTurn,
    ),
    aiTrace: [],
    turnSnapshots: [],
  });
}

function resolveSimpleTurnInternal(previousState, commands, options = {}) {
  if (previousState.status !== "running") {
    throw new Error("The battle is already finished");
  }
  if (!Array.isArray(commands) || commands.length !== 2) {
    throw new Error("Exactly two commands are required");
  }
  const state = turnResolutionState(
    previousState,
    options.compactHistoryTurns ?? null,
  );
  const suppressedRandomSecondaries = [];
  if (options.suppressRandomSecondaries === true) {
    for (const side of state.sides) {
      for (const pokemon of side.team) {
        for (const move of pokemon.moves) {
          if (move.secondaries.some((effect) => effect.chance < 100)) {
            suppressedRandomSecondaries.push({
              move,
              secondaries: move.secondaries,
            });
            move.secondaries = move.secondaries.filter(
              (effect) => effect.chance >= 100,
            );
          }
        }
      }
    }
  }
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
    } else if (action.kind === "item") {
      executeTrainerItem(state, action);
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
        action.resolvedMove,
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
        `Unsupported move effect in cobbleventure-simple strict validation: ${unsupported.move} - ${unsupported.reason}`,
      );
    }
  }
  applyEndTurnEffects(state, rng);
  advanceTimedEffects(state, rng);
  expireDynamax(state);
  for (const [sideIndex, side] of state.sides.entries()) {
    const pokemon = side.team[side.active];
    const switchedInThisTurn = state.events.some(
      (event) =>
        event.turn === state.turn &&
        event.type === "switch" &&
        event.side === sideIndex &&
        event.pokemon === pokemon?.name,
    );
    if (!pokemon?.fainted && !switchedInThisTurn) {
      pokemon.activeTurns = Math.max(0, Number(pokemon.activeTurns ?? 0)) + 1;
    }
  }
  advanceFaintedSides(state);
  for (const { move, secondaries } of suppressedRandomSecondaries) {
    move.secondaries = secondaries;
  }
  state.rngState = rng.snapshot();
  return state;
}

export function resolveSimpleTurn(previousState, commands) {
  return resolveSimpleTurnInternal(previousState, commands);
}

export function simulateSimpleTurn(previousState, commands, options = {}) {
  return resolveSimpleTurnInternal(previousState, commands, {
    compactHistoryTurns: Math.max(
      0,
      Number.isInteger(options.historyTurns) ? options.historyTurns : 3,
    ),
    suppressRandomSecondaries: options.suppressRandomSecondaries === true,
  });
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
  const weather = effectiveWeather(state);
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

function aiEndTurnResidualDamage(pokemon, state) {
  const sideIndex = state.sides.findIndex((side) => side.team.includes(pokemon));
  const result = sharedEndTurnResidual(state, sideIndex, pokemon);
  return (result.operations ?? []).reduce((total, operation) => {
    if (["damage", "drain"].includes(operation.kind)) {
      return total + Number(operation.amount ?? 0);
    }
    if (operation.kind === "heal" && operation.effect === "poisonheal") {
      return total - Number(operation.amount ?? 0);
    }
    return total;
  }, 0);
}
function aiDamageThresholdChance(minimum, maximum, threshold) {
  if (threshold <= 0) return 1;
  if (maximum < threshold) return 0;
  if (minimum >= threshold) return 1;
  return Math.max(
    0,
    Math.min(1, (maximum - threshold + 1) / (maximum - minimum + 1)),
  );
}

function saltCureResidualDamage(target) {
  return JSON.parse(
    deriveSaltCureDamageJson(
      JSON.stringify({
        maxHp: target.stats.hp,
        waterOrSteel: target.types.some((type) => ["Water", "Steel"].includes(type)),
      }),
    ),
  );
}

function aiExpectedHitCount(move, attacker) {
  if (!move.multihit) return 1;
  const minimum = Math.max(1, Math.floor(move.multihit[0] ?? 1));
  const maximum = Math.max(minimum, Math.floor(move.multihit[1] ?? minimum));
  if (activeAbility(attacker) === "skilllink") return maximum;
  if (minimum === maximum) return minimum;
  if (minimum === 2 && maximum === 5 && LOADED_DICE_ITEMS.has(cleanId(attacker.item))) {
    return 4.5;
  }
  if (minimum === 2 && maximum === 5) return 3;
  return (minimum + maximum) / 2;
}

function aiDamageOutcomeProfile(attacker, defender, move, range) {
  const hitCount = aiExpectedHitCount(move, attacker);
  const criticalModifier =
    range.effectiveness !== 0
      ? criticalDamageModifier(
          attacker,
          defender,
          move.willCrit || attacker.volatiles?.laserfocus,
        )
      : 1;
  const disguiseBlocked =
    activeAbility(defender) === "disguise" &&
    defender.abilityState?.disguiseBusted !== true &&
    !ignoresDefenderAbility(attacker) &&
    range.effectiveness !== 0;
  const damagingHitCount = Math.max(0, hitCount - (disguiseBlocked ? 1 : 0));
  const disguiseDamage = disguiseBlocked
    ? Math.max(1, Math.floor(defender.stats.hp / 8))
    : 0;
  const totalMinimum =
    range.minimum * damagingHitCount * criticalModifier + disguiseDamage;
  const totalMaximum =
    range.maximum * damagingHitCount * criticalModifier + disguiseDamage;
  const sturdyCanTrigger =
    activeAbility(defender) === "sturdy" &&
    defender.hp >= defender.stats.hp &&
    defender.hp > 1 &&
    !ignoresDefenderAbility(attacker) &&
    range.effectiveness !== 0;
  const focusSashCanTrigger =
    cleanId(defender.item) === "focussash" &&
    defender.hp >= defender.stats.hp &&
    defender.hp > 1 &&
    range.effectiveness !== 0;
  const sturdyBlocked = sturdyCanTrigger && hitCount <= 1 && totalMaximum >= defender.hp;
  const focusSashBlocked =
    focusSashCanTrigger && hitCount <= 1 && totalMaximum >= defender.hp;
  const singleHitSurvivalBlocked = sturdyBlocked || focusSashBlocked;
  const breaksSturdy = sturdyCanTrigger && hitCount > 1 && totalMaximum >= defender.hp;
  const breaksFocusSash =
    focusSashCanTrigger && hitCount > 1 && totalMaximum >= defender.hp;
  const effectiveMaximum = singleHitSurvivalBlocked
    ? Math.max(0, defender.hp - 1)
    : totalMaximum;
  const effectiveMinimum =
    singleHitSurvivalBlocked && totalMinimum >= defender.hp
      ? Math.max(0, defender.hp - 1)
      : totalMinimum;
  return {
    hitCount,
    totalMinimum,
    totalMaximum,
    effectiveMinimum,
    effectiveMaximum,
    disguiseBlocked,
    sturdyBlocked,
    focusSashBlocked,
    singleHitSurvivalBlocked,
    breaksSturdy,
    breaksFocusSash,
    koChance:
      effectiveMaximum < defender.hp
        ? "none"
        : effectiveMinimum >= defender.hp
          ? "guaranteed"
          : "possible",
  };
}

function aiExpectedMoveDamage(
  attacker,
  defender,
  move,
  state,
  attackerSide,
  defenderSide,
) {
  const criticalChance = preventsCriticalHit(defender, attacker)
    ? 0
    : move.willCrit || attacker.volatiles?.laserfocus
      ? 1
      : activeAbility(attacker) === "superluck" ||
          heldItemCriticalRatioBoost(attacker) > 0
        ? criticalHitChance(attacker, move)
        : 0;
  const critical = criticalChance >= 1;
  const estimatedMove = resolveEstimatedMovePower(
    attacker,
    defender,
    move,
    state,
    attackerSide,
    defenderSide,
  );
  if (isMoveBlockedByDynamaxTarget(estimatedMove, defender)) {
    return {
      move: estimatedMove,
      range: {
        minimum: 0,
        maximum: 0,
        stab: 1,
        effectiveness: 1,
        itemModifier: 1,
        abilityModifier: 1,
        fieldModifier: 1,
      },
      expectedDamage: 0,
    };
  }
  const fixedDamage = fixedDamageAmount(estimatedMove, attacker, defender);
  if (fixedDamage !== null) {
    const effectiveness = moveEffectiveness(
      estimatedMove,
      defender.types,
      attacker,
      defender,
    );
    const expectedDamage =
      effectiveness === 0 ? 0 : Math.min(defender.hp, fixedDamage);
    return {
      move: estimatedMove,
      range: {
        minimum: expectedDamage,
        maximum: expectedDamage,
        stab: 1,
        effectiveness,
        itemModifier: 1,
        abilityModifier: 1,
        fieldModifier: 1,
      },
      expectedDamage,
    };
  }
  const range = calculateDamageRange(attacker, defender, estimatedMove, {
    state,
    attackerSide,
    defenderSide,
    critical,
  });
  const criticalModifier =
    range.effectiveness !== 0
      ? 1 +
        criticalChance *
          (criticalDamageModifier(attacker, defender, true) - 1)
      : 1;
  return {
    move: estimatedMove,
    range,
    expectedDamage:
      ((range.minimum + range.maximum) / 2) *
      aiExpectedHitCount(move, attacker) *
      criticalModifier,
  };
}

function boostedPokemonForAi(pokemon, boosts) {
  const boosted = clone(pokemon);
  boosted.boosts = { ...pokemon.boosts };
  for (const [stat, amount] of Object.entries(boosts ?? {})) {
    if (!BOOST_STATS.includes(stat) || !Number.isFinite(amount)) continue;
    const modifiedAmount = activeAbility(pokemon) === "simple" ? amount * 2 : amount;
    boosted.boosts[stat] = Math.max(
      -6,
      Math.min(6, Number(boosted.boosts[stat] ?? 0) + modifiedAmount),
    );
  }
  return boosted;
}

function aiSetupFollowupValue(
  pokemon,
  defender,
  move,
  state,
  sideIndex,
  defenderSide,
) {
  const selfBoosts = move.selfBoosts ?? {};
  const hasOffensiveBoost = ["attack", "specialAttack", "speed"].some(
    (stat) => Number(selfBoosts[stat] ?? 0) > 0,
  );
  if (!hasOffensiveBoost) return {};

  const damagingMoves = pokemon.moves.filter(
    (candidate) =>
      candidate.category !== "Status" &&
      candidate.power > 0 &&
      cleanId(candidate.id) !== cleanId(move.id),
  );
  if (damagingMoves.length === 0) return {};

  const boosted = boostedPokemonForAi(pokemon, selfBoosts);
  const effectiveBoostTotal = ["attack", "specialAttack", "speed"].reduce(
    (sum, stat) =>
      sum +
      Math.max(
        0,
        Number(boosted.boosts?.[stat] ?? 0) - Number(pokemon.boosts?.[stat] ?? 0),
      ),
    0,
  );
  const trickRoomActive =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  const currentSpeed = effectiveSpeed(pokemon, state, sideIndex);
  const boostedSpeed = effectiveSpeed(boosted, state, sideIndex);
  const livingTargets = state.sides[defenderSide].team.filter(
    (target) => !target.fainted && target.hp > 0,
  );

  const bestDamageAgainst = (attacker, target) =>
    damagingMoves.reduce((best, candidate) => {
      const displayMove = aiDisplayMoveData(attacker, candidate);
      const accuracy = expectedAccuracyFraction(
        attacker,
        target,
        displayMove,
        state,
      );
      const damage =
        aiExpectedMoveDamage(
          attacker,
          target,
          displayMove,
          state,
          sideIndex,
          defenderSide,
        ).expectedDamage * accuracy;
      return Math.max(best, damage);
    }, 0);

  const targetProfiles = livingTargets.map((target) => {
    const currentBest = bestDamageAgainst(pokemon, target);
    const boostedBest = bestDamageAgainst(boosted, target);
    const currentKo = currentBest >= target.hp;
    const boostedKo = boostedBest >= target.hp;
    const currentPressure = Math.min(1.25, currentBest / target.hp);
    const boostedPressure = Math.min(1.25, boostedBest / target.hp);
    const targetSpeed = effectiveSpeed(target, state, defenderSide);
    const actsFirstBefore = trickRoomActive
      ? currentSpeed < targetSpeed
      : currentSpeed > targetSpeed;
    const actsFirstAfter = trickRoomActive
      ? boostedSpeed < targetSpeed
      : boostedSpeed > targetSpeed;
    return {
      target,
      currentBest,
      boostedBest,
      currentKo,
      boostedKo,
      pressureGain: currentKo
        ? 0
        : Math.max(0, boostedPressure - currentPressure),
      newKo: boostedKo && !currentKo,
      newSpeedAdvantage: actsFirstAfter && !actsFirstBefore,
    };
  });
  const currentProfile =
    targetProfiles.find((profile) => profile.target === defender) ?? targetProfiles[0];
  const futureProfiles = targetProfiles.filter((profile) => profile.target !== defender);
  const futurePressureGain = futureProfiles.reduce(
    (sum, profile) => sum + profile.pressureGain,
    0,
  );
  const currentBest = currentProfile?.currentBest ?? 0;
  const boostedBest = currentProfile?.boostedBest ?? 0;
  const improvement = currentProfile?.pressureGain
    ? Math.max(0, boostedBest - currentBest)
    : 0;
  const koAfterSetup = currentProfile?.boostedKo === true;
  const koBeforeSetup = currentProfile?.currentKo === true;
  return {
    setupCurrentBestDamage: Math.round(currentBest * 100) / 100,
    setupBoostedBestDamage: Math.round(boostedBest * 100) / 100,
    setupDamageImprovement: Math.round(improvement * 100) / 100,
    setupKoAfterBoost: koAfterSetup,
    setupKoBeforeBoost: koBeforeSetup,
    setupEffectiveBoostTotal: effectiveBoostTotal,
    setupBoostAlreadyMaxed: effectiveBoostTotal <= 0,
    setupLivingTargetCount: targetProfiles.length,
    setupFutureTargetCount: futureProfiles.length,
    setupNewKoTargets: targetProfiles.filter((profile) => profile.newKo).length,
    setupFutureNewKoTargets: futureProfiles.filter((profile) => profile.newKo).length,
    setupNewSpeedAdvantages: targetProfiles.filter(
      (profile) => profile.newSpeedAdvantage,
    ).length,
    setupFuturePressureGain: Math.round(futurePressureGain * 100) / 100,
    setupCurrentPressureGain:
      Math.round(Number(currentProfile?.pressureGain ?? 0) * 100) / 100,
  };
}

function aiBatonPassProfile(
  state,
  sideIndex,
  source,
  additionalBoosts = {},
) {
  const side = state.sides[sideIndex];
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const hasBatonPass = source.moves.some(
    (move) => cleanId(move.id) === "batonpass" && move.pp > 0,
  );
  if (!hasBatonPass) return {};
  const teamAnalysis = simpleTeamAnalysis(state, sideIndex);
  const aceIndex = teamAnalysis.roles.findIndex(
    (role) => role?.aceProfile?.qualifies === true,
  );
  const ace = side.team[aceIndex];
  if (
    aceIndex < 0 ||
    ace === source ||
    !ace ||
    ace.fainted ||
    ace.hp <= 0
  ) {
    return JSON.parse(
      deriveBatonPassFactsJson(JSON.stringify({ available: true })),
    );
  }

  const passedSource = boostedPokemonForAi(source, additionalBoosts);
  const passedBoosts = { ...passedSource.boosts };
  const sweepBoostStats = ["attack", "specialAttack", "speed"];
  const defensiveBoostStats = ["defence", "specialDefence"];
  const transferableBoostStats = [
    ...sweepBoostStats,
    ...defensiveBoostStats,
  ];
  const availableSetupBoostStats = new Set(
    source.moves
      .filter(
        (move) =>
          move.pp > 0 &&
          !isMoveTemporarilyDisabled(source, move) &&
          cleanId(move.id) !== "batonpass",
      )
      .flatMap((move) => {
        const displayMove = aiDisplayMoveData(source, move);
        return transferableBoostStats.filter(
          (stat) =>
            Number(displayMove.selfBoosts?.[stat] ?? 0) > 0 &&
            Number(source.boosts?.[stat] ?? 0) < 6,
        );
      }),
  );
  const projectedAce = clone(ace);
  projectedAce.boosts = Object.fromEntries(
    BOOST_STATS.map((stat) => [stat, Number(passedBoosts[stat] ?? 0)]),
  );
  const livingTargets = state.sides[opponentSide].team.filter(
    (target) => !target.fainted && target.hp > 0,
  );
  const targets = livingTargets.map((target) => {
    const baseline = bestAiAttackProfile(
      state,
      sideIndex,
      ace,
      opponentSide,
      target,
    );
    const boosted = bestAiAttackProfile(
      state,
      sideIndex,
      projectedAce,
      opponentSide,
      target,
    );
    return {
      targetHp: target.hp,
      baselineDamage: baseline.expectedDamage,
      boostedDamage: boosted.expectedDamage,
    };
  });
  return JSON.parse(
    deriveBatonPassFactsJson(
      JSON.stringify({
        available: true,
        targetAvailable: true,
        targetSlot: aceIndex + 1,
        targetName: ace.name,
        targetAce: true,
        currentBoosts: source.boosts ?? {},
        passedBoosts,
        canRaiseSweepFurther: sweepBoostStats.some((stat) =>
          availableSetupBoostStats.has(stat),
        ),
        canRaiseDefenseFurther: defensiveBoostStats.some((stat) =>
          availableSetupBoostStats.has(stat),
        ),
        targets,
      }),
    ),
  );
}

function aiBatonPassSetupPlan(state, sideIndex, source) {
  const setupMoves = source.moves
    .filter(
      (move) =>
        move.pp > 0 &&
        !isMoveTemporarilyDisabled(source, move) &&
        cleanId(move.id) !== "batonpass",
    )
    .map((move) => aiDisplayMoveData(source, move))
    .map((move) => {
      const boosts = Object.fromEntries(
        BOOST_STATS.map((stat) => [
          stat,
          Math.max(0, Number(move.selfBoosts?.[stat] ?? 0)),
        ]),
      );
      const boostTotal = Object.values(boosts).reduce(
        (sum, amount) => sum + amount,
        0,
      );
      return { move, boosts, boostTotal };
    })
    .filter((entry) => entry.boostTotal > 0)
    .sort(
      (left, right) =>
        right.boostTotal - left.boostTotal ||
        cleanId(left.move.id).localeCompare(cleanId(right.move.id)),
    );
  const abilityId = activeAbility(source);
  const abilityBoosts =
    abilityId === "speedboost"
      ? { speed: 1 }
      : abilityId === "moody"
        ? { attack: 1, speed: 1 }
        : null;
  const selected =
    setupMoves[0] ??
    (abilityBoosts
      ? {
          move: null,
          boosts: abilityBoosts,
          boostTotal: Object.values(abilityBoosts).reduce(
            (sum, amount) => sum + amount,
            0,
          ),
        }
      : null);
  if (!selected) return {};
  return {
    setupMoveId: selected.move?.id ?? "",
    setupAbility: selected.move ? "" : abilityId,
    setupBoosts: selected.boosts,
    setupBoostTotal: selected.boostTotal,
    ...aiBatonPassProfile(
      state,
      sideIndex,
      source,
      selected.boosts,
    ),
  };
}

function aiRoleAnalysisMember(pokemon) {
  const member = { ...pokemon };
  if (pokemon.baseStats ?? pokemon.baseStatsRaw ?? pokemon.roleStats) {
    member.stats = pokemon.baseStats ?? pokemon.baseStatsRaw ?? pokemon.roleStats;
  } else {
    delete member.stats;
  }
  return member;
}

function isAiSetupBoostMove(move) {
  const selfBoosts = move.selfBoosts ?? {};
  return ["attack", "specialAttack", "speed"].some(
    (stat) => Number(selfBoosts[stat] ?? 0) > 0,
  );
}

function aiOpponentSetupThreatProfile({
  state,
  sideIndex,
  defenderSide,
  attacker,
  defender,
  opponentRoleProfile,
  threatEntry = null,
}) {
  const setupMoves = defender.moves.filter(isAiSetupBoostMove);
  if (setupMoves.length === 0) {
    const setupThreatEvaluation = evaluateSetupThreat();
    return {
      opponentSetupMoveCount: 0,
      opponentSetupFirstTurnLikelihood: 0,
      opponentLikelyFirstTurnSetup: false,
      opponentSetupThreatTier: 0,
      opponentSetupSweepRisk: 0,
      opponentSetupAnswerCount: 0,
      opponentSetupPunishOptions: [],
      setupThreatEvaluation,
      oneMoreTurnUnmanageable: false,
    };
  }

  const punishOptions = [];
  const bestIncomingDamage = attacker.moves.reduce((best, move) => {
    const moveId = cleanId(move.id ?? move.name);
    if (
      [
        "haze",
        "clearsmog",
        "roar",
        "whirlwind",
        "dragontail",
        "circlethrow",
        "taunt",
        "encore",
      ].includes(moveId) ||
      ["brn", "par", "slp"].includes(cleanId(move.status)) ||
      (move.secondaries ?? []).some(
        (secondary) =>
          ["brn", "par", "slp"].includes(cleanId(secondary.status)) &&
          Number(secondary.chance ?? 100) >= 60,
      )
    ) {
      punishOptions.push(moveId);
    }
    if (move.category === "Status" || move.power <= 0) return best;
    const displayMove = aiDisplayMoveData(attacker, move);
    const accuracy = expectedAccuracyFraction(
      attacker,
      defender,
      displayMove,
      state,
    );
    const damage =
      aiExpectedMoveDamage(
        attacker,
        defender,
        displayMove,
        state,
        sideIndex,
        defenderSide,
      ).expectedDamage * accuracy;
    if (damage >= defender.hp && accuracy >= 0.8) {
      punishOptions.push(moveId);
    }
    return Math.max(best, damage);
  }, 0);
  const damageRatio =
    defender.hp > 0 ? bestIncomingDamage / defender.hp : Number.POSITIVE_INFINITY;
  const hpPercent = defender.stats.hp > 0 ? defender.hp / defender.stats.hp : 0;
  const setupRoleScore =
    opponentRoleProfile?.roles?.find((entry) => entry.role === "setupSweeper")?.score ?? 0;
  const aceQualified = opponentRoleProfile?.aceProfile?.qualifies === true;
  const turn = Math.max(1, Number(state.turn ?? 0) + 1);

  const likelihood = evaluateSetupLikelihood({
    turn,
    immediateDamageRatio: damageRatio,
    opponentHpPercent: hpPercent,
    opponentRoleScore: setupRoleScore,
    opponentAce: aceQualified,
  });
  const setupThreatEvaluation = evaluateSetupThreat({
    setupMoves,
    setupLikelihood: likelihood,
    opponentCurrentBoosts: offensiveBoostTotal(defender),
    opponentRoleScore: setupRoleScore,
    opponentAce: aceQualified,
    opponentHpPercent: hpPercent,
    immediateDamageRatio: damageRatio,
    counters: threatEntry?.counters ?? [],
    softChecks: threatEntry?.softChecks ?? [],
    revengeKillers: threatEntry?.revengeKillers ?? [],
    punishOptions,
  });

  return {
    opponentSetupMoveCount: setupMoves.length,
    opponentSetupMoveIds: setupMoves.map((move) => cleanId(move.id ?? move.name)),
    opponentSetupFirstTurnLikelihood: likelihood,
    opponentLikelyFirstTurnSetup: likelihood >= 0.65,
    opponentSetupThreatTier: setupThreatEvaluation.riskTier,
    opponentSetupPunishUrgency: Math.round((likelihood * Math.max(0, 1 - damageRatio)) * 100) / 100,
    opponentSetupSweepRisk: setupThreatEvaluation.sweepRiskAfterSetup,
    opponentSetupAnswerCount:
      setupThreatEvaluation.availableAnswersAfterSetup.estimatedTotal,
    opponentSetupPunishOptions: setupThreatEvaluation.punishOptions,
    setupThreatEvaluation,
    oneMoreTurnUnmanageable:
      setupThreatEvaluation.oneMoreTurnUnmanageable,
  };
}

function aiDisplayMoveData(pokemon, move, dynamaxMode = "") {
  const abilityMove = abilityModifiedMove(pokemon, move);
  if (pokemon.dynamaxTurns <= 0 && !dynamaxMode) {
    return {
      ...abilityMove,
      priority: movePriorityForPokemon(pokemon, abilityMove),
    };
  }
  const maxMovePokemon = dynamaxMode
    ? { ...pokemon, dynamaxTurns: 3, dynamaxMode }
    : pokemon;
  const maxMove = resolveNativeMaxMove(maxMovePokemon, abilityMove);
  const isStatus = abilityMove.category === "Status";
  return {
    ...abilityMove,
    id: maxMove.id,
    name: maxMove.name,
    accuracy: true,
    priority: nativeMaxMovePriority(maxMovePokemon, abilityMove),
    power: isStatus ? 0 : Math.max(90, Math.min(150, abilityMove.power * 1.35)),
    target: isStatus ? "self" : abilityMove.target,
    status: "",
    selfStatus: "",
    volatileStatus: maxMove.volatileStatus ?? "",
    bypassProtect: maxMove.bypassProtect === true,
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

function aiStatusControlValue(move) {
  if (!move || move.category !== "Status") return 0;
  const moveId = cleanId(move.id);
  const positiveSelfBoosts = Object.values(move.selfBoosts ?? {}).reduce(
    (sum, value) => sum + Math.max(0, Number(value ?? 0)),
    0,
  );
  if (positiveSelfBoosts > 0) {
    return Math.min(1, 0.55 + positiveSelfBoosts * 0.12);
  }
  if (AI_RECOVERY_MOVES.has(moveId) || moveId === "batonpass") return 1;
  if (AI_HAZARD_MOVES.has(moveId)) return 0.82;
  if (AI_PROTECTIVE_MOVES.has(moveId) || moveId === "substitute") return 0.68;
  if (AI_STATUS_CONTROL_MOVES.has(moveId)) return 0.72;
  if (
    move.status ||
    move.volatileStatus ||
    move.sideCondition ||
    move.pseudoWeather ||
    move.weather ||
    move.terrain
  ) {
    return 0.6;
  }
  return 0.4;
}

function automaticMoveCandidates(
  state,
  sideIndex,
  strategy = "balanced",
  difficulty = "standard",
  dynamaxMode = "",
  projectionOptions = {},
) {
  const pokemon = activePokemon(state, sideIndex);
  const defenderSide = sideIndex === 0 ? 1 : 0;
  const defender = activePokemon(state, defenderSide);
  const exactOpponentKnowledge = state.aiKnowledge?.[sideIndex] ?? null;
  const exactOpponentCommand =
    exactOpponentKnowledge?.opponentCommand ?? null;
  const exactOpponentMove = exactOpponentKnowledge?.opponentMove ?? null;
  const opponentHazards = Object.fromEntries(
    ["stealthrock", "spikes", "toxicspikes", "stickyweb"].map((id) => [
      id,
      Number(state.sides[defenderSide]?.conditions?.[id]?.layers ?? 0),
    ]),
  );
  const livingOpponents = state.sides[defenderSide].team.filter(
    (member) => !member.fainted && member.hp > 0,
  ).length;
  const revealedSetupResetProfiles = state.sides[defenderSide].team.flatMap(
    (member, memberIndex) => {
      if (member.fainted || member.hp <= 0) return [];
      const revealedMoves = new Set(
        (member.moveHistory ?? []).map((moveId) => cleanId(moveId)),
      );
      if (member.lastMoveSucceeded === true) {
        revealedMoves.add(cleanId(member.lastMove?.id));
      }
      return member.moves
        .filter(
          (move) =>
            move.pp > 0 &&
            revealedMoves.has(cleanId(move.id)) &&
            AI_REVEALED_SETUP_RESET_MOVES.has(cleanId(move.id)),
        )
        .map((move) => ({
          id: cleanId(move.id),
          active: memberIndex === state.sides[defenderSide].active,
        }));
    },
  );
  const opponentRevealedSetupResetMoveIds = [
    ...new Set(revealedSetupResetProfiles.map((profile) => profile.id)),
  ];
  const opponentActiveRevealedSetupResetMoveIds = [
    ...new Set(
      revealedSetupResetProfiles
        .filter((profile) => profile.active)
        .map((profile) => profile.id),
    ),
  ];
  const threatTarget = dynamaxMode
    ? {
        ...pokemon,
        hp: pokemon.hp * 2,
        stats: { ...pokemon.stats, hp: pokemon.stats.hp * 2 },
      }
    : pokemon;
  const opponentAttackThreats = defender.moves
    .filter(
      (move) =>
        move.pp > 0 &&
        !isMoveTemporarilyDisabled(defender, move),
    )
    .map((move) => {
      const displayMove = aiDisplayMoveData(defender, move);
      if (displayMove.category === "Status") return null;
      const accuracy = expectedAccuracyFraction(
        defender,
        threatTarget,
        displayMove,
        state,
      );
      const estimate = aiExpectedMoveDamage(
        defender,
        threatTarget,
        displayMove,
        state,
        defenderSide,
        sideIndex,
      );
      const outcome = aiDamageOutcomeProfile(
        defender,
        threatTarget,
        displayMove,
        estimate.range,
      );
      return {
        moveId: displayMove.id,
        priority: Number(displayMove.priority ?? 0),
        expectedDamage: estimate.expectedDamage * accuracy,
        knockoutProbability:
          aiDamageThresholdChance(
            outcome.effectiveMinimum,
            outcome.effectiveMaximum,
            threatTarget.hp,
          ) * accuracy,
      };
    })
    .filter(Boolean);
  const opponentConditionalPriorityThreat = opponentAttackThreats
    .filter((threat) =>
      ["suckerpunch", "thunderclap"].includes(cleanId(threat.moveId)),
    )
    .sort(
      (left, right) =>
        right.knockoutProbability - left.knockoutProbability ||
        right.expectedDamage - left.expectedDamage,
    )[0] ?? null;
  const currentAttackPressure = pokemon.moves.reduce(
    (best, move) => {
      if (move.pp <= 0 || isMoveTemporarilyDisabled(pokemon, move)) {
        return best;
      }
      const displayMove = aiDisplayMoveData(pokemon, move, dynamaxMode);
      if (displayMove.category === "Status") return best;
      const accuracy = expectedAccuracyFraction(
        threatTarget,
        defender,
        displayMove,
        state,
      );
      const estimate = aiExpectedMoveDamage(
        threatTarget,
        defender,
        displayMove,
        state,
        sideIndex,
        defenderSide,
      );
      const outcome = aiDamageOutcomeProfile(
        threatTarget,
        defender,
        displayMove,
        estimate.range,
      );
      const knockoutProbability =
        aiDamageThresholdChance(
          outcome.effectiveMinimum,
          outcome.effectiveMaximum,
          defender.hp,
        ) * accuracy;
      const expectedDamage = estimate.expectedDamage * accuracy;
      return knockoutProbability > best.knockoutProbability ||
        (knockoutProbability === best.knockoutProbability &&
          expectedDamage > best.expectedDamage)
        ? {
            moveId: displayMove.id,
            expectedDamage,
            knockoutProbability,
          }
        : best;
    },
    {
      moveId: "",
      expectedDamage: 0,
      knockoutProbability: 0,
    },
  );
  const conditionalPriorityFailureCount = state.events.filter(
    (event) =>
      event.type === "move_failed" &&
      event.side === defenderSide &&
      event.pokemon === defender.name &&
      ["suckerpunch", "thunderclap"].includes(cleanId(event.move)) &&
      Number(event.turn) >= Math.max(1, state.turn - 2),
  ).length;
  const trickRoomForPrediction =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  const defenderNaturallyActsFirst = trickRoomForPrediction
    ? effectiveSpeed(defender, state, defenderSide) <
      effectiveSpeed(threatTarget, state, sideIndex)
    : effectiveSpeed(defender, state, defenderSide) >
      effectiveSpeed(threatTarget, state, sideIndex);
  let opponentConditionalPriorityLikelihood = 0;
  if (opponentConditionalPriorityThreat) {
    opponentConditionalPriorityLikelihood = 0.18;
    if (!defenderNaturallyActsFirst) {
      opponentConditionalPriorityLikelihood += 0.22;
    }
    if (currentAttackPressure.knockoutProbability >= 0.75) {
      opponentConditionalPriorityLikelihood += 0.25;
    } else if (currentAttackPressure.knockoutProbability > 0) {
      opponentConditionalPriorityLikelihood += 0.12;
    }
    if (opponentConditionalPriorityThreat.knockoutProbability >= 0.75) {
      opponentConditionalPriorityLikelihood += 0.18;
    } else if (opponentConditionalPriorityThreat.knockoutProbability > 0) {
      opponentConditionalPriorityLikelihood += 0.08;
    }
    const lastMove = pokemon.moves.find(
      (move) => cleanId(move.id) === cleanId(pokemon.lastMove?.id),
    );
    if (pokemon.lastMoveSucceeded === true && lastMove?.category !== "Status") {
      opponentConditionalPriorityLikelihood += 0.07;
    }
    opponentConditionalPriorityLikelihood -=
      Math.min(0.3, conditionalPriorityFailureCount * 0.12);
    opponentConditionalPriorityLikelihood = Math.max(
      0.08,
      Math.min(
        0.85,
        Math.round(opponentConditionalPriorityLikelihood * 100) / 100,
      ),
    );
  }
  const opponentLastMove = defender.moves.find(
    (move) =>
      cleanId(move.id) === cleanId(defender.lastMove?.id) &&
      move.pp > 0 &&
      !isMoveTemporarilyDisabled(defender, move),
  );
  const opponentLastDisplayMove = opponentLastMove
    ? aiDisplayMoveData(defender, opponentLastMove)
    : null;
  const opponentDisruptionMoves = Object.fromEntries(
    ["taunt", "encore"].map((id) => {
      const move = defender.moves.find(
        (candidate) =>
          cleanId(candidate.id) === id &&
          candidate.pp > 0 &&
          !isMoveTemporarilyDisabled(defender, candidate),
      );
      return [id, move ? aiDisplayMoveData(defender, move) : null];
    }),
  );
  const recentOpponentMoveId = cleanId(opponentLastDisplayMove?.id);
  const ownLastMove = pokemon.moves.find(
    (move) => cleanId(move.id) === cleanId(pokemon.lastMove?.id),
  );
  const ownLastMoveWasStatus =
    pokemon.lastMoveSucceeded === true && ownLastMove?.category === "Status";
  const disruptionUseLikelihood = (id) => {
    if (!opponentDisruptionMoves[id]) return 0;
    let likelihood = id === "taunt" ? 0.24 : 0.18;
    if (recentOpponentMoveId === id) likelihood += 0.2;
    if (id === "encore" && ownLastMoveWasStatus) likelihood += 0.22;
    return Math.max(0, Math.min(0.72, likelihood));
  };
  const opponentTauntLikelihood = disruptionUseLikelihood("taunt");
  const opponentEncoreLikelihood = disruptionUseLikelihood("encore");
  const hasStatusControlMove = pokemon.moves.some((move) =>
    ["taunt", "encore"].includes(cleanId(move.id)),
  );
  const opponentUsableMoves = hasStatusControlMove
    ? defender.moves.filter(
        (move) => move.pp > 0 && !isMoveTemporarilyDisabled(defender, move),
      )
    : [];
  const opponentStatusMoveProfiles = opponentUsableMoves
    .filter((move) => move.category === "Status")
    .map((move) => ({
      id: cleanId(move.id),
      value: aiStatusControlValue(move),
    }));
  const opponentStatusMoveRatio =
    opponentUsableMoves.length > 0
      ? opponentStatusMoveProfiles.length /
        opponentUsableMoves.length
      : 0;
  const opponentStatusControlValue =
    opponentStatusMoveProfiles.length > 0
      ? opponentStatusMoveProfiles.reduce(
          (sum, profile) => sum + profile.value,
          0,
        ) / opponentStatusMoveProfiles.length
      : 0;
  const conditionalPriorityFailureTurns = new Set(
    state.events
      .filter(
        (event) =>
          event.type === "move_failed" &&
          event.side === sideIndex &&
          event.pokemon === pokemon.name &&
          ["suckerpunch", "thunderclap"].includes(cleanId(event.move)),
      )
      .map((event) => Number(event.turn)),
  );
  let conditionalPriorityFailureStreak = 0;
  for (
    let turn = state.turn;
    turn > 0 && conditionalPriorityFailureTurns.has(turn);
    turn -= 1
  ) {
    conditionalPriorityFailureStreak += 1;
  }
  const conditionalPriorityAdaptChance =
    conditionalPriorityFailureStreak >= 4
      ? 0.95
      : [0, 0.4, 0.68, 0.86][conditionalPriorityFailureStreak] ?? 0;
  const conditionalPriorityAdaptRoll =
    createAiRng(
      state.seed,
      sideIndex,
      state.turn * 131 + conditionalPriorityFailureStreak * 17 + 43,
    ).nextIndex(10_000) / 10_000;
  const conditionalPriorityAdapted =
    conditionalPriorityFailureStreak > 0 &&
    conditionalPriorityAdaptRoll < conditionalPriorityAdaptChance;
  const conditionalPriorityAdaptPenalty = conditionalPriorityAdapted
    ? -2000
    : -Math.min(220, 40 + conditionalPriorityFailureStreak * 35);
  const opponentBestDamage = opponentAttackThreats.reduce(
    (best, threat) =>
      Math.max(
        best,
        Number.isFinite(Number(threat.expectedDamage))
          ? Number(threat.expectedDamage)
          : 0,
      ),
    0,
  );
  const incomingDamageRatio =
    threatTarget.hp > 0 ? opponentBestDamage / threatTarget.hp : 1;
  const opponentBenchAttackDamageRatio = hasStatusControlMove
    ? state.sides[defenderSide].team.reduce((worstRatio, opponent, opponentIndex) => {
    if (
      opponentIndex === state.sides[defenderSide].active ||
      opponent.fainted ||
      opponent.hp <= 0
    ) {
      return worstRatio;
    }
    const expectedDamage = opponent.moves.reduce((worstDamage, move) => {
      if (move.pp <= 0 || isMoveTemporarilyDisabled(opponent, move)) {
        return worstDamage;
      }
      const opponentMove = aiDisplayMoveData(opponent, move);
      if (
        opponentMove.category === "Status" ||
        Number(opponentMove.power ?? 0) <= 0
      ) {
        return worstDamage;
      }
      const accuracy = expectedAccuracyFraction(
        opponent,
        threatTarget,
        opponentMove,
        state,
      );
      const estimate = aiExpectedMoveDamage(
        opponent,
        threatTarget,
        opponentMove,
        state,
        defenderSide,
        sideIndex,
      );
      return Math.max(worstDamage, estimate.expectedDamage * accuracy);
    }, 0);
    return Math.max(
      worstRatio,
      threatTarget.hp > 0 ? expectedDamage / threatTarget.hp : 1,
    );
      }, 0)
    : 0;
  const survivalTurns = estimatedSurvivalTurns(pokemon, opponentBestDamage);
  const switchPressure = activeSwitchPressure(pokemon);
  const activeRoleProfile = analyzeTeamProfile([
    {
      ...aiRoleAnalysisMember(pokemon),
      moves: pokemon.moves.filter((move) => !SELF_DESTRUCT_MOVES.has(cleanId(move.id))),
    },
  ]).roles[0];
  const activeRoleScore = activeRoleProfile?.roles[0]?.score ?? 0;
  const oneTurnSearchWeight = aiOneTurnSearchWeight(difficulty);
  const gimmickResourceCost = Math.max(
    0,
    Number(projectionOptions.gimmickResourceCost ?? 0),
  );
  const needsSacrificeAnalysis = pokemon.moves.some((move) =>
    SELF_DESTRUCT_MOVES.has(cleanId(move.id)),
  );
  const opponentHasSetupMove = defender.moves.some(isAiSetupBoostMove);
  const activeThreatCounterMap =
    needsSacrificeAnalysis || opponentHasSetupMove || oneTurnSearchWeight > 0
    ? simpleThreatCounterMap(state, sideIndex)
    : null;
  const activePreservationProfile = activeThreatCounterMap
    ? activeThreatCounterMap.mustPreserveResources.find(
        (resource) => resource.slot === state.sides[sideIndex].active + 1,
      )
    : null;
  const activeRoleProgress = needsSacrificeAnalysis
    ? simpleTeamRoleProgress(
        state,
        sideIndex,
        null,
        activeThreatCounterMap,
      )[state.sides[sideIndex].active]
    : null;
  const opponentRoleProfile = analyzeTeamProfile([aiRoleAnalysisMember(defender)]).roles[0];
  const currentSetupThreatEntry = activeThreatCounterMap?.threats.find(
    (threat) =>
      threat.enemySlot === state.sides[defenderSide].active + 1,
  );
  const opponentSetupThreat = aiOpponentSetupThreatProfile({
    state,
    sideIndex,
    defenderSide,
    attacker: pokemon,
    defender,
    opponentRoleProfile,
    threatEntry: currentSetupThreatEntry,
  });
  const oneTurnStateBefore =
    oneTurnSearchWeight > 0
      ? simpleBattleStateValueSnapshot(
          state,
          sideIndex,
          null,
          activeThreatCounterMap,
        )
      : null;
  const roomContext = trickRoomContext(
    state,
    sideIndex,
    defenderSide,
    pokemon,
    defender,
    incomingDamageRatio,
  );
  const knockoutBoosts = knockoutAbilityBoosts(activeAbility(pokemon), pokemon);
  return pokemon.moves
    .map((move, index) => {
      let displayMove = aiDisplayMoveData(pokemon, move, dynamaxMode);
      const movePriority = Number(displayMove.priority ?? 0);
      const attackerSpeed = effectiveSpeed(pokemon, state, sideIndex);
      const defenderSpeed = effectiveSpeed(defender, state, defenderSide);
      const actionOrderTrickRoomActive =
        Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
      const opponentLastMovePriority = Number(
        opponentLastDisplayMove?.priority ?? 0,
      );
      const opponentLastMoveActsFirst =
        opponentLastMovePriority > movePriority ||
        (opponentLastMovePriority === movePriority &&
          (actionOrderTrickRoomActive
            ? defenderSpeed < attackerSpeed
            : defenderSpeed > attackerSpeed));
      const disruptionActsBeforeProbability = (disruptionMove) => {
        if (!disruptionMove) return 0;
        const disruptionPriority = Number(
          movePriorityForPokemon(defender, disruptionMove),
        );
        if (disruptionPriority > movePriority) return 1;
        if (disruptionPriority < movePriority) return 0;
        const speedComparison = actionOrderTrickRoomActive
          ? attackerSpeed - defenderSpeed
          : defenderSpeed - attackerSpeed;
        return speedComparison > 0 ? 1 : speedComparison === 0 ? 0.5 : 0;
      };
      const tauntActsBeforeProbability = disruptionActsBeforeProbability(
        opponentDisruptionMoves.taunt,
      );
      const encoreActsBeforeProbability = disruptionActsBeforeProbability(
        opponentDisruptionMoves.encore,
      );
      const statusMoveDisruptionBaseProfile =
        displayMove.category === "Status"
          ? {
              opponentHasTaunt: Boolean(opponentDisruptionMoves.taunt),
              opponentHasEncore: Boolean(opponentDisruptionMoves.encore),
              opponentTauntRisk:
                opponentTauntLikelihood *
                (0.45 + tauntActsBeforeProbability * 0.55),
              opponentEncoreRisk:
                opponentEncoreLikelihood *
                (ownLastMoveWasStatus
                  ? 0.65 + encoreActsBeforeProbability * 0.35
                  : 0.4 + encoreActsBeforeProbability * 0.25),
              opponentDisruptionActsBeforeProbability: Math.max(
                tauntActsBeforeProbability,
                encoreActsBeforeProbability,
              ),
              exactTauntRisk:
                cleanId(exactOpponentMove?.id) === "taunt"
                  ? tauntActsBeforeProbability > 0
                    ? 1
                    : displayMove.selfSwitch
                      ? 0
                      : 0.65
                  : 0,
              exactEncoreRisk:
                cleanId(exactOpponentMove?.id) === "encore"
                  ? displayMove.selfSwitch
                    ? encoreActsBeforeProbability > 0
                      ? 0.15
                      : 0
                    : 0.85
                  : 0,
            }
          : {};
      const upperHandEligibleThreats =
        cleanId(displayMove.id) === "upperhand"
          ? opponentAttackThreats.filter((threat) => {
              if (threat.priority <= 0) return false;
              if (movePriority > threat.priority) return true;
              if (movePriority < threat.priority) return false;
              return actionOrderTrickRoomActive
                ? attackerSpeed <= defenderSpeed
                : attackerSpeed >= defenderSpeed;
            })
          : [];
      const exactUpperHandTargetIsValid =
        Number.isInteger(exactOpponentCommand?.move) &&
        exactOpponentMove?.category !== "Status" &&
        Number(exactOpponentMove?.power ?? 0) > 0 &&
        Number(exactOpponentMove?.priority ?? 0) > 0;
      const exactOpponentPriority = Number(exactOpponentMove?.priority ?? 0);
      const upperHandSpeedComparison = actionOrderTrickRoomActive
        ? defenderSpeed - attackerSpeed
        : attackerSpeed - defenderSpeed;
      const exactUpperHandActsFirstProbability =
        movePriority > exactOpponentPriority
          ? 1
          : movePriority < exactOpponentPriority
            ? 0
            : upperHandSpeedComparison > 0
              ? 1
              : upperHandSpeedComparison === 0
                ? 0.5
                : 0;
      const upperHandExactOutcome =
        cleanId(displayMove.id) === "upperhand" && exactOpponentCommand
          ? !exactUpperHandTargetIsValid ||
            exactUpperHandActsFirstProbability <= 0
            ? "failure"
            : exactUpperHandActsFirstProbability >= 1
              ? "success"
              : "uncertain"
          : "";
      const upperHandPriorityPressure = upperHandEligibleThreats.reduce(
        (best, threat) => Math.max(best, threat.expectedDamage),
        0,
      );
      const upperHandTotalAttackPressure = opponentAttackThreats.reduce(
        (sum, threat) => sum + Math.max(0, threat.expectedDamage),
        0,
      );
      const upperHandSuccessProbability =
        upperHandExactOutcome === "success"
          ? 1
          : upperHandExactOutcome === "failure"
            ? 0
            : upperHandExactOutcome === "uncertain"
              ? exactUpperHandActsFirstProbability
            : upperHandEligibleThreats.length > 0
              ? Math.max(
                  0.15,
                  Math.min(
                    0.8,
                    upperHandTotalAttackPressure > 0
                      ? (upperHandPriorityPressure /
                          upperHandTotalAttackPressure) *
                          1.4
                      : 0.25,
                  ),
                )
              : 0;
      const exactConditionalPriorityFailure =
        ["suckerpunch", "thunderclap"].includes(cleanId(displayMove.id)) &&
        (Number.isInteger(exactOpponentCommand?.switch) ||
          exactOpponentMove?.category === "Status");
      const conditionalPriorityRepeatFailure =
        ["suckerpunch", "thunderclap"].includes(cleanId(displayMove.id)) &&
        (exactConditionalPriorityFailure ||
          (conditionalPriorityFailureStreak > 0 &&
            (opponentLastDisplayMove?.category === "Status" ||
              (defender.lastMoveSucceeded === true &&
                Number(opponentLastDisplayMove?.power ?? 0) > 0 &&
                opponentLastMoveActsFirst))));
      const conditionalPriorityRepeatCause =
        Number.isInteger(exactOpponentCommand?.switch)
          ? "switch"
          : exactOpponentMove?.category === "Status" ||
              opponentLastDisplayMove?.category === "Status"
          ? "status_move"
          : conditionalPriorityRepeatFailure
            ? "already_acted"
            : "";
      const incomingBeforeActionThreat = opponentAttackThreats.reduce(
        (worst, threat) => {
          let actionBeforeThreatProbability = 0;
          if (movePriority > threat.priority) {
            actionBeforeThreatProbability = 1;
          } else if (movePriority === threat.priority) {
            const speedComparison = actionOrderTrickRoomActive
              ? defenderSpeed - attackerSpeed
              : attackerSpeed - defenderSpeed;
            actionBeforeThreatProbability =
              speedComparison > 0 ? 1 : speedComparison === 0 ? 0.5 : 0;
          }
          const knockoutBeforeActionProbability =
            threat.knockoutProbability * (1 - actionBeforeThreatProbability);
          if (
            knockoutBeforeActionProbability >
              worst.knockoutBeforeActionProbability ||
            (knockoutBeforeActionProbability ===
              worst.knockoutBeforeActionProbability &&
              threat.expectedDamage > worst.expectedDamage)
          ) {
            return {
              ...threat,
              actionBeforeThreatProbability,
              knockoutBeforeActionProbability,
            };
          }
          return worst;
        },
        {
          moveId: "",
          priority: 0,
          expectedDamage: 0,
          knockoutProbability: 0,
          actionBeforeThreatProbability: 1,
          knockoutBeforeActionProbability: 0,
        },
      );
      const damageEstimate = aiExpectedMoveDamage(
        pokemon,
        defender,
        displayMove,
        state,
        sideIndex,
        defenderSide,
      );
      displayMove = damageEstimate.move ?? displayMove;
      const range = damageEstimate.range;
      const damageOutcome = aiDamageOutcomeProfile(
        pokemon,
        defender,
        displayMove,
        range,
      );
      const accuracy = expectedAccuracyFraction(
        pokemon,
        defender,
        displayMove,
        state,
      );
      const statusWeights = {
        slp: 48,
        tox: 42,
        brn: 34,
        par: 30,
        psn: 24,
        frz: 55,
      };
      const majorStatusValue =
        displayMove.status &&
        canReceiveStatus(
          defender,
          displayMove.status,
          state,
          defenderSide,
          sideIndex,
        )
          ? statusWeights[displayMove.status] ?? 18
          : 0;
      const setupPokemon = boostedPokemonForAi(
        pokemon,
        displayMove.selfBoosts,
      );
      const positiveSelfBoostCount = Object.values(
        displayMove.selfBoosts,
      ).reduce(
        (sum, amount) => sum + (Number(amount ?? 0) > 0 ? 1 : 0),
        0,
      );
      const effectiveSelfBoostTotal = BOOST_STATS.reduce(
        (sum, stat) =>
          sum +
          Math.max(
            0,
            Number(setupPokemon.boosts?.[stat] ?? 0) -
              Number(pokemon.boosts?.[stat] ?? 0),
          ),
        0,
      );
      const selfBoostValue = effectiveSelfBoostTotal * 15;
      const selfDropTotal = Object.values(displayMove.selfBoosts).reduce(
        (sum, amount) => sum + Math.max(0, -Number(amount ?? 0)),
        0,
      );
      const selfDropValue = Object.values(displayMove.selfBoosts).reduce(
        (sum, amount) => sum + Math.min(0, amount) * 15,
        0,
      );
      const targetDropValue =
        cleanId(defender.item) === "clearamulet"
          ? 0
          : Object.values(displayMove.boosts).reduce(
              (sum, amount) => sum + Math.max(0, -amount) * 13,
              0,
            );
      const isRestMove = cleanId(displayMove.id) === "rest";
      const recoveryFacts = JSON.parse(
        deriveRecoveryFactsJson(
          JSON.stringify({
            currentHp: pokemon.hp,
            maxHp: pokemon.stats.hp,
            healFraction: displayMove.heal
              ? Number(displayMove.heal[0] ?? 0) / Math.max(1, Number(displayMove.heal[1] ?? 1))
              : 0,
            fullHeal: isRestMove,
            exposureTurns: isRestMove ? 3 : 1,
            opponentBestDamage,
          }),
        ),
      );
      const {
        recoveryAmount,
        recoveryValue,
        recoveryExposureTurns,
        recoveryExpectedIncomingDamage,
        recoveryNetHpChange,
      } = recoveryFacts;
      const secondaryValue = displayMove.secondaries.reduce((sum, effect) => {
        if (secondaryEffectsBlocked(pokemon, defender, displayMove)) return sum;
        const chance = secondaryEffectChance(pokemon, effect) / 100;
        const status =
          effect.status &&
          canReceiveStatus(
            defender,
            effect.status,
            state,
            defenderSide,
            sideIndex,
          )
            ? statusWeights[effect.status] ?? 18
            : 0;
        const volatile =
          cleanId(effect.volatileStatus) === "flinch" ? 18 : 0;
        const boosts =
          Object.values(effect.selfBoosts ?? {}).reduce(
            (value, amount) => value + Math.max(0, amount) * 12,
            0,
          ) +
          Object.values(effect.boosts ?? {}).reduce(
            (value, amount) => value + Math.max(0, -amount) * 10,
            0,
          );
        return sum + (status + volatile + boosts) * chance;
      }, 0);
      const statusResidualCandidates = [
        ...(displayMove.status &&
        canReceiveStatus(defender, displayMove.status, state, defenderSide, sideIndex)
          ? [{ status: displayMove.status, chance: 100 }]
          : []),
        ...displayMove.secondaries
          .filter(
            (effect) =>
              !secondaryEffectsBlocked(pokemon, defender, displayMove) &&
              effect.status &&
              canReceiveStatus(
                defender,
                effect.status,
                state,
                defenderSide,
                sideIndex,
              ),
          )
          .map((effect) => ({
            status: effect.status,
            chance: secondaryEffectChance(pokemon, effect),
          })),
      ];
      const statusBlocked =
        Boolean(displayMove.status) &&
        !canReceiveStatus(defender, displayMove.status, state, defenderSide, sideIndex);
      const tacticalValue =
        majorStatusValue +
        selfBoostValue +
        selfDropValue +
        targetDropValue +
        recoveryValue +
        secondaryValue;
      const uncappedExpectedDamage =
        (damageOutcome.singleHitSurvivalBlocked || damageOutcome.disguiseBlocked
          ? (damageOutcome.effectiveMinimum + damageOutcome.effectiveMaximum) / 2
          : damageEstimate.expectedDamage) * accuracy;
      const expectedDamage = Math.min(defender.hp, uncappedExpectedDamage);
      const expectedRecoilDamage =
        displayMove.recoil && activeAbility(pokemon) !== "magicguard"
        ? Math.min(
            pokemon.hp,
            fractionAmount(
              Math.min(defender.hp, damageEstimate.expectedDamage),
              displayMove.recoil,
            ),
          )
        : 0;
      const setupFollowup = aiSetupFollowupValue(
        pokemon,
        defender,
        displayMove,
        state,
        sideIndex,
        defenderSide,
      );
      const batonPassProfile = aiBatonPassProfile(
        state,
        sideIndex,
        pokemon,
        displayMove.selfBoosts,
      );
      const setupResidualDamage = aiEndTurnResidualDamage(pokemon, state);
      const setupIncomingThreat = defender.moves.reduce(
        (worst, opponentMove) => {
          const opponentDisplayMove = aiDisplayMoveData(defender, opponentMove);
          if (opponentDisplayMove.category === "Status") return worst;
          if (
            displayMove.category === "Status" &&
            ["suckerpunch", "thunderclap"].includes(
              cleanId(opponentDisplayMove.id),
            )
          ) {
            return worst;
          }
          const opponentAccuracy = expectedAccuracyFraction(
            defender,
            setupPokemon,
            opponentDisplayMove,
            state,
          );
          const estimate = aiExpectedMoveDamage(
            defender,
            setupPokemon,
            opponentDisplayMove,
            state,
            defenderSide,
            sideIndex,
          );
          const outcome = aiDamageOutcomeProfile(
            defender,
            setupPokemon,
            opponentDisplayMove,
            estimate.range,
          );
          const knockoutProbability =
            aiDamageThresholdChance(
              outcome.effectiveMinimum,
              outcome.effectiveMaximum,
              pokemon.hp - setupResidualDamage,
            ) * opponentAccuracy;
          const guardConsumptionProbability =
            outcome.singleHitSurvivalBlocked
              ? aiDamageThresholdChance(
                  outcome.totalMinimum,
                  outcome.totalMaximum,
                  pokemon.hp - setupResidualDamage,
                ) * opponentAccuracy
              : 0;
          const expectedDamage = estimate.expectedDamage * opponentAccuracy;
          const failureRisk = Math.max(
            knockoutProbability,
            guardConsumptionProbability,
          );
          const worstFailureRisk = Math.max(
            worst.knockoutProbability,
            worst.guardConsumptionProbability,
          );
          if (
            failureRisk > worstFailureRisk ||
            (failureRisk === worstFailureRisk &&
              expectedDamage > worst.expectedDamage)
          ) {
            return {
              expectedDamage,
              knockoutProbability,
              guardConsumptionProbability,
              moveId: opponentDisplayMove.id,
              priority: Number(opponentDisplayMove.priority ?? 0),
            };
          }
          return worst;
        },
        {
          expectedDamage: 0,
          knockoutProbability: setupResidualDamage >= pokemon.hp ? 1 : 0,
          guardConsumptionProbability: 0,
          moveId: "",
          priority: 0,
        },
      );
      const setupIncomingDamage = setupIncomingThreat.expectedDamage;
      const setupIncomingDamageRatioAfterBoost =
        pokemon.hp > 0 ? setupIncomingDamage / pokemon.hp : 1;
      const defensiveSetup =
        Number(displayMove.selfBoosts?.defence ?? 0) > 0 ||
        Number(displayMove.selfBoosts?.specialDefence ?? 0) > 0;
      const disruptionActiveDamageRatio =
        displayMove.category === "Status" && defensiveSetup
          ? Math.min(
              incomingDamageRatio,
              setupIncomingDamageRatioAfterBoost,
            )
          : incomingDamageRatio;
      const disruptionBenchSwitchDamageRatio =
        displayMove.category === "Status"
          ? state.sides[defenderSide].team.reduce(
              (worstRatio, opponent, opponentIndex) => {
                if (
                  opponentIndex === state.sides[defenderSide].active ||
                  opponent.fainted ||
                  opponent.hp <= 0
                ) {
                  return worstRatio;
                }
                const expectedDamage = opponent.moves.reduce(
                  (worstDamage, opponentMove) => {
                    if (opponentMove.pp <= 0) return worstDamage;
                    const opponentDisplayMove = aiDisplayMoveData(
                      opponent,
                      opponentMove,
                    );
                    if (
                      opponentDisplayMove.category === "Status" ||
                      Number(opponentDisplayMove.power ?? 0) <= 0
                    ) {
                      return worstDamage;
                    }
                    const estimate = aiExpectedMoveDamage(
                      opponent,
                      setupPokemon,
                      opponentDisplayMove,
                      state,
                      defenderSide,
                      sideIndex,
                    );
                    return Math.max(
                      worstDamage,
                      estimate.expectedDamage *
                        expectedAccuracyFraction(
                          opponent,
                          setupPokemon,
                          opponentDisplayMove,
                          state,
                        ),
                    );
                  },
                  0,
                );
                return Math.max(
                  worstRatio,
                  pokemon.hp > 0 ? expectedDamage / pokemon.hp : 1,
                );
              },
              0,
            )
          : 0;
      const disruptionIncomingDamageRatio = Math.max(
        disruptionActiveDamageRatio,
        disruptionBenchSwitchDamageRatio,
      );
      const disruptionThreeTurnDamageRatio =
        Math.max(
          disruptionActiveDamageRatio * 3,
          disruptionBenchSwitchDamageRatio * 2,
        );
      const statusMoveDisruptionProfile =
        displayMove.category === "Status"
          ? {
              ...statusMoveDisruptionBaseProfile,
              disruptionIncomingDamageRatio,
              disruptionThreeTurnDamageRatio,
              disruptionBenchSwitchDamageRatio,
              disruptionBenchSwitchThreat:
                disruptionBenchSwitchDamageRatio >
                disruptionActiveDamageRatio,
              disruptionCanSurviveThreeTurns:
                disruptionThreeTurnDamageRatio < 1,
              disruptionDefensiveSetup: defensiveSetup,
              disruptionDefensiveDamageReduction: defensiveSetup
                ? Math.max(
                    0,
                    incomingDamageRatio -
                      setupIncomingDamageRatioAfterBoost,
                  )
                : 0,
              disruptionSwitchEscapeAvailable:
                !isPokemonTrapped(state, sideIndex, pokemon) &&
                state.sides[sideIndex].team.some(
                  (member, memberIndex) =>
                    memberIndex !== state.sides[sideIndex].active &&
                    !member.fainted &&
                    member.hp > 0,
                ),
            }
          : {};
      const statusControlMoveId = cleanId(displayMove.id);
      const exactOpponentMovePriority = Number(
        exactOpponentMove?.priority ?? 0,
      );
      const exactOpponentActsBeforeProbability = !exactOpponentMove
        ? 0
        : exactOpponentMovePriority > movePriority
          ? 1
          : exactOpponentMovePriority < movePriority
            ? 0
            : actionOrderTrickRoomActive
              ? defenderSpeed < attackerSpeed
                ? 1
                : defenderSpeed === attackerSpeed
                  ? 0.5
                  : 0
              : defenderSpeed > attackerSpeed
                ? 1
                : defenderSpeed === attackerSpeed
                  ? 0.5
                  : 0;
      const exactEncoreTargetAvailable =
        statusControlMoveId === "encore" &&
        exactOpponentMove &&
        !Number.isInteger(exactOpponentCommand?.switch) &&
        exactOpponentActsBeforeProbability > 0;
      const encoreTargetMove = exactEncoreTargetAvailable
        ? exactOpponentMove
        : opponentLastDisplayMove;
      const encoreTargetMoveId = cleanId(encoreTargetMove?.id);
      const encoreTargetInvalid =
        !encoreTargetMoveId ||
        ["encore", "mimic", "sketch", "struggle"].includes(
          encoreTargetMoveId,
        );
      const encoreTargetThreat = opponentAttackThreats.find(
        (threat) => cleanId(threat.moveId) === encoreTargetMoveId,
      );
      const encoreTargetDamageRatio =
        threatTarget.hp > 0
          ? Number(encoreTargetThreat?.expectedDamage ?? 0) / threatTarget.hp
          : 1;
      const opponentCanEscapeStatusControl =
        livingOpponents > 1 &&
        !isPokemonTrapped(state, defenderSide, defender);
      const opponentSwitchHazardLayers =
        Number(opponentHazards.stealthrock ?? 0) +
        Number(opponentHazards.spikes ?? 0) +
        Number(opponentHazards.toxicspikes ?? 0) +
        Number(opponentHazards.stickyweb ?? 0);
      const statusControlActiveDamageRatio =
        statusControlMoveId === "encore" && !encoreTargetInvalid
          ? encoreTargetMove?.category === "Status"
            ? 0
            : encoreTargetDamageRatio
          : incomingDamageRatio;
      const statusControlThreeTurnDamageRatio = Math.max(
        statusControlActiveDamageRatio * 3,
        opponentCanEscapeStatusControl
          ? opponentBenchAttackDamageRatio * 2
          : 0,
      );
      const offensiveStatusControlProfile =
        displayMove.category === "Status" &&
        ["taunt", "encore"].includes(statusControlMoveId)
          ? {
              statusControlTargetStatusMoveCount:
                opponentStatusMoveProfiles.length,
              statusControlTargetStatusMoveRatio: opponentStatusMoveRatio,
              statusControlTargetValue: opponentStatusControlValue,
              statusControlThreeTurnDamageRatio,
              statusControlCanSurviveThreeTurns:
                statusControlThreeTurnDamageRatio < 1,
              statusControlOpponentCanSwitch:
                opponentCanEscapeStatusControl,
              statusControlSwitchHazardLayers:
                opponentSwitchHazardLayers,
              statusControlBenchDamageRatio:
                opponentBenchAttackDamageRatio,
              statusControlTargetAlreadyAffected:
                Boolean(defender.volatiles?.[statusControlMoveId]),
              tauntPreventsExactStatus:
                statusControlMoveId === "taunt" &&
                exactOpponentMove?.category === "Status" &&
                exactOpponentActsBeforeProbability < 1,
              tauntPreventionConfidence:
                statusControlMoveId === "taunt" &&
                exactOpponentMove?.category === "Status"
                  ? 1 - exactOpponentActsBeforeProbability
                  : 0,
              encoreTargetValid:
                statusControlMoveId === "encore" &&
                !encoreTargetInvalid,
              encoreTargetMoveId,
              encoreTargetIsStatus:
                encoreTargetMove?.category === "Status",
              encoreTargetStatusValue:
                encoreTargetMove?.category === "Status"
                  ? aiStatusControlValue(encoreTargetMove)
                  : 0,
              encoreTargetDamageRatio,
              encoreExactTargetConfidence: exactEncoreTargetAvailable
                ? exactOpponentActsBeforeProbability
                : 0,
            }
          : {};
      const setupFollowupPokemon = clone(setupPokemon);
      if (activeAbility(setupFollowupPokemon) === "speedboost") {
        setupFollowupPokemon.boosts.speed = Math.min(
          6,
          Number(setupFollowupPokemon.boosts.speed ?? 0) + 1,
        );
      }
      const setupFollowupMove = pokemon.moves.reduce((best, followupMove) => {
        const followupDisplayMove = aiDisplayMoveData(
          setupFollowupPokemon,
          followupMove,
        );
        if (
          followupDisplayMove.category === "Status" ||
          followupMove.pp <= 0
        ) {
          return best;
        }
        const followupDamage = aiExpectedMoveDamage(
          setupFollowupPokemon,
          defender,
          followupDisplayMove,
          state,
          sideIndex,
          defenderSide,
        ).expectedDamage;
        if (followupDamage <= 0) return best;
        const priority = Number(followupDisplayMove.priority ?? 0);
        return !best || priority > best.priority
          ? { priority, moveId: followupDisplayMove.id }
          : best;
      }, null);
      const projectedDefender = clone(defender);
      if (activeAbility(projectedDefender) === "speedboost") {
        projectedDefender.boosts.speed = Math.min(
          6,
          Number(projectedDefender.boosts.speed ?? 0) + 1,
        );
      }
      const followupPriority = Number(setupFollowupMove?.priority ?? 0);
      const threatPriority = Number(setupIncomingThreat.priority ?? 0);
      const trickRoomActive =
        Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 1;
      const setupFollowupActsBeforeThreat =
        Boolean(setupFollowupMove) &&
        (followupPriority > threatPriority ||
          (followupPriority === threatPriority &&
            (trickRoomActive
              ? effectiveSpeed(setupFollowupPokemon, state, sideIndex) <
                effectiveSpeed(projectedDefender, state, defenderSide)
              : effectiveSpeed(setupFollowupPokemon, state, sideIndex) >
                effectiveSpeed(projectedDefender, state, defenderSide))));
      const guardFollowupFailureProbability =
        setupFollowupActsBeforeThreat
          ? 0
          : setupIncomingThreat.guardConsumptionProbability;
      const setupFollowupSurvivalProbability = Math.max(
        0,
        1 -
          Math.max(
            setupIncomingThreat.knockoutProbability,
            guardFollowupFailureProbability,
          ),
      );
      const baseCandidate = {
        ...displayMove,
        willFail:
          isTruantLoafTurn(pokemon) ||
          isPrimordialSeaBlockedMove(state, displayMove) ||
          isDesolateLandBlockedMove(state, displayMove) ||
          isDampBlockedMove(state, displayMove) ||
          isAromaVeilBlockedMove(state, defenderSide, displayMove) ||
          isPranksterBlocked(pokemon, defender, displayMove) ||
          (movePriorityForPokemon(pokemon, displayMove) > 0 &&
            !["self", "allyside"].includes(cleanId(displayMove.target)) &&
            Boolean(priorityBlockingAbility(defender)) &&
            !ignoresDefenderAbility(pokemon)) ||
          (displayMove.category === "Status" &&
            Boolean(candidateHazardConditionId(displayMove)) &&
            candidateHazardLayerDelta({ ...displayMove, opponentHazards }) === 0),
        protectSuccessProbability: CONSECUTIVE_PROTECTION_MOVES.has(
          cleanId(displayMove.id),
        )
          ? 1 / 3 ** Math.max(0, Number(pokemon.protectCounter ?? 0))
          : 1,
        expectedDamage,
        tacticalValue,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        incomingDamageRatio,
        opponentThreateningMoveId: incomingBeforeActionThreat.moveId,
        opponentThreatPriority: incomingBeforeActionThreat.priority,
        opponentKnockoutProbability:
          incomingBeforeActionThreat.knockoutProbability,
        actionBeforeThreatProbability:
          incomingBeforeActionThreat.actionBeforeThreatProbability,
        opponentKnockoutBeforeActionProbability:
          incomingBeforeActionThreat.knockoutBeforeActionProbability,
        recoveryAmount,
        recoveryExposureTurns,
        recoveryExpectedIncomingDamage,
        recoveryNetHpChange,
        recoveryBeforeActionKoRisk:
          recoveryExposureTurns > 0
            ? incomingBeforeActionThreat.knockoutBeforeActionProbability
            : 0,
        conditionalPriorityRepeatFailure,
        conditionalPriorityRepeatCause,
        conditionalPriorityFailureStreak,
        conditionalPriorityAdaptChance,
        conditionalPriorityAdapted,
        conditionalPriorityAdaptPenalty,
        upperHandExactOutcome,
        upperHandSuccessProbability,
        upperHandEligiblePriorityMoves: upperHandEligibleThreats.map(
          (threat) => threat.moveId,
        ),
        exactOpponentMoveId: exactOpponentMove?.id ?? "",
        opponentLastMoveId: opponentLastDisplayMove?.id ?? "",
        opponentHp: defender.hp,
        opponentAbility: activeAbility(defender),
        opponentHazards,
        opponentVolatiles: defender.volatiles ?? {},
        opponentStatus: defender.status,
        opponentRevealedSetupResetMoveIds,
        opponentActiveRevealedSetupResetMoveIds,
        statusBlocked,
        statusResidualCandidates,
        livingOpponents,
        turn: state.turn + 1,
        expectedSurvivalTurns: survivalTurns,
        sustainTurnBonus: aiSustainTurnBonus(pokemon),
        saltCureResidualDamage: saltCureResidualDamage(defender),
        opponentMaxHp: defender.stats.hp,
        opponentPrimaryRole: opponentRoleProfile?.primaryRole ?? "support",
        opponentAceScore: opponentRoleProfile?.aceScore ?? 0,
        opponentAceQualified: opponentRoleProfile?.aceProfile?.qualifies === true,
        opponentBoosts: defender.boosts ?? {},
        opponentPositiveBoosts: Object.values(defender.boosts ?? {}).reduce(
          (sum, value) => sum + Math.max(0, Number(value ?? 0)),
          0,
        ),
        ...opponentSetupThreat,
        ...setupFollowup,
        ...batonPassProfile,
        setupIncomingDamageRatioAfterBoost,
        setupFollowupSurvivalProbability,
        setupResidualDamage,
        setupThreateningMoveId: setupIncomingThreat.moveId,
        setupGuardConsumptionProbability:
          setupIncomingThreat.guardConsumptionProbability,
        setupFollowupActsBeforeThreat,
        setupCanSurviveIncoming:
          setupFollowupSurvivalProbability >= 0.5,
        opponentConditionalPriorityMoveId:
          opponentConditionalPriorityThreat?.moveId ?? "",
        opponentConditionalPriorityLikelihood,
        opponentConditionalPriorityKnockoutProbability:
          opponentConditionalPriorityThreat?.knockoutProbability ?? 0,
        opponentConditionalPriorityExpectedDamage:
          opponentConditionalPriorityThreat?.expectedDamage ?? 0,
        opponentConditionalPriorityFailureCount:
          conditionalPriorityFailureCount,
        ...statusMoveDisruptionProfile,
        ...offensiveStatusControlProfile,
        ...roomContext,
        activeRoleScore,
        activePrimaryRole: activeRoleProfile?.primaryRole ?? "support",
        roleComplete: activeRoleProgress?.roleComplete === true,
        expendableResource:
          activeRoleProgress?.expendableResource === true,
        completedRoles: activeRoleProgress?.completedRoles ?? [],
        remainingRoles: activeRoleProgress?.remainingRoles ?? [],
        roleProgressReasons: activeRoleProgress?.reasons ?? [],
        mustPreserveResource: Boolean(activePreservationProfile),
        mustPreserveFor:
          activePreservationProfile?.threats.map((threat) => threat.species) ?? [],
        selfSacrifice: SELF_DESTRUCT_MOVES.has(cleanId(displayMove.id)),
        selfBoosts: displayMove.selfBoosts,
        effectiveSelfBoostTotal,
        selfBoostAlreadyMaxed:
          positiveSelfBoostCount > 0 && effectiveSelfBoostTotal <= 0,
        selfDropTotal,
        hasSelfStatDrop: selfDropTotal > 0,
        expectedRecoilDamage,
        recoilWouldFaint:
          expectedRecoilDamage > 0 && expectedRecoilDamage >= pokemon.hp,
        ...switchPressure,
        hitCount: damageOutcome.hitCount,
        damageRangeMinimum: damageOutcome.effectiveMinimum,
        damageRangeMaximum: damageOutcome.effectiveMaximum,
        rawDamageRangeMinimum: damageOutcome.totalMinimum,
        rawDamageRangeMaximum: damageOutcome.totalMaximum,
        sturdyBlocked: damageOutcome.sturdyBlocked,
        focusSashBlocked: damageOutcome.focusSashBlocked,
        singleHitSurvivalBlocked: damageOutcome.singleHitSurvivalBlocked,
        breaksSturdy: damageOutcome.breaksSturdy,
        breaksFocusSash: damageOutcome.breaksFocusSash,
        koChance: damageOutcome.koChance,
      };
      return {
        slot: index + 1,
        id: displayMove.id,
        name: displayMove.name,
        type: displayMove.type,
        category: displayMove.category,
        power: displayMove.power,
        accuracy: effectiveAccuracy(pokemon, defender, displayMove, state),
        priority: displayMove.priority,
        willFail: baseCandidate.willFail,
        protectSuccessProbability: baseCandidate.protectSuccessProbability,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        incomingDamageRatio,
        opponentThreateningMoveId: incomingBeforeActionThreat.moveId,
        opponentThreatPriority: incomingBeforeActionThreat.priority,
        opponentKnockoutProbability:
          incomingBeforeActionThreat.knockoutProbability,
        actionBeforeThreatProbability:
          incomingBeforeActionThreat.actionBeforeThreatProbability,
        opponentKnockoutBeforeActionProbability:
          incomingBeforeActionThreat.knockoutBeforeActionProbability,
        recoveryAmount,
        recoveryExposureTurns,
        recoveryExpectedIncomingDamage,
        recoveryNetHpChange,
        recoveryBeforeActionKoRisk:
          recoveryExposureTurns > 0
            ? incomingBeforeActionThreat.knockoutBeforeActionProbability
            : 0,
        conditionalPriorityRepeatFailure,
        conditionalPriorityRepeatCause,
        conditionalPriorityFailureStreak,
        conditionalPriorityAdaptChance,
        conditionalPriorityAdapted,
        conditionalPriorityAdaptPenalty,
        upperHandExactOutcome,
        upperHandSuccessProbability,
        upperHandEligiblePriorityMoves: upperHandEligibleThreats.map(
          (threat) => threat.moveId,
        ),
        exactOpponentMoveId: exactOpponentMove?.id ?? "",
        opponentLastMoveId: opponentLastDisplayMove?.id ?? "",
        opponentHp: defender.hp,
        score: scoreAiMoveCandidate(baseCandidate, difficulty, strategy),
        expectedDamage,
        tacticalValue,
        hpPercent: pokemon.hp / pokemon.stats.hp,
        incomingDamageRatio,
        opponentHp: defender.hp,
        opponentAbility: activeAbility(defender),
        opponentHazards,
        opponentVolatiles: defender.volatiles ?? {},
        opponentStatus: defender.status,
        opponentRevealedSetupResetMoveIds,
        opponentActiveRevealedSetupResetMoveIds,
        statusBlocked,
        statusResidualCandidates,
        livingOpponents,
        turn: state.turn + 1,
        expectedSurvivalTurns: survivalTurns,
        sustainTurnBonus: aiSustainTurnBonus(pokemon),
        saltCureResidualDamage: saltCureResidualDamage(defender),
        opponentMaxHp: defender.stats.hp,
        opponentPrimaryRole: opponentRoleProfile?.primaryRole ?? "support",
        opponentAceScore: opponentRoleProfile?.aceScore ?? 0,
        opponentAceQualified: opponentRoleProfile?.aceProfile?.qualifies === true,
        opponentBoosts: defender.boosts ?? {},
        opponentPositiveBoosts: Object.values(defender.boosts ?? {}).reduce(
          (sum, value) => sum + Math.max(0, Number(value ?? 0)),
          0,
        ),
        ...opponentSetupThreat,
        ...setupFollowup,
        ...batonPassProfile,
        setupIncomingDamageRatioAfterBoost,
        setupFollowupSurvivalProbability,
        setupResidualDamage,
        setupThreateningMoveId: setupIncomingThreat.moveId,
        setupGuardConsumptionProbability:
          setupIncomingThreat.guardConsumptionProbability,
        setupFollowupActsBeforeThreat,
        setupCanSurviveIncoming:
          setupFollowupSurvivalProbability >= 0.5,
        opponentConditionalPriorityMoveId:
          opponentConditionalPriorityThreat?.moveId ?? "",
        opponentConditionalPriorityLikelihood,
        opponentConditionalPriorityKnockoutProbability:
          opponentConditionalPriorityThreat?.knockoutProbability ?? 0,
        opponentConditionalPriorityExpectedDamage:
          opponentConditionalPriorityThreat?.expectedDamage ?? 0,
        opponentConditionalPriorityFailureCount:
          conditionalPriorityFailureCount,
        ...offensiveStatusControlProfile,
        ...statusMoveDisruptionProfile,
        ...roomContext,
        activeRoleScore,
        activePrimaryRole: activeRoleProfile?.primaryRole ?? "support",
        roleComplete: activeRoleProgress?.roleComplete === true,
        expendableResource:
          activeRoleProgress?.expendableResource === true,
        completedRoles: activeRoleProgress?.completedRoles ?? [],
        remainingRoles: activeRoleProgress?.remainingRoles ?? [],
        roleProgressReasons: activeRoleProgress?.reasons ?? [],
        mustPreserveResource: Boolean(activePreservationProfile),
        mustPreserveFor:
          activePreservationProfile?.threats.map((threat) => threat.species) ?? [],
        selfSacrifice: SELF_DESTRUCT_MOVES.has(cleanId(displayMove.id)),
        selfBoosts: displayMove.selfBoosts,
        effectiveSelfBoostTotal,
        selfBoostAlreadyMaxed:
          positiveSelfBoostCount > 0 && effectiveSelfBoostTotal <= 0,
        selfDropTotal,
        hasSelfStatDrop: selfDropTotal > 0,
        expectedRecoilDamage,
        recoilWouldFaint:
          expectedRecoilDamage > 0 && expectedRecoilDamage >= pokemon.hp,
        ...switchPressure,
        hitCount: damageOutcome.hitCount,
        damageRangeMinimum: damageOutcome.effectiveMinimum,
        damageRangeMaximum: damageOutcome.effectiveMaximum,
        rawDamageRangeMinimum: damageOutcome.totalMinimum,
        rawDamageRangeMaximum: damageOutcome.totalMaximum,
        sturdyBlocked: damageOutcome.sturdyBlocked,
        focusSashBlocked: damageOutcome.focusSashBlocked,
        singleHitSurvivalBlocked: damageOutcome.singleHitSurvivalBlocked,
        breaksSturdy: damageOutcome.breaksSturdy,
        breaksFocusSash: damageOutcome.breaksFocusSash,
        koChance: baseCandidate.koChance,
        pp: move.pp,
        disabled:
          cleanId(displayMove.id) === "haze" &&
          Object.values(defender.boosts ?? {}).every(
            (value) => Number(value ?? 0) <= 0,
          ),
        temporarilyDisabled:
          pokemon.dynamaxTurns <= 0 &&
          !dynamaxMode &&
          isMoveTemporarilyDisabled(pokemon, move),
        targetRestricted:
          !dynamaxMode &&
          isMoveBlockedByDynamaxTarget(displayMove, defender),
      };
    })
    .filter(
      (candidate) =>
        candidate.pp > 0 &&
        !candidate.temporarilyDisabled &&
        !candidate.targetRestricted,
    )
    .map((candidate, _, candidates) => {
      const candidateAccuracy =
        candidate.accuracy === true ? 1 : Number(candidate.accuracy ?? 100) / 100;
      const safeNoDropKoAvailable = candidates.some(
        (other) =>
          other.koChance === "guaranteed" &&
          other.hasSelfStatDrop !== true &&
          other.selfSacrifice !== true &&
          other.sturdyBlocked !== true &&
          other.focusSashBlocked !== true,
      );
      const safeNoRecoilKoAvailable = candidates.some((other) => {
        if (
          other === candidate ||
          other.koChance !== "guaranteed" ||
          Number(other.expectedRecoilDamage ?? 0) > 0 ||
          other.selfSacrifice === true ||
          other.sturdyBlocked === true ||
          other.focusSashBlocked === true
        ) {
          return false;
        }
        const otherAccuracy =
          other.accuracy === true ? 1 : Number(other.accuracy ?? 100) / 100;
        return otherAccuracy >= 0.85 && otherAccuracy >= candidateAccuracy - 0.15;
      });
      const reliableKoAlternative = candidates.some((other) => {
        if (
          other === candidate ||
          other.category === "Status" ||
          other.koChance !== "guaranteed" ||
          other.selfSacrifice === true ||
          other.recoilWouldFaint === true ||
          other.sturdyBlocked === true ||
          other.focusSashBlocked === true
        ) {
          return false;
        }
        const otherAccuracy =
          other.accuracy === true ? 1 : Number(other.accuracy ?? 100) / 100;
        return otherAccuracy >= 0.85;
      });
      const enriched = {
        ...candidate,
        safeNoDropKoAvailable:
          candidate.koChance === "guaranteed" &&
          candidate.hasSelfStatDrop === true &&
          safeNoDropKoAvailable,
        safeNoRecoilKoAvailable:
          Number(candidate.expectedRecoilDamage ?? 0) > 0 &&
          candidate.koChance === "guaranteed" &&
          safeNoRecoilKoAvailable,
        reliableKoAlternative,
        knockoutBoostAlternative:
          reliableKoAlternative && knockoutBoosts
            ? { ...knockoutBoosts }
            : null,
      };
      const oneTurnEvaluation = oneTurnStateBefore
        ? evaluateOneTurnBattleState(
            oneTurnStateBefore,
            simpleMoveOneTurnOutcome({
              pokemon: threatTarget,
              defender,
              candidate: enriched,
              opponentBestDamage,
              activeRoleProfile,
              opponentRoleProfile,
              activePreservationProfile,
              gimmickResourceCost,
            }),
          )
        : null;
      const evaluated = oneTurnEvaluation
        ? {
            ...enriched,
            oneTurnEvaluation:
              compactOneTurnEvaluation(oneTurnEvaluation),
            battleStateValueDelta: oneTurnEvaluation.delta,
            oneTurnSearchWeight,
          }
        : enriched;
      return {
        ...evaluated,
        score: scoreAiMoveCandidate(evaluated, difficulty, strategy),
      };
    })
    .sort((left, right) => right.score - left.score || left.slot - right.slot);
}

function predictedEntryHazardDamage(state, sideIndex, pokemon) {
  const conditions = state.sides[sideIndex]?.conditions ?? {};
  return JSON.parse(
    deriveEntryHazardDamageJson(
      JSON.stringify({
        currentHp: pokemon.hp,
        maximumHp: pokemon.stats.hp,
        stealthRockLayers: conditions.stealthrock
          ? Number(conditions.stealthrock.layers ?? 1)
          : 0,
        spikesLayers: conditions.spikes
          ? Number(conditions.spikes.layers ?? 1)
          : 0,
        types: pokemon.types,
        ability: activeAbility(pokemon),
        item: pokemon.item,
      }),
    ),
  ).damage;
}

function bestAiAttackProfile(
  state,
  attackerSide,
  attacker,
  defenderSide,
  defender,
) {
  return attacker.moves.reduce(
    (best, move) => {
      if (move.pp <= 0 || isMoveTemporarilyDisabled(attacker, move)) return best;
      const displayMove = aiDisplayMoveData(attacker, move);
      if (displayMove.category === "Status") return best;
      const accuracy = expectedAccuracyFraction(
        attacker,
        defender,
        displayMove,
        state,
      );
      const estimate = aiExpectedMoveDamage(
        attacker,
        defender,
        displayMove,
        state,
        attackerSide,
        defenderSide,
      );
      const expectedDamage = estimate.expectedDamage * accuracy;
      if (expectedDamage <= best.expectedDamage) return best;
      return {
        expectedDamage,
        priority: Number(displayMove.priority ?? 0),
        moveId: displayMove.id,
      };
    },
    { expectedDamage: 0, priority: 0, moveId: "" },
  );
}

function teraDefensiveProjection(state, projectedState, sideIndex) {
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const currentPokemon = activePokemon(state, sideIndex);
  const projectedPokemon = activePokemon(projectedState, sideIndex);
  const maxHp = Math.max(1, Number(currentPokemon.stats?.hp ?? currentPokemon.hp));
  const matchups = state.sides[opponentSide].team
    .map((enemy, index) => {
      if (enemy.fainted || enemy.hp <= 0) return null;
      const currentDamage = bestAiAttackProfile(
        state,
        opponentSide,
        enemy,
        sideIndex,
        currentPokemon,
      ).expectedDamage;
      const projectedDamage = bestAiAttackProfile(
        projectedState,
        opponentSide,
        enemy,
        sideIndex,
        projectedPokemon,
      ).expectedDamage;
      return {
        slot: index + 1,
        name: enemy.name,
        types: [...(enemy.types ?? [])],
        active: index === state.sides[opponentSide].active,
        currentDamage,
        projectedDamage,
      };
    })
    .filter(Boolean);
  const activeMatchup = matchups.find((matchup) => matchup.active) ?? null;
  const futureMatchups = matchups.filter((matchup) => !matchup.active);
  const futureWeight = Math.max(1, futureMatchups.length);
  const futureDamageReductionRatio =
    futureMatchups.reduce(
      (total, matchup) =>
        total + (matchup.currentDamage - matchup.projectedDamage) / maxHp,
      0,
    ) / futureWeight;
  const activeDamageReductionRatio = activeMatchup
    ? (activeMatchup.currentDamage - activeMatchup.projectedDamage) / maxHp
    : 0;
  const preventsActiveKo =
    Boolean(activeMatchup) &&
    activeMatchup.currentDamage >= currentPokemon.hp &&
    activeMatchup.projectedDamage < projectedPokemon.hp;
  const createsActiveKoRisk =
    Boolean(activeMatchup) &&
    activeMatchup.currentDamage < currentPokemon.hp &&
    activeMatchup.projectedDamage >= projectedPokemon.hp;
  const activeKoAfterTera =
    Boolean(activeMatchup) &&
    activeMatchup.projectedDamage >= projectedPokemon.hp;

  return {
    activeMatchup,
    futureMatchups,
    activeDamageReductionRatio,
    futureDamageReductionRatio,
    preventsActiveKo,
    createsActiveKoRisk,
    activeKoAfterTera,
  };
}

function teraLongTermPotential(state, sideIndex, pokemon) {
  const teraType = canonicalTypeName(pokemon?.configuredTeraType);
  if (!teraType) return 0;
  const originalTypes = pokemon.originalTypes ?? pokemon.types ?? [];
  const offensiveGain = (pokemon.moves ?? []).reduce((best, move) => {
    if (
      move.category === "Status" ||
      Number(move.power ?? 0) <= 0 ||
      cleanId(move.type) !== cleanId(teraType)
    ) {
      return best;
    }
    const originalStab = originalTypes.some(
      (type) => cleanId(type) === cleanId(move.type),
    )
      ? 1.5
      : 1;
    const teraStab = originalStab > 1 ? 2 : 1.5;
    return Math.max(
      best,
      Number(move.power ?? 0) * Math.max(0, teraStab - originalStab) * 0.08,
    );
  }, 0);
  const enemyMoves = state.sides[sideIndex === 0 ? 1 : 0].team.flatMap(
    (enemy) =>
      enemy.fainted || enemy.hp <= 0
        ? []
        : (enemy.moves ?? []).filter(
            (move) => move.category !== "Status" && Number(move.power ?? 0) > 0,
          ),
  );
  const defensiveGain =
    enemyMoves.length > 0
      ? (enemyMoves.reduce(
          (total, move) =>
            total +
            Math.max(
              -2,
              Math.min(
                2,
                typeMultiplier(move.type, originalTypes) -
                  typeMultiplier(move.type, [teraType]),
              ),
            ),
          0,
        ) /
          enemyMoves.length) *
        5
      : 0;
  return Math.round((offensiveGain + defensiveGain) * 100) / 100;
}

function teraResourceOpportunity(state, sideIndex) {
  const side = state.sides[sideIndex];
  const active = activePokemon(state, sideIndex);
  const currentPotential = teraLongTermPotential(state, sideIndex, active);
  const alternatives = side.team
    .filter(
      (pokemon) =>
        pokemon !== active &&
        !pokemon.fainted &&
        pokemon.hp > 0 &&
        isConfiguredTeraPokemon(pokemon),
    )
    .map((pokemon) => ({
      name: pokemon.name,
      potential: teraLongTermPotential(state, sideIndex, pokemon),
    }))
    .sort((left, right) => right.potential - left.potential);
  return {
    currentPotential,
    alternatives,
    bestAlternative: alternatives[0] ?? null,
  };
}

function applyTeraDefensiveScore(
  candidate,
  projection,
  { baseMove = {}, selectedMove = {}, resourceOpportunity = null } = {},
) {
  if (!candidate || !projection) return candidate;
  const reasons = [...(candidate.reasons ?? [])];
  let adjustment = 0;
  const safeGuaranteedKo =
    baseMove.koChance === "guaranteed" &&
    Number(baseMove.actionBeforeThreatProbability ?? 0) >= 0.99;
  const teraPreventsCounterattack =
    selectedMove.koChance === "guaranteed" &&
    Number(selectedMove.actionBeforeThreatProbability ?? 0) >= 0.99;
  const actionScoreGain =
    Number(selectedMove.score ?? 0) - Number(baseMove.score ?? 0);
  const koChanceValue = (value) =>
    ({ guaranteed: 2, possible: 1 }[value] ?? 0);
  const baseExpectedDamage = Number(baseMove.expectedDamage ?? 0);
  const selectedExpectedDamage = Number(selectedMove.expectedDamage ?? 0);
  const expectedDamageGain = Math.max(
    0,
    selectedExpectedDamage - baseExpectedDamage,
  );
  const improvesKoChance =
    koChanceValue(selectedMove.koChance) > koChanceValue(baseMove.koChance);
  const hasMeaningfulDamageGain =
    expectedDamageGain >= Math.max(30, baseExpectedDamage * 0.2);
  const failsToSurviveDefensiveTera =
    projection.activeKoAfterTera &&
    !teraPreventsCounterattack;
  const activeWeight = Math.max(
    -32,
    Math.min(
      36,
      safeGuaranteedKo || failsToSurviveDefensiveTera
        ? 0
        : projection.activeDamageReductionRatio * 48,
    ),
  );
  if (Math.abs(activeWeight) >= 0.5) {
    adjustment += activeWeight;
    reasons.push({
      code: "gimmick.tera.active_damage_change",
      label: "현재 대면 방어 변화",
      value: projection.activeMatchup
        ? {
            opponent: projection.activeMatchup.name,
            types: projection.activeMatchup.types,
            before: Math.round(projection.activeMatchup.currentDamage * 100) / 100,
            after: Math.round(projection.activeMatchup.projectedDamage * 100) / 100,
          }
        : null,
      weight: Math.round(activeWeight * 100) / 100,
      message: projection.activeMatchup
        ? `테라스탈 전후 ${projection.activeMatchup.name}의 최선 공격 피해를 ${Math.round(
            projection.activeMatchup.currentDamage,
          )} -> ${Math.round(projection.activeMatchup.projectedDamage)}로 비교했습니다.`
        : "현재 상대의 공격 피해 변화가 없습니다.",
    });
  }
  if (projection.preventsActiveKo && !safeGuaranteedKo) {
    adjustment += 32;
    reasons.push({
      code: "gimmick.tera.prevents_active_ko",
      label: "테라 생존 확보",
      value: true,
      weight: 32,
      message:
        "현재 상대의 최선 공격에 쓰러지는 상황을 테라스탈로 피할 수 있어 생존 가치를 반영했습니다.",
    });
  }
  if (projection.createsActiveKoRisk) {
    adjustment -= 40;
    reasons.push({
      code: "gimmick.tera.creates_active_ko_risk",
      label: "테라 후 KO 위험",
      value: true,
      weight: -40,
      message:
        "현재는 버티는 공격을 테라스탈 후에는 버티지 못하므로 사용 가치를 크게 낮췄습니다.",
    });
  }
  if (failsToSurviveDefensiveTera) {
    adjustment -= 999;
    reasons.push({
      code: "gimmick.tera.fails_to_survive_active_hit",
      label: "테라 후에도 생존 불가",
      value: projection.activeMatchup
        ? {
            opponent: projection.activeMatchup.name,
            damage:
              Math.round(projection.activeMatchup.projectedDamage * 100) / 100,
          }
        : true,
      weight: -999,
      message:
        "테라스탈로 피해를 줄여도 현재 상대의 공격에 쓰러지며, 그 전에 확정 KO로 반격을 막을 수도 없어 테라 자원을 보존합니다.",
    });
  }
  if (!failsToSurviveDefensiveTera && projection.futureMatchups.length > 0) {
    const futureWeight = Math.max(
      -18,
      Math.min(18, projection.futureDamageReductionRatio * 24),
    );
    adjustment += futureWeight;
    reasons.push({
      code: "gimmick.tera.remaining_matchups",
      label: "남은 상대 대면",
      value: projection.futureMatchups.map((matchup) => ({
        opponent: matchup.name,
        types: matchup.types,
        before: Math.round(matchup.currentDamage * 100) / 100,
        after: Math.round(matchup.projectedDamage * 100) / 100,
      })),
      weight: Math.round(futureWeight * 100) / 100,
      message:
        "살아 있는 상대 포켓몬들의 타입과 알려진 공격을 기준으로 이후 대면의 피해 변화를 함께 반영했습니다.",
    });
  }
  if (
    Math.abs(projection.activeDamageReductionRatio) < 0.05 &&
    Math.abs(projection.futureDamageReductionRatio) < 0.05
  ) {
    reasons.push({
      code: "gimmick.tera.no_defensive_gain",
      label: "방어 이득 부족",
      value: false,
      weight: 0,
      message:
        "현재와 남은 대면에서 뚜렷한 방어 이득이 없어 테라 자원을 보존합니다.",
    });
  }
  const hasImmediateGain =
    actionScoreGain >= 5 ||
    (!safeGuaranteedKo &&
      !failsToSurviveDefensiveTera &&
      (projection.preventsActiveKo ||
        projection.activeDamageReductionRatio >= 0.1));
  if (
    safeGuaranteedKo &&
    !hasImmediateGain &&
    resourceOpportunity?.alternatives?.length > 0
  ) {
    adjustment -= 36;
    reasons.push({
      code: "gimmick.tera.safe_ko_preservation",
      label: "확정 KO에서 테라 보존",
      value: {
        move: baseMove.name ?? baseMove.id ?? "",
        alternatives: resourceOpportunity.alternatives.map(
          (alternative) => alternative.name,
        ),
      },
      weight: -36,
      message:
        "테라스탈 없이 먼저 확정 KO할 수 있고 다른 테라 후보가 살아 있어 공용 자원을 보존합니다.",
    });
  } else if (
    !hasImmediateGain &&
    resourceOpportunity?.alternatives?.length > 0
  ) {
    adjustment -= 8;
    reasons.push({
      code: "gimmick.tera.shared_resource_preservation",
      label: "파티 공용 테라 보존",
      value: resourceOpportunity.alternatives.map(
        (alternative) => alternative.name,
      ),
      weight: -8,
      message:
        "현재 행동의 즉시 이득이 작고 다른 사용 후보가 살아 있어 테라 자원을 보존합니다.",
    });
  }
  const opportunityGap =
    Number(resourceOpportunity?.bestAlternative?.potential ?? -Infinity) -
    Number(resourceOpportunity?.currentPotential ?? 0);
  if (opportunityGap > 2) {
    const opportunityPenalty = -Math.min(
      18,
      Math.round((opportunityGap - 2) * 200) / 100,
    );
    adjustment += opportunityPenalty;
    reasons.push({
      code: "gimmick.tera.better_reserve_candidate",
      label: "더 적합한 테라 후보 보존",
      value: {
        current: Math.round(resourceOpportunity.currentPotential * 100) / 100,
        reserve: resourceOpportunity.bestAlternative,
      },
      weight: opportunityPenalty,
      message: `${resourceOpportunity.bestAlternative.name}의 장기 테라 적합도가 더 높아 현재 포켓몬의 사용 점수를 낮췄습니다.`,
    });
  }

  const shouldPreserveForBetterCandidate =
    opportunityGap > 2 &&
    !improvesKoChance &&
    !hasMeaningfulDamageGain &&
    !projection.preventsActiveKo &&
    projection.activeDamageReductionRatio < 0.1;
  if (shouldPreserveForBetterCandidate) {
    const activationThreshold = Number(candidate.activationThreshold ?? 5);
    const scoreBeforeCap = Number(candidate.score ?? 0) + adjustment;
    const marginalPenalty = Math.min(
      0,
      activationThreshold - 1 - scoreBeforeCap,
    );
    adjustment += marginalPenalty;
    reasons.push({
      code: "gimmick.tera.marginal_gain",
      label: "테라 실질 이득 부족",
      value: {
        damageBefore: Math.round(baseExpectedDamage * 100) / 100,
        damageAfter: Math.round(selectedExpectedDamage * 100) / 100,
        koBefore: baseMove.koChance ?? "none",
        koAfter: selectedMove.koChance ?? "none",
      },
      weight: Math.round(marginalPenalty * 100) / 100,
      message:
        "KO 단계, 충분한 화력 상승, 현재 대면의 생존 가치가 개선되지 않고 더 적합한 파티 후보가 있어 테라 자원을 보존합니다.",
    });
  }

  return {
    ...candidate,
    score: Math.round((Number(candidate.score ?? 0) + adjustment) * 100) / 100,
    reasons,
    defensiveProjection: projection,
  };
}

const SIMPLE_THREAT_COUNTER_MAP_CACHE = new WeakMap();
const SIMPLE_ROLE_PROGRESS_CACHE = new WeakMap();
const SIMPLE_TEAM_ANALYSIS_CACHE = new WeakMap();
const SIMPLE_BATTLE_VALUE_STATE_CACHE = new WeakMap();
const SIMPLE_TEAM_MATCHUP_CACHE = new WeakMap();

function aiOneTurnSearchWeight(difficulty) {
  const id = cleanId(difficulty);
  if (id === "cheater") return 0.4;
  if (id === "expert") return 0.35;
  if (id === "advanced") return 0.2;
  return 0;
}

function compactOneTurnEvaluation(evaluation) {
  return {
    qValue: evaluation.qValue,
    delta: evaluation.delta,
    winProbabilityBefore: evaluation.winProbabilityBefore,
    winProbabilityAfter: evaluation.winProbabilityAfter,
    winProbabilityDelta: evaluation.winProbabilityDelta,
    componentDeltas: evaluation.componentDeltas,
    reasons: evaluation.reasons,
  };
}

function compactWinEstimate(estimate) {
  return {
    probability: estimate.probability,
    probabilityPercent: estimate.probabilityPercent,
    confidence: estimate.confidence,
    modelVersion: estimate.modelVersion,
    featureSchemaVersion: estimate.featureSchemaVersion,
    terminal: estimate.terminal,
    terminalOutcome: estimate.terminalOutcome,
    topFactors: estimate.topFactors,
  };
}

function simpleHazardLayerCount(conditions = {}) {
  return [
    ["stealthrock", 1],
    ["spikes", 3],
    ["toxicspikes", 2],
    ["stickyweb", 1],
  ].reduce((total, [id, maximum]) => {
    const condition = conditions[id];
    if (!condition) return total;
    const layers =
      condition === true ? 1 : Number(condition.layers ?? 1);
    return total + Math.max(0, Math.min(maximum, layers));
  }, 0);
}

function simpleTeamAnalysis(state, sideIndex) {
  const cached = SIMPLE_TEAM_ANALYSIS_CACHE.get(state);
  if (cached?.has(sideIndex)) return cached.get(sideIndex);
  const analysis = analyzeTeamProfile(
    state.sides[sideIndex].team.map((member) =>
      aiRoleAnalysisMember(member),
    ),
  );
  const nextCache = cached ?? new Map();
  nextCache.set(sideIndex, analysis);
  SIMPLE_TEAM_ANALYSIS_CACHE.set(state, nextCache);
  return analysis;
}

function simpleStatusBurden(pokemon, { active = false } = {}) {
  const status = cleanId(pokemon?.status);
  if (!status) return 0;
  if (status === "slp") {
    if (canExploitSleepForAi(pokemon)) return active ? 0.5 : 0.75;
    const remainingTurns = Math.max(
      1,
      Math.min(3, Number(pokemon?.statusTurns ?? 1)),
    );
    const strandedBoosts = Math.min(6, positiveBoostTotal(pokemon));
    // Active sleep concedes tempo and often strands boosts when switching out.
    return active
      ? 7 + remainingTurns * 1.5 + strandedBoosts * 0.75
      : 2 + remainingTurns * 0.5;
  }
  return status === "tox" ? 1.5 : 1;
}

function simpleRemainingGimmicks(side) {
  const used = side?.usedGimmicks ?? {};
  return ["mega", "zmove", "dynamax", "terastallize"].reduce(
    (total, gimmick) => total + (used[gimmick] === true ? 0 : 1),
    0,
  );
}

function simpleTeamMatchupMetrics(
  state,
  sideIndex,
  cacheKey = simpleAnalysisStateKey(state),
) {
  const cached = SIMPLE_TEAM_MATCHUP_CACHE.get(state);
  if (cached?.key === cacheKey && cached.bySide.has(sideIndex)) {
    return cached.bySide.get(sideIndex);
  }
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const ownSide = state.sides[sideIndex];
  const enemySide = state.sides[opponentSide];
  const allies = ownSide.team
    .map((member, index) => ({ member, index }))
    .filter(({ member }) => member.fainted !== true && member.hp > 0);
  const enemies = enemySide.team
    .map((member, index) => ({ member, index }))
    .filter(({ member }) => member.fainted !== true && member.hp > 0);
  const trickRoom =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  const pairByAlly = new WeakMap();
  const pairs = allies.map((allyEntry) => {
    const row = enemies.map((enemyEntry) => {
      const ally = allyEntry.member;
      const enemy = enemyEntry.member;
      const outgoing = bestAiAttackProfile(
        state,
        sideIndex,
        ally,
        opponentSide,
        enemy,
      );
      const incoming = bestAiAttackProfile(
        state,
        opponentSide,
        enemy,
        sideIndex,
        ally,
      );
      const allySpeed = effectiveSpeed(ally, state, sideIndex);
      const enemySpeed = effectiveSpeed(enemy, state, opponentSide);
      const actsBefore =
        outgoing.priority > incoming.priority ||
        (outgoing.priority === incoming.priority &&
          (trickRoom ? allySpeed < enemySpeed : allySpeed > enemySpeed));
      const enemyActsBefore =
        incoming.priority > outgoing.priority ||
        (incoming.priority === outgoing.priority &&
          (trickRoom ? enemySpeed < allySpeed : enemySpeed > allySpeed));
      const outgoingRatio = Math.min(
        1.5,
        Math.max(0, outgoing.expectedDamage / Math.max(1, enemy.hp)),
      );
      const incomingRatio = Math.min(
        1.5,
        Math.max(0, incoming.expectedDamage / Math.max(1, ally.hp)),
      );
      const canKo = outgoing.expectedDamage >= enemy.hp;
      const enemyCanKo = incoming.expectedDamage >= ally.hp;
      const safeKo = canKo && (actsBefore || !enemyCanKo);
      const enemySafeKo = enemyCanKo && (enemyActsBefore || !canKo);
      const tempo =
        actsBefore === enemyActsBefore ? 0 : actsBefore ? 0.06 : -0.06;
      const allyScore = Math.max(
        0,
        Math.min(
          1,
          0.5 +
            (outgoingRatio - incomingRatio) * 0.24 +
            tempo +
            (safeKo ? 0.16 : 0) -
            (enemySafeKo ? 0.16 : 0),
        ),
      );
      return {
        ally: allyEntry,
        enemy: enemyEntry,
        outgoing,
        incoming,
        actsBefore,
        enemyActsBefore,
        outgoingRatio,
        incomingRatio,
        safeKo,
        enemySafeKo,
        allyScore,
        enemyScore: 1 - allyScore,
      };
    });
    pairByAlly.set(allyEntry.member, new Map(
      row.map((pair) => [pair.enemy.member, pair]),
    ));
    return row;
  });

  const sideMetrics = (
    ownEntries,
    opposingEntries,
    scoreForPair,
    safeKoForPair,
    activeIndex,
  ) => {
    if (ownEntries.length === 0) {
      return {
        matchupCoverage: 0,
        safeKoCoverage: 0,
        benchReadiness: 0,
        sweepPotential: 0,
      };
    }
    if (opposingEntries.length === 0) {
      return {
        matchupCoverage: 1,
        safeKoCoverage: 1,
        benchReadiness: 1,
        sweepPotential: 1,
      };
    }
    const opponentBestScores = opposingEntries.map((opponent, opponentIndex) =>
      Math.max(
        ...ownEntries.map((_, ownIndex) =>
          scoreForPair(ownIndex, opponentIndex),
        ),
      ),
    );
    const matchupCoverage =
      opponentBestScores.reduce((total, score) => total + score, 0) /
      opposingEntries.length;
    const safeKoCoverage =
      opposingEntries.filter((_, opponentIndex) =>
        ownEntries.some((__, ownIndex) =>
          safeKoForPair(ownIndex, opponentIndex),
        ),
      ).length / opposingEntries.length;
    const memberSweepScores = ownEntries.map((entry, ownIndex) => {
      const matchupAverage =
        opposingEntries.reduce(
          (total, _, opponentIndex) =>
            total + scoreForPair(ownIndex, opponentIndex),
          0,
        ) / opposingEntries.length;
      const maxHp = Math.max(
        1,
        Number(entry.member.stats?.hp ?? entry.member.hp ?? 1),
      );
      const hpRatio = Math.max(
        0,
        Math.min(1, Number(entry.member.hp ?? 0) / maxHp),
      );
      return matchupAverage * (0.7 + hpRatio * 0.3);
    });
    const bench = ownEntries
      .map((entry, ownIndex) => ({ entry, ownIndex }))
      .filter(({ entry }) => entry.index !== activeIndex);
    const benchReadiness =
      bench.length === 0
        ? 0
        : bench.reduce((total, { entry, ownIndex }) => {
            const maxHp = Math.max(
              1,
              Number(entry.member.stats?.hp ?? entry.member.hp ?? 1),
            );
            const hpRatio = Math.max(
              0,
              Math.min(1, Number(entry.member.hp ?? 0) / maxHp),
            );
            const statusReadiness = entry.member.status ? 0.55 : 1;
            const bestMatchup = Math.max(
              ...opposingEntries.map((_, opponentIndex) =>
                scoreForPair(ownIndex, opponentIndex),
              ),
            );
            return (
              total +
              hpRatio * 0.45 +
              statusReadiness * 0.15 +
              bestMatchup * 0.4
            );
          }, 0) / bench.length;
    return {
      matchupCoverage,
      safeKoCoverage,
      benchReadiness,
      sweepPotential: Math.max(...memberSweepScores),
    };
  };

  const ownMetrics = sideMetrics(
    allies,
    enemies,
    (allyIndex, enemyIndex) => pairs[allyIndex][enemyIndex].allyScore,
    (allyIndex, enemyIndex) => pairs[allyIndex][enemyIndex].safeKo,
    ownSide.active,
  );
  const opponentMetrics = sideMetrics(
    enemies,
    allies,
    (enemyIndex, allyIndex) => pairs[allyIndex][enemyIndex].enemyScore,
    (enemyIndex, allyIndex) => pairs[allyIndex][enemyIndex].enemySafeKo,
    enemySide.active,
  );
  const result = {
    own: ownMetrics,
    opponent: opponentMetrics,
    pairByAlly,
  };
  const nextCache = cached?.key === cacheKey ? cached.bySide : new Map();
  nextCache.set(sideIndex, result);
  SIMPLE_TEAM_MATCHUP_CACHE.set(state, {
    key: cacheKey,
    bySide: nextCache,
  });
  return result;
}

function simpleBattleValueSide(
  side,
  roleAnalysis,
  uniqueCounterSlots = new Set(),
  matchupMetrics = {},
) {
  const members = side.team.map((member, index) => {
    const maxHp = Math.max(1, Number(member.stats?.hp ?? member.hp ?? 1));
    const hpRatio = Math.max(0, Math.min(1, Number(member.hp ?? 0) / maxHp));
    const living = member.fainted !== true && member.hp > 0;
    const isActive = index === side.active;
    const memberBoosts = positiveBoostTotal(member);
    const sleepingWithoutCounterplay =
      isActive &&
      cleanId(member.status) === "slp" &&
      !canExploitSleepForAi(member);
    return {
      hpRatio,
      living,
      aceCandidate:
        roleAnalysis?.roles?.[index]?.aceProfile?.qualifies === true,
      positiveBoosts: sleepingWithoutCounterplay
        ? memberBoosts * 0.25
        : memberBoosts,
      statusBurden: simpleStatusBurden(member, { active: isActive }),
      uniqueCounter: uniqueCounterSlots.has(index + 1),
    };
  });
  return JSON.parse(
    extractBattleValueSideJson(
      JSON.stringify({
        members,
        hazardLayers: simpleHazardLayerCount(side.conditions),
        gimmicksRemaining: simpleRemainingGimmicks(side),
        matchupCoverage: Number(matchupMetrics.matchupCoverage ?? 0),
        safeKoCoverage: Number(matchupMetrics.safeKoCoverage ?? 0),
        benchReadiness: Number(matchupMetrics.benchReadiness ?? 0),
        sweepPotential: Number(matchupMetrics.sweepPotential ?? 0),
      }),
    ),
  );
}

function simpleBattleStateValueSnapshot(
  state,
  sideIndex,
  allyAnalysis = null,
  threatCounterMap = null,
) {
  const cacheKey = simpleAnalysisStateKey(state);
  const cached = SIMPLE_BATTLE_VALUE_STATE_CACHE.get(state);
  if (cached?.key === cacheKey && cached.bySide.has(sideIndex)) {
    return cached.bySide.get(sideIndex);
  }
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const ownSide = state.sides[sideIndex];
  const enemySide = state.sides[opponentSide];
  const resolvedAllyAnalysis =
    allyAnalysis ??
    simpleTeamAnalysis(state, sideIndex);
  const enemyAnalysis = simpleTeamAnalysis(state, opponentSide);
  const resolvedThreatMap =
    threatCounterMap ??
    simpleThreatCounterMap(
      state,
      sideIndex,
      resolvedAllyAnalysis,
      cacheKey,
    );
  const uniqueCounterSlots = new Set(
    resolvedThreatMap.mustPreserveResources.map((resource) => resource.slot),
  );
  const ownActive = activePokemon(state, sideIndex);
  const enemyActive = activePokemon(state, opponentSide);
  const matchupMetrics = simpleTeamMatchupMetrics(state, sideIndex, cacheKey);
  const fieldAdvantage =
    (fieldSwitchSynergy(state, sideIndex, ownActive, enemyActive)
      .fieldSynergyValue -
      fieldSwitchSynergy(state, opponentSide, enemyActive, ownActive)
        .fieldSynergyValue) /
    4;

  const result = {
    own: simpleBattleValueSide(
      ownSide,
      resolvedAllyAnalysis,
      uniqueCounterSlots,
      matchupMetrics.own,
    ),
    opponent: simpleBattleValueSide(
      enemySide,
      enemyAnalysis,
      new Set(),
      matchupMetrics.opponent,
    ),
    fieldAdvantage: Math.round(fieldAdvantage * 100) / 100,
    field: {
      weather: effectiveWeather(state),
      terrain: cleanId(state.field?.terrain?.id),
      trickRoom:
        Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0,
    },
  };
  const nextCache = cached?.key === cacheKey ? cached.bySide : new Map();
  nextCache.set(sideIndex, result);
  SIMPLE_BATTLE_VALUE_STATE_CACHE.set(state, {
    key: cacheKey,
    bySide: nextCache,
  });
  return result;
}

export function estimateSimpleBattleWinProbability(state, sideIndex = 0) {
  return estimateBattleWinProbability(
    simpleBattleStateValueSnapshot(state, sideIndex),
  );
}

function aiCandidateKoProbability(candidate) {
  const accuracy =
    candidate.accuracy === true
      ? 1
      : Math.max(0, Math.min(1, Number(candidate.accuracy ?? 100) / 100));
  if (candidate.koChance === "guaranteed") return accuracy;
  if (candidate.koChance === "possible") return accuracy * 0.5;
  return 0;
}

function candidateStatusBurden(candidate) {
  const statusWeight = (status) => (cleanId(status) === "tox" ? 1.5 : 1);
  if (candidate.status && candidate.statusBlocked !== true) {
    return statusWeight(candidate.status);
  }
  return (candidate.statusResidualCandidates ?? []).reduce(
    (best, status) =>
      Math.max(
        best,
        statusWeight(status.status) *
          Math.max(0, Math.min(1, Number(status.chance ?? 100) / 100)),
      ),
    0,
  );
}

function candidateHazardConditionId(candidate) {
  const moveId = cleanId(candidate.id);
  return (
    cleanId(candidate.sideCondition) ||
    ({
      ceaselessedge: "spikes",
      spikes: "spikes",
      stealthrock: "stealthrock",
      stickyweb: "stickyweb",
      stoneaxe: "stealthrock",
      toxicspikes: "toxicspikes",
    })[moveId] ||
    ""
  );
}

function candidateHazardLayerDelta(candidate) {
  const conditionId = candidateHazardConditionId(candidate);
  if (!conditionId) return 0;
  const currentLayers = Number(
    candidate.opponentHazards?.[conditionId] ?? 0,
  );
  return JSON.parse(
    deriveHazardLayerFactsJson(
      JSON.stringify({ conditionId, currentLayers }),
    ),
  ).layerDelta;
}

function simpleMoveOneTurnOutcome({
  pokemon,
  defender,
  candidate,
  opponentBestDamage,
  activeRoleProfile,
  opponentRoleProfile,
  activePreservationProfile,
  gimmickResourceCost = 0,
}) {
  const actionProbability = Math.max(
    0,
    1 -
      Number(candidate.opponentKnockoutBeforeActionProbability ?? 0),
  );
  const actionBeforeThreatProbability = Math.max(
    0,
    Math.min(1, Number(candidate.actionBeforeThreatProbability ?? 0)),
  );
  const koProbability = aiCandidateKoProbability(candidate) * actionProbability;
  const retaliationProbability = Math.max(
    0,
    1 - koProbability * actionBeforeThreatProbability,
  );
  const maxHp = Math.max(1, Number(pokemon.stats?.hp ?? pokemon.hp));
  const opponentMaxHp = Math.max(
    1,
    Number(defender.stats?.hp ?? defender.hp),
  );
  const selfSacrifice = candidate.selfSacrifice === true;
  const projectedIncomingDamage =
    candidate.category === "Status"
      ? Math.max(
          0,
          Number(candidate.setupIncomingDamageRatioAfterBoost ?? 0) *
            pokemon.hp,
        )
      : Math.max(0, Number(opponentBestDamage ?? 0));
  const incomingDamage = selfSacrifice
    ? 0
    : Math.min(
        pokemon.hp,
        projectedIncomingDamage * retaliationProbability,
      );
  const recoilDamage = Math.max(
    0,
    Number(candidate.expectedRecoilDamage ?? 0) * actionProbability,
  );
  const residualDamage = Math.max(
    0,
    Number(candidate.setupResidualDamage ?? 0),
  );
  const recoveryAmount = Math.max(
    0,
    Number(candidate.recoveryAmount ?? 0) * actionProbability,
  );
  const projectedOwnHp = selfSacrifice
    ? 0
    : Math.max(
        0,
        Math.min(
          maxHp,
          pokemon.hp -
            incomingDamage -
            recoilDamage -
            residualDamage +
            recoveryAmount,
        ),
      );
  const ownFaintProbability = selfSacrifice
    ? 1
    : projectedOwnHp <= 0
      ? 1
      : 0;
  const expectedDamage = Math.max(
    0,
    Number(candidate.expectedDamage ?? 0) * actionProbability,
  );
  const ownIsAce =
    activeRoleProfile?.aceProfile?.qualifies === true;
  const opponentIsAce =
    opponentRoleProfile?.aceProfile?.qualifies === true;
  const ownPositiveBoostDelta =
    Math.max(0, Number(candidate.effectiveSelfBoostTotal ?? 0)) *
      actionProbability -
    Math.min(
      positiveBoostTotal(pokemon),
      Math.max(0, Number(candidate.selfDropTotal ?? 0)) * actionProbability,
    );
  const opponentPositiveBoostDelta = -Math.min(
    positiveBoostTotal(defender),
    Object.values(candidate.boosts ?? {}).reduce(
      (total, amount) => total + Math.max(0, -Number(amount ?? 0)),
      0,
    ) * actionProbability,
  );
  const fieldDelta =
    candidate.weather || candidate.terrain || candidate.pseudoWeather
      ? Math.max(2, Math.min(12, Number(candidate.fieldValue ?? 6)))
      : 0;
  const projectedField = {};
  if (candidate.weather) {
    projectedField.weather = cleanId(candidate.weather);
  }
  if (candidate.terrain) {
    projectedField.terrain = cleanId(candidate.terrain);
  }
  if (cleanId(candidate.pseudoWeather) === "trickroom") {
    projectedField.trickRoom = true;
  }

  return {
    own: {
      livingCount: -ownFaintProbability,
      totalHpRatio: (projectedOwnHp - pokemon.hp) / maxHp,
      aceAliveCount: ownIsAce ? -ownFaintProbability : 0,
      aceHpRatio: ownIsAce
        ? (projectedOwnHp - pokemon.hp) / maxHp
        : 0,
      positiveBoosts: ownPositiveBoostDelta,
      statusBurden:
        cleanId(candidate.id) === "rest" && pokemon.status
          ? -simpleStatusBurden(pokemon)
          : 0,
      uniqueCountersAlive:
        activePreservationProfile && ownFaintProbability > 0
          ? -ownFaintProbability
          : 0,
      gimmicksRemaining: -Math.max(0, Number(gimmickResourceCost ?? 0)),
    },
    opponent: {
      livingCount: -koProbability,
      totalHpRatio: -Math.min(defender.hp, expectedDamage) / opponentMaxHp,
      aceAliveCount: opponentIsAce ? -koProbability : 0,
      aceHpRatio: opponentIsAce
        ? -Math.min(defender.hp, expectedDamage) / opponentMaxHp
        : 0,
      positiveBoosts: opponentPositiveBoostDelta,
      statusBurden:
        defender.status || defender.fainted
          ? 0
          : candidateStatusBurden(candidate) * actionProbability,
      hazardLayers:
        candidateHazardLayerDelta(candidate) * actionProbability,
    },
    fieldAdvantage: fieldDelta * actionProbability,
    field: projectedField,
  };
}

function simpleSwitchOneTurnOutcome({
  current,
  target,
  candidate,
  targetRoleProfile,
  preservationProfile,
}) {
  const maxHp = Math.max(1, Number(target.stats?.hp ?? target.hp));
  const setupEvaluation = candidate.setupThreatEvaluation ?? {};
  const setupLikelihood =
    setupEvaluation.opponentCanSetup === true
      ? Math.max(
          0,
          Math.min(
            1,
            Number(
              setupEvaluation.setupLikelihood ??
                candidate.opponentSetupFirstTurnLikelihood ??
                0,
            ),
          ),
        )
      : 0;
  const attackProbability = 1 - setupLikelihood;
  const entryDamage = Math.max(0, Number(candidate.hazardDamage ?? 0));
  const incomingDamage =
    Math.max(0, Number(candidate.switchInExpectedDamage ?? 0)) *
    attackProbability;
  const projectedHp = Math.max(
    0,
    target.hp - entryDamage - incomingDamage,
  );
  const faintProbability = projectedHp <= 0 ? 1 : 0;
  const targetIsAce =
    targetRoleProfile?.aceProfile?.qualifies === true;
  const strongestBoost = setupEvaluation.strongestBoost ?? {};
  const opponentSetupBoosts =
    (Math.max(0, Number(strongestBoost.attack ?? 0)) +
      Math.max(0, Number(strongestBoost.speed ?? 0))) *
    setupLikelihood;

  return {
    own: {
      livingCount: -faintProbability,
      totalHpRatio: (projectedHp - target.hp) / maxHp,
      aceAliveCount: targetIsAce ? -faintProbability : 0,
      aceHpRatio: targetIsAce
        ? (projectedHp - target.hp) / maxHp
        : 0,
      positiveBoosts: -positiveBoostTotal(current),
      uniqueCountersAlive:
        preservationProfile && faintProbability > 0
          ? -faintProbability
          : 0,
    },
    opponent: {
      positiveBoosts: opponentSetupBoosts,
    },
  };
}

function simpleAnalysisStateKey(state) {
  return JSON.stringify({
    turn: state.turn,
    field: state.field,
    sides: state.sides.map((side) => ({
      active: side.active,
      conditions: side.conditions,
      team: side.team.map((member) => ({
        hp: member.hp,
        fainted: member.fainted,
        status: member.status,
        statusTurns: member.statusTurns,
        toxicCounter: member.toxicCounter,
        volatiles: member.volatiles,
        boosts: member.boosts,
        activeTurns: member.activeTurns,
      })),
    })),
  });
}

function simpleThreatCounterMap(
  state,
  sideIndex,
  allyAnalysis = null,
  cacheKey = simpleAnalysisStateKey(state),
) {
  const cached = SIMPLE_THREAT_COUNTER_MAP_CACHE.get(state);
  if (cached?.key === cacheKey && cached.bySide.has(sideIndex)) {
    return cached.bySide.get(sideIndex);
  }
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const allies = state.sides[sideIndex].team;
  const enemies = state.sides[opponentSide].team;
  const resolvedAllyAnalysis =
    allyAnalysis ??
    simpleTeamAnalysis(state, sideIndex);
  const enemyAnalysis = simpleTeamAnalysis(state, opponentSide);
  const matchupMetrics = simpleTeamMatchupMetrics(state, sideIndex, cacheKey);
  const result = buildThreatCounterMap({
    allies,
    enemies,
    allyAnalysis: resolvedAllyAnalysis,
    enemyAnalysis,
    evaluateMatchup: ({ ally, enemy }) => {
      const pair = matchupMetrics.pairByAlly.get(ally)?.get(enemy);
      if (!pair) {
        return {
          allyHpPercent: ally.hp / ally.stats.hp,
          incomingDamageRatio: 0,
          outgoingDamageRatio: 0,
          actsBefore: false,
          priorityKo: false,
        };
      }
      return {
        allyHpPercent: ally.hp / ally.stats.hp,
        incomingDamageRatio: pair.incoming.expectedDamage / ally.stats.hp,
        outgoingDamageRatio: pair.outgoingRatio,
        actsBefore: pair.actsBefore,
        priorityKo:
          pair.outgoing.priority > pair.incoming.priority &&
          pair.outgoing.expectedDamage >= enemy.hp,
      };
    },
  });
  const nextCache = cached?.key === cacheKey ? cached.bySide : new Map();
  nextCache.set(sideIndex, result);
  SIMPLE_THREAT_COUNTER_MAP_CACHE.set(state, {
    key: cacheKey,
    bySide: nextCache,
  });
  return result;
}

function simpleTeamRoleProgress(
  state,
  sideIndex,
  teamRoleAnalysis = null,
  threatCounterMap = null,
) {
  const cacheKey = simpleAnalysisStateKey(state);
  const cached = SIMPLE_ROLE_PROGRESS_CACHE.get(state);
  if (cached?.key === cacheKey && cached.bySide.has(sideIndex)) {
    return cached.bySide.get(sideIndex);
  }
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const side = state.sides[sideIndex];
  const opponent = state.sides[opponentSide];
  const resolvedTeamAnalysis =
    teamRoleAnalysis ??
    simpleTeamAnalysis(state, sideIndex);
  const enemyAnalysis = simpleTeamAnalysis(state, opponentSide);
  const resolvedThreatMap =
    threatCounterMap ??
    simpleThreatCounterMap(state, sideIndex, resolvedTeamAnalysis);
  const opponentLivingCount = opponent.team.filter(
    (member) => !member.fainted && member.hp > 0,
  ).length;
  const highThreatCount = resolvedThreatMap.threats.filter(
    (threat) =>
      ["critical", "high"].includes(threat.threatLevel) &&
      opponent.team[threat.enemySlot - 1]?.fainted !== true &&
      opponent.team[threat.enemySlot - 1]?.hp > 0,
  ).length;
  const setupThreatCount = enemyAnalysis.setupThreats.filter(
    (role) =>
      opponent.team[role.slot - 1]?.fainted !== true &&
      opponent.team[role.slot - 1]?.hp > 0,
  ).length;
  const opponentHazardSetterAlive = enemyAnalysis.hazardPlan.setters.some(
    (role) =>
      opponent.team[role.slot - 1]?.fainted !== true &&
      opponent.team[role.slot - 1]?.hp > 0,
  );
  const progress = side.team.map((member, index) => {
    const slot = index + 1;
    const preservationProfile =
      resolvedThreatMap.mustPreserveResources.find(
        (resource) => resource.slot === slot,
      );
    const assignedThreats = resolvedThreatMap.threats
      .filter((threat) =>
        [
          ...threat.counters,
          ...threat.softChecks,
          ...threat.revengeKillers,
        ].some((resource) => resource.slot === slot),
      )
      .map((threat) => threat.species);
    return evaluatePokemonRoleProgress({
      member: aiRoleAnalysisMember(member),
      roleProfile: resolvedTeamAnalysis.roles[index],
      ownSideConditions: side.conditions,
      opponentSideConditions: opponent.conditions,
      opponentLivingCount,
      highThreatCount,
      setupThreatCount,
      assignedThreats,
      opponentHazardSetterAlive,
      mustPreserveResource: Boolean(preservationProfile),
      activeTurns: Number(member.activeTurns ?? 0),
    });
  });
  const nextCache = cached?.key === cacheKey ? cached.bySide : new Map();
  nextCache.set(sideIndex, progress);
  SIMPLE_ROLE_PROGRESS_CACHE.set(state, {
    key: cacheKey,
    bySide: nextCache,
  });
  return progress;
}

function projectedSwitchMoveCandidates(
  state,
  sideIndex,
  pokemon,
  opponentSide,
  opponent,
  hpAfterSwitchIn,
  difficulty,
  strategy,
) {
  if (hpAfterSwitchIn <= 0) return [];
  const projectedPokemon = {
    ...pokemon,
    hp: Math.max(1, Math.floor(hpAfterSwitchIn)),
  };
  const opponentThreats = opponent.moves
    .filter(
      (move) =>
        move.pp > 0 &&
        !isMoveTemporarilyDisabled(opponent, move),
    )
    .map((move) => {
      const displayMove = aiDisplayMoveData(opponent, move);
      if (displayMove.category === "Status") return null;
      const accuracy = expectedAccuracyFraction(
        opponent,
        projectedPokemon,
        displayMove,
        state,
      );
      const estimate = aiExpectedMoveDamage(
        opponent,
        projectedPokemon,
        displayMove,
        state,
        opponentSide,
        sideIndex,
      );
      const outcome = aiDamageOutcomeProfile(
        opponent,
        projectedPokemon,
        displayMove,
        estimate.range,
      );
      return {
        moveId: displayMove.id,
        priority: Number(displayMove.priority ?? 0),
        expectedDamage: estimate.expectedDamage * accuracy,
        knockoutProbability:
          aiDamageThresholdChance(
            outcome.effectiveMinimum,
            outcome.effectiveMaximum,
            projectedPokemon.hp,
          ) * accuracy,
      };
    })
    .filter(Boolean);
  const incomingDamage = opponentThreats.reduce(
    (best, threat) => Math.max(best, threat.expectedDamage),
    0,
  );
  const trickRoomActive =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  const attackerSpeed = effectiveSpeed(projectedPokemon, state, sideIndex);
  const defenderSpeed = effectiveSpeed(opponent, state, opponentSide);

  return projectedPokemon.moves
    .map((move, index) => {
      if (
        move.pp <= 0 ||
        isMoveTemporarilyDisabled(projectedPokemon, move)
      ) {
        return null;
      }
      const displayMove = aiDisplayMoveData(projectedPokemon, move);
      const movePriority = Number(displayMove.priority ?? 0);
      const incomingThreat = opponentThreats.reduce(
        (worst, threat) => {
          let actsBeforeProbability = 0;
          if (movePriority > threat.priority) {
            actsBeforeProbability = 1;
          } else if (movePriority === threat.priority) {
            const speedComparison = trickRoomActive
              ? defenderSpeed - attackerSpeed
              : attackerSpeed - defenderSpeed;
            actsBeforeProbability =
              speedComparison > 0 ? 1 : speedComparison === 0 ? 0.5 : 0;
          }
          const knockoutBeforeActionProbability =
            threat.knockoutProbability * (1 - actsBeforeProbability);
          return knockoutBeforeActionProbability >
            worst.knockoutBeforeActionProbability
            ? {
                ...threat,
                knockoutBeforeActionProbability,
              }
            : worst;
        },
        {
          moveId: "",
          priority: 0,
          expectedDamage: 0,
          knockoutProbability: 0,
          knockoutBeforeActionProbability: 0,
        },
      );
      const damageEstimate = aiExpectedMoveDamage(
        projectedPokemon,
        opponent,
        displayMove,
        state,
        sideIndex,
        opponentSide,
      );
      const damageOutcome = aiDamageOutcomeProfile(
        projectedPokemon,
        opponent,
        displayMove,
        damageEstimate.range,
      );
      const accuracy = expectedAccuracyFraction(
        projectedPokemon,
        opponent,
        displayMove,
        state,
      );
      const expectedDamage = Math.min(
        opponent.hp,
        damageEstimate.expectedDamage * accuracy,
      );
      const missingHp = Math.max(
        0,
        projectedPokemon.stats.hp - projectedPokemon.hp,
      );
      const selfBoostValue = Object.values(displayMove.selfBoosts ?? {}).reduce(
        (sum, amount) => sum + Number(amount ?? 0) * 15,
        0,
      );
      const recoveryValue = displayMove.heal
        ? Math.min(
            missingHp,
            fractionAmount(projectedPokemon.stats.hp, displayMove.heal),
          ) * 0.75
        : 0;
      const selfDropTotal = Object.values(
        displayMove.selfBoosts ?? {},
      ).reduce(
        (sum, amount) => sum + Math.max(0, -Number(amount ?? 0)),
        0,
      );
      const candidate = {
        ...displayMove,
        slot: index + 1,
        expectedDamage,
        tacticalValue: selfBoostValue + recoveryValue,
        hpPercent: projectedPokemon.hp / projectedPokemon.stats.hp,
        incomingDamageRatio:
          projectedPokemon.hp > 0
            ? incomingDamage / projectedPokemon.hp
            : 1,
        opponentHp: opponent.hp,
        opponentAbility: activeAbility(opponent),
        opponentVolatiles: opponent.volatiles ?? {},
        opponentStatus: opponent.status,
        livingOpponents: state.sides[opponentSide].team.filter(
          (member) => !member.fainted && member.hp > 0,
        ).length,
        opponentThreateningMoveId: incomingThreat.moveId,
        opponentThreatPriority: incomingThreat.priority,
        opponentKnockoutProbability: incomingThreat.knockoutProbability,
        opponentKnockoutBeforeActionProbability:
          incomingThreat.knockoutBeforeActionProbability,
        selfBoosts: displayMove.selfBoosts,
        selfDropTotal,
        hasSelfStatDrop: selfDropTotal > 0,
        hitCount: damageOutcome.hitCount,
        damageRangeMinimum: damageOutcome.effectiveMinimum,
        damageRangeMaximum: damageOutcome.effectiveMaximum,
        koChance: damageOutcome.koChance,
      };
      return {
        ...candidate,
        score: scoreAiMoveCandidate(candidate, difficulty, strategy),
      };
    })
    .filter(Boolean)
    .sort((left, right) => right.score - left.score || left.slot - right.slot);
}

function positiveBoostTotal(pokemon) {
  return Object.values(pokemon.boosts ?? {}).reduce(
    (sum, amount) => sum + Math.max(0, Number(amount ?? 0)),
    0,
  );
}

function offensiveBoostTotal(pokemon) {
  return ["attack", "specialAttack", "speed"].reduce(
    (sum, stat) =>
      sum + Math.max(0, Number(pokemon.boosts?.[stat] ?? 0)),
    0,
  );
}

function canExploitSleepForAi(pokemon) {
  const usableMoveIds = new Set(
    (pokemon.moves ?? [])
      .filter(
        (move) =>
          move.pp > 0 &&
          !isMoveTemporarilyDisabled(pokemon, move),
      )
      .map((move) => cleanId(move.id)),
  );
  return usableMoveIds.has("sleeptalk") || usableMoveIds.has("snore");
}

function activeSwitchPressure(pokemon) {
  const maxHp = Math.max(1, Number(pokemon.stats?.hp ?? pokemon.hp ?? 1));
  const ignoresResidualDamage = activeAbility(pokemon) === "magicguard";
  const yawn = pokemon.volatiles?.yawn;
  const yawnTurns = Number(yawn?.turns ?? 0);
  const sleepExploitable = canExploitSleepForAi(pokemon);
  const saltCureDamage = pokemon.volatiles?.saltcure && !ignoresResidualDamage
    ? saltCureResidualDamage(pokemon)
    : 0;
  const toxicCounter =
    pokemon.status === "tox" && !ignoresResidualDamage
      ? Math.max(1, Number(pokemon.toxicCounter ?? 1))
      : 0;
  return JSON.parse(
    deriveResidualPressureJson(
      JSON.stringify({
        currentHp: pokemon.hp,
        maxHp,
        yawnActive: Boolean(yawn),
        yawnTurns,
        sleepExploitable,
        saltCureActive: Boolean(pokemon.volatiles?.saltcure),
        saltCureResidualDamage: saltCureDamage,
        toxicCounter,
        ignoresResidualDamage,
      }),
    ),
  );
}

function switchEventOnPreviousTurn(state, sideIndex, pokemon) {
  return state.events.find(
    (event) =>
      event.type === "switch" &&
      event.side === sideIndex &&
      event.turn === state.turn &&
      event.pokemon === pokemon.name,
  );
}

export function automaticSwitchCandidates(
  state,
  sideIndex,
  moveCandidates = [],
  difficulty = "expert",
  strategy = "balanced",
) {
  const side = state.sides[sideIndex];
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const current = activePokemon(state, sideIndex);
  const opponent = activePokemon(state, opponentSide);
  if (!current.fainted && isPokemonTrapped(state, sideIndex, current)) {
    return [];
  }
  const teamRoleAnalysis = simpleTeamAnalysis(state, sideIndex);
  const oneTurnSearchWeight = aiOneTurnSearchWeight(difficulty);
  const threatCounterMap = simpleThreatCounterMap(
    state,
    sideIndex,
    teamRoleAnalysis,
  );
  const teamRoleProgress = simpleTeamRoleProgress(
    state,
    sideIndex,
    teamRoleAnalysis,
    threatCounterMap,
  );
  const currentThreat = threatCounterMap.threats.find(
    (threat) => threat.enemySlot === state.sides[opponentSide].active + 1,
  );
  const moveSetupContext = moveCandidates.find(
    (candidate) => candidate.setupThreatEvaluation,
  );
  const switchSetupThreat = moveSetupContext
    ? {
        opponentSetupMoveCount: moveSetupContext.opponentSetupMoveCount,
        opponentSetupMoveIds: moveSetupContext.opponentSetupMoveIds,
        opponentSetupFirstTurnLikelihood:
          moveSetupContext.opponentSetupFirstTurnLikelihood,
        opponentLikelyFirstTurnSetup:
          moveSetupContext.opponentLikelyFirstTurnSetup,
        opponentSetupThreatTier:
          moveSetupContext.opponentSetupThreatTier,
        opponentSetupSweepRisk:
          moveSetupContext.opponentSetupSweepRisk,
        opponentSetupAnswerCount:
          moveSetupContext.opponentSetupAnswerCount,
        opponentSetupPunishOptions:
          moveSetupContext.opponentSetupPunishOptions,
        setupThreatEvaluation: moveSetupContext.setupThreatEvaluation,
        oneMoreTurnUnmanageable:
          moveSetupContext.oneMoreTurnUnmanageable,
      }
    : aiOpponentSetupThreatProfile({
        state,
        sideIndex,
        defenderSide: opponentSide,
        attacker: current,
        defender: opponent,
        opponentRoleProfile: analyzeTeamProfile([
          aiRoleAnalysisMember(opponent),
        ]).roles[0],
        threatEntry: currentThreat,
      });
  const currentAttack = bestAiAttackProfile(
    state,
    sideIndex,
    current,
    opponentSide,
    opponent,
  );
  const currentIncoming = bestAiAttackProfile(
    state,
    opponentSide,
    opponent,
    sideIndex,
    current,
  );
  const currentHpPercent = current.hp / current.stats.hp;
  const currentAbility = activeAbility(current);
  const regeneratorRecoveryHp =
    currentAbility === "regenerator"
      ? Math.min(
          current.stats.hp - current.hp,
          Math.max(1, Math.floor(current.stats.hp / 3)),
        )
      : 0;
  const regeneratorRecoveryRatio =
    regeneratorRecoveryHp / Math.max(1, current.stats.hp);
  const currentIncomingRatio =
    currentIncoming.expectedDamage / current.stats.hp;
  const currentOutgoingRatio =
    opponent.hp > 0 ? currentAttack.expectedDamage / opponent.hp : 0;
  const bestMove = moveCandidates[0] ?? null;
  const switchPressure = activeSwitchPressure(current);
  const currentActsFirst =
    currentAttack.priority > currentIncoming.priority ||
    (currentAttack.priority === currentIncoming.priority &&
      (Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0
        ? effectiveSpeed(current, state, sideIndex) <
          effectiveSpeed(opponent, state, opponentSide)
        : effectiveSpeed(current, state, sideIndex) >
          effectiveSpeed(opponent, state, opponentSide)));
  const currentSurvives =
    currentIncoming.expectedDamage < current.hp;
  const bestMoveAccuracy =
    bestMove?.accuracy === true
      ? 1
      : Number(bestMove?.accuracy ?? 100) / 100;
  const safeImmediateKoAvailable =
    bestMove?.koChance === "guaranteed" &&
    bestMoveAccuracy >= 0.85 &&
    (currentActsFirst || currentSurvives);
  const probabilisticActionRoute = moveCandidates.some(
      (candidate) =>
        Number(candidate.actionBeforeThreatProbability ?? 0) >= 0.5 &&
        Number(candidate.opponentKnockoutBeforeActionProbability ?? 0) < 0.5,
    );
  const safeActionDenialAvailable =
    opponent.dynamaxTurns <= 0 &&
    moveCandidates.some((candidate) => {
      const guaranteedFlinch =
        cleanId(candidate.id) === "fakeout" ||
        cleanId(candidate.volatileStatus) === "flinch" ||
        candidate.secondaries?.some(
          (effect) =>
            cleanId(effect.volatileStatus) === "flinch" &&
            Number(effect.chance ?? 100) >= 100,
        );
      const accuracy =
        candidate.accuracy === true
          ? 1
          : Number(candidate.accuracy ?? 100) / 100;
      return (
        guaranteedFlinch &&
        accuracy >= 0.95 &&
        Number(candidate.expectedDamage ?? 0) > 0 &&
        Number(candidate.actionBeforeThreatProbability ?? 0) >= 0.95
      );
    });
  const safePivotAvailable = current.moves.some(
    (move, index) =>
      move.pp > 0 &&
      aiDisplayMoveData(current, move).selfSwitch &&
      Number(moveCandidates.find((candidate) => candidate.slot === index + 1)?.score ?? -Infinity) >
        0,
  );
  const recentSwitchEvent = switchEventOnPreviousTurn(
    state,
    sideIndex,
    current,
  );
  const recentSwitch = Boolean(recentSwitchEvent);
  const oneTurnStateBefore =
    oneTurnSearchWeight > 0
      ? simpleBattleStateValueSnapshot(
          state,
          sideIndex,
          teamRoleAnalysis,
          threatCounterMap,
        )
      : null;
  const lowestResidualIndex = lowestResidualValuePokemonIndex(
    state,
    sideIndex,
  );
  const aceRecoveryPlan =
    lowestResidualIndex >= 0 && lowestResidualIndex !== side.active
      ? bestAceRecoverySacrificePlan(
          state,
          sideIndex,
          strategy,
          lowestResidualIndex,
        )
      : null;

  return side.team
    .map((pokemon, index) => {
      const slot = index + 1;
      if (
        index === side.active ||
        pokemon.fainted ||
        pokemon.hp <= 0
      ) {
        return null;
      }
      const hazardDamage = predictedEntryHazardDamage(state, sideIndex, pokemon);
      const hpAfterHazards = Math.max(0, pokemon.hp - hazardDamage);
      const hpPercent = hpAfterHazards / pokemon.stats.hp;
      const targetAttack = bestAiAttackProfile(
        state,
        sideIndex,
        pokemon,
        opponentSide,
        opponent,
      );
      const targetIncoming = bestAiAttackProfile(
        state,
        opponentSide,
        opponent,
        sideIndex,
        pokemon,
      );
      const targetIncomingRatio =
        targetIncoming.expectedDamage / pokemon.stats.hp;
      const targetActsFirst =
        targetAttack.priority > targetIncoming.priority ||
        (targetAttack.priority === targetIncoming.priority &&
          (Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0
            ? effectiveSpeed(pokemon, state, sideIndex) <
              effectiveSpeed(opponent, state, opponentSide)
            : effectiveSpeed(pokemon, state, sideIndex) >
              effectiveSpeed(opponent, state, opponentSide)));
      const hpAfterSwitchIn = Math.max(
        0,
        hpAfterHazards - targetIncoming.expectedDamage,
      );
      const survivesSwitchIn = hpAfterSwitchIn > 0;
      const projectedMoveCandidates = survivesSwitchIn
        ? projectedSwitchMoveCandidates(
            state,
            sideIndex,
            pokemon,
            opponentSide,
            opponent,
            hpAfterSwitchIn,
            difficulty,
            strategy,
          )
        : [];
      const projectedBestMove = projectedMoveCandidates[0] ?? null;
      const projectedKnockoutBeforeActionProbability = Number(
        projectedBestMove?.opponentKnockoutBeforeActionProbability ?? 1,
      );
      const projectedExpectedDamage = Number(
        projectedBestMove?.expectedDamage ?? targetAttack.expectedDamage,
      );
      const targetOutgoingRatio =
        opponent.hp > 0 ? projectedExpectedDamage / opponent.hp : 0;
      const canReachNextAction =
        survivesSwitchIn &&
        projectedKnockoutBeforeActionProbability < 0.75;
      const canKoOnNextAction =
        canReachNextAction &&
        projectedBestMove?.koChance === "guaranteed";
      const targetRoleProfile = teamRoleAnalysis.roles[index];
      const targetRoleProgress = teamRoleProgress[index];
      const batonPassSetupPlan = aiBatonPassSetupPlan(
        state,
        sideIndex,
        pokemon,
      );
      const batonPassBoostedPokemon =
        batonPassSetupPlan.batonPassTargetAvailable === true
          ? boostedPokemonForAi(
              pokemon,
              batonPassSetupPlan.setupBoosts,
            )
          : pokemon;
      const batonPassActsFirst =
        Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0
          ? effectiveSpeed(
              batonPassBoostedPokemon,
              state,
              sideIndex,
            ) <
            effectiveSpeed(opponent, state, opponentSide)
          : effectiveSpeed(
              batonPassBoostedPokemon,
              state,
              sideIndex,
            ) >
            effectiveSpeed(opponent, state, opponentSide);
      const batonPassIncomingDamageRatio =
        targetIncoming.expectedDamage / Math.max(1, hpAfterSwitchIn);
      const batonPassRequiredHits = batonPassActsFirst ? 1 : 2;
      const batonPassSurvivesPlan =
        hpAfterSwitchIn -
          targetIncoming.expectedDamage * batonPassRequiredHits >
        0;
      const batonPassSafeSetupTurns =
        targetIncoming.expectedDamage <= 0
          ? 2
          : Math.max(
              0,
              Math.min(
                2,
                Math.floor(
                  (hpAfterSwitchIn - 1) /
                    targetIncoming.expectedDamage,
                ) - (batonPassActsFirst ? 0 : 1),
              ),
            );
      const batonPassSetupOpportunity =
        batonPassSetupPlan.batonPassTargetAvailable === true &&
        Number(batonPassSetupPlan.setupBoostTotal ?? 0) > 0 &&
        survivesSwitchIn &&
        batonPassSurvivesPlan &&
        batonPassSafeSetupTurns >= 1 &&
        batonPassIncomingDamageRatio <= 0.45 &&
        positiveBoostTotal(opponent) <= 1 &&
        Number(switchSetupThreat.opponentSetupSweepRisk ?? 0) < 0.42;
      const preservationProfile = threatCounterMap.mustPreserveResources.find(
        (resource) => resource.slot === slot,
      );
      const currentThreatResource = [
        ...(currentThreat?.counters ?? []),
        ...(currentThreat?.softChecks ?? []),
        ...(currentThreat?.revengeKillers ?? []),
      ].find((resource) => resource.slot === slot);
      const switchMatchupEvaluation = JSON.parse(
        deriveSharedSwitchMatchupObservationJson(
          JSON.stringify({
            currentHp: current.hp,
            currentMaximumHp: current.stats.hp,
            targetHp: pokemon.hp,
            targetMaximumHp: pokemon.stats.hp,
            opponentHp: opponent.hp,
            currentIncomingDamage: currentIncoming.expectedDamage,
            targetIncomingDamage: targetIncoming.expectedDamage,
            currentOutgoingDamage: currentAttack.expectedDamage,
            targetOutgoingDamage: projectedExpectedDamage,
            targetHazardDamage: hazardDamage,
            currentAttackPriority: currentAttack.priority,
            opponentAttackPriority: currentIncoming.priority,
            currentSpeed: effectiveSpeed(current, state, sideIndex),
            opponentSpeed: effectiveSpeed(opponent, state, opponentSide),
            trickRoomActive:
              Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0,
            probabilisticActionRoute,
          }),
        ),
      );
      const { facts: switchMatchupFacts, result: switchMatchup } =
        switchMatchupEvaluation;
      const {
        matchupValue,
        emergencyEscape,
        noEffectiveMoveEscape,
      } = switchMatchup;
      const currentCanReachAction =
        switchMatchupFacts.currentCanReachAction;

      const fieldSynergy = fieldSwitchSynergy(
        state,
        sideIndex,
        pokemon,
        opponent,
      );
      const candidate = {
        slot,
        id: pokemon.id,
        switchId: pokemon.id,
        name: `${pokemon.name}(으)로 교체`,
        active: false,
        fainted: false,
        forceSwitch: false,
        hpPercent: hpAfterSwitchIn / pokemon.stats.hp,
        expectedDamage: Math.min(opponent.hp, projectedExpectedDamage) * 0.4,
        matchupValue,
        currentIncomingDamageRatio: currentIncomingRatio,
        targetIncomingDamageRatio: targetIncomingRatio,
        currentOutgoingDamageRatio: currentOutgoingRatio,
        targetOutgoingDamageRatio: targetOutgoingRatio,
        currentStatus: current.status,
        currentAbility,
        currentHpPercent,
        regeneratorRecoveryHp,
        regeneratorRecoveryRatio,
        targetStatus: pokemon.status,
        currentPositiveBoosts: positiveBoostTotal(current),
        opponentPositiveBoosts: positiveBoostTotal(opponent),
        opponentOffensiveBoosts: offensiveBoostTotal(opponent),
        speedAdvantage: targetActsFirst,
        switchInExpectedDamage: targetIncoming.expectedDamage,
        switchInThreatMoveId: targetIncoming.moveId,
        switchInDamageRatio:
          targetIncoming.expectedDamage / pokemon.stats.hp,
        hpAfterSwitchIn,
        survivesSwitchIn,
        canReachNextAction,
        canKoOnNextAction,
        projectedBestMoveId: projectedBestMove?.id ?? targetAttack.moveId,
        projectedBestMoveName: projectedBestMove?.name ?? targetAttack.moveId,
        projectedBestMoveScore: projectedBestMove?.score,
        projectedKnockoutBeforeActionProbability,
        targetPrimaryRole: targetRoleProfile?.primaryRole ?? "support",
        targetRoleScore: targetRoleProfile?.roles?.[0]?.score ?? 0,
        targetRoleComplete: targetRoleProgress?.roleComplete === true,
        targetExpendableResource:
          targetRoleProgress?.expendableResource === true,
        targetCompletedRoles: targetRoleProgress?.completedRoles ?? [],
        targetRemainingRoles: targetRoleProgress?.remainingRoles ?? [],
        targetRoleProgressReasons: targetRoleProgress?.reasons ?? [],
        targetAceScore: targetRoleProfile?.aceScore ?? 0,
        targetAceQualified:
          targetRoleProfile?.aceProfile?.qualifies === true,
        targetBatonPassSupport:
          targetRoleProfile?.batonPassProfile?.qualifies === true,
        batonPassSetupOpportunity,
        batonPassSafeSetupTurns,
        batonPassIncomingDamageRatio:
          Math.round(batonPassIncomingDamageRatio * 1000) / 1000,
        batonPassActsFirst,
        ...batonPassSetupPlan,
        mustPreserveResource: Boolean(preservationProfile),
        mustPreserveFor:
          preservationProfile?.threats.map((threat) => threat.species) ?? [],
        preservationTargetIsCurrent:
          preservationProfile?.threats.some(
            (threat) => threat.enemySlot === state.sides[opponentSide].active + 1,
          ) ?? false,
        currentThreatClassification:
          currentThreatResource?.classification ?? null,
        priorityKo:
          Number(projectedBestMove?.priority ?? targetAttack.priority) >
            targetIncoming.priority &&
          projectedBestMove?.koChance === "guaranteed",
        immediateKoBeforeOpponent:
          canReachNextAction &&
          projectedBestMove?.koChance === "guaranteed",
        safeImmediateKoAvailable,
        currentCanReachAction,
        currentBestMoveScore: Number(bestMove?.score ?? 0),
        safeActionDenialAvailable,
        safePivotAvailable,
        switchedLastTurn: recentSwitch,
        forcedReplacement:
          recentSwitchEvent?.forced === true ||
          recentSwitchEvent?.selection === "matchup_score" ||
          recentSwitchEvent?.selection === "faint_replacement",
        dynamaxActive: current.dynamaxTurns > 0,
        dynamaxRemainingTurns: current.dynamaxTurns,
        dynamaxEscapeJustified: emergencyEscape,
        hazardDamage,
        hazardDamageRatio: hazardDamage / pokemon.stats.hp,
        emergencyEscape,
        noEffectiveMoveEscape,
        ...switchPressure,
        ...switchSetupThreat,
        ...fieldSynergy,
        aceRecoveryPlanEligible:
          aceRecoveryPlan?.eligible === true &&
          index === lowestResidualIndex,
        aceRecoveryPlan:
          index === lowestResidualIndex ? aceRecoveryPlan : null,
      };
      const oneTurnEvaluation = oneTurnStateBefore
        ? evaluateOneTurnBattleState(
            oneTurnStateBefore,
            simpleSwitchOneTurnOutcome({
              current,
              target: pokemon,
              candidate,
              targetRoleProfile,
              preservationProfile,
            }),
          )
        : null;
      const evaluated = oneTurnEvaluation
        ? {
            ...candidate,
            oneTurnEvaluation:
              compactOneTurnEvaluation(oneTurnEvaluation),
            battleStateValueDelta: oneTurnEvaluation.delta,
            oneTurnSearchWeight,
          }
        : candidate;
      const selected =
        difficulty === "novice"
          ? {
              ...evaluated,
              score:
                Math.round(scoreAiSwitchCandidate(evaluated, strategy) * 100) /
                100,
            }
          : selectAiSwitchCandidate([evaluated], {
              difficulty,
              strategy,
              rng: createAiRng(state.seed, sideIndex, state.turn * 23 + slot),
            });
      return selected;
    })
    .filter(Boolean)
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

function isConfiguredTeraPokemon(pokemon) {
  return pokemon?.gimmicks?.teraConfigured === true;
}

function canUseTeraFallback(side) {
  const configured = side.team.filter(isConfiguredTeraPokemon);
  return (
    configured.length > 0 &&
    configured.every((pokemon) => pokemon.fainted || pokemon.hp <= 0)
  );
}

export function canPokemonUseTerastallization(
  state,
  sideIndex,
  pokemon = activePokemon(state, sideIndex),
) {
  if (!state?.sides?.[sideIndex] || !pokemon) return false;
  return (
    isConfiguredTeraPokemon(pokemon) ||
    canUseTeraFallback(state.sides[sideIndex])
  );
}

function canUseDynamaxFallback(side) {
  const configured = side.team.filter(isConfiguredDynamaxPokemon);
  return (
    configured.length > 0 &&
    configured.every((pokemon) => pokemon.fainted || pokemon.hp <= 0)
  );
}

function hasLivingConfiguredDynamaxOther(side, active) {
  return side.team.some(
    (pokemon) =>
      pokemon !== active &&
      isConfiguredDynamaxPokemon(pokemon) &&
      !pokemon.fainted &&
      pokemon.hp > 0,
  );
}

function projectedSimpleStateForGimmick(state, sideIndex, gimmick) {
  const side = state.sides[sideIndex];
  const source = activePokemon(state, sideIndex);
  const projectedPokemon = {
    ...source,
    types: [...source.types],
    originalTypes: [...(source.originalTypes ?? source.types)],
    stats: { ...source.stats },
    boosts: { ...source.boosts },
    abilityState: { ...(source.abilityState ?? {}) },
  };
  const projectedState = {
    ...state,
    events: [...(state.events ?? [])],
    field: {
      ...state.field,
      weather: state.field?.weather ? { ...state.field.weather } : null,
      terrain: state.field?.terrain ? { ...state.field.terrain } : null,
      pseudoWeather: { ...(state.field?.pseudoWeather ?? {}) },
    },
  };

  if (gimmick === "mega") {
    const stone = projectedPokemon.gimmicks?.megaStone ?? {};
    const megaForm = cleanDisplayName(stone.form);
    if (megaForm) {
      projectedPokemon.baseSpeciesName =
        projectedPokemon.baseSpeciesName || projectedPokemon.name;
      projectedPokemon.name = megaForm;
      projectedPokemon.id = cleanId(megaForm);
    }
    if (Array.isArray(stone.types) && stone.types.length > 0) {
      projectedPokemon.types = stone.types
        .map(String)
        .filter(Boolean)
        .slice(0, 2);
      projectedPokemon.originalTypes = projectedPokemon.types.slice();
    }
    if (stone.ability) projectedPokemon.ability = stone.ability;
    if (stone.stats) {
      for (const [stat, value] of Object.entries(stone.stats)) {
        projectedPokemon.stats[stat] = value;
      }
    } else {
      for (const stat of [
        "attack",
        "defence",
        "specialAttack",
        "specialDefence",
        "speed",
      ]) {
        projectedPokemon.stats[stat] = Math.max(
          1,
          Math.floor(projectedPokemon.stats[stat] * 1.1),
        );
      }
    }
    projectedPokemon.megaEvolved = true;
  } else if (gimmick === "terastallize") {
    applySpeciesTerastallization(
      projectedState,
      sideIndex,
      projectedPokemon,
    );
  }

  const projectedTeam = side.team.map((member, index) =>
    index === side.active ? projectedPokemon : member,
  );
  return {
    ...projectedState,
    sides: state.sides.map((candidateSide, index) =>
      index === sideIndex
        ? { ...candidateSide, team: projectedTeam }
        : candidateSide,
    ),
  };
}

function projectedSimpleGimmickCandidate({
  state,
  sideIndex,
  gimmick,
  difficulty,
  strategy,
  baseMove,
  configured = false,
}) {
  const projectedState = projectedSimpleStateForGimmick(
    state,
    sideIndex,
    gimmick,
  );
  const candidates = automaticMoveCandidates(
    projectedState,
    sideIndex,
    strategy,
    difficulty,
    "",
    { gimmickResourceCost: 1 },
  );
  const selectedMove = selectAiMoveCandidate(candidates, {
    difficulty,
    strategy,
    rng: createAiRng(
      state.seed,
      sideIndex,
      state.turn * 29 + (gimmick === "mega" ? 3 : 7),
    ),
  });
  if (!selectedMove) {
    return {
      candidate: null,
      moveCandidates: candidates,
      selectedMove: null,
    };
  }
  const projectedCandidate = scoreAiProjectedGimmickCandidate({
    id: gimmick,
    selectedMove,
    baseMove,
    configured,
  });
  return {
    candidate:
      gimmick === "terastallize"
        ? applyTeraDefensiveScore(
            projectedCandidate,
            teraDefensiveProjection(state, projectedState, sideIndex),
            {
              baseMove,
              selectedMove,
              resourceOpportunity: teraResourceOpportunity(state, sideIndex),
            },
          )
        : projectedCandidate,
    moveCandidates: candidates,
    selectedMove,
  };
}


function simpleSearchPolicyCandidates({
  moveCandidates = [],
  switchCandidates = [],
  itemCandidates = [],
  gimmick = null,
  gimmickMove = null,
}) {
  const candidates = [
    ...moveCandidates.map((candidate) => ({
      ...candidate,
      policyKind: "move",
      policyCommand: { move: candidate.slot },
    })),
    ...switchCandidates.map((candidate) => ({
      ...candidate,
      policyKind: "switch",
      policyCommand: { switch: candidate.slot },
    })),
    ...itemCandidates.map((candidate) => ({
      ...candidate,
      policyKind: "item",
      policyCommand: { item: candidate.id },
    })),
    ...(gimmick && gimmickMove
      ? [
          {
            ...gimmickMove,
            policyKind: "gimmick",
            policyCommand: { move: gimmickMove.slot, gimmick },
          },
        ]
      : []),
  ];
  const sourceById = new Map();
  for (const candidate of candidates) {
    if (candidate.legal === false || candidate.disabled === true) continue;
    const key = simpleCommandKey(candidate.policyCommand);
    const previous = sourceById.get(key);
    if (!previous || Number(candidate.score ?? 0) > Number(previous.score ?? 0)) {
      sourceById.set(key, candidate);
    }
  }
  const actions = JSON.parse(
    generateSharedSearchActionsJson(
      JSON.stringify(candidates.map(toSharedSearchCandidateObservation)),
    ),
  );
  return actions.map((action) => ({
    ...sourceById.get(action.id),
    sharedSearchAction: action,
  }));
}


function simpleSearchWinProbability(state, sideIndex) {
  return estimateBattleWinProbability(
    simpleBattleStateValueSnapshot(state, sideIndex),
  ).probability;
}




function toSharedSearchCandidateObservation(candidate) {
  const sharedFinite = (value, fallback = 0) => {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  };
  return {
    id: simpleCommandKey(candidate.policyCommand),
    kind: candidate.policyKind ?? "move",
    score: sharedFinite(candidate.score),
    successProbability: Math.max(
      0,
      Math.min(1, sharedFinite(candidate.protectSuccessProbability, 1)),
    ),
    expectedDamage: sharedFinite(candidate.expectedDamage),
    nonConsecutive: NON_CONSECUTIVE_MOVES.has(cleanId(candidate.id)),
    statusMove: candidate.category === "Status",
    guaranteedKnockout: candidate.koChance === "guaranteed",
    opponentKnockoutBeforeActionProbability: sharedFinite(
      candidate.opponentKnockoutBeforeActionProbability,
    ),
    heuristicSelected: candidate.searchHeuristicSelected === true,
    legal: candidate.legal !== false,
    disabled: candidate.disabled === true,
  };
}

function toSharedSearchAction(candidate) {
  const generated = candidate.sharedSearchAction ?? JSON.parse(
    generateSharedSearchActionsJson(
      JSON.stringify([toSharedSearchCandidateObservation(candidate)]),
    ),
  )[0];
  return {
    ...generated,
    heuristicSelected: candidate.searchHeuristicSelected === true,
  };
}

const SEARCH_HAZARD_IDS = ["stealthrock", "spikes", "toxicspikes", "stickyweb"];
const SEARCH_RANK_IDS = ["attack", "specialAttack", "defence", "specialDefence", "speed"];
const SEARCH_PERSISTENT_FIELD_IDS = new Set(["desolateland", "primordialsea", "deltastream"]);

function toSharedTimedEffect(effect) {
  if (!effect?.id) return null;
  const id = cleanId(effect.id);
  const persistent = effect.turns == null || SEARCH_PERSISTENT_FIELD_IDS.has(id);
  return {
    id,
    turns: persistent ? 2147483647 : Math.max(0, Number(effect.turns ?? 0)),
    persistent,
  };
}

function toSharedSearchCombatProfile(pokemon, sideIndex, slot, override = null) {
  const source = override ?? pokemon;
  const stats = source.stats ?? pokemon.stats ?? {};
  return {
    id: cleanId(source.id ?? source.name ?? pokemon.id),
    ability: cleanId(source.ability ?? pokemon.ability),
    types: [...(source.types ?? pokemon.types ?? [])],
    stats: {
      hp: Math.max(1, Math.round(Number(stats.hp ?? pokemon.stats?.hp ?? 1))),
      attack: Math.max(1, Math.round(Number(stats.attack ?? pokemon.stats?.attack ?? 1))),
      defence: Math.max(1, Math.round(Number(stats.defence ?? pokemon.stats?.defence ?? 1))),
      specialAttack: Math.max(1, Math.round(Number(stats.specialAttack ?? pokemon.stats?.specialAttack ?? 1))),
      specialDefence: Math.max(1, Math.round(Number(stats.specialDefence ?? pokemon.stats?.specialDefence ?? 1))),
      speed: Math.max(1, Math.round(Number(stats.speed ?? pokemon.stats?.speed ?? 1))),
    },
    moveSourceSide: sideIndex,
    moveSourceSlot: slot,
  };
}

function searchBaseProfile(pokemon, sideIndex, slot) {
  pokemon.searchBaseMoves ??= structuredClone(pokemon.moves ?? []);
  pokemon.searchBaseProfile ??= toSharedSearchCombatProfile(pokemon, sideIndex, slot);
  return pokemon.searchBaseProfile;
}

function searchFormProfiles(pokemon, sideIndex, slot) {
  const result = {};
  for (const [key, form] of Object.entries(pokemon.speciesForms ?? {})) {
    if (!form) continue;
    const profile = toSharedSearchCombatProfile(pokemon, sideIndex, slot, form);
    result[cleanId(form.id ?? key)] = profile;
  }
  return result;
}

function currentSearchProfile(state, pokemon, sideIndex, slot) {
  const profile = toSharedSearchCombatProfile(pokemon, sideIndex, slot);
  const remembered = pokemon.searchCurrentProfile;
  if (remembered) {
    profile.moveSourceSide = Number(remembered.moveSourceSide);
    profile.moveSourceSlot = Number(remembered.moveSourceSlot);
    return profile;
  }
  const transformedTarget = pokemon.volatiles?.transform?.target;
  if (transformedTarget) {
    const opponentSide = sideIndex === 0 ? 1 : 0;
    const targetSlot = state.sides[opponentSide].team.findIndex(
      (candidate) => candidate.name === transformedTarget,
    );
    if (targetSlot >= 0) {
      profile.moveSourceSide = opponentSide;
      profile.moveSourceSlot = targetSlot;
    }
  }
  return profile;
}

function toSharedSearchProjectionState(state) {
  const baseProfiles = state.sides.map((side, sideIndex) =>
    side.team.map((pokemon, slot) => searchBaseProfile(pokemon, sideIndex, slot)),
  );
  return {
    turn: Number(state.turn ?? 0),
    active: state.sides.map((side) => Number(side.active ?? 0)),
    hp: state.sides.map((side) => side.team.map((pokemon) => Number(pokemon.hp ?? 0))),
    maxHp: state.sides.map((side) => side.team.map((pokemon) => Number(pokemon.maxHp ?? pokemon.hp ?? 0))),
    gimmicksRemaining: state.sides.map((side) =>
      !Object.values(side.usedGimmicks ?? {}).some(Boolean),
    ),
    itemCounts: state.sides.map(() => []),
    hazards: state.sides.map((side) =>
      SEARCH_HAZARD_IDS.map((id) => Number(side.conditions?.[id]?.layers ?? 0)),
    ),
    pressures: state.sides.map((side) =>
      side.team.map((pokemon) => ({
        yawn: Boolean(pokemon.volatiles?.yawn),
        yawnTurns: Number(pokemon.volatiles?.yawn?.turns ?? 0),
        saltCure: Boolean(pokemon.volatiles?.saltcure),
        toxicCounter:
          pokemon.status === "tox" ? Math.max(1, Number(pokemon.toxicCounter ?? 1)) : 0,
        sleepTurns: pokemon.status === "slp" ? Math.max(1, Number(pokemon.statusTurns ?? 1)) : 0,
      })),
    ),
    ranks: state.sides.map((side) =>
      side.team.map((pokemon) =>
        SEARCH_RANK_IDS.map((stat) => Number(pokemon.boosts?.[stat] ?? 0)),
      ),
    ),
    heldItems: state.sides.map((side) =>
      side.team.map((pokemon) => cleanId(pokemon.item)),
    ),
    abilityStates: state.sides.map((side) =>
      side.team.map((pokemon) => [
        ...Object.entries(pokemon.abilityState ?? {})
          .filter(([, value]) => value === true)
          .map(([key]) => key),
        ...(pokemon.volatiles?.illusion ? ["illusion"] : []),
        ...(pokemon.abilityState?.gulpMissileForm
          ? [cleanId(pokemon.abilityState.gulpMissileForm)]
          : []),
      ].filter((value, index, values) => values.indexOf(value) === index)),
    ),
    field: {
      weather: toSharedTimedEffect(state.field?.weather),
      terrain: toSharedTimedEffect(state.field?.terrain),
      pseudoWeather: Object.fromEntries(
        Object.entries(state.field?.pseudoWeather ?? {})
          .map(([id, effect]) => [cleanId(id), toSharedTimedEffect(effect)])
          .filter(([, effect]) => effect),
      ),
    },
    sideConditions: state.sides.map((side) =>
      Object.fromEntries(
        Object.entries(side.conditions ?? {})
          .filter(([id, condition]) =>
            !SEARCH_HAZARD_IDS.includes(cleanId(id)) && Number(condition?.turns ?? 0) > 0,
          )
          .map(([id, condition]) => [cleanId(id), toSharedTimedEffect(condition)])
          .filter(([, effect]) => effect),
      ),
    ),
    baseProfiles,
    profiles: state.sides.map((side, sideIndex) =>
      side.team.map((pokemon, slot) =>
        currentSearchProfile(state, pokemon, sideIndex, slot),
      ),
    ),
    formProfiles: state.sides.map((side, sideIndex) =>
      side.team.map((pokemon, slot) => searchFormProfiles(pokemon, sideIndex, slot)),
    ),
  };
}

function projectedSearchAction(state, sideIndex, candidate) {
  const command = candidate.policyCommand ?? {};
  const pokemon = activePokemon(state, sideIndex);
  const switchSlot = Number.isInteger(Number(command.switch)) ? Number(command.switch) - 1 : -1;
  const switchTarget = state.sides[sideIndex].team[switchSlot];
  const moveSlot = Number(command.move ?? 0);
  const move = moveSlot > 0 ? pokemon.moves?.[moveSlot - 1] : null;
  const moveId = cleanId(move?.id ?? candidate.id);
  const hazardIndex = SEARCH_HAZARD_IDS.findIndex((id) =>
    id === moveId ||
    (id === "stealthrock" && moveId === "stoneaxe") ||
    (id === "spikes" && moveId === "ceaselessedge"),
  );
  const pressure = moveId === "yawn"
    ? "yawn"
    : moveId === "saltcure"
      ? "saltcure"
      : moveId === "toxic"
        ? "toxic"
        : "";
  const batonPassTarget = moveId === "batonpass"
    ? state.sides[sideIndex].team.findIndex(
        (member, index) => index !== state.sides[sideIndex].active && !member.fainted && member.hp > 0,
      )
    : -1;
  const exactConditionalFailure =
    candidate.conditionalPriorityRepeatFailure === true ||
    candidate.upperHandExactOutcome === "failure";
  const successProbability = exactConditionalFailure
    ? 0
    : candidate.upperHandExactOutcome
      ? Number(candidate.upperHandSuccessProbability ?? 1)
      : 1;
  const opponent = activePokemon(state, sideIndex === 0 ? 1 : 0);
  const projectedDamage = Number(candidate.expectedDamage ?? 0);
  const projectedEffectiveness = move
    ? moveEffectiveness(move, opponent.types, pokemon, opponent)
    : 1;
  const projectedAttackerItemConsumed = Boolean(
    move && cleanId(pokemon.item) === `${cleanId(move.type)}gem`,
  );
  const projectedResistBerryConsumed = Boolean(
    move &&
    ["chartiberry", "colburberry", "yacheberry"].includes(cleanId(opponent.item)) &&
    heldItemType(opponent.item) === move.type &&
    projectedEffectiveness > 1,
  );
  const projectedDefenderItemConsumed = projectedResistBerryConsumed ||
    (cleanId(opponent.item) === "weaknesspolicy" && projectedEffectiveness > 1) ||
    (cleanId(opponent.item) === "marangaberry" && move?.category === "Special");
  const hitReactions = move && projectedDamage > 0
    ? JSON.parse(
        evaluateSharedHitReactionsJson(
          JSON.stringify({
            damage: projectedDamage,
            attackerAlive: pokemon.hp > 0,
            defenderAlive: opponent.hp - projectedDamage > 0,
            attackerAbility: activeAbility(pokemon),
            defenderAbility: activeAbility(opponent),
            defenderItem: opponent.item,
            moveId,
            moveType: move.type,
            moveCategory: move.category,
            superEffective: projectedEffectiveness > 1,
            contactPunishment: triggersContactPunishment(pokemon, move),
            effectiveContact: makesEffectiveContact(pokemon, move),
            ignoresDefenderAbility: ignoresDefenderAbility(pokemon),
            attackerGrassType: pokemon.types.includes("Grass"),
            attackerOvercoat: activeAbility(pokemon) === "overcoat",
            moveIsMax: Boolean(move.isMaxMove),
            moveIsFuture: hasMoveFlag(move, "futuremove"),
            resolveRandom: false,
            attackerItem: pokemon.item,
            defenderHasIllusion: Boolean(opponent.volatiles?.illusion),
            defenderGulpMissileForm: opponent.abilityState?.gulpMissileForm ?? "",
            attackerItemRemovalBlocked:
              activeAbility(pokemon) === "stickyhold" &&
              !ignoresDefenderAbility(opponent),
            defenderItemRemovalBlocked:
              activeAbility(opponent) === "stickyhold" &&
              !ignoresDefenderAbility(pokemon),
            attackerItemConsumedOnHit: projectedAttackerItemConsumed,
            defenderItemConsumedOnHit: projectedResistBerryConsumed,
          }),
        ),
      ).reactions
    : [];
  const postHitInstructions = move && projectedDamage > 0
    ? sharedPostHitOutcome(
        move,
        projectedAttackerItemConsumed ? { ...pokemon, item: "" } : pokemon,
        projectedDefenderItemConsumed ? { ...opponent, item: "" } : opponent,
        Math.round(projectedDamage),
        1,
      ).instructions
    : [];
  const switchPhase = switchTarget
    ? sharedSwitchPhaseOutcome(state, sideIndex, switchTarget, pokemon)
    : null;
  return {
    sourceCandidate: candidate,
    action: toSharedSearchAction(candidate),
    side: sideIndex,
    switchSlot,
    itemIndex: Number(candidate.itemIndex ?? -1),
    itemTargetSlot: command.item
      ? Math.max(0, Number(command.itemTarget ?? state.sides[sideIndex].active + 1) - 1)
      : -1,
    healing: Number(candidate.healing ?? candidate.healAmount ?? 0),
    damage: Number(candidate.expectedDamage ?? 0),
    successProbability: Math.max(0, Math.min(1, successProbability)),
    hazardIndex,
    pressure,
    selfBoosts: move?.selfBoosts ?? candidate.selfBoosts ?? {},
    batonPassTarget,
    consumesGimmick: Boolean(command.gimmick),
    weather: cleanId(move?.weather ?? candidate.weather),
    terrain: cleanId(move?.terrain ?? candidate.terrain),
    pseudoWeather: cleanId(move?.pseudoWeather ?? candidate.pseudoWeather),
    sideCondition: cleanId(move?.sideCondition ?? candidate.sideCondition),
    fieldDuration: Number(candidate.fieldDuration ?? move?.duration ?? 0),
    sideConditionDuration: Number(candidate.sideConditionDuration ?? move?.duration ?? 0),
    hitReactions,
    postHitInstructions,
    switchPhase,
  };
}

function applyProjectedCombatProfile(pokemon, profile, sourcePokemon, sideIndex, slot) {
  if (!profile?.id) return;
  const preserveHp = profile.moveSourceSide !== sideIndex || profile.moveSourceSlot !== slot;
  const previousMaximumHp = Number(pokemon.stats?.hp ?? pokemon.hp ?? 1);
  const previousDamage = Math.max(0, previousMaximumHp - Number(pokemon.hp ?? 0));
  pokemon.id = profile.id;
  pokemon.ability = cleanId(profile.ability);
  pokemon.types = [...(profile.types ?? pokemon.types ?? [])];
  pokemon.stats = {
    ...pokemon.stats,
    ...profile.stats,
    hp: preserveHp ? previousMaximumHp : Number(profile.stats?.hp ?? previousMaximumHp),
  };
  if (!preserveHp && pokemon.hp > 0) {
    pokemon.hp = Math.max(1, pokemon.stats.hp - previousDamage);
  }
  const sourceIsSelf = profile.moveSourceSide === sideIndex && profile.moveSourceSlot === slot;
  const sourceMoves = sourceIsSelf
    ? pokemon.searchBaseMoves
    : sourcePokemon?.moves;
  if (sourceMoves) pokemon.moves = structuredClone(sourceMoves);
  pokemon.searchCurrentProfile = { ...profile };
}

function applySharedSearchProjection(state, projection, projectedActions) {
  const next = structuredClone(state);
  const profileSources = next.sides.map((side) =>
    side.team.map((pokemon) => ({ moves: structuredClone(pokemon.moves ?? []) })),
  );
  next.turn = projection.turn;
  next.field ??= { weather: null, terrain: null, pseudoWeather: {} };
  const restoreTimedEffect = (effect) => effect
    ? {
        id: effect.id,
        turns: effect.persistent ? null : Number(effect.turns ?? 0),
      }
    : null;
  next.field.weather = restoreTimedEffect(projection.field?.weather);
  next.field.terrain = restoreTimedEffect(projection.field?.terrain);
  next.field.pseudoWeather = Object.fromEntries(
    Object.entries(projection.field?.pseudoWeather ?? {})
      .map(([id, effect]) => [id, restoreTimedEffect(effect)])
      .filter(([, effect]) => effect),
  );
  for (const [sideIndex, side] of next.sides.entries()) {
    const previousActive = side.active;
    side.active = Number(projection.active?.[sideIndex] ?? side.active);
    for (const [slot, pokemon] of side.team.entries()) {
      const profile = projection.profiles?.[sideIndex]?.[slot];
      const sourcePokemon = profileSources?.[profile?.moveSourceSide]?.[profile?.moveSourceSlot];
      applyProjectedCombatProfile(pokemon, profile, sourcePokemon, sideIndex, slot);
      pokemon.hp = Math.max(0, Number(projection.hp?.[sideIndex]?.[slot] ?? pokemon.hp));
      pokemon.fainted = pokemon.hp <= 0;
      pokemon.item = projection.heldItems?.[sideIndex]?.[slot] ?? pokemon.item;
      const abilityStates = new Set(projection.abilityStates?.[sideIndex]?.[slot] ?? []);
      if (!abilityStates.has("illusion") && pokemon.volatiles?.illusion) {
        delete pokemon.volatiles.illusion;
        delete pokemon.displayName;
      }
      pokemon.abilityState ??= {};
      const gulpMissileForm = ["gulping", "gorging"].find((id) => abilityStates.has(id));
      if (gulpMissileForm) pokemon.abilityState.gulpMissileForm = gulpMissileForm;
      else delete pokemon.abilityState.gulpMissileForm;
      pokemon.boosts ??= {};
      for (const [rankIndex, stat] of SEARCH_RANK_IDS.entries()) {
        pokemon.boosts[stat] = Number(
          projection.ranks?.[sideIndex]?.[slot]?.[rankIndex] ?? pokemon.boosts[stat] ?? 0,
        );
      }
      const pressure = projection.pressures?.[sideIndex]?.[slot];
      if (pressure) {
        pokemon.volatiles ??= {};
        if (pressure.yawn) {
          pokemon.volatiles.yawn = {
            ...(pokemon.volatiles.yawn ?? { id: "yawn", source: "Yawn" }),
            turns: pressure.yawnTurns,
          };
        } else {
          delete pokemon.volatiles.yawn;
        }
        if (pressure.saltCure) {
          pokemon.volatiles.saltcure ??= { id: "saltcure", source: "Salt Cure" };
        } else {
          delete pokemon.volatiles.saltcure;
        }
        if (pressure.toxicCounter > 0) {
          pokemon.status = "tox";
          pokemon.toxicCounter = pressure.toxicCounter;
        } else if (pressure.sleepTurns > 0) {
          pokemon.status = "slp";
          pokemon.statusTurns = pressure.sleepTurns;
          delete pokemon.toxicCounter;
        } else if (pokemon.status === "slp") {
          pokemon.status = null;
          pokemon.statusTurns = 0;
        }
      }
    }
    for (const [hazardIndex, id] of SEARCH_HAZARD_IDS.entries()) {
      const layers = Number(projection.hazards?.[sideIndex]?.[hazardIndex] ?? 0);
      if (layers > 0) {
        side.conditions[id] = { ...(side.conditions[id] ?? { id }), layers };
      } else {
        delete side.conditions[id];
      }
    }
    for (const [id, condition] of Object.entries(side.conditions)) {
      if (!SEARCH_HAZARD_IDS.includes(cleanId(id)) && condition?.turns != null) {
        delete side.conditions[id];
      }
    }
    for (const [id, effect] of Object.entries(projection.sideConditions?.[sideIndex] ?? {})) {
      side.conditions[id] = {
        ...(side.conditions[id] ?? { id }),
        turns: effect.persistent ? null : Number(effect.turns ?? 0),
      };
    }
    if (side.active !== previousActive) side.team[side.active].activeTurns = 0;
    const action = projectedActions[sideIndex];
    const gimmick = action?.sourceCandidate?.policyCommand?.gimmick;
    if (gimmick) {
      side.usedGimmicks ??= {};
      side.usedGimmicks[gimmick] = true;
    }
  }
  const defeated = next.sides.map((side) => side.team.every((pokemon) => pokemon.fainted));
  if (defeated.some(Boolean)) {
    next.status = defeated.every(Boolean) ? "tie" : "completed";
    next.winner = defeated.every(Boolean)
      ? null
      : next.sides[defeated[0] ? 1 : 0].name;
  }
  return next;
}

function sharedSearchPolicyRuntime({
  state,
  sideIndex,
  strategy,
  opponentStrategy,
  moveCandidates,
  switchCandidates,
  itemCandidates,
  gimmick,
  gimmickMove,
  heuristicDecision,
}) {
  const searchStateJson = (value) => {
    const searchable = { ...value };
    delete searchable.aiTrace;
    delete searchable.turnSnapshots;
    return JSON.stringify(searchable);
  };
  const initialStateId = searchStateJson(state);
  const states = new Map([[initialStateId, state]]);
  const candidateCache = new Map();
  const candidateById = new Map();
  const stateFor = (stateId) => {
    if (states.has(stateId)) return states.get(stateId);
    try {
      const restored = JSON.parse(stateId);
      states.set(stateId, restored);
      return restored;
    } catch {
      return null;
    }
  };
  const markHeuristic = (candidates, command) => {
    const selectedKey = simpleCommandKey(command);
    return candidates.map((candidate) => ({
      ...candidate,
      searchHeuristicSelected:
        simpleCommandKey(candidate.policyCommand) === selectedKey,
    }));
  };
  const rootCandidates = markHeuristic(
    simpleSearchPolicyCandidates({
      moveCandidates,
      switchCandidates,
      itemCandidates,
      gimmick,
      gimmickMove,
    }),
    heuristicDecision.command,
  );

  const candidatesFor = (stateId, requestedSide) => {
    const cacheKey = `${stateId}|${requestedSide}`;
    if (candidateCache.has(cacheKey)) return candidateCache.get(cacheKey);
    const currentState = stateFor(stateId);
    if (!currentState) return [];
    let candidates;
    if (stateId === initialStateId && requestedSide === sideIndex) {
      candidates = rootCandidates;
    } else {
      const decision = chooseSimpleAiDecision(
        currentState,
        requestedSide,
        "expert",
        requestedSide === sideIndex
          ? strategy
          : opponentStrategy ?? "balanced",
      );
      candidates = markHeuristic(
        simpleSearchPolicyCandidates({
          moveCandidates: decision.moveCandidates,
          switchCandidates: decision.switchCandidates,
          itemCandidates:
            stateId === initialStateId ? decision.itemCandidates : [],
          gimmick: decision.command.gimmick ?? null,
          gimmickMove: decision.command.gimmick
            ? decision.selectedMove
            : null,
        }),
        decision.command,
      );
    }
    const legalIds = new Set(
      JSON.parse(
        legalSharedSearchCandidatesJson(
          JSON.stringify({
            state: toSharedSearchProjectionState(currentState),
            sideIndex: requestedSide,
            candidates: candidates.map((candidate) =>
              projectedSearchAction(currentState, requestedSide, candidate),
            ),
          }),
        ),
      ).map((candidate) => candidate.action.id),
    );
    candidates = candidates.filter((candidate) =>
      legalIds.has(simpleCommandKey(candidate.policyCommand)),
    );
    const byId = new Map(
      candidates.map((candidate) => [
        simpleCommandKey(candidate.policyCommand),
        candidate,
      ]),
    );
    candidateCache.set(cacheKey, candidates);
    candidateById.set(cacheKey, byId);
    return candidates;
  };

  const candidateCallback = (stateId, requestedSide) =>
    JSON.stringify(
      candidatesFor(stateId, requestedSide).map(toSharedSearchAction),
    );
  const registerCandidate = (stateId, requestedSide, candidate) => {
    const candidates = candidatesFor(stateId, requestedSide);
    candidates.push(candidate);
    candidateById
      .get(`${stateId}|${requestedSide}`)
      ?.set(simpleCommandKey(candidate.policyCommand), candidate);
  };
  const transitionCallback = (stateId, zeroActionId, oneActionId) => {
    try {
      candidatesFor(stateId, 0);
      candidatesFor(stateId, 1);
      const zero = candidateById.get(`${stateId}|0`)?.get(zeroActionId);
      const one = candidateById.get(`${stateId}|1`)?.get(oneActionId);
      if (!zero || !one) return null;
      const currentState = stateFor(stateId);
      const projectedActions = [
        projectedSearchAction(currentState, 0, zero),
        projectedSearchAction(currentState, 1, one),
      ];
      const projection = JSON.parse(
        transitionSharedSearchStateJson(
          JSON.stringify({
            state: toSharedSearchProjectionState(currentState),
            sideZeroAction: projectedActions[0],
            sideOneAction: projectedActions[1],
          }),
        ),
      );
      const nextState = applySharedSearchProjection(
        currentState,
        projection,
        projectedActions,
      );
      const id = searchStateJson(nextState);
      states.set(id, nextState);
      return id;
    } catch {
      return null;
    }
  };
  return {
    rootCandidates,
    initialStateId,
    transitionCacheNamespace: `web-simple-v1:${initialStateId}`,
    candidatesFor,
    registerCandidate,
    candidateCallback,
    transitionCallback,
    winProbabilityCallback: (stateId, requestedSide) =>
      simpleSearchWinProbability(stateFor(stateId), requestedSide),
    terminalCallback: (stateId) => stateFor(stateId)?.status !== "running",
  };
}

function sharedEvaluationById(decision) {
  return new Map(
    (decision.evaluations ?? []).map((evaluation) => [
      evaluation.action?.id,
      evaluation,
    ]),
  );
}

function sharedSearchEvaluation(evaluation) {
  if (!evaluation) return null;
  return {
    expectedWinProbability: evaluation.expectedWinProbability,
    worstWinProbability: evaluation.worstWinProbability,
    searchValue: evaluation.searchValue,
    outcomes: (evaluation.outcomes ?? []).map((outcome) => ({
      opponentCommand: JSON.parse(outcome.opponentAction.id),
      opponentId: outcome.opponentAction.id,
      opponentProbability: outcome.opponentProbability,
      winProbability: outcome.winProbability,
      evaluatedWinProbability: outcome.evaluatedWinProbability,
      riskWinProbability: outcome.riskWinProbability,
      continuation: outcome.continuation
        ? {
            ownCommand: outcome.continuation.action
              ? JSON.parse(outcome.continuation.action.id)
              : null,
            ownId: outcome.continuation.action?.id ?? null,
            expectedWinProbability:
              outcome.continuation.expectedWinProbability,
            worstWinProbability: outcome.continuation.worstWinProbability,
            searchValue: outcome.continuation.searchValue,
          }
        : null,
    })),
  };
}

function applySharedSearchDecision({
  policy,
  sideIndex,
  coreDecision,
  runtime,
  heuristicDecision,
  moveCandidates,
  switchCandidates,
  gimmickCandidate,
  maxNodes,
  exactOpponentCommand,
}) {
  const selected = runtime.rootCandidates.find(
    (candidate) =>
      simpleCommandKey(candidate.policyCommand) === coreDecision.selected?.id,
  );
  if (!selected) {
    return {
      ...heuristicDecision,
      diagnostics: {
        ...heuristicDecision.diagnostics,
        policy,
        policyOverride: false,
        searchSkipped: "no-valid-outcome",
      },
    };
  }
  const evaluationById = sharedEvaluationById(coreDecision);
  const decorate = (candidate, command) => {
    const evaluation = evaluationById.get(simpleCommandKey(command));
    return {
      ...candidate,
      ...(policy === "win-probability-simulated"
        ? {
            oneTurnEvaluation: evaluation
              ? {
                  qValue: evaluation.expectedWinProbability,
                  winProbabilityAfter: evaluation.expectedWinProbability,
                }
              : candidate.oneTurnEvaluation,
            winRateSimulation: evaluation
              ? {
                  expectedWinProbability: evaluation.expectedWinProbability,
                  worstWinProbability: evaluation.worstWinProbability,
                  outcomes: sharedSearchEvaluation(evaluation).outcomes,
                }
              : candidate.winRateSimulation,
          }
        : {
            searchEvaluation:
              sharedSearchEvaluation(evaluation) ?? candidate.searchEvaluation,
          }),
    };
  };
  const enrichedMoves = moveCandidates.map((candidate) =>
    decorate(candidate, { move: candidate.slot }),
  );
  const enrichedSwitches = switchCandidates.map((candidate) =>
    decorate(candidate, { switch: candidate.slot }),
  );
  const selectedIsSwitch = selected.policyKind === "switch";
  const selectedIsItem = selected.policyKind === "item";
  const selectedMove =
    selectedIsSwitch || selectedIsItem
      ? null
      : enrichedMoves.find(
          (candidate) => candidate.slot === selected.policyCommand.move,
        ) ?? selected;
  const selectedSwitch = selectedIsSwitch
    ? enrichedSwitches.find(
        (candidate) => candidate.slot === selected.policyCommand.switch,
      ) ?? selected
    : null;
  return {
    ...heuristicDecision,
    command: selected.policyCommand,
    selectedMove,
    selectedSwitch,
    selectedItem: selectedIsItem ? selected : null,
    moveCandidates: enrichedMoves,
    switchCandidates: enrichedSwitches,
    gimmickCandidate:
      selected.policyKind === "gimmick" ? gimmickCandidate : null,
    diagnostics: {
      ...heuristicDecision.diagnostics,
      selectionSource: coreDecision.policyOverride
        ? policy
        : heuristicDecision.diagnostics.selectionSource,
      policy,
      policyOverride: coreDecision.policyOverride,
      searchNodes: coreDecision.visitedNodes,
      simulationNodes: coreDecision.visitedNodes,
      searchCacheHits: coreDecision.cacheHits,
      simulationCacheHits: coreDecision.cacheHits,
      searchBudget: maxNodes,
      simulationBudget: maxNodes,
      searchBudgetExhausted: coreDecision.budgetExhausted,
      simulationBudgetExhausted: coreDecision.budgetExhausted,
      searchDepthTurns: coreDecision.depthTurns,
      searchDepthLimit: policy === "expectimax-two-turn" ? 2 : 1,
      ownCandidateCount: runtime.rootCandidates.length,
      opponentCandidateCount: exactOpponentCommand
        ? 1
        : runtime.candidatesFor(
            runtime.initialStateId,
            sideIndex === 0 ? 1 : 0,
          ).length,
      opponentDistribution: exactOpponentCommand
        ? [
            {
              id: `exact:${simpleCommandKey(exactOpponentCommand)}`,
              command: structuredClone(exactOpponentCommand),
              probability: 1,
            },
          ]
        : undefined,
      searchCommand: structuredClone(selected.policyCommand),
    },
  };
}

function applySharedWinProbabilityDecisionPolicy(options) {
  if (options.lockedSelection) return options.heuristicDecision;
  const runtime = sharedSearchPolicyRuntime(options);
  const decision = JSON.parse(
    decideWinRateJson(
      runtime.initialStateId,
      options.sideIndex,
      options.maxNodes ?? 8,
      runtime.candidateCallback,
      runtime.transitionCallback,
      runtime.winProbabilityCallback,
      runtime.terminalCallback,
    ),
  );
  return applySharedSearchDecision({
    policy: "win-probability-simulated",
    coreDecision: decision,
    runtime,
    ...options,
  });
}

function applySharedTwoTurnExpectimaxDecisionPolicy(options) {
  const runtime = sharedSearchPolicyRuntime(options);
  let exactOpponentActionJson = null;
  let exactOwnActionJson = null;
  if (options.exactOpponentCommand) {
    const exactCandidate = {
      policyKind: Number.isInteger(options.exactOpponentCommand.switch)
        ? "switch"
        : "move",
      policyCommand: options.exactOpponentCommand,
      score: 0,
    };
    exactOpponentActionJson = JSON.stringify(
      toSharedSearchAction(exactCandidate),
    );
    const opponentSide = options.sideIndex === 0 ? 1 : 0;
    runtime.registerCandidate(runtime.initialStateId, opponentSide, exactCandidate);
  }
  if (options.lockedSelection) {
    const lockedCandidate = runtime.rootCandidates.find(
      (candidate) =>
        candidate.policyKind === "move" &&
        candidate.policyCommand?.move === options.lockedSelection.slot,
    );
    if (!lockedCandidate) return options.heuristicDecision;
    exactOwnActionJson = JSON.stringify(toSharedSearchAction(lockedCandidate));
  }
  let decision;
  try {
    decision = JSON.parse(
      decideTwoTurnJson(
        runtime.initialStateId,
        options.sideIndex,
        options.maxNodes ?? 10,
        runtime.candidateCallback,
        runtime.transitionCallback,
        runtime.winProbabilityCallback,
        runtime.terminalCallback,
        exactOpponentActionJson,
        exactOwnActionJson,
        runtime.transitionCacheNamespace,
      ),
    );
  } catch (error) {
    throw new Error(
      `shared AI two-turn search failed: ${error?.message ?? String(error)}`,
      { cause: error },
    );
  }
  return applySharedSearchDecision({
    policy: "expectimax-two-turn",
    coreDecision: decision,
    runtime,
    ...options,
  });
}

function trainerItemFutureMatchupValue(
  state,
  sideIndex,
  pokemon,
  postTurnHp,
) {
  if (postTurnHp <= 0) {
    return {
      value: 0,
      safeKoTargets: [],
      pressureTargets: [],
    };
  }
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const projectedPokemon = { ...pokemon, hp: postTurnHp };
  const trickRoom =
    Number(state.field?.pseudoWeather?.trickroom?.turns ?? 0) > 0;
  const opponentAnalysis = simpleTeamAnalysis(state, opponentSide);
  const safeKoTargets = [];
  const pressureTargets = [];
  let value = 0;

  for (const [enemyIndex, enemy] of state.sides[opponentSide].team.entries()) {
    if (enemy.fainted || enemy.hp <= 0) continue;
    const outgoing = bestAiAttackProfile(
      state,
      sideIndex,
      projectedPokemon,
      opponentSide,
      enemy,
    );
    const incoming = bestAiAttackProfile(
      state,
      opponentSide,
      enemy,
      sideIndex,
      projectedPokemon,
    );
    const ownSpeed = effectiveSpeed(projectedPokemon, state, sideIndex);
    const enemySpeed = effectiveSpeed(enemy, state, opponentSide);
    const actsBefore =
      outgoing.priority > incoming.priority ||
      (outgoing.priority === incoming.priority &&
        (trickRoom ? ownSpeed < enemySpeed : ownSpeed > enemySpeed));
    const canKo = outgoing.expectedDamage >= enemy.hp;
    const survivesIncoming = incoming.expectedDamage < postTurnHp;
    const canReachAction = actsBefore || survivesIncoming;
    const safeKo = canKo && canReachAction;
    const pressureRatio = canReachAction
      ? Math.min(1.25, outgoing.expectedDamage / Math.max(1, enemy.hp))
      : 0;
    if (safeKo) {
      safeKoTargets.push(enemy.name);
      value += 24;
    } else if (pressureRatio >= 0.6) {
      pressureTargets.push(enemy.name);
      value += pressureRatio * 10;
    }
    if (
      safeKo &&
      opponentAnalysis.roles[enemyIndex]?.aceProfile?.qualifies === true
    ) {
      value += 10;
    }
  }
  return {
    value: Math.round(value * 100) / 100,
    safeKoTargets,
    pressureTargets,
  };
}

function trainerItemRoleValue(
  state,
  sideIndex,
  pokemon,
  baselinePostTurnHp,
  healedPostTurnHp,
) {
  const side = state.sides[sideIndex];
  const teamAnalysis = simpleTeamAnalysis(state, sideIndex);
  const threatMap = simpleThreatCounterMap(
    state,
    sideIndex,
    teamAnalysis,
  );
  const roleProgress = simpleTeamRoleProgress(
    state,
    sideIndex,
    teamAnalysis,
    threatMap,
  )[side.active];
  const roleProfile = teamAnalysis.roles[side.active];
  const baseline = trainerItemFutureMatchupValue(
    state,
    sideIndex,
    pokemon,
    baselinePostTurnHp,
  );
  const healed = trainerItemFutureMatchupValue(
    state,
    sideIndex,
    pokemon,
    healedPostTurnHp,
  );
  const baselineSafeKos = new Set(baseline.safeKoTargets);
  const unlockedSafeKoTargets = healed.safeKoTargets.filter(
    (target) => !baselineSafeKos.has(target),
  );
  const mustPreserve = threatMap.mustPreserveResources.some(
    (resource) => resource.slot === side.active + 1,
  );
  let value =
    Math.max(0, healed.value - baseline.value) +
    unlockedSafeKoTargets.length * 14 +
    healed.safeKoTargets.length * 18;
  if (
    healed.safeKoTargets.length > 0 &&
    roleProfile?.aceProfile?.qualifies === true
  ) {
    value += 10;
  }
  if (healedPostTurnHp > 0 && baselinePostTurnHp <= 0) {
    if (roleProfile?.aceProfile?.qualifies === true) value += 18;
    if ((roleProgress?.remainingRoles ?? []).includes("revengeKiller")) {
      value += 14;
    }
    if ((roleProgress?.remainingRoles ?? []).includes("setupSweeper")) {
      value += 10;
    }
    if (mustPreserve) value += 20;
    if (roleProgress?.expendableResource === true) value -= 14;
  }
  return {
    value: Math.round(value * 100) / 100,
    safeKoTargets: healed.safeKoTargets,
    pressureTargets: healed.pressureTargets,
    unlockedSafeKoTargets,
    remainingRoles: roleProgress?.remainingRoles ?? [],
    mustPreserve,
    expendableResource: roleProgress?.expendableResource === true,
  };
}

function lowestResidualValuePokemonIndex(state, sideIndex) {
  const side = state.sides[sideIndex];
  const analysis = simpleTeamAnalysis(state, sideIndex);
  const threatMap = simpleThreatCounterMap(
    state,
    sideIndex,
    analysis,
  );
  const progress = simpleTeamRoleProgress(
    state,
    sideIndex,
    analysis,
    threatMap,
  );
  const preservedSlots = new Set(
    threatMap.mustPreserveResources.map((resource) => resource.slot),
  );
  return side.team
    .map((pokemon, index) => {
      if (
        pokemon.fainted ||
        pokemon.hp <= 0 ||
        analysis.roles[index]?.aceProfile?.qualifies === true
      ) {
        return null;
      }
      const maxHp = Math.max(1, Number(pokemon.stats?.hp ?? pokemon.hp));
      const roleStrength = Math.max(
        0,
        ...(analysis.roles[index]?.roles ?? []).map(
          (role) => Number(role.score ?? 0),
        ),
      );
      const remainingRoleValue =
        Number(progress[index]?.remainingRoles?.length ?? 0) * 6;
      const preservationValue = preservedSlots.has(index + 1) ? 120 : 0;
      const completedDiscount =
        progress[index]?.roleComplete === true ? 18 : 0;
      const expendableDiscount =
        progress[index]?.expendableResource === true ? 24 : 0;
      return {
        index,
        value:
          roleStrength * 7 +
          remainingRoleValue +
          preservationValue +
          (pokemon.hp / maxHp) * 12 -
          completedDiscount -
          expendableDiscount,
      };
    })
    .filter(Boolean)
    .sort((left, right) => left.value - right.value || left.index - right.index)[0]
    ?.index ?? -1;
}

function aceRecoverySacrificeProjection(
  state,
  sideIndex,
  sacrificeIndex,
  strategy,
  itemId,
) {
  const side = state.sides[sideIndex];
  const analysis = simpleTeamAnalysis(state, sideIndex);
  const aceIndex = analysis.roles.findIndex(
    (role) => role?.aceProfile?.qualifies === true,
  );
  const ace = side.team[aceIndex];
  const sacrifice = side.team[sacrificeIndex];
  const effect = TRAINER_BATTLE_ITEMS[cleanId(itemId)];
  if (
    aceIndex < 0 ||
    aceIndex === sacrificeIndex ||
    !ace ||
    ace.fainted ||
    ace.hp <= 0 ||
    !sacrifice ||
    sacrifice.fainted ||
    sacrifice.hp <= 0 ||
    !effect ||
    effect.heal === 0
  ) {
    return null;
  }
  const missingHp = Math.max(0, ace.stats.hp - ace.hp);
  const healing =
    effect.heal === "full"
      ? missingHp
      : Math.min(missingHp, Number(effect.heal ?? 0));
  if (healing <= 0) return null;

  const projected = clone(state);
  const projectedSide = projected.sides[sideIndex];
  const projectedSacrifice = projectedSide.team[sacrificeIndex];
  const projectedAce = projectedSide.team[aceIndex];
  projectedSacrifice.hp = 0;
  projectedSacrifice.fainted = true;
  projectedSide.active = aceIndex;
  projectedAce.hp = Math.min(projectedAce.stats.hp, projectedAce.hp + healing);
  if (effect.cureStatus) {
    projectedAce.status = "";
    projectedAce.statusTurns = 0;
    projectedAce.toxicCounter = 0;
    if (projectedAce.volatiles) delete projectedAce.volatiles.confusion;
  }
  const beforeEstimate = estimateSimpleBattleWinProbability(
    state,
    sideIndex,
  );
  const afterEstimate = estimateSimpleBattleWinProbability(
    projected,
    sideIndex,
  );
  const winProbabilityBefore = Number(
    beforeEstimate?.probability ?? beforeEstimate,
  );
  const winProbabilityAfter = Number(
    afterEstimate?.probability ?? afterEstimate,
  );
  const winProbabilityDelta =
    winProbabilityAfter - winProbabilityBefore;
  const minimumGain = strategy === "reckless_ace" ? 0.025 : 0.04;
  const strategyMultiplier = strategy === "reckless_ace" ? 1.55 : 1;
  return {
    eligible: winProbabilityDelta >= minimumGain,
    aceIndex,
    aceSlot: aceIndex + 1,
    aceName: ace.name,
    sacrificeIndex,
    sacrificeSlot: sacrificeIndex + 1,
    sacrificeName: sacrifice.name,
    itemId: cleanId(itemId),
    healing,
    hpAfterHealing: projectedAce.hp,
    winProbabilityBefore:
      Math.round(winProbabilityBefore * 10_000) / 10_000,
    winProbabilityAfter:
      Math.round(winProbabilityAfter * 10_000) / 10_000,
    winProbabilityDelta:
      Math.round(winProbabilityDelta * 10_000) / 10_000,
    minimumGain,
    strategyMultiplier,
  };
}

function bestAceRecoverySacrificePlan(
  state,
  sideIndex,
  strategy,
  sacrificeIndex = lowestResidualValuePokemonIndex(state, sideIndex),
) {
  if (sacrificeIndex < 0) return null;
  const side = state.sides[sideIndex];
  return side.bag
    .filter(
      (entry) =>
        entry.quantity > 0 &&
        TRAINER_BATTLE_ITEMS[cleanId(entry.item)]?.heal !== 0,
    )
    .map((entry) =>
      aceRecoverySacrificeProjection(
        state,
        sideIndex,
        sacrificeIndex,
        strategy,
        entry.item,
      ),
    )
    .filter(Boolean)
    .sort(
      (left, right) =>
        right.winProbabilityDelta - left.winProbabilityDelta ||
        right.healing - left.healing,
    )[0] ?? null;
}

function automaticTrainerItemCandidates(
  state,
  sideIndex,
  moveCandidates,
  strategy = "balanced",
) {
  const side = state.sides[sideIndex];
  const pokemon = activePokemon(state, sideIndex);
  if (side.itemUsesRemaining <= 0 || pokemon.fainted || pokemon.hp <= 0) {
    return [];
  }
  const incomingDamageRatio = Math.max(
    0,
    ...moveCandidates.map((candidate) =>
      Number(candidate.incomingDamageRatio ?? 0),
    ),
  );
  const incomingDamage = incomingDamageRatio * pokemon.hp;
  const missingHp = Math.max(0, pokemon.stats.hp - pokemon.hp);
  const currentMoveScore = Math.max(
    0,
    ...moveCandidates.map((candidate) => Number(candidate.score ?? 0)),
  );
  const statusValue = Math.max(
    {
      slp: 130,
      frz: 130,
      tox: 95,
      par: 70,
      brn: 65,
      psn: 55,
    }[cleanId(pokemon.status)] ?? 0,
    pokemon.volatiles?.confusion ? 55 : 0,
  );
  const uniqueItems = new Map();
  for (const entry of side.bag) {
    const id = cleanId(entry.item);
    if (!TRAINER_BATTLE_ITEMS[id] || entry.quantity <= 0 || uniqueItems.has(id)) {
      continue;
    }
    const effect = TRAINER_BATTLE_ITEMS[id];
    const healing =
      effect.heal === "full"
        ? missingHp
        : Math.min(missingHp, Number(effect.heal ?? 0));
    const curedStatusValue = effect.cureStatus ? statusValue : 0;
    if (healing <= 0 && curedStatusValue <= 0) continue;
    const hpAfter = Math.min(pokemon.stats.hp, pokemon.hp + healing);
    const residualPokemon = effect.cureStatus
      ? {
          ...pokemon,
          status: "",
          statusTurns: 0,
          toxicCounter: 0,
        }
      : pokemon;
    const residualDamage = aiEndTurnResidualDamage(residualPokemon, state);
    const baselineResidualDamage = aiEndTurnResidualDamage(pokemon, state);
    const baselinePostTurnHp = Math.max(
      0,
      pokemon.hp - incomingDamage - baselineResidualDamage,
    );
    const postTurnHp = Math.max(
      0,
      hpAfter - incomingDamage - residualDamage,
    );
    const preventsImmediateKo =
      incomingDamage >= pokemon.hp && incomingDamage < hpAfter;
    const stillFaints = postTurnHp <= 0;
    const roleValue = trainerItemRoleValue(
      state,
      sideIndex,
      pokemon,
      baselinePostTurnHp,
      postTurnHp,
    );
    const resourceCost =
      side.itemUsesRemaining <= 1
        ? 18
        : side.itemUsesRemaining === 2
          ? 10
          : 6;
    const score = JSON.parse(
      scoreSharedTrainerItemCandidateJson(
        JSON.stringify({
          healing,
          curedStatusValue,
          preventsImmediateKnockout: preventsImmediateKo,
          incomingDamage,
          futureRoleValue: roleValue.value,
          resourceCost,
          lethalAfterUse:
            stillFaints && incomingDamage + residualDamage >= pokemon.hp,
          inefficientPotion: id === "potion" && healing < 20,
          strongMoveAvailable: currentMoveScore >= 180,
        }),
      ),
    ).score;
    uniqueItems.set(id, {
      id,
      name: effect.name,
      type: "item",
      legal: true,
      score: Math.round(score * 100) / 100,
      healing,
      curesStatus:
        effect.cureStatus &&
        (Boolean(pokemon.status) || Boolean(pokemon.volatiles?.confusion)),
      incomingDamage: Math.round(incomingDamage * 100) / 100,
      residualDamage,
      postTurnHp: Math.round(postTurnHp * 100) / 100,
      preventsImmediateKo,
      survivesEndOfTurn: postTurnHp > 0,
      futureRoleValue: roleValue.value,
      futureSafeKoTargets: roleValue.safeKoTargets,
      futurePressureTargets: roleValue.pressureTargets,
      unlockedSafeKoTargets: roleValue.unlockedSafeKoTargets,
      remainingRoles: roleValue.remainingRoles,
      mustPreserveResource: roleValue.mustPreserve,
      expendableResource: roleValue.expendableResource,
      resourceCost,
      reasons: [
        ...(healing > 0
          ? [{
              component: "healing",
              label: "회복량",
              value: healing,
              message: `${effect.name}으로 체력 ${healing}을 회복할 수 있습니다.`,
            }]
          : []),
        ...(curedStatusValue > 0
          ? [{
              component: "statusCure",
              label: "상태 회복",
              value: curedStatusValue,
              message: `${pokemon.status || "confusion"} 상태를 치료할 수 있습니다.`,
            }]
          : []),
        ...(preventsImmediateKo
          ? [{
              component: "survival",
              label: "즉시 기절 방지",
              value: 95,
              message: "예상 공격을 받은 뒤에도 생존할 수 있습니다.",
            }]
          : []),
        ...(roleValue.safeKoTargets.length > 0
          ? [{
              component: "futureKoRole",
              label: "후속 처리 역할",
              value: roleValue.value,
              message:
                roleValue.unlockedSafeKoTargets.length > 0
                  ? `회복하면 ${roleValue.unlockedSafeKoTargets.join(", ")} 상대로 안전한 KO 경로를 되찾습니다.`
                  : `회복하면 ${roleValue.safeKoTargets.join(", ")} 상대로 안전한 KO 역할을 유지할 수 있습니다.`,
            }]
          : []),
        ...(roleValue.mustPreserve
          ? [{
              component: "preservation",
              label: "핵심 대응 자원",
              value: 20,
              message: "남은 상대를 막는 핵심 대응 자원이라 생존 가치를 높게 반영했습니다.",
            }]
          : []),
        ...(residualDamage > 0
          ? [{
              component: "residualDamage",
              label: "턴 종료 피해",
              value: -residualDamage,
              message: `아이템 사용 후에도 턴 종료 피해 ${residualDamage}이 예상됩니다.`,
            }]
          : []),
        {
          component: "resourceCost",
          label: "아이템 자원",
          value: -resourceCost,
          message: `남은 사용 횟수를 보존하기 위해 자원 비용 ${resourceCost}을 반영했습니다.`,
        },
      ],
    });
  }
  const sacrificeIndex = lowestResidualValuePokemonIndex(state, sideIndex);
  const recoveryPlan =
    sacrificeIndex === side.active
      ? bestAceRecoverySacrificePlan(
          state,
          sideIndex,
          strategy,
          sacrificeIndex,
        )
      : null;
  if (recoveryPlan?.eligible) {
    const effect = TRAINER_BATTLE_ITEMS[recoveryPlan.itemId];
    const planScore =
      recoveryPlan.winProbabilityDelta *
      1_000 *
      recoveryPlan.strategyMultiplier;
    uniqueItems.set(
      `${recoveryPlan.itemId}:target:${recoveryPlan.aceSlot}`,
      {
        id: recoveryPlan.itemId,
        actionId: `item:${recoveryPlan.itemId}:target:${recoveryPlan.aceSlot}`,
        name: `${effect.name} → ${recoveryPlan.aceName}`,
        type: "item",
        legal: true,
        score: Math.round(planScore * 100) / 100,
        targetSlot: recoveryPlan.aceSlot,
        targetPokemon: recoveryPlan.aceName,
        healing: recoveryPlan.healing,
        postTurnHp: recoveryPlan.hpAfterHealing,
        aceRecoveryPlanEligible: true,
        aceRecoveryPlan: recoveryPlan,
        reasons: [
          {
            component: "aceRecoveryPlan",
            label: "에이스 회복 계획",
            value: recoveryPlan.winProbabilityDelta,
            message: `${recoveryPlan.sacrificeName}을 잔존 가치가 가장 낮은 자원으로 두고 ${recoveryPlan.aceName}을 회복하면, 희생 후 예측 승률이 ${Math.round(recoveryPlan.winProbabilityBefore * 1_000) / 10}%에서 ${Math.round(recoveryPlan.winProbabilityAfter * 1_000) / 10}%로 ${Math.round(recoveryPlan.winProbabilityDelta * 1_000) / 10}%p 상승합니다.`,
          },
          ...(strategy === "reckless_ace"
            ? [{
                component: "recklessAceRecovery",
                label: "저돌적 에이스 운용",
                value: recoveryPlan.strategyMultiplier,
                message:
                  "저돌적 에이스 성향이라 에이스의 재돌파 가능성을 더 강하게 반영했습니다.",
              }]
            : []),
        ],
      },
    );
  }
  return [...uniqueItems.values()].sort((left, right) => right.score - left.score);
}

export function chooseSimpleAiDecision(
  state,
  sideIndex,
  difficulty = "standard",
  strategy = "balanced",
  options = {},
) {
  const scoringDifficulty =
    difficulty === "expert_winrate" ||
    difficulty === "expert_search" ||
    difficulty === "cheater"
      ? "expert"
      : difficulty;
  const side = state.sides[sideIndex];
  const pokemon = activePokemon(state, sideIndex);
  const opponentSideIndex = sideIndex === 0 ? 1 : 0;
  const opponent = activePokemon(state, opponentSideIndex);
  const livingOpponentCount = state.sides[opponentSideIndex].team.filter(
    (member) => !member.fainted && member.hp > 0,
  ).length;
  const lockedSelection = lockedMoveSelection(pokemon);
  const moveCandidates = automaticMoveCandidates(
    state,
    sideIndex,
    strategy,
    scoringDifficulty,
  );
  const selectedMove = selectAiMoveCandidate(moveCandidates, {
    difficulty: scoringDifficulty,
    strategy,
    rng: createAiRng(state.seed, sideIndex, state.turn * 17),
  });
  const forcedMove =
    lockedSelection &&
    moveCandidates.find((candidate) => candidate.slot === lockedSelection.slot);
  const chosenMove = forcedMove ?? selectedMove;
  const itemCandidates = lockedSelection?.preventsSwitch
    ? []
    : automaticTrainerItemCandidates(
        state,
        sideIndex,
        moveCandidates,
        strategy,
      );
  const selectedItem =
    itemCandidates.find(
      (candidate) => candidate.aceRecoveryPlanEligible === true,
    ) ??
    itemCandidates[0] ??
    null;
  const canVoluntarilySwitch =
    !lockedSelection?.preventsSwitch &&
    !isPokemonTrapped(state, sideIndex, pokemon) &&
    side.team.some(
      (member, index) =>
        index !== side.active && !member.fainted && member.hp > 0,
    );
  const switchCandidates = canVoluntarilySwitch
    ? automaticSwitchCandidates(
        state,
        sideIndex,
        moveCandidates,
        scoringDifficulty,
        strategy,
      )
    : [];
  const tacticallyViableSwitches = switchCandidates.filter(
    (candidate) =>
      !(
        candidate.targetAceQualified === true &&
        candidate.canReachNextAction === false &&
        candidate.canKoOnNextAction !== true
      ),
  );
  const recoveryPlanSwitch =
    tacticallyViableSwitches.find(
      (candidate) => candidate.aceRecoveryPlanEligible === true,
    ) ?? null;
  const selectedSwitch =
    recoveryPlanSwitch ?? tacticallyViableSwitches[0] ?? null;
  const switchMargin = {
    novice: Infinity,
    standard: 24,
    advanced: 14,
    expert: 8,
    cheater: 8,
  }[scoringDifficulty] ?? 18;
  const batonDevelopmentPlan =
    chosenMove?.batonPassTargetAvailable === true &&
    Number(chosenMove.batonPassAdditionalBoostTotal ?? 0) > 0 &&
    Number(chosenMove.setupFollowupSurvivalProbability ?? 0) >= 0.65;
  const residualCounterSwitch =
    selectedSwitch?.toxicTwoTurnLethal === true &&
    selectedSwitch.safeImmediateKoAvailable !== true &&
    selectedSwitch.survivesSwitchIn !== false &&
    selectedSwitch.canReachNextAction === true &&
    selectedSwitch.canKoOnNextAction === true;
  const shouldSwitch =
    selectedSwitch &&
    (residualCounterSwitch ||
      (!batonDevelopmentPlan &&
        (selectedSwitch.aceRecoveryPlanEligible === true ||
          ((selectedSwitch.safeImmediateKoAvailable !== true ||
            selectedSwitch.urgentSwitchPressure === true) &&
            Number(selectedSwitch.score ?? -Infinity) >=
              Number(chosenMove?.score ?? -Infinity) + switchMargin))));
  const canMegaEvo =
    side.gimmickResources.mega === "available" &&
    pokemon.megaEvolved !== true &&
    canPokemonCombineGimmick(pokemon, "mega") &&
    canMegaEvolvePokemon(pokemon);
  const canTerastallize =
    side.gimmickResources.terastallize === "available" &&
    pokemon.terastallized !== true &&
    canPokemonCombineGimmick(pokemon, "terastallize") &&
    canPokemonUseTerastallization(state, sideIndex, pokemon) &&
    Boolean(String(pokemon.configuredTeraType ?? "").trim());
  const dynamaxFallback =
    canUseDynamaxFallback(side) && !canMegaEvo;
  const canDynamax =
    side.gimmickResources.dynamax === "available" &&
    pokemon.dynamaxTurns <= 0 &&
    canPokemonCombineGimmick(pokemon, "dynamax");
  const dynamaxMode = pokemon.gimmicks?.canGigantamax === true
    ? "gigantamax"
    : "dynamax";
  const dynamaxMoveCandidates =
    canDynamax && !lockedSelection
      ? automaticMoveCandidates(
          state,
          sideIndex,
          strategy,
          scoringDifficulty,
          dynamaxMode,
          { gimmickResourceCost: 1 },
        )
      : [];
  const selectedDynamaxMove =
    dynamaxMoveCandidates.length > 0
      ? selectAiMoveCandidate(dynamaxMoveCandidates, {
          difficulty: scoringDifficulty,
          strategy,
          rng: createAiRng(state.seed, sideIndex, state.turn * 19),
        })
      : null;
  const baseMoveForDynamax = selectedDynamaxMove ? chosenMove : null;
  const megaProjection =
    canMegaEvo && !lockedSelection && chosenMove
      ? projectedSimpleGimmickCandidate({
          state,
          sideIndex,
          gimmick: "mega",
          difficulty: scoringDifficulty,
          strategy,
          baseMove: chosenMove,
          configured: true,
        })
      : { candidate: null, moveCandidates: [], selectedMove: null };
  const teraProjection =
    canTerastallize && !lockedSelection && chosenMove
      ? projectedSimpleGimmickCandidate({
          state,
          sideIndex,
          gimmick: "terastallize",
          difficulty: scoringDifficulty,
          strategy,
          baseMove: chosenMove,
          configured: pokemon.gimmicks?.teraConfigured === true,
        })
      : { candidate: null, moveCandidates: [], selectedMove: null };
  const teamRoleAnalysis = simpleTeamAnalysis(state, sideIndex);
  const activeRoleProfile = teamRoleAnalysis.roles[side.active];
  const livingAceIndex = teamRoleAnalysis.roles.findIndex(
    (role, index) =>
      role?.aceProfile?.qualifies === true &&
      !side.team[index].fainted &&
      side.team[index].hp > 0,
  );
  const livingAce = side.team[livingAceIndex] ?? null;
  const gimmickDecision = chosenMove
    ? selectAiGimmick({
        active: {
          canMegaEvo,
          canDynamax,
          canGigantamax: pokemon.gimmicks?.canGigantamax === true,
          canTerastallize,
          dynamaxReservedForOther: hasLivingConfiguredDynamaxOther(side, pokemon),
          hpPercent: pokemon.hp / pokemon.stats.hp,
          incomingDamageRatio: chosenMove.incomingDamageRatio,
          opponentHp: chosenMove.opponentHp,
          aceQualified:
            activeRoleProfile?.aceProfile?.qualifies === true,
          livingAceOther:
            livingAceIndex >= 0 && livingAceIndex !== side.active,
          livingAceName: livingAce?.name ?? "",
        },
        configured: {
          gimmicks: {
            dynamax:
              pokemon.gimmicks?.forceDynamax === true || dynamaxFallback,
            gigantamax: pokemon.gimmicks?.gigantamax === true,
            tera: canTerastallize,
          },
        },
        selectedMove: chosenMove,
        moveCandidates,
        dynamaxMove: selectedDynamaxMove,
        baseMoveForDynamax,
        dynamaxMoveCandidates,
        projectedGimmickCandidates: [
          megaProjection.candidate,
          teraProjection.candidate,
        ].filter(Boolean),
        forceDynamax: pokemon.gimmicks?.forceDynamax === true,
        alreadyUsed: side.usedGimmicks,
      })
    : {
        id: null,
        candidate: null,
      };
  const gimmick = gimmickDecision.id;
  const usesDynamaxMove =
    gimmick === "dynamax" || gimmick === "gigantamax";
  const commandMove =
    usesDynamaxMove && selectedDynamaxMove
      ? selectedDynamaxMove
      : gimmick === "mega" && megaProjection.selectedMove
        ? megaProjection.selectedMove
        : gimmick === "terastallize" && teraProjection.selectedMove
          ? teraProjection.selectedMove
      : chosenMove;
  const heuristicDecision = shouldSwitch
    ? {
      command: { switch: selectedSwitch.slot },
      selectedMove: null,
      selectedSwitch,
      moveCandidates,
      switchCandidates,
      itemCandidates,
      dynamaxMoveCandidates,
      selectedDynamaxMove,
      gimmickCandidate: gimmickDecision.candidate ?? null,
      diagnostics: {
        selectionSource: residualCounterSwitch
          ? "residual-counter-switch"
          : "switch-score",
        lockedSelection: lockedSelection
          ? {
              slot: lockedSelection.slot,
              moveId: lockedSelection.move?.id ?? "",
              source: lockedSelection.lockSource,
              preventsSwitch: lockedSelection.preventsSwitch,
            }
          : null,
        scoreWinner: selectedMove
          ? {
              slot: selectedMove.slot,
              id: selectedMove.id,
              score: selectedMove.score,
            }
          : null,
        chosenMove: chosenMove
          ? {
              slot: chosenMove.slot,
              id: chosenMove.id,
              score: chosenMove.score,
            }
          : null,
        chosenSwitch: {
          slot: selectedSwitch.slot,
          id: selectedSwitch.id,
          score: selectedSwitch.score,
        },
        switchMargin,
      },
    }
    : {
    command: {
      move: commandMove?.slot ?? automaticMoveSlot(
        state,
        sideIndex,
        scoringDifficulty,
        strategy,
        moveCandidates,
      ),
      ...(gimmick && !lockedSelection ? { gimmick } : {}),
    },
    selectedMove: commandMove
      ? moveCandidates.find((candidate) => candidate.slot === commandMove.slot) ?? chosenMove
      : chosenMove,
    selectedSwitch: null,
    moveCandidates,
    switchCandidates,
    itemCandidates,
    dynamaxMoveCandidates,
    selectedDynamaxMove,
    gimmickCandidate: gimmickDecision.candidate ?? null,
    diagnostics: {
      selectionSource: lockedSelection
        ? `forced:${lockedSelection.lockSource}`
        : usesDynamaxMove
          ? dynamaxMode
          : "move-score",
      lockedSelection: lockedSelection
        ? {
            slot: lockedSelection.slot,
            moveId: lockedSelection.move?.id ?? "",
            source: lockedSelection.lockSource,
            preventsSwitch: lockedSelection.preventsSwitch,
          }
        : null,
      scoreWinner: selectedMove
        ? {
            slot: selectedMove.slot,
            id: selectedMove.id,
            score: selectedMove.score,
          }
        : null,
      chosenMove: commandMove
        ? {
            slot: commandMove.slot,
            id: commandMove.id,
            score: commandMove.score,
          }
        : null,
      chosenSwitch: null,
      switchMargin,
    },
  };
  const currentActionScore = shouldSwitch
    ? Number(selectedSwitch?.score ?? -Infinity)
    : Number(commandMove?.score ?? chosenMove?.score ?? -Infinity);
  const shouldUseItem =
    selectedItem &&
    (selectedItem.aceRecoveryPlanEligible === true ||
      selectedItem.score >= currentActionScore + 8);
  const itemAwareHeuristicDecision = shouldUseItem
    ? {
        ...heuristicDecision,
        command: {
          item: selectedItem.id,
          ...(selectedItem.targetSlot
            ? { itemTarget: selectedItem.targetSlot }
            : {}),
        },
        selectedMove: null,
        selectedSwitch: null,
        selectedItem,
        diagnostics: {
          ...heuristicDecision.diagnostics,
          selectionSource: "item-score",
          chosenItem: {
            id: selectedItem.id,
            score: selectedItem.score,
          },
        },
      }
    : {
        ...heuristicDecision,
        selectedItem: null,
      };
  const immediateHazeCandidate = !lockedSelection
    ? moveCandidates.find(
        (candidate) =>
          cleanId(candidate.id) === "haze" &&
          candidate.disabled !== true &&
          Number(candidate.opponentPositiveBoosts ?? 0) > 0,
      )
    : null;
  if (immediateHazeCandidate) {
    return {
      ...itemAwareHeuristicDecision,
      command: { move: immediateHazeCandidate.slot },
      selectedMove: immediateHazeCandidate,
      selectedSwitch: null,
      selectedItem: null,
      gimmickCandidate: null,
      diagnostics: {
        ...itemAwareHeuristicDecision.diagnostics,
        selectionSource: "immediate-haze",
        policy: "immediate-boost-reset",
        chosenMove: {
          slot: immediateHazeCandidate.slot,
          id: immediateHazeCandidate.id,
          score: immediateHazeCandidate.score,
        },
      },
    };
  }
  const immediatePhazeCandidate =
    !lockedSelection &&
    opponent.dynamaxTurns <= 0 &&
    (activeAbility(opponent) !== "magicbounce" ||
      ignoresDefenderAbility(pokemon)) &&
    livingOpponentCount > 1
      ? moveCandidates.find(
          (candidate) =>
            AI_PHAZE_MOVES.has(cleanId(candidate.id)) &&
            candidate.disabled !== true &&
            Number(candidate.pp ?? 0) > 0 &&
            Number(candidate.opponentPositiveBoosts ?? 0) > 0,
        )
      : null;
  if (immediatePhazeCandidate) {
    return {
      ...itemAwareHeuristicDecision,
      command: { move: immediatePhazeCandidate.slot },
      selectedMove: immediatePhazeCandidate,
      selectedSwitch: null,
      selectedItem: null,
      gimmickCandidate: null,
      diagnostics: {
        ...itemAwareHeuristicDecision.diagnostics,
        selectionSource: "immediate-phaze",
        policy: "immediate-boost-removal",
        chosenMove: {
          slot: immediatePhazeCandidate.slot,
          id: immediatePhazeCandidate.id,
          score: immediatePhazeCandidate.score,
        },
      },
    };
  }
  if (difficulty === "expert_search") {
    return applySharedTwoTurnExpectimaxDecisionPolicy({
      state,
      sideIndex,
      strategy,
      opponentStrategy: options.opponentStrategy,
      heuristicDecision: itemAwareHeuristicDecision,
      moveCandidates,
      switchCandidates: tacticallyViableSwitches,
      itemCandidates,
      gimmick,
      gimmickMove: commandMove,
      gimmickCandidate: gimmickDecision.candidate ?? null,
      lockedSelection,
      maxNodes: Number(options.searchNodeBudget ?? 10),
    });
  }
  if (difficulty !== "expert_winrate") {
    return itemAwareHeuristicDecision;
  }
  return applySharedWinProbabilityDecisionPolicy({
    state,
    sideIndex,
    opponentStrategy: options.opponentStrategy,
    heuristicDecision: itemAwareHeuristicDecision,
    moveCandidates,
    switchCandidates: tacticallyViableSwitches,
    itemCandidates,
    gimmick,
    gimmickMove: commandMove,
    gimmickCandidate: gimmickDecision.candidate ?? null,
    lockedSelection,
    maxNodes: Number(options.winRateNodeBudget ?? 8),
  });
}

export function chooseSimpleAiCommand(
  state,
  sideIndex,
  difficulty = "standard",
  strategy = "balanced",
) {
  return chooseSimpleAiDecision(state, sideIndex, difficulty, strategy).command;
}

function simpleCommandKey(command) {
  return JSON.stringify({
    move: Number(command?.move ?? 0),
    switch: Number(command?.switch ?? 0),
    ...(command?.item ? { item: String(command.item) } : {}),
    ...(command?.itemTarget
      ? { itemTarget: Number(command.itemTarget) }
      : {}),
    gimmick: String(command?.gimmick ?? ""),
  });
}

function stateWithExactOpponentCommand(state, sideIndex, opponentCommand) {
  const opponentSide = sideIndex === 0 ? 1 : 0;
  const knownState = structuredClone(state);
  const knownSide = knownState.sides[opponentSide];
  knownState.aiKnowledge = {
    ...(knownState.aiKnowledge ?? {}),
    [sideIndex]: {
      opponentCommand: structuredClone(opponentCommand),
    },
  };

  if (Number.isInteger(opponentCommand?.switch)) {
    const switchIndex = Number(opponentCommand.switch) - 1;
    const switchTarget = knownSide.team[switchIndex];
    if (switchTarget && !switchTarget.fainted && switchTarget.hp > 0) {
      knownSide.active = switchIndex;
      switchTarget.moves = [];
      switchTarget.lastMove = null;
      switchTarget.lastMoveSucceeded = null;
    }
    return knownState;
  }

  if (!Number.isInteger(opponentCommand?.move)) return knownState;
  const opponent = activePokemon(knownState, opponentSide);
  const moveIndex = Number(opponentCommand.move) - 1;
  const selectedMove = opponent.moves[moveIndex];
  if (!selectedMove) return knownState;

  const knownMove =
    opponentCommand.gimmick === "dynamax" ||
    opponentCommand.gimmick === "gigantamax"
      ? aiDisplayMoveData(opponent, selectedMove, opponentCommand.gimmick)
      : selectedMove;
  knownState.aiKnowledge[sideIndex].opponentMove = structuredClone(knownMove);
  opponent.moves = [knownMove];
  opponent.lastMove = {
    id: knownMove.id,
    name: knownMove.name,
  };
  opponent.lastMoveSucceeded = true;
  return knownState;
}

export function applySimpleCheaterKnowledge(
  state,
  sideIndex,
  decision,
  opponentCommand,
  options = {},
) {
  const strategy = options.strategy ?? "balanced";
  const exactHeuristicDecision = chooseSimpleAiDecision(
    stateWithExactOpponentCommand(state, sideIndex, opponentCommand),
    sideIndex,
    "expert",
    strategy,
  );
  const exactDecision = applySharedTwoTurnExpectimaxDecisionPolicy({
    state,
    sideIndex,
    strategy,
    opponentStrategy: options.opponentStrategy ?? "balanced",
    exactOpponentCommand: opponentCommand,
    heuristicDecision: exactHeuristicDecision,
    moveCandidates: exactHeuristicDecision.moveCandidates,
    switchCandidates: exactHeuristicDecision.switchCandidates,
    itemCandidates: exactHeuristicDecision.itemCandidates,
    gimmick: exactHeuristicDecision.command.gimmick ?? null,
    gimmickMove: exactHeuristicDecision.command.gimmick
      ? exactHeuristicDecision.selectedMove
      : null,
    gimmickCandidate: exactHeuristicDecision.gimmickCandidate ?? null,
    lockedSelection: lockedMoveSelection(activePokemon(state, sideIndex)),
    maxNodes: Number(options.searchNodeBudget ?? 10),
  });
  return {
    ...exactDecision,
    diagnostics: {
      ...exactDecision.diagnostics,
      selectionSource: "cheater-exact-command",
      policy: "cheater-exact-command-search",
      cheatActivated: true,
      observedOpponentCommand: structuredClone(opponentCommand),
      heuristicCommand: decision?.command
        ? structuredClone(decision.command)
        : null,
      cheaterCommand: structuredClone(exactDecision.command),
      cheaterResponseChanged: decision?.command
        ? simpleCommandKey(exactDecision.command) !==
          simpleCommandKey(decision.command)
        : null,
    },
  };
}

export function resolveSimpleCheaterDecision(
  state,
  sideIndex,
  profile,
  opponentCommand,
  fallbackDecision = null,
) {
  if (profile?.difficulty !== "cheater") {
    return (
      fallbackDecision ??
      chooseSimpleAiDecision(
        state,
        sideIndex,
        profile?.difficulty ?? "expert",
        profile?.strategy ?? "balanced",
      )
    );
  }
  const strategy = profile?.strategy ?? "balanced";
  const probability = Math.max(
    0,
    Math.min(1, Number(profile?.cheatProbability ?? 0.5)),
  );
  const roll =
    createAiRng(
      state.seed,
      sideIndex,
      state.turn * 211 + sideIndex * 17 + 59,
    ).nextIndex(10_000) / 10_000;
  if (roll >= probability) {
    const searchDecision =
      fallbackDecision?.diagnostics?.policy === "expectimax-two-turn"
        ? fallbackDecision
        : chooseSimpleAiDecision(
            state,
            sideIndex,
            "expert_search",
            strategy,
            {
              opponentStrategy: profile?.opponentStrategy ?? "balanced",
              searchNodeBudget: profile?.searchNodeBudget,
            },
          );
    return {
      ...searchDecision,
      diagnostics: {
        ...searchDecision.diagnostics,
        cheatActivated: false,
        cheatProbability: probability,
        cheatRoll: roll,
      },
    };
  }
  const cheated = applySimpleCheaterKnowledge(
    state,
    sideIndex,
    fallbackDecision,
    opponentCommand,
    {
      strategy,
      opponentStrategy: profile?.opponentStrategy ?? "balanced",
      searchNodeBudget: profile?.searchNodeBudget,
    },
  );
  return {
    ...cheated,
    diagnostics: {
      ...cheated.diagnostics,
      cheatActivated: true,
      cheatProbability: probability,
      cheatRoll: roll,
    },
  };
}

export function createSimpleAiDecisionTrace(
  state,
  sideIndex,
  decision,
  difficulty = "standard",
  strategy = "balanced",
) {
  const command = decision.command;
  const active = activePokemon(state, sideIndex);
  const winEstimate = compactWinEstimate(
    estimateBattleWinProbability(
      simpleBattleStateValueSnapshot(state, sideIndex),
    ),
  );
  const moveCandidates = decision.moveCandidates.map((candidate) => ({
    ...toAiTraceCandidate(
      {
        ...candidate,
        score: Math.round(candidate.score * 100) / 100,
      },
      {
        type: "move",
        difficulty,
        strategy,
      },
    ),
    score: Math.round(candidate.score * 100) / 100,
    selected:
      Number.isInteger(command.move) &&
      candidate.slot === command.move,
  }));
  const switchCandidates = decision.switchCandidates.map((candidate) => ({
    ...toAiTraceCandidate(
      {
        ...candidate,
        score: Math.round(candidate.score * 100) / 100,
      },
      {
        type: "switch",
        difficulty,
        strategy,
      },
    ),
    score: Math.round(candidate.score * 100) / 100,
    selected:
      Number.isInteger(command.switch) &&
      candidate.slot === decision.selectedSwitch?.slot,
  }));
  const itemCandidates = (decision.itemCandidates ?? []).map((candidate) => ({
    ...candidate,
    score: Math.round(Number(candidate.score ?? 0) * 100) / 100,
    selected:
      cleanId(command.item) === cleanId(candidate.id) &&
      Number(command.itemTarget ?? 0) ===
        Number(candidate.targetSlot ?? 0),
  }));
  const gimmickCandidates = decision.gimmickCandidate
    ? [
        {
          ...toAiActionCandidate(decision.gimmickCandidate, {
            type: "gimmick",
            difficulty,
            strategy,
          }),
          id: decision.gimmickCandidate.id,
          name: {
            mega: "메가진화",
            dynamax: "다이맥스",
            gigantamax: "거다이맥스",
            terastallize: "테라스탈",
          }[decision.gimmickCandidate.id] ?? decision.gimmickCandidate.id,
          score:
            Math.round(
              Number(decision.gimmickCandidate.score ?? 0) * 100,
            ) / 100,
          reasons: decision.gimmickCandidate.reasons ?? [],
          winProbabilityBefore:
            decision.gimmickCandidate.oneTurnEvaluation
              ?.winProbabilityBefore,
          winProbabilityAfter:
            decision.gimmickCandidate.oneTurnEvaluation
              ?.winProbabilityAfter,
          winProbabilityDelta:
            decision.gimmickCandidate.oneTurnEvaluation
              ?.winProbabilityDelta,
          searchEvaluation:
            decision.gimmickCandidate.searchEvaluation,
          selected: command.gimmick === decision.gimmickCandidate.id,
        },
      ]
    : [];
  const candidates = [
    ...moveCandidates,
    ...switchCandidates,
    ...itemCandidates,
    ...gimmickCandidates,
  ].sort(
    (left, right) => Number(right.score ?? 0) - Number(left.score ?? 0),
  );
  const policyComparison = compareAiDecisionPolicies(candidates);
  return {
    turn: state.turn + 1,
    side: sideIndex,
    sideName: state.sides[sideIndex].name,
    actor: sideIndex === 1 ? "AI" : state.sides[sideIndex].name,
    species: active.name,
    kind: Number.isInteger(command.switch)
      ? "switch"
      : command.item
        ? "item"
        : "move",
    difficulty,
    strategy,
    chosenAction:
      moveCandidates.find((candidate) => candidate.selected)?.name ??
      switchCandidates.find((candidate) => candidate.selected)?.name ??
      itemCandidates.find((candidate) => candidate.selected)?.name ??
      gimmickCandidates.find((candidate) => candidate.selected)?.name ??
      "",
    gimmick: command.gimmick ?? "",
    reason: aiDecisionReason(strategy, command.gimmick ?? ""),
    candidates,
    aiModel: "common-battle-ai",
    selectionPolicy:
      difficulty === "expert_winrate"
        ? "win-probability"
        : difficulty === "expert_search" ||
            (difficulty === "cheater" &&
              decision.diagnostics?.cheatActivated !== true &&
              decision.diagnostics?.policy === "expectimax-two-turn")
          ? "expectimax-two-turn"
        : difficulty === "cheater" && decision.diagnostics?.cheatActivated
          ? "cheater-exact-command"
        : "heuristic",
    winEstimate,
    policyComparison,
    diagnostics: decision.diagnostics ?? null,
  };
}

function compactTurnSnapshot(state) {
  return {
    turn: state.turn,
    sides: state.sides.map((side) => ({
      active: side.active,
      team: side.team.map((pokemon) => ({
        hp: pokemon.hp,
        maxHp: pokemon.stats.hp,
        fainted: pokemon.fainted,
        status: pokemon.status,
      })),
    })),
  };
}

export function runSimpleBattle(setup, options = {}) {
  const maxTurns = Number(options.maxTurns ?? DEFAULT_MAX_TURNS);
  const difficulty = String(options.difficulty ?? "standard");
  const captureAiTrace = options.captureAiTrace !== false;
  const captureTurnSnapshots = options.captureTurnSnapshots !== false;
  const aiProfiles = [0, 1].map((index) => ({
    difficulty: options.aiProfiles?.[index]?.difficulty ?? difficulty,
    strategy: options.aiProfiles?.[index]?.strategy ?? "balanced",
    cheatProbability: Math.max(
      0,
      Math.min(1, Number(options.aiProfiles?.[index]?.cheatProbability ?? 0.5)),
    ),
  }));
  let state = createSimpleBattle(setup);
  state.turnSnapshots = captureTurnSnapshots ? [compactTurnSnapshot(state)] : [];
  while (state.status === "running" && state.turn < maxTurns) {
    const baseDecisions = state.sides.map((_, sideIndex) => {
      const profile = aiProfiles[sideIndex];
      if (profile.difficulty === "cheater") return null;
      return chooseSimpleAiDecision(
        state,
        sideIndex,
        profile.difficulty,
        profile.strategy,
        {
          opponentStrategy:
            aiProfiles[sideIndex === 0 ? 1 : 0]?.strategy ?? "balanced",
        },
      );
    });
    const decisions = baseDecisions.map((baseDecision, sideIndex) => {
      const profile = aiProfiles[sideIndex];
      if (profile.difficulty !== "cheater") return baseDecision;
      const opponentSide = sideIndex === 0 ? 1 : 0;
      const opponentDecision =
        baseDecisions[opponentSide] ??
        chooseSimpleAiDecision(
          state,
          opponentSide,
          "expert",
          aiProfiles[opponentSide].strategy,
        );
      return resolveSimpleCheaterDecision(
        state,
        sideIndex,
        {
          ...profile,
          opponentStrategy:
            aiProfiles[opponentSide]?.strategy ?? "balanced",
        },
        opponentDecision.command,
      );
    });
    const tracedDecisions = decisions.map((decision, sideIndex) => {
      const profile = aiProfiles[sideIndex];
      return {
        command: decision.command,
        trace: captureAiTrace
          ? createSimpleAiDecisionTrace(
              state,
              sideIndex,
              decision,
              profile.difficulty,
              profile.strategy,
            )
          : null,
      };
    });
    if (captureAiTrace) {
      state.aiTrace.push(
        ...tracedDecisions.map((decision) => decision.trace),
      );
    }
    const commands = tracedDecisions.map((decision) => decision.command);
    state = resolveSimpleTurn(state, commands);
    if (captureTurnSnapshots) {
      state.turnSnapshots.push(compactTurnSnapshot(state));
    }
  }
  if (state.status === "running") {
    state.status = "turn_limit";
    state.events.push({ turn: state.turn, type: "turn_limit" });
  }
  return state;
}
