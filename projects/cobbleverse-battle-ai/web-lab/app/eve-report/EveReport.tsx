"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";

import { localizedSpeciesName } from "../../lib/species-localization.mjs";

const EVE_REPORT_KEY = "cobbleverse-battle-lab:eve-report";

type AiProfile = {
  difficulty: "novice" | "standard" | "advanced" | "expert" | "cheater";
  strategy: "balanced" | "aggressive" | "defensive" | "unpredictable";
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
    team: Array<{ slot: number; species: string; level: number }>;
  }>;
};

type AiCandidate = {
  slot: number;
  id?: string;
  name: string;
  type?: string;
  category?: string;
  power?: number;
  accuracy?: number | true;
  priority?: number;
  score?: number;
  selected: boolean;
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
  aiTrace?: AiTrace[];
  events: Array<{
    turn: number;
    type: string;
    actor?: string;
    detail?: string;
    condition?: string;
    source?: string;
  }>;
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
  balanced: "균형형",
  aggressive: "공격형",
  defensive: "안정형",
  unpredictable: "변칙형",
};

function id(value: string | undefined) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

function actorName(value: string | undefined) {
  return String(value ?? "").replace(/^p[12][a-z]?: /, "");
}

function eventLabel(type: string) {
  const labels: Record<string, string> = {
    switch: "교체",
    move: "기술",
    damage: "피해",
    heal: "회복",
    faint: "기절",
    status: "상태 이상",
    status_cured: "상태 회복",
    super_effective: "효과가 굉장함",
    resisted: "효과가 별로임",
    critical: "급소",
    miss: "빗나감",
    win: "승리",
    tie: "무승부",
  };
  return labels[type] ?? type;
}

export default function EveReport() {
  const [report, setReport] = useState<ReportData | null>(null);
  const [localization, setLocalization] =
    useState<LocalizationCatalog | null>(null);
  const [seed, setSeed] = useState(0);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const restoreTimer = window.setTimeout(() => {
      const stored = localStorage.getItem(EVE_REPORT_KEY);
      if (!stored) return;
      try {
        const parsed = JSON.parse(stored) as ReportData;
        setReport(parsed);
        setSeed(parsed.scenario.seed);
      } catch {
        setError("저장된 EVE 리포트를 읽지 못했습니다.");
      }
    }, 0);
    fetch("/data/cobblemon-ko-kr.json")
      .then((response) => response.json() as Promise<LocalizationCatalog>)
      .then(setLocalization)
      .catch(() => setLocalization(null));
    return () => window.clearTimeout(restoreTimer);
  }, []);

  const profiles = report?.scenario.aiProfiles ??
    report?.battle.settings?.aiProfiles ?? [
      {
        difficulty: report?.scenario.aiDifficulty ?? "standard",
        strategy: "balanced",
      },
      {
        difficulty: report?.scenario.aiDifficulty ?? "standard",
        strategy: "balanced",
      },
    ];

  const tracesBySide = useMemo(
    () => [
      report?.battle.aiTrace?.filter((entry) => entry.side === 0) ?? [],
      report?.battle.aiTrace?.filter((entry) => entry.side === 1) ?? [],
    ],
    [report],
  );

  const rerun = async (nextSeed: number) => {
    if (!report || !Number.isInteger(nextSeed)) return;
    setRunning(true);
    setError("");
    try {
      const response = await fetch("/api/battles", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ ...report.scenario, seed: nextSeed }),
      });
      const result = (await response.json()) as
        | { ok: true; scenario: Scenario; battle: Battle }
        | { ok: false; issues: Array<{ message: string }> };
      if (!result.ok) {
        setError(result.issues[0]?.message ?? "재대전을 실행하지 못했습니다.");
        return;
      }
      const next = {
        schemaVersion: 1,
        savedAt: new Date().toISOString(),
        scenario: result.scenario,
        battle: result.battle,
      };
      localStorage.setItem(EVE_REPORT_KEY, JSON.stringify(next));
      setReport(next);
      setSeed(result.scenario.seed);
    } catch {
      setError("전투 API에 연결하지 못했습니다.");
    } finally {
      setRunning(false);
    }
  };

  const download = () => {
    if (!report) return;
    const blob = new Blob([`${JSON.stringify(report, null, 2)}\n`], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${report.battle.battleId}-report.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  if (!report) {
    return (
      <main className="eve-report-page eve-report-empty">
        <p className="eyebrow">EVE ANALYSIS REPORT</p>
        <h1>표시할 자동대전 리포트가 없습니다.</h1>
        <p>{error || "메인 화면에서 EVE 자동대전을 먼저 실행해 주세요."}</p>
        <Link href="/">전투 연구실로 돌아가기</Link>
      </main>
    );
  }

  return (
    <main className="eve-report-page">
      <nav className="eve-report-nav">
        <Link href="/">← 전투 연구실</Link>
        <button onClick={download}>리포트 JSON 다운로드</button>
      </nav>

      <header className="eve-report-hero">
        <div>
          <p className="eyebrow">EVE ANALYSIS REPORT</p>
          <h1>{report.battle.winner ? `${report.battle.winner} 승리` : "무승부"}</h1>
          <p>
            {report.scenario.sides[0].name} vs {report.scenario.sides[1].name}
          </p>
        </div>
        <div className="eve-report-score">
          <strong>{report.battle.turns}</strong>
          <span>진행 턴</span>
        </div>
      </header>

      <section className="eve-report-metrics">
        <article><span>SEED</span><strong>{report.scenario.seed}</strong></article>
        <article><span>ENGINE</span><strong>{report.battle.engine.id}</strong></article>
        <article><span>FORMAT</span><strong>{report.battle.engine.format}</strong></article>
        <article><span>RUNTIME</span><strong>{report.battle.durationMs} ms</strong></article>
      </section>

      <section className="eve-rematch-panel">
        <div>
          <p className="eyebrow">REPRODUCIBLE REMATCH</p>
          <h2>시드를 바꿔 다시 대전</h2>
          <p>파티와 AI 설정은 유지하고 난수 흐름만 변경합니다.</p>
        </div>
        <label>
          <span>새 시드</span>
          <input
            type="number"
            value={seed}
            onChange={(event) => setSeed(Number(event.target.value))}
          />
        </label>
        <button onClick={() => setSeed(report.scenario.seed + 1)}>다음 시드</button>
        <button
          onClick={() =>
            setSeed(Math.floor(1_000_000_000 + Math.random() * 1_000_000_000))
          }
        >
          무작위 시드
        </button>
        <button className="primary-action" disabled={running} onClick={() => rerun(seed)}>
          {running ? "재대전 진행 중" : "이 시드로 재대전"}
        </button>
      </section>
      {error ? <p className="eve-report-error">{error}</p> : null}

      <section className="eve-ai-comparison">
        {report.scenario.sides.map((side, sideIndex) => (
          <article key={side.name}>
            <div className="eve-ai-heading">
              <span>AI {sideIndex === 0 ? "A" : "B"}</span>
              <div>
                <h2>{side.name}</h2>
                <p>
                  {difficultyNames[profiles[sideIndex]?.difficulty]} ·{" "}
                  {strategyNames[profiles[sideIndex]?.strategy]}
                </p>
              </div>
            </div>
            <div className="eve-report-team">
              {side.team.map((pokemon) => (
                <div key={pokemon.slot}>
                  <strong>{localizedSpeciesName(localization, pokemon.species)}</strong>
                  <span>Lv.{pokemon.level}</span>
                </div>
              ))}
            </div>
            <dl className="eve-ai-stats">
              <div><dt>판단 횟수</dt><dd>{tracesBySide[sideIndex].length}</dd></div>
              <div>
                <dt>공격 선택</dt>
                <dd>{tracesBySide[sideIndex].filter((entry) => entry.kind === "move").length}</dd>
              </div>
              <div>
                <dt>교체 선택</dt>
                <dd>{tracesBySide[sideIndex].filter((entry) => entry.kind === "switch").length}</dd>
              </div>
            </dl>
          </article>
        ))}
      </section>

      <section className="eve-report-section">
        <div className="eve-section-heading">
          <div>
            <p className="eyebrow">DECISION EXPLAINABILITY</p>
            <h2>AI 판단 근거</h2>
          </div>
          <p>각 항목을 열면 후보 행동과 평가 점수를 확인할 수 있습니다.</p>
        </div>
        <div className="eve-trace-columns">
          {report.scenario.sides.map((side, sideIndex) => (
            <div key={side.name}>
              <h3>{side.name}</h3>
              {tracesBySide[sideIndex].length === 0 ? (
                <p className="eve-empty-copy">이 엔진에서 제공된 판단 로그가 없습니다.</p>
              ) : (
                tracesBySide[sideIndex].map((trace, index) => (
                  <details key={`${trace.turn}-${index}`} className="eve-decision">
                    <summary>
                      <span>판단 {index + 1}</span>
                      <strong>
                        {localizedSpeciesName(localization, trace.species)} →{" "}
                        {localization?.moves[id(trace.chosenAction)]?.name ??
                          trace.chosenAction}
                      </strong>
                    </summary>
                    <p>{trace.reason}</p>
                    <div className="eve-candidate-table">
                      {trace.candidates.map((candidate) => (
                        <div
                          className={candidate.selected ? "selected" : ""}
                          key={`${candidate.slot}-${candidate.name}`}
                        >
                          <span>{candidate.selected ? "선택" : `#${candidate.slot}`}</span>
                          <strong>
                            {localization?.moves[id(candidate.id ?? candidate.name)]?.name ??
                              candidate.name}
                          </strong>
                          <small>
                            점수 {candidate.score ?? "—"} · 위력 {candidate.power ?? "—"} ·
                            우선도 {candidate.priority ?? 0}
                          </small>
                        </div>
                      ))}
                    </div>
                  </details>
                ))
              )}
            </div>
          ))}
        </div>
      </section>

      <section className="eve-report-section">
        <div className="eve-section-heading">
          <div>
            <p className="eyebrow">BATTLE TIMELINE</p>
            <h2>전투 진행 기록</h2>
          </div>
          <p>총 {report.battle.events.length}개의 판정 이벤트</p>
        </div>
        <div className="eve-event-table">
          {report.battle.events
            .filter((event) => event.type !== "turn")
            .map((event, index) => (
              <div key={`${event.turn}-${event.type}-${index}`}>
                <span>T{event.turn}</span>
                <strong>{eventLabel(event.type)}</strong>
                <p>
                  {localizedSpeciesName(localization, actorName(event.actor))}
                  {event.detail
                    ? ` · ${
                        localization?.moves[id(event.detail)]?.name ?? event.detail
                      }`
                    : ""}
                  {event.condition ? ` · ${event.condition}` : ""}
                  {event.source ? ` · 원인: ${event.source}` : ""}
                </p>
              </div>
            ))}
        </div>
      </section>

      {report.battle.warnings.length > 0 ? (
        <section className="eve-report-warnings">
          <h2>호환성 및 구현 경고</h2>
          {report.battle.warnings.map((warning, index) => (
            <p key={index}>{warning.message}</p>
          ))}
        </section>
      ) : null}
    </main>
  );
}
