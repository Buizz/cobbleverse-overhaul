const DIFFICULTY_LABELS = {
  novice: "초급",
  standard: "보통",
  advanced: "상급",
  expert: "전문가",
  cheater: "치터",
};

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

function switchDecisionReasons(candidate) {
  const reasons = [];
  const hpPercent = finiteNumber(candidate.hpPercent);
  const expectedDamage = finiteNumber(candidate.expectedDamage);
  const matchupValue = finiteNumber(candidate.matchupValue);

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
  const id =
    candidate.actionId ??
    (type === "switch"
      ? `switch:${candidate.slot}`
      : `${type}:${candidate.slot ?? candidate.id ?? candidate.name}`);
  return {
    ...candidate,
    id: candidate.id ?? candidate.moveId ?? candidate.switchId ?? id,
    actionId: id,
    type,
    legal: candidate.legal ?? !candidate.disabled,
    action: {
      type,
      slot: candidate.slot,
      id: candidate.id ?? candidate.moveId ?? candidate.switchId ?? null,
      label: candidate.name ?? candidate.label ?? null,
    },
    expectedDamage: expectedDamageObservation(candidate),
    koChance: candidate.koChance ?? undefined,
    survivalRisk: finiteNumber(candidate.survivalRisk),
    speedRisk: finiteNumber(candidate.speedRisk),
    statusValue: finiteNumber(candidate.statusValue),
    setupRisk: finiteNumber(candidate.setupRisk),
    fieldValue: finiteNumber(candidate.fieldValue),
    roleValue: finiteNumber(candidate.roleValue),
    resourceCost: finiteNumber(candidate.resourceCost),
    score: finiteNumber(candidate.score, 0),
    reasons:
      type === "switch"
        ? switchDecisionReasons(candidate)
        : moveDecisionReasons(candidate, difficulty, strategy),
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
  return expectedDamage + matchupValue + hpPercent * 10;
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

export function selectAiGimmick({
  active = {},
  configured = {},
  moveSlot = 1,
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
    active.canDynamax &&
    (forceDynamax || configured?.gimmicks?.dynamax)
  ) {
    return { id: "dynamax", showdownSuffix: " dynamax" };
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
