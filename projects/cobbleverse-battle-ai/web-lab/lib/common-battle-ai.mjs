import MOVE_ROLE_CATALOG from "../../data/ai/ai-move-role-classification.json" with { type: "json" };
import POKEMON_ROLE_OVERRIDES from "../../data/ai/ai-pokemon-role-overrides.json" with { type: "json" };
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
const PROJECTED_GIMMICK_THRESHOLDS = {
  mega: 0,
  terastallize: 5,
};

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
    source: "CobbleverseAI",
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
  const sortedLevels = team
    .map((member) => pokemonLevel(member))
    .filter((level) => level > 0)
    .sort((left, right) => right - left);
  const teamContext = {
    maxLevel: sortedLevels[0] ?? 0,
    secondMaxLevel:
      sortedLevels.find((level) => level < (sortedLevels[0] ?? 0)) ?? sortedLevels[0] ?? 0,
    hasBatonPassSupport: team.some((member) =>
      pokemonMoveIds(member).includes("batonpass"),
    ),
    hasTeamSetupRoute: team.some((member) => {
      const moveIds = pokemonMoveIds(member);
      return (
        moveIds.some((moveId) =>
          (moveRoleEntry(moveId)?.tags ?? []).some(
            (tag) => cleanId(tag) === "setupboost",
          ),
        ) ||
        ACE_SETUP_ABILITY_IDS.has(pokemonAbilityId(member))
      );
    }),
  };
  if (teamContext.hasBatonPassSupport) {
    teamContext.hasTeamSetupRoute = true;
  }
  const roles = team.map((member, index) =>
    analyzeTeamMemberRole(member, index, teamContext),
  );
  const aceSelection = finalizeTeamAceRoles(roles, teamContext);
  const byRole = (role) =>
    roles
      .filter((entry) => entry.roles.some((candidate) => candidate.role === role))
      .sort(
        (left, right) =>
          Number(right.roleScores[role] ?? 0) - Number(left.roleScores[role] ?? 0),
      );
  const aceCandidates = aceSelection.ace ? [aceSelection.ace] : [];
  const subAceCandidates = aceSelection.subAces;
  const defensiveCore = byRole("wall").slice(0, 3);
  const speedControl = [
    ...new Map(
      [...byRole("revengeKiller"), ...byRole("pivot")].map((entry) => [
        entry.slot,
        entry,
      ]),
    ).values(),
  ].slice(0, 4);
  const hazardSetters = byRole("hazardControl").filter((entry) =>
    entry.moveIds.some((moveId) =>
      (moveRoleEntry(moveId)?.tags ?? []).some((tag) => cleanId(tag) === "hazardset"),
    ),
  );
  const hazardRemovers = byRole("hazardControl").filter((entry) =>
    entry.moveIds.some((moveId) =>
      (moveRoleEntry(moveId)?.tags ?? []).some((tag) => cleanId(tag) === "hazardremove"),
    ),
  );
  const setupThreats = byRole("setupSweeper").slice(0, 3);
  const vulnerabilities = [];
  if (aceCandidates.length === 0) vulnerabilities.push("명확한 에이스 후보가 약합니다.");
  if (defensiveCore.length === 0) vulnerabilities.push("안정적인 막이 후보가 부족합니다.");
  if (hazardRemovers.length === 0) vulnerabilities.push("설치물 제거 수단이 확인되지 않았습니다.");

  return {
    roles,
    aceCandidates,
    subAceCandidates,
    defensiveCore,
    speedControl,
    hazardPlan: {
      setters: hazardSetters.slice(0, 3),
      removers: hazardRemovers.slice(0, 3),
    },
    setupThreats,
    vulnerabilities,
  };
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
  const threats = enemies
    .map((enemy, enemyIndex) => {
      if (!livingBattleMember(enemy)) return null;
      const enemyRole = enemyAnalysis.roles?.[enemyIndex] ?? {};
      const aceScore = Math.max(0, Math.min(20, finiteNumber(enemyRole.aceScore, 0)));
      const setupScore = Math.max(
        0,
        finiteNumber(enemyRole.roleScores?.setupSweeper, 0),
      );
      const offense = Math.max(
        pokemonStat(enemy, ["attack", "atk"]),
        pokemonStat(enemy, ["specialAttack", "specialAtk", "spa"]),
      );
      const hpPercent = battleMemberHpPercent(enemy);
      const threatScore =
        Math.min(12, aceScore) +
        Math.min(6, setupScore) +
        Math.max(0, offense - 100) / 15 +
        hpPercent * 2;
      const threatLevel =
        threatScore >= 14
          ? "critical"
          : threatScore >= 9
            ? "high"
            : threatScore >= 5
              ? "medium"
              : "low";
      if (!["critical", "high"].includes(threatLevel)) {
        return {
          enemySlot: Number(enemy.slot ?? enemyIndex + 1),
          enemyPokemonId: battleMemberId(enemy, enemyIndex + 1),
          species: pokemonDisplayName(enemy),
          threatLevel,
          threatScore: Math.round(threatScore * 100) / 100,
          counters: [],
          softChecks: [],
          revengeKillers: [],
          mustPreserveResources: [],
        };
      }
      const resources = allies
        .map((ally, allyIndex) => {
          if (!livingBattleMember(ally)) return null;
          const matchup =
            evaluateMatchup({
              ally,
              enemy,
              allyIndex,
              enemyIndex,
            }) ?? {};
          const allyHpPercent = ratioValue(
            matchup.allyHpPercent,
            battleMemberHpPercent(ally),
          );
          const incomingDamageRatio = Math.max(
            0,
            finiteNumber(matchup.incomingDamageRatio, 1),
          );
          const outgoingDamageRatio = Math.max(
            0,
            finiteNumber(matchup.outgoingDamageRatio, 0),
          );
          const survivesHit =
            matchup.survivesHit === true ||
            incomingDamageRatio < allyHpPercent;
          const revengeKill =
            matchup.priorityKo === true ||
            (outgoingDamageRatio >= 1 && matchup.actsBefore === true);
          const hardCounter =
            survivesHit &&
            (outgoingDamageRatio >= 0.65 ||
              (incomingDamageRatio <= 0.35 && outgoingDamageRatio >= 0.35));
          const softCheck =
            hardCounter ||
            (survivesHit &&
              (outgoingDamageRatio >= 0.35 || incomingDamageRatio <= 0.6));
          if (!softCheck && !revengeKill) return null;
          const allyRole = allyAnalysis.roles?.[allyIndex] ?? {};
          return {
            slot: Number(ally.slot ?? allyIndex + 1),
            pokemonId: battleMemberId(ally, allyIndex + 1),
            species: pokemonDisplayName(ally),
            classification: hardCounter
              ? "counter"
              : revengeKill
                ? "revenge_killer"
                : "soft_check",
            incomingDamageRatio,
            outgoingDamageRatio,
            survivesHit,
            actsBefore: matchup.actsBefore === true,
            priorityKo: matchup.priorityKo === true,
            aceQualified: allyRole.aceProfile?.qualifies === true,
          };
        })
        .filter(Boolean)
        .sort(
          (left, right) =>
            Number(right.classification === "counter") -
              Number(left.classification === "counter") ||
            Number(right.priorityKo) - Number(left.priorityKo) ||
            right.outgoingDamageRatio - left.outgoingDamageRatio ||
            left.incomingDamageRatio - right.incomingDamageRatio,
        );
      const counters = resources.filter(
        (resource) => resource.classification === "counter",
      );
      const revengeKillers = resources.filter(
        (resource) => resource.classification === "revenge_killer",
      );
      const softChecks = resources.filter(
        (resource) => resource.classification === "soft_check",
      );
      const mustPreserveResources =
        ["critical", "high"].includes(threatLevel) && counters.length <= 1
          ? counters.length === 1
            ? counters
            : softChecks.length === 1
              ? softChecks
              : revengeKillers.length === 1
                ? revengeKillers
                : []
          : [];
      return {
        enemySlot: Number(enemy.slot ?? enemyIndex + 1),
        enemyPokemonId: battleMemberId(enemy, enemyIndex + 1),
        species: pokemonDisplayName(enemy),
        threatLevel,
        threatScore: Math.round(threatScore * 100) / 100,
        counters,
        softChecks,
        revengeKillers,
        mustPreserveResources,
      };
    })
    .filter(Boolean)
    .sort(
      (left, right) =>
        right.threatScore - left.threatScore || left.enemySlot - right.enemySlot,
    );

  const mustPreserveResources = [
    ...new Map(
      threats.flatMap((threat) =>
        threat.mustPreserveResources.map((resource) => [
          resource.slot,
          {
            ...resource,
            threats: [],
          },
        ]),
      ),
    ).values(),
  ];
  for (const resource of mustPreserveResources) {
    resource.threats = threats
      .filter((threat) =>
        threat.mustPreserveResources.some(
          (candidate) => candidate.slot === resource.slot,
        ),
      )
      .map((threat) => ({
        enemySlot: threat.enemySlot,
        enemyPokemonId: threat.enemyPokemonId,
        species: threat.species,
        threatLevel: threat.threatLevel,
      }));
  }

  return {
    threats,
    mustPreserveResources,
  };
}

function setupAnswerCount(value) {
  if (Array.isArray(value)) return value.length;
  return Math.max(0, finiteNumber(value, 0));
}

export function evaluateSetupThreat({
  setupMoves = [],
  setupMoveIds = [],
  setupLikelihood = 0,
  opponentCurrentBoosts = 0,
  opponentRoleScore = 0,
  opponentAce = false,
  opponentHpPercent = 1,
  immediateDamageRatio = 0,
  counters = [],
  softChecks = [],
  revengeKillers = [],
  punishOptions = [],
} = {}) {
  const normalizedMoves =
    setupMoves.length > 0
      ? setupMoves.map((move) => ({
          id: cleanId(move?.id ?? move?.name ?? move),
          boosts: { ...(move?.selfBoosts ?? move?.boosts ?? {}) },
        }))
      : setupMoveIds.map((id) => ({ id: cleanId(id), boosts: {} }));
  const opponentCanSetup = normalizedMoves.length > 0;
  if (!opponentCanSetup) {
    return {
      opponentCanSetup: false,
      setupMoveCandidates: [],
      setupLikelihood: 0,
      sweepRiskAfterSetup: 0,
      riskTier: 0,
      availableAnswersAfterSetup: {
        counters: 0,
        softChecks: 0,
        revengeKillers: 0,
        estimatedTotal: 0,
      },
      punishOptions: [],
      oneMoreTurnUnmanageable: false,
      freeTurnPenalty: 0,
      reasons: [],
    };
  }

  const strongestBoost = normalizedMoves.reduce(
    (best, move) => {
      const attack = Math.max(
        0,
        finiteNumber(move.boosts.attack, 0),
        finiteNumber(
          move.boosts.specialAttack,
          finiteNumber(move.boosts.specialattack, 0),
        ),
      );
      const speed = Math.max(0, finiteNumber(move.boosts.speed, 0));
      const pressure = attack + speed * 0.8;
      return pressure > best.pressure
        ? { moveId: move.id, attack, speed, pressure }
        : best;
    },
    { moveId: normalizedMoves[0]?.id ?? "", attack: 0, speed: 0, pressure: 0 },
  );
  const counterCount = setupAnswerCount(counters);
  const softCheckCount = setupAnswerCount(softChecks);
  const revengeKillerCount = setupAnswerCount(revengeKillers);
  const effectiveSoftChecks =
    strongestBoost.attack >= 2 ? Math.min(0.5, softCheckCount * 0.25) : softCheckCount * 0.65;
  const effectiveRevengeKillers =
    strongestBoost.speed > 0 ? revengeKillerCount * 0.25 : revengeKillerCount * 0.75;
  const estimatedAnswerCount =
    counterCount + effectiveSoftChecks + effectiveRevengeKillers;
  const answerScarcity =
    estimatedAnswerCount <= 0
      ? 1
      : estimatedAnswerCount < 1
        ? 0.82
        : estimatedAnswerCount < 2
          ? 0.48
          : 0.12;
  const likelihood = Math.max(0, Math.min(1, finiteNumber(setupLikelihood, 0)));
  const currentBoostPressure = Math.min(
    1,
    Math.max(0, finiteNumber(opponentCurrentBoosts, 0)) / 4,
  );
  const nextBoostPressure = Math.min(1, strongestBoost.pressure / 3);
  const rolePressure = Math.min(
    1,
    Math.max(0, finiteNumber(opponentRoleScore, 0)) / 10,
  );
  const hpPressure = Math.max(
    0,
    Math.min(1, finiteNumber(opponentHpPercent, 1)),
  );
  const immediatePunish = Math.max(
    0,
    Math.min(1, finiteNumber(immediateDamageRatio, 0)),
  );
  const rawSweepRisk =
    likelihood *
    (0.18 +
      nextBoostPressure * 0.3 +
      currentBoostPressure * 0.18 +
      answerScarcity * 0.24 +
      rolePressure * 0.08 +
      (opponentAce ? 0.08 : 0) +
      hpPressure * 0.05) *
    (1 - Math.min(0.55, immediatePunish * 0.45));
  const sweepRiskAfterSetup =
    Math.round(Math.max(0, Math.min(1, rawSweepRisk)) * 100) / 100;
  const riskTier =
    sweepRiskAfterSetup >= 0.65
      ? 3
      : sweepRiskAfterSetup >= 0.42
        ? 2
        : sweepRiskAfterSetup >= 0.22
          ? 1
          : 0;
  const normalizedPunishOptions = [
    ...new Set(
      punishOptions
        .map((option) => cleanId(option?.id ?? option?.moveId ?? option))
        .filter(Boolean),
    ),
  ];
  const oneMoreTurnUnmanageable =
    riskTier >= 3 && estimatedAnswerCount < 1 && immediatePunish < 1;
  const freeTurnPenalty =
    Math.round(
      sweepRiskAfterSetup *
        (riskTier >= 3 ? 180 : riskTier === 2 ? 125 : riskTier === 1 ? 70 : 0) *
        100,
    ) / 100;
  const reasons = [
    `랭크업 가능성 ${Math.round(likelihood * 100)}%, 사용 후 스윕 위험 ${Math.round(sweepRiskAfterSetup * 100)}%`,
    `랭크업 후 유효 대응 자원 약 ${Math.round(estimatedAnswerCount * 10) / 10}마리`,
  ];
  if (strongestBoost.moveId) {
    reasons.push(
      `${strongestBoost.moveId}: 공격 ${strongestBoost.attack}, 스피드 ${strongestBoost.speed} 상승`,
    );
  }
  if (normalizedPunishOptions.length > 0) {
    reasons.push(`즉시 응징 수단: ${normalizedPunishOptions.join(", ")}`);
  }

  return {
    opponentCanSetup,
    setupMoveCandidates: normalizedMoves,
    setupLikelihood: likelihood,
    sweepRiskAfterSetup,
    riskTier,
    strongestBoost,
    availableAnswersAfterSetup: {
      counters: counterCount,
      softChecks: softCheckCount,
      revengeKillers: revengeKillerCount,
      estimatedTotal: Math.round(estimatedAnswerCount * 100) / 100,
    },
    punishOptions: normalizedPunishOptions,
    oneMoreTurnUnmanageable,
    freeTurnPenalty,
    reasons,
  };
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
  const own = normalizedBattleValueSide(state.own);
  const opponent = normalizedBattleValueSide(state.opponent);
  const fieldAdvantage = finiteNumber(state.fieldAdvantage, 0);
  const ownAceAliveRatio = own.aceAliveCount / own.aceCandidateCount;
  const opponentAceAliveRatio =
    opponent.aceAliveCount / opponent.aceCandidateCount;
  const ownAceHpRatio = own.aceHpRatio / own.aceCandidateCount;
  const opponentAceHpRatio =
    opponent.aceHpRatio / opponent.aceCandidateCount;
  const components = {
    pokemonCount: (own.livingCount - opponent.livingCount) * 70,
    totalHp: (own.totalHpRatio - opponent.totalHpRatio) * 24,
    aceSurvival:
      (ownAceAliveRatio - opponentAceAliveRatio) * 54 +
      (ownAceHpRatio - opponentAceHpRatio) * 84,
    status:
      (opponent.statusBurden - own.statusBurden) * 9,
    boosts:
      (own.positiveBoosts - opponent.positiveBoosts) * 7,
    hazards:
      (opponent.hazardLayers - own.hazardLayers) * 5,
    gimmicks:
      (own.gimmicksRemaining - opponent.gimmicksRemaining) * 4,
    uniqueCounters:
      (own.uniqueCountersAlive - opponent.uniqueCountersAlive) * 16,
    matchupCoverage:
      (own.matchupCoverage - opponent.matchupCoverage) * 36,
    safeKoCoverage:
      (own.safeKoCoverage - opponent.safeKoCoverage) * 22,
    benchReadiness:
      (own.benchReadiness - opponent.benchReadiness) * 16,
    sweepPotential:
      (own.sweepPotential - opponent.sweepPotential) * 22,
    field: fieldAdvantage,
  };
  const value =
    Math.round(
      Object.values(components).reduce(
        (total, component) => total + component,
        0,
      ) * 100,
    ) / 100;
  return {
    value,
    components,
    state: {
      own,
      opponent,
      fieldAdvantage,
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
  return winEstimateFromEvaluation(
    state,
    evaluateBattleStateValue(state),
    options,
  );
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
  const resolvedRole =
    roleProfile ?? analyzeTeamProfile([member]).roles[0] ?? {};
  const roleNames = (resolvedRole.roles ?? [])
    .filter((entry) => Number(entry.score ?? 0) > 0)
    .map((entry) => entry.role);
  const roleScoreByName = new Map(
    (resolvedRole.roles ?? []).map((entry) => [
      entry.role,
      Number(entry.score ?? 0),
    ]),
  );
  const primaryRoleScore = Number(
    roleScoreByName.get(resolvedRole.primaryRole) ?? 0,
  );
  const meaningfulRoleThreshold = Math.max(2.5, primaryRoleScore * 0.4);
  const trackedRoleNames = roleNames.filter((role) => {
    if (
      role !== resolvedRole.primaryRole &&
      Number(roleScoreByName.get(role) ?? 0) < meaningfulRoleThreshold
    ) {
      return false;
    }
    if (role === "ace") return resolvedRole.aceProfile?.qualifies === true;
    if (role === "support" || role === "pivot") {
      return resolvedRole.primaryRole === role;
    }
    return true;
  });
  const auxiliaryRoles = roleNames.filter(
    (role) => !trackedRoleNames.includes(role),
  );
  const moveIds = resolvedRole.moveIds?.length
    ? resolvedRole.moveIds
    : pokemonMoveIds(member);
  const hazardSetConditions = [
    ...new Set(
      moveIds
        .filter((moveId) =>
          (moveRoleEntry(moveId)?.tags ?? []).some(
            (tag) => cleanId(tag) === "hazardset",
          ),
        )
        .map((moveId) => HAZARD_MOVE_CONDITIONS[cleanId(moveId)])
        .filter(Boolean),
    ),
  ];
  const hasHazardRemoval = moveIds.some((moveId) =>
    (moveRoleEntry(moveId)?.tags ?? []).some(
      (tag) => cleanId(tag) === "hazardremove",
    ),
  );
  const ownHazardLayers = Object.keys(HAZARD_MAX_LAYERS).reduce(
    (total, conditionId) =>
      total + sideConditionLayers(ownSideConditions, conditionId),
    0,
  );
  const hazardSetComplete =
    hazardSetConditions.length > 0 &&
    hazardSetConditions.every(
      (conditionId) =>
        sideConditionLayers(opponentSideConditions, conditionId) >=
        HAZARD_MAX_LAYERS[conditionId],
    );
  const hazardRemovalComplete =
    hasHazardRemoval &&
    ownHazardLayers === 0 &&
    opponentHazardSetterAlive !== true;
  const completedRoles = [];
  const remainingRoles = [];
  const reasons = [];

  for (const role of trackedRoleNames) {
    let complete = false;
    if (role === "lead") {
      complete = activeTurns > 0 || hazardSetComplete;
    } else if (role === "hazardControl") {
      const setTaskComplete =
        hazardSetConditions.length === 0 || hazardSetComplete;
      const removeTaskComplete =
        !hasHazardRemoval || hazardRemovalComplete;
      complete = setTaskComplete && removeTaskComplete;
    } else if (role === "revengeKiller") {
      complete = highThreatCount <= 0;
    } else if (role === "disruptor") {
      complete = setupThreatCount <= 0;
    } else if (role === "wall") {
      complete =
        assignedThreats.length === 0 &&
        highThreatCount <= 0 &&
        opponentLivingCount > 0;
    } else {
      complete = opponentLivingCount <= 0;
    }
    if (complete) completedRoles.push(role);
    else remainingRoles.push(role);
  }

  if (hazardSetConditions.length > 0) {
    reasons.push(
      hazardSetComplete
        ? `설치 임무 완료: ${hazardSetConditions.join(", ")} 최대 층수`
        : `설치 임무 남음: ${hazardSetConditions
            .filter(
              (conditionId) =>
                sideConditionLayers(opponentSideConditions, conditionId) <
                HAZARD_MAX_LAYERS[conditionId],
            )
            .join(", ")}`,
    );
  }
  if (hasHazardRemoval) {
    reasons.push(
      hazardRemovalComplete
        ? "제거 임무 완료: 아군 설치물 없음, 상대 설치 요원 없음"
        : ownHazardLayers > 0
          ? `제거 임무 남음: 아군 쪽 설치물 ${ownHazardLayers}층`
          : "제거 임무 남음: 상대 설치 요원 생존",
    );
  }
  if (assignedThreats.length > 0) {
    reasons.push(`담당 위협 생존: ${assignedThreats.join(", ")}`);
  }

  const roleComplete =
    trackedRoleNames.length > 0 &&
    remainingRoles.length === 0 &&
    opponentLivingCount > 0;
  const expendableResource =
    roleComplete &&
    mustPreserveResource !== true &&
    resolvedRole.aceProfile?.qualifies !== true;
  return {
    roleComplete,
    expendableResource,
    completedRoles,
    remainingRoles,
    auxiliaryRoles,
    hazardSetComplete,
    hazardRemovalComplete,
    assignedThreats: [...assignedThreats],
    reasons,
  };
}

export function aiScoringRuleCatalog() {
  return AI_SCORING_RULES.map((rule) => ({ ...rule }));
}

export function moveRuleAdjustments(candidate, strategy = "balanced") {
  const enriched = enrichMoveCandidateWithRole(candidate, strategy);
  const moveId = cleanId(enriched.id ?? enriched.moveId ?? enriched.name);
  const tags = candidateTagSet(enriched);
  const adjustments = [];
  if (enriched.category === "Status") {
    const disruptionThreeTurnDamageRatio = Math.max(
      0,
      finiteNumber(enriched.disruptionThreeTurnDamageRatio, 3),
    );
    const survivesDisruptionWindow =
      enriched.disruptionCanSurviveThreeTurns === true ||
      disruptionThreeTurnDamageRatio < 1;
    const defensiveSetup = enriched.disruptionDefensiveSetup === true;
    const switchEscapeAvailable =
      enriched.disruptionSwitchEscapeAvailable === true;
    const benchSwitchThreat =
      enriched.disruptionBenchSwitchThreat === true;
    const defensiveDamageReduction = Math.max(
      0,
      finiteNumber(enriched.disruptionDefensiveDamageReduction, 0),
    );
    const disruptionWindowDescription = benchSwitchThreat
      ? `상대의 벤치 교체 경로까지 포함한 3턴 예상 피해가 현재 체력의 ${Math.round(disruptionThreeTurnDamageRatio * 100)}%`
      : `3턴 예상 피해가 현재 체력의 ${Math.round(disruptionThreeTurnDamageRatio * 100)}%`;
    const survivalDiscount = Math.min(
      0.78,
      (survivesDisruptionWindow ? 0.45 : 0) +
        (defensiveSetup && defensiveDamageReduction > 0 ? 0.18 : 0) +
        (switchEscapeAvailable ? 0.1 : 0),
    );
    const exactTauntRisk = Math.max(
      0,
      Math.min(1, finiteNumber(enriched.exactTauntRisk, 0)),
    );
    const exactEncoreRisk = Math.max(
      0,
      Math.min(1, finiteNumber(enriched.exactEncoreRisk, 0)),
    );
    const exactDisruptionRisk = Math.max(exactTauntRisk, exactEncoreRisk);
    if (exactDisruptionRisk > 0) {
      const exactMove = exactTauntRisk >= exactEncoreRisk ? "도발" : "앙코르";
      const exactTauntActsFirst =
        exactMove === "도발" &&
        finiteNumber(
          enriched.opponentDisruptionActsBeforeProbability,
          0,
        ) >= 1;
      const adjustedExactRisk =
        exactDisruptionRisk *
        (exactTauntActsFirst ? 1 : 1 - survivalDiscount);
      adjustments.push(
        scoreAdjustment(
          `rule.status_disruption.exact_${exactMove === "도발" ? "taunt" : "encore"}`,
          `확정된 ${exactMove} 경계`,
          adjustedExactRisk,
          -Math.round(adjustedExactRisk * 700),
          exactTauntActsFirst
            ? "치터 판단으로 상대의 선공 도발을 확인해 이번 변화기가 실패하는 위험을 크게 반영했습니다."
            : survivesDisruptionWindow
              ? `상대의 ${exactMove}을 확인했지만 ${disruptionWindowDescription}라 생존 및 교체 여지를 함께 반영했습니다.`
              : `치터 판단으로 상대가 이번 턴 ${exactMove}을 사용하는 것을 확인해 변화기가 봉쇄되거나 반복 사용에 묶일 위험을 크게 반영했습니다.`,
        ),
      );
    } else {
      const tauntRisk = Math.max(
        0,
        Math.min(1, finiteNumber(enriched.opponentTauntRisk, 0)),
      );
      const encoreRisk = Math.max(
        0,
        Math.min(1, finiteNumber(enriched.opponentEncoreRisk, 0)),
      );
      if (tauntRisk > 0) {
        const adjustedTauntRisk = tauntRisk * (1 - survivalDiscount);
        adjustments.push(
          scoreAdjustment(
            "rule.status_disruption.taunt_risk",
            "상대 도발 경계",
            adjustedTauntRisk,
            -Math.round(adjustedTauntRisk * 90),
            survivesDisruptionWindow
              ? `상대가 도발을 보유했지만 ${disruptionWindowDescription}라 위험 감점을 완화했습니다.`
              : `상대가 도발을 보유하고 있어 이번 변화기가 봉쇄될 위험을 ${Math.round(tauntRisk * 100)}%로 평가했습니다.`,
          ),
        );
      }
      if (encoreRisk > 0) {
        const adjustedEncoreRisk = encoreRisk * (1 - survivalDiscount);
        adjustments.push(
          scoreAdjustment(
            "rule.status_disruption.encore_risk",
            "상대 앙코르 경계",
            adjustedEncoreRisk,
            -Math.round(adjustedEncoreRisk * 75),
            survivesDisruptionWindow
              ? defensiveSetup && defensiveDamageReduction > 0
                ? `상대가 앙코르를 보유했지만 방어형 랭크업 후 ${disruptionWindowDescription}로 줄어 위험 감점을 크게 완화했습니다.`
                : `상대가 앙코르를 보유했지만 ${disruptionWindowDescription}라 생존 및 교체 여지를 반영했습니다.`
              : benchSwitchThreat
                ? `상대가 앙코르를 보유했고 벤치 위협으로 교체하면 3턴 안에 쓰러질 수 있어 반복 사용에 묶이는 위험을 유지했습니다.`
                : `상대가 앙코르를 보유하고 있어 변화기 반복에 묶일 위험을 ${Math.round(encoreRisk * 100)}%로 평가했습니다.`,
          ),
        );
      }
    }
  }
  const hasStatusControlObservation =
    enriched.statusControlTargetStatusMoveCount !== undefined ||
    enriched.encoreTargetValid !== undefined;
  if (
    hasStatusControlObservation &&
    (moveId === "taunt" || moveId === "encore")
  ) {
    const targetAlreadyAffected =
      enriched.statusControlTargetAlreadyAffected === true;
    const canSurviveControlWindow =
      enriched.statusControlCanSurviveThreeTurns === true;
    const controlWindowDamageRatio = Math.max(
      0,
      finiteNumber(enriched.statusControlThreeTurnDamageRatio, 3),
    );
    const opponentCanSwitch =
      enriched.statusControlOpponentCanSwitch === true;
    const switchHazardLayers = Math.max(
      0,
      finiteNumber(enriched.statusControlSwitchHazardLayers, 0),
    );
    if (targetAlreadyAffected) {
      adjustments.push(
        scoreAdjustment(
          `rule.status_control.${moveId}_already_active`,
          "이미 적용된 방해 효과",
          moveId,
          -1000,
          `상대에게 ${moveId === "taunt" ? "도발" : "앙코르"}가 이미 적용되어 있어 다시 사용할 이유가 없습니다.`,
        ),
      );
    } else if (moveId === "taunt") {
      const statusMoveCount = Math.max(
        0,
        finiteNumber(enriched.statusControlTargetStatusMoveCount, 0),
      );
      const statusMoveRatio = Math.max(
        0,
        Math.min(
          1,
          finiteNumber(enriched.statusControlTargetStatusMoveRatio, 0),
        ),
      );
      const targetValue = Math.max(
        0,
        Math.min(1, finiteNumber(enriched.statusControlTargetValue, 0)),
      );
      if (statusMoveCount <= 0) {
        adjustments.push(
          scoreAdjustment(
            "rule.status_control.taunt_no_target",
            "차단할 변화기 없음",
            0,
            -1000,
            "상대가 사용할 수 있는 변화기가 없어 도발은 아무 효과가 없습니다.",
          ),
        );
      } else {
        const preventionConfidence = Math.max(
          0,
          Math.min(
            1,
            finiteNumber(enriched.tauntPreventionConfidence, 0),
          ),
        );
        const controlBonus = Math.round(
          12 +
            statusMoveRatio * 30 +
            targetValue * 34 +
            preventionConfidence * 75,
        );
        adjustments.push(
          scoreAdjustment(
            "rule.status_control.taunt_lock",
            "핵심 변화기 차단",
            `${statusMoveCount} / ${Math.round(statusMoveRatio * 100)}%`,
            controlBonus,
            preventionConfidence > 0
              ? `상대가 이번 턴 사용할 변화기를 최대 ${Math.round(preventionConfidence * 100)}% 확률로 먼저 차단하며, 기술 ${statusMoveCount}개를 봉쇄할 수 있습니다.`
              : `상대 기술 중 변화기 ${statusMoveCount}개의 회복·랭크업·설치 가치를 차단할 수 있어 점수를 높였습니다.`,
          ),
        );
        if (!canSurviveControlWindow && preventionConfidence < 1) {
          const penalty = -Math.min(
            120,
            Math.round(45 + Math.max(0, controlWindowDamageRatio - 1) * 65),
          );
          adjustments.push(
            scoreAdjustment(
              "rule.status_control.taunt_short_life",
              "도발 유지 전 생존 위험",
              controlWindowDamageRatio,
              penalty,
              `도발을 걸어도 공격 기술이나 교체 대응으로 3턴 동안 현재 체력의 약 ${Math.round(controlWindowDamageRatio * 100)}% 피해를 받을 수 있어 가치를 낮췄습니다.`,
            ),
          );
        }
      }
    } else if (enriched.encoreTargetValid !== true) {
      adjustments.push(
        scoreAdjustment(
          "rule.status_control.encore_no_target",
          "고정할 직전 기술 없음",
          enriched.encoreTargetMoveId ?? "",
          -1000,
          "앙코르로 고정할 수 있는 상대의 직전 기술이 없어 사용할 수 없습니다.",
        ),
      );
    } else {
      const targetMoveId = enriched.encoreTargetMoveId ?? "";
      const exactConfidence = Math.max(
        0,
        Math.min(
          1,
          finiteNumber(enriched.encoreExactTargetConfidence, 0),
        ),
      );
      if (enriched.encoreTargetIsStatus === true) {
        const targetValue = Math.max(
          0,
          Math.min(
            1,
            finiteNumber(enriched.encoreTargetStatusValue, 0),
          ),
        );
        const bonus = Math.round(
          38 +
            targetValue * 72 +
            exactConfidence * 70 +
            Math.min(18, switchHazardLayers * 6),
        );
        adjustments.push(
          scoreAdjustment(
            "rule.status_control.encore_status_lock",
            "변화기 반복 고정",
            targetMoveId,
            bonus,
            opponentCanSwitch
              ? `상대를 ${targetMoveId}에 묶어 교체를 강요하고 한 턴의 주도권${switchHazardLayers > 0 ? "과 설치물 피해" : ""}을 얻을 수 있습니다.`
              : `상대를 ${targetMoveId}에 3턴 동안 묶어 안전한 공격이나 전개 기회를 확보할 수 있습니다.`,
          ),
        );
      } else {
        const targetDamageRatio = Math.max(
          0,
          finiteNumber(enriched.encoreTargetDamageRatio, 1),
        );
        const weight =
          targetDamageRatio <= 0.2
            ? 52
            : targetDamageRatio <= 0.35
              ? 24
              : targetDamageRatio >= 0.65
                ? -130
                : targetDamageRatio >= 0.5
                  ? -75
                  : -18;
        adjustments.push(
          scoreAdjustment(
            "rule.status_control.encore_attack_lock",
            targetDamageRatio <= 0.35
              ? "약한 공격에 고정"
              : "위험한 공격에 고정",
            `${targetMoveId} / ${Math.round(targetDamageRatio * 100)}%`,
            weight,
            targetDamageRatio <= 0.35
              ? `상대를 현재 체력의 약 ${Math.round(targetDamageRatio * 100)}%만 깎는 ${targetMoveId}에 묶어 전개 기회를 만들 수 있습니다.`
              : `${targetMoveId}에 묶어도 한 번에 현재 체력의 약 ${Math.round(targetDamageRatio * 100)}% 피해를 받아 앙코르의 가치를 낮췄습니다.`,
          ),
        );
      }
      if (!canSurviveControlWindow) {
        const penalty = -Math.min(
          110,
          Math.round(35 + Math.max(0, controlWindowDamageRatio - 1) * 55),
        );
        adjustments.push(
          scoreAdjustment(
            "rule.status_control.encore_short_life",
            "앙코르 이후 생존 위험",
            controlWindowDamageRatio,
            penalty,
            `상대의 교체 대응까지 고려하면 3턴 동안 현재 체력의 약 ${Math.round(controlWindowDamageRatio * 100)}% 피해를 받을 수 있어 후속 이득을 제한했습니다.`,
          ),
        );
      }
    }
  }
  const tier = setupThreatTier(enriched);
  const setupEvaluation =
    enriched.setupThreatEvaluation ??
    enriched.opponentSetupThreatEvaluation ??
    {};
  const sweepRiskAfterSetup = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(
        setupEvaluation.sweepRiskAfterSetup,
        enriched.opponentSetupSweepRisk,
      ) ?? 0,
    ),
  );
  const actsBefore =
    enriched.actsBeforeOpponent === true ||
    Number(enriched.priority ?? 0) > 0 ||
    enriched.speedAdvantage === true;
  const hasSafeImmediateKo = enriched.safeImmediateKoAvailable === true;
  const isDamage = isDamagingCandidate(enriched);
  const knockoutBeforeActionProbability = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(enriched.opponentKnockoutBeforeActionProbability, 0),
    ),
  );
  const oneTurnEvaluation =
    enriched.oneTurnEvaluation ??
    enriched.battleStateEvaluation ??
    null;
  if (oneTurnEvaluation) {
    const delta = finiteNumber(
      oneTurnEvaluation.delta,
      enriched.battleStateValueDelta,
    );
    const weightMultiplier = Math.max(
      0,
      finiteNumber(enriched.oneTurnSearchWeight, 0.35),
    );
    if (delta !== undefined && weightMultiplier > 0) {
      const weight =
        Math.round(delta * weightMultiplier * 100) / 100;
      adjustments.push(
        scoreAdjustment(
          "simulation.one_turn_state_value",
          "1턴 후 전투 상태",
          oneTurnEvaluation.winProbabilityAfter ??
            oneTurnEvaluation.qValue,
          weight,
          oneTurnEvaluation.winProbabilityAfter !== undefined
            ? `현재 승률 ${Math.round(oneTurnEvaluation.winProbabilityBefore * 1_000) / 10}%에서 행동 후 ${Math.round(oneTurnEvaluation.winProbabilityAfter * 1_000) / 10}%로 ${oneTurnEvaluation.winProbabilityDelta >= 0 ? "+" : ""}${Math.round(oneTurnEvaluation.winProbabilityDelta * 1_000) / 10}%p 변할 것으로 추정했습니다.`
            : `이 행동을 적용한 다음 상태의 가치는 ${oneTurnEvaluation.qValue}, 현재 상태 대비 변화는 ${delta >= 0 ? "+" : ""}${delta}로 평가했습니다.`,
        ),
      );
    }
  }

  const stayPressurePenalty = Math.max(
    0,
    finiteNumber(enriched.stayPressurePenalty, 0),
  );
  if (stayPressurePenalty > 0) {
    const pressureSources = [];
    if (finiteNumber(enriched.yawnSwitchPressure, 0) > 0) {
      pressureSources.push(
        Number(enriched.yawnTurns ?? 0) <= 1
          ? "이번 턴 뒤 발동하는 하품"
          : "하품",
      );
    }
    if (finiteNumber(enriched.saltCureSwitchPressure, 0) > 0) {
      pressureSources.push(
        `소금절이 ${finiteNumber(enriched.saltCureResidualDamage, 0)} 피해`,
      );
    }
    if (finiteNumber(enriched.toxicSwitchPressure, 0) > 0) {
      pressureSources.push(
        `맹독 ${Math.max(1, finiteNumber(enriched.toxicCounter, 1))}단계`,
      );
    }
    adjustments.push(
      scoreAdjustment(
        "rule.action.switch_cleared_pressure",
        "교체로 해제 가능한 누적 위험",
        pressureSources,
        -stayPressurePenalty,
        `${pressureSources.join(", ")} 때문에 현재 포켓몬을 계속 두는 행동의 점수를 ${stayPressurePenalty} 낮췄습니다.`,
      ),
    );
  }

  if (isDamage && knockoutBeforeActionProbability >= 0.25) {
    const weight =
      knockoutBeforeActionProbability >= 0.75
        ? -520
        : knockoutBeforeActionProbability >= 0.5
          ? -280
          : -120;
    adjustments.push(
      scoreAdjustment(
        "rule.action.ko_before_acting",
        "행동 전 기절 위험",
        knockoutBeforeActionProbability,
        weight,
        `상대의 ${enriched.opponentThreateningMoveId || "공격"}에 먼저 쓰러져 이 행동을 실행하지 못할 확률이 ${Math.round(knockoutBeforeActionProbability * 100)}%라 점수를 크게 낮췄습니다.`,
      ),
    );
  }

  if (moveId === "upperhand") {
    const exactOutcome = String(enriched.upperHandExactOutcome ?? "");
    const successProbability = Math.max(
      0,
      Math.min(
        1,
        finiteNumber(enriched.upperHandSuccessProbability, 0),
      ),
    );
    const eligibleMoves = Array.isArray(enriched.upperHandEligiblePriorityMoves)
      ? enriched.upperHandEligiblePriorityMoves.filter(Boolean)
      : [];
    if (exactOutcome === "failure") {
      adjustments.push(
        scoreAdjustment(
          "rule.upper_hand.exact_failure",
          "기선제압 실패 확정",
          enriched.exactOpponentMoveId || "non-priority-action",
          -2000,
          "확인한 상대 행동이 공격 선공기가 아니거나 기선제압보다 먼저 행동하므로 이 기술은 실패합니다.",
        ),
      );
    } else if (exactOutcome === "success") {
      adjustments.push(
        scoreAdjustment(
          "rule.upper_hand.exact_success",
          "기선제압 성공 확정",
          enriched.exactOpponentMoveId || eligibleMoves[0] || "priority-move",
          90,
          "상대가 공격 선공기를 확정했고 기선제압이 먼저 발동하므로 피해와 풀죽음 가치를 높게 반영했습니다.",
        ),
      );
    } else if (successProbability <= 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.upper_hand.no_valid_target",
          "기선제압 대상 없음",
          false,
          -1200,
          "현재 확인된 상대 기술 중 기선제압보다 늦게 발동하는 공격 선공기가 없어 실패 가능성이 확정적입니다.",
        ),
      );
    } else {
      const probabilityPercent = Math.round(successProbability * 100);
      const weight =
        Math.round(
          (-140 * (1 - successProbability) + 70 * successProbability) * 100,
        ) / 100;
      adjustments.push(
        scoreAdjustment(
          "rule.upper_hand.predicted_priority",
          "기선제압 선공기 예측",
          `${probabilityPercent}%`,
          weight,
          `상대의 공격 선공기 후보 ${eligibleMoves.join(", ")}와 피해 압박을 기준으로 성공 확률을 ${probabilityPercent}%로 추정했습니다.`,
        ),
      );
    }
  }

  if (
    ["suckerpunch", "thunderclap"].includes(moveId) &&
    enriched.conditionalPriorityRepeatFailure === true
  ) {
    const repeatedStatusMove =
      enriched.conditionalPriorityRepeatCause === "status_move";
    const failureStreak = Math.max(
      1,
      finiteNumber(enriched.conditionalPriorityFailureStreak, 1),
    );
    const adaptChance = Math.round(
      Math.max(
        0,
        Math.min(
          1,
          finiteNumber(enriched.conditionalPriorityAdaptChance, 1),
        ),
      ) * 100,
    );
    adjustments.push(
      scoreAdjustment(
        "rule.conditional_priority.repeat_failure",
        enriched.conditionalPriorityAdapted === true
          ? "조건부 선공기 패턴 경계"
          : "조건부 선공기 재시도",
        `${failureStreak}회 / ${adaptChance}%`,
        finiteNumber(enriched.conditionalPriorityAdaptPenalty, -2000),
        repeatedStatusMove
          ? `변화기 ${enriched.opponentLastMoveId || ""} 때문에 ${failureStreak}회 연속 실패한 패턴을 ${adaptChance}% 확률로 경계합니다.`
          : `${enriched.opponentLastMoveId || "상대 선공기"} 때문에 ${failureStreak}회 연속 실패한 패턴을 ${adaptChance}% 확률로 경계합니다.`,
      ),
    );
  }

  if (enriched.selfBoostAlreadyMaxed === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.setup.all_boosts_maxed",
        "상승 랭크 최대",
        enriched.effectiveSelfBoostTotal ?? 0,
        -1000,
        "이 기술로 올릴 수 있는 능력치가 모두 최대 랭크라 반복 사용 가치를 제거했습니다.",
      ),
    );
  }

  if (hasSafeImmediateKo && !isSafeFinisher(enriched)) {
    const livingOpponents = Math.max(0, Number(enriched.livingOpponents ?? 2));
    const highValueHazard =
      tags.has("hazardset") &&
      moveId === "stealthrock" &&
      livingOpponents >= 3 &&
      Number(enriched.opponentHazards?.stealthrock ?? 0) <= 0;
    const weight = highValueHazard ? -12 : isDamage ? -10 : -80;
    adjustments.push(
      scoreAdjustment(
        isDamage
          ? "rule.immediate_ko_attack_preference"
          : "rule.immediate_ko_dominance",
        isDamage ? "비마무리 공격 억제" : "즉시 KO 우선",
        true,
        weight,
        isDamage
          ? "위험이 통제된 확정 KO 공격기가 있어 상대를 못 쓰러뜨리는 공격을 낮게 봤습니다."
          : "안전한 즉시 KO가 있어 교체/회복/변화기보다 마무리를 우선합니다.",
      ),
    );
  }

  if (enriched.koChance === "guaranteed" && actsBefore && tier >= 2) {
    adjustments.push(
      scoreAdjustment(
        "rule.immediate_ko_response",
        "전개 위협 즉시 KO",
        tier,
        4,
        Number(enriched.priority ?? 0) > 0
          ? "우선도기로 상대 랭크업 위협을 확정 KO할 수 있어 보너스를 반영했습니다."
          : "선공 확정 KO로 상대 랭크업 위협을 끊을 수 있어 보너스를 반영했습니다.",
      ),
    );
  }

  const selfDropTotal = finiteNumber(
    enriched.selfDropTotal,
    negativeBoostTotal(enriched.selfBoosts ?? enriched.selfBoostStages),
  );
  if (selfDropTotal > 0) {
    const safeNoDropKoAvailable =
      enriched.safeNoDropKoAvailable === true ||
      enriched.safeNoDropFinisherAvailable === true;
    const guaranteedKo = enriched.koChance === "guaranteed";
    const weight =
      guaranteedKo && safeNoDropKoAvailable
        ? -95 - selfDropTotal * 8
        : -Math.min(30, selfDropTotal * 6);
    adjustments.push(
      scoreAdjustment(
        guaranteedKo && safeNoDropKoAvailable
          ? "rule.self_drop.safe_ko_alternative"
          : "rule.self_drop.stat_cost",
        "자기 능력 하락",
        selfDropTotal,
        weight,
        guaranteedKo && safeNoDropKoAvailable
          ? "같은 확정 KO를 낼 수 있는 무하락 공격기가 있어, 방어 자원을 깎는 마무리 선택을 크게 낮췄습니다."
          : "공격 후 자신의 능력치가 떨어지는 비용을 반영했습니다.",
      ),
    );
  }

  const expectedRecoilDamage = finiteNumber(enriched.expectedRecoilDamage, 0);
  if (expectedRecoilDamage > 0) {
    const recoilWouldFaint = enriched.recoilWouldFaint === true;
    const safeAlternative = enriched.safeNoRecoilKoAvailable === true;
    const weight =
      recoilWouldFaint && safeAlternative
        ? -140
        : recoilWouldFaint
          ? -12
          : -Math.min(36, Math.max(4, expectedRecoilDamage * 0.35));
    adjustments.push(
      scoreAdjustment(
        recoilWouldFaint && safeAlternative
          ? "rule.recoil.safe_ko_alternative"
          : recoilWouldFaint
            ? "rule.recoil.necessary_trade"
            : "rule.recoil.hp_cost",
        recoilWouldFaint ? "반동 기절 위험" : "반동 피해",
        expectedRecoilDamage,
        weight,
        recoilWouldFaint && safeAlternative
          ? "명중률이 충분한 무반동 마무리기가 있어, 자신까지 쓰러지는 공격의 점수를 크게 낮췄습니다."
          : recoilWouldFaint
            ? "반동으로 쓰러지지만 신뢰할 만한 무반동 마무리기가 없어 필요한 교환으로 평가했습니다."
            : "공격 후 받는 예상 반동 피해를 생존 비용으로 반영했습니다.",
      ),
    );
  }

  const hazardMaximumLayers = HAZARD_MAX_LAYERS[moveId];
  if (hazardMaximumLayers !== undefined && tags.has("hazardset")) {
    const currentLayers = Math.max(
      0,
      Number(
        enriched.existingHazardLayers ??
          enriched.opponentHazards?.[moveId] ??
          enriched.field?.opponentHazards?.[moveId] ??
          0,
      ),
    );
    if (cleanId(enriched.opponentAbility) === "magicbounce") {
      adjustments.push(
        scoreAdjustment(
          "rule.entry_hazard.magic_bounce",
          "설치기 반사 위험",
          "magicbounce",
          -30,
          "상대 매직미러에 설치기가 반사될 수 있어 큰 페널티를 반영했습니다.",
        ),
      );
    } else if (currentLayers >= hazardMaximumLayers) {
      adjustments.push(
        scoreAdjustment(
          "rule.entry_hazard.already_maxed",
          "이미 설치됨",
          currentLayers,
          -180,
          "이미 최대 층수까지 설치되어 다시 사용해도 실패하므로 점수를 크게 낮췄습니다.",
        ),
      );
    } else {
      const incomingRatio = ratioValue(
        enriched.opponentMaxDamageToCurrentHealthRatio,
        enriched.incomingDamageRatio,
      );
      const hpPercent = ratioValue(enriched.hpPercent, 1);
      const livingOpponents = Math.max(0, Number(enriched.livingOpponents ?? 2));
      const highValueHazardDespiteKo =
        enriched.immediateKoAvailable === true &&
        moveId === "stealthrock" &&
        livingOpponents >= 3;
      if (
        (!enriched.immediateKoAvailable || highValueHazardDespiteKo) &&
        (actsBefore || incomingRatio === undefined || incomingRatio < hpPercent)
      ) {
        if (livingOpponents > 1) {
          const bonus = 12 + 2 * Math.min(6, livingOpponents);
          adjustments.push(
            scoreAdjustment(
              "rule.entry_hazard.team_value",
              "판 설치 지속 가치",
              livingOpponents,
              bonus,
              `남은 상대 ${livingOpponents}마리에 진입 압박을 줄 수 있어 설치기 가치를 반영했습니다.`,
            ),
          );
        }
        if (moveId === "stealthrock" && livingOpponents >= 3) {
          const turn = Math.max(1, Number(enriched.turn ?? 1));
          const remainingValue = Math.min(6, livingOpponents) * 8;
          const earlyTempoValue = turn <= 2 && livingOpponents >= 4 ? 10 : 0;
          const stealthRockBonus = 18 + remainingValue + earlyTempoValue;
          adjustments.push(
            scoreAdjustment(
              "rule.entry_hazard.stealth_rock_pressure",
              "스텔스록 지속 압박",
              livingOpponents,
              stealthRockBonus,
              turn <= 2
                ? "초반 스텔스록은 상대 파티 전체의 교체와 기띠/멀티스케일 자원을 계속 압박합니다."
                : "아직 스텔스록이 없어 남은 상대 포켓몬들의 진입 피해 기대값을 계속 높게 봤습니다.",
            ),
          );
        }
        if (
          moveId === "stealthrock" &&
          Number(enriched.turn ?? 1) <= 2 &&
          livingOpponents >= 4
        ) {
          adjustments.push(
            scoreAdjustment(
              "rule.entry_hazard.early_stealth_rock",
              "초반 스텔스록",
              livingOpponents,
              42,
              "초반 스텔스록은 남은 파티 전체의 교체와 기띠/멀티스케일 자원을 압박하므로 크게 높게 봤습니다.",
            ),
          );
        }
      } else {
        adjustments.push(
          scoreAdjustment(
            "rule.entry_hazard.cannot_set",
            "설치 전 KO 위험",
            incomingRatio,
            -30,
            "후공이며 상대 공격을 버티지 못할 위험이 있어 설치기 가치를 낮췄습니다.",
          ),
        );
      }
    }
  }

  if (moveId === "saltcure") {
    const opponentVolatiles = new Set(
      Object.keys(enriched.opponentVolatiles ?? {}).map(cleanId),
    );
    if (opponentVolatiles.has("saltcure")) {
      adjustments.push(
        scoreAdjustment(
          "rule.salt_cure.already_active",
          "소금절이 중복",
          true,
          -45,
          "상대가 이미 소금절이 상태라 재사용 가치를 낮췄습니다.",
        ),
      );
    } else if (!enriched.immediateKoAvailable) {
      const residualDamage = finiteNumber(
        enriched.saltCureResidualDamage,
        ratioValue(enriched.opponentMaxHp, enriched.opponentHp, 0) / 8,
      );
      const survivalTurns = Math.max(
        1,
        Math.min(
          6,
          ratioValue(
            enriched.expectedSurvivalTurns,
            enriched.survivalTurns,
            enriched.turnsCanSurvive,
            1,
          ),
        ),
      );
      const opponentHp = finiteNumber(enriched.opponentHp, enriched.opponentMaxHp);
      const opponentPressureTurns =
        residualDamage > 0 && opponentHp > 0
          ? Math.max(1, Math.min(6, Math.ceil(opponentHp / residualDamage)))
          : survivalTurns;
      const pressureTurns = Math.max(survivalTurns, opponentPressureTurns);
      const stealthRockLayers = Number(enriched.opponentHazards?.stealthrock ?? 0);
      const livingOpponents = Math.max(0, Number(enriched.livingOpponents ?? 2));
      const earlyRockStillPreferred =
        stealthRockLayers <= 0 &&
        Number(enriched.turn ?? 1) <= 2 &&
        livingOpponents >= 4;
      const opponentIsAce =
        enriched.opponentAceQualified === true ||
        Number(enriched.opponentAceScore ?? 0) >= 5.8 ||
        enriched.opponentIsAce === true;
      const opponentPositiveBoosts = Math.max(
        0,
        Number(enriched.opponentPositiveBoosts ?? 0),
        positiveBoostTotal(enriched.opponentBoosts),
        positiveBoostTotal(enriched.targetBoosts),
      );
      const opponentSetupThreat = setupThreatTier(enriched);
      const setupLikelihood = Math.max(
        0,
        Math.min(
          1,
          ratioValue(
            enriched.opponentSetupFirstTurnLikelihood,
            enriched.opponentSetupLikelihood,
            0,
          ),
        ),
      );
      const likelyFirstTurnSetup =
        enriched.opponentLikelyFirstTurnSetup === true ||
        (Number(enriched.turn ?? 1) <= 2 &&
          Number(enriched.opponentSetupMoveCount ?? 0) > 0 &&
          setupLikelihood >= 0.65);
      const currentIncoming = ratioValue(
        enriched.currentIncomingDamageRatio,
        enriched.opponentMaxDamageToCurrentHealthRatio,
        enriched.incomingDamageRatio,
      );
      const urgentPersistentPressure =
        opponentIsAce ||
        likelyFirstTurnSetup ||
        opponentSetupThreat >= 3 ||
        opponentPositiveBoosts >= 2 ||
        enriched.opponentCanSweep === true ||
        enriched.oneMoreTurnUnmanageable === true;
      const dotValue = Math.min(
        urgentPersistentPressure ? 185 : 135,
        Math.round(Math.max(0, residualDamage) * pressureTurns * 0.68 * 100) / 100,
      );
      const pressureBonus =
        (opponentIsAce ? 24 : 0) +
        (likelyFirstTurnSetup
          ? 58
          : opponentSetupThreat >= 3
            ? 36
            : opponentSetupThreat >= 2
              ? 18
              : 0) +
        Math.min(36, opponentPositiveBoosts * 12) +
        (currentIncoming !== undefined && currentIncoming >= 0.5 ? 20 : 0);
      const weight = earlyRockStillPreferred && !urgentPersistentPressure
        ? Math.min(34, 22 + dotValue)
        : 22 + dotValue + pressureBonus;
      adjustments.push(
        scoreAdjustment(
          "rule.salt_cure.persistent_pressure",
          "소금절이 지속 압박",
          `${Math.round(residualDamage)} x ${pressureTurns}`,
          Math.round(weight * 100) / 100,
          earlyRockStillPreferred
            ? "소금절이는 지속 피해 가치가 크지만 초반 스텔스록이 아직 없어 보너스를 보수적으로 제한했습니다."
            : `소금절이는 예상 생존 ${survivalTurns}턴 동안 누적 피해를 만들 수 있어 도트 기대값을 반영했습니다.`,
        ),
      );
    }
  }

  const opponentStatus = cleanId(enriched.opponentStatus ?? enriched.targetStatus);
  const residualStatuses = [];
  const pushResidualStatus = (status, chance = 100) => {
    const id = cleanId(status);
    if (!["tox", "toxic", "badlypoisoned", "psn", "poison", "brn", "burn"].includes(id)) return;
    residualStatuses.push({
      status: id,
      chance: Math.max(0, Math.min(100, Number(chance ?? 100))),
    });
  };
  if (Array.isArray(enriched.statusResidualCandidates)) {
    for (const entry of enriched.statusResidualCandidates) {
      pushResidualStatus(entry.status, entry.chance ?? 100);
    }
  } else {
    pushResidualStatus(enriched.status, 100);
    for (const secondary of enriched.secondaries ?? []) {
      pushResidualStatus(secondary.status, secondary.chance ?? 100);
    }
  }
  if (
    residualStatuses.length > 0 &&
    !opponentStatus &&
    enriched.statusBlocked !== true
  ) {
    const opponentMaxHp = ratioValue(enriched.opponentMaxHp, enriched.opponentHp, 0);
    const survivalTurns = Math.max(
      1,
      Math.min(
        6,
        ratioValue(
          enriched.expectedSurvivalTurns,
          enriched.survivalTurns,
          enriched.turnsCanSurvive,
          1,
        ),
      ),
    );
    const best = residualStatuses.reduce(
      (bestEntry, entry) => {
        const statusId = cleanId(entry.status);
        const chance = entry.chance / 100;
        const toxic =
          statusId === "tox" ||
          statusId === "toxic" ||
          statusId === "badlypoisoned";
        const poison = statusId === "psn" || statusId === "poison";
        const burn = statusId === "brn" || statusId === "burn";
        const residual =
          toxic
            ? (opponentMaxHp / 16) * ((survivalTurns * (survivalTurns + 1)) / 2)
            : poison
              ? (opponentMaxHp / 8) * survivalTurns
              : burn
                ? (opponentMaxHp / 16) * survivalTurns
                : 0;
        const utility = burn ? 10 * survivalTurns : toxic ? 4 * survivalTurns : 0;
        const value = Math.min(95, (residual * 0.5 + utility) * chance);
        return value > bestEntry.value
          ? { status: statusId, chance: entry.chance, value }
          : bestEntry;
      },
      { status: "", chance: 0, value: 0 },
    );
    if (best.value > 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.status_residual.expected_value",
          "상태이상 지속 기대값",
          `${best.status} ${best.chance}% x ${survivalTurns}`,
          Math.round(best.value * 100) / 100,
          `독/맹독/화상은 예상 생존 ${survivalTurns}턴 동안 누적 피해를 만들 수 있어 성공 확률을 곱해 반영했습니다.`,
        ),
      );
    }
  }

  if (moveId === "trickroom") {
    const activeRoom =
      enriched.trickRoomActive === true ||
      Boolean(enriched.field?.pseudoWeather?.trickroom);
    const canSurviveToSetRoom =
      enriched.canSurviveToSetRoom ??
      ratioValue(enriched.incomingDamageRatio, 0) < 1;
    const slowAceCount = Math.max(0, Number(enriched.slowAceCount ?? 0));
    const advantage = Number(enriched.trickRoomAdvantage ?? 0);
    const hpPercent = ratioValue(enriched.hpPercent, enriched.healthRatio, 1);
    if (activeRoom && !enriched.shouldReverseTrickRoom) {
      adjustments.push(
        scoreAdjustment(
          "rule.trick_room.already_active",
          "트릭룸 유지",
          true,
          -160,
          "트릭룸이 이미 켜져 있어 다시 사용하면 이득을 잃을 수 있으므로 크게 낮췄습니다.",
        ),
      );
    } else if (!canSurviveToSetRoom) {
      adjustments.push(
        scoreAdjustment(
          "rule.trick_room.cannot_survive",
          "설치 전 KO 위험",
          false,
          -90,
          "트릭룸은 우선도가 낮아 이번 턴 공격을 버티지 못하면 설치할 수 없어 점수를 낮췄습니다.",
        ),
      );
    } else if (advantage > 0 || slowAceCount > 0) {
      const speedAdvantageBonus = Math.max(0, Math.min(60, advantage * 22));
      const bonus =
        55 +
        speedAdvantageBonus +
        Math.min(48, slowAceCount * 18) +
        (enriched.activeIsSlower === true ? 18 : 0) +
        (hpPercent <= 0.45 ? 18 : 0);
      adjustments.push(
        scoreAdjustment(
          "rule.trick_room.slow_ace_plan",
          "느린 에이스 전개",
          slowAceCount,
          bonus,
          `남은 느린 에이스 ${slowAceCount}마리가 트릭룸에서 선공권을 얻을 수 있어 전개 가치를 반영했습니다.`,
        ),
      );
    } else if (enriched.activeIsFaster === true || advantage < 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.trick_room.bad_speed_context",
          "속도 역전 손해",
          advantage,
          -70,
          "현재 속도 구조에서는 트릭룸이 상대에게 더 유리할 수 있어 점수를 낮췄습니다.",
        ),
      );
    }
  }

  const batonSetupGain = Math.max(
    0,
    finiteNumber(enriched.batonPassAdditionalBoostTotal, 0),
  );
  const batonSetupSurvivalProbability = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(enriched.setupFollowupSurvivalProbability, 0),
    ),
  );
  if (
    moveId !== "batonpass" &&
    enriched.batonPassTargetAvailable === true &&
    batonSetupGain > 0 &&
    batonSetupSurvivalProbability >= 0.65
  ) {
    const batonSetupWeight = Math.min(
      180,
      70 +
        batonSetupGain * 24 +
        Math.max(0, finiteNumber(enriched.batonPassNewKoTargets, 0)) * 42 +
        Math.max(0, finiteNumber(enriched.batonPassPressureGain, 0)) * 28,
    );
    adjustments.push(
      scoreAdjustment(
        "rule.baton_pass.setup_for_ace",
        "에이스 전달용 랭크업",
        enriched.batonPassTargetName,
        Math.round(batonSetupWeight * 100) / 100,
        `${enriched.batonPassTargetName}에게 배턴터치할 수 있고 다음 행동까지 생존할 확률이 ${Math.round(batonSetupSurvivalProbability * 100)}%라, 안전한 범위에서 랭크를 더 쌓는 가치를 반영했습니다.`,
      ),
    );
  }

  const batonPassCurrentSweepBoostTotal = finiteNumber(
    enriched.batonPassCurrentSweepBoostTotal,
    finiteNumber(enriched.batonPassCurrentBoostTotal, 0),
  );
  const batonPassCurrentDefensiveBoostTotal = finiteNumber(
    enriched.batonPassCurrentDefensiveBoostTotal,
    0,
  );
  const safeForAnotherBatonSetup =
    batonSetupSurvivalProbability >= 0.85 &&
    finiteNumber(enriched.incomingDamageRatio, 1) <= 0.35 &&
    finiteNumber(enriched.opponentKnockoutBeforeActionProbability, 0) < 0.2;
  const batonPassDevelopmentRemaining =
    (batonPassCurrentSweepBoostTotal < 6 &&
      enriched.batonPassCanRaiseSweepFurther === true) ||
    (batonPassCurrentDefensiveBoostTotal < 2 &&
      enriched.batonPassCanRaiseDefenseFurther === true);
  const batonPassReady =
    enriched.batonPassTargetAvailable === true &&
    enriched.batonPassTargetAce === true &&
    batonPassCurrentSweepBoostTotal >= 3 &&
    finiteNumber(enriched.batonPassNewKoTargets, 0) >= 2 &&
    (!safeForAnotherBatonSetup ||
      (batonPassCurrentSweepBoostTotal >= 6 &&
        batonPassCurrentDefensiveBoostTotal >= 2));
  if (
    moveId !== "batonpass" &&
    batonPassReady &&
    (batonSetupGain > 0 ||
      tags.has("setupboost") ||
      finiteNumber(enriched.effectiveSelfBoostTotal, 0) > 0)
  ) {
    adjustments.push(
      scoreAdjustment(
        "rule.baton_pass.ready_to_transfer",
        "에이스 전달 준비 완료",
        enriched.batonPassTargetName,
        -220,
        safeForAnotherBatonSetup
          ? `핵심 공격 랭크 ${batonPassCurrentSweepBoostTotal}와 방어 랭크 ${batonPassCurrentDefensiveBoostTotal}를 확보해, 안전한 추가 전개보다 ${enriched.batonPassTargetName}에게 전달할 가치가 높습니다.`
          : `이미 쌓은 랭크로 ${enriched.batonPassTargetName}이 상대 ${Math.max(2, finiteNumber(enriched.batonPassNewKoTargets, 0))}마리 이상을 압박하며 추가 전개가 안전하지 않아 즉시 전달을 우선합니다.`,
      ),
    );
  }
  if (
    moveId === "batonpass" &&
    safeForAnotherBatonSetup &&
    batonPassDevelopmentRemaining
  ) {
    adjustments.push(
      scoreAdjustment(
        "rule.baton_pass.safe_development_remaining",
        "안전한 추가 전개 가능",
        enriched.batonPassTargetName,
        -90,
        `상대의 예상 최대 피해가 현재 체력의 ${Math.round(finiteNumber(enriched.incomingDamageRatio, 0) * 100)}%에 불과해, ${enriched.batonPassTargetName}에게 넘기기 전에 의미 있는 랭크를 한 번 더 확보할 수 있습니다.`,
      ),
    );
  }

  const protectSuccessProbability = Math.max(
    0,
    Math.min(1, finiteNumber(enriched.protectSuccessProbability, 1)),
  );
  if (
    protectSuccessProbability < 1 &&
    ["protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "burningbulwark", "obstruct", "silktrap", "endure", "maxguard"].includes(moveId)
  ) {
    const penalty = Math.round((1 - protectSuccessProbability) * -21000) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.protect.consecutive_failure_risk",
        "연속 방어 실패 위험",
        protectSuccessProbability,
        penalty,
        `연속 사용 성공률이 ${Math.round(protectSuccessProbability * 100)}%로 낮아져 실패 위험을 반영했습니다.`,
      ),
    );
  }

  if (tags.has("setupboost")) {
    const incomingRatio = ratioValue(
      enriched.opponentMaxDamageToCurrentHealthRatio,
      enriched.incomingDamageRatio,
    );
    const setupIncomingRatio = ratioValue(
      enriched.setupIncomingDamageRatioAfterBoost,
      incomingRatio,
    );
    const setupFollowupSurvivalProbability = ratioValue(
      enriched.setupFollowupSurvivalProbability,
      enriched.setupCanSurviveIncoming === false ? 0 : undefined,
      1,
    );
    const canSurviveSetupTurn =
      enriched.setupCanSurviveIncoming !== false &&
      setupFollowupSurvivalProbability >= 0.5;
    const setupSafetyAssured =
      canSurviveSetupTurn &&
      finiteNumber(enriched.setupGuardConsumptionProbability, 0) < 0.25 &&
      (setupIncomingRatio === undefined || setupIncomingRatio < 0.5);
    const conditionalPriorityLikelihood = Math.max(
      0,
      Math.min(
        0.85,
        finiteNumber(enriched.opponentConditionalPriorityLikelihood, 0),
      ),
    );
    const conditionalPriorityKnockoutProbability = Math.max(
      0,
      Math.min(
        1,
        finiteNumber(
          enriched.opponentConditionalPriorityKnockoutProbability,
          0,
        ),
      ),
    );
    const effectiveBoostAvailable =
      finiteNumber(
        enriched.effectiveSelfBoostTotal,
        enriched.setupEffectiveBoostTotal,
        1,
      ) > 0;
    const revealedSetupResetMoveIds = arrayValues(
      enriched.opponentRevealedSetupResetMoveIds,
    ).map(cleanId).filter(Boolean);
    const activeRevealedSetupResetMoveIds = arrayValues(
      enriched.opponentActiveRevealedSetupResetMoveIds,
    ).map(cleanId).filter(Boolean);
    if (effectiveBoostAvailable && revealedSetupResetMoveIds.length > 0) {
      const activeResetAvailable = activeRevealedSetupResetMoveIds.length > 0;
      const penalty = activeResetAvailable ? -240 : -100;
      const resetMoves = activeResetAvailable
        ? activeRevealedSetupResetMoveIds
        : revealedSetupResetMoveIds;
      adjustments.push(
        scoreAdjustment(
          "rule.setup.revealed_boost_reset",
          "공개된 랭크 초기화 대응",
          resetMoves,
          penalty,
          `상대가 이미 ${resetMoves.join(", ")} 사용을 공개했고${activeResetAvailable ? " 현재 포켓몬이 다시 사용할 수 있어" : " 교체로 다시 대응할 수 있어"} 랭크업 투자 가치가 낮습니다.`,
        ),
      );
    }
    if (
      conditionalPriorityLikelihood >= 0.25 &&
      effectiveBoostAvailable &&
      canSurviveSetupTurn
    ) {
      const baitValue =
        conditionalPriorityLikelihood *
        (42 + conditionalPriorityKnockoutProbability * 38);
      adjustments.push(
        scoreAdjustment(
          "rule.setup.conditional_priority_bait",
          "조건부 선공기 낭비 유도",
          `${enriched.opponentConditionalPriorityMoveId || "Sucker Punch"} ${Math.round(conditionalPriorityLikelihood * 100)}%`,
          Math.round(baitValue * 100) / 100,
          `상대가 ${enriched.opponentConditionalPriorityMoveId || "조건부 선공기"}를 선택할 가능성을 ${Math.round(conditionalPriorityLikelihood * 100)}%로 추정했습니다. 변화기를 쓰면 그 공격은 실패하지만 다른 공격 가능성도 남겨 둔 기대값만 반영했습니다.`,
        ),
      );
    }
    if (enriched.reliableKoAlternative === true && !setupSafetyAssured) {
      const knockoutBoostAlternative =
        enriched.knockoutBoostAlternative &&
        typeof enriched.knockoutBoostAlternative === "object";
      adjustments.push(
        scoreAdjustment(
          knockoutBoostAlternative
            ? "rule.setup.foregoes_ko_boost"
            : "rule.setup.foregoes_safe_ko",
          knockoutBoostAlternative
            ? "확정 KO와 특성 랭크업 포기"
            : "안전한 확정 KO 포기",
          setupIncomingRatio,
          knockoutBoostAlternative ? -260 : -180,
          knockoutBoostAlternative
            ? "현재 상대를 확정 KO하면 위협을 제거하면서 특성으로 랭크도 오르므로, 생존 자원을 소모하는 랭크업보다 우선합니다."
            : "현재 상대를 확정 KO할 수 있지만 랭크업 중 큰 피해를 받을 수 있어 안전한 마무리를 우선합니다.",
        ),
      );
    }
    if (Number(enriched.turn ?? 2) === 1 && !enriched.opponentActionKnown) {
      adjustments.push(
        scoreAdjustment(
          "rule.setup.first_turn_unknown",
          "첫 턴 정보 부족",
          true,
          -2,
          "첫 턴에는 상대 행동을 확인하기 전이라 랭크업 가치를 조금 낮췄습니다.",
        ),
      );
    }
    if (!canSurviveSetupTurn) {
      const survivalPenalty =
        setupFollowupSurvivalProbability <= 0.05
          ? -360
          : setupFollowupSurvivalProbability < 0.25
            ? -280
            : -210;
      adjustments.push(
        scoreAdjustment(
          "rule.setup.cannot_reach_followup",
          "랭크업 후속 행동 불가",
          setupFollowupSurvivalProbability,
          survivalPenalty,
          finiteNumber(enriched.setupGuardConsumptionProbability, 0) > 0 &&
            enriched.setupFollowupActsBeforeThreat === false
            ? `기합의띠나 옹골참으로 이번 턴을 버텨도 다음 턴 상대보다 늦게 행동하므로 강화 공격을 사용하기 전에 쓰러집니다.`
            : `랭크업 후 다음 행동까지 생존할 확률이 ${Math.round(setupFollowupSurvivalProbability * 100)}%라 전개 투자를 회수하기 어렵습니다.`,
        ),
      );
      adjustments.push(
        scoreAdjustment(
          "rule.setup.cannot_survive_turn",
          "랭크업 후 기절 위험",
          setupIncomingRatio,
          -220,
          `랭크업을 적용해도 상대 최대 피해가 현재 체력의 ${Math.round((setupIncomingRatio ?? 1) * 100)}%라 다음 공격 기회를 얻을 수 없습니다.`,
        ),
      );
    } else if (incomingRatio !== undefined) {
      const bonus =
        incomingRatio <= 0.1
          ? 18
          : incomingRatio <= 0.2
            ? 16
            : incomingRatio <= 1 / 3
              ? 10
              : incomingRatio >= 1
                ? -20
                : incomingRatio >= 0.5
                  ? -10
                  : 0;
      if (bonus !== 0) {
        adjustments.push(
          scoreAdjustment(
            bonus > 0 ? "rule.setup.safe_turn" : "rule.setup.damage_risk",
            bonus > 0 ? "랭크업 기점" : "랭크업 피해 위험",
            incomingRatio,
            bonus,
            bonus > 0
              ? `상대 최대 피해가 현재 체력의 ${Math.round(incomingRatio * 100)}%라 랭크업 기점으로 평가했습니다.`
              : `상대 최대 피해가 현재 체력의 ${Math.round(incomingRatio * 100)}%라 랭크업 위험을 반영했습니다.`,
          ),
        );
      }
    }
    const currentBestDamage = finiteNumber(enriched.setupCurrentBestDamage, 0);
    const boostedBestDamage = finiteNumber(enriched.setupBoostedBestDamage, 0);
    const damageImprovement = finiteNumber(
      enriched.setupDamageImprovement,
      Math.max(0, boostedBestDamage - currentBestDamage),
    );
    const effectiveBoostTotal = Math.max(
      0,
      finiteNumber(enriched.setupEffectiveBoostTotal, 1),
    );
    const newKoTargets = Math.max(0, finiteNumber(enriched.setupNewKoTargets, 0));
    const futureNewKoTargets = Math.max(
      0,
      finiteNumber(enriched.setupFutureNewKoTargets, 0),
    );
    const newSpeedAdvantages = Math.max(
      0,
      finiteNumber(enriched.setupNewSpeedAdvantages, 0),
    );
    const futurePressureGain = Math.max(
      0,
      finiteNumber(enriched.setupFuturePressureGain, 0),
    );
    const currentPressureGain = Math.max(
      0,
      finiteNumber(enriched.setupCurrentPressureGain, 0),
    );
    const hasTeamSetupProfile =
      enriched.setupLivingTargetCount !== undefined ||
      enriched.setupEffectiveBoostTotal !== undefined;
    const strategicGain =
      newKoTargets +
      newSpeedAdvantages * 0.65 +
      futurePressureGain +
      currentPressureGain * 0.6;
    const opponentHp = finiteNumber(enriched.opponentHp);
    if (
      hasTeamSetupProfile &&
      (enriched.setupBoostAlreadyMaxed === true || effectiveBoostTotal <= 0)
    ) {
      adjustments.push(
        scoreAdjustment(
          "rule.setup.boost_already_maxed",
          "랭크 상승 한계",
          effectiveBoostTotal,
          -260,
          "현재 랭크에서는 이 기술로 더 오르는 공격·특수공격·스피드가 없어 재사용 가치를 제거했습니다.",
        ),
      );
    } else if (hasTeamSetupProfile && strategicGain <= 0.01) {
      adjustments.push(
        scoreAdjustment(
          "rule.setup.no_matchup_gain",
          "추가 전개 실익 없음",
          strategicGain,
          -190,
          "이번 랭크업으로 현재 상대나 남은 엔트리에서 새 KO권, 피해 압박, 속도 우위를 만들지 못합니다.",
        ),
      );
    } else if (
      canSurviveSetupTurn &&
      (hasTeamSetupProfile ? strategicGain > 0.01 : damageImprovement > 0)
    ) {
      const safeEnough = incomingRatio === undefined || incomingRatio < 0.5;
      const turnsKoImproved =
        enriched.setupKoAfterBoost === true &&
        enriched.setupKoBeforeBoost !== true;
      const weight = hasTeamSetupProfile
        ? Math.min(120, damageImprovement * 0.55) +
          Math.min(150, newKoTargets * 55 + futureNewKoTargets * 20) +
          Math.min(70, futurePressureGain * 45) +
          Math.min(50, newSpeedAdvantages * 25) +
          (turnsKoImproved ? (safeEnough ? 55 : 25) : 0) +
          (opponentHp && boostedBestDamage >= opponentHp * 0.75 ? 30 : 0)
        : Math.min(120, damageImprovement * 0.55) +
          (turnsKoImproved ? (safeEnough ? 245 : 105) : 0) +
          (opponentHp && boostedBestDamage >= opponentHp * 0.75 ? 30 : 0);
      adjustments.push(
        scoreAdjustment(
          "rule.setup.team_sweep_plan",
          "팀 단위 전개 가치",
          Math.round(strategicGain * 100) / 100,
          Math.round(weight * 100) / 100,
          `랭크업 후 새 KO권 ${newKoTargets}마리, 뒤쪽 엔트리 압박 증가 ${Math.round(futurePressureGain * 100)}%, 새 속도 우위 ${newSpeedAdvantages}개를 만들 수 있습니다.`,
        ),
      );
    }
  }

  if (moveId === "batonpass") {
    const currentBoostTotal = Math.max(
      0,
      finiteNumber(
        enriched.batonPassCurrentBoostTotal,
        finiteNumber(enriched.batonPassBoostTotal, 0),
      ),
    );
    const canReachPass =
      finiteNumber(enriched.opponentKnockoutBeforeActionProbability, 0) < 0.75;
    if (
      enriched.batonPassTargetAvailable !== true ||
      enriched.batonPassTargetAce !== true
    ) {
      adjustments.push(
        scoreAdjustment(
          "rule.baton_pass.no_ace_target",
          "전달 대상 없음",
          false,
          -180,
          "살아 있는 에이스 전달 대상이 없어 배턴터치 가치를 크게 낮췄습니다.",
        ),
      );
    } else if (currentBoostTotal <= 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.baton_pass.no_boosts",
          "전달할 랭크 없음",
          0,
          -150,
          "현재 전달할 유효한 공격·특수공격·스피드 랭크가 없어 배턴터치를 보류합니다.",
        ),
      );
    } else if (!canReachPass) {
      adjustments.push(
        scoreAdjustment(
          "rule.baton_pass.ko_before_pass",
          "전달 전 기절 위험",
          enriched.opponentKnockoutBeforeActionProbability,
          -420,
          "배턴터치를 사용하기 전에 쓰러질 가능성이 높아 전개를 성공시킬 수 없습니다.",
        ),
      );
    } else {
      const followupSurvival = Math.max(
        0,
        Math.min(
          1,
          finiteNumber(enriched.setupFollowupSurvivalProbability, 1),
        ),
      );
      const urgentPass =
        followupSurvival < 0.55 ||
        finiteNumber(enriched.incomingDamageRatio, 0) >=
          finiteNumber(enriched.hpPercent, 1);
      const transferWeight = Math.min(
        260,
        55 +
          Math.max(0, finiteNumber(enriched.batonPassTransferValue, 0)) *
            0.75 +
          (urgentPass ? 70 : 0),
      );
      adjustments.push(
        scoreAdjustment(
          urgentPass
            ? "rule.baton_pass.pass_before_faint"
            : "rule.baton_pass.transfer_to_ace",
          urgentPass ? "기절 전 에이스 전달" : "에이스에게 랭크 전달",
          {
            target: enriched.batonPassTargetName,
            boosts: currentBoostTotal,
            newKoTargets: enriched.batonPassNewKoTargets,
          },
          Math.round(transferWeight * 100) / 100,
          urgentPass
            ? `다음 턴까지 전개 포켓몬이 버티기 어려워, 쌓은 ${currentBoostTotal}랭크를 잃기 전에 ${enriched.batonPassTargetName}에게 넘기는 가치를 높였습니다.`
            : `쌓은 ${currentBoostTotal}랭크를 에이스 ${enriched.batonPassTargetName}에게 넘기면 새 KO권 ${Math.max(0, finiteNumber(enriched.batonPassNewKoTargets, 0))}개를 만들 수 있어 전개 가치를 반영했습니다.`,
        ),
      );
    }
  }

  if (RECOVERY_MOVE_IDS.has(moveId) || tags.has("recovery")) {
    const hpPercent = ratioValue(enriched.hpPercent, enriched.healthRatio, enriched.currentHpRatio, 1);
    const incomingRatio = ratioValue(
      enriched.currentIncomingDamageRatio,
      enriched.opponentMaxDamageToCurrentHealthRatio,
      enriched.incomingDamageRatio,
    );
    const projectedHp =
      incomingRatio === undefined || actsBefore ? hpPercent : hpPercent - incomingRatio;
    const setupRiskRecoveryEmergency =
      hpPercent <= 0.45 ||
      (projectedHp > 0 && projectedHp <= 0.25) ||
      (incomingRatio !== undefined && incomingRatio >= hpPercent);
    let weight = 0;
    let message = "";
    if (hpPercent <= 0.35) {
      weight = 24;
      message = `체력 ${Math.round(hpPercent * 100)}%로 즉시 회복 필요성이 큽니다.`;
    } else if (hpPercent <= 0.5) {
      weight = 12;
      message = `체력 ${Math.round(hpPercent * 100)}%라 회복 가치를 반영했습니다.`;
    } else if (projectedHp > 0 && projectedHp <= 0.6) {
      weight = 30;
      message = `예상 피격 후 체력 ${Math.round(projectedHp * 100)}%라 회복 가치를 높였습니다.`;
    } else if (!enriched.currentStatus && !enriched.status) {
      weight = -10;
      message = "체력이 충분해 회복 낭비 가능성을 반영했습니다.";
    }
    if (weight !== 0) {
      adjustments.push(
        scoreAdjustment(
          weight > 0 ? "rule.recovery.survival_value" : "rule.recovery.healthy_penalty",
          weight > 0 ? "회복 생존 가치" : "회복 낭비 억제",
          hpPercent,
          weight,
          message,
        ),
      );
    }
    const recoveryAmount = finiteNumber(enriched.recoveryAmount);
    const recoveryExposureTurns = Math.max(
      1,
      finiteNumber(enriched.recoveryExposureTurns, moveId === "rest" ? 3 : 1),
    );
    const expectedIncomingDamage = finiteNumber(
      enriched.recoveryExpectedIncomingDamage,
    );
    const recoveryNetHpChange = finiteNumber(enriched.recoveryNetHpChange);
    const beforeActionKoRisk = Math.max(
      0,
      Math.min(
        1,
        finiteNumber(
          enriched.recoveryBeforeActionKoRisk,
          finiteNumber(enriched.opponentKnockoutBeforeActionProbability, 0),
        ),
      ),
    );
    if (beforeActionKoRisk >= 0.75) {
      const penalty =
        beforeActionKoRisk >= 0.85
          ? -520
          : -260;
      adjustments.push(
        scoreAdjustment(
          "rule.recovery.ko_before_heal",
          "회복 전 기절 위험",
          beforeActionKoRisk,
          penalty,
          `상대가 먼저 공격해 회복기를 쓰기 전에 쓰러질 확률이 ${Math.round(beforeActionKoRisk * 100)}%라 회복 선택을 크게 낮췄습니다.`,
        ),
      );
    }
    if (
      recoveryAmount !== undefined &&
      expectedIncomingDamage !== undefined &&
      recoveryNetHpChange !== undefined &&
      recoveryNetHpChange < 0
    ) {
      const deficit = Math.abs(recoveryNetHpChange);
      const basePenalty = recoveryExposureTurns >= 3 ? 120 : 80;
      const penalty = -Math.min(
        520,
        basePenalty + deficit * (recoveryExposureTurns >= 3 ? 0.35 : 0.5),
      );
      adjustments.push(
        scoreAdjustment(
          recoveryExposureTurns >= 3
            ? "rule.recovery.sleep_turn_damage"
            : "rule.recovery.negative_exchange",
          recoveryExposureTurns >= 3
            ? "수면 중 누적 피해"
            : "회복보다 큰 피격",
          `${Math.round(recoveryAmount)} / ${Math.round(expectedIncomingDamage)}`,
          Math.round(penalty * 100) / 100,
          recoveryExposureTurns >= 3
            ? `잠자기로 약 ${Math.round(recoveryAmount)} HP를 회복하지만 사용 턴과 수면 2턴 동안 약 ${Math.round(expectedIncomingDamage)} 피해를 받을 수 있어 점수를 크게 낮췄습니다.`
            : `약 ${Math.round(recoveryAmount)} HP를 회복해도 같은 턴에 약 ${Math.round(expectedIncomingDamage)} 피해를 받아 순체력이 감소하므로 점수를 낮췄습니다.`,
        ),
      );
    }
    if (!setupRiskRecoveryEmergency && opponentLikelyToSetup(enriched)) {
      const likelihood = opponentSetupLikelihood(enriched);
      const legacyPenalty =
        hpPercent >= 0.8
          ? 95
          : hpPercent >= 0.65
            ? 75
            : 45;
      const penalty = -Math.max(
        legacyPenalty,
        finiteNumber(setupEvaluation.freeTurnPenalty, 0),
      );
      adjustments.push(
        scoreAdjustment(
          "rule.recovery.free_setup_risk",
          "무료 랭크업 위험",
          `${Math.round(hpPercent * 100)}% / ${Math.round(likelihood * 100)}%`,
          penalty,
          "회복이 급하지 않은 상황에서 회복기를 쓰면 상대 랭크업 기술에 무료 턴을 줄 위험이 커서 크게 감점했습니다.",
        ),
      );
    }
  }

  const setupPunishMove =
    BOOST_RESET_MOVE_IDS.has(moveId) ||
    PHAZE_MOVE_IDS.has(moveId) ||
    TAUNT_MOVE_IDS.has(moveId) ||
    moveId === "encore" ||
    enriched.koChance === "guaranteed" ||
    enriched.immediateKoBeforeOpponent === true ||
    ["brn", "par", "slp"].includes(cleanId(enriched.status)) ||
    (enriched.secondaries ?? []).some(
      (secondary) =>
        ["brn", "par", "slp"].includes(cleanId(secondary.status)) &&
        Number(secondary.chance ?? 100) >= 60,
    );

  const opponentPositiveBoosts = Math.max(
    0,
    finiteNumber(enriched.opponentPositiveBoosts, 0),
    positiveBoostTotal(enriched.opponentBoosts),
    positiveBoostTotal(enriched.targetBoosts),
  );
  if (moveId === "haze" && opponentPositiveBoosts <= 0) {
    adjustments.push(
      scoreAdjustment(
        "rule.haze.no_opponent_boosts",
        "초기화할 상대 랭크 없음",
        0,
        -1000,
        "상대에게 올라간 랭크가 없어 흑안개를 사용할 이유가 없습니다.",
      ),
    );
  } else if (moveId === "haze") {
    const immediateResetBonus = 240 + Math.min(6, opponentPositiveBoosts) * 40;
    adjustments.push(
      scoreAdjustment(
        "rule.haze.immediate_boost_reset",
        "상대 랭크 즉시 초기화",
        opponentPositiveBoosts,
        immediateResetBonus,
        `상대에게 양의 랭크가 ${opponentPositiveBoosts}단계 있으므로 위협도 예측과 무관하게 흑안개를 즉시 우선합니다.`,
      ),
    );
  }
  const recoveryMove = RECOVERY_MOVE_IDS.has(moveId) || tags.has("recovery");
  if (
    setupPunishMove &&
    setupEvaluation.opponentCanSetup === true &&
    sweepRiskAfterSetup >= 0.22 &&
    enriched.koChance !== "guaranteed" &&
    enriched.immediateKoBeforeOpponent !== true
  ) {
    const bonus =
      Math.round(
        Math.max(
          12,
          finiteNumber(setupEvaluation.freeTurnPenalty, 0) * 0.85,
        ) * 100,
      ) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.setup_threat.punish_option",
        "랭크업 즉시 응징",
        moveId,
        bonus,
        `상대가 랭크업하면 스윕 위험이 ${Math.round(sweepRiskAfterSetup * 100)}%까지 오르므로, ${moveId}로 전개를 즉시 끊는 가치를 높였습니다.`,
      ),
    );
  }
  if (
    !recoveryMove &&
    setupEvaluation.opponentCanSetup === true &&
    sweepRiskAfterSetup >= 0.22 &&
    !setupPunishMove
  ) {
    let exposureMultiplier = 0;
    if (enriched.category === "Status") {
      exposureMultiplier = tags.has("setupboost")
        ? 0.45
        : tags.has("hazardset")
          ? 1
          : 0.8;
    } else {
      const opponentHp = Math.max(1, finiteNumber(enriched.opponentHp, 1));
      const damageRatio = finiteNumber(enriched.expectedDamage, 0) / opponentHp;
      if (damageRatio < 0.2) exposureMultiplier = 0.45;
    }
    if (exposureMultiplier > 0) {
      const penalty =
        -Math.round(
          finiteNumber(setupEvaluation.freeTurnPenalty, 0) *
            exposureMultiplier *
            100,
        ) / 100;
      if (penalty < 0) {
        adjustments.push(
          scoreAdjustment(
            tags.has("hazardset")
              ? "rule.setup_threat.free_hazard_turn"
              : tags.has("setupboost")
                ? "rule.setup_threat.setup_race"
                : "rule.setup_threat.free_turn",
            tags.has("hazardset")
              ? "설치 중 상대 랭크업 위험"
              : tags.has("setupboost")
                ? "랭크업 맞대응 위험"
                : "상대 무료 랭크업 위험",
            Math.round(sweepRiskAfterSetup * 100),
            penalty,
            `이 행동으로 상대에게 랭크업 기회를 주면 스윕 위험이 ${Math.round(sweepRiskAfterSetup * 100)}%까지 오르고, 랭크업 후 유효 대응 자원은 약 ${finiteNumber(setupEvaluation.availableAnswersAfterSetup?.estimatedTotal, 0)}마리로 평가됩니다.`,
          ),
        );
      }
    }
  }

  if (PIVOT_MOVE_IDS.has(moveId)) {
    const hasLivingBench = enriched.hasLivingBench ?? enriched.livingBenchCount > 0;
    if (hasLivingBench === false) {
      adjustments.push(
        scoreAdjustment(
          "rule.pivot.no_bench",
          "피벗 불가",
          false,
          -60,
          "교체할 아군이 없어 피벗 기술 가치를 크게 낮췄습니다.",
        ),
      );
    } else if (hasLivingBench !== false && !enriched.forceSwitch) {
      const survivalProbability = ratioValue(enriched.survivalProbability, actsBefore ? 1 : undefined);
      if (actsBefore || survivalProbability >= 1 || enriched.safePivot === true) {
        const weight = moveId === "partingshot" ? 12 : 8;
        adjustments.push(
          scoreAdjustment(
            "rule.pivot.safe_pivot",
            "안전 피벗",
            moveId,
            weight,
            moveId === "partingshot"
              ? "상대를 약화시키며 안전하게 교체할 수 있어 피벗 가치를 반영했습니다."
              : "공격 후 안전하게 교체 흐름을 만들 수 있어 피벗 가치를 반영했습니다.",
          ),
        );
      }
    }
  }

  if (isSelfSacrificeCandidate(enriched)) {
    const opponentHp = finiteNumber(enriched.opponentHp);
    const expectedDamage = finiteNumber(enriched.expectedDamage, 0);
    const damageRatio =
      opponentHp && opponentHp > 0 ? expectedDamage / opponentHp : undefined;
    const meaningfulDamage =
      enriched.koChance === "guaranteed" ||
      damageRatio >= 0.6 ||
      enriched.meaningfulSacrificeDamage === true;
    const activeRoleScore = finiteNumber(enriched.activeRoleScore, enriched.userRoleScore);
    const hpPercent = ratioValue(enriched.hpPercent, enriched.healthRatio, 1);
    const incomingRatio = ratioValue(
      enriched.currentIncomingDamageRatio,
      enriched.opponentMaxDamageToCurrentHealthRatio,
      enriched.incomingDamageRatio,
    );
    const expendable =
      enriched.expendableResource === true ||
      enriched.roleComplete === true ||
      (activeRoleScore !== undefined && activeRoleScore <= 4) ||
      (hpPercent <= 0.25 && incomingRatio >= 0.6);
    let weight = -220;
    if (meaningfulDamage) weight += 35;
    if (enriched.koChance === "guaranteed") weight += 45;
    if (expendable) weight += 70;
    if (activeRoleScore >= 10) weight -= 70;
    else if (activeRoleScore >= 6) weight -= 35;
    if (enriched.mustPreserveResource === true) weight -= 180;
    if (!meaningfulDamage) weight -= 60;
    adjustments.push(
      scoreAdjustment(
        "rule.self_sacrifice.resource_cost",
        "자폭 리스크",
        damageRatio === undefined ? enriched.koChance ?? false : Math.round(damageRatio * 100),
        weight,
        enriched.mustPreserveResource === true
          ? `현재 포켓몬은 ${arrayValues(enriched.mustPreserveFor).join(", ") || "상대 핵심 포켓몬"}의 유일한 대응 자원이라 자폭으로 소모하지 않도록 크게 낮췄습니다.`
          : enriched.roleComplete === true
            ? `현재 포켓몬은 ${arrayValues(enriched.completedRoles).map((role) => ROLE_LABELS[role] ?? role).join(", ") || "주요"} 역할을 마쳐, 유의미한 피해를 남기는 자폭의 소모 비용을 완화했습니다.`
          : meaningfulDamage && expendable
          ? "상대에게 유의미한 피해를 주고 현재 포켓몬의 남은 역할 가치가 낮아 자폭 리스크를 제한적으로 허용했습니다."
          : meaningfulDamage
            ? "상대에게 피해 가치는 있지만 사용자가 쓰러지는 소모 비용을 크게 반영했습니다."
            : "사용자가 쓰러지는 기술인데 피해/마무리 가치가 충분하지 않아 크게 낮게 봤습니다.",
      ),
    );
  }

  if (enriched.focusSashBlocked === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.focus_sash.single_hit_blocked",
        "기합의띠 방지",
        true,
        -90,
        "상대 기합의띠가 발동하면 단타 공격은 HP 1에서 멈추므로 확정 마무리 가치를 크게 낮췄습니다.",
      ),
    );
  } else if (enriched.sturdyBlocked === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.sturdy.single_hit_blocked",
        "옹골참 단타 저지",
        cleanId(enriched.opponentAbility ?? "sturdy") || true,
        -90,
        "상대 옹골참이 발동하면 단타 공격은 HP 1에서 멈추므로 확정 마무리 가치가 크게 낮아집니다.",
      ),
    );
  } else if (enriched.breaksFocusSash === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.focus_sash.multi_hit_breaker",
        "기합의띠 관통",
        Number(enriched.hitCount ?? enriched.hits ?? 2),
        55,
        "연속타가 기합의띠로 HP 1에 남은 상대를 이어서 처리할 수 있어 마무리 가치를 높였습니다.",
      ),
    );
  } else if (enriched.breaksSturdy === true || enriched.sturdyBreaker === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.sturdy.multi_hit_breaker",
        "옹골참 관통",
        Number(enriched.hitCount ?? enriched.hits ?? 2),
        55,
        "연속타가 옹골참으로 남은 HP 1을 이어서 처리할 수 있어 마무리 가치를 높였습니다.",
      ),
    );
  }

  if (tier >= 2) {
    if (BOOST_RESET_MOVE_IDS.has(moveId)) {
      adjustments.push(
        scoreAdjustment(
          "rule.setup_disruption.boost_reset",
          "랭크 초기화",
          tier,
          tier >= 3 ? 17 : 13,
          "상대 랭크업 위협을 초기화할 수 있어 방해 가치를 반영했습니다.",
        ),
      );
    } else if (PHAZE_MOVE_IDS.has(moveId)) {
      const hazardLayers = Math.min(3, Math.max(0, Number(enriched.opponentHazardLayers ?? 0)));
      adjustments.push(
        scoreAdjustment(
          "rule.setup_disruption.phaze",
          "강제 교체",
          tier,
          (tier >= 3 ? 16 : 12) + hazardLayers,
          hazardLayers > 0
            ? "랭크업 위협을 강제 교체시키고 설치물 피해까지 활용할 수 있습니다."
            : "랭크업 위협을 강제 교체로 끊을 수 있습니다.",
        ),
      );
    } else if (TAUNT_MOVE_IDS.has(moveId) && !enriched.opponentAlreadyBoosted) {
      adjustments.push(
        scoreAdjustment(
          "rule.setup_disruption.taunt",
          "전개 차단",
          tier,
          tier >= 3 ? 12 : 8,
          "상대의 추가 랭크업이나 보조기를 도발로 막을 수 있어 가치를 반영했습니다.",
        ),
      );
    } else if (TAUNT_MOVE_IDS.has(moveId) && enriched.opponentAlreadyBoosted) {
      adjustments.push(
        scoreAdjustment(
          "rule.setup_disruption.late_taunt",
          "늦은 도발",
          tier,
          tier >= 3 ? -16 : -12,
          "이미 오른 랭크는 도발로 제거할 수 없어 가치를 낮췄습니다.",
        ),
      );
    }
  }

  return adjustments;
}

function moveRuleAdjustmentScore(candidate, strategy = "balanced") {
  return moveRuleAdjustments(candidate, strategy).reduce(
    (sum, adjustment) => sum + Number(adjustment.weight ?? 0),
    0,
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
  const priorityWeight =
    difficulty === "expert" ||
    difficulty === "expert_winrate" ||
    difficulty === "expert_search" ||
    difficulty === "cheater"
      ? 12
      : 5;
  const statusValue =
    candidate.category === "Status"
      ? strategy === "defensive"
        ? 38
        : strategy === "balanced"
          ? 12
          : 4
      : 0;
  const powerWeight =
    strategy === "aggressive" ? 1.2 : strategy === "defensive" ? 0.82 : 1;
  const accuracyWeight = strategy === "defensive" ? accuracy * accuracy : accuracy;
  const directValue = Number.isFinite(Number(candidate.expectedDamage))
    ? Number(candidate.expectedDamage)
    : Number(candidate.power ?? 0);
  const tacticalValue = Number(candidate.tacticalValue ?? 0);
  const roleValue = Number(candidate.roleValue ?? moveRoleValue(candidate, strategy));
  const ruleValue = moveRuleAdjustmentScore(candidate, strategy);
  const koBonus =
    candidate.koChance === "guaranteed"
      ? 55 * accuracy
      : candidate.koChance === "possible"
        ? 25 * accuracy
        : 0;

  return (
    directValue * powerWeight * accuracyWeight +
    Number(candidate.priority ?? 0) * priorityWeight +
    statusValue +
    tacticalValue +
    roleValue +
    ruleValue +
    koBonus
  );
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

export function switchRuleAdjustments(candidate, strategy = "balanced") {
  const adjustments = [];
  const hpPercent = ratioValue(candidate.hpPercent, 0);
  const currentIncoming = ratioValue(
    candidate.currentIncomingDamageRatio,
    candidate.currentIncomingRatio,
  );
  const targetIncoming = ratioValue(
    candidate.targetIncomingDamageRatio,
    candidate.incomingDamageRatio,
  );
  const currentOutgoing = ratioValue(
    candidate.currentOutgoingDamageRatio,
    candidate.currentDamageRatio,
  );
  const targetOutgoing = ratioValue(
    candidate.targetOutgoingDamageRatio,
    candidate.outgoingDamageRatio,
  );
  const switchInDamageRatio = Math.max(
    0,
    finiteNumber(candidate.switchInDamageRatio, 0),
  );
  const forceSwitch = candidate.forceSwitch === true;
  const fieldSynergyValue = finiteNumber(
    candidate.fieldSynergyValue,
    candidate.fieldValue,
  );
  const setupEvaluation =
    candidate.setupThreatEvaluation ??
    candidate.opponentSetupThreatEvaluation ??
    {};
  const setupSweepRisk = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(
        setupEvaluation.sweepRiskAfterSetup,
        candidate.opponentSetupSweepRisk,
      ) ?? 0,
    ),
  );
  const oneTurnEvaluation =
    candidate.oneTurnEvaluation ??
    candidate.battleStateEvaluation ??
    null;
  const stayPressurePenalty = Math.max(
    0,
    finiteNumber(candidate.stayPressurePenalty, 0),
  );
  const currentHpPercent = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(candidate.currentHpPercent, 1),
    ),
  );
  const regeneratorRecoveryRatio = Math.max(
    0,
    finiteNumber(candidate.regeneratorRecoveryRatio, 0),
  );
  if (
    cleanId(candidate.currentAbility) === "regenerator" &&
    !forceSwitch &&
    currentHpPercent < 0.6 &&
    regeneratorRecoveryRatio > 0
  ) {
    const urgency = Math.max(
      0,
      Math.min(1, (0.6 - currentHpPercent) / 0.6),
    );
    const strategyMultiplier =
      strategy === "tempo"
        ? 1.25
        : strategy === "defensive"
          ? 1.2
          : strategy === "aggressive"
            ? 0.8
            : strategy === "reckless_ace"
              ? 0.75
              : 1;
    const bonus =
      Math.round(
        (12 + regeneratorRecoveryRatio * 70 + urgency * 28) *
          strategyMultiplier *
          100,
      ) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.regenerator_recovery",
        "재생력 회복",
        {
          hpPercent: Math.round(currentHpPercent * 100),
          recovery: candidate.regeneratorRecoveryHp,
        },
        bonus,
        `현재 체력이 ${Math.round(currentHpPercent * 100)}%라 교체하면 재생력으로 ${Math.round(regeneratorRecoveryRatio * 100)}%만큼 회복할 수 있어 교체 가치를 높였습니다.`,
      ),
    );
  }
  if (stayPressurePenalty > 0) {
    const relieved = [];
    if (finiteNumber(candidate.yawnSwitchPressure, 0) > 0) {
      relieved.push("하품");
    }
    if (finiteNumber(candidate.saltCureSwitchPressure, 0) > 0) {
      relieved.push("소금절이");
    }
    if (finiteNumber(candidate.toxicSwitchPressure, 0) > 0) {
      relieved.push("맹독 누적");
    }
    adjustments.push(
      scoreAdjustment(
        "rule.switch.clears_residual_pressure",
        "교체 시 누적 위험 해제",
        relieved,
        0,
        `교체하면 ${relieved.join(", ")} 압박을 제거하거나 초기화할 수 있어 잔류 행동과 비교할 때 유리합니다.`,
      ),
    );
  }
  if (oneTurnEvaluation) {
    const delta = finiteNumber(
      oneTurnEvaluation.delta,
      candidate.battleStateValueDelta,
    );
    const weightMultiplier = Math.max(
      0,
      finiteNumber(candidate.oneTurnSearchWeight, 0.35),
    );
    if (delta !== undefined && weightMultiplier > 0) {
      const weight =
        Math.round(delta * weightMultiplier * 100) / 100;
      adjustments.push(
        scoreAdjustment(
          "simulation.one_turn_state_value",
          "1턴 후 전투 상태",
          oneTurnEvaluation.winProbabilityAfter ??
            oneTurnEvaluation.qValue,
          weight,
          oneTurnEvaluation.winProbabilityAfter !== undefined
            ? `현재 승률 ${Math.round(oneTurnEvaluation.winProbabilityBefore * 1_000) / 10}%에서 교체 후 ${Math.round(oneTurnEvaluation.winProbabilityAfter * 1_000) / 10}%로 ${oneTurnEvaluation.winProbabilityDelta >= 0 ? "+" : ""}${Math.round(oneTurnEvaluation.winProbabilityDelta * 1_000) / 10}%p 변할 것으로 추정했습니다.`
            : `교체 직후 상태의 가치는 ${oneTurnEvaluation.qValue}, 현재 상태 대비 변화는 ${delta >= 0 ? "+" : ""}${delta}로 평가했습니다.`,
        ),
      );
    }
  }
  if (candidate.aceRecoveryPlanEligible === true) {
    const plan = candidate.aceRecoveryPlan ?? {};
    adjustments.push(
      scoreAdjustment(
        "rule.switch.ace_recovery_sacrifice_plan",
        "에이스 회복용 희생 교체",
        {
          sacrifice: plan.sacrificeName,
          ace: plan.aceName,
          winProbabilityDelta: plan.winProbabilityDelta,
        },
        0,
        `${plan.sacrificeName ?? candidate.name}을 잔존 가치가 가장 낮은 자원으로 투입한 뒤 ${plan.aceName ?? "에이스"}을 회복하면 예측 승률이 ${Math.round(Number(plan.winProbabilityDelta ?? 0) * 1_000) / 10}%p 상승하므로 연속 계획을 시작합니다.`,
      ),
    );
  }
  if (candidate.batonPassSetupOpportunity === true) {
    const strategyMultiplier =
      strategy === "reckless_ace"
        ? 3.1
        : strategy === "setup"
          ? 1.6
          : strategy === "aggressive"
            ? 1.15
            : strategy === "defensive" || strategy === "hazard"
              ? 0.75
              : 1;
    const transferValue = Math.max(
      0,
      finiteNumber(candidate.batonPassTransferValue, 0),
    );
    const newKoTargets = Math.max(
      0,
      finiteNumber(candidate.batonPassNewKoTargets, 0),
    );
    const setupTurns = Math.max(
      1,
      finiteNumber(candidate.batonPassSafeSetupTurns, 1),
    );
    const bonus =
      Math.round(
        Math.min(
          125,
          38 +
            setupTurns * 10 +
            transferValue * 0.16 +
            newKoTargets * 24,
        ) *
          strategyMultiplier *
          100,
      ) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.baton_pass_setup_opportunity",
        "배턴터치 전개 기회",
        {
          ace: candidate.batonPassTargetName,
          setupTurns,
          incomingDamageRatio: candidate.batonPassIncomingDamageRatio,
        },
        bonus,
        `${candidate.name}이(가) 약한 상대를 상대로 약 ${setupTurns}회 안전하게 랭크업한 뒤 에이스 ${candidate.batonPassTargetName}에게 배턴터치할 수 있어 투입 가치를 높였습니다.${
          strategy === "reckless_ace"
            ? " 저돌적 에이스 전략이라 에이스 전개 보너스를 더 강하게 적용했습니다."
            : ""
        }`,
      ),
    );
  }

  if (candidate.mustPreserveResource === true) {
    const preservationTargets = arrayValues(candidate.mustPreserveFor);
    const currentThreat = candidate.preservationTargetIsCurrent === true;
    if (currentThreat && candidate.currentThreatClassification === "counter") {
      adjustments.push(
        scoreAdjustment(
          "rule.switch.unique_counter_deployment",
          "유일 카운터 투입",
          preservationTargets,
          18,
          `현재 상대는 ${preservationTargets.join(", ") || "핵심 위협"}이며, 이 교체 후보가 유일한 안정 대응 자원이라 투입 가치를 높였습니다.`,
        ),
      );
    } else if (!currentThreat) {
      const exposureRisk = Math.max(
        switchInDamageRatio,
        candidate.canReachNextAction === false ? 1 : 0,
      );
      if (exposureRisk >= 0.2) {
        const strategyMultiplier =
          strategy === "ace_check"
            ? 1.3
            : strategy === "defensive"
              ? 1.15
              : strategy === "reckless_ace"
                ? 0.75
                : 1;
        const weight =
          -Math.round(
            Math.min(180, 28 + exposureRisk * 95) *
              strategyMultiplier *
              100,
          ) / 100;
        adjustments.push(
          scoreAdjustment(
            "rule.switch.unique_counter_preservation",
            "유일 카운터 보존",
            preservationTargets,
            weight,
            `${preservationTargets.join(", ") || "남은 상대 핵심 포켓몬"}을 막을 유일한 대응 자원이라, 현재 대면에서 체력을 소모하는 교체를 낮게 평가했습니다.`,
          ),
        );
      }
    }
  }

  if (candidate.targetRoleComplete === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.role_complete",
        "역할 완료 자원",
        candidate.targetCompletedRoles ?? true,
        0,
        `${arrayValues(candidate.targetCompletedRoles).map((role) => ROLE_LABELS[role] ?? role).join(", ") || "주요"} 역할을 마친 자원으로 평가했습니다.`,
      ),
    );
  }

  if (
    !forceSwitch &&
    setupEvaluation.opponentCanSetup === true &&
    setupSweepRisk >= 0.22
  ) {
    const classification = cleanId(candidate.currentThreatClassification);
    const entersAsAnswer = [
      "counter",
      "softcheck",
      "revengekiller",
    ].includes(classification);
    const canPunishAfterSwitch =
      candidate.canKoOnNextAction === true ||
      candidate.priorityKo === true ||
      candidate.setupPunishAfterSwitch === true;
    if (entersAsAnswer || canPunishAfterSwitch) {
      const weight = Math.round((10 + setupSweepRisk * 20) * 100) / 100;
      adjustments.push(
        scoreAdjustment(
          "rule.switch.setup_answer",
          "랭크업 대응 투입",
          candidate.currentThreatClassification ?? candidate.projectedBestMoveId,
          weight,
          "상대의 랭크업 가능성을 허용하더라도 교체 후보가 카운터 또는 즉시 응징 자원으로 기능할 수 있습니다.",
        ),
      );
    } else {
      const targetPressure = Math.max(
        0,
        finiteNumber(candidate.targetOutgoingDamageRatio, 0),
      );
      const lowPressureMultiplier =
        targetPressure < 0.35 ? 0.8 : targetPressure < 0.6 ? 0.55 : 0.3;
      const penalty =
        -Math.round(
          finiteNumber(setupEvaluation.freeTurnPenalty, 0) *
            lowPressureMultiplier *
            100,
        ) / 100;
      if (penalty < 0) {
        adjustments.push(
          scoreAdjustment(
            "rule.switch.free_setup_turn",
            "의미 없는 교체의 랭크업 위험",
            Math.round(setupSweepRisk * 100),
            penalty,
            `교체 후보가 상대 랭크업 포켓몬의 카운터가 아니고 즉시 KO도 만들지 못해, 교체 중 스윕 위험 ${Math.round(setupSweepRisk * 100)}%를 허용하는 비용을 반영했습니다.`,
          ),
        );
      }
    }
  }

  if (currentIncoming !== undefined && targetIncoming !== undefined) {
    const weight = Math.round((currentIncoming - targetIncoming) * 12 * 100) / 100;
    if (weight !== 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.switch.defensive_improvement",
          "피격 감소",
          `${Math.round(currentIncoming * 100)}%→${Math.round(targetIncoming * 100)}%`,
          weight,
          `예상 피격량이 ${Math.round(currentIncoming * 100)}%에서 ${Math.round(targetIncoming * 100)}%로 바뀌는 점을 반영했습니다.`,
        ),
      );
    }
  }

  const safeTwoHitHold =
    currentOutgoing !== undefined &&
    currentIncoming !== undefined &&
    currentOutgoing >= 0.5 &&
    currentIncoming < hpPercent &&
    !forceSwitch;
  if (currentOutgoing !== undefined && targetOutgoing !== undefined && !safeTwoHitHold) {
    const weight = Math.round((targetOutgoing - currentOutgoing) * 6 * 100) / 100;
    if (weight !== 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.switch.offensive_improvement",
          "공격 개선",
          `${Math.round(currentOutgoing * 100)}%→${Math.round(targetOutgoing * 100)}%`,
          weight,
          `교체 전후 최대 공격 기대값 변화를 ${Math.round(currentOutgoing * 100)}%에서 ${Math.round(targetOutgoing * 100)}%로 평가했습니다.`,
        ),
      );
    }
  }

  if (safeTwoHitHold) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.hold_safe_two_hit",
        "유리 대면 유지",
        true,
        -4,
        "현재 포켓몬이 안전하게 2타 KO 압박을 유지할 수 있어 무리한 교체를 낮게 봤습니다.",
      ),
    );
  }

  if (candidate.speedAdvantage === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.speed_advantage",
        "교체 후 선공",
        true,
        2,
        "교체 후보가 상대보다 먼저 움직일 수 있어 속도 가치를 반영했습니다.",
      ),
    );
  }

  if (!forceSwitch && candidate.survivesSwitchIn === false) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.faints_on_entry_turn",
        "교체 턴 기절",
        candidate.switchInExpectedDamage,
        -240,
        "교체 직후 상대 공격을 받아 행동 기회 없이 쓰러질 것으로 예상해 크게 낮췄습니다.",
      ),
    );
  } else if (!forceSwitch && candidate.canReachNextAction === false) {
    const aceScore = Math.max(0, finiteNumber(candidate.targetAceScore, 0));
    const roleScore = Math.max(0, finiteNumber(candidate.targetRoleScore, 0));
    const preservationMultiplier =
      strategy === "ace_check"
        ? 1.3
        : strategy === "defensive"
          ? 1.15
          : strategy === "reckless_ace"
            ? 0.8
            : 1;
    const preservationCost =
      (candidate.targetAceQualified === true
        ? 650 + Math.min(180, aceScore * 10)
        : candidate.targetRoleComplete === true
          ? 10
          : 35 + Math.min(60, roleScore * 5)) * preservationMultiplier;
    const weight = -Math.round((150 + preservationCost) * 100) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.no_action_opportunity",
        "반격 불가능한 교체",
        candidate.hpAfterSwitchIn,
        weight,
        candidate.targetAceQualified === true
          ? "교체 턴에 피해를 받은 뒤 다음 행동 전에 다시 쓰러질 전망이라, 에이스를 아무 행동 없이 소모하는 선택을 크게 낮췄습니다."
          : "교체 턴에 피해를 받은 뒤 다음 행동 전에 다시 쓰러질 전망이라 희생 교체 비용을 반영했습니다.",
      ),
    );
  } else if (!forceSwitch && candidate.canKoOnNextAction === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.next_action_counter_ko",
        "교체 후 반격 KO",
        true,
        24,
        "교체 턴의 공격을 버틴 뒤 다음 행동권에서 상대를 쓰러뜨릴 수 있어 카운터 투입 가치를 반영했습니다.",
      ),
    );
  }
  if (
    forceSwitch &&
    candidate.canReachNextAction === false &&
    candidate.immediateKoBeforeOpponent !== true &&
    candidate.priorityKo !== true
  ) {
    const aceScore = Math.max(0, finiteNumber(candidate.targetAceScore, 0));
    const preservationCost =
      candidate.targetAceQualified === true
        ? 220 + Math.min(120, aceScore * 8)
        : 140;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.forced_no_action",
        "행동 불가능한 강제 출전",
        candidate.projectedKnockoutBeforeActionProbability,
        -Math.round(preservationCost * 100) / 100,
        candidate.targetAceQualified === true
          ? "강제 출전 직후 상대의 선공 공격에 쓰러져 아무 행동도 못 할 에이스라 다른 생존 후보보다 크게 낮췄습니다."
          : "강제 출전 직후 상대의 선공 공격에 쓰러져 아무 행동도 못 할 전망이라 다른 생존 후보보다 낮췄습니다.",
      ),
    );
  }

  if (fieldSynergyValue !== undefined && fieldSynergyValue !== 0) {
    adjustments.push(
      scoreAdjustment(
        fieldSynergyValue > 0
          ? "rule.switch.field_synergy"
          : "rule.switch.field_mismatch",
        fieldSynergyValue > 0 ? "필드 활용" : "필드 불리",
        candidate.fieldSynergyLabel ?? candidate.fieldEffect ?? true,
        fieldSynergyValue,
        candidate.fieldSynergyReason ??
          (fieldSynergyValue > 0
            ? "현재 필드/날씨/룸 효과를 활용할 수 있어 교체 가치를 높였습니다."
            : "현재 필드/날씨/룸 효과와 맞지 않아 교체 가치를 낮췄습니다."),
      ),
    );
  }

  if (candidate.currentStatus && !["tox", "toxic", "badlypoisoned"].includes(cleanId(candidate.currentStatus))) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.status_relief",
        "상태 이상 회피",
        candidate.currentStatus,
        4,
        "현재 포켓몬의 상태 이상 부담을 덜 수 있어 교체 가치를 반영했습니다.",
      ),
    );
  }
  if (candidate.targetStatus) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.target_status",
        "교체 후보 상태 이상",
        candidate.targetStatus,
        -4,
        "교체 후보가 이미 상태 이상이라 안정성을 낮게 봤습니다.",
      ),
    );
  }

  const positiveBoosts = Math.max(0, Number(candidate.currentPositiveBoosts ?? 0));
  if (positiveBoosts > 0 && !forceSwitch) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.boost_loss",
        "랭크 손실",
        positiveBoosts,
        -positiveBoosts * 2,
        `교체하면 현재 쌓은 유리한 랭크 ${positiveBoosts}단계를 잃습니다.`,
      ),
    );
  }

  const opponentOffensiveBoosts = Math.max(
    0,
    Number(candidate.opponentOffensiveBoosts ?? 0),
  );
  const boostedAceExposure =
    !forceSwitch &&
    candidate.targetAceQualified === true &&
    opponentOffensiveBoosts > 0 &&
    candidate.canKoOnNextAction !== true &&
    switchInDamageRatio >= 0.2;
  if (boostedAceExposure) {
    const preservationMultiplier =
      strategy === "ace_check"
        ? 1.25
        : strategy === "defensive"
          ? 1.15
          : strategy === "reckless_ace"
            ? 0.75
            : 1;
    const weight =
      -Math.round(
        Math.min(
          240,
          (50 + opponentOffensiveBoosts * 35 + switchInDamageRatio * 100) *
            preservationMultiplier,
        ) * 100,
      ) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.boosted_attacker_ace_exposure",
        "랭크업 상대 앞 에이스 노출",
        `${opponentOffensiveBoosts}랭크 / 피해 ${Math.round(switchInDamageRatio * 100)}%`,
        weight,
        `상대가 공격 계열 랭크를 ${opponentOffensiveBoosts}단계 쌓았고 교체 후보가 ${candidate.switchInThreatMoveId || "예상 공격"}에 큰 피해를 받지만 다음 행동에서 KO를 보장하지 못해, 에이스 소모 위험을 크게 반영했습니다.`,
      ),
    );
  }

  if (
    targetIncoming !== undefined &&
    targetOutgoing !== undefined &&
    targetOutgoing >= 0.9 &&
    targetIncoming < 1 &&
    !safeTwoHitHold
  ) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.safe_counter_ko",
        "생존 카운터 KO",
        true,
        10,
        "교체 후보가 상대 공격을 버티고 높은 확률로 반격 KO를 노릴 수 있습니다.",
      ),
    );
  }

  if (targetIncoming !== undefined && targetIncoming >= hpPercent) {
    const actsBeforeAfterSwitch = candidate.speedAdvantage === true || candidate.priorityKo === true;
    const canKoBeforeFaint =
      forceSwitch &&
      (candidate.immediateKoBeforeOpponent === true ||
        candidate.priorityKo === true ||
        (actsBeforeAfterSwitch && targetOutgoing !== undefined && targetOutgoing >= 1));
    if (!canKoBeforeFaint) {
      adjustments.push(
        scoreAdjustment(
          "rule.switch.lethal_switch_in",
          "교체 즉시 KO 위험",
          `${Math.round(targetIncoming * 100)}% / HP ${Math.round(hpPercent * 100)}%`,
          forceSwitch && actsBeforeAfterSwitch ? -40 : -80,
          forceSwitch && actsBeforeAfterSwitch
            ? "강제 교체 후보가 다음 행동 후 쓰러질 위험이 있어 크게 낮게 봤습니다."
            : "교체 후보가 예상 공격에 즉시 쓰러질 위험이 있어 크게 낮게 봤습니다.",
        ),
      );
    }
  }

  if (!forceSwitch && candidate.safeImmediateKoAvailable === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.guaranteed_ko_penalty",
        "확정 KO 포기",
        true,
        -30,
        "현재 포켓몬이 안전한 확정 KO를 낼 수 있어 자발 교체를 낮게 봤습니다.",
      ),
    );
  }

  if (!forceSwitch && candidate.safePivotAvailable === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.pivot_available",
        "피벗 우선",
        true,
        -12,
        "안전한 피벗 기술로 상대를 압박하며 교체할 수 있어 즉시 교체를 낮게 봤습니다.",
      ),
    );
  }

  if (!forceSwitch && switchInDamageRatio > 0) {
    const weight =
      -Math.round(Math.min(70, switchInDamageRatio * 55) * 100) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.incoming_hit_cost",
        "교체 턴 체력 손실",
        Math.round(switchInDamageRatio * 100),
        weight,
        `교체와 동시에 최대 체력의 약 ${Math.round(switchInDamageRatio * 100)}%를 잃을 것으로 예상해 비용을 반영했습니다.`,
      ),
    );
  }

  if (
    !forceSwitch &&
    candidate.currentCanReachAction === true &&
    candidate.emergencyEscape !== true
  ) {
    const currentBestMoveScore = Math.max(
      0,
      finiteNumber(candidate.currentBestMoveScore, 0),
    );
    const weight =
      -Math.round(Math.min(24, currentBestMoveScore * 0.06) * 100) / 100;
    if (weight < 0) {
      adjustments.push(
        scoreAdjustment(
          "rule.switch.action_opportunity_cost",
          "현재 행동권 포기",
          currentBestMoveScore,
          weight,
          "현재 포켓몬이 쓰러지기 전에 행동할 수 있어, 그 행동권을 버리는 교체 비용을 반영했습니다.",
        ),
      );
    }
  }

  if (!forceSwitch && candidate.safeActionDenialAvailable === true) {
    adjustments.push(
      scoreAdjustment(
        "rule.switch.safe_disruption_available",
        "확정 행동 저지 포기",
        true,
        -80,
        "속이기처럼 상대 행동을 확실히 막는 기술을 사용할 수 있어, 이를 버리고 피해를 받는 교체를 크게 낮췄습니다.",
      ),
    );
  }

  if (!forceSwitch && candidate.switchedLastTurn === true) {
    const immediateReturn = candidate.immediateReturn === true;
    const forcedReplacement = candidate.forcedReplacement === true;
    const setupEmergency = setupThreatTier(candidate) >= 3 || candidate.oneMoreTurnUnmanageable === true;
    if (!setupEmergency) {
      const penalty =
        2 + (immediateReturn ? 4 : 0) + (forcedReplacement ? 36 : 0);
      adjustments.push(
        scoreAdjustment(
          "rule.switch.repeated_switch",
          forcedReplacement
            ? "강제 출전 직후 재교체"
            : immediateReturn
              ? "교체 왕복 억제"
              : "연속 교체 억제",
          true,
          -penalty,
          forcedReplacement
            ? "기절 후 강제 출전 직후 다시 교체하는 행동을 낮게 봤습니다."
            : immediateReturn
              ? "직전 교체 후 같은 두 포켓몬을 왕복하는 행동을 낮게 봤습니다."
              : "직전 턴에 이어 연속 교체하는 행동을 낮게 봤습니다.",
        ),
      );
    }
  }

  const dynamaxRemainingTurns = Math.max(
    0,
    Number(candidate.dynamaxRemainingTurns ?? candidate.remainingDynamaxTurns ?? 0),
  );
  if (!forceSwitch && candidate.dynamaxActive === true && dynamaxRemainingTurns > 0) {
    const multiplier = candidate.dynamaxEscapeJustified === true ? 0.5 : 1;
    const weight = -Math.round(dynamaxRemainingTurns * 9 * multiplier * 100) / 100;
    adjustments.push(
      scoreAdjustment(
        "rule.switch.dynamax_turn_cost",
        "다이맥스 턴 포기",
        dynamaxRemainingTurns,
        weight,
        candidate.dynamaxEscapeJustified === true
          ? `다이맥스 ${dynamaxRemainingTurns}턴을 포기하지만 즉사/봉쇄 위험으로 페널티를 완화했습니다.`
          : `교체하면 남은 다이맥스 ${dynamaxRemainingTurns}턴을 잃어 기회비용을 반영했습니다.`,
      ),
    );
  }

  return adjustments;
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
  const ruleValue = switchRuleAdjustments(candidate, strategy).reduce(
    (sum, adjustment) => sum + Number(adjustment.weight ?? 0),
    0,
  );
  return expectedDamage + matchupValue + hpPercent * 10 + ruleValue;
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
  const normalizedId = cleanId(id);
  const reasons = [];
  const selectedScore = finiteNumber(selectedMove.score, 0);
  const baseScore = finiteNumber(baseMove.score, 0);
  const scoreDifference = Math.round((selectedScore - baseScore) * 100) / 100;
  const configuredBonus =
    configured === true
      ? normalizedId === "mega"
        ? 8
        : normalizedId === "terastallize"
          ? 3
          : 0
      : 0;
  const score = Math.round((scoreDifference + configuredBonus) * 100) / 100;
  const selectedDelta = finiteNumber(
    selectedMove.oneTurnEvaluation?.delta,
    selectedMove.battleStateValueDelta,
  );
  const baseDelta = finiteNumber(
    baseMove.oneTurnEvaluation?.delta,
    baseMove.battleStateValueDelta,
  );
  const stateDeltaDifference =
    selectedDelta !== undefined && baseDelta !== undefined
      ? Math.round((selectedDelta - baseDelta) * 100) / 100
      : null;

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
    activationThreshold:
      activationThreshold ??
      PROJECTED_GIMMICK_THRESHOLDS[normalizedId] ??
      0,
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
