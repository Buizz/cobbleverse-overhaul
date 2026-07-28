import MOVE_ROLE_CATALOG from "../../data/ai/ai-move-role-classification.json" with { type: "json" };
import POKEMON_ROLE_OVERRIDES from "../../data/ai/ai-pokemon-role-overrides.json" with { type: "json" };
const DIFFICULTY_LABELS = {
  novice: "초급",
  standard: "보통",
  advanced: "상급",
  expert: "전문가",
  cheater: "치터",
};
const ROLE_LABELS = {
  lead: "선봉",
  ace: "에이스",
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
  },
  ace_check: {
    revengeKiller: 1.25,
    disruptor: 1.15,
    ace: 0.9,
    pivot: 0.75,
    hazardControl: 0.65,
    wall: 0.6,
    support: 0.45,
    setupSweeper: 0.35,
  },
  reckless_ace: {
    ace: 1.55,
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
    setupSweeper: 0.25,
  },
  tempo: {
    pivot: 1.45,
    revengeKiller: 1.05,
    disruptor: 0.9,
    ace: 0.75,
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
const ROLE_VALUE_SCALE = 4;
const HAZARD_MAX_LAYERS = {
  stealthrock: 1,
  stickyweb: 1,
  spikes: 3,
  toxicspikes: 2,
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
const DYNAMAX_SCORE_THRESHOLD = 18;

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
  const hasOffensiveStat = offense >= 115;
  const hasFastStat = speed >= 100;
  const hasHighBst = baseTotal >= 570;
  const hasStrongSpeciesPrior = rawAce >= 2.4;

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

  const qualifies = manual.forced || (score >= 5.8 && offensiveAnchor);
  return {
    score: Math.round(score * 100) / 100,
    qualifies,
    manual,
    reasons,
  };
}

function analyzeTeamMemberRole(member = {}, index = 0, teamContext = {}) {
  const roleScores = Object.fromEntries(Object.keys(ROLE_LABELS).map((role) => [role, 0]));
  const reasons = [];
  const warnings = [];
  const moveIds = pokemonMoveIds(member);
  const tags = new Set();

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
    tags,
    stats: { attack, specialAttack, speed, hp, defense, specialDefense },
    teamContext,
  });
  const displayRoleScores = { ...roleScores };
  if (!aceProfile.qualifies) {
    displayRoleScores.ace = 0;
  }
  if (aceProfile.qualifies && aceProfile.reasons.length > 0) {
    reasons.unshift(`에이스 판단: ${aceProfile.reasons.slice(0, 3).join(", ")}`);
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
    moveIds,
    reasons: reasons.slice(0, 4),
    warnings,
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
  };
  const roles = team.map((member, index) => analyzeTeamMemberRole(member, index, teamContext));
  const byRole = (role) =>
    roles
      .filter((entry) => entry.roles.some((candidate) => candidate.role === role))
      .sort(
        (left, right) =>
          Number(right.roleScores[role] ?? 0) - Number(left.roleScores[role] ?? 0),
      );
  const qualifiedAceCandidates = roles
    .filter((entry) => entry.aceProfile?.qualifies)
    .sort(
      (left, right) =>
        Number(right.aceScore ?? 0) - Number(left.aceScore ?? 0) ||
        Number(right.rawRoleScores?.ace ?? 0) - Number(left.rawRoleScores?.ace ?? 0) ||
        left.slot - right.slot,
    );
  const fallbackAce = roles
    .filter((entry) => Number.isFinite(Number(entry.aceScore)))
    .sort(
      (left, right) =>
        Number(right.aceScore ?? 0) - Number(left.aceScore ?? 0) || left.slot - right.slot,
    )[0];
  const aceCandidates =
    qualifiedAceCandidates.length > 0
      ? qualifiedAceCandidates.slice(0, 3)
      : fallbackAce
        ? [fallbackAce]
        : [];
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

export function aiScoringRuleCatalog() {
  return AI_SCORING_RULES.map((rule) => ({ ...rule }));
}

export function moveRuleAdjustments(candidate, strategy = "balanced") {
  const enriched = enrichMoveCandidateWithRole(candidate, strategy);
  const moveId = cleanId(enriched.id ?? enriched.moveId ?? enriched.name);
  const tags = candidateTagSet(enriched);
  const adjustments = [];
  const tier = setupThreatTier(enriched);
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
    if (!setupRiskRecoveryEmergency && opponentLikelyToSetup(enriched)) {
      const likelihood = opponentSetupLikelihood(enriched);
      const penalty =
        hpPercent >= 0.8
          ? -95
          : hpPercent >= 0.65
            ? -75
            : -45;
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
    if (!meaningfulDamage) weight -= 60;
    adjustments.push(
      scoreAdjustment(
        "rule.self_sacrifice.resource_cost",
        "자폭 리스크",
        damageRatio === undefined ? enriched.koChance ?? false : Math.round(damageRatio * 100),
        weight,
        meaningfulDamage && expendable
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
    difficulty === "expert" || difficulty === "cheater" ? 12 : 5;
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

  if (difficulty === "expert" || difficulty === "cheater") {
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
  if (difficulty === "novice") {
    return available[rng.nextIndex(available.length)];
  }
  const ranked = rankAiMoveCandidates(available, difficulty, strategy);
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
      ...toAiActionCandidate(candidate, { type: "move", difficulty, strategy }),
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
    ...toAiActionCandidate(candidate, { type: "switch", difficulty, strategy }),
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
  forceDynamax = false,
  alreadyUsed = {},
} = {}) {
  let dynamaxCandidate = null;
  if (!alreadyUsed.mega) {
    if (active.canMegaEvo) return { id: "mega", showdownSuffix: " mega" };
    if (active.canMegaEvoX) return { id: "mega", showdownSuffix: " megax" };
    if (active.canMegaEvoY) return { id: "mega", showdownSuffix: " megay" };
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
      return {
        id: useGigantamax ? "gigantamax" : "dynamax",
        showdownSuffix: " dynamax",
        candidate: {
          ...dynamaxCandidate,
          id: useGigantamax ? "gigantamax" : "dynamax",
        },
      };
    }
  }
  if (
    !alreadyUsed.terastallize &&
    active.canTerastallize &&
    configured?.gimmicks?.tera
  ) {
    return { id: "terastallize", showdownSuffix: " terastallize" };
  }
  return { id: "", showdownSuffix: "", candidate: dynamaxCandidate };
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
