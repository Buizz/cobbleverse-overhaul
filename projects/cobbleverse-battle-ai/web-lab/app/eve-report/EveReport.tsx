"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";

import {
  analyzeTeamProfile,
  teamRoleLabel,
} from "../../lib/common-battle-ai.mjs";
import { localizedSpeciesName } from "../../lib/species-localization.mjs";

const EVE_REPORT_KEY = "cobbleverse-battle-lab:eve-report";
const EVE_HISTORY_KEY = "cobbleverse-battle-lab:eve-history";
const EVE_HISTORY_LIMIT = 20;

type AiProfile = {
  difficulty: "novice" | "standard" | "advanced" | "expert" | "cheater";
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
  selected: boolean;
  reasons?: AiDecisionReason[];
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

type LocalizationCatalog = {
  species: Record<string, { name?: string }>;
  moves: Record<string, { name?: string }>;
};

const difficultyNames: Record<string, string> = {
  novice: "초급",
  standard: "보통",
  advanced: "상급",
  expert: "전문가",
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

function conditionHp(condition: string | undefined) {
  if (!condition) return "";
  if (condition.includes(" fnt")) return "0";
  return condition.split(" ")[0]?.split("/")[0] ?? condition;
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
        difficulty: report?.scenario.aiDifficulty ?? "standard",
        strategy: "balanced",
      },
      {
        difficulty: report?.scenario.aiDifficulty ?? "standard",
        strategy: "balanced",
      },
    ]
  );
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

function traceByTurnAndSide(report: ReportData | null) {
  const map = new Map<string, AiTrace[]>();
  for (const trace of report?.battle.aiTrace ?? []) {
    const key = `${trace.turn}:${trace.side}`;
    map.set(key, [...(map.get(key) ?? []), trace]);
  }
  return map;
}

function eventLine(
  event: BattleEvent,
  localization: LocalizationCatalog | null,
) {
  const side = actorSide(event.actor);
  const actor = localSpecies(localization, event.actor);
  const move = localMove(localization, event.detail);
  const hp = conditionHp(event.condition);
  const source = localMove(localization, event.source);

  if (event.type === "switch") {
    return `- ${side} ${actor} 등장`;
  }
  if (event.type === "move") {
    return `- ${side} ${actor}의 ${move}!`;
  }
  if (event.type === "damage") {
    return `  · ${actor} 피해${hp ? ` -> HP ${hp}` : ""}${source ? ` | 원인 ${source}` : ""}`;
  }
  if (event.type === "heal") {
    return `  · ${actor} 회복${hp ? ` -> HP ${hp}` : ""}${source ? ` | ${source}` : ""}`;
  }
  if (event.type === "damage_prevented") {
    return `  · ${actor} ${event.detail || source || "damage"}로 버텼다${event.condition ? ` | HP ${event.condition}` : ""}`;
  }
  if (event.type === "item_removed") {
    return `  · ${actor}의 ${event.detail || "item"}이(가) 소모됐다${source ? ` | 원인 ${source}` : ""}`;
  }
  if (event.type === "faint") {
    return `  · ${side} ${actor} 기절`;
  }
  if (event.type === "mega_evolution") {
    return `  · ${side} ${actor} 메가진화${event.detail ? ` -> ${event.detail}` : ""}`;
  }
  if (event.type === "dynamax_started") {
    return `  · ${side} ${actor} 다이맥스`;
  }
  if (event.type === "terastallized") {
    return `  · ${side} ${actor} 테라스탈${event.detail ? `(${event.detail})` : ""}`;
  }
  if (event.type === "z_power") {
    return `  · ${side} ${actor} Z파워`;
  }
  if (event.type === "stat_up" || event.type === "stat_down") {
    return `  · ${actor} ${event.detail} 랭크 ${event.type === "stat_up" ? "+" : "-"}${event.condition ?? "1"}`;
  }
  if (event.type === "super_effective") return "  · 효과가 굉장했다";
  if (event.type === "resisted") return "  · 효과가 별로였다";
  if (event.type === "critical") return "  · 급소에 맞았다";
  if (event.type === "miss") return `  · ${actor}의 공격은 빗나갔다`;
  if (event.type === "status") return `  · ${actor} 상태 이상: ${event.detail}`;
  if (event.type === "status_cured") return `  · ${actor} 상태 회복: ${event.detail}`;
  if (event.type === "field_started") {
    const layerText =
      Number.isFinite(event.layers) && Number(event.layers) > 0
        ? ` ${event.layers}층`
        : "";
    const durationText =
      Number.isFinite(event.duration) && Number(event.duration) > 0
        ? ` ${event.duration}턴`
        : "";
    return `  · ${move || event.detail} 시작${layerText}${durationText}`;
  }
  if (event.type === "field_ended") {
    return `  · ${move || event.detail} 종료`;
  }
  if (event.type === "win") return `- 전투 종료: ${actorName(event.actor)} 승리`;
  return `  · ${event.type}${event.detail ? ` | ${event.detail}` : ""}${hp ? ` | HP ${hp}` : ""}`;
}

function hpSnapshot(
  report: ReportData,
  localization: LocalizationCatalog | null,
  turn: number,
) {
  const hpBySide = report.scenario.sides.map(() => new Map<number, string>());
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
    const finalTeam = report.battle.finalState?.sides?.[sideIndex]?.team ?? [];
    const entries = side.team
      .slice(0, 6)
      .map((pokemon, pokemonIndex) => {
        const name = localSpecies(localization, pokemon.species);
        const finalPokemon = finalTeam[pokemonIndex];
        const fallbackHp =
          finalPokemon &&
          Number.isFinite(finalPokemon.maxHp) &&
          Number.isFinite(finalPokemon.hp)
            ? finalPokemon.fainted || Number(finalPokemon.hp) <= 0
              ? "0"
              : `${finalPokemon.hp}/${finalPokemon.maxHp}`
            : "";
        return `${name} ${hpBySide[sideIndex].get(pokemonIndex) || fallbackHp || "?"}`;
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
  const firstSwitches = events.filter((event) => event.type === "switch");
  if (firstSwitches.length > 0) {
    lines.push(
      `선봉/교체: ${firstSwitches
        .map((event) => `${actorSide(event.actor)} ${localSpecies(localization, event.actor || event.detail)}`)
        .join(" | ")}`,
    );
  }
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
      for (const candidate of trace.candidates.slice(0, 6)) {
        const marker = candidate.selected ? "*" : " ";
        const label =
          trace.kind === "switch"
            ? `교체 -> ${candidate.name}`
            : candidate.type === "gimmick" || candidate.action?.type === "gimmick"
              ? candidate.id === "gigantamax"
                ? "거다이맥스"
                : candidate.id === "dynamax"
                  ? "다이맥스"
                  : candidate.name
              : localMove(localization, candidate.id ?? candidate.name);
        lines.push(
          `    ${marker} ${label} | 점수 ${Number(candidate.score ?? 0).toFixed(2)}`,
        );
        for (const reason of (candidate.reasons ?? []).slice(0, candidate.selected ? 3 : 1)) {
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
          selected: candidate.selected,
          reasons: candidate.reasons?.slice(0, candidate.selected ? 3 : 1).map((reason) => ({
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

export default function EveReport() {
  const [history, setHistory] = useState<ReportData[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [localization, setLocalization] =
    useState<LocalizationCatalog | null>(null);
  const [seed, setSeed] = useState(0);
  const [profiles, setProfiles] = useState<AiProfile[]>([
    { difficulty: "standard", strategy: "balanced" },
    { difficulty: "standard", strategy: "aggressive" },
  ]);
  const [running, setRunning] = useState(false);
  const [batchRunning, setBatchRunning] = useState(false);
  const [sweepRounds, setSweepRounds] = useState(3);
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
        setProfiles([...profilesOf(nextHistory[0])]);
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
    setProfiles([...profilesOf(next)]);
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
    const reports: ReportData[] = [];
    const sideA = new Map<AiProfile["strategy"], { games: number; wins: number }>();
    const sideB = new Map<AiProfile["strategy"], { games: number; wins: number }>();
    const matchups = new Map<string, SweepMatchup>();
    const totalBattles = strategyValues.length * strategyValues.length * rounds;

    for (const strategy of strategyValues) {
      sideA.set(strategy, { games: 0, wins: 0 });
      sideB.set(strategy, { games: 0, wins: 0 });
    }

    setBatchRunning(true);
    setBatchStatus(`0/${totalBattles} 전투 실행 중`);
    setError("");
    try {
      let completed = 0;
      for (const strategyA of strategyValues) {
        for (const strategyB of strategyValues) {
          const matchupKey = `${strategyA}:${strategyB}`;
          matchups.set(matchupKey, {
            strategyA,
            strategyB,
            games: 0,
            winsA: 0,
            winsB: 0,
          });
          for (let round = 0; round < rounds; round += 1) {
            const nextProfiles: AiProfile[] = [
              { ...(profiles[0] ?? { difficulty: "standard" }), strategy: strategyA },
              { ...(profiles[1] ?? { difficulty: "standard" }), strategy: strategyB },
            ];
            const scenario = scenarioWith(baseSeed + round, nextProfiles);
            if (!scenario) return;
            const report = await runBattleScenario(scenario);
            reports.push(report);

            const winner = report.battle.winner;
            const winnerSide =
              winner === report.scenario.sides[0].name
                ? 0
                : winner === report.scenario.sides[1].name
                  ? 1
                  : -1;
            const scoreA = sideA.get(strategyA);
            const scoreB = sideB.get(strategyB);
            const matchup = matchups.get(matchupKey);
            if (scoreA) scoreA.games += 1;
            if (scoreB) scoreB.games += 1;
            if (matchup) matchup.games += 1;
            if (winnerSide === 0) {
              if (scoreA) scoreA.wins += 1;
              if (matchup) matchup.winsA += 1;
            }
            if (winnerSide === 1) {
              if (scoreB) scoreB.wins += 1;
              if (matchup) matchup.winsB += 1;
            }

            completed += 1;
            setBatchStatus(`${completed}/${totalBattles} 전투 실행 중`);
          }
        }
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
      const sideAScores = toScores(sideA);
      const sideBScores = toScores(sideB);
      const nextHistory = [
        ...[...reports].reverse(),
        ...history.filter(
          (entry) => !reports.some((report) => historyId(report) === historyId(entry)),
        ),
      ];
      const latestReport = reports.at(-1);
      const stored = persistEveStorage(nextHistory, latestReport);
      setHistory(stored.history);
      if (stored.trimmed) {
        setError("저장 공간이 부족해 오래된 EvE 전적 일부를 정리했습니다.");
      }
      if (latestReport) {
        selectReport(latestReport);
      }
      setSweepResult({
        baseSeed,
        rounds,
        totalBattles,
        bestA: sideAScores[0] ?? null,
        bestB: sideBScores[0] ?? null,
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
        <Link href="/">← 전투 도구</Link>
        <div>
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

      <section className="eve-strategy-strip">
        {selected.scenario.sides.map((side, sideIndex) => (
          <article key={side.name}>
            <strong>{sideIndex === 0 ? "1P" : "2P"} {side.name}</strong>
            <span>{compactTeamLine(localization, side)}</span>
            <label>
              난이도
              <select
                value={profiles[sideIndex]?.difficulty ?? "standard"}
                onChange={(event) => {
                  const next = [...profiles];
                  next[sideIndex] = {
                    ...(next[sideIndex] ?? { strategy: "balanced" }),
                    difficulty: event.target.value as AiProfile["difficulty"],
                  };
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
                    ...(next[sideIndex] ?? { difficulty: "standard" }),
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
            1P/2P 성향 조합 {strategyValues.length * strategyValues.length}개를 같은 시드 묶음으로 반복합니다.
          </span>
        </div>
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
        <button disabled={running || batchRunning} onClick={runStrategySweep}>
          {batchRunning ? batchStatus : "최고 승률 성향 찾기"}
        </button>
      </section>

      {sweepResult ? (
        <section className="eve-sweep-result">
          <header>
            <strong>
              분석 결과 · seed {sweepResult.baseSeed}부터 {sweepResult.rounds}회 반복 · {sweepResult.totalBattles}전
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
