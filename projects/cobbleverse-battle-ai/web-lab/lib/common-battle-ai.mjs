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
      ...candidate,
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
    ...candidate,
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
