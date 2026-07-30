/* eslint-disable @next/next/no-img-element */
"use client";

import {
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
} from "react";
import Link from "next/link";

import {
  analyzeTeamProfile,
  teamRoleLabel,
} from "../../lib/common-battle-ai.mjs";
import { localizedSpeciesName } from "../../lib/species-localization.mjs";
import {
  BATTLE_EVENT_NAMES,
  formatBattleDialogue,
} from "../../lib/battle-dialogue";
import { BattleAudioControl } from "../../lib/BattleAudioControl";
import {
  getBattleAudioServerSettings,
  getBattleAudioSettings,
  playBattleSoundEffects,
  subscribeBattleAudioSettings,
} from "../../lib/battle-audio";

const EVE_REPORT_KEY = "cobbleverse-battle-lab:eve-report";
const EVE_HISTORY_KEY = "cobbleverse-battle-lab:eve-history";
const EVE_HISTORY_LIMIT = 20;

type AiProfile = {
  difficulty:
    | "novice"
    | "standard"
    | "advanced"
    | "expert"
    | "expert_winrate"
    | "expert_search"
    | "cheater";
  strategy:
    | "balanced"
    | "aggressive"
    | "defensive"
    | "ace_check"
    | "reckless_ace"
    | "setup"
    | "hazard"
    | "tempo"
    | "unpredictable";
  cheatProbability?: number;
};

type AiDecisionReason = {
  code: string;
  label: string;
  value?: number | string | boolean;
  weight?: number;
  message: string;
};

type Scenario = {
  scenarioId: string;
  mode: "eve";
  seed: number;
  battleType: string;
  battleEngine: string;
  levelMode: string;
  aiDifficulty: AiProfile["difficulty"];
  aiProfiles?: AiProfile[];
  sides: Array<{
    name: string;
    trainerId: string | null;
    team: Array<{
      slot: number;
      species: string;
      name?: string;
      level: number;
      types?: string[];
      ability?: string;
      item?: string;
      heldItem?: string;
      gimmicks?: Record<string, unknown>;
      moves?: Array<
        | string
        | {
            id?: string;
            moveId?: string;
            name?: string;
            move?: string;
            power?: number;
            category?: string;
            type?: string;
            priority?: number;
          }
      >;
      stats?: Record<string, number>;
      baseStats?: Record<string, number>;
      baseStatsRaw?: Record<string, number>;
    }>;
  }>;
};

type AiCandidate = {
  slot: number;
  id?: string;
  name: string;
  type?: string;
  action?: {
    type?: string;
    id?: string;
  };
  power?: number;
  score?: number;
  koChance?: "none" | "possible" | "guaranteed";
  damageRangeMinimum?: number;
  damageRangeMaximum?: number;
  winProbabilityBefore?: number;
  winProbabilityAfter?: number;
  winProbabilityDelta?: number;
  selected: boolean;
  reasons?: AiDecisionReason[];
};

type WinEstimate = {
  probability: number;
  probabilityPercent: number;
  confidence: number;
  modelVersion: string;
  terminal: boolean;
  topFactors: Array<{
    component: string;
    label: string;
    contribution: number;
    direction: "favorable" | "unfavorable" | "neutral";
    message: string;
  }>;
};

type AiTrace = {
  turn: number;
  side: number;
  sideName: string;
  species: string;
  kind: string;
  difficulty: string;
  strategy: string;
  chosenAction: string;
  gimmick?: string;
  reason: string;
  candidates: AiCandidate[];
  winEstimate?: WinEstimate;
  selectionPolicy?: "heuristic" | "win-probability" | "cheater-exact-command";
  diagnostics?: {
    cheatActivated?: boolean;
    cheatProbability?: number;
    cheatRoll?: number;
    cheaterResponseChanged?: boolean;
    observedOpponentCommand?: {
      move?: number;
      switch?: number;
      gimmick?: string;
    };
    heuristicExpectedWinProbability?: number | null;
    cheaterExpectedWinProbability?: number | null;
  } | null;
  policyComparison?: {
    heuristicAction: string;
    heuristicWinProbability: number;
    winProbabilityAction: string;
    winProbability: number;
    probabilityGap: number;
    differs: boolean;
    materiallyDiffers: boolean;
  } | null;
};

type BattleEvent = {
  turn: number;
  type: string;
  actor?: string;
  detail?: string;
  condition?: string;
  layers?: number;
  duration?: number;
  source?: string;
  target?: string;
  fromActor?: string;
  automatic?: boolean;
  forced?: boolean;
  selection?: string;
};

type Battle = {
  battleId: string;
  scenarioId: string;
  seed: number;
  status: string;
  winner: string | null;
  turns: number;
  durationMs: number;
  engine: { id: string; version: string; format: string; controller: string };
  settings?: { aiProfiles?: AiProfile[] };
  warnings: Array<{ message: string }>;
  finalState?: {
    sides: Array<{
      name?: string;
      active?: number;
      team: Array<{
        name?: string;
        species?: string;
        hp?: number;
        maxHp?: number;
        fainted?: boolean;
        status?: string;
      }>;
    }>;
  };
  turnSnapshots?: Array<{
    turn: number;
    sides: Array<{
      active: number;
      team: Array<{
        hp: number;
        maxHp: number;
        fainted: boolean;
        status?: string;
      }>;
    }>;
  }>;
  aiTrace?: AiTrace[];
  events: BattleEvent[];
  log: string[];
};

type ReportData = {
  schemaVersion: number;
  savedAt: string;
  scenario: Scenario;
  battle: Battle;
};

type BattleSummary = Pick<
  Battle,
  "battleId" | "scenarioId" | "seed" | "status" | "winner" | "turns" | "durationMs"
>;

type SweepBattleSummary = Pick<
  BattleSummary,
  "seed" | "status" | "winner" | "turns" | "durationMs"
>;

type LocalizationCatalog = {
  species: Record<string, { name?: string }>;
  moves: Record<string, { name?: string }>;
};

const difficultyNames: Record<string, string> = {
  novice: "초급",
  standard: "보통",
  advanced: "상급",
  expert: "전문가(휴리스틱)",
  expert_winrate: "전문가(승률 기반)",
  expert_search: "전문가(2턴 탐색)",
  cheater: "치터",
};

const strategyNames: Record<string, string> = {
  balanced: "균형",
  aggressive: "공격",
  defensive: "방어",
  ace_check: "에이스 견제",
  reckless_ace: "저돌적 에이스",
  setup: "랭크업 전개",
  hazard: "판 장악",
  tempo: "템포/피벗",
  unpredictable: "변칙",
};

const strategyValues = [
  "balanced",
  "aggressive",
  "defensive",
  "ace_check",
  "reckless_ace",
  "setup",
  "hazard",
  "tempo",
] as AiProfile["strategy"][];

type SweepScore = {
  strategy: AiProfile["strategy"];
  games: number;
  wins: number;
  winRate: number;
};

type SweepMatchup = {
  strategyA: AiProfile["strategy"];
  strategyB: AiProfile["strategy"];
  games: number;
  winsA: number;
  winsB: number;
};

type SweepResult = {
  baseSeed: number;
  rounds: number;
  totalBattles: number;
  parallelism: number;
  executionMode: "worker_threads" | "sequential";
  bestA: SweepScore | null;
  bestB: SweepScore | null;
  sideA: SweepScore[];
  sideB: SweepScore[];
  matchups: SweepMatchup[];
};

function id(value: string | undefined) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

function actorName(value: string | undefined) {
  return String(value ?? "").replace(/^p[12][a-z]?: /, "");
}

function actorSide(value: string | undefined) {
  if (String(value ?? "").startsWith("p1")) return "1P";
  if (String(value ?? "").startsWith("p2")) return "2P";
  return "";
}

function localSpecies(
  localization: LocalizationCatalog | null,
  species: string | undefined,
) {
  return localizedSpeciesName(localization, actorName(species));
}

function localMove(
  localization: LocalizationCatalog | null,
  move: string | undefined,
) {
  if (!move) return "";
  return localization?.moves[id(move)]?.name ?? move;
}

function conditionFullHp(condition: string | undefined) {
  if (!condition) return "";
  if (condition.includes(" fnt")) return "0";
  return condition.split(" ")[0] ?? condition;
}

function pokemonSnapshotKeys(
  localization: LocalizationCatalog | null,
  pokemon: Scenario["sides"][number]["team"][number],
  finalPokemon?: NonNullable<Battle["finalState"]>["sides"][number]["team"][number],
) {
  return new Set(
    [
      pokemon.species,
      pokemon.name,
      finalPokemon?.species,
      finalPokemon?.name,
      localSpecies(localization, pokemon.species),
      localSpecies(localization, finalPokemon?.species),
      finalPokemon?.name ? localSpecies(localization, finalPokemon.name) : "",
    ]
      .map(id)
      .filter(Boolean),
  );
}

function eventPokemonKeys(
  localization: LocalizationCatalog | null,
  event: BattleEvent,
) {
  return new Set(
    [
      actorName(event.actor),
      event.detail,
      event.target,
      localSpecies(localization, event.actor),
      event.detail ? localSpecies(localization, event.detail) : "",
      event.target ? localSpecies(localization, event.target) : "",
    ]
      .map(id)
      .filter(Boolean),
  );
}

function eventPokemonSlot(
  report: ReportData,
  localization: LocalizationCatalog | null,
  sideIndex: number,
  event: BattleEvent,
) {
  const finalTeam = report.battle.finalState?.sides?.[sideIndex]?.team ?? [];
  const eventKeys = eventPokemonKeys(localization, event);
  if (eventKeys.size === 0) return -1;
  return report.scenario.sides[sideIndex].team
    .slice(0, 6)
    .findIndex((pokemon, pokemonIndex) => {
      const keys = pokemonSnapshotKeys(localization, pokemon, finalTeam[pokemonIndex]);
      return [...eventKeys].some((key) => keys.has(key));
    });
}

function profilesOf(report: ReportData | null) {
  return (
    report?.scenario.aiProfiles ??
    report?.battle.settings?.aiProfiles ?? [
      {
        difficulty: report?.scenario.aiDifficulty ?? "expert",
        strategy: "balanced",
      },
      {
        difficulty: report?.scenario.aiDifficulty ?? "expert",
        strategy: "balanced",
      },
    ]
  );
}

function editableProfilesOf(report: ReportData | null) {
  const profiles = [...profilesOf(report)];
  if (
    profiles[0]?.difficulty === "cheater" &&
    profiles[1]?.difficulty === "cheater"
  ) {
    profiles[1] = {
      ...profiles[1],
      difficulty: "expert",
      cheatProbability: undefined,
    };
  }
  return profiles;
}

function compactTeamLine(
  localization: LocalizationCatalog | null,
  side: Scenario["sides"][number],
) {
  return side.team
    .slice(0, 6)
    .map((pokemon) => `${localSpecies(localization, pokemon.species)} Lv.${pokemon.level}`)
    .join(", ");
}

function ReportPokemonSprite({
  species,
  label,
}: {
  species: string;
  label: string;
}) {
  const remoteUrl = `/api/pokemon-sprites?species=${encodeURIComponent(species)}&remote=1`;
  const [failed, setFailed] = useState(false);
  return (
    <img
      alt={label}
      src={
        failed
          ? `/api/pokemon-sprites?species=${encodeURIComponent(species)}&fallback=1`
          : remoteUrl
      }
      onError={() => setFailed(true)}
    />
  );
}

function traceByTurnAndSide(report: ReportData | null) {
  const map = new Map<string, AiTrace[]>();
  for (const trace of report?.battle.aiTrace ?? []) {
    const key = `${trace.turn}:${trace.side}`;
    map.set(key, [...(map.get(key) ?? []), trace]);
  }
  return map;
}

function eveBattleDialogue(
  event: BattleEvent,
  localization: LocalizationCatalog | null,
) {
  return formatBattleDialogue(event, {
    speciesName: (value) => localSpecies(localization, value),
    moveName: (value) => localMove(localization, value),
    detailName: () => {
      if (event.type === "move") return localMove(localization, event.detail);
      if (event.type === "switch") return localSpecies(localization, event.actor);
      if (event.type === "mega_evolution") {
        return localSpecies(localization, event.detail || event.actor);
      }
      return event.detail ?? "";
    },
    sourceName: (value) => localMove(localization, value),
    sideLabels: { p1: "1P ", p2: "2P " },
  });
}

function eventLine(
  event: BattleEvent,
  localization: LocalizationCatalog | null,
) {
  const message = eveBattleDialogue(event, localization);
  return `${["switch", "move", "win", "tie"].includes(event.type) ? "-" : "  ·"} ${message}`;
}

function hpSnapshot(
  report: ReportData,
  localization: LocalizationCatalog | null,
  turn: number,
) {
  const exactSnapshot = report.battle.turnSnapshots?.find(
    (snapshot) => snapshot.turn === turn,
  );
  if (exactSnapshot) {
    return report.scenario.sides.map((side, sideIndex) => {
      const prefix = sideIndex === 0 ? "1P" : "2P";
      const snapshotTeam = exactSnapshot.sides[sideIndex]?.team ?? [];
      const entries = side.team
        .slice(0, 6)
        .map((pokemon, pokemonIndex) => {
          const name = localSpecies(localization, pokemon.species);
          const snapshot = snapshotTeam[pokemonIndex];
          const hp =
            snapshot &&
            Number.isFinite(snapshot.hp) &&
            Number.isFinite(snapshot.maxHp)
              ? snapshot.fainted || snapshot.hp <= 0
                ? "0"
                : `${snapshot.hp}/${snapshot.maxHp}`
              : "?";
          return `${name} ${hp}`;
        })
        .join(", ");
      return `${prefix} [${entries}]`;
    });
  }

  const hpBySide = report.scenario.sides.map((side, sideIndex) => {
    const finalTeam = report.battle.finalState?.sides?.[sideIndex]?.team ?? [];
    return new Map(
      side.team.slice(0, 6).map((_, pokemonIndex) => {
        const maxHp = finalTeam[pokemonIndex]?.maxHp;
        return [
          pokemonIndex,
          Number.isFinite(maxHp) && Number(maxHp) > 0 ? `${maxHp}/${maxHp}` : "?",
        ] as const;
      }),
    );
  });
  const activeSlots = report.scenario.sides.map(() => 0);
  for (const event of report.battle.events) {
    if (event.turn > turn) continue;
    const sideLabel = actorSide(event.actor);
    const sideIndex = sideLabel === "1P" ? 0 : sideLabel === "2P" ? 1 : -1;
    if (sideIndex < 0) continue;
    if (!["switch", "damage", "heal", "faint"].includes(event.type)) continue;
    const matchedSlot = eventPokemonSlot(report, localization, sideIndex, event);
    if (event.type === "switch" && matchedSlot >= 0) {
      activeSlots[sideIndex] = matchedSlot;
    }
    const slot = matchedSlot >= 0 ? matchedSlot : activeSlots[sideIndex];
    if (!Number.isInteger(slot) || slot < 0) continue;
    const hp = event.type === "faint" ? "0" : conditionFullHp(event.condition);
    if (hp) hpBySide[sideIndex].set(slot, hp);
  }

  return report.scenario.sides.map((side, sideIndex) => {
    const prefix = sideIndex === 0 ? "1P" : "2P";
    const entries = side.team
      .slice(0, 6)
      .map((pokemon, pokemonIndex) => {
        const name = localSpecies(localization, pokemon.species);
        return `${name} ${hpBySide[sideIndex].get(pokemonIndex) || "?"}`;
      })
      .join(", ");
    return `${prefix} [${entries}]`;
  });
}

function turnPlainText(
  report: ReportData,
  turn: number,
  localization: LocalizationCatalog | null,
  traceMap: Map<string, AiTrace[]>,
) {
  const events = report.battle.events.filter((event) => event.turn === turn);
  const lines = [`${turn}턴`];
  for (const event of events.filter((event) => event.type !== "turn")) {
    lines.push(eventLine(event, localization));
  }
  lines.push("남은 엔트리:");
  lines.push(`  ${hpSnapshot(report, localization, turn).join("\n  ")}`);
  lines.push("AI 판단 상세:");
  for (const side of [0, 1]) {
    const traces = traceMap.get(`${turn}:${side}`) ?? [];
    lines.push(`  ${side === 0 ? "1P" : "2P"}:`);
    if (traces.length === 0) {
      lines.push("    * 판단 로그 없음");
      continue;
    }
    for (const trace of traces) {
      if (trace.winEstimate) {
        lines.push(
          `    현재 추정 승률 ${trace.winEstimate.probabilityPercent.toFixed(1)}% | 신뢰도 ${(trace.winEstimate.confidence * 100).toFixed(0)}% | ${trace.winEstimate.modelVersion}`,
        );
      }
      if (trace.diagnostics?.cheatActivated) {
        lines.push(
          "    치터 발동: 상대 확정 명령 하나만 사용해 전문가 판단을 실행했습니다.",
        );
      }
      for (const candidate of trace.candidates.slice(0, 6)) {
        const marker = candidate.selected ? "*" : " ";
        const label =
          candidate.type === "switch" || candidate.action?.type === "switch"
            ? `교체 -> ${candidate.name}`
            : candidate.type === "gimmick" || candidate.action?.type === "gimmick"
              ? candidate.id === "gigantamax"
                ? "거다이맥스"
                : candidate.id === "dynamax"
                  ? "다이맥스"
                  : candidate.name
              : localMove(localization, candidate.id ?? candidate.name);
        const damageRange =
          Number.isFinite(candidate.damageRangeMinimum) &&
          Number.isFinite(candidate.damageRangeMaximum)
            ? ` | 피해 범위 ${candidate.damageRangeMinimum}~${candidate.damageRangeMaximum}`
            : "";
        const koText =
          candidate.koChance === "guaranteed"
            ? " | 확정 KO"
            : candidate.koChance === "possible"
              ? " | KO 가능"
              : "";
        const winText = Number.isFinite(candidate.winProbabilityAfter)
          ? ` | 행동 후 승률 ${(Number(candidate.winProbabilityAfter) * 100).toFixed(1)}% (${Number(candidate.winProbabilityDelta) >= 0 ? "+" : ""}${(Number(candidate.winProbabilityDelta) * 100).toFixed(1)}%p)`
          : "";
        lines.push(
          `    ${marker} ${label} | 점수 ${Number(candidate.score ?? 0).toFixed(2)}${damageRange}${koText}${winText}`,
        );
        for (const reason of (candidate.reasons ?? []).slice(0, candidate.selected ? 3 : 2)) {
          const weight =
            typeof reason.weight === "number" ? ` ${reason.weight >= 0 ? "+" : ""}${reason.weight}` : "";
          lines.push(`      - ${reason.label}${weight}: ${reason.message}`);
        }
      }
    }
  }
  return `${lines.join("\n")}\n\n----------------------------------------------------`;
}

function roleNameList(entry: ReturnType<typeof analyzeTeamProfile>["roles"][number]) {
  const labels = entry.roles.slice(0, 2).map((role) => teamRoleLabel(role.role));
  return labels.length > 0 ? labels.join(" / ") : teamRoleLabel(entry.primaryRole);
}

function roleSpeciesName(
  localization: LocalizationCatalog | null,
  species: string | undefined,
) {
  return localSpecies(localization, species);
}

function roleSummaryLine(
  localization: LocalizationCatalog | null,
  entry: ReturnType<typeof analyzeTeamProfile>["roles"][number],
) {
  const name = roleSpeciesName(localization, entry.species);
  const score = entry.roles[0]?.score ?? 0;
  const reason = entry.reasons[0] ? ` - ${entry.reasons[0]}` : "";
  const warnings =
    entry.warnings?.length > 0 ? ` [확인: ${entry.warnings.join(", ")}]` : "";
  return `  - ${entry.slot}. ${name}: ${roleNameList(entry)} (${score.toFixed(1)})${reason}${warnings}`;
}

function roleNameSummary(
  localization: LocalizationCatalog | null,
  entries: ReturnType<typeof analyzeTeamProfile>["roles"],
) {
  if (entries.length === 0) return "없음";
  return entries
    .map((entry) => roleSpeciesName(localization, entry.species))
    .join(", ");
}

function teamRolePlainText(
  report: ReportData,
  localization: LocalizationCatalog | null,
) {
  const lines = ["AI 팀 역할 분석"];
  for (const [sideIndex, side] of report.scenario.sides.entries()) {
    const profile = analyzeTeamProfile(side.team);
    lines.push(`${sideIndex + 1}P ${side.name}`);
    for (const entry of profile.roles.slice(0, 6)) {
      lines.push(roleSummaryLine(localization, entry));
    }
    lines.push(
      `  에이스 후보: ${roleNameSummary(localization, profile.aceCandidates)}`,
    );
    lines.push(
      `  막이 코어: ${roleNameSummary(localization, profile.defensiveCore)}`,
    );
    lines.push(
      `  판 관리: 설치 ${roleNameSummary(
        localization,
        profile.hazardPlan.setters,
      )} / 제거 ${roleNameSummary(localization, profile.hazardPlan.removers)}`,
    );
    if (profile.vulnerabilities.length > 0) {
      lines.push(`  주의: ${profile.vulnerabilities.join(" / ")}`);
    }
  }
  return `${lines.join("\n")}\n\n----------------------------------------------------`;
}

function battlePlainText(
  report: ReportData,
  localization: LocalizationCatalog | null,
) {
  const traceMap = traceByTurnAndSide(report);
  const turnLog = Array.from({ length: report.battle.turns }, (_, index) =>
    turnPlainText(report, index + 1, localization, traceMap),
  ).join("\n\n");
  return `${teamRolePlainText(report, localization)}\n\n${turnLog}`;
}

function historyId(report: ReportData) {
  return `${report.battle.battleId}:${report.savedAt}`;
}

function winnerSummary(report: ReportData) {
  const winner = report.battle.winner;
  if (!winner) return { side: "DRAW", label: "무승부" };
  const sideIndex = report.scenario.sides.findIndex((side) => side.name === winner);
  if (sideIndex < 0) return { side: "WIN", label: `${winner} 승리` };
  return {
    side: `${sideIndex + 1}P`,
    label: `${sideIndex + 1}P 승리 · ${winner}`,
  };
}

function compactReportForStorage(report: ReportData): ReportData {
  return {
    ...report,
    battle: {
      ...report.battle,
      log: [],
      aiTrace: report.battle.aiTrace?.map((trace) => ({
        ...trace,
        candidates: trace.candidates.slice(0, 6).map((candidate) => ({
          slot: candidate.slot,
          id: candidate.id,
          name: candidate.name,
          type: candidate.type,
          action: candidate.action,
          power: candidate.power,
          score: candidate.score,
          koChance: candidate.koChance,
          damageRangeMinimum: candidate.damageRangeMinimum,
          damageRangeMaximum: candidate.damageRangeMaximum,
          selected: candidate.selected,
          reasons: candidate.reasons?.slice(0, candidate.selected ? 3 : 2).map((reason) => ({
            code: reason.code,
            label: reason.label,
            value: reason.value,
            weight: reason.weight,
            message: reason.message,
          })),
        })),
      })),
    },
  };
}

function compactHistoryForStorage(history: ReportData[]) {
  const seen = new Set<string>();
  const compacted: ReportData[] = [];
  for (const entry of history) {
    const id = historyId(entry);
    if (seen.has(id)) continue;
    seen.add(id);
    compacted.push(compactReportForStorage(entry));
    if (compacted.length >= EVE_HISTORY_LIMIT) break;
  }
  return compacted;
}

function trySetStorageItem(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

function persistEveStorage(history: ReportData[], latest?: ReportData) {
  const compactLatest = latest ? compactReportForStorage(latest) : null;
  if (compactLatest) {
    if (!trySetStorageItem(EVE_REPORT_KEY, JSON.stringify(compactLatest))) {
      localStorage.removeItem(EVE_REPORT_KEY);
    }
  }

  let nextHistory = compactHistoryForStorage(history);
  while (nextHistory.length > 0) {
    if (trySetStorageItem(EVE_HISTORY_KEY, JSON.stringify(nextHistory))) {
      return {
        history: nextHistory,
        trimmed: nextHistory.length < Math.min(history.length, EVE_HISTORY_LIMIT),
      };
    }
    nextHistory = nextHistory.slice(0, -1);
  }
  localStorage.removeItem(EVE_HISTORY_KEY);
  return { history: [], trimmed: history.length > 0 };
}

type EveReplaySpeed = "slow" | "normal" | "fast";

const EVE_REPLAY_EVENT_TYPES = new Set([
  ...Object.keys(BATTLE_EVENT_NAMES),
  "item_removed",
  "multi_hit",
  "volatile_start",
  "volatile_end",
]);

function eventSideIndex(event: BattleEvent) {
  if (String(event.actor ?? "").startsWith("p1")) return 0;
  if (String(event.actor ?? "").startsWith("p2")) return 1;
  return -1;
}

function conditionNumbers(condition: string | undefined) {
  const token = String(condition ?? "").split(" ")[0];
  if (!token) return null;
  const [hpValue, maxValue] = token.split("/").map(Number);
  if (!Number.isFinite(hpValue)) return null;
  return {
    hp: Math.max(0, hpValue),
    maxHp: Number.isFinite(maxValue) ? Math.max(1, maxValue) : null,
  };
}

function replaySideState(
  report: ReportData,
  localization: LocalizationCatalog | null,
  visibleEvents: BattleEvent[],
  sideIndex: number,
  currentTurn: number,
) {
  const team = report.scenario.sides[sideIndex].team.slice(0, 6);
  const firstSnapshot = report.battle.turnSnapshots?.[0]?.sides?.[sideIndex];
  const finalTeam = report.battle.finalState?.sides?.[sideIndex]?.team ?? [];
  const maxHpBySlot = team.map((pokemon, index) =>
    Math.max(
      1,
      Number(
        firstSnapshot?.team?.[index]?.maxHp ??
          finalTeam[index]?.maxHp ??
          pokemon.stats?.hp ??
          pokemon.baseStats?.hp ??
          1,
      ),
    ),
  );
  for (const event of report.battle.events) {
    const slot = eventPokemonSlot(report, localization, sideIndex, event);
    if (slot < 0) continue;
    const condition = conditionNumbers(event.condition);
    if (condition?.maxHp) {
      maxHpBySlot[slot] = Math.max(maxHpBySlot[slot], condition.maxHp);
    }
  }
  const hpBySlot = [...maxHpBySlot];
  let activeIndex = 0;
  let displaySpecies = team[0]?.species ?? "";

  const previousSnapshot = [...(report.battle.turnSnapshots ?? [])]
    .reverse()
    .find((snapshot) => snapshot.turn < currentTurn)?.sides?.[sideIndex];
  if (previousSnapshot) {
    activeIndex = Math.max(
      0,
      Math.min(team.length - 1, Number(previousSnapshot.active ?? 0)),
    );
    previousSnapshot.team.forEach((pokemon, index) => {
      if (index < hpBySlot.length) hpBySlot[index] = Math.max(0, pokemon.hp);
    });
    displaySpecies = team[activeIndex]?.species ?? displaySpecies;
  }

  for (const event of visibleEvents) {
    if (event.type === "switch" && eventSideIndex(event) === sideIndex) {
      const nextSlot = eventPokemonSlot(report, localization, sideIndex, event);
      if (nextSlot >= 0) activeIndex = nextSlot;
      displaySpecies = actorName(event.actor) || team[activeIndex]?.species || "";
    }
    if (
      event.type === "mega_evolution" &&
      eventSideIndex(event) === sideIndex
    ) {
      displaySpecies = actorName(event.actor) || displaySpecies;
    }

    const slot = eventPokemonSlot(report, localization, sideIndex, event);
    if (slot < 0) continue;
    if (
      ["switch", "damage", "damage_prevented", "heal", "faint"].includes(
        event.type,
      )
    ) {
      const condition = conditionNumbers(event.condition);
      if (condition) {
        hpBySlot[slot] = condition.hp;
        if (condition.maxHp) maxHpBySlot[slot] = condition.maxHp;
      } else if (event.type === "faint") {
        hpBySlot[slot] = 0;
      }
    }
  }

  const pokemon = team[activeIndex] ?? team[0];
  const hp = hpBySlot[activeIndex] ?? 0;
  const maxHp = maxHpBySlot[activeIndex] ?? 1;
  return {
    activeIndex,
    pokemon,
    displaySpecies: displaySpecies || pokemon?.species || "",
    hp,
    maxHp,
    hpPercent: Math.max(0, Math.min(100, (hp / maxHp) * 100)),
  };
}

function replayDelay(speed: EveReplaySpeed) {
  if (speed === "slow") return 800;
  if (speed === "fast") return 180;
  return 420;
}

function replayCandidateName(
  candidate: AiCandidate,
  localization: LocalizationCatalog | null,
) {
  const actionType = candidate.action?.type ?? candidate.type;
  if (actionType === "switch") {
    return `교체 · ${localSpecies(localization, candidate.id || candidate.name)}`;
  }
  if (actionType === "gimmick") return candidate.name;
  return localMove(localization, candidate.id || candidate.name);
}

function EveReplayDecisionRail({
  report,
  localization,
  sideIndex,
  turn,
  traces,
}: {
  report: ReportData;
  localization: LocalizationCatalog | null;
  sideIndex: number;
  turn: number;
  traces: AiTrace[];
}) {
  const side = report.scenario.sides[sideIndex];
  const visibleTraces = traces.slice(-2);

  return (
    <aside
      className={`eve-replay-decision side-${sideIndex === 0 ? "a" : "b"}`}
      aria-label={`${side.name} AI 판단`}
    >
      <header>
        <span>SIDE {sideIndex === 0 ? "A" : "B"} DECISION</span>
        <strong>{side.name}</strong>
        <small>{turn > 0 ? `TURN ${turn}` : "재생 대기"}</small>
      </header>
      {visibleTraces.length > 0 ? (
        visibleTraces.map((trace, traceIndex) => {
          const selected =
            trace.candidates.find((candidate) => candidate.selected) ?? null;
          const maximumScore = Math.max(
            1,
            ...trace.candidates.map((candidate) =>
              Math.max(0, Number(candidate.score ?? 0)),
            ),
          );
          return (
            <article key={`${trace.turn}-${trace.species}-${traceIndex}`}>
              <div className="eve-replay-decision-choice">
                <span>{localSpecies(localization, trace.species)}</span>
                <strong>
                  {selected
                    ? replayCandidateName(selected, localization)
                    : trace.chosenAction || "선택 기록 없음"}
                </strong>
                <small>
                  {difficultyNames[trace.difficulty] ?? trace.difficulty} ·{" "}
                  {strategyNames[trace.strategy] ?? trace.strategy}
                </small>
                {trace.winEstimate ? (
                  <small>
                    현재 승률 {trace.winEstimate.probabilityPercent.toFixed(1)}%
                    {" · "}신뢰도{" "}
                    {(trace.winEstimate.confidence * 100).toFixed(0)}%
                  </small>
                ) : null}
              </div>
              <div className="eve-replay-decision-candidates">
                {trace.candidates.slice(0, 4).map((candidate, index) => {
                  const score = Number(candidate.score ?? 0);
                  return (
                    <div
                      className={candidate.selected ? "selected" : ""}
                      key={`${candidate.slot}-${candidate.id}-${index}`}
                    >
                      <span>
                        <strong>
                          {replayCandidateName(candidate, localization)}
                        </strong>
                        <b>
                          {Number.isFinite(candidate.winProbabilityAfter)
                            ? `${(Number(candidate.winProbabilityAfter) * 100).toFixed(1)}%`
                            : score.toFixed(1)}
                        </b>
                      </span>
                      <i>
                        <b
                          style={{
                            width: `${Math.max(
                              2,
                              Math.min(
                                100,
                                (Math.max(0, score) / maximumScore) * 100,
                              ),
                            )}%`,
                          }}
                        />
                      </i>
                    </div>
                  );
                })}
              </div>
              <div className="eve-replay-decision-reasons">
                {(selected?.reasons ?? []).slice(0, 3).map((reason, index) => (
                  <p key={`${reason.code}-${index}`}>
                    <b>{reason.label}</b>
                    <span>{reason.message}</span>
                  </p>
                ))}
                {!selected?.reasons?.length && trace.reason ? (
                  <p>
                    <b>판단 근거</b>
                    <span>{trace.reason}</span>
                  </p>
                ) : null}
              </div>
            </article>
          );
        })
      ) : (
        <div className="eve-replay-decision-empty">
          <strong>{turn > 0 ? "판단 기록 없음" : "READY"}</strong>
          <span>
            {turn > 0
              ? "이 턴에 저장된 AI 후보 정보가 없습니다."
              : "재생하면 턴별 선택 근거가 표시됩니다."}
          </span>
        </div>
      )}
    </aside>
  );
}

function EveBattleReplay({
  report,
  localization,
}: {
  report: ReportData;
  localization: LocalizationCatalog | null;
}) {
  const replayEvents = useMemo(
    () =>
      report.battle.events.filter(
        (event) =>
          event.type !== "turn" && EVE_REPLAY_EVENT_TYPES.has(event.type),
      ),
    [report],
  );
  const [cursor, setCursor] = useState(-1);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState<EveReplaySpeed>("normal");
  const soundSettings = useSyncExternalStore(
    (onStoreChange) =>
      subscribeBattleAudioSettings(() => onStoreChange()),
    getBattleAudioSettings,
    getBattleAudioServerSettings,
  );
  const currentEvent = cursor >= 0 ? replayEvents[cursor] : null;
  const visibleEvents = replayEvents.slice(0, cursor + 1);
  const currentTurn = currentEvent?.turn ?? 0;
  const sideStates = [0, 1].map((sideIndex) =>
    replaySideState(
      report,
      localization,
      visibleEvents,
      sideIndex,
      currentTurn,
    ),
  );
  const recentMessages = visibleEvents
    .filter((event) => event.turn === currentTurn)
    .slice(-5)
    .map((event) => eveBattleDialogue(event, localization));
  const replayTraceMap = useMemo(() => traceByTurnAndSide(report), [report]);
  const currentTraces = [0, 1].map(
    (sideIndex) => replayTraceMap.get(`${currentTurn}:${sideIndex}`) ?? [],
  );

  useEffect(() => {
    if (!playing) return;
    if (cursor >= replayEvents.length - 1) return;
    const timer = window.setTimeout(
      () => {
        setCursor((current) => {
          const next = Math.min(replayEvents.length - 1, current + 1);
          if (next >= replayEvents.length - 1) setPlaying(false);
          return next;
        });
      },
      replayDelay(speed),
    );
    return () => window.clearTimeout(timer);
  }, [cursor, playing, replayEvents.length, speed]);

  useEffect(() => {
    if (!soundSettings.sfxEnabled || !currentEvent) return;
    void playBattleSoundEffects([
      { type: currentEvent.type, detail: currentEvent.detail },
    ]);
  }, [currentEvent, soundSettings]);

  const startPlayback = () => {
    if (replayEvents.length === 0) return;
    const nextCursor = cursor >= replayEvents.length - 1 ? 0 : cursor + 1;
    setCursor(nextCursor);
    setPlaying(nextCursor < replayEvents.length - 1);
  };

  return (
    <section className="eve-replay-panel" aria-label="EvE 대전 재생기">
      <header>
        <div>
          <span>BATTLE REPLAYER</span>
          <strong>EvE 대전 재생</strong>
          <small>
            이벤트 {Math.max(0, cursor + 1)}/{replayEvents.length} · 턴{" "}
            {cursor >= 0 ? currentTurn || "선봉" : "준비"}
          </small>
        </div>
        <div className="eve-replay-header-controls">
          <label>
            속도
            <select
              value={speed}
              onChange={(event) => setSpeed(event.target.value as EveReplaySpeed)}
            >
              <option value="slow">0.5x</option>
              <option value="normal">1x</option>
              <option value="fast">2x</option>
            </select>
          </label>
          <BattleAudioControl eventId="battle.pvp.default" compact />
        </div>
      </header>

      <div className="eve-replay-body">
        <EveReplayDecisionRail
          report={report}
          localization={localization}
          sideIndex={0}
          turn={currentTurn}
          traces={currentTraces[0]}
        />
        <div className="eve-replay-stage">
        {sideStates.map((state, sideIndex) => (
          <div
            className={`eve-replay-combatant side-${sideIndex === 0 ? "a" : "b"}`}
            key={sideIndex}
          >
            <article>
              <span>{sideIndex === 0 ? "SIDE A" : "SIDE B"}</span>
              <strong>
                {state.pokemon
                  ? localSpecies(localization, state.displaySpecies)
                  : "출전 대기"}
              </strong>
              <small>
                {state.hp}/{state.maxHp}
              </small>
              <i>
                <b style={{ width: `${state.hpPercent}%` }} />
              </i>
            </article>
            {state.pokemon ? (
              <ReportPokemonSprite
                species={state.displaySpecies}
                label={localSpecies(localization, state.displaySpecies)}
              />
            ) : null}
          </div>
        ))}

        <div className="eve-replay-turn">
          <span>TURN</span>
          <strong>{cursor >= 0 ? currentTurn || "LEAD" : "READY"}</strong>
        </div>

        <div className="eve-replay-message">
          <span>
            {currentEvent
              ? BATTLE_EVENT_NAMES[currentEvent.type] ?? currentEvent.type
              : "전투 준비"}
          </span>
          <strong>
            {currentEvent
              ? eveBattleDialogue(currentEvent, localization)
              : "재생 버튼을 누르면 대전이 시작됩니다."}
          </strong>
          <div>
            {recentMessages.slice(-3).map((message, index) => (
              <small key={`${cursor}-${index}`}>{message}</small>
            ))}
          </div>
        </div>

        <div className="eve-replay-parties">
          {report.scenario.sides.map((side, sideIndex) => (
            <div key={side.name}>
              {side.team.slice(0, 6).map((pokemon, index) => (
                <span
                  className={
                    sideStates[sideIndex].activeIndex === index ? "active" : ""
                  }
                  key={pokemon.slot}
                  title={localSpecies(localization, pokemon.species)}
                >
                  <ReportPokemonSprite
                    species={pokemon.species}
                    label={localSpecies(localization, pokemon.species)}
                  />
                </span>
              ))}
            </div>
          ))}
        </div>
        </div>
        <EveReplayDecisionRail
          report={report}
          localization={localization}
          sideIndex={1}
          turn={currentTurn}
          traces={currentTraces[1]}
        />
      </div>

      <footer className="eve-replay-controls">
        <button
          type="button"
          onClick={() => {
            setPlaying(false);
            setCursor(-1);
          }}
          disabled={cursor < 0}
          title="처음으로"
          aria-label="처음으로"
        >
          |◀
        </button>
        <button
          type="button"
          onClick={() => {
            setPlaying(false);
            setCursor((current) => Math.max(-1, current - 1));
          }}
          disabled={cursor < 0}
          title="이전 이벤트"
          aria-label="이전 이벤트"
        >
          ◀
        </button>
        <button
          type="button"
          className="primary"
          onClick={() => (playing ? setPlaying(false) : startPlayback())}
          disabled={replayEvents.length === 0}
        >
          {playing ? "일시정지" : "재생"}
        </button>
        <button
          type="button"
          onClick={() => {
            setPlaying(false);
            setCursor((current) =>
              Math.min(replayEvents.length - 1, current + 1),
            );
          }}
          disabled={cursor >= replayEvents.length - 1}
          title="다음 이벤트"
          aria-label="다음 이벤트"
        >
          ▶
        </button>
        <input
          type="range"
          min="-1"
          max={Math.max(-1, replayEvents.length - 1)}
          value={cursor}
          onChange={(event) => {
            setPlaying(false);
            setCursor(Number(event.target.value));
          }}
          aria-label="재생 위치"
        />
      </footer>
    </section>
  );
}

export default function EveReport() {
  const [history, setHistory] = useState<ReportData[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [localization, setLocalization] =
    useState<LocalizationCatalog | null>(null);
  const [seed, setSeed] = useState(0);
  const [profiles, setProfiles] = useState<AiProfile[]>([
    { difficulty: "expert", strategy: "balanced" },
    { difficulty: "expert", strategy: "balanced" },
  ]);
  const [running, setRunning] = useState(false);
  const [batchRunning, setBatchRunning] = useState(false);
  const [sweepRounds, setSweepRounds] = useState(3);
  const [sweepMode, setSweepMode] = useState<"fast" | "exact">("fast");
  const [sweepParallelism, setSweepParallelism] = useState(0);
  const [batchStatus, setBatchStatus] = useState("");
  const [sweepResult, setSweepResult] = useState<SweepResult | null>(null);
  const [error, setError] = useState("");

  const selected = useMemo(
    () => history.find((entry) => historyId(entry) === selectedId) ?? history[0] ?? null,
    [history, selectedId],
  );

  useEffect(() => {
    const restoreTimer = window.setTimeout(() => {
      const storedHistory = localStorage.getItem(EVE_HISTORY_KEY);
      const storedLatest = localStorage.getItem(EVE_REPORT_KEY);
      const restored: ReportData[] = [];
      try {
        if (storedHistory) restored.push(...(JSON.parse(storedHistory) as ReportData[]));
        if (storedLatest) {
          const latest = JSON.parse(storedLatest) as ReportData;
          if (!restored.some((entry) => entry.battle.battleId === latest.battle.battleId)) {
            restored.unshift(latest);
          }
        }
      } catch {
        setError("저장된 EVE 리포트를 읽지 못했습니다.");
      }
      if (restored.length > 0) {
        const nextHistory = compactHistoryForStorage(restored);
        setHistory(nextHistory);
        setSelectedId(historyId(nextHistory[0]));
        setSeed(nextHistory[0].scenario.seed);
        setProfiles(editableProfilesOf(nextHistory[0]));
        const stored = persistEveStorage(nextHistory, nextHistory[0]);
        if (stored.trimmed) {
          setError("저장 공간이 부족해 오래된 EvE 전적 일부를 정리했습니다.");
        }
      }
    }, 0);
    fetch("/data/cobblemon-ko-kr.json")
      .then((response) => response.json() as Promise<LocalizationCatalog>)
      .then(setLocalization)
      .catch(() => setLocalization(null));
    return () => window.clearTimeout(restoreTimer);
  }, []);

  const selectReport = (next: ReportData) => {
    setSelectedId(historyId(next));
    setSeed(next.scenario.seed);
    setProfiles(editableProfilesOf(next));
  };

  const saveRun = (next: ReportData) => {
    setHistory((current) => {
      const nextHistory = [
        next,
        ...current.filter((entry) => historyId(entry) !== historyId(next)),
      ];
      const stored = persistEveStorage(nextHistory, next);
      if (stored.trimmed) {
        setError("저장 공간이 부족해 오래된 EvE 전적 일부를 정리했습니다.");
      }
      return stored.history;
    });
    selectReport(next);
  };

  const selectHistory = (entry: ReportData) => {
    selectReport(entry);
  };

  const deleteHistory = (entry: ReportData) => {
    const nextHistory = history.filter((item) => historyId(item) !== historyId(entry));
    if (historyId(entry) === historyId(selected) && nextHistory[0]) {
      const stored = persistEveStorage(nextHistory, nextHistory[0]);
      setHistory(stored.history);
      selectReport(nextHistory[0]);
    } else {
      const stored = persistEveStorage(nextHistory, selected ?? nextHistory[0]);
      setHistory(stored.history);
    }
    if (nextHistory.length === 0) {
      localStorage.removeItem(EVE_REPORT_KEY);
      setSelectedId("");
    }
  };

  const clearHistory = () => {
    if (!window.confirm("EvE 전적을 모두 삭제할까요?")) return;
    setHistory([]);
    setSelectedId("");
    setSweepResult(null);
    localStorage.removeItem(EVE_REPORT_KEY);
    localStorage.removeItem(EVE_HISTORY_KEY);
  };

  const runBattleScenario = async (scenario: Scenario) => {
    const response = await fetch("/api/battles", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(scenario),
    });
    const result = (await response.json()) as
      | { ok: true; scenario?: Scenario; battle: Battle }
      | { ok: false; issues: Array<{ message: string }> };
    if (!result.ok) {
      throw new Error(result.issues[0]?.message ?? "대전을 실행하지 못했습니다.");
    }
    return {
      schemaVersion: 1,
      savedAt: new Date().toISOString(),
      scenario: result.scenario ?? scenario,
      battle: result.battle,
    };
  };

  const runBattleSummaries = async (
    scenario: Scenario,
    jobs: Array<{ seed: number; aiProfiles: AiProfile[] }>,
  ) => {
    const response = await fetch("/api/battle-sweep", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        scenario,
        jobs,
        concurrency: sweepParallelism,
      }),
    });
    const result = (await response.json()) as
      | {
          ok: true;
          results: SweepBattleSummary[];
          parallelism: number;
          executionMode: "worker_threads" | "sequential";
        }
      | { ok: false; issues: Array<{ message: string }> };
    if (!result.ok) {
      throw new Error(result.issues[0]?.message ?? "반복 전투를 실행하지 못했습니다.");
    }
    return result;
  };

  const scenarioWith = (nextSeed: number, nextProfiles = profiles) => {
    if (!selected) return null;
    return {
      ...selected.scenario,
      seed: nextSeed,
      aiDifficulty: nextProfiles[0]?.difficulty ?? selected.scenario.aiDifficulty,
      aiProfiles: nextProfiles,
    };
  };

  const rerun = async (nextSeed: number, nextProfiles = profiles) => {
    const scenario = scenarioWith(nextSeed, nextProfiles);
    if (!scenario || !Number.isInteger(nextSeed)) return;
    setRunning(true);
    setError("");
    try {
      saveRun(await runBattleScenario(scenario));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "전투 API에 연결하지 못했습니다.");
    } finally {
      setRunning(false);
    }
  };

  const runStrategySweep = async () => {
    if (!selected) return;
    const rounds = Math.max(1, Math.min(20, Math.floor(sweepRounds)));
    const baseSeed = Number.isInteger(seed) ? seed : selected.scenario.seed;
    const sideA = new Map<AiProfile["strategy"], { games: number; wins: number }>();
    const sideB = new Map<AiProfile["strategy"], { games: number; wins: number }>();
    const finalistA = new Map<AiProfile["strategy"], { games: number; wins: number }>();
    const finalistB = new Map<AiProfile["strategy"], { games: number; wins: number }>();
    const matchups = new Map<string, SweepMatchup>();
    const totalBattles =
      (sweepMode === "exact"
        ? strategyValues.length * strategyValues.length
        : strategyValues.length * 2 - 1 + 4) * rounds;

    for (const strategy of strategyValues) {
      sideA.set(strategy, { games: 0, wins: 0 });
      sideB.set(strategy, { games: 0, wins: 0 });
    }

    setBatchRunning(true);
    setBatchStatus(`0/${totalBattles} 전투 실행 중`);
    setError("");
    try {
      let completed = 0;
      let usedParallelism = 1;
      let executionMode: SweepResult["executionMode"] = "sequential";
      type MatchupPlan = {
        strategyA: AiProfile["strategy"];
        strategyB: AiProfile["strategy"];
        score: { sideA?: boolean; sideB?: boolean; finalist?: boolean };
      };
      const runMatchups = async (plans: MatchupPlan[]) => {
        const executions = plans.flatMap((plan) => {
          const matchupKey = `${plan.strategyA}:${plan.strategyB}`;
          if (!matchups.has(matchupKey)) {
            matchups.set(matchupKey, {
              strategyA: plan.strategyA,
              strategyB: plan.strategyB,
              games: 0,
              winsA: 0,
              winsB: 0,
            });
          }
          return Array.from({ length: rounds }, (_, round) => ({
            ...plan,
            matchupKey,
            seed: baseSeed + round,
            aiProfiles: [
              {
                ...(profiles[0] ?? { difficulty: "expert" }),
                strategy: plan.strategyA,
              },
              {
                ...(profiles[1] ?? { difficulty: "expert" }),
                strategy: plan.strategyB,
              },
            ] as AiProfile[],
          }));
        });
        const baseScenario = scenarioWith(baseSeed, profiles);
        if (!baseScenario) {
          throw new Error("반복 전투의 기준 시나리오를 찾지 못했습니다.");
        }
        const sweep = await runBattleSummaries(
          baseScenario,
          executions.map((execution) => ({
            seed: execution.seed,
            aiProfiles: execution.aiProfiles,
          })),
        );
        const battles = sweep.results;
        usedParallelism = Math.max(usedParallelism, sweep.parallelism);
        executionMode = sweep.executionMode;
        for (const [index, execution] of executions.entries()) {
          const battle = battles[index];
          if (!battle) {
            throw new Error("반복 전투 결과 수가 요청과 일치하지 않습니다.");
          }
          const winnerSide =
            battle.winner === baseScenario.sides[0].name
              ? 0
              : battle.winner === baseScenario.sides[1].name
                ? 1
                : -1;
          const scoreA = execution.score.sideA
            ? sideA.get(execution.strategyA)
            : undefined;
          const scoreB = execution.score.sideB
            ? sideB.get(execution.strategyB)
            : undefined;
          const finalA = execution.score.finalist
            ? finalistA.get(execution.strategyA)
            : undefined;
          const finalB = execution.score.finalist
            ? finalistB.get(execution.strategyB)
            : undefined;
          const matchup = matchups.get(execution.matchupKey);
          if (scoreA) scoreA.games += 1;
          if (scoreB) scoreB.games += 1;
          if (finalA) finalA.games += 1;
          if (finalB) finalB.games += 1;
          if (matchup) matchup.games += 1;
          if (winnerSide === 0) {
            if (scoreA) scoreA.wins += 1;
            if (finalA) finalA.wins += 1;
            if (matchup) matchup.winsA += 1;
          }
          if (winnerSide === 1) {
            if (scoreB) scoreB.wins += 1;
            if (finalB) finalB.wins += 1;
            if (matchup) matchup.winsB += 1;
          }

          completed += 1;
        }
        setBatchStatus(
          `${completed}/${totalBattles} 전투 실행 중 · ${usedParallelism}코어`,
        );
      };

      if (sweepMode === "exact") {
        await runMatchups(
          strategyValues.flatMap((strategyA) =>
            strategyValues.map((strategyB) => ({
              strategyA,
              strategyB,
              score: { sideA: true, sideB: true },
            })),
          ),
        );
      } else {
        await runMatchups([
          ...strategyValues.map((strategy) => ({
            strategyA: strategy,
            strategyB: "balanced" as const,
            score: { sideA: true, sideB: strategy === "balanced" },
          })),
          ...strategyValues
            .filter((strategy) => strategy !== "balanced")
            .map((strategy) => ({
              strategyA: "balanced" as const,
              strategyB: strategy,
              score: { sideB: true },
            })),
        ]);
      }

      const toScores = (map: Map<AiProfile["strategy"], { games: number; wins: number }>) =>
        Array.from(map.entries())
          .map(([strategy, score]) => ({
            strategy,
            games: score.games,
            wins: score.wins,
            winRate: score.games > 0 ? score.wins / score.games : 0,
          }))
          .sort((a, b) => b.winRate - a.winRate || b.wins - a.wins);
      let sideAScores = toScores(sideA);
      let sideBScores = toScores(sideB);
      let bestA = sideAScores[0] ?? null;
      let bestB = sideBScores[0] ?? null;
      if (sweepMode === "fast") {
        const topA = sideAScores.slice(0, 2);
        const topB = sideBScores.slice(0, 2);
        for (const score of topA) {
          finalistA.set(score.strategy, { games: 0, wins: 0 });
        }
        for (const score of topB) {
          finalistB.set(score.strategy, { games: 0, wins: 0 });
        }
        await runMatchups(
          topA.flatMap((scoreA) =>
            topB.map((scoreB) => ({
              strategyA: scoreA.strategy,
              strategyB: scoreB.strategy,
              score: { finalist: true },
            })),
          ),
        );
        const finalistAScores = toScores(finalistA);
        const finalistBScores = toScores(finalistB);
        bestA = finalistAScores[0] ?? bestA;
        bestB = finalistBScores[0] ?? bestB;
        sideAScores = [
          ...finalistAScores,
          ...sideAScores.filter((score) => !finalistA.has(score.strategy)),
        ];
        sideBScores = [
          ...finalistBScores,
          ...sideBScores.filter((score) => !finalistB.has(score.strategy)),
        ];
      }
      const bestProfiles: AiProfile[] = [
        {
          ...(profiles[0] ?? { difficulty: "expert" }),
          strategy: bestA?.strategy ?? "balanced",
        },
        {
          ...(profiles[1] ?? { difficulty: "expert" }),
          strategy: bestB?.strategy ?? "balanced",
        },
      ];
      const representativeScenario = scenarioWith(baseSeed, bestProfiles);
      if (representativeScenario) {
        setBatchStatus(`${totalBattles}/${totalBattles} 전투 완료 · 대표 로그 생성 중`);
        saveRun(await runBattleScenario(representativeScenario));
      }
      setSweepResult({
        baseSeed,
        rounds,
        totalBattles,
        parallelism: usedParallelism,
        executionMode,
        bestA,
        bestB,
        sideA: sideAScores,
        sideB: sideBScores,
        matchups: Array.from(matchups.values()).sort(
          (a, b) => b.winsA + b.winsB - (a.winsA + a.winsB),
        ),
      });
      setBatchStatus(`${totalBattles}/${totalBattles} 전투 완료`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "성향 자동 분석을 실행하지 못했습니다.");
    } finally {
      setBatchRunning(false);
    }
  };

  const download = () => {
    if (!selected) return;
    const blob = new Blob([`${JSON.stringify(selected, null, 2)}\n`], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${selected.battle.battleId}-report.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const plainLog = useMemo(
    () => (selected ? battlePlainText(selected, localization) : ""),
    [selected, localization],
  );
  const latestTraceBySide = useMemo(
    () =>
      [0, 1].map((side) =>
        [...(selected?.battle.aiTrace ?? [])]
          .reverse()
          .find((trace) => trace.side === side),
      ),
    [selected],
  );

  if (!selected) {
    return (
      <main className="eve-report-page eve-report-empty">
        <p className="eyebrow">EVE ANALYSIS REPORT</p>
        <h1>아직 자동대전 리포트가 없습니다.</h1>
        <p>{error || "메인 화면에서 EVE 자동대전을 먼저 실행해 주세요."}</p>
        <Link href="/">전투 도구로 돌아가기</Link>
      </main>
    );
  }

  return (
    <main className="eve-report-page eve-report-compact">
      <nav className="eve-report-nav">
        <Link className="eve-report-brand" href="/">
          <span>CV</span>
          <strong>Cobbleverse Battle Lab</strong>
        </Link>
        <div className="eve-report-tabs">
          <Link href="/">배틀 준비</Link>
          <strong>EvE 리포트</strong>
        </div>
        <div className="eve-report-actions">
          <button onClick={download}>JSON</button>
          <button onClick={() => navigator.clipboard.writeText(plainLog)}>로그 복사</button>
          <button onClick={clearHistory}>전적 전체 삭제</button>
        </div>
      </nav>

      <section className="eve-compact-top">
        <div>
          <p className="eyebrow">EVE REPORT</p>
          <h1>
            {selected.scenario.sides[0].name} vs {selected.scenario.sides[1].name}
          </h1>
          <p>
            승자: {selected.battle.winner ?? "무승부"} · {selected.battle.turns}턴 ·
            seed {selected.scenario.seed} · {selected.battle.engine.id}
          </p>
        </div>
        <div className="eve-run-controls">
          <label>
            seed
            <input
              type="number"
              value={seed}
              onChange={(event) => setSeed(Number(event.target.value))}
            />
          </label>
          <button disabled={running || batchRunning} onClick={() => rerun(seed)}>
            같은 조건 재대전
          </button>
          <button disabled={running || batchRunning} onClick={() => rerun(selected.scenario.seed + 1)}>
            다음 시드
          </button>
          <button
            disabled={running || batchRunning}
            onClick={() =>
              rerun(Math.floor(1_000_000_000 + Math.random() * 1_000_000_000))
            }
          >
            랜덤 시드
          </button>
        </div>
      </section>

      <section className="eve-match-summary" aria-label="선택한 EvE 대전 요약">
        {selected.scenario.sides.map((side, sideIndex) => {
          const profile = profilesOf(selected)[sideIndex];
          return (
            <article className={sideIndex === 0 ? "side-a" : "side-b"} key={side.name}>
              <header>
                <span>SIDE {sideIndex === 0 ? "A" : "B"}</span>
                <div>
                  <strong>{side.name}</strong>
                  <small>
                    {difficultyNames[profile?.difficulty]} · {strategyNames[profile?.strategy]}
                  </small>
                </div>
              </header>
              <div>
                {side.team.slice(0, 6).map((pokemon) => (
                  <span key={pokemon.slot} title={localSpecies(localization, pokemon.species)}>
                    <ReportPokemonSprite
                      species={pokemon.species}
                      label={localSpecies(localization, pokemon.species)}
                    />
                    <small>Lv.{pokemon.level}</small>
                  </span>
                ))}
              </div>
            </article>
          );
        })}
        <div className="eve-match-result">
          <span>WINNER</span>
          <strong>{selected.battle.winner ?? "DRAW"}</strong>
          <small>
            {selected.battle.turns}턴 · {(selected.battle.durationMs / 1000).toFixed(2)}초
          </small>
        </div>
      </section>

      <EveBattleReplay
        key={selected.battle.battleId}
        report={selected}
        localization={localization}
      />

      <section className="eve-strategy-strip">
        {selected.scenario.sides.map((side, sideIndex) => (
          <article key={side.name}>
            <strong>{sideIndex === 0 ? "1P" : "2P"} {side.name}</strong>
            <span>{compactTeamLine(localization, side)}</span>
            <label>
              난이도
              <select
                value={profiles[sideIndex]?.difficulty ?? "expert"}
                onChange={(event) => {
                  const next = [...profiles];
                  const difficulty = event.target
                    .value as AiProfile["difficulty"];
                  next[sideIndex] = {
                    ...(next[sideIndex] ?? { strategy: "balanced" }),
                    difficulty,
                  };
                  const otherSideIndex = sideIndex === 0 ? 1 : 0;
                  if (
                    difficulty === "cheater" &&
                    next[otherSideIndex]?.difficulty === "cheater"
                  ) {
                    next[otherSideIndex] = {
                      ...next[otherSideIndex],
                      difficulty: "expert",
                      cheatProbability: undefined,
                    };
                  }
                  setProfiles(next);
                }}
              >
                {Object.entries(difficultyNames).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
            <label>
              성향
              <select
                value={profiles[sideIndex]?.strategy ?? "balanced"}
                onChange={(event) => {
                  const next = [...profiles];
                  next[sideIndex] = {
                    ...(next[sideIndex] ?? { difficulty: "expert" }),
                    strategy: event.target.value as AiProfile["strategy"],
                  };
                  setProfiles(next);
                }}
              >
                {Object.entries(strategyNames).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
            {profiles[sideIndex]?.difficulty === "cheater" ? (
              <label>
                행동 열람 {Math.round((profiles[sideIndex]?.cheatProbability ?? 0.5) * 100)}%
                <input
                  type="range"
                  min={0}
                  max={100}
                  step={5}
                  value={Math.round(
                    (profiles[sideIndex]?.cheatProbability ?? 0.5) * 100,
                  )}
                  onChange={(event) => {
                    const next = [...profiles];
                    next[sideIndex] = {
                      ...(next[sideIndex] ?? {
                        difficulty: "cheater",
                        strategy: "balanced",
                      }),
                      cheatProbability: Number(event.target.value) / 100,
                    };
                    setProfiles(next);
                  }}
                />
              </label>
            ) : null}
          </article>
        ))}
        <button disabled={running || batchRunning} onClick={() => rerun(selected.scenario.seed, profiles)}>
          같은 시드로 전략 비교
        </button>
      </section>

      <section className="eve-sweep-panel">
        <div>
          <strong>성향 자동 분석</strong>
          <span>
            {sweepMode === "fast"
              ? "모든 성향을 균형형과 비교한 뒤 상위 2개씩 다시 대결합니다."
              : `1P/2P 성향 조합 ${strategyValues.length * strategyValues.length}개를 모두 대결합니다.`}
          </span>
        </div>
        <label>
          분석 방식
          <select
            value={sweepMode}
            onChange={(event) => setSweepMode(event.target.value as "fast" | "exact")}
          >
            <option value="fast">빠른 분석 · 최대 19조합</option>
            <option value="exact">정밀 분석 · 64조합</option>
          </select>
        </label>
        <label>
          반복 시드 수
          <input
            type="number"
            min={1}
            max={20}
            value={sweepRounds}
            onChange={(event) => setSweepRounds(Number(event.target.value))}
          />
        </label>
        <label>
          CPU 병렬 실행
          <select
            value={sweepParallelism}
            onChange={(event) =>
              setSweepParallelism(Number(event.target.value))
            }
          >
            <option value={0}>자동</option>
            <option value={1}>1코어</option>
            <option value={2}>2코어</option>
            <option value={4}>4코어</option>
            <option value={8}>8코어</option>
          </select>
        </label>
        <button disabled={running || batchRunning} onClick={runStrategySweep}>
          {batchRunning ? batchStatus : "최고 승률 성향 찾기"}
        </button>
      </section>

      {sweepResult ? (
        <section className="eve-sweep-result">
          <header>
            <strong>
              분석 결과 · seed {sweepResult.baseSeed}부터 {sweepResult.rounds}회 반복 · {sweepResult.totalBattles}전 · {sweepResult.parallelism}코어
            </strong>
            <span>
              1P 최고 {sweepResult.bestA ? strategyNames[sweepResult.bestA.strategy] : "-"} ·
              2P 최고 {sweepResult.bestB ? strategyNames[sweepResult.bestB.strategy] : "-"}
            </span>
          </header>
          <div className="eve-sweep-grid">
            <div>
              <strong>1P 성향 승률</strong>
              {sweepResult.sideA.map((score) => (
                <span key={score.strategy}>
                  {strategyNames[score.strategy]} {score.wins}/{score.games} ({Math.round(score.winRate * 100)}%)
                </span>
              ))}
            </div>
            <div>
              <strong>2P 성향 승률</strong>
              {sweepResult.sideB.map((score) => (
                <span key={score.strategy}>
                  {strategyNames[score.strategy]} {score.wins}/{score.games} ({Math.round(score.winRate * 100)}%)
                </span>
              ))}
            </div>
          </div>
        </section>
      ) : null}

      {error ? <p className="eve-report-error">{error}</p> : null}

      <section className="eve-analysis-layout">
        <aside className="eve-history-panel">
          <header>
            <strong>전체 전적</strong>
            <span>{history.length}전</span>
          </header>
          <div className="eve-history-list">
            {history.map((entry, index) => {
              const active = historyId(entry) === historyId(selected);
              const entryProfiles = profilesOf(entry);
              const winner = winnerSummary(entry);
              return (
                <div
                  key={historyId(entry)}
                  className={`eve-history-entry${active ? " active" : ""}`}
                >
                  <button
                    className="eve-history-main"
                    onClick={() => selectHistory(entry)}
                  >
                    <span>#{history.length - index}</span>
                    <strong className="eve-history-winner">
                      <b>{winner.side}</b>
                      {winner.label}
                    </strong>
                    <small>
                      seed {entry.scenario.seed} · {entry.battle.turns}턴 ·{" "}
                      {strategyNames[entryProfiles[0]?.strategy]} vs{" "}
                      {strategyNames[entryProfiles[1]?.strategy]}
                    </small>
                  </button>
                  <button
                    className="eve-history-delete"
                    onClick={() => deleteHistory(entry)}
                  >
                    삭제
                  </button>
                </div>
              );
            })}
          </div>
        </aside>

        <section className="eve-plain-log">
          <header>
            <strong>대전 과정</strong>
            <span>
              {difficultyNames[profilesOf(selected)[0]?.difficulty]} {strategyNames[profilesOf(selected)[0]?.strategy]} vs{" "}
              {difficultyNames[profilesOf(selected)[1]?.difficulty]} {strategyNames[profilesOf(selected)[1]?.strategy]}
            </span>
          </header>
          <pre>{plainLog}</pre>
        </section>

        <aside className="eve-ai-detail-panel">
          <header>
            <strong>AI 판단 상세</strong>
            <span>최근 턴 기준</span>
          </header>
          {latestTraceBySide.map((trace, sideIndex) => (
            <article className={sideIndex === 0 ? "side-a" : "side-b"} key={sideIndex}>
              <div className="eve-ai-choice">
                <span>{sideIndex === 0 ? "SIDE A" : "SIDE B"}</span>
                <strong>{trace?.chosenAction ?? "판단 기록 없음"}</strong>
                <small>
                  {trace
                    ? `T${trace.turn} · ${localSpecies(localization, trace.species)}${
                        trace.winEstimate
                          ? ` · 현재 승률 ${trace.winEstimate.probabilityPercent.toFixed(1)}%`
                          : ""
                      }`
                    : "AI 후보 정보가 없습니다."}
                </small>
              </div>
              {trace ? (
                <>
                  <p>{trace.reason}</p>
                  {trace.diagnostics?.cheatActivated ? (
                    <p className="eve-ai-policy-comparison">
                      치터 발동 · 상대 확정 행동만 반영한 전문가 판단
                    </p>
                  ) : null}
                  {trace.policyComparison?.differs ? (
                    <p className="eve-ai-policy-comparison">
                      휴리스틱 선택 {trace.policyComparison.heuristicAction}{" "}
                      {(trace.policyComparison.heuristicWinProbability * 100).toFixed(1)}%
                      {" · "}승률 최상 {trace.policyComparison.winProbabilityAction}{" "}
                      {(trace.policyComparison.winProbability * 100).toFixed(1)}%
                      {" · "}차이 +{(trace.policyComparison.probabilityGap * 100).toFixed(1)}%p
                    </p>
                  ) : null}
                  <div className="eve-ai-candidates">
                    {trace.candidates.slice(0, 4).map((candidate) => {
                      const score = Number(candidate.score ?? 0);
                      const maximum = Math.max(
                        1,
                        ...trace.candidates.map((entry) =>
                          Math.max(0, Number(entry.score ?? 0)),
                        ),
                      );
                      return (
                        <div className={candidate.selected ? "selected" : ""} key={`${candidate.slot}-${candidate.name}`}>
                          <span>
                            <strong>{candidate.name}</strong>
                            <b>
                              {Number.isFinite(candidate.winProbabilityAfter)
                                ? `${(Number(candidate.winProbabilityAfter) * 100).toFixed(1)}%`
                                : score.toFixed(1)}
                            </b>
                          </span>
                          <i style={{ width: `${Math.max(3, Math.min(100, (Math.max(0, score) / maximum) * 100))}%` }} />
                          <small>
                            {candidate.reasons?.[0]?.message ?? "세부 판단 근거 없음"}
                          </small>
                        </div>
                      );
                    })}
                  </div>
                </>
              ) : null}
            </article>
          ))}
        </aside>
      </section>

      {selected.battle.warnings.length > 0 ? (
        <section className="eve-report-warnings">
          <h2>경고</h2>
          {selected.battle.warnings.map((warning, index) => (
            <p key={index}>{warning.message}</p>
          ))}
        </section>
      ) : null}
    </main>
  );
}
