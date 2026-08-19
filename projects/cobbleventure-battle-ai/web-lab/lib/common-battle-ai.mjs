import MOVE_ROLE_CATALOG from "../../data/ai/ai-move-role-classification.json" with { type: "json" };
import POKEMON_ROLE_OVERRIDES from "../../data/ai/ai-pokemon-role-overrides.json" with { type: "json" };
import {
  analyzeSharedTeamProfileJson,
  evaluateActionReachabilityJson,
  estimateWinProbabilityJson,
  evaluateMoveRuleFactsJson,
  evaluateRoleProgressJson,
  evaluateSetupLikelihoodJson,
  evaluateSetupThreatJson,
  evaluateSwitchRuleFactsJson,
  evaluateThreatCountersJson,
  scoreObservedActionCandidateJson,
  scoreProjectedGimmickJson,
} from "./shared-ai-core.mjs";
const DIFFICULTY_LABELS = {
  novice: "초급",
  standard: "보통",
  advanced: "상급",
  expert: "전문가(휴리스틱)",
  expert_winrate: "전문가(승률 기반)",
  expert_search: "전문가(2턴 탐색)",
  cheater: "치터",
};
const ROLE_LABELS = {
  lead: "선봉",
  ace: "에이스",
  subAce: "준에이스",
  setupSweeper: "랭크업 스위퍼",
  wall: "막이",
  pivot: "피벗",
  hazardControl: "판 장악",
  revengeKiller: "복수 처리",
  disruptor: "방해",
  support: "지원",
};
const STRATEGY_ROLE_WEIGHTS = {
  balanced: {
    ace: 0.9,
    subAce: 0.65,
    setupSweeper: 0.8,
    wall: 0.75,
    pivot: 0.8,
    hazardControl: 0.8,
    revengeKiller: 0.75,
    disruptor: 0.75,
    support: 0.7,
  },
  aggressive: {
    ace: 1.35,
    subAce: 0.95,
    setupSweeper: 1.05,
    revengeKiller: 1.0,
    pivot: 0.55,
    disruptor: 0.45,
    hazardControl: 0.35,
    wall: 0.15,
    support: 0.2,
  },
  defensive: {
    wall: 1.35,
    support: 1.05,
    disruptor: 0.95,
    hazardControl: 0.85,
    pivot: 0.65,
    revengeKiller: 0.55,
    setupSweeper: 0.35,
    ace: 0.25,
    subAce: 0.2,
  },
  ace_check: {
    revengeKiller: 1.25,
    disruptor: 1.15,
    ace: 0.9,
    subAce: 0.65,
    pivot: 0.75,
    hazardControl: 0.65,
    wall: 0.6,
    support: 0.45,
    setupSweeper: 0.35,
  },
  reckless_ace: {
    ace: 1.55,
    subAce: 0.9,
    setupSweeper: 1.25,
    revengeKiller: 0.95,
    pivot: 0.35,
    disruptor: 0.25,
    hazardControl: 0.2,
    wall: 0.05,
    support: 0.05,
  },
  setup: {
    setupSweeper: 1.55,
    support: 0.85,
    disruptor: 0.75,
    ace: 0.7,
    subAce: 0.55,
    pivot: 0.65,
    hazardControl: 0.55,
    revengeKiller: 0.35,
    wall: 0.25,
  },
  hazard: {
    hazardControl: 1.55,
    lead: 1.2,
    pivot: 0.9,
    disruptor: 0.8,
    support: 0.55,
    wall: 0.45,
    ace: 0.3,
    subAce: 0.2,
    setupSweeper: 0.25,
  },
  tempo: {
    pivot: 1.45,
    revengeKiller: 1.05,
    disruptor: 0.9,
    ace: 0.75,
    subAce: 0.6,
    hazardControl: 0.65,
    support: 0.45,
    setupSweeper: 0.35,
    wall: 0.2,
  },
};
const STRATEGY_ALIASES = {
  balance: "balanced",
  balanced: "balanced",
  aggressive: "aggressive",
  attack: "aggressive",
  offense: "aggressive",
  defensive: "defensive",
  defense: "defensive",
  acecheck: "ace_check",
  ace_check: "ace_check",
  antiace: "ace_check",
  recklessace: "reckless_ace",
  reckless_ace: "reckless_ace",
  ace: "reckless_ace",
  setup: "setup",
  setup_sweeper: "setup",
  setupsweeper: "setup",
  hazard: "hazard",
  hazardcontrol: "hazard",
  hazard_control: "hazard",
  tempo: "tempo",
  pivot: "tempo",
  unpredictable: "tempo",
};
export const SELECTABLE_AI_STRATEGIES = Object.freeze([
  "balanced",
  "aggressive",
  "defensive",
  "ace_check",
  "reckless_ace",
  "setup",
  "hazard",
  "tempo",
]);
const ROLE_VALUE_SCALE = 4;
const HAZARD_MAX_LAYERS = {
  stealthrock: 1,
  stickyweb: 1,
  spikes: 3,
  toxicspikes: 2,
};
const HAZARD_MOVE_CONDITIONS = {
  ceaselessedge: "spikes",
  spikes: "spikes",
  stealthrock: "stealthrock",
  stickyweb: "stickyweb",
  stoneaxe: "stealthrock",
  toxicspikes: "toxicspikes",
};
const BOOST_RESET_MOVE_IDS = new Set(["haze", "clearsmog"]);
const PHAZE_MOVE_IDS = new Set([
  "roar",
  "whirlwind",
  "dragontail",
  "circlethrow",
]);
const TAUNT_MOVE_IDS = new Set(["taunt"]);
const RECOVERY_MOVE_IDS = new Set([
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
const PIVOT_MOVE_IDS = new Set(["partingshot", "uturn", "voltswitch", "flipturn"]);
const SELF_SACRIFICE_MOVE_IDS = new Set([
  "explosion",
  "mistyexplosion",
  "selfdestruct",
]);
const ACE_SETUP_ABILITY_IDS = new Set([
  "asoneglastrier",
  "asonespectrier",
  "beastboost",
  "chillingneigh",
  "competitive",
  "contrary",
  "defiant",
  "download",
  "grimneigh",
  "intrepidsword",
  "moxie",
  "soulheart",
  "speedboost",
]);
const DYNAMAX_SCORE_THRESHOLD = 18;
function toRuleFactBag(kind, candidate, extras = {}, tags = []) {
  const numbers = {};
  const flags = {};
  const strings = {};
  const stringLists = {};
  const visit = (value, key, depth = 0) => {
    if (!key || value === undefined || value === null || depth > 4) return;
    if (typeof value === "number" && Number.isFinite(value)) {
      numbers[key] = value;
    } else if (typeof value === "boolean") {
      flags[key] = value;
    } else if (typeof value === "string") {
      strings[key] = value;
    } else if (Array.isArray(value)) {
      if (value.every((entry) => typeof entry === "string")) {
        stringLists[key] = value;
      } else {
        value.forEach((entry, index) =>
          visit(entry, `${key}.${index}`, depth + 1),
        );
        numbers[`${key}.length`] = value.length;
      }
    } else if (typeof value === "object") {
      Object.entries(value).forEach(([childKey, childValue]) =>
        visit(childValue, `${key}.${childKey}`, depth + 1),
      );
    }
  };
  Object.entries(candidate ?? {}).forEach(([key, value]) => visit(value, key));
  Object.entries(extras).forEach(([key, value]) => visit(value, key));
  return {
    kind,
    numbers,
    flags,
    strings,
    tags: [...new Set(tags)],
    stringLists,
  };
}

const AI_SCORING_RULES = [
  {
    id: "immediate_ko_response",
    source: "RunBunAI.ImmediateKoResponseRule",
    portStatus: "implemented-light",
    summary: "선공 확정 KO가 상대 전개 위협을 즉시 끊을 때 보너스를 준다.",
  },
  {
    id: "entry_hazard_placement",
    source: "RunBunAI.EntryHazardPlacementRule",
    portStatus: "implemented-light",
    summary: "남은 상대가 많고 설치 가능한 상황이면 판 설치 기술을 높게 본다.",
  },
  {
    id: "setup_opportunity",
    source: "RunBunAI.SetupOpportunityRule",
    portStatus: "implemented-light",
    summary: "상대 피해가 낮을 때 랭크업 가치를 올리고 KO 위험이면 낮춘다.",
  },
  {
    id: "setup_disruption",
    source: "RunBunAI.SetupDisruptionRule",
    portStatus: "implemented-light",
    summary: "상대 랭크업 위협에는 흑안개/클리어스모그/강제교체/도발을 보정한다.",
  },
  {
    id: "switch_matchup",
    source: "RunBunAI.SwitchMatchupRule",
    portStatus: "implemented-light",
    summary: "교체 후보의 피격 감소, 공격 개선, 체력, 상태, 랭크 손실을 함께 본다.",
  },
  {
    id: "immediate_ko_dominance",
    source: "RunBunAI.ImmediateKoDominanceRule",
    portStatus: "implemented-light",
    summary: "안전한 확정 KO가 있을 때 비공격/비마무리 행동을 억제한다.",
  },
  {
    id: "recovery_move_value",
    source: "RunBunAI.RecoveryMoveValueRule",
    portStatus: "implemented-light",
    summary: "저체력 또는 예상 피격 후 위험한 체력일 때 회복기 가치를 올린다.",
  },
  {
    id: "parting_shot_pivot",
    source: "RunBunAI.PartingShotPivotRule",
    portStatus: "implemented-light",
    summary: "안전한 피벗 기술은 즉시 교체보다 높게 평가한다.",
  },
  {
    id: "lethal_switch_in",
    source: "RunBunAI.LethalSwitchInRule",
    portStatus: "implemented-light",
    summary: "교체 직후 쓰러질 후보를 강하게 억제한다.",
  },
  {
    id: "repeated_switch_penalty",
    source: "RunBunAI.RepeatedSwitchPenaltyRule",
    portStatus: "implemented-light",
    summary: "직전 턴 교체 후 의미 없는 연속 교체를 억제한다.",
  },
  {
    id: "guaranteed_ko_switch_penalty",
    source: "RunBunAI.GuaranteedKoSwitchPenaltyRule",
    portStatus: "implemented-light",
    summary: "안전한 확정 KO를 포기하는 자발 교체를 억제한다.",
  },
  {
    id: "dynamax_switch_penalty",
    source: "RunBunAI.DynamaxSwitchPenaltyRule",
    portStatus: "implemented-light",
    summary: "남은 다이맥스 턴을 버리는 교체의 기회비용을 반영한다.",
  },
  {
    id: "dynamax_activation_value",
    source: "CobbleventureAI",
    portStatus: "implemented-light",
    summary: "다이맥스 사용 자체를 피해/생존/랭크업 기회비용으로 점수화한다.",
  },
];

export function createAiRng(seed, side = 0, salt = 0) {
  let state = (Number(seed) ^ 0x9e3779b9 ^ (side + 1) * 0x85ebca6b ^ salt) >>> 0;
  return {
    nextIndex(length) {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      state >>>= 0;
      return length > 0 ? state % length : 0;
    },
  };
}

export function selectSeededAiStrategy(
  seed,
  preferredStrategies = SELECTABLE_AI_STRATEGIES,
) {
  const normalized = Array.from(
    new Set(
      (Array.isArray(preferredStrategies) ? preferredStrategies : [])
        .map((strategy) => canonicalStrategy(strategy))
        .filter((strategy) => SELECTABLE_AI_STRATEGIES.includes(strategy)),
    ),
  );
  const candidates =
    normalized.length > 0 ? normalized : SELECTABLE_AI_STRATEGIES;
  const rng = createAiRng(seed, 1, 0x51a7e9);
  return candidates[rng.nextIndex(candidates.length)];
}

function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function candidateTagSet(candidate) {
  const entry = moveRoleEntry(candidate.id ?? candidate.moveId ?? candidate.name);
  return new Set([...(candidate.roleTags ?? entry?.tags ?? [])].map((tag) => cleanId(tag)));
}

function setupThreatTier(candidate) {
  const raw =
    candidate.setupThreatTier ??
    candidate.opponentSetupThreatTier ??
    candidate.setupThreat?.tier ??
    candidate.opponentSetupThreat?.tier ??
    0;
  const key = cleanId(raw);
  if (key === "tier3" || key === "3" || key === "sweep") return 3;
  if (key === "tier2" || key === "2" || key === "danger") return 2;
  if (key === "tier1" || key === "1") return 1;
  return 0;
}

function opponentSetupLikelihood(candidate) {
  const likelihood = ratioValue(
    candidate.opponentSetupFirstTurnLikelihood,
    candidate.opponentSetupLikelihood,
    candidate.setupLikelihood,
    0,
  );
  return Math.max(0, Math.min(1, likelihood ?? 0));
}

function opponentLikelyToSetup(candidate) {
  const setupMoveCount = Math.max(0, Number(candidate.opponentSetupMoveCount ?? 0));
  if (setupMoveCount <= 0) return false;
  return (
    candidate.opponentLikelyFirstTurnSetup === true ||
    opponentSetupLikelihood(candidate) >= 0.55 ||
    setupThreatTier(candidate) >= 2
  );
}

function positiveBoostTotal(boosts = {}) {
  if (!boosts || typeof boosts !== "object") return 0;
  return Object.values(boosts).reduce(
    (sum, value) => sum + Math.max(0, Number(value ?? 0)),
    0,
  );
}

function negativeBoostTotal(boosts = {}) {
  if (!boosts || typeof boosts !== "object") return 0;
  return Object.values(boosts).reduce(
    (sum, value) => sum + Math.max(0, -Number(value ?? 0)),
    0,
  );
}

function ratioValue(...values) {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return undefined;
}

function scoreAdjustment(code, label, value, weight, message) {
  return { code, label, value, weight, message };
}

function isDamagingCandidate(candidate) {
  return (
    Number(candidate.expectedDamage ?? 0) > 0 ||
    Number(candidate.power ?? 0) > 0 ||
    ["Physical", "Special", "physical", "special"].includes(candidate.category)
  );
}

function isSafeFinisher(candidate) {
  return (
    candidate.koChance === "guaranteed" &&
    (candidate.actsBeforeOpponent === true ||
      Number(candidate.priority ?? 0) > 0 ||
      candidate.survivalProbability === 1 ||
      candidate.safeFinisher === true)
  );
}

function hasSetupMove(moveCandidates = []) {
  return moveCandidates.some((candidate) => candidateTagSet(candidate).has("setupboost"));
}

function hasFightingAttack(moveCandidates = []) {
  return moveCandidates.some(
    (candidate) =>
      cleanId(candidate.type) === "fighting" &&
      isDamagingCandidate(candidate) &&
      cleanId(candidate.id) !== "maxguard",
  );
}

function isSelfSacrificeCandidate(candidate) {
  const moveId = cleanId(candidate.id ?? candidate.moveId ?? candidate.name);
  return (
    SELF_SACRIFICE_MOVE_IDS.has(moveId) ||
    candidate.selfSacrifice === true ||
    candidate.selfKo === true
  );
}

function canonicalStrategy(strategy) {
  const key = cleanId(strategy);
  return STRATEGY_ALIASES[key] ?? strategy ?? "balanced";
}

export function moveRoleEntry(moveId) {
  return MOVE_ROLE_CATALOG.moves?.[cleanId(moveId)] ?? null;
}

export function moveRoleScores(moveId) {
  return moveRoleEntry(moveId)?.roleScores ?? {};
}

export function moveRoleValue(candidate, strategy = "balanced") {
  const scores = candidate.roleScores ?? moveRoleScores(candidate.id ?? candidate.moveId);
  const weights =
    STRATEGY_ROLE_WEIGHTS[canonicalStrategy(strategy)] ??
    STRATEGY_ROLE_WEIGHTS.balanced;
  return Math.round(
    Object.entries(scores).reduce(
      (sum, [role, score]) => sum + Number(score ?? 0) * Number(weights[role] ?? 0),
      0,
    ) *
      ROLE_VALUE_SCALE *
      100,
  ) / 100;
}

export function enrichMoveCandidateWithRole(candidate, strategy = "balanced") {
  const entry = moveRoleEntry(candidate.id ?? candidate.moveId ?? candidate.name);
  const roleScores = candidate.roleScores ?? entry?.roleScores ?? {};
  const roleValue =
    candidate.roleValue ?? moveRoleValue({ ...candidate, roleScores }, strategy);
  return {
    ...candidate,
    roleScores,
    roleTags: candidate.roleTags ?? entry?.tags ?? [],
    roleValue,
  };
}

export function teamRoleLabel(role) {
  return ROLE_LABELS[role] ?? role;
}

function addRoleScore(roleScores, role, value) {
  roleScores[role] = (Number(roleScores[role]) || 0) + value;
}

function pokemonMoveIds(member = {}) {
  return (member.moves ?? member.moveset ?? member.moveSet ?? [])
    .map((move) =>
      cleanId(
        typeof move === "string"
          ? move
          : move?.id ?? move?.moveId ?? move?.name ?? move?.move,
      ),
    )
    .filter(Boolean);
}

function pokemonStat(member = {}, keys = []) {
  const sources = [member.stats, member.baseStats, member.baseStatsRaw];
  for (const source of sources) {
    if (!source) continue;
    for (const key of keys) {
      const value = Number(source[key]);
      if (Number.isFinite(value) && value > 0) return value;
    }
  }
  return 0;
}

function pokemonDisplayName(member = {}) {
  return member.name ?? member.species ?? member.id ?? "Unknown";
}

function pokemonRoleOverride(member = {}) {
  const roles = POKEMON_ROLE_OVERRIDES.roles ?? {};
  const candidates = [
    member.id,
    member.species,
    member.resolvedSpecies,
    member.name,
    String(member.species ?? "").split("-")[0],
    String(member.resolvedSpecies ?? "").split("-")[0],
  ]
    .map(cleanId)
    .filter(Boolean);
  for (const key of candidates) {
    if (roles[key]) return roles[key];
  }
  return null;
}

function topRoles(roleScores, limit = 4) {
  return Object.entries(roleScores)
    .map(([role, score]) => ({ role, score: Math.round(score * 100) / 100 }))
    .filter((entry) => entry.score > 0)
    .sort((left, right) => right.score - left.score)
    .slice(0, limit);
}

function arrayValues(value) {
  if (Array.isArray(value)) return value;
  if (value === undefined || value === null) return [];
  return [value];
}

function pokemonLevel(member = {}) {
  const level = Number(member.level ?? member.lvl ?? member.details?.level);
  return Number.isFinite(level) && level > 0 ? level : 0;
}

function pokemonAbilityId(member = {}) {
  const ability =
    member.ability ??
    member.abilityId ??
    member.currentAbility ??
    member.baseAbility ??
    "";
  return cleanId(
    typeof ability === "string"
      ? ability
      : ability?.id ?? ability?.name ?? ability?.ability,
  );
}

function manualAcePreference(member = {}) {
  const ai = member.ai ?? member.aiProfile ?? {};
  const directValues = [
    member.ace,
    member.isAce,
    member.forceAce,
    member.notAce,
    member.aiAce,
    ai.ace,
    ai.isAce,
    ai.forceAce,
    ai.notAce,
    member.gimmicks?.ace,
    member.gimmicks?.forceAce,
  ];
  const roleValues = [
    member.role,
    member.aiRole,
    ai.role,
    ...arrayValues(member.roles),
    ...arrayValues(member.aiRoles),
    ...arrayValues(ai.roles),
  ];
  const blocked =
    directValues.some((value) => value === false) ||
    directValues.some((value) =>
      ["notace", "noace", "nonace", "sacrifice", "expendable"].includes(cleanId(value)),
    ) ||
    roleValues.some((value) =>
      ["notace", "noace", "nonace", "sacrifice", "expendable"].includes(cleanId(value)),
    );
  if (blocked) {
    return { forced: false, blocked: true, priority: 0, reason: "사람 지정: 에이스 제외" };
  }

  const forced =
    directValues.some((value) => value === true || Number(value) > 0) ||
    roleValues.some((value) =>
      ["ace", "mainace", "primaryace", "coreace"].includes(cleanId(value)),
    );
  const priority = Math.max(
    0,
    Number(member.acePriority ?? ai.acePriority ?? member.gimmicks?.acePriority ?? 0),
  );
  if (forced || priority > 0) {
    return {
      forced: true,
      blocked: false,
      priority,
      reason: priority > 0 ? `사람 지정: 에이스 우선도 ${priority}` : "사람 지정: 에이스",
    };
  }
  return { forced: false, blocked: false, priority: 0, reason: "" };
}

function gimmickAceProfile(member = {}) {
  const gimmicks = member.gimmicks ?? {};
  const itemId = cleanId(member.item ?? member.heldItem ?? member.itemId);
  const nonMegaStoneItems = new Set(["eviolite"]);
  const mega =
    gimmicks.mega === true ||
    gimmicks.megaEvolution === true ||
    member.canMegaEvo === true ||
    itemId.includes("mega") ||
    (itemId.endsWith("ite") && !nonMegaStoneItems.has(itemId));
  const gigantamax =
    gimmicks.gigantamax === true ||
    gimmicks.gmax === true ||
    member.canGigantamax === true ||
    member.gigantamax === true;
  const dynamax =
    gimmicks.dynamax === true ||
    gimmicks.forceDynamax === true ||
    member.canDynamax === true ||
    member.dynamax === true;
  const tera =
    gimmicks.tera === true ||
    gimmicks.terastallize === true ||
    Boolean(member.teraType ?? member.teratype);
  let value = 0;
  const reasons = [];
  if (mega) {
    value += 3.2;
    reasons.push("메가진화 자원");
  }
  if (gigantamax) {
    value += 2.4;
    reasons.push("거다이맥스 자원");
  } else if (dynamax) {
    value += 1.8;
    reasons.push("다이맥스 자원");
  }
  if (tera) {
    value += 1;
    reasons.push("테라스탈 자원");
  }
  return { value, reasons };
}

function computeAceProfile({
  member = {},
  roleScores = {},
  speciesAceScore = 0,
  tags = new Set(),
  stats = {},
  teamContext = {},
} = {}) {
  const manual = manualAcePreference(member);
  if (manual.blocked) {
    return {
      score: -Infinity,
      qualifies: false,
      manual,
      reasons: [manual.reason],
    };
  }

  const reasons = [];
  let score = 0;
  const offense = Math.max(Number(stats.attack ?? 0), Number(stats.specialAttack ?? 0));
  const speed = Number(stats.speed ?? 0);
  const baseTotal = [
    stats.hp,
    stats.attack,
    stats.defense,
    stats.specialAttack,
    stats.specialDefense,
    stats.speed,
  ].reduce((sum, value) => sum + Math.max(0, Number(value ?? 0)), 0);
  const rawAce = Math.max(0, Number(roleScores.ace ?? 0));
  const setupScore = Math.max(0, Number(roleScores.setupSweeper ?? 0));
  const revengeScore = Math.max(0, Number(roleScores.revengeKiller ?? 0));
  const defensiveUtility = Math.max(
    Number(roleScores.wall ?? 0),
    Number(roleScores.support ?? 0),
    Number(roleScores.hazardControl ?? 0),
    Number(roleScores.disruptor ?? 0),
    Number(roleScores.pivot ?? 0),
  );
  const gimmick = gimmickAceProfile(member);
  const level = pokemonLevel(member);
  const maxLevel = Number(teamContext.maxLevel ?? 0);
  const highestLevel = level > 0 && maxLevel > 0 && level >= maxLevel;
  const levelGap = highestLevel ? level - Number(teamContext.secondMaxLevel ?? level) : 0;
  const hasSetup = tags.has("setupboost") || setupScore >= 3.5;
  const setupAbility = ACE_SETUP_ABILITY_IDS.has(pokemonAbilityId(member));
  const batonPassSupport = teamContext.hasBatonPassSupport === true;
  const hasSetupRoute = hasSetup || setupAbility || batonPassSupport;
  const hasOffensiveStat = offense >= 115;
  const hasFastStat = speed >= 100;
  const hasHighBst = baseTotal >= 570;
  const hasStrongSpeciesPrior = Number(speciesAceScore) >= 2.4;

  if (manual.forced) {
    score += 100 + manual.priority;
    reasons.push(manual.reason);
  }
  if (rawAce > 0) {
    const value = Math.min(4.5, rawAce * 0.55);
    score += value;
    reasons.push(`공격 역할 성향 ${Math.round(rawAce * 10) / 10}`);
  }
  if (offense >= 145) {
    score += 3.2;
    reasons.push("매우 높은 공격 능력치");
  } else if (offense >= 125) {
    score += 2.3;
    reasons.push("높은 공격 능력치");
  } else if (offense >= 115) {
    score += 1.4;
    reasons.push("공격 능력치 우수");
  }
  if (speed >= 120) {
    score += 2.2;
    reasons.push("매우 빠른 스피드");
  } else if (speed >= 100) {
    score += 1.4;
    reasons.push("빠른 스피드");
  } else if (speed >= 85) {
    score += 0.6;
    reasons.push("준수한 스피드");
  }
  if (hasSetup) {
    score += 2.6;
    reasons.push("랭크업 전개 가능");
  }
  if (setupAbility) {
    score += 1.8;
    reasons.push("특성으로 랭크업 가능");
  }
  if (batonPassSupport && !hasSetup) {
    score += 1.2;
    reasons.push("팀의 배턴터치 전개 수혜");
  }
  if (revengeScore >= 3) {
    score += 0.8;
    reasons.push("마무리/복수 처리 성향");
  }
  if (baseTotal >= 670) {
    score += 3;
    reasons.push("초전설급 종족값");
  } else if (baseTotal >= 600) {
    score += 2.2;
    reasons.push("높은 종족값");
  } else if (baseTotal >= 570) {
    score += 1.3;
    reasons.push("준전설급 종족값");
  } else if (baseTotal >= 530) {
    score += 0.7;
    reasons.push("평균 이상 종족값");
  }
  if (highestLevel) {
    const value = levelGap >= 5 ? 2.4 : 1.1;
    score += value;
    reasons.push(levelGap >= 5 ? "파티 내 고레벨 에이스 후보" : "파티 내 최고 레벨");
  }
  if (gimmick.value > 0) {
    score += gimmick.value;
    reasons.push(...gimmick.reasons);
  }

  const offensiveAnchor =
    manual.forced ||
    gimmick.value > 0 ||
    hasOffensiveStat ||
    hasFastStat ||
    hasSetup ||
    hasHighBst ||
    hasStrongSpeciesPrior ||
    levelGap >= 5;
  if (!offensiveAnchor && defensiveUtility >= rawAce) {
    score -= 3.5;
    reasons.push("방어/지원 성향이 더 강해 에이스 제외 경향");
  } else if (defensiveUtility >= rawAce + 2 && !manual.forced && gimmick.value <= 0) {
    score -= 1.5;
    reasons.push("막이/지원 역할 보존");
  }

  const estimatedKoCapacity =
    manual.forced ||
    (hasSetupRoute &&
      (offense >= 100 || rawAce >= 2 || gimmick.value > 0))
      ? 2
      : offense >= 135 && speed >= 100
        ? 2
        : 1;
  const qualifies = manual.forced || (score >= 5.8 && offensiveAnchor);
  return {
    score: Math.round(score * 100) / 100,
    qualifies,
    manual,
    reasons,
    offense,
    speed,
    hasSetup,
    setupAbility,
    batonPassSupport,
    hasSetupRoute,
    estimatedKoCapacity,
    offensiveAnchor,
  };
}

function analyzeTeamMemberRole(member = {}, index = 0, teamContext = {}) {
  const roleScores = Object.fromEntries(Object.keys(ROLE_LABELS).map((role) => [role, 0]));
  const reasons = [];
  const warnings = [];
  const moveIds = pokemonMoveIds(member);
  const tags = new Set();
  const hasBatonPass = moveIds.includes("batonpass");
  const hasBatonPassSetupMove = (member.moves ?? member.moveset ?? []).some(
    (move) => {
      const moveId = cleanId(
        typeof move === "string"
          ? move
          : move?.id ?? move?.moveId ?? move?.name ?? move?.move,
      );
      if (!moveId || moveId === "batonpass") return false;
      const boosts =
        typeof move === "string"
          ? {}
          : move?.selfBoosts ?? move?.boosts ?? {};
      const explicitBoost = Object.values(boosts).some(
        (amount) => Number(amount ?? 0) > 0,
      );
      const catalogEntry = moveRoleEntry(moveId);
      return (
        explicitBoost ||
        (catalogEntry?.tags ?? []).some(
          (tag) => cleanId(tag) === "setupboost",
        ) ||
        Number(catalogEntry?.roleScores?.setupSweeper ?? 0) >= 2.5
      );
    },
  );
  const batonPassSetupAbility = ["speedboost", "moody"].includes(
    cleanId(member.ability),
  );

  if (moveIds.length === 0) {
    warnings.push("기술 정보 없음");
  }

  for (const moveId of moveIds) {
    const entry = moveRoleEntry(moveId);
    if (!entry) continue;
    for (const [role, score] of Object.entries(entry.roleScores ?? {})) {
      addRoleScore(roleScores, role, Number(score ?? 0));
    }
    for (const tag of entry.tags ?? []) tags.add(cleanId(tag));
  }

  const speciesOverride = pokemonRoleOverride(member);
  if (speciesOverride) {
    for (const [role, score] of Object.entries(speciesOverride.roleScores ?? {})) {
      addRoleScore(roleScores, role, Number(score ?? 0));
    }
    for (const reason of speciesOverride.reasons ?? []) {
      reasons.push(`포켓몬 기본 역할: ${reason}`);
    }
  }

  const attack = pokemonStat(member, ["attack", "atk"]);
  const specialAttack = pokemonStat(member, ["specialAttack", "specialAtk", "spa"]);
  const speed = pokemonStat(member, ["speed", "spe"]);
  const hp = pokemonStat(member, ["hp"]);
  const defense = pokemonStat(member, ["defence", "defense", "def"]);
  const specialDefense = pokemonStat(member, [
    "specialDefence",
    "specialDefense",
    "specialDef",
    "spd",
  ]);
  const offense = Math.max(attack, specialAttack);
  const bulk = hp + defense + specialDefense;

  if (offense === 0 && bulk === 0) {
    warnings.push("능력치 정보 없음");
  }

  if (index === 0) {
    addRoleScore(roleScores, "lead", 1.2);
    reasons.push("선봉 슬롯이라 초반 판 만들기 가능성을 봤습니다.");
  }
  if (offense >= 115) {
    addRoleScore(roleScores, "ace", 2.4);
    reasons.push("공격 능력치가 높아 에이스/돌파 역할 후보입니다.");
  }
  if (speed >= 100) {
    addRoleScore(roleScores, "revengeKiller", 1.8);
    addRoleScore(roleScores, "ace", 0.7);
    reasons.push("스피드가 높아 복수 처리와 마무리 역할을 기대할 수 있습니다.");
  }
  if (bulk >= 300) {
    addRoleScore(roleScores, "wall", 2.2);
    reasons.push("내구 합이 높아 교체 받이와 장기전 자원으로 봤습니다.");
  }
  if (tags.has("setupboost")) {
    addRoleScore(roleScores, "setupSweeper", 2.5);
    reasons.push("랭크업 기술을 보유해 전개형 스위퍼 후보입니다.");
  }
  if (tags.has("recovery")) {
    addRoleScore(roleScores, "wall", 1.8);
    addRoleScore(roleScores, "support", 0.8);
    reasons.push("회복기를 보유해 막이/유지력 역할 가치가 있습니다.");
  }
  if (tags.has("pivot")) {
    addRoleScore(roleScores, "pivot", 2.2);
    reasons.push("피벗 기술로 유리 대면을 연결할 수 있습니다.");
  }
  if (
    hasBatonPass &&
    (hasBatonPassSetupMove || batonPassSetupAbility)
  ) {
    addRoleScore(roleScores, "pivot", 1.6);
    addRoleScore(roleScores, "support", 1.2);
    reasons.push(
      "랭크업 수단과 배턴터치를 함께 보유해 에이스 전개 요원으로 평가합니다.",
    );
  }
  if (tags.has("hazardset") || tags.has("hazardremove")) {
    addRoleScore(roleScores, "hazardControl", 2.2);
    reasons.push("설치물 설치/제거로 판 관리 역할을 맡을 수 있습니다.");
  }
  if (tags.has("priority") || tags.has("finisher")) {
    addRoleScore(roleScores, "revengeKiller", 1.8);
    reasons.push("선공기/마무리 태그로 복수 처리 가치가 있습니다.");
  }
  if (tags.has("disrupt") || tags.has("setupanswer")) {
    addRoleScore(roleScores, "disruptor", 1.8);
    reasons.push("상대 전개를 끊는 방해 기술 가치가 있습니다.");
  }

  const aceProfile = computeAceProfile({
    member,
    roleScores,
    speciesAceScore: Number(speciesOverride?.roleScores?.ace ?? 0),
    tags,
    stats: { attack, specialAttack, speed, hp, defense, specialDefense },
    teamContext,
  });
  const displayRoleScores = { ...roleScores };
  if (!aceProfile.qualifies) {
    displayRoleScores.ace = 0;
  }

  const roles = topRoles(displayRoleScores);
  return {
    slot: Number(member.slot ?? index + 1),
    pokemonId: cleanId(member.id ?? member.species ?? member.name),
    species: pokemonDisplayName(member),
    primaryRole: roles[0]?.role ?? "support",
    roles,
    roleScores: displayRoleScores,
    rawRoleScores: roleScores,
    aceScore: aceProfile.score,
    aceProfile,
    batonPassProfile: {
      qualifies:
        hasBatonPass &&
        (hasBatonPassSetupMove || batonPassSetupAbility),
      hasBatonPass,
      hasSetupMove: hasBatonPassSetupMove,
      setupAbility: batonPassSetupAbility ? cleanId(member.ability) : "",
    },
    moveIds,
    reasons: reasons.slice(0, 4),
    warnings,
  };
}

function finalizeTeamAceRoles(roleEntries, teamContext) {
  const candidates = roleEntries
    .filter(
      (entry) =>
        entry.aceProfile?.manual?.blocked !== true &&
        Number.isFinite(Number(entry.aceScore)),
    )
    .sort(
      (left, right) =>
        Number(right.aceProfile?.manual?.forced === true) -
          Number(left.aceProfile?.manual?.forced === true) ||
        Number(right.aceProfile?.manual?.priority ?? 0) -
          Number(left.aceProfile?.manual?.priority ?? 0) ||
        Number(right.aceScore ?? 0) - Number(left.aceScore ?? 0) ||
        left.slot - right.slot,
    );
  const forcedCandidates = candidates.filter(
    (entry) => entry.aceProfile?.manual?.forced === true,
  );
  const sweepCandidates = candidates.filter(
    (entry) =>
      Number(entry.aceProfile?.estimatedKoCapacity ?? 1) >= 2 &&
      entry.aceProfile?.offensiveAnchor === true,
  );
  const noTeamSetupRoute = teamContext.hasTeamSetupRoute !== true;
  const strongestOffenseCandidates = candidates
    .filter(
      (entry) =>
        entry.aceProfile?.offensiveAnchor === true ||
        entry.aceProfile?.manual?.forced === true,
    )
    .sort(
      (left, right) =>
        Number(right.aceProfile?.offense ?? 0) -
          Number(left.aceProfile?.offense ?? 0) ||
        Number(right.aceScore ?? 0) - Number(left.aceScore ?? 0) ||
        left.slot - right.slot,
    );
  const primaryAce =
    forcedCandidates[0] ??
    (noTeamSetupRoute ? strongestOffenseCandidates[0] : sweepCandidates[0]) ??
    strongestOffenseCandidates[0] ??
    null;
  const subAceCandidates = candidates
    .filter((entry) => {
      if (!primaryAce || entry.slot === primaryAce.slot) return false;
      const scoreGap =
        Number(primaryAce.aceScore ?? 0) - Number(entry.aceScore ?? 0);
      return (
        entry.aceProfile?.qualifies === true ||
        Number(entry.aceProfile?.estimatedKoCapacity ?? 1) >= 2 ||
        (entry.aceProfile?.offensiveAnchor === true && scoreGap <= 3)
      );
    })
    .slice(0, 2);
  const subAceSlots = new Set(subAceCandidates.map((entry) => entry.slot));

  for (const entry of roleEntries) {
    const isAce = primaryAce?.slot === entry.slot;
    const isSubAce = subAceSlots.has(entry.slot);
    const roleScores = {
      ...entry.rawRoleScores,
      ace: isAce
        ? Math.max(3, Number(entry.rawRoleScores?.ace ?? 0))
        : 0,
      subAce: isSubAce
        ? Math.max(
            1.5,
            Math.min(4.5, Number(entry.aceScore ?? 0) * 0.35),
          )
        : 0,
    };
    entry.aceProfile = {
      ...entry.aceProfile,
      qualifies: isAce,
      tier: isAce ? "ace" : isSubAce ? "subAce" : "none",
    };
    entry.roleScores = roleScores;
    entry.roles = topRoles(roleScores);
    entry.primaryRole = entry.roles[0]?.role ?? "support";
    if (isAce) {
      const selectionReason = noTeamSetupRoute
        ? "팀에 확실한 랭크업 경로가 없어 가장 높은 공격 능력을 우선했습니다."
        : `최소 ${entry.aceProfile.estimatedKoCapacity}명을 처리할 전개 잠재력을 가진 팀 내 최우선 후보입니다.`;
      entry.reasons.unshift(
        `에이스 확정: ${selectionReason}`,
        `에이스 판단: ${entry.aceProfile.reasons.slice(0, 3).join(", ")}`,
      );
    } else if (isSubAce) {
      entry.reasons.unshift(
        `준에이스 판단: 에이스 점수 ${entry.aceScore}로 최종 에이스 다음 공격 자원입니다.`,
      );
    }
    entry.reasons = entry.reasons.slice(0, 4);
  }

  return {
    ace: primaryAce
      ? roleEntries.find((entry) => entry.slot === primaryAce.slot)
      : null,
    subAces: subAceCandidates
      .map((candidate) =>
        roleEntries.find((entry) => entry.slot === candidate.slot),
      )
      .filter(Boolean),
  };
}

export function analyzeTeamProfile(team = []) {
  const members = team.map((member, index) => {
    const moveIds = pokemonMoveIds(member);
    const catalogRoleScores = {};
    const catalogTags = new Set();
    for (const moveId of moveIds) {
      const entry = moveRoleEntry(moveId);
      for (const [role, score] of Object.entries(entry?.roleScores ?? {})) {
        catalogRoleScores[role] = Number(catalogRoleScores[role] ?? 0) + Number(score ?? 0);
      }
      for (const tag of entry?.tags ?? []) catalogTags.add(cleanId(tag));
    }
    const speciesOverride = pokemonRoleOverride(member);
    for (const [role, score] of Object.entries(speciesOverride?.roleScores ?? {})) {
      catalogRoleScores[role] = Number(catalogRoleScores[role] ?? 0) + Number(score ?? 0);
    }
    const hasBatonPassSetupMove = (member.moves ?? member.moveset ?? []).some((move) => {
      const moveId = cleanId(typeof move === "string" ? move : move?.id ?? move?.moveId ?? move?.name ?? move?.move);
      if (!moveId || moveId === "batonpass") return false;
      const boosts = typeof move === "string" ? {} : move?.selfBoosts ?? move?.boosts ?? {};
      const entry = moveRoleEntry(moveId);
      return Object.values(boosts).some((amount) => Number(amount ?? 0) > 0) ||
        (entry?.tags ?? []).some((tag) => cleanId(tag) === "setupboost") ||
        Number(entry?.roleScores?.setupSweeper ?? 0) >= 2.5;
    });
    const gimmick = gimmickAceProfile(member);
    return {
      slot: Number(member.slot ?? index + 1),
      pokemonId: cleanId(member.id ?? member.species ?? member.name),
      species: pokemonDisplayName(member),
      level: pokemonLevel(member),
      ability: pokemonAbilityId(member),
      stats: {
        hp: pokemonStat(member, ["hp"]),
        attack: pokemonStat(member, ["attack", "atk"]),
        defense: pokemonStat(member, ["defence", "defense", "def"]),
        specialAttack: pokemonStat(member, ["specialAttack", "specialAtk", "spa"]),
        specialDefense: pokemonStat(member, ["specialDefence", "specialDefense", "specialDef", "spd"]),
        speed: pokemonStat(member, ["speed", "spe"]),
      },
      moveIds,
      catalogRoleScores,
      catalogTags: [...catalogTags],
      catalogReasons: speciesOverride?.reasons ?? [],
      hasBatonPassSetupMove,
      manualAce: manualAcePreference(member),
      gimmickAceValue: gimmick.value,
      gimmickReasons: gimmick.reasons,
      speciesAceScore: Number(speciesOverride?.roleScores?.ace ?? 0),
    };
  });
  return JSON.parse(analyzeSharedTeamProfileJson(JSON.stringify({ members })));
}

function battleMemberId(member = {}, fallback = "") {
  return cleanId(member.id ?? member.species ?? member.name ?? fallback);
}

function battleMemberHpPercent(member = {}) {
  const maxHp = finiteNumber(
    member.maxHp,
    finiteNumber(
      member.stats?.hp,
      finiteNumber(member.maxhp, member.hp),
    ),
  );
  const hp = finiteNumber(member.hp, maxHp);
  return maxHp > 0 ? Math.max(0, Math.min(1, hp / maxHp)) : 0;
}

function livingBattleMember(member = {}) {
  return member.fainted !== true && battleMemberHpPercent(member) > 0;
}

export function buildThreatCounterMap({
  allies = [],
  enemies = [],
  allyAnalysis = analyzeTeamProfile(allies),
  enemyAnalysis = analyzeTeamProfile(enemies),
  evaluateMatchup = () => ({}),
} = {}) {
  const threats = enemies.map((enemy, enemyIndex) => {
    const enemyRole = enemyAnalysis.roles?.[enemyIndex] ?? {};
    return {
      enemySlot: Number(enemy.slot ?? enemyIndex + 1),
      enemyPokemonId: battleMemberId(enemy, enemyIndex + 1),
      species: pokemonDisplayName(enemy),
      living: livingBattleMember(enemy),
      aceScore: finiteNumber(enemyRole.aceScore, 0),
      setupScore: finiteNumber(enemyRole.roleScores?.setupSweeper, 0),
      offense: Math.max(
        pokemonStat(enemy, ["attack", "atk"]),
        pokemonStat(enemy, ["specialAttack", "specialAtk", "spa"]),
      ),
      hpPercent: battleMemberHpPercent(enemy),
      resources: allies.map((ally, allyIndex) => {
        const matchup = evaluateMatchup({ ally, enemy, allyIndex, enemyIndex }) ?? {};
        const allyRole = allyAnalysis.roles?.[allyIndex] ?? {};
        return {
          slot: Number(ally.slot ?? allyIndex + 1),
          pokemonId: battleMemberId(ally, allyIndex + 1),
          species: pokemonDisplayName(ally),
          living: livingBattleMember(ally),
          hpPercent: ratioValue(matchup.allyHpPercent, battleMemberHpPercent(ally)),
          incomingDamageRatio: Math.max(0, finiteNumber(matchup.incomingDamageRatio, 1)),
          outgoingDamageRatio: Math.max(0, finiteNumber(matchup.outgoingDamageRatio, 0)),
          survivesHit: matchup.survivesHit === true,
          actsBefore: matchup.actsBefore === true,
          priorityKo: matchup.priorityKo === true,
          aceQualified: allyRole.aceProfile?.qualifies === true,
        };
      }),
    };
  });
  return JSON.parse(evaluateThreatCountersJson(JSON.stringify({ threats })));
}

export function evaluateSetupThreat(input = {}) {
  const answerCount = (value) =>
    Array.isArray(value) ? value.length : Math.max(0, finiteNumber(value, 0));
  return JSON.parse(
    evaluateSetupThreatJson(
      JSON.stringify({
        ...input,
        setupMoves: (input.setupMoves ?? []).map((move) => ({
          id: cleanId(move?.id ?? move?.name ?? move),
          selfBoosts: { ...(move?.selfBoosts ?? move?.boosts ?? {}) },
        })),
        setupMoveIds: (input.setupMoveIds ?? []).map(cleanId),
        counterCount: answerCount(input.counters),
        softCheckCount: answerCount(input.softChecks),
        revengeKillerCount: answerCount(input.revengeKillers),
        counters: [],
        softChecks: [],
        revengeKillers: [],
        punishOptions: (input.punishOptions ?? []).map((option) =>
          cleanId(option?.id ?? option?.moveId ?? option),
        ),
      }),
    ),
  );
}

export function evaluateSetupLikelihood(input = {}) {
  return JSON.parse(evaluateSetupLikelihoodJson(JSON.stringify(input)));
}

function normalizedBattleValueSide(side = {}) {
  const teamSize = Math.max(
    1,
    finiteNumber(
      side.teamSize,
      finiteNumber(side.livingCount, 1),
    ),
  );
  const aceAliveCount = Math.max(0, finiteNumber(side.aceAliveCount, 0));
  const aceHpRatio = Math.max(0, finiteNumber(side.aceHpRatio, 0));
  const aceCandidateCount = Math.max(
    1,
    finiteNumber(
      side.aceCandidateCount,
      Math.max(aceAliveCount, Math.ceil(aceHpRatio), 1),
    ),
  );
  return {
    teamSize,
    livingCount: Math.max(
      0,
      Math.min(teamSize, finiteNumber(side.livingCount, 0)),
    ),
    totalHpRatio: Math.max(
      0,
      Math.min(teamSize, finiteNumber(side.totalHpRatio, 0)),
    ),
    aceCandidateCount,
    aceAliveCount: Math.min(aceCandidateCount, aceAliveCount),
    aceHpRatio: Math.min(aceCandidateCount, aceHpRatio),
    positiveBoosts: Math.max(0, finiteNumber(side.positiveBoosts, 0)),
    statusBurden: Math.max(0, finiteNumber(side.statusBurden, 0)),
    hazardLayers: Math.max(0, finiteNumber(side.hazardLayers, 0)),
    uniqueCountersAlive: Math.max(
      0,
      finiteNumber(side.uniqueCountersAlive, 0),
    ),
    gimmicksRemaining: Math.max(
      0,
      finiteNumber(side.gimmicksRemaining, 0),
    ),
    matchupCoverage: Math.max(
      0,
      Math.min(1, finiteNumber(side.matchupCoverage, 0)),
    ),
    safeKoCoverage: Math.max(
      0,
      Math.min(1, finiteNumber(side.safeKoCoverage, 0)),
    ),
    benchReadiness: Math.max(
      0,
      Math.min(1, finiteNumber(side.benchReadiness, 0)),
    ),
    sweepPotential: Math.max(
      0,
      Math.min(1, finiteNumber(side.sweepPotential, 0)),
    ),
  };
}

export function evaluateBattleStateValue(state = {}) {
  const sharedState = {
    ...state,
    own: normalizedBattleValueSide(state.own),
    opponent: normalizedBattleValueSide(state.opponent),
    fieldAdvantage: finiteNumber(state.fieldAdvantage, 0),
  };
  const shared = JSON.parse(
    estimateWinProbabilityJson(JSON.stringify(sharedState)),
  );
  return {
    value: shared.rawValue,
    components: shared.components,
    state: {
      own: shared.state.own,
      opponent: shared.state.opponent,
      fieldAdvantage: shared.state.fieldAdvantage,
      field: { ...(state.field ?? {}) },
    },
  };
}

const WIN_PROBABILITY_MODEL_VERSION = "heuristic-logistic-v3";
const WIN_PROBABILITY_FEATURE_SCHEMA_VERSION = 3;
const WIN_PROBABILITY_COMPONENT_LABELS = {
  pokemonCount: "남은 포켓몬",
  totalHp: "남은 체력",
  aceSurvival: "에이스 생존",
  status: "상태이상",
  boosts: "능력치 랭크",
  hazards: "설치물",
  gimmicks: "남은 기믹",
  uniqueCounters: "핵심 대응 자원",
  matchupCoverage: "팀 대면 대응력",
  safeKoCoverage: "안전한 KO 경로",
  benchReadiness: "벤치 준비도",
  sweepPotential: "후반 돌파 가능성",
  field: "필드 상성",
};

function clampProbability(value) {
  return Math.max(0, Math.min(1, Number(value) || 0));
}

function probabilityLogit(probability) {
  const clamped = Math.max(0.0001, Math.min(0.9999, probability));
  return Math.log(clamped / (1 - clamped));
}

export function calibrateWinProbability(probability, calibration = {}) {
  const intercept = finiteNumber(calibration.intercept, 0);
  const slope = finiteNumber(calibration.slope, 1);
  const calibrated =
    1 / (1 + Math.exp(-(intercept + slope * probabilityLogit(probability))));
  return Math.max(0.01, Math.min(0.99, calibrated));
}

export function fitWinProbabilityCalibration(samples = [], options = {}) {
  const normalized = samples
    .map((sample) => ({
      probability: finiteNumber(
        sample.predictedProbability,
        finiteNumber(sample.probability),
      ),
      outcome: finiteNumber(
        sample.actualOutcome,
        finiteNumber(sample.outcome),
      ),
    }))
    .filter(
      (sample) =>
        sample.probability !== undefined &&
        sample.outcome !== undefined &&
        sample.outcome >= 0 &&
        sample.outcome <= 1,
    );
  if (normalized.length < 2) {
    return {
      intercept: 0,
      slope: 1,
      sampleCount: normalized.length,
      fitted: false,
    };
  }
  const iterations = Math.max(1, Math.floor(finiteNumber(options.iterations, 1200)));
  const learningRate = Math.max(0.0001, finiteNumber(options.learningRate, 0.03));
  const regularization = Math.max(0, finiteNumber(options.regularization, 0.01));
  let intercept = 0;
  let slope = 1;
  for (let iteration = 0; iteration < iterations; iteration += 1) {
    let interceptGradient = 0;
    let slopeGradient = 0;
    for (const sample of normalized) {
      const input = probabilityLogit(sample.probability);
      const prediction =
        1 / (1 + Math.exp(-(intercept + slope * input)));
      const error = prediction - sample.outcome;
      interceptGradient += error;
      slopeGradient += error * input;
    }
    interceptGradient =
      interceptGradient / normalized.length + regularization * intercept;
    slopeGradient =
      slopeGradient / normalized.length + regularization * (slope - 1);
    intercept -= learningRate * interceptGradient;
    slope -= learningRate * slopeGradient;
  }
  return {
    intercept: Math.round(intercept * 100_000) / 100_000,
    slope: Math.round(slope * 100_000) / 100_000,
    sampleCount: normalized.length,
    fitted: true,
  };
}

function battleStateInformationCoverage(state = {}) {
  const sideFields = [
    "teamSize",
    "livingCount",
    "totalHpRatio",
    "aceCandidateCount",
    "aceAliveCount",
    "aceHpRatio",
    "positiveBoosts",
    "statusBurden",
    "hazardLayers",
    "uniqueCountersAlive",
    "gimmicksRemaining",
    "matchupCoverage",
    "safeKoCoverage",
    "benchReadiness",
    "sweepPotential",
  ];
  const sides = [state.own ?? {}, state.opponent ?? {}];
  const knownSideFields = sides.reduce(
    (total, side) =>
      total +
      sideFields.filter((field) => Number.isFinite(Number(side[field]))).length,
    0,
  );
  const fieldKnown = Number.isFinite(Number(state.fieldAdvantage)) ? 1 : 0;
  return (knownSideFields + fieldKnown) / (sideFields.length * 2 + 1);
}

function winEstimateFromEvaluation(state, evaluation, options = {}) {
  const ownLiving = evaluation.state.own.livingCount;
  const opponentLiving = evaluation.state.opponent.livingCount;
  const terminal =
    ownLiving <= 0 || opponentLiving <= 0;
  const terminalOutcome =
    ownLiving <= 0 && opponentLiving <= 0
      ? "draw"
      : opponentLiving <= 0
        ? "win"
        : ownLiving <= 0
          ? "loss"
          : null;
  const scale = Math.max(1, finiteNumber(options.scale, 90));
  const logisticProbability = 1 / (1 + Math.exp(-evaluation.value / scale));
  const calibratedProbability = calibrateWinProbability(
    logisticProbability,
    options.calibration,
  );
  const probability =
    terminalOutcome === "win"
      ? 1
      : terminalOutcome === "loss"
        ? 0
        : terminalOutcome === "draw"
          ? 0.5
          : calibratedProbability;
  const suppliedConfidence = finiteNumber(
    options.informationConfidence,
    finiteNumber(state.informationConfidence),
  );
  const coverage = battleStateInformationCoverage(state);
  const confidence = clampProbability(
    suppliedConfidence ??
      (terminal ? 1 : 0.45 + coverage * 0.45),
  );
  const topFactors = Object.entries(evaluation.components)
    .filter(([, contribution]) => Math.abs(contribution) >= 0.5)
    .sort(
      (left, right) =>
        Math.abs(right[1]) - Math.abs(left[1]),
    )
    .slice(0, 5)
    .map(([component, contribution]) => ({
      component,
      label: WIN_PROBABILITY_COMPONENT_LABELS[component] ?? component,
      contribution,
      direction:
        contribution > 0
          ? "favorable"
          : contribution < 0
            ? "unfavorable"
            : "neutral",
      message: `${WIN_PROBABILITY_COMPONENT_LABELS[component] ?? component} 항목은 현재 승률을 ${contribution > 0 ? "높이는" : "낮추는"} 주요 요인입니다.`,
    }));

  return {
    probability: Math.round(probability * 10_000) / 10_000,
    probabilityPercent: Math.round(probability * 1_000) / 10,
    confidence: Math.round(confidence * 1_000) / 1_000,
    modelVersion: WIN_PROBABILITY_MODEL_VERSION,
    featureSchemaVersion: WIN_PROBABILITY_FEATURE_SCHEMA_VERSION,
    terminal,
    terminalOutcome,
    rawValue: evaluation.value,
    rawProbability: Math.round(logisticProbability * 10_000) / 10_000,
    calibration: {
      intercept: finiteNumber(options.calibration?.intercept, 0),
      slope: finiteNumber(options.calibration?.slope, 1),
    },
    topFactors,
    components: evaluation.components,
  };
}

export function estimateBattleWinProbability(state = {}, options = {}) {
  const ownLiving = finiteNumber(state.own?.livingCount, 0);
  const opponentLiving = finiteNumber(state.opponent?.livingCount, 0);
  const terminal = ownLiving <= 0 || opponentLiving <= 0;
  const suppliedConfidence = finiteNumber(
    options.informationConfidence,
    finiteNumber(state.informationConfidence),
  );
  const informationConfidence = clampProbability(
    suppliedConfidence ??
      (terminal ? 1 : 0.45 + battleStateInformationCoverage(state) * 0.45),
  );
  const sharedState = {
    ...state,
    own: normalizedBattleValueSide(state.own),
    opponent: normalizedBattleValueSide(state.opponent),
    fieldAdvantage: finiteNumber(state.fieldAdvantage, 0),
    informationConfidence,
  };
  const shared = JSON.parse(
    estimateWinProbabilityJson(
      JSON.stringify(sharedState),
      finiteNumber(options.calibration?.intercept, 0),
      finiteNumber(options.calibration?.slope, 1),
    ),
  );
  return {
    probability: shared.probability,
    probabilityPercent: shared.probabilityPercent,
    confidence: shared.confidence,
    modelVersion: shared.modelVersion,
    featureSchemaVersion: shared.featureSchemaVersion,
    terminal: shared.terminal,
    terminalOutcome: shared.terminalOutcome,
    rawValue: shared.rawValue,
    rawProbability: shared.rawProbability,
    calibration: {
      intercept: finiteNumber(options.calibration?.intercept, 0),
      slope: finiteNumber(options.calibration?.slope, 1),
    },
    topFactors: shared.topFactors.map(({ component, contribution }) => ({
      component,
      label: WIN_PROBABILITY_COMPONENT_LABELS[component] ?? component,
      contribution,
      direction:
        contribution > 0
          ? "favorable"
          : contribution < 0
            ? "unfavorable"
            : "neutral",
      message: `${WIN_PROBABILITY_COMPONENT_LABELS[component] ?? component} 항목은 현재 승률을 ${contribution > 0 ? "높이는" : "낮추는"} 주요 요인입니다.`,
    })),
    components: shared.components,
  };
}

function applyProjectedSideDelta(side, delta = {}) {
  const projected = {
    ...side,
    livingCount: side.livingCount + finiteNumber(delta.livingCount, 0),
    totalHpRatio: side.totalHpRatio + finiteNumber(delta.totalHpRatio, 0),
    aceAliveCount:
      side.aceAliveCount + finiteNumber(delta.aceAliveCount, 0),
    aceHpRatio: side.aceHpRatio + finiteNumber(delta.aceHpRatio, 0),
    positiveBoosts:
      side.positiveBoosts + finiteNumber(delta.positiveBoosts, 0),
    statusBurden:
      side.statusBurden + finiteNumber(delta.statusBurden, 0),
    hazardLayers:
      side.hazardLayers + finiteNumber(delta.hazardLayers, 0),
    uniqueCountersAlive:
      side.uniqueCountersAlive +
      finiteNumber(delta.uniqueCountersAlive, 0),
    gimmicksRemaining:
      side.gimmicksRemaining +
      finiteNumber(delta.gimmicksRemaining, 0),
    matchupCoverage:
      side.matchupCoverage + finiteNumber(delta.matchupCoverage, 0),
    safeKoCoverage:
      side.safeKoCoverage + finiteNumber(delta.safeKoCoverage, 0),
    benchReadiness:
      side.benchReadiness + finiteNumber(delta.benchReadiness, 0),
    sweepPotential:
      side.sweepPotential + finiteNumber(delta.sweepPotential, 0),
  };
  return normalizedBattleValueSide(projected);
}

export function projectOneTurnBattleState(
  state = {},
  outcome = {},
) {
  const before = evaluateBattleStateValue(state).state;
  const after = {
    own: applyProjectedSideDelta(before.own, outcome.own),
    opponent: applyProjectedSideDelta(before.opponent, outcome.opponent),
    fieldAdvantage:
      before.fieldAdvantage + finiteNumber(outcome.fieldAdvantage, 0),
    field: {
      ...before.field,
      ...(outcome.field ?? {}),
    },
  };
  return after;
}

export function evaluateOneTurnBattleState(
  state = {},
  outcome = {},
) {
  const before = evaluateBattleStateValue(state);
  const beforeWinEstimate = winEstimateFromEvaluation(
    before.state,
    before,
  );
  const projectedState = projectOneTurnBattleState(before.state, outcome);
  const after = evaluateBattleStateValue(projectedState);
  const afterWinEstimate = winEstimateFromEvaluation(
    after.state,
    after,
  );
  const componentDeltas = Object.fromEntries(
    Object.keys(after.components).map((key) => [
      key,
      Math.round(
        (after.components[key] - before.components[key]) * 100,
      ) / 100,
    ]),
  );
  const delta =
    Math.round((after.value - before.value) * 100) / 100;
  const reasons = Object.entries(componentDeltas)
    .filter(([, value]) => Math.abs(value) >= 0.5)
    .sort((left, right) => Math.abs(right[1]) - Math.abs(left[1]))
    .slice(0, 4)
    .map(([component, value]) => ({
      component,
      value,
    }));
  return {
    before,
    after,
    projectedState,
    qValue: after.value,
    delta,
    winProbabilityBefore: beforeWinEstimate.probability,
    winProbabilityAfter: afterWinEstimate.probability,
    winProbabilityDelta:
      Math.round(
        (afterWinEstimate.probability - beforeWinEstimate.probability) *
          10_000,
      ) / 10_000,
    winEstimateBefore: beforeWinEstimate,
    winEstimateAfter: afterWinEstimate,
    componentDeltas,
    reasons,
  };
}

function sideConditionLayers(conditions = {}, conditionId = "") {
  const condition = conditions?.[conditionId];
  if (!condition) return 0;
  if (condition === true) return 1;
  if (Number.isFinite(Number(condition))) return Number(condition);
  return Math.max(
    1,
    Number(condition.layers ?? condition.level ?? condition.count ?? 1),
  );
}

export function evaluatePokemonRoleProgress({
  member = {},
  roleProfile = null,
  ownSideConditions = {},
  opponentSideConditions = {},
  opponentLivingCount = 0,
  highThreatCount = 0,
  setupThreatCount = 0,
  assignedThreats = [],
  opponentHazardSetterAlive = false,
  mustPreserveResource = false,
  activeTurns = 0,
} = {}) {
  const resolvedRole = roleProfile ?? analyzeTeamProfile([member]).roles[0] ?? {};
  const moveIds = resolvedRole.moveIds?.length ? resolvedRole.moveIds : pokemonMoveIds(member);
  const hazardSetConditions = [...new Set(
    moveIds
      .filter((moveId) => (moveRoleEntry(moveId)?.tags ?? []).some((tag) => cleanId(tag) === "hazardset"))
      .map((moveId) => HAZARD_MOVE_CONDITIONS[cleanId(moveId)])
      .filter(Boolean),
  )];
  const hasHazardRemoval = moveIds.some((moveId) =>
    (moveRoleEntry(moveId)?.tags ?? []).some((tag) => cleanId(tag) === "hazardremove"),
  );
  const ownHazardLayers = Object.keys(HAZARD_MAX_LAYERS).reduce(
    (total, conditionId) => total + sideConditionLayers(ownSideConditions, conditionId),
    0,
  );
  return JSON.parse(
    evaluateRoleProgressJson(
      JSON.stringify({
        roleScores: Object.fromEntries(
          (resolvedRole.roles ?? []).map((entry) => [entry.role, Number(entry.score ?? 0)]),
        ),
        primaryRole: resolvedRole.primaryRole ?? "",
        aceQualified: resolvedRole.aceProfile?.qualifies === true,
        hazardSetConditions,
        hazardMaxLayers: Object.fromEntries(
          hazardSetConditions.map((id) => [id, HAZARD_MAX_LAYERS[id] ?? 1]),
        ),
        opponentHazardLayers: Object.fromEntries(
          hazardSetConditions.map((id) => [id, sideConditionLayers(opponentSideConditions, id)]),
        ),
        hasHazardRemoval,
        ownHazardLayers,
        opponentHazardSetterAlive,
        opponentLivingCount,
        highThreatCount,
        setupThreatCount,
        assignedThreats,
        mustPreserveResource,
        activeTurns,
      }),
    ),
  );
}

export function aiScoringRuleCatalog() {
  return AI_SCORING_RULES.map((rule) => ({ ...rule }));
}

function renderSharedRuleAdjustment(adjustment, candidate, kind) {
  const code = String(adjustment.code ?? "shared.rule");
  const ruleName = code.split(".").at(-1)?.replaceAll("_", " ") ?? code;
  let message = `${kind === "move" ? "기술" : "교체"} 후보의 공통 AI 규칙(${ruleName})을 적용했습니다.`;
  if (code === "rule.self_sacrifice.resource_cost" && candidate.mustPreserveResource === true) {
    message = `현재 포켓몬은 ${arrayValues(candidate.mustPreserveFor).join(", ") || "상대 핵심 포켓몬"}의 유일한 대응 자원이라 자폭으로 소모하지 않도록 크게 낮췄습니다.`;
  } else if (code.startsWith("rule.status_disruption.")) {
    const ratio = Math.round(
      Math.max(0, finiteNumber(candidate.disruptionThreeTurnDamageRatio, 0)) * 100,
    );
    message = candidate.disruptionBenchSwitchThreat === true
      ? `벤치 위협으로 교체하는 경로까지 포함한 3턴 예상 피해가 현재 체력의 ${ratio}%라 방해 기술 위험을 반영했습니다.`
      : candidate.disruptionDefensiveSetup === true
        ? `방어형 랭크업 후 3턴 예상 피해가 현재 체력의 ${ratio}%라 생존 가능성을 함께 반영했습니다.`
        : `3턴 예상 피해가 현재 체력의 ${ratio}%라 상대 방해 기술 위험을 반영했습니다.`;
  }
  return scoreAdjustment(
    code,
    kind === "move" ? "공통 기술 규칙" : "공통 교체 규칙",
    true,
    Number(adjustment.weight ?? 0),
    message,
  );
}

function sharedMoveRuleAdjustments(candidate, strategy = "balanced") {
  const enriched = enrichMoveCandidateWithRole(candidate, strategy);
  const moveId = cleanId(enriched.id ?? enriched.moveId ?? enriched.name);
  const tags = candidateTagSet(enriched);
  const tier = setupThreatTier(enriched);
  const isDamage = isDamagingCandidate(enriched);
  const hasSafeImmediateKo = enriched.safeImmediateKoAvailable === true;
  const hpRatio = ratioValue(
    enriched.hpPercent,
    enriched.healthRatio,
    enriched.currentHpRatio,
    1,
  );
  const incomingDamageRatio = ratioValue(
    enriched.currentIncomingDamageRatio,
    enriched.opponentMaxDamageToCurrentHealthRatio,
    enriched.incomingDamageRatio,
  );
  const reachability = JSON.parse(
    evaluateActionReachabilityJson(
      JSON.stringify({
        ownPriority: Number(enriched.priority ?? 0),
        opponentPriority: Number(enriched.opponentPriority ?? 0),
        speedAdvantage: enriched.speedAdvantage === true,
        actsBeforeOpponent: enriched.actsBeforeOpponent,
        currentHp: hpRatio,
        incomingDamage: incomingDamageRatio,
        survivalProbability: finiteNumber(enriched.survivalProbability),
        knockoutBeforeActionProbability: finiteNumber(
          enriched.opponentKnockoutBeforeActionProbability,
        ),
        canReachNextAction: enriched.canReachNextAction,
        guaranteedSurvival:
          enriched.focusSashSurvival === true || enriched.sturdySurvival === true,
      }),
    ),
  );
  const livingBench =
    enriched.hasLivingBench !== undefined
      ? enriched.hasLivingBench === true
      : enriched.livingBenchCount !== undefined
        ? Number(enriched.livingBenchCount) > 0
        : false;
  const livingOpponents = Math.max(0, Number(enriched.livingOpponents ?? 2));
  return JSON.parse(
    evaluateMoveRuleFactsJson(
      JSON.stringify(
        toRuleFactBag(
          "move",
          enriched,
          {
            strategy,
            "computed.isDamage": isDamage,
            "computed.actsBefore": reachability.actsBefore,
            survivalProbability: reachability.survivalProbability,
            opponentKnockoutBeforeActionProbability:
              reachability.knockoutBeforeActionProbability,
            canReachNextAction: reachability.canReachNextAction,
            safePivot: reachability.safePivot,
            "computed.hasSafeImmediateKo": hasSafeImmediateKo,
            "computed.safeFinisher": isSafeFinisher(enriched),
            "computed.highValueHazard":
              tags.has("hazardset") &&
              moveId === "stealthrock" &&
              livingOpponents >= 3 &&
              Number(enriched.opponentHazards?.stealthrock ?? 0) <= 0,
            "computed.setupThreatTier": tier,
            "computed.recoveryMove":
              RECOVERY_MOVE_IDS.has(moveId) || tags.has("recovery"),
            "computed.opponentLikelyToSetup": opponentLikelyToSetup(enriched),
            "computed.opponentSetupLikelihood": opponentSetupLikelihood(enriched),
            "computed.pivotMove": PIVOT_MOVE_IDS.has(moveId),
            "computed.hasLivingBench": livingBench,
            "computed.selfSacrifice": isSelfSacrificeCandidate(enriched),
            "computed.knockoutBoostAlternative": Boolean(
              enriched.knockoutBoostAlternative &&
                typeof enriched.knockoutBoostAlternative === "object",
            ),
            "computed.opponentPositiveBoosts": Math.max(
              0,
              finiteNumber(enriched.opponentPositiveBoosts, 0),
              positiveBoostTotal(enriched.opponentBoosts),
              positiveBoostTotal(enriched.targetBoosts),
            ),
            "computed.saltCureActive": Object.keys(
              enriched.opponentVolatiles ?? {},
            ).map(cleanId).includes("saltcure"),
            hpRatio,
            incomingDamageRatio,
          },
          [...tags],
        ),
      ),
    ),
  );
}

export function moveRuleAdjustments(candidate, strategy = "balanced") {
  return sharedMoveRuleAdjustments(candidate, strategy).map((adjustment) =>
    renderSharedRuleAdjustment(adjustment, candidate, "move"),
  );
}

export function scoreAiMoveCandidate(
  candidate,
  difficulty = "standard",
  strategy = "balanced",
) {
  const accuracy =
    candidate.accuracy === true
      ? 1
      : Number.isFinite(Number(candidate.accuracy))
        ? Number(candidate.accuracy) / 100
        : 1;
  return JSON.parse(
    scoreObservedActionCandidateJson(
      JSON.stringify({
        kind: "move",
        difficulty,
        strategy,
        expectedDamage: Number.isFinite(Number(candidate.expectedDamage))
          ? Number(candidate.expectedDamage)
          : null,
        power: finiteNumber(candidate.power, 0),
        accuracyPercent: accuracy * 100,
        priority: finiteNumber(candidate.priority, 0),
        statusMove: candidate.category === "Status",
        tacticalValue: finiteNumber(candidate.tacticalValue, 0),
        roleValue: finiteNumber(
          candidate.roleValue,
          finiteNumber(moveRoleValue(candidate, strategy), 0),
        ),
        koChance: candidate.koChance ?? "none",
        adjustments: moveRuleAdjustments(candidate, strategy).map(
          ({ code, weight }) => ({ code, weight: finiteNumber(weight, 0) }),
        ),
      }),
    ),
  ).score;
}

export function rankAiMoveCandidates(
  candidates,
  difficulty = "standard",
  strategy = "balanced",
) {
  return candidates
    .map((candidate) => ({
      ...candidate,
      score: Math.round(
        scoreAiMoveCandidate(candidate, difficulty, strategy) * 100,
      ) / 100,
    }))
    .sort((left, right) => right.score - left.score || left.slot - right.slot);
}

function finiteNumber(value, fallback = undefined) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function expectedDamageObservation(candidate) {
  const damage = finiteNumber(candidate.expectedDamage);
  if (damage === undefined) return undefined;
  return {
    value: damage,
    unit: "hp",
  };
}

function moveDecisionReasons(candidate, difficulty, strategy) {
  const reasons = [];
  const expectedDamage = finiteNumber(candidate.expectedDamage);
  const power = finiteNumber(candidate.power, 0);
  const priority = finiteNumber(candidate.priority, 0);
  const tacticalValue = finiteNumber(candidate.tacticalValue, 0);
  const roleValue = finiteNumber(candidate.roleValue, moveRoleValue(candidate, strategy));

  if (expectedDamage !== undefined) {
    reasons.push({
      code: "damage.expected",
      label: "예상 피해",
      value: expectedDamage,
      message: `예상 피해 ${expectedDamage}를 기준으로 공격 가치를 계산했습니다.`,
    });
  } else if (power > 0) {
    reasons.push({
      code: "damage.base_power",
      label: "기본 위력",
      value: power,
      message: `기본 위력 ${power}를 피해 기대값의 대체 기준으로 사용했습니다.`,
    });
  }

  if (candidate.koChance === "guaranteed") {
    reasons.push({
      code: "ko.guaranteed",
      label: "확정 KO",
      value: true,
      weight: 55,
      message: "현재 계산 기준으로 상대를 확정적으로 쓰러뜨릴 수 있습니다.",
    });
  } else if (candidate.koChance === "possible") {
    reasons.push({
      code: "ko.possible",
      label: "KO 가능성",
      value: true,
      weight: 25,
      message: "피해 난수에 따라 상대를 쓰러뜨릴 가능성이 있습니다.",
    });
  }

  if (priority !== 0) {
    reasons.push({
      code: "speed.priority",
      label: "우선도",
      value: priority,
      message: `우선도 ${priority} 기술이라 행동 순서 가치가 있습니다.`,
    });
  }

  if (candidate.category === "Status") {
    reasons.push({
      code: "status.move",
      label: "변화기",
      value: strategy,
      message:
        strategy === "defensive"
          ? "방어 성향이 변화기 가치를 높게 반영했습니다."
          : "변화기 전술 가치를 반영했습니다.",
    });
  }

  if (tacticalValue !== 0) {
    reasons.push({
      code: "tactical.value",
      label: "전술 가치",
      value: tacticalValue,
      message: `기술 역할 분류에서 전술 가치 ${tacticalValue}를 반영했습니다.`,
    });
  }

  if (roleValue !== 0) {
    const scores = candidate.roleScores ?? moveRoleScores(candidate.id ?? candidate.moveId);
    const bestRole = Object.entries(scores).sort(
      (left, right) => Number(right[1]) - Number(left[1]),
    )[0]?.[0];
    reasons.push({
      code: "role.strategy_fit",
      label: "역할 적합도",
      value: roleValue,
      role: bestRole,
      message: `${ROLE_LABELS[bestRole] ?? "기술 역할"} 성향이 ${strategy} 전략과 맞아 역할 점수 ${roleValue}를 반영했습니다.`,
    });
  }

  for (const adjustment of moveRuleAdjustments(candidate, strategy)) {
    reasons.push(adjustment);
  }

  if (
    difficulty === "expert" ||
    difficulty === "expert_winrate" ||
    difficulty === "expert_search" ||
    difficulty === "cheater"
  ) {
    reasons.push({
      code: "difficulty.priority_weight",
      label: "고난도 판단",
      value: difficulty,
      message: "고난도 프로필이 우선도와 KO 가능성을 더 적극적으로 비교했습니다.",
    });
  }

  if (reasons.length === 0) {
    reasons.push({
      code: "baseline.score",
      label: "기본 점수",
      message: "공통 기준선 점수로 후보를 비교했습니다.",
    });
  }

  return reasons;
}

function sharedSwitchRuleAdjustments(candidate, strategy = "balanced") {
  const dynamaxRemainingTurns = Math.max(
    0,
    Number(candidate.dynamaxRemainingTurns ?? candidate.remainingDynamaxTurns ?? 0),
  );
  return JSON.parse(
    evaluateSwitchRuleFactsJson(
      JSON.stringify(
        toRuleFactBag("switch", candidate, {
          strategy,
          setupThreatTier: setupThreatTier(candidate),
          dynamaxRemainingTurns,
        }),
      ),
    ),
  );
}

export function switchRuleAdjustments(candidate, strategy = "balanced") {
  return sharedSwitchRuleAdjustments(candidate, strategy).map((adjustment) =>
    renderSharedRuleAdjustment(adjustment, candidate, "switch"),
  );
}

function switchDecisionReasons(candidate, strategy = "balanced") {
  const reasons = [];
  const hpPercent = finiteNumber(candidate.hpPercent);
  const expectedDamage = finiteNumber(candidate.expectedDamage);
  const matchupValue = finiteNumber(candidate.matchupValue);
  const switchAdjustments = switchRuleAdjustments(candidate, strategy);

  if (hpPercent !== undefined) {
    reasons.push({
      code: "switch.hp_remaining",
      label: "남은 체력",
      value: Math.round(hpPercent * 100),
      message: `남은 체력 ${Math.round(hpPercent * 100)}%를 교체 안정성으로 반영했습니다.`,
    });
  }
  const switchInExpectedDamage = finiteNumber(candidate.switchInExpectedDamage);
  if (switchInExpectedDamage !== undefined) {
    reasons.push({
      code: "switch.incoming_hit",
      label: "교체 턴 예상 피해",
      value: switchInExpectedDamage,
      message: `교체와 동시에 받을 상대 공격을 ${switchInExpectedDamage} 피해로 예상했습니다.`,
    });
  }
  if (candidate.projectedBestMoveId) {
    const projectedRisk = Math.round(
      Math.max(
        0,
        Math.min(
          1,
          finiteNumber(candidate.projectedKnockoutBeforeActionProbability, 0),
        ),
      ) * 100,
    );
    reasons.push({
      code: "switch.projected_best_action",
      label: "투입 후 최선 행동",
      value: candidate.projectedBestMoveId,
      message: `${candidate.projectedBestMoveName ?? candidate.projectedBestMoveId} 사용을 최선으로 평가했으며, 그 전에 쓰러질 위험은 ${projectedRisk}%입니다.`,
    });
  }
  if (expectedDamage !== undefined) {
    reasons.push({
      code: "switch.expected_damage",
      label: "교체 후 공격 기대값",
      value: expectedDamage,
      message: `교체 후 기대 피해 ${expectedDamage}를 반영했습니다.`,
    });
  }
  if (matchupValue !== undefined) {
    reasons.push({
      code: "switch.matchup",
      label: "상성 가치",
      value: matchupValue,
      message: `상대와의 상성 가치 ${matchupValue}를 반영했습니다.`,
    });
  }
  if (candidate.emergencyEscape === true) {
    reasons.push({
      code: "switch.emergency_escape",
      label: "위기 탈출",
      value: true,
      message:
        "현재 포켓몬은 다음 공격에 쓰러질 위험이 크고 교체 후보는 버틸 수 있어 긴급 교체 가치를 반영했습니다.",
    });
  }
  if (candidate.noEffectiveMoveEscape === true) {
    reasons.push({
      code: "switch.no_effective_move",
      label: "유효타 부족",
      value: true,
      message:
        "현재 포켓몬의 유효타가 부족하고 교체 후보의 반격 기대값이 더 높습니다.",
    });
  }
  if (Number(candidate.hazardDamage ?? 0) > 0) {
    reasons.push({
      code: "switch.entry_hazard_damage",
      label: "교체 피해",
      value: Number(candidate.hazardDamage),
      message: `교체하면서 설치물 피해 ${Number(candidate.hazardDamage)}를 받을 것으로 예상합니다.`,
    });
  }
  for (const adjustment of switchAdjustments) {
    reasons.push(adjustment);
  }
  if (reasons.length === 0) {
    reasons.push({
      code: "switch.available",
      label: "교체 가능",
      message: "전투 가능한 벤치 후보로 평가했습니다.",
    });
  }
  return reasons;
}

export function toAiActionCandidate(
  candidate,
  {
    type = candidate.type ?? "move",
    difficulty = "standard",
    strategy = "balanced",
  } = {},
) {
  const enrichedCandidate =
    type === "switch" ? candidate : enrichMoveCandidateWithRole(candidate, strategy);
  const id =
    enrichedCandidate.actionId ??
    (type === "switch"
      ? `switch:${enrichedCandidate.slot}`
      : `${type}:${enrichedCandidate.slot ?? enrichedCandidate.id ?? enrichedCandidate.name}`);
  return {
    ...enrichedCandidate,
    id: enrichedCandidate.id ?? enrichedCandidate.moveId ?? enrichedCandidate.switchId ?? id,
    actionId: id,
    type,
    legal: enrichedCandidate.legal ?? !enrichedCandidate.disabled,
    action: {
      type,
      slot: enrichedCandidate.slot,
      id: enrichedCandidate.id ?? enrichedCandidate.moveId ?? enrichedCandidate.switchId ?? null,
      label: enrichedCandidate.name ?? enrichedCandidate.label ?? null,
    },
    expectedDamage: expectedDamageObservation(enrichedCandidate),
    koChance: enrichedCandidate.koChance ?? undefined,
    survivalRisk: finiteNumber(enrichedCandidate.survivalRisk),
    speedRisk: finiteNumber(enrichedCandidate.speedRisk),
    statusValue: finiteNumber(enrichedCandidate.statusValue),
    setupRisk: finiteNumber(enrichedCandidate.setupRisk),
    fieldValue: finiteNumber(enrichedCandidate.fieldValue),
    roleValue: finiteNumber(enrichedCandidate.roleValue),
    resourceCost: finiteNumber(enrichedCandidate.resourceCost),
    score: finiteNumber(enrichedCandidate.score, 0),
    reasons:
      type === "switch"
        ? switchDecisionReasons(enrichedCandidate, strategy)
        : moveDecisionReasons(enrichedCandidate, difficulty, strategy),
  };
}

export function toAiTraceCandidate(candidate, options) {
  const normalized = toAiActionCandidate(candidate, options);
  const setupThreatEvaluation = normalized.setupThreatEvaluation
    ? {
        opponentCanSetup:
          normalized.setupThreatEvaluation.opponentCanSetup === true,
        setupLikelihood:
          normalized.setupThreatEvaluation.setupLikelihood,
        sweepRiskAfterSetup:
          normalized.setupThreatEvaluation.sweepRiskAfterSetup,
        riskTier: normalized.setupThreatEvaluation.riskTier,
        availableAnswersAfterSetup:
          normalized.setupThreatEvaluation.availableAnswersAfterSetup,
        oneMoreTurnUnmanageable:
          normalized.setupThreatEvaluation.oneMoreTurnUnmanageable,
      }
    : undefined;
  return {
    slot: normalized.slot,
    id: normalized.id,
    name: normalized.name,
    type: normalized.type,
    actionId: normalized.actionId,
    action: normalized.action,
    legal: normalized.legal,
    pp: normalized.pp,
    maxPp: normalized.maxPp,
    disabled: normalized.disabled,
    category: normalized.category,
    power: normalized.power,
    accuracy: normalized.accuracy,
    priority: normalized.priority,
    expectedDamage: normalized.expectedDamage,
    koChance: normalized.koChance,
    score: normalized.score,
    roleValue: normalized.roleValue,
    damageRangeMinimum: normalized.damageRangeMinimum,
    damageRangeMaximum: normalized.damageRangeMaximum,
    battleStateValueDelta: normalized.battleStateValueDelta,
    winProbabilityBefore:
      normalized.oneTurnEvaluation?.winProbabilityBefore,
    winProbabilityAfter:
      normalized.oneTurnEvaluation?.winProbabilityAfter,
    winProbabilityDelta:
      normalized.oneTurnEvaluation?.winProbabilityDelta,
    oneTurnEvaluation: normalized.oneTurnEvaluation,
    searchEvaluation: candidate.searchEvaluation,
    opponentConditionalPriorityLikelihood:
      normalized.opponentConditionalPriorityLikelihood,
    opponentSetupMoveCount: normalized.opponentSetupMoveCount,
    opponentSetupFirstTurnLikelihood:
      normalized.opponentSetupFirstTurnLikelihood,
    opponentSetupSweepRisk: normalized.opponentSetupSweepRisk,
    setupThreatEvaluation,
    setupFutureTargetCount: normalized.setupFutureTargetCount,
    setupFuturePressureGain: normalized.setupFuturePressureGain,
    setupFollowupSurvivalProbability:
      normalized.setupFollowupSurvivalProbability,
    setupGuardConsumptionProbability:
      normalized.setupGuardConsumptionProbability,
    setupFollowupActsBeforeThreat:
      normalized.setupFollowupActsBeforeThreat,
    opponentKnockoutBeforeActionProbability:
      normalized.opponentKnockoutBeforeActionProbability,
    reliableKoAlternative: normalized.reliableKoAlternative,
    knockoutBoostAlternative: normalized.knockoutBoostAlternative,
    canReachNextAction: normalized.canReachNextAction,
    mustPreserveResource: normalized.mustPreserveResource,
    mustPreserveFor: normalized.mustPreserveFor,
    reasons: normalized.reasons,
  };
}

export function compareAiDecisionPolicies(
  candidates = [],
  { materialThreshold = 0.02 } = {},
) {
  const comparable = candidates.filter(
    (candidate) =>
      candidate?.legal !== false &&
      Number.isFinite(Number(candidate?.winProbabilityAfter)),
  );
  const selectedCandidates = comparable.filter(
    (candidate) => candidate.selected === true,
  );
  const heuristicSelection =
    selectedCandidates.find((candidate) => candidate.type === "gimmick") ??
    selectedCandidates[0] ??
    null;
  const winProbabilitySelection =
    [...comparable].sort(
      (left, right) =>
        Number(right.winProbabilityAfter) -
          Number(left.winProbabilityAfter) ||
        Number(right.score ?? 0) - Number(left.score ?? 0),
    )[0] ?? null;
  if (!heuristicSelection || !winProbabilitySelection) {
    return null;
  }
  const probabilityGap =
    Number(winProbabilitySelection.winProbabilityAfter) -
    Number(heuristicSelection.winProbabilityAfter);
  return {
    heuristicActionId:
      heuristicSelection.actionId ?? heuristicSelection.id ?? "",
    heuristicAction: heuristicSelection.name ?? heuristicSelection.id ?? "",
    heuristicScore: finiteNumber(heuristicSelection.score, 0),
    heuristicWinProbability: Number(
      heuristicSelection.winProbabilityAfter,
    ),
    winProbabilityActionId:
      winProbabilitySelection.actionId ?? winProbabilitySelection.id ?? "",
    winProbabilityAction:
      winProbabilitySelection.name ?? winProbabilitySelection.id ?? "",
    winProbabilityScore: finiteNumber(winProbabilitySelection.score, 0),
    winProbability: Number(winProbabilitySelection.winProbabilityAfter),
    probabilityGap:
      Math.round(probabilityGap * 10_000) / 10_000,
    differs: probabilityGap > 1e-9,
    materiallyDiffers: probabilityGap >= materialThreshold,
    materialThreshold,
  };
}

export function selectWinProbabilityCandidate(
  candidates = [],
  heuristicSelection = null,
  { minimumGain = 0.02 } = {},
) {
  const comparable = candidates.filter(
    (candidate) =>
      candidate?.legal !== false &&
      Number.isFinite(
        Number(candidate?.oneTurnEvaluation?.winProbabilityAfter),
      ),
  );
  const probabilityWinner =
    [...comparable].sort(
      (left, right) =>
        Number(right.oneTurnEvaluation.winProbabilityAfter) -
          Number(left.oneTurnEvaluation.winProbabilityAfter) ||
        Number(right.score ?? 0) - Number(left.score ?? 0),
    )[0] ?? null;
  if (!probabilityWinner) return heuristicSelection;
  const heuristicProbability = Number(
    heuristicSelection?.oneTurnEvaluation?.winProbabilityAfter,
  );
  if (
    heuristicSelection &&
    Number.isFinite(heuristicProbability) &&
    Number(probabilityWinner.oneTurnEvaluation.winProbabilityAfter) <
      heuristicProbability + minimumGain
  ) {
    return heuristicSelection;
  }
  return probabilityWinner;
}

export function selectAiMoveCandidate(
  candidates,
  {
    difficulty = "standard",
    strategy = "balanced",
    rng = createAiRng(0),
  } = {},
) {
  const available = candidates.filter(
    (candidate) => !candidate.disabled && Number(candidate.pp ?? 1) > 0,
  );
  if (available.length === 0) return null;
  const usable = available.filter((candidate) => candidate.willFail !== true);
  const selectable = usable.length > 0 ? usable : available;
  if (difficulty === "novice") {
    return selectable[rng.nextIndex(selectable.length)];
  }
  const ranked = rankAiMoveCandidates(selectable, difficulty, strategy);
  if (strategy === "unpredictable" && ranked.length > 1) {
    return ranked[rng.nextIndex(Math.min(3, ranked.length))];
  }
  if (difficulty === "standard" && ranked.length > 1) {
    return ranked[rng.nextIndex(4) === 0 ? 1 : 0];
  }
  return ranked[0];
}

export function aiDecisionReason(strategy = "balanced", gimmick = "") {
  if (gimmick === "dynamax") {
    return "엔트리의 다이맥스 지시에 따라 이번 행동에 다이맥스를 사용합니다.";
  }
  if (strategy === "aggressive") {
    return "공격 성향으로 피해량과 KO 가능성을 우선해 행동을 선택했습니다.";
  }
  if (strategy === "defensive") {
    return "방어 성향으로 명중 안정성, 회복, 변화기 가치를 함께 비교했습니다.";
  }
  if (strategy === "ace_check") {
    return "에이스 견제 성향으로 상대 핵심 자원 억제와 복수 처리 가치를 높게 비교했습니다.";
  }
  if (strategy === "reckless_ace") {
    return "저돌적 에이스 성향으로 즉시 돌파와 에이스 전개 기회를 높게 평가했습니다.";
  }
  if (strategy === "setup") {
    return "랭크업 전개 성향으로 안전한 강화와 스윕 기회를 우선 비교했습니다.";
  }
  if (strategy === "hazard") {
    return "판 장악 성향으로 설치물과 교체 압박 가치를 높게 평가했습니다.";
  }
  if (strategy === "tempo") {
    return "템포/피벗 성향으로 유리 대면 연결과 속도 주도권을 높게 평가했습니다.";
  }
  if (strategy === "unpredictable") {
    return "예측 방지 성향으로 상위 후보 안에서 시드 기반 선택을 분산했습니다.";
  }
  return "균형 성향으로 피해량, 명중률, 우선도, 변화기 가치를 종합했습니다.";
}

export function createAiMoveTrace({
  turn,
  side,
  sideName,
  species,
  difficulty = "standard",
  strategy = "balanced",
  candidates,
  selected,
  gimmick = "",
}) {
  const ranked = rankAiMoveCandidates(candidates, difficulty, strategy).map(
    (candidate) => ({
      ...toAiTraceCandidate(candidate, {
        type: "move",
        difficulty,
        strategy,
      }),
      selected: candidate.slot === selected?.slot,
    }),
  );
  const chosen = ranked.find((candidate) => candidate.selected);
  return {
    turn,
    side,
    sideName,
    species,
    kind: "move",
    difficulty,
    strategy,
    chosenAction: chosen?.name ?? selected?.name ?? selected?.id ?? "",
    gimmick: gimmick.trim(),
    reason: aiDecisionReason(strategy, gimmick.trim()),
    candidates: ranked,
    aiModel: "common-battle-ai",
    difficultyLabel: DIFFICULTY_LABELS[difficulty] ?? difficulty,
  };
}

export function scoreAiSwitchCandidate(candidate, strategy = "balanced") {
  const hpPercent = Number.isFinite(Number(candidate.hpPercent))
    ? Number(candidate.hpPercent)
    : 0;
  const expectedDamage = Number.isFinite(Number(candidate.expectedDamage))
    ? Number(candidate.expectedDamage)
    : 0;
  const matchupValue = Number.isFinite(Number(candidate.matchupValue))
    ? Number(candidate.matchupValue)
    : 0;
  return JSON.parse(
    scoreObservedActionCandidateJson(
      JSON.stringify({
        kind: "switch",
        strategy,
        expectedDamage,
        matchupValue,
        hpRatio: hpPercent,
        adjustments: switchRuleAdjustments(candidate, strategy).map(
          ({ code, weight }) => ({ code, weight: Number(weight ?? 0) }),
        ),
      }),
    ),
  ).score;
}

export function rankAiSwitchCandidates(candidates, strategy = "balanced") {
  return candidates
    .map((candidate) => ({
      ...candidate,
      score: Math.round(scoreAiSwitchCandidate(candidate, strategy) * 100) / 100,
    }))
    .sort((left, right) => right.score - left.score || left.slot - right.slot);
}

export function selectAiSwitchCandidate(
  candidates,
  {
    difficulty = "standard",
    strategy = "balanced",
    rng = createAiRng(0),
  } = {},
) {
  const available = candidates.filter(
    (candidate) => !candidate.disabled && !candidate.active && !candidate.fainted,
  );
  if (available.length === 0) return null;
  if (difficulty === "novice") {
    return available[rng.nextIndex(available.length)];
  }
  const ranked = rankAiSwitchCandidates(available, strategy);
  if (strategy === "unpredictable" && ranked.length > 1) {
    return ranked[rng.nextIndex(Math.min(3, ranked.length))];
  }
  if (difficulty === "standard" && ranked.length > 1) {
    return ranked[rng.nextIndex(4) === 0 ? 1 : 0];
  }
  return ranked[0];
}

export function createAiSwitchTrace({
  turn,
  side,
  sideName,
  species,
  difficulty = "standard",
  strategy = "balanced",
  candidates,
  selected,
}) {
  const ranked = rankAiSwitchCandidates(candidates, strategy).map((candidate) => ({
    ...toAiTraceCandidate(candidate, {
      type: "switch",
      difficulty,
      strategy,
    }),
    selected: candidate.slot === selected?.slot,
  }));
  return {
    turn,
    side,
    sideName,
    species,
    kind: "switch",
    difficulty,
    strategy,
    chosenAction: selected ? `슬롯 ${selected.slot} 교체` : "기본 교체",
    reason: "공통 AI가 남은 체력과 공격 기대값을 기준으로 교체 후보를 선택했습니다.",
    candidates: ranked,
    aiModel: "common-battle-ai",
    difficultyLabel: DIFFICULTY_LABELS[difficulty] ?? difficulty,
  };
}

export function scoreAiDynamaxCandidate({
  active = {},
  configured = {},
  selectedMove = {},
  moveCandidates = [],
  dynamaxMove = null,
  baseMoveForDynamax = null,
  dynamaxMoveCandidates = [],
  forceDynamax = false,
} = {}) {
  const reasons = [];
  if (!active.canDynamax) {
    return {
      id: "dynamax",
      type: "gimmick",
      legal: false,
      score: -Infinity,
      reasons: [
        scoreAdjustment(
          "gimmick.dynamax.unavailable",
          "다이맥스 불가",
          false,
          0,
          "현재 포켓몬은 다이맥스를 사용할 수 없습니다.",
        ),
      ],
    };
  }
  if (active.dynamaxReservedForOther === true) {
    return {
      id: active.canGigantamax || configured?.gimmicks?.gigantamax ? "gigantamax" : "dynamax",
      type: "gimmick",
      legal: true,
      score: -999,
      reasons: [
        scoreAdjustment(
          "gimmick.dynamax.reserved_for_configured",
          "지정 대상 보존",
          true,
          -999,
          "엔트리에 지정된 다른 다이맥스 대상이 아직 살아 있어 현재 포켓몬의 다이맥스 사용을 보류했습니다.",
        ),
      ],
    };
  }

  const evaluatedMove = dynamaxMove ?? selectedMove;
  let score = forceDynamax || configured?.gimmicks?.dynamax ? 18 : 0;
  if (score > 0) {
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.configured",
        "다이맥스 후보",
        true,
        score,
        forceDynamax
          ? "엔트리의 다이맥스 지정을 기본 후보로 반영했습니다."
          : "AI 설정의 다이맥스 허용을 기본 후보로 반영했습니다.",
      ),
    );
  }
  const dynamaxIncomingKoProbability = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(dynamaxMove?.opponentKnockoutProbability, 0),
    ),
  );
  const dynamaxActionBeforeThreatProbability = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(dynamaxMove?.actionBeforeThreatProbability, 0),
    ),
  );
  const guaranteedKoBeforeThreat =
    dynamaxMove?.koChance === "guaranteed"
      ? dynamaxActionBeforeThreatProbability
      : 0;
  const dynamaxFatalExchangeProbability =
    dynamaxIncomingKoProbability * (1 - guaranteedKoBeforeThreat);
  if (dynamaxMove && dynamaxFatalExchangeProbability >= 0.75) {
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.cannot_survive_exchange",
        "다이맥스 후에도 기절",
        dynamaxFatalExchangeProbability,
        -999,
        `다이맥스로 체력을 늘려도 상대의 다음 공격을 받으면 쓰러질 가능성이 ${Math.round(dynamaxFatalExchangeProbability * 100)}%이며, 먼저 확정 KO로 공격을 차단할 수도 없어 기믹 자원을 보존합니다.`,
      ),
    );
    return {
      id: active.canGigantamax || configured?.gimmicks?.gigantamax
        ? "gigantamax"
        : "dynamax",
      type: "gimmick",
      legal: true,
      score: -999,
      reasons,
    };
  }

  const incomingRatio = ratioValue(
    active.incomingDamageRatio,
    selectedMove.incomingDamageRatio,
    selectedMove.opponentMaxDamageToCurrentHealthRatio,
  );
  const hpPercent = ratioValue(active.hpPercent, selectedMove.hpPercent, 1);
  if (incomingRatio !== undefined && incomingRatio >= hpPercent) {
    score += 30;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.survival",
        "생존 보강",
        incomingRatio,
        30,
        "현재 체력에서 큰 피해나 KO 위험이 있어 다이맥스 생존 가치를 반영했습니다.",
      ),
    );
  } else if (incomingRatio !== undefined && incomingRatio <= 1 / 3) {
    const setupCandidate =
      candidateTagSet(selectedMove).has("setupboost") ||
      moveCandidates.some((candidate) => candidateTagSet(candidate).has("setupboost"));
    if (setupCandidate && !hasFightingAttack(moveCandidates)) {
      score -= 24;
      reasons.push(
        scoreAdjustment(
          "gimmick.dynamax.delay_for_setup",
          "랭크업 후 다이맥스",
          incomingRatio,
          -24,
          "상대 피해가 낮고 랭크업기가 있으며 다이맥스 격투 기술이 없어, 1랭크를 올린 뒤 다이맥스하는 판단을 우선합니다.",
        ),
      );
    }
  }

  if (evaluatedMove.koChance === "guaranteed") {
    score += 8;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.ko_pressure",
        "KO 압박",
        true,
        8,
        "선택 기술이 KO 압박을 만들 수 있어 다이맥스 돌파 가치를 반영했습니다.",
      ),
    );
  }
  if (Number(evaluatedMove.expectedDamage ?? 0) >= Number(active.opponentHp ?? Infinity) * 0.6) {
    score += 6;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.damage_pressure",
        "피해 압박",
        evaluatedMove.expectedDamage,
        6,
        `${evaluatedMove.name ?? "맥스기술"}의 피해 기대값이 높아 다이맥스 화력 가치를 반영했습니다.`,
      ),
    );
  }

  if (
    (dynamaxMoveCandidates.length > 0
      ? hasFightingAttack(dynamaxMoveCandidates)
      : hasFightingAttack(moveCandidates)) &&
    moveCandidates.length > 0 &&
    hasSetupMove(moveCandidates)
  ) {
    score += 8;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.max_knuckle",
        "다이너클 랭크업",
        true,
        8,
        "격투 공격기가 있어 다이맥스 중에도 다이너클로 공격 상승을 노릴 수 있습니다.",
      ),
    );
  }

  if (dynamaxMove && baseMoveForDynamax) {
    const baseScore = Number(baseMoveForDynamax.score ?? 0);
    const maxScore = Number(dynamaxMove.score ?? 0);
    const rawDifference = maxScore - baseScore;
    const scoreDifference = Math.max(-120, Math.min(0, rawDifference * 0.5));
    score += scoreDifference;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.move_conversion",
        "맥스기술 변환",
        rawDifference,
        scoreDifference,
        `최선의 일반 행동 ${baseMoveForDynamax.name ?? "원래 기술"}(${baseScore.toFixed(
          2,
        )})과 최선의 맥스 행동 ${dynamaxMove.name ?? "맥스기술"}(${maxScore.toFixed(
          2,
        )})의 전술 점수 차이를 반영했습니다.`,
      ),
    );
    if (dynamaxMove.oneTurnEvaluation) {
      const maxDelta = finiteNumber(
        dynamaxMove.oneTurnEvaluation.delta,
        dynamaxMove.battleStateValueDelta,
      );
      const baseDelta = finiteNumber(
        baseMoveForDynamax.oneTurnEvaluation?.delta,
        baseMoveForDynamax.battleStateValueDelta,
      );
      reasons.push(
        scoreAdjustment(
          "simulation.gimmick_one_turn_state",
          "다이맥스 후 1턴 상태",
          dynamaxMove.oneTurnEvaluation.qValue,
          0,
          `다이맥스 행동의 다음 상태 변화는 ${
            maxDelta >= 0 ? "+" : ""
          }${maxDelta}${
            baseDelta !== undefined
              ? `, 일반 행동 대비 ${maxDelta - baseDelta >= 0 ? "+" : ""}${Math.round(
                  (maxDelta - baseDelta) * 100,
                ) / 100}`
              : ""
          }입니다.`,
        ),
      );
    }

    const baseHitCount = Math.max(1, Number(baseMoveForDynamax.hitCount ?? 1));
    const maxHitCount = Math.max(1, Number(dynamaxMove.hitCount ?? 1));
    const losesMultiHitBreaker =
      baseHitCount > maxHitCount &&
      (baseMoveForDynamax.breaksSturdy === true ||
        baseMoveForDynamax.breaksFocusSash === true) &&
      dynamaxMove.koChance !== "guaranteed";
    if (losesMultiHitBreaker) {
      score -= 80;
      reasons.push(
        scoreAdjustment(
          "gimmick.dynamax.loses_multi_hit_breaker",
          "연속타 돌파 상실",
          `${baseHitCount} -> ${maxHitCount}`,
          -80,
          `일반 상태의 ${baseMoveForDynamax.name ?? "원래 기술"} ${baseHitCount}타 대신 ${
            dynamaxMove.name ?? "맥스기술"
          } ${maxHitCount}타를 사용하게 되어 옹골참/기합의띠 돌파가 사라집니다.`,
        ),
      );
    }

    if (
      baseMoveForDynamax.koChance === "guaranteed" &&
      dynamaxMove.koChance !== "guaranteed"
    ) {
      score -= 45;
      reasons.push(
        scoreAdjustment(
          "gimmick.dynamax.loses_guaranteed_ko",
          "확정 KO 상실",
          dynamaxMove.koChance ?? "none",
          -45,
          "원래 기술의 확정 KO가 맥스기술 변환 후 사라져 다이맥스 사용을 크게 감점했습니다.",
        ),
      );
    }
  }

  if (
    score >= DYNAMAX_SCORE_THRESHOLD &&
    active.aceQualified === true
  ) {
    score += 4;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.ace_preference",
        "에이스 다이맥스",
        true,
        4,
        "이미 사용 기준을 충족한 후보 중 팀의 단일 에이스가 다이맥스 체력과 맥스기술을 활용하도록 선호도를 높였습니다.",
      ),
    );
  } else if (
    score >= DYNAMAX_SCORE_THRESHOLD &&
    active.livingAceOther === true
  ) {
    const adjustedScore = Math.max(
      DYNAMAX_SCORE_THRESHOLD,
      score - 6,
    );
    const penalty = adjustedScore - score;
    score = adjustedScore;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.reserve_for_ace",
        "에이스 자원 보존",
        active.livingAceName ?? true,
        penalty,
        `${active.livingAceName ?? "팀 에이스"}가 살아 있어 비에이스의 다이맥스 선호도를 낮추되, 자체 사용 근거가 충분한 후보를 강제로 배제하지는 않습니다.`,
      ),
    );
  }

  if (
    forceDynamax &&
    score >= DYNAMAX_SCORE_THRESHOLD - 5 &&
    score < DYNAMAX_SCORE_THRESHOLD
  ) {
    const forcedFloor = DYNAMAX_SCORE_THRESHOLD - score;
    score = DYNAMAX_SCORE_THRESHOLD;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.configured_floor",
        "지정 대상 우선",
        true,
        forcedFloor,
        "안전 조건을 통과한 엔트리 지정 대상이므로 다이맥스 활성화 기준을 충족하도록 보정했습니다.",
      ),
    );
  }

  if (reasons.length === 0) {
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.neutral",
        "다이맥스 기준점",
        true,
        0,
        "뚜렷한 다이맥스 사용 이득이나 지연 이유가 없어 보류합니다.",
      ),
    );
  }

  return {
    id: active.canGigantamax || configured?.gimmicks?.gigantamax ? "gigantamax" : "dynamax",
    type: "gimmick",
    legal: true,
    score: Math.round(score * 100) / 100,
    oneTurnEvaluation: dynamaxMove?.oneTurnEvaluation ?? null,
    battleStateValueDelta:
      dynamaxMove?.battleStateValueDelta ??
      dynamaxMove?.oneTurnEvaluation?.delta ??
      null,
    reasons,
  };
}

export function scoreAiProjectedGimmickCandidate({
  id,
  selectedMove = {},
  baseMove = {},
  configured = false,
  activationThreshold,
} = {}) {
  const reasons = [];
  const selectedScore = finiteNumber(selectedMove.score, 0);
  const baseScore = finiteNumber(baseMove.score, 0);
  const selectedDelta = finiteNumber(
    selectedMove.oneTurnEvaluation?.delta,
    selectedMove.battleStateValueDelta,
  );
  const baseDelta = finiteNumber(
    baseMove.oneTurnEvaluation?.delta,
    baseMove.battleStateValueDelta,
  );
  const evaluation = JSON.parse(
    scoreProjectedGimmickJson(
      JSON.stringify({
        id,
        selectedScore,
        baseScore,
        selectedStateDelta: selectedDelta,
        baseStateDelta: baseDelta,
        configured: configured === true,
        activationThreshold,
      }),
    ),
  );
  const {
    id: normalizedId,
    score,
    scoreDifference,
    configuredBonus,
    stateDeltaDifference,
  } = evaluation;

  reasons.push(
    scoreAdjustment(
      `gimmick.${normalizedId}.action_conversion`,
      "기믹 적용 행동 비교",
      scoreDifference,
      scoreDifference,
      `일반 행동 ${baseMove.name ?? baseMove.id ?? "기술"}(${baseScore.toFixed(
        2,
      )})과 기믹 적용 행동 ${
        selectedMove.name ?? selectedMove.id ?? "기술"
      }(${selectedScore.toFixed(2)})의 점수 차이를 반영했습니다.`,
    ),
  );
  if (stateDeltaDifference !== null) {
    reasons.push(
      scoreAdjustment(
        "simulation.gimmick_one_turn_state",
        "기믹 적용 후 1턴 상태",
        selectedMove.oneTurnEvaluation?.qValue ?? null,
        0,
        `기믹 적용 행동의 다음 상태 변화는 ${
          selectedDelta >= 0 ? "+" : ""
        }${selectedDelta}, 일반 행동 대비 차이는 ${
          stateDeltaDifference >= 0 ? "+" : ""
        }${stateDeltaDifference}입니다.`,
      ),
    );
  }
  if (configuredBonus > 0) {
    reasons.push(
      scoreAdjustment(
        `gimmick.${normalizedId}.configured`,
        "엔트리 기믹 지정",
        true,
        configuredBonus,
        "엔트리에 지정된 기믹 후보이므로 사용 우선도를 소폭 높였습니다.",
      ),
    );
  }

  return {
    id: normalizedId,
    type: "gimmick",
    legal: true,
    score,
    activationThreshold: evaluation.activationThreshold,
    selectedMove,
    oneTurnEvaluation: selectedMove.oneTurnEvaluation ?? null,
    battleStateValueDelta:
      selectedMove.battleStateValueDelta ??
      selectedMove.oneTurnEvaluation?.delta ??
      null,
    reasons,
  };
}

export function selectAiGimmick({
  active = {},
  configured = {},
  moveSlot = 1,
  selectedMove = {},
  moveCandidates = [],
  dynamaxMove = null,
  baseMoveForDynamax = null,
  dynamaxMoveCandidates = [],
  projectedGimmickCandidates = [],
  forceDynamax = false,
  alreadyUsed = {},
} = {}) {
  let dynamaxCandidate = null;
  const projectedCandidates = new Map(
    projectedGimmickCandidates
      .filter((candidate) => candidate?.id)
      .map((candidate) => [cleanId(candidate.id), candidate]),
  );
  const availableCandidates = [];
  if (!alreadyUsed.mega) {
    const megaSuffix = active.canMegaEvo
      ? " mega"
      : active.canMegaEvoX
        ? " megax"
        : active.canMegaEvoY
          ? " megay"
          : "";
    if (megaSuffix) {
      const projectedMega = projectedCandidates.get("mega");
      if (!projectedMega) {
        return { id: "mega", showdownSuffix: megaSuffix };
      }
      availableCandidates.push({
        ...projectedMega,
        id: "mega",
        showdownSuffix: megaSuffix,
      });
    }
  }
  if (!alreadyUsed.zmove && active.canZMove?.[moveSlot - 1]) {
    return { id: "zmove", showdownSuffix: " zmove" };
  }
  if (!alreadyUsed.dynamax && active.canDynamax) {
    dynamaxCandidate = scoreAiDynamaxCandidate({
      active,
      configured,
      selectedMove,
      moveCandidates,
      dynamaxMove,
      baseMoveForDynamax,
      dynamaxMoveCandidates,
      forceDynamax,
    });
    if (dynamaxCandidate.score >= DYNAMAX_SCORE_THRESHOLD) {
      const useGigantamax =
        active.canGigantamax === true || configured?.gimmicks?.gigantamax === true;
      availableCandidates.push({
        id: useGigantamax ? "gigantamax" : "dynamax",
        showdownSuffix: " dynamax",
        ...dynamaxCandidate,
        id: useGigantamax ? "gigantamax" : "dynamax",
        forced: forceDynamax,
        activationThreshold: DYNAMAX_SCORE_THRESHOLD,
      });
    }
  }
  if (
    !alreadyUsed.terastallize &&
    active.canTerastallize &&
    (
      configured?.gimmicks?.teraEligible === true ||
      (
        configured?.gimmicks?.teraEligible == null &&
        configured?.gimmicks?.tera
      )
    )
  ) {
    const projectedTera = projectedCandidates.get("terastallize");
    if (!projectedTera) {
      return { id: "terastallize", showdownSuffix: " terastallize" };
    }
    availableCandidates.push({
      ...projectedTera,
      id: "terastallize",
      showdownSuffix: " terastallize",
    });
  }
  const selectedCandidate = availableCandidates
    .filter(
      (candidate) =>
        candidate.legal !== false &&
        finiteNumber(candidate.score, -Infinity) >=
          finiteNumber(candidate.activationThreshold, 0),
    )
    .sort(
      (left, right) => {
        if (left.forced !== right.forced) return right.forced ? 1 : -1;
        return (
          finiteNumber(right.score, -Infinity) -
          finiteNumber(right.activationThreshold, 0) -
          (finiteNumber(left.score, -Infinity) -
            finiteNumber(left.activationThreshold, 0))
        );
      },
    )[0];
  if (selectedCandidate) {
    return {
      id: selectedCandidate.id,
      showdownSuffix: selectedCandidate.showdownSuffix,
      candidate: selectedCandidate,
    };
  }
  const bestRejectedCandidate = [
    ...availableCandidates,
    dynamaxCandidate,
  ]
    .filter(Boolean)
    .sort(
      (left, right) =>
        finiteNumber(right.score, -Infinity) -
        finiteNumber(left.score, -Infinity),
    )[0];
  return {
    id: "",
    showdownSuffix: "",
    candidate: bestRejectedCandidate ?? null,
  };
}

export function selectAiTargetSuffix(move, activeIndex, activeCount, team = []) {
  if (activeCount < 2) return "";
  if (["normal", "any", "adjacentFoe"].includes(move?.target)) {
    return ` ${activeCount === 3 ? 2 : 1}`;
  }
  if (move?.target === "adjacentAlly") {
    const allyIndex = team.findIndex(
      (pokemon, index) =>
        index !== activeIndex &&
        pokemon.active &&
        !String(pokemon.condition).endsWith(" fnt"),
    );
    return allyIndex >= 0 ? ` -${allyIndex + 1}` : "";
  }
  if (move?.target === "adjacentAllyOrSelf") {
    return ` -${activeIndex + 1}`;
  }
  return "";
}
