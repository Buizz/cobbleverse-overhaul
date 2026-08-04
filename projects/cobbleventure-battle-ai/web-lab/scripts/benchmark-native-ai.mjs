import { performance } from "node:perf_hooks";
import { readFile } from "node:fs/promises";

import { runNativeScenarioBattle } from "../lib/native-scenario-runner.mjs";

const seed = Number(process.argv[2] ?? 20260719);
const repetitions = Math.max(1, Number(process.argv[3] ?? 3));
const maxTurns = Math.max(1, Number(process.argv[4] ?? 100));
const summaryOnly = process.argv.includes("--summary");
const includeDetails = !process.argv.includes("--no-details");
const sweep = process.argv.includes("--sweep");
const fastSweep = process.argv.includes("--fast-sweep");
const strategies = [
  "balanced",
  "aggressive",
  "defensive",
  "ace_check",
  "reckless_ace",
  "setup",
  "hazard",
  "tempo",
];
const trainerPayload = JSON.parse(
  await readFile(new URL("../public/data/trainers.json", import.meta.url), "utf8"),
);
const trainerIds = ["dbingsu-server-party", "hoenn_league_drake"];
const trainers = trainerIds.map((trainerId) => {
  const trainer = trainerPayload.trainers.find((entry) => entry.id === trainerId);
  if (!trainer) {
    throw new Error(`Benchmark trainer not found: ${trainerId}`);
  }
  return trainer;
});
const scenario = {
  scenarioId: "native-ai-performance",
  schemaVersion: 1,
  mode: "eve",
  seed,
  levelMode: "level-100",
  battleEngine: "cobbleventure",
  aiDifficulty: "expert",
  aiProfiles: [
    { difficulty: "expert", strategy: "balanced" },
    { difficulty: "expert", strategy: "balanced" },
  ],
  battleType: "single",
  gimmickRules: "all",
  sides: trainers.map((trainer) => ({
    source: "preset",
    trainerId: trainer.id,
    name: trainer.name,
    team: structuredClone(trainer.team),
    ai: trainer.ai,
    battleRules: trainer.battleRules,
    bag: trainer.bag,
  })),
};

const samples = [];
let payloadBreakdownKiB = {};
const exhaustiveSweepJobs = strategies.flatMap((strategyA) =>
      strategies.flatMap((strategyB) =>
        Array.from({ length: repetitions }, (_, round) => ({
          strategyA,
          strategyB,
          seed: seed + round,
        })),
      ),
    );
const fastSweepPairs = [
  ...strategies.map((strategy) => [strategy, "balanced"]),
  ...strategies
    .filter((strategy) => strategy !== "balanced")
    .map((strategy) => ["balanced", strategy]),
  ["balanced", "balanced"],
  ["balanced", "aggressive"],
  ["aggressive", "balanced"],
  ["aggressive", "aggressive"],
];
const fastSweepJobs = fastSweepPairs.flatMap(([strategyA, strategyB]) =>
  Array.from({ length: repetitions }, (_, round) => ({
    strategyA,
    strategyB,
    seed: seed + round,
  })),
);
const jobs = sweep
  ? exhaustiveSweepJobs
  : fastSweep
    ? fastSweepJobs
    : Array.from({ length: repetitions }, () => ({
      strategyA: "balanced",
      strategyB: "balanced",
      seed,
    }));
for (let index = 0; index < jobs.length; index += 1) {
  const job = jobs[index];
  const runScenario = {
    ...scenario,
    seed: job.seed,
    aiProfiles: [
      { difficulty: "expert", strategy: job.strategyA },
      { difficulty: "expert", strategy: job.strategyB },
    ],
  };
  const startedAt = performance.now();
  const battle = runNativeScenarioBattle(runScenario, { maxTurns, includeDetails });
  const engineCompletedAt = performance.now();
  const serialized = JSON.stringify({ ok: true, scenario: runScenario, battle });
  const serializedAt = performance.now();
  if (index === 0) {
    payloadBreakdownKiB = Object.fromEntries(
      Object.entries(battle)
        .map(([key, value]) => [
          key,
          Number((Buffer.byteLength(JSON.stringify(value)) / 1024).toFixed(2)),
        ])
        .sort((left, right) => right[1] - left[1]),
    );
  }
  samples.push({
    run: index + 1,
    strategies: `${job.strategyA}:${job.strategyB}`,
    seed: job.seed,
    wallMs: Number((serializedAt - startedAt).toFixed(2)),
    engineMs: Number(battle.durationMs.toFixed(2)),
    serializationMs: Number((serializedAt - engineCompletedAt).toFixed(2)),
    responseKiB: Number((Buffer.byteLength(serialized) / 1024).toFixed(2)),
    turns: battle.turns,
    status: battle.status,
    winner: battle.winner,
    aiDecisions: battle.aiTrace?.length ?? 0,
    events: battle.events?.length ?? 0,
  });
}

const totals = samples.reduce(
  (result, sample) => ({
    wallMs: result.wallMs + sample.wallMs,
    engineMs: result.engineMs + sample.engineMs,
    serializationMs: result.serializationMs + sample.serializationMs,
    responseKiB: result.responseKiB + sample.responseKiB,
    turns: result.turns + sample.turns,
  }),
  { wallMs: 0, engineMs: 0, serializationMs: 0, responseKiB: 0, turns: 0 },
);

console.log(
  JSON.stringify(
    {
      seed,
      repetitions,
      battleCount: samples.length,
      sweep: fastSweep ? "fast" : sweep ? "exact" : false,
      maxTurns,
      ...(!summaryOnly && { samples }),
      averages: {
        wallMs: Number((totals.wallMs / samples.length).toFixed(2)),
        engineMs: Number((totals.engineMs / samples.length).toFixed(2)),
        serializationMs: Number((totals.serializationMs / samples.length).toFixed(2)),
        msPerTurn: Number((totals.engineMs / totals.turns).toFixed(2)),
        responseKiB: Number((totals.responseKiB / samples.length).toFixed(2)),
        turns: Number((totals.turns / samples.length).toFixed(2)),
      },
      totalResponseMiB: Number((totals.responseKiB / 1024).toFixed(2)),
      turnRange: {
        minimum: Math.min(...samples.map((sample) => sample.turns)),
        maximum: Math.max(...samples.map((sample) => sample.turns)),
      },
      payloadBreakdownKiB,
    },
    null,
    2,
  ),
);
