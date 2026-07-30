const baseUrl = String(
  process.env.COBBLEVERSE_BATTLE_LAB_URL ?? "http://localhost:3000",
).replace(/\/$/, "");
const battleCount = Math.max(
  4,
  Math.min(160, Number(process.argv[2] ?? 24)),
);
const difficulty = String(process.argv[3] ?? "expert_search");
const parallelismValues = [1, 2, 4, 8];
const scenario = {
  mode: "eve",
  seed: 20260719,
  levelMode: "level-100",
  battleEngine: "cobbleverse",
  aiDifficulty: difficulty,
  aiProfiles: [
    { difficulty, strategy: "balanced" },
    { difficulty, strategy: "balanced" },
  ],
  battleType: "single",
  sides: [
    { source: "preset", trainerId: "dbingsu-server-party" },
    { source: "preset", trainerId: "hoenn_league_drake" },
  ],
};
const jobs = Array.from({ length: battleCount }, (_, index) => ({
  seed: scenario.seed + index,
  aiProfiles: scenario.aiProfiles,
}));

function deterministicResult(results) {
  return results.map(({ seed, status, winner, turns }) => ({
    seed,
    status,
    winner,
    turns,
  }));
}

async function run(parallelism) {
  const startedAt = performance.now();
  const response = await fetch(`${baseUrl}/api/battle-sweep`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ scenario, jobs, concurrency: parallelism }),
  });
  const payload = await response.json();
  if (!response.ok || !payload.ok) {
    throw new Error(JSON.stringify(payload));
  }
  const wallMs = performance.now() - startedAt;
  return {
    requestedParallelism: parallelism,
    actualParallelism: payload.parallelism,
    executionMode: payload.executionMode,
    wallMs: Math.round(wallMs * 100) / 100,
    battlesPerSecond:
      Math.round((battleCount / (wallMs / 1_000)) * 100) / 100,
    results: payload.results,
  };
}

const measurements = [];
for (const parallelism of parallelismValues) {
  measurements.push(await run(parallelism));
}
const baseline = measurements[0];
const expected = JSON.stringify(deterministicResult(baseline.results));
for (const measurement of measurements) {
  measurement.speedup =
    Math.round((baseline.wallMs / measurement.wallMs) * 100) / 100;
  measurement.deterministic =
    JSON.stringify(deterministicResult(measurement.results)) === expected;
  delete measurement.results;
}

console.log(
  JSON.stringify(
    {
      battleCount,
      difficulty,
      logicalProcessors: process.env.NUMBER_OF_PROCESSORS ?? null,
      measurements,
    },
    null,
    2,
  ),
);

if (measurements.some((measurement) => !measurement.deterministic)) {
  process.exitCode = 1;
}
