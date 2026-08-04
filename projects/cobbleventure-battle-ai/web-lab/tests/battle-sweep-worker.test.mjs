import assert from "node:assert/strict";
import test from "node:test";
import { Worker } from "node:worker_threads";

const workerUrl = new URL(
  "../scripts/battle-sweep-worker.mjs",
  import.meta.url,
);

function runWorker(payload) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(workerUrl);
    worker.once("message", (result) => {
      resolve(result);
      void worker.terminate();
    });
    worker.once("error", reject);
    worker.postMessage(payload);
  });
}

function deterministicSummary(result) {
  return {
    index: result.index,
    seed: result.seed,
    status: result.status,
    winner: result.winner,
    turns: result.turns,
  };
}

test("local sweep worker runs compact deterministic battles", async () => {
  const scenario = {
    mode: "eve",
    seed: 20260719,
    levelMode: "level-100",
    battleEngine: "cobbleventure",
    aiDifficulty: "expert",
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
    battleType: "single",
    sides: [
      { source: "preset", trainerId: "dbingsu-server-party" },
      { source: "preset", trainerId: "hoenn_league_drake" },
    ],
  };
  const jobs = [20260719, 20260720].map((seed, index) => ({
    index,
    seed,
    aiProfiles: scenario.aiProfiles,
  }));

  const first = await runWorker({ scenario, jobs });
  const second = await runWorker({ scenario, jobs });

  assert.equal(first.ok, true);
  assert.equal(second.ok, true);
  assert.equal(first.results.length, jobs.length);
  assert.deepEqual(
    first.results.map(deterministicSummary),
    second.results.map(deterministicSummary),
  );
});
