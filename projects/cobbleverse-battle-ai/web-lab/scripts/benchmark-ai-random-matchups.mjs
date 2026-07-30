import { readFile } from "node:fs/promises";

import { createSimpleBattle } from "../lib/cobbleverse-battle-engine.mjs";
import {
  createNativeBattleSetup,
  runNativeScenarioBattle,
} from "../lib/native-scenario-runner.mjs";

const requestedPairs = Math.max(1, Number(process.argv[2] ?? 50));
const scheduleSeed = Number(process.argv[3] ?? 20260730);
const maxTurns = Math.max(1, Number(process.argv[4] ?? 100));
const referenceDifficulty = String(process.argv[5] ?? "expert");
const difficulties = String(
  process.argv[6] ?? "expert,expert_winrate,expert_search,cheater",
)
  .split(",")
  .map((difficulty) => difficulty.trim())
  .filter(Boolean);
const trainerPayload = JSON.parse(
  await readFile(new URL("../public/data/trainers.json", import.meta.url), "utf8"),
);
const trainerCandidates = trainerPayload.trainers.filter(
  (trainer) => Array.isArray(trainer.team) && trainer.team.length >= 6,
);
let trainers = [];

const random = (() => {
  let state = scheduleSeed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 0x1_0000_0000;
  };
})();

const trainerSide = (trainer) => ({
  source: "preset",
  trainerId: trainer.id,
  name: trainer.name,
  team: structuredClone(trainer.team),
  ai: trainer.ai,
  battleRules: trainer.battleRules,
  bag: trainer.bag,
});

const buildScenario = (left, right, seed, profiles) => ({
  scenarioId: `random-ai-benchmark-${left.id}-${right.id}-${seed}`,
  schemaVersion: 1,
  mode: "eve",
  seed,
  levelMode: "level-100",
  battleEngine: "cobbleverse",
  aiDifficulty: profiles[0],
  aiProfiles: profiles.map((difficulty) => ({
    difficulty,
    strategy: "balanced",
    ...(difficulty === "cheater" ? { cheatProbability: 1 } : {}),
  })),
  battleType: "single",
  gimmickRules: "all",
  sides: [trainerSide(left), trainerSide(right)],
});

const validationReference =
  trainerCandidates.find((trainer) => trainer.id === "dbingsu-server-party") ??
  trainerCandidates[0];
trainers = trainerCandidates.filter((trainer) => {
  try {
    createSimpleBattle(
      createNativeBattleSetup(
        buildScenario(trainer, validationReference, scheduleSeed, [
          "expert",
          "expert",
        ]),
      ),
    );
    return true;
  } catch {
    return false;
  }
});

const runBattle = (left, right, seed, profiles) =>
  runNativeScenarioBattle(buildScenario(left, right, seed, profiles), {
    maxTurns,
    includeDetails: false,
  });

const outcome = (battle, sideName) =>
  battle.winner == null ? 0.5 : battle.winner === sideName ? 1 : 0;
const round = (value, digits = 2) => Number(value.toFixed(digits));
const totals = new Map(
  difficulties.map((difficulty) => [
    difficulty,
    {
      difficulty,
      points: 0,
      asAPoints: 0,
      asBPoints: 0,
      turns: 0,
      engineMs: 0,
      battles: 0,
    },
  ]),
);
const accepted = [];
const rejected = [];
const seenPairs = new Set();
const startedAt = performance.now();
let attempts = 0;

while (accepted.length < requestedPairs && attempts < requestedPairs * 20) {
  attempts += 1;
  const left = trainers[Math.floor(random() * trainers.length)];
  const right = trainers[Math.floor(random() * trainers.length)];
  const pairKey = [left.id, right.id].sort().join(":");
  if (left.id === right.id || seenPairs.has(pairKey)) {
    continue;
  }
  seenPairs.add(pairKey);
  const seed = (scheduleSeed + attempts * 7919) >>> 0;
  const pairResults = [];

  try {
    for (const difficulty of difficulties) {
      const candidateAsA = runBattle(left, right, seed, [
        difficulty,
        referenceDifficulty,
      ]);
      const candidateAsB = runBattle(left, right, seed, [
        referenceDifficulty,
        difficulty,
      ]);
      pairResults.push({ difficulty, candidateAsA, candidateAsB });
    }
  } catch (error) {
    rejected.push({
      trainerIds: [left.id, right.id],
      message: error instanceof Error ? error.message : String(error),
    });
    continue;
  }

  for (const result of pairResults) {
    const total = totals.get(result.difficulty);
    const asAPoints = outcome(result.candidateAsA, left.name);
    const asBPoints = outcome(result.candidateAsB, right.name);
    total.points += asAPoints + asBPoints;
    total.asAPoints += asAPoints;
    total.asBPoints += asBPoints;
    total.turns += result.candidateAsA.turns + result.candidateAsB.turns;
    total.engineMs +=
      result.candidateAsA.durationMs + result.candidateAsB.durationMs;
    total.battles += 2;
  }
  accepted.push({
    trainerIds: [left.id, right.id],
    seed,
  });
  if (accepted.length % 5 === 0 || accepted.length === requestedPairs) {
    console.error(
      `[random-ai-benchmark] ${accepted.length}/${requestedPairs} pairs`,
    );
  }
}

if (accepted.length < requestedPairs) {
  throw new Error(
    `Only ${accepted.length}/${requestedPairs} valid random matchups were found.`,
  );
}

const results = [...totals.values()].map((total) => ({
  difficulty: total.difficulty,
  battles: total.battles,
  points: total.points,
  scorePercent: round((total.points / total.battles) * 100),
  asAScorePercent: round((total.asAPoints / requestedPairs) * 100),
  asBScorePercent: round((total.asBPoints / requestedPairs) * 100),
  averageTurns: round(total.turns / total.battles),
  averageEngineMs: round(total.engineMs / total.battles),
}));

console.log(
  JSON.stringify(
    {
      schema: "cobbleverse-random-ai-benchmark",
      version: 1,
      conditions: {
        requestedPairs,
        battlesPerDifficulty: requestedPairs * 2,
        scheduleSeed,
        maxTurns,
        levelMode: "level-100",
        strategy: "balanced",
        referenceDifficulty,
        cheaterProbability: 1,
      },
      trainerPoolSize: trainers.length,
      attempts,
      rejectedCount: rejected.length,
      rejected: rejected.slice(0, 10),
      wallMs: round(performance.now() - startedAt),
      results,
      schedule: accepted,
    },
    null,
    2,
  ),
);
