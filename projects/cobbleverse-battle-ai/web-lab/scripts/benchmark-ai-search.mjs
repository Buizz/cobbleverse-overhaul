import { readFile } from "node:fs/promises";

import { runNativeScenarioBattle } from "../lib/native-scenario-runner.mjs";

const candidateDifficulty = String(process.argv[2] ?? "expert");
const summaryOnly = process.argv.includes("--summary");
const fixtureUrl = new URL(
  "../tests/fixtures/ai-search-benchmark-cases.json",
  import.meta.url,
);
const trainerUrl = new URL("../public/data/trainers.json", import.meta.url);
const benchmark = JSON.parse(await readFile(fixtureUrl, "utf8"));
const trainerPayload = JSON.parse(await readFile(trainerUrl, "utf8"));
const trainerById = new Map(
  trainerPayload.trainers.map((trainer) => [trainer.id, trainer]),
);

const round = (value, digits = 2) => Number(value.toFixed(digits));
const outcomeForSide = (battle, sideName) =>
  battle.winner == null ? 0.5 : battle.winner === sideName ? 1 : 0;
const winnerTrainerId = (battle, trainers) =>
  trainers.find((trainer) => trainer.name === battle.winner)?.id ?? null;

const buildScenario = (entry, trainers, difficulties) => ({
  scenarioId: `ai-search-benchmark-${entry.id}`,
  schemaVersion: 1,
  mode: "eve",
  seed: entry.seed,
  levelMode: benchmark.defaults.levelMode,
  battleEngine: "cobbleverse",
  aiDifficulty: difficulties[0],
  aiProfiles: difficulties.map((difficulty) => ({
    difficulty,
    strategy: benchmark.defaults.strategy,
    ...(difficulty === "cheater" ? { cheatProbability: 1 } : {}),
  })),
  battleType: benchmark.defaults.battleType,
  gimmickRules: benchmark.defaults.gimmickRules,
  sides: trainers.map((trainer) => ({
    source: "preset",
    trainerId: trainer.id,
    name: trainer.name,
    team: structuredClone(trainer.team),
    ai: trainer.ai,
    battleRules: trainer.battleRules,
    bag: trainer.bag,
  })),
  metadata: {
    benchmarkId: entry.id,
    benchmarkVersion: benchmark.version,
  },
});

const runBattle = (entry, trainers, difficulties) =>
  runNativeScenarioBattle(buildScenario(entry, trainers, difficulties), {
    maxTurns: benchmark.defaults.maxTurns,
    includeDetails: false,
  });

const startedAt = performance.now();
const results = [];
for (const entry of benchmark.cases) {
  const trainers = entry.trainerIds.map((trainerId) => {
    const trainer = trainerById.get(trainerId);
    if (!trainer) {
      throw new Error(
        `Benchmark trainer not found: ${trainerId} (${entry.id})`,
      );
    }
    return trainer;
  });
  const baseline = runBattle(entry, trainers, ["expert", "expert"]);
  const baselineResult = {
    winnerTrainerId: winnerTrainerId(baseline, trainers),
    turns: baseline.turns,
  };
  const baselineMatches =
    entry.baseline == null ||
    (entry.baseline.winnerTrainerId === baselineResult.winnerTrainerId &&
      entry.baseline.turns === baselineResult.turns);

  const candidateAsA =
    candidateDifficulty === "expert"
      ? baseline
      : runBattle(entry, trainers, [candidateDifficulty, "expert"]);
  const candidateAsB =
    candidateDifficulty === "expert"
      ? baseline
      : runBattle(entry, trainers, ["expert", candidateDifficulty]);
  const candidatePoints =
    outcomeForSide(candidateAsA, trainers[0].name) +
    outcomeForSide(candidateAsB, trainers[1].name);

  results.push({
    id: entry.id,
    seed: entry.seed,
    trainerIds: entry.trainerIds,
    focus: entry.focus,
    baseline: {
      ...baselineResult,
      matchesFixture: baselineMatches,
      durationMs: round(baseline.durationMs),
    },
    candidateAsA: {
      winnerTrainerId: winnerTrainerId(candidateAsA, trainers),
      turns: candidateAsA.turns,
      durationMs: round(candidateAsA.durationMs),
      points: outcomeForSide(candidateAsA, trainers[0].name),
    },
    candidateAsB: {
      winnerTrainerId: winnerTrainerId(candidateAsB, trainers),
      turns: candidateAsB.turns,
      durationMs: round(candidateAsB.durationMs),
      points: outcomeForSide(candidateAsB, trainers[1].name),
    },
    candidatePoints,
  });
}

const baselineMismatches = results.filter(
  (entry) => !entry.baseline.matchesFixture,
);
const totalCandidatePoints = results.reduce(
  (sum, entry) => sum + entry.candidatePoints,
  0,
);
const candidateBattles = results.length * 2;
const average = (values) =>
  values.reduce((sum, value) => sum + value, 0) / Math.max(1, values.length);
const candidateDurations = results.flatMap((entry) => [
  entry.candidateAsA.durationMs,
  entry.candidateAsB.durationMs,
]);

console.log(
  JSON.stringify(
    {
      schema: benchmark.schema,
      version: benchmark.version,
      candidateDifficulty,
      conditions: benchmark.defaults,
      summary: {
        cases: results.length,
        candidateBattles,
        candidatePoints: totalCandidatePoints,
        candidateScorePercent: round(
          (totalCandidatePoints / candidateBattles) * 100,
        ),
        baselineMismatches: baselineMismatches.length,
        averageBaselineTurns: round(
          average(results.map((entry) => entry.baseline.turns)),
        ),
        averageCandidateTurns: round(
          average(
            results.flatMap((entry) => [
              entry.candidateAsA.turns,
              entry.candidateAsB.turns,
            ]),
          ),
        ),
        averageBaselineEngineMs: round(
          average(results.map((entry) => entry.baseline.durationMs)),
        ),
        averageCandidateEngineMs: round(average(candidateDurations)),
        wallMs: round(performance.now() - startedAt),
      },
      baselineFixtureValues: results.map((entry) => ({
        id: entry.id,
        baseline: {
          winnerTrainerId: entry.baseline.winnerTrainerId,
          turns: entry.baseline.turns,
        },
      })),
      ...(!summaryOnly && {
        baselineMismatches,
        results,
      }),
    },
    null,
    2,
  ),
);

if (baselineMismatches.length > 0) {
  process.exitCode = 1;
}
