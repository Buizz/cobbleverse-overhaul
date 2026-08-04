import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { runNativeScenarioBattle } from "../lib/native-scenario-runner.mjs";

const benchmark = JSON.parse(
  await readFile(
    new URL("./fixtures/ai-search-benchmark-cases.json", import.meta.url),
    "utf8",
  ),
);
const trainerPayload = JSON.parse(
  await readFile(new URL("../public/data/trainers.json", import.meta.url), "utf8"),
);
const trainerById = new Map(
  trainerPayload.trainers.map((trainer) => [trainer.id, trainer]),
);

const buildScenario = (entry, trainers) => ({
  scenarioId: `ai-search-benchmark-test-${entry.id}`,
  schemaVersion: 1,
  mode: "eve",
  seed: entry.seed,
  levelMode: benchmark.defaults.levelMode,
  battleEngine: "cobbleventure",
  aiDifficulty: "expert",
  aiProfiles: [0, 1].map(() => ({
    difficulty: "expert",
    strategy: benchmark.defaults.strategy,
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
});

test("defines unique fixed AI search benchmark cases", () => {
  assert.equal(benchmark.schema, "cobbleventure-ai-search-benchmark");
  assert.equal(benchmark.version, 2);
  assert.equal(benchmark.cases.length, 10);
  assert.equal(
    new Set(benchmark.cases.map((entry) => entry.id)).size,
    benchmark.cases.length,
  );
  assert.equal(
    new Set(benchmark.cases.map((entry) => entry.seed)).size,
    benchmark.cases.length,
  );
  for (const entry of benchmark.cases) {
    assert.equal(entry.trainerIds.length, 2);
    assert.ok(Number.isInteger(entry.seed));
    assert.ok(entry.focus.length > 0);
    for (const trainerId of entry.trainerIds) {
      assert.ok(
        trainerById.has(trainerId),
        `missing benchmark trainer ${trainerId}`,
      );
    }
  }
});

test("keeps the expert AI benchmark baseline deterministic", () => {
  for (const entry of benchmark.cases) {
    assert.ok(entry.baseline, `baseline missing for ${entry.id}`);
    const trainers = entry.trainerIds.map((trainerId) =>
      trainerById.get(trainerId),
    );
    const battle = runNativeScenarioBattle(buildScenario(entry, trainers), {
      maxTurns: benchmark.defaults.maxTurns,
      includeDetails: false,
    });
    const actualWinnerId =
      trainers.find((trainer) => trainer.name === battle.winner)?.id ?? null;
    assert.deepEqual(
      {
        winnerTrainerId: actualWinnerId,
        turns: battle.turns,
      },
      entry.baseline,
      entry.id,
    );
  }
});
