const MAX_SEED = 0xffffffff;
const MAX_TEXT_LENGTH = 80;
const LEVEL_MODES = new Set(["original", "level-50", "level-100"]);
const BATTLE_ENGINES = new Set(["showdown", "cobbleverse"]);
const GIMMICK_RULESETS = new Set(["gen8", "gen9", "all"]);
const AI_DIFFICULTIES = new Set([
  "novice",
  "standard",
  "advanced",
  "expert",
  "cheater",
]);
const AI_STRATEGIES = new Set([
  "balanced",
  "aggressive",
  "defensive",
  "ace_check",
  "reckless_ace",
  "setup",
  "hazard",
  "tempo",
  "unpredictable",
]);
const AI_STRATEGY_LABEL = Array.from(AI_STRATEGIES).join(", ");

function issue(path, code, message) {
  return { path, code, message };
}

function cleanText(value) {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeMoveList(value) {
  if (!Array.isArray(value)) return [];
  return value.map(cleanText).filter(Boolean).slice(0, 4);
}

function normalizeStats(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value)
      .map(([key, stat]) => [key, Number(stat)])
      .filter(([, stat]) => Number.isFinite(stat)),
  );
}

function normalizeMember(raw, slot, itemResolver) {
  const normalizedItem = normalizeHeldItem(
    raw?.heldItemOptions?.length ? raw.heldItemOptions : raw?.heldItem,
    itemResolver,
  );
  return {
    slot,
    species: cleanText(raw?.species),
    resolvedSpecies: cleanText(raw?.resolvedSpecies) || cleanText(raw?.species),
    aspects: Array.isArray(raw?.aspects)
      ? raw.aspects.map(cleanText).filter(Boolean)
      : [],
    gimmicks:
      raw?.gimmicks &&
      typeof raw.gimmicks === "object" &&
      !Array.isArray(raw.gimmicks)
        ? {
            mega: raw.gimmicks.mega === true,
            dynamax: raw.gimmicks.dynamax === true,
            gmax: raw.gimmicks.gmax === true,
            tera: cleanText(raw.gimmicks.tera).toLowerCase() || null,
          }
        : {},
    level: Number(raw?.level),
    gender: cleanText(raw?.gender) || null,
    nature: cleanText(raw?.nature) || null,
    ability: cleanText(raw?.ability) || null,
    ...normalizedItem,
    moveset: normalizeMoveList(raw?.moveset ?? raw?.moves),
    ivs: normalizeStats(raw?.ivs),
    evs: normalizeStats(raw?.evs),
  };
}

function validateMember(member, path) {
  const issues = [];
  if (!member.species) {
    issues.push(issue(`${path}.species`, "required", "포켓몬 종을 입력해 주세요."));
  } else if (member.species.length > MAX_TEXT_LENGTH) {
    issues.push(issue(`${path}.species`, "too_long", "포켓몬 ID가 너무 깁니다."));
  }

  if (!Number.isInteger(member.level) || member.level < 1 || member.level > 100) {
    issues.push(issue(`${path}.level`, "range", "레벨은 1부터 100 사이의 정수여야 합니다."));
  }

  if (member.moveset.length === 0) {
    issues.push(issue(`${path}.moveset`, "required", "최소 한 개의 기술이 필요합니다."));
  }

  for (const [index, move] of member.moveset.entries()) {
    if (move.length > MAX_TEXT_LENGTH) {
      issues.push(issue(`${path}.moveset.${index}`, "too_long", "기술 ID가 너무 깁니다."));
    }
  }

  return issues;
}

function normalizeCustomSide(raw, path, itemResolver) {
  const rawTeam = Array.isArray(raw?.team) ? raw.team : [];
  const populatedTeam = rawTeam.filter((member) => cleanText(member?.species));
  const team = populatedTeam
    .slice(0, 6)
    .map((member, index) => normalizeMember(member, index + 1, itemResolver));
  const issues = [];

  if (populatedTeam.length === 0) {
    issues.push(issue(`${path}.team`, "required", "파티에 포켓몬을 한 마리 이상 추가해 주세요."));
  }
  if (populatedTeam.length > 6) {
    issues.push(issue(`${path}.team`, "too_many", "파티에는 포켓몬을 최대 6마리까지 넣을 수 있습니다."));
  }
  team.forEach((member, index) => {
    issues.push(...validateMember(member, `${path}.team.${index}`));
  });

  return {
    issues,
    side: {
      source: "custom",
      trainerId: null,
      name: cleanText(raw?.name) || "Player",
      team,
    },
  };
}

function normalizePresetSide(raw, path, trainerById, itemResolver) {
  const trainerId = cleanText(raw?.trainerId);
  const trainer = trainerById.get(trainerId);
  if (!trainer) {
    return {
      issues: [issue(`${path}.trainerId`, "unknown_trainer", "존재하는 트레이너 JSON을 선택해 주세요.")],
      side: null,
    };
  }

  const requestedOrder = Array.isArray(raw?.teamOrder)
    ? raw.teamOrder
        .map(Number)
        .filter(
          (slot, index, slots) =>
            Number.isInteger(slot) && slots.indexOf(slot) === index,
        )
    : [];
  const requestedSlots = new Set(requestedOrder);
  const orderedTeam = [
    ...requestedOrder
      .map((slot) => trainer.team.find((member) => member.slot === slot))
      .filter(Boolean),
    ...trainer.team.filter((member) => !requestedSlots.has(member.slot)),
  ];
  const team = orderedTeam
    .slice(0, 6)
    .map((member, index) => normalizeMember(member, index + 1, itemResolver));
  const issues = [];
  team.forEach((member, index) => {
    issues.push(...validateMember(member, `${path}.team.${index}`));
  });

  return {
    issues,
    side: {
      source: "preset",
      trainerId: trainer.id,
      name: trainer.name,
      team,
    },
  };
}

function canonicalScenarioHash(value) {
  const input = JSON.stringify(value);
  let hash = 0x811c9dc5;
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}

export function createBattleScenario(raw, trainers, itemResolver = null) {
  const issues = [];
  const mode = cleanText(raw?.mode).toLowerCase();
  if (mode !== "pve" && mode !== "eve") {
    issues.push(issue("mode", "unsupported", "전투 모드는 pve 또는 eve여야 합니다."));
  }

  const seed = Number(raw?.seed);
  if (!Number.isInteger(seed) || seed < 0 || seed > MAX_SEED) {
    issues.push(issue("seed", "range", `시드는 0부터 ${MAX_SEED} 사이의 정수여야 합니다.`));
  }

  const levelMode = cleanText(raw?.levelMode).toLowerCase() || "original";
  if (!LEVEL_MODES.has(levelMode)) {
    issues.push(
      issue(
        "levelMode",
        "unsupported",
        "레벨 모드는 original, level-50, level-100 중 하나여야 합니다.",
      ),
    );
  }

  const battleEngine =
    cleanText(raw?.battleEngine).toLowerCase() || "showdown";
  if (!BATTLE_ENGINES.has(battleEngine)) {
    issues.push(
      issue(
        "battleEngine",
        "unsupported",
        "배틀 엔진은 showdown 또는 cobbleverse여야 합니다.",
      ),
    );
  }

  const requestedGimmickRules =
    cleanText(raw?.gimmickRules).toLowerCase() || "gen9";
  const gimmickRules =
    battleEngine === "cobbleverse" ? "all" : requestedGimmickRules;
  if (!GIMMICK_RULESETS.has(gimmickRules)) {
    issues.push(
      issue(
        "gimmickRules",
        "unsupported",
        "기믹 규칙은 gen8, gen9 또는 all이어야 합니다.",
      ),
    );
  } else if (battleEngine === "showdown" && gimmickRules === "all") {
    issues.push(
      issue(
        "gimmickRules",
        "unsupported_engine_gimmicks",
        "전체 기믹 규칙은 Cobbleverse 자체 엔진에서만 사용할 수 있습니다.",
      ),
    );
  }

  const battleType = cleanText(raw?.battleType).toLowerCase() || "single";
  const format = battleFormat(battleType);
  if (!format) {
    issues.push(
      issue(
        "battleType",
        "unsupported",
        "대결 타입은 single, double, triple 중 하나여야 합니다.",
      ),
    );
  } else if (battleEngine === "cobbleverse" && !format.supportsCobbleverse) {
    issues.push(
      issue(
        "battleType",
        "unsupported_engine_format",
        "Cobbleverse 자체 엔진은 현재 싱글 배틀만 지원합니다.",
      ),
    );
  } else if (
    battleEngine === "showdown" &&
    gimmickRules === "gen8" &&
    battleType === "triple"
  ) {
    issues.push(
      issue(
        "battleType",
        "unsupported_gimmick_format",
        "Showdown에는 8세대 트리플 형식이 없어 다이맥스를 사용할 수 없습니다. 싱글·더블 또는 9세대 규칙을 선택해 주세요.",
      ),
    );
  }

  const aiDifficulty =
    cleanText(raw?.aiDifficulty).toLowerCase() || "standard";
  if (!AI_DIFFICULTIES.has(aiDifficulty)) {
    issues.push(
      issue(
        "aiDifficulty",
        "unsupported",
        "AI 수준은 novice, standard, advanced, expert, cheater 중 하나여야 합니다.",
      ),
    );
  }

  const rawAiProfiles = Array.isArray(raw?.aiProfiles) ? raw.aiProfiles : [];
  const aiProfiles = [0, 1].map((index) => {
    const difficulty =
      cleanText(rawAiProfiles[index]?.difficulty).toLowerCase() || aiDifficulty;
    const strategy =
      cleanText(rawAiProfiles[index]?.strategy).toLowerCase() || "balanced";
    if (!AI_DIFFICULTIES.has(difficulty)) {
      issues.push(
        issue(
          `aiProfiles.${index}.difficulty`,
          "unsupported",
          "AI 수준은 novice, standard, advanced, expert, cheater 중 하나여야 합니다.",
        ),
      );
    }
    if (!AI_STRATEGIES.has(strategy)) {
      issues.push(
        issue(
          `aiProfiles.${index}.strategy`,
          "unsupported",
          `AI 성향은 ${AI_STRATEGY_LABEL} 중 하나여야 합니다.`,
        ),
      );
    }
    return { difficulty, strategy };
  });

  const trainerById = new Map(
    (Array.isArray(trainers) ? trainers : []).map((trainer) => [trainer.id, trainer]),
  );
  const rawSides = Array.isArray(raw?.sides) ? raw.sides : [];
  if (rawSides.length !== 2) {
    issues.push(issue("sides", "size", "전투에는 정확히 두 개의 파티가 필요합니다."));
  }

  const normalizedSides = rawSides.slice(0, 2).map((rawSide, index) => {
    const path = `sides.${index}`;
    const source = cleanText(rawSide?.source).toLowerCase();
    if (source === "custom") return normalizeCustomSide(rawSide, path, itemResolver);
    if (source === "preset") {
      return normalizePresetSide(rawSide, path, trainerById, itemResolver);
    }
    return {
      issues: [issue(`${path}.source`, "unsupported", "파티 출처는 custom 또는 preset이어야 합니다.")],
      side: null,
    };
  });

  normalizedSides.forEach((result) => issues.push(...result.issues));
  const sides = normalizedSides.map((result) => result.side).filter(Boolean);
  if (format) {
    sides.forEach((side, index) => {
      if (side.team.length < format.activeSlots) {
        issues.push(
          issue(
            `sides.${index}.team`,
            "insufficient_active_members",
            `${format.label} 배틀에는 각 파티에 최소 ${format.activeSlots}마리가 필요합니다.`,
          ),
        );
      }
    });
  }

  if (mode === "pve" && rawSides[1]?.source !== "preset") {
    issues.push(issue("sides.1.source", "pve_opponent", "PvE의 상대 파티는 트레이너 JSON이어야 합니다."));
  }
  if (mode === "eve" && rawSides.some((side) => side?.source !== "preset")) {
    issues.push(issue("sides", "eve_presets", "EvE에서는 양쪽 모두 트레이너 JSON을 사용해야 합니다."));
  }
  if (
    mode === "eve" &&
    cleanText(rawSides[0]?.trainerId) &&
    cleanText(rawSides[0]?.trainerId) === cleanText(rawSides[1]?.trainerId)
  ) {
    issues.push(issue("sides.1.trainerId", "duplicate", "EvE에서는 서로 다른 트레이너를 선택해 주세요."));
  }

  if (issues.length > 0) {
    return { ok: false, issues };
  }

  const fixedLevel =
    levelMode === "level-50" ? 50 : levelMode === "level-100" ? 100 : null;
  const leveledSides = sides.map((side) => ({
    ...side,
    team: side.team.map((member) => ({
      ...member,
      level: fixedLevel ?? member.level,
    })),
  }));
  const scenarioBody = {
    schemaVersion: 1,
    mode,
    seed,
    levelMode,
    battleEngine,
    battleType,
    gimmickRules,
    aiDifficulty,
    aiProfiles,
    sides: leveledSides,
  };
  const scenarioId = `${mode}-${seed.toString(16).padStart(8, "0")}-${canonicalScenarioHash(scenarioBody)}`;

  return {
    ok: true,
    scenario: {
      scenarioId,
      ...scenarioBody,
    },
  };
}
import { normalizeHeldItem } from "./cobblemon-item-catalog.mjs";
import { battleFormat } from "./battle-formats.mjs";
