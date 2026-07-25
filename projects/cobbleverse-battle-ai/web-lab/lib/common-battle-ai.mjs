import { readFileSync } from "node:fs";

const MOVE_ROLE_CATALOG_URL = new URL(
  "../../data/ai/ai-move-role-classification.json",
  import.meta.url,
);
const MOVE_ROLE_CATALOG = JSON.parse(
  readFileSync(MOVE_ROLE_CATALOG_URL, "utf8"),
);
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
const DYNAMAX_SCORE_THRESHOLD = 12;

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

  if (hasSafeImmediateKo && !isSafeFinisher(enriched)) {
    const weight = isDamage ? -10 : -80;
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
    } else if (currentLayers < hazardMaximumLayers && !enriched.immediateKoAvailable) {
      const incomingRatio = ratioValue(
        enriched.opponentMaxDamageToCurrentHealthRatio,
        enriched.incomingDamageRatio,
      );
      const hpPercent = ratioValue(enriched.hpPercent, 1);
      if (actsBefore || incomingRatio === undefined || incomingRatio < hpPercent) {
        const livingOpponents = Math.max(0, Number(enriched.livingOpponents ?? 2));
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

  if (tags.has("setupboost")) {
    const incomingRatio = ratioValue(
      enriched.opponentMaxDamageToCurrentHealthRatio,
      enriched.incomingDamageRatio,
    );
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
    if (incomingRatio !== undefined) {
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
      ? 55
      : candidate.koChance === "possible"
        ? 25
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

export function switchRuleAdjustments(candidate) {
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
  const forceSwitch = candidate.forceSwitch === true;

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

  if (!forceSwitch && candidate.switchedLastTurn === true) {
    const immediateReturn = candidate.immediateReturn === true;
    const forcedReplacement = candidate.forcedReplacement === true;
    const setupEmergency = setupThreatTier(candidate) >= 3 || candidate.oneMoreTurnUnmanageable === true;
    if (!setupEmergency) {
      const penalty =
        2 + (immediateReturn ? 4 : 0) + (forcedReplacement ? 24 : 0);
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

function switchDecisionReasons(candidate) {
  const reasons = [];
  const hpPercent = finiteNumber(candidate.hpPercent);
  const expectedDamage = finiteNumber(candidate.expectedDamage);
  const matchupValue = finiteNumber(candidate.matchupValue);
  const switchAdjustments = switchRuleAdjustments(candidate);

  if (hpPercent !== undefined) {
    reasons.push({
      code: "switch.hp_remaining",
      label: "남은 체력",
      value: Math.round(hpPercent * 100),
      message: `남은 체력 ${Math.round(hpPercent * 100)}%를 교체 안정성으로 반영했습니다.`,
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
        ? switchDecisionReasons(enrichedCandidate)
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

export function scoreAiSwitchCandidate(candidate) {
  const hpPercent = Number.isFinite(Number(candidate.hpPercent))
    ? Number(candidate.hpPercent)
    : 0;
  const expectedDamage = Number.isFinite(Number(candidate.expectedDamage))
    ? Number(candidate.expectedDamage)
    : 0;
  const matchupValue = Number.isFinite(Number(candidate.matchupValue))
    ? Number(candidate.matchupValue)
    : 0;
  const ruleValue = switchRuleAdjustments(candidate).reduce(
    (sum, adjustment) => sum + Number(adjustment.weight ?? 0),
    0,
  );
  return expectedDamage + matchupValue + hpPercent * 10 + ruleValue;
}

export function rankAiSwitchCandidates(candidates) {
  return candidates
    .map((candidate) => ({
      ...candidate,
      score: Math.round(scoreAiSwitchCandidate(candidate) * 100) / 100,
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
  const ranked = rankAiSwitchCandidates(available);
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
  const ranked = rankAiSwitchCandidates(candidates).map((candidate) => ({
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

  if (selectedMove.koChance === "guaranteed") {
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
  if (Number(selectedMove.expectedDamage ?? 0) >= Number(active.opponentHp ?? Infinity) * 0.6) {
    score += 6;
    reasons.push(
      scoreAdjustment(
        "gimmick.dynamax.damage_pressure",
        "피해 압박",
        selectedMove.expectedDamage,
        6,
        "선택 기술의 피해 기대값이 높아 다이맥스 화력 가치를 반영했습니다.",
      ),
    );
  }

  if (moveCandidates.length > 0 && hasSetupMove(moveCandidates) && hasFightingAttack(moveCandidates)) {
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
    id: "dynamax",
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
  forceDynamax = false,
  alreadyUsed = {},
} = {}) {
  if (!alreadyUsed.mega) {
    if (active.canMegaEvo) return { id: "mega", showdownSuffix: " mega" };
    if (active.canMegaEvoX) return { id: "mega", showdownSuffix: " megax" };
    if (active.canMegaEvoY) return { id: "mega", showdownSuffix: " megay" };
  }
  if (!alreadyUsed.zmove && active.canZMove?.[moveSlot - 1]) {
    return { id: "zmove", showdownSuffix: " zmove" };
  }
  if (
    !alreadyUsed.dynamax &&
    active.canDynamax
  ) {
    const candidate = scoreAiDynamaxCandidate({
      active,
      configured,
      selectedMove,
      moveCandidates,
      forceDynamax,
    });
    if (candidate.score >= DYNAMAX_SCORE_THRESHOLD) {
      return { id: "dynamax", showdownSuffix: " dynamax", candidate };
    }
  }
  if (
    !alreadyUsed.terastallize &&
    active.canTerastallize &&
    configured?.gimmicks?.tera
  ) {
    return { id: "terastallize", showdownSuffix: " terastallize" };
  }
  return { id: "", showdownSuffix: "" };
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
