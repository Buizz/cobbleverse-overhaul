import { performance } from "node:perf_hooks";
import { readFile } from "node:fs/promises";

import { runNativeScenarioBattle } from "../lib/native-scenario-runner.mjs";
import { isSimpleAbilitySupported } from "../lib/cobbleventure-battle-engine.mjs";
import {
  calibrateWinProbability,
  fitWinProbabilityCalibration,
} from "../lib/common-battle-ai.mjs";

const startSeed = Number(process.argv[2] ?? 20260719);
const battleCount = Math.max(1, Number(process.argv[3] ?? 10));
const maxTurns = Math.max(1, Number(process.argv[4] ?? 100));
const difficultyA = String(process.argv[5] ?? "expert");
const difficultyB = String(process.argv[6] ?? "expert");
const summaryOnly = process.argv.includes("--summary");
const randomMatchups = process.argv.includes("--random-matchups");
const numericFlag = (name, fallback) => {
  const prefix = `--${name}=`;
  const argument = process.argv.find((entry) => entry.startsWith(prefix));
  if (!argument) return fallback;
  const value = Number(argument.slice(prefix.length));
  return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
};
const cheatProbabilityA = numericFlag("cheat-a", 0.5);
const cheatProbabilityB = numericFlag("cheat-b", 0.5);
const trainerPayload = JSON.parse(
  await readFile(new URL("../public/data/trainers.json", import.meta.url), "utf8"),
);
const trainerIds = ["dbingsu-server-party", "hoenn_league_drake"];
const fixedTrainers = trainerIds.map((trainerId) => {
  const trainer = trainerPayload.trainers.find((entry) => entry.id === trainerId);
  if (!trainer) {
    throw new Error(`Calibration trainer not found: ${trainerId}`);
  }
  return trainer;
});

const createSeededRandom = (seed) => {
  let state = Number(seed) >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
};
const shuffle = (entries, random) => {
  const result = [...entries];
  for (let index = result.length - 1; index > 0; index -= 1) {
    const target = Math.floor(random() * (index + 1));
    [result[index], result[target]] = [result[target], result[index]];
  }
  return result;
};
const buildRandomTrainerPairs = (trainers, count, seed) => {
  const eligible = trainers.filter(
    (trainer) =>
      typeof trainer?.id === "string" &&
      trainer.id.length > 0 &&
      Array.isArray(trainer.team) &&
      trainer.team.length === 6 &&
      trainer.team.every((pokemon) =>
        isSimpleAbilitySupported(pokemon?.ability),
      ),
  );
  if (eligible.length < 2) {
    throw new Error("At least two complete six-Pokemon entries are required.");
  }
  const random = createSeededRandom(seed);
  const pairs = [];
  while (pairs.length < count) {
    const pool = shuffle(eligible, random);
    for (
      let index = 0;
      index + 1 < pool.length && pairs.length < count;
      index += 2
    ) {
      pairs.push([pool[index], pool[index + 1]]);
    }
  }
  return pairs;
};
const trainerPairs = randomMatchups
  ? buildRandomTrainerPairs(
      trainerPayload.trainers,
      Math.max(battleCount * 20, battleCount),
      startSeed,
    )
  : Array.from({ length: battleCount }, () => fixedTrainers);
const buildScenario = (trainers, seed, matchupIndex) => ({
  scenarioId: "native-ai-win-probability-comparison",
  schemaVersion: 1,
  mode: "eve",
  seed,
  levelMode: "level-100",
  battleEngine: "cobbleventure",
  aiDifficulty: difficultyA,
  aiProfiles: [
    {
      difficulty: difficultyA,
      strategy: "balanced",
      ...(difficultyA === "cheater"
        ? { cheatProbability: cheatProbabilityA }
        : {}),
    },
    {
      difficulty: difficultyB,
      strategy: "balanced",
      ...(difficultyB === "cheater"
        ? { cheatProbability: cheatProbabilityB }
        : {}),
    },
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
  metadata: {
    comparisonMode: randomMatchups ? "random-matchups" : "fixed-matchup",
    matchupIndex,
  },
});

const clampProbability = (value) => Math.min(1 - 1e-9, Math.max(1e-9, value));
const round = (value, digits = 4) => Number(value.toFixed(digits));
const compareDecision = (trace) => {
  const candidates = (trace.candidates ?? []).filter(
    (candidate) =>
      candidate.legal !== false &&
      Number.isFinite(candidate.winProbabilityAfter),
  );
  const selectedCandidates = candidates.filter((candidate) => candidate.selected);
  const selected =
    selectedCandidates.find((candidate) => candidate.type === "gimmick") ??
    selectedCandidates[0];
  const probabilityBest = [...candidates].sort(
    (left, right) =>
      right.winProbabilityAfter - left.winProbabilityAfter ||
      Number(right.score ?? 0) - Number(left.score ?? 0),
  )[0];
  if (!selected || !probabilityBest) {
    return null;
  }
  const predictedRegret =
    probabilityBest.winProbabilityAfter - selected.winProbabilityAfter;
  return {
    turn: trace.turn,
    side: trace.side,
    actor: trace.species,
    selectedAction: selected.name ?? selected.id,
    selectedScore: selected.score,
    selectedWinPercent: round(selected.winProbabilityAfter * 100, 2),
    probabilityBestAction: probabilityBest.name ?? probabilityBest.id,
    probabilityBestScore: probabilityBest.score,
    probabilityBestWinPercent: round(
      probabilityBest.winProbabilityAfter * 100,
      2,
    ),
    predictedRegretPercentagePoints: round(predictedRegret * 100, 2),
    differs: predictedRegret > 1e-9,
    materiallyDiffers: predictedRegret >= 0.02,
  };
};
const wilsonInterval = (wins, trials, z = 1.96) => {
  if (trials === 0) {
    return null;
  }
  const rate = wins / trials;
  const denominator = 1 + (z ** 2) / trials;
  const center = (rate + (z ** 2) / (2 * trials)) / denominator;
  const margin =
    (z *
      Math.sqrt(
        (rate * (1 - rate)) / trials + (z ** 2) / (4 * trials ** 2),
      )) /
    denominator;
  return {
    lower: round(Math.max(0, center - margin)),
    upper: round(Math.min(1, center + margin)),
  };
};
const samples = [];
const decisionComparisons = [];
const calibrationSamples = [];
const policyOverrides = [];
const cheatActivations = [];
const skippedMatchups = [];
let openingModel = null;
let firstBattleTimeline = null;
const startedAt = performance.now();

let index = 0;
for (
  let attemptIndex = 0;
  index < battleCount && attemptIndex < trainerPairs.length;
  attemptIndex += 1
) {
  const seed = startSeed + attemptIndex;
  const matchupTrainers = trainerPairs[attemptIndex];
  const runScenario = buildScenario(matchupTrainers, seed, index);
  let battle;
  try {
    battle = runNativeScenarioBattle(runScenario, {
      maxTurns,
      includeDetails: true,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (
      randomMatchups &&
      /^Unsupported .+ strict validation:/i.test(message)
    ) {
      skippedMatchups.push({
        seed,
        matchup: `${runScenario.sides[0].name} vs ${runScenario.sides[1].name}`,
        trainerIds: runScenario.sides.map((side) => side.trainerId),
        reason: message,
      });
      continue;
    }
    throw error;
  }
  const openingTrace = battle.aiTrace?.find(
    (trace) => trace.turn === 1 && trace.side === 0,
  );
  const predictedWinProbability = openingTrace?.winEstimate?.probability;
  if (!Number.isFinite(predictedWinProbability)) {
    throw new Error(`Opening win estimate missing for seed ${seed}.`);
  }
  openingModel ??= {
    version: openingTrace.winEstimate.modelVersion,
    featureSchemaVersion: openingTrace.winEstimate.featureSchemaVersion,
    confidence: openingTrace.winEstimate.confidence,
    topFactors: openingTrace.winEstimate.topFactors,
  };
  const battleDecisionComparisons = (battle.aiTrace ?? [])
    .map(compareDecision)
    .filter(Boolean);
  decisionComparisons.push(...battleDecisionComparisons);
  policyOverrides.push(
    ...(battle.aiTrace ?? [])
      .filter((trace) => trace.diagnostics?.policyOverride === true)
      .map((trace) => ({
        seed,
        turn: trace.turn,
        side: trace.side,
        actor: trace.species,
        heuristicAction: trace.diagnostics.heuristicAction,
        probabilityAction: trace.diagnostics.probabilityAction,
        probabilityGain: trace.diagnostics.probabilityGain,
      })),
  );
  cheatActivations.push(
    ...(battle.aiTrace ?? [])
      .filter((trace) => trace.diagnostics?.cheatActivated === true)
      .map((trace) => ({
        seed,
        turn: trace.turn,
        side: trace.side,
        actor: trace.species,
        probability: trace.diagnostics.cheatProbability,
        roll: trace.diagnostics.cheatRoll,
        observedOpponentCommand:
          trace.diagnostics.observedOpponentCommand ?? null,
      })),
  );
  firstBattleTimeline ??= battleDecisionComparisons
    .filter((comparison) => comparison.side === 0)
    .map((comparison) => ({
      turn: comparison.turn,
      actor: comparison.actor,
      predictedWinPercent: battle.aiTrace?.find(
        (trace) =>
          trace.turn === comparison.turn && trace.side === comparison.side,
      )?.winEstimate?.probabilityPercent,
      chosenAction: comparison.selectedAction,
      chosenActionWinPercent: comparison.selectedWinPercent,
      probabilityBestAction: comparison.probabilityBestAction,
      probabilityBestWinPercent: comparison.probabilityBestWinPercent,
      predictedRegretPercentagePoints:
        comparison.predictedRegretPercentagePoints,
    }));

  const actualOutcome =
    battle.winner === runScenario.sides[0].name
      ? 1
      : battle.winner === runScenario.sides[1].name
        ? 0
        : 0.5;
  for (const trace of battle.aiTrace ?? []) {
    if (!Number.isFinite(trace.winEstimate?.probability)) continue;
    calibrationSamples.push({
      seed,
      sampleIndex: index,
      predictedProbability: trace.winEstimate.probability,
      actualOutcome:
        actualOutcome === 0.5
          ? 0.5
          : trace.side === 0
            ? actualOutcome
            : 1 - actualOutcome,
    });
  }
  const safeProbability = clampProbability(predictedWinProbability);
  samples.push({
    seed,
    matchup: `${runScenario.sides[0].name} vs ${runScenario.sides[1].name}`,
    trainerIds: runScenario.sides.map((side) => side.trainerId),
    predictedWinProbability: round(predictedWinProbability),
    predictedWinPercent: round(predictedWinProbability * 100, 2),
    actualOutcome,
    winner: battle.winner ?? "draw",
    turns: battle.turns,
    durationMs: round(battle.durationMs, 2),
    decisionsCompared: battleDecisionComparisons.length,
    materialDecisionDifferences: battleDecisionComparisons.filter(
      (comparison) => comparison.materiallyDiffers,
    ).length,
    squaredError: round((predictedWinProbability - actualOutcome) ** 2),
    logLoss: round(
      -(
        actualOutcome * Math.log(safeProbability) +
        (1 - actualOutcome) * Math.log(1 - safeProbability)
      ),
    ),
  });
  index += 1;
}

if (samples.length < battleCount) {
  throw new Error(
    `Only ${samples.length}/${battleCount} random matchups completed after ${trainerPairs.length} attempts.`,
  );
}

const total = samples.reduce(
  (result, sample) => ({
    predicted: result.predicted + sample.predictedWinProbability,
    actual: result.actual + sample.actualOutcome,
    squaredError: result.squaredError + sample.squaredError,
    logLoss: result.logLoss + sample.logLoss,
    correct:
      result.correct +
      (sample.actualOutcome === 0.5
        ? 0
        : Number(
            (sample.predictedWinProbability >= 0.5) ===
              (sample.actualOutcome === 1),
          )),
    turns: result.turns + sample.turns,
    durationMs: result.durationMs + sample.durationMs,
  }),
  {
    predicted: 0,
    actual: 0,
    squaredError: 0,
    logLoss: 0,
    correct: 0,
    turns: 0,
    durationMs: 0,
  },
);
const averagePrediction = total.predicted / battleCount;
const actualWinRate = total.actual / battleCount;
const decisiveSamples = samples.filter((sample) => sample.actualOutcome !== 0.5);
const sideAWins = decisiveSamples.filter((sample) => sample.actualOutcome === 1).length;
const materialDifferences = decisionComparisons.filter(
  (comparison) => comparison.materiallyDiffers,
);
const trainingSeedCount = Math.max(1, Math.floor(battleCount * 0.8));
const trainingCalibrationSamples = calibrationSamples.filter(
  (sample) => sample.sampleIndex < trainingSeedCount,
);
const holdoutCalibrationSamples = calibrationSamples.filter(
  (sample) => sample.sampleIndex >= trainingSeedCount,
);
const fittedCalibration = fitWinProbabilityCalibration(
  trainingCalibrationSamples,
);
const calibrationMetrics = (entries) => {
  if (entries.length === 0) return null;
  const totals = entries.reduce(
    (result, sample) => {
      const calibrated = calibrateWinProbability(
        sample.predictedProbability,
        fittedCalibration,
      );
      return {
        raw:
          result.raw +
          (sample.predictedProbability - sample.actualOutcome) ** 2,
        calibrated:
          result.calibrated +
          (calibrated - sample.actualOutcome) ** 2,
      };
    },
    { raw: 0, calibrated: 0 },
  );
  return {
    sampleCount: entries.length,
    rawBrierScore: round(totals.raw / entries.length),
    calibratedBrierScore: round(totals.calibrated / entries.length),
  };
};

console.log(
  JSON.stringify(
    {
      mode: randomMatchups ? "random-matchups" : "fixed-matchup",
      matchup: randomMatchups
        ? `${battleCount} seeded random matchups`
        : `${fixedTrainers[0].name} vs ${fixedTrainers[1].name}`,
      conditions: {
        difficulties: [difficultyA, difficultyB],
        cheatProbabilities: [
          difficultyA === "cheater" ? cheatProbabilityA : null,
          difficultyB === "cheater" ? cheatProbabilityB : null,
        ],
        strategies: ["balanced", "balanced"],
        startSeed,
        battleCount,
        maxTurns,
      },
      openingModel,
      calibration: {
        trainingSeeds: trainingSeedCount,
        holdoutSeeds: battleCount - trainingSeedCount,
        fittedCalibration,
        training: calibrationMetrics(trainingCalibrationSamples),
        holdout: calibrationMetrics(holdoutCalibrationSamples),
      },
      matchupResults: samples.map((sample) => ({
        seed: sample.seed,
        matchup: sample.matchup,
        trainerIds: sample.trainerIds,
        predictedSideAWinPercent: sample.predictedWinPercent,
        winner: sample.winner,
        turns: sample.turns,
        policyOverrides: policyOverrides.filter(
          (override) => override.seed === sample.seed,
        ).length,
        cheatActivations: cheatActivations.filter(
          (activation) => activation.seed === sample.seed,
        ).length,
      })),
      skippedMatchups: {
        count: skippedMatchups.length,
        ...(!summaryOnly && { entries: skippedMatchups }),
      },
      ...(!summaryOnly && {
        firstBattleTimeline,
        largestDecisionDifferences: [...decisionComparisons]
          .sort(
            (left, right) =>
              right.predictedRegretPercentagePoints -
              left.predictedRegretPercentagePoints,
          )
          .slice(0, 10),
        policyOverrides: policyOverrides.slice(0, 20),
        cheatActivations: cheatActivations.slice(0, 30),
        samples,
      }),
      summary: {
        averagePredictedWinProbability: round(averagePrediction),
        averagePredictedWinPercent: round(averagePrediction * 100, 2),
        actualWinRate: round(actualWinRate),
        actualWinPercent: round(actualWinRate * 100, 2),
        actualWinRateWilson95: wilsonInterval(sideAWins, decisiveSamples.length),
        calibrationGapPercentagePoints: round(
          (averagePrediction - actualWinRate) * 100,
          2,
        ),
        brierScore: round(total.squaredError / battleCount),
        logLoss: round(total.logLoss / battleCount),
        thresholdAccuracy: round(total.correct / battleCount),
        decisionsCompared: decisionComparisons.length,
        decisionsDifferentFromProbabilityBest: decisionComparisons.filter(
          (comparison) => comparison.differs,
        ).length,
        materialDecisionDifferences: materialDifferences.length,
        materialDecisionDifferenceRate: round(
          materialDifferences.length / Math.max(1, decisionComparisons.length),
        ),
        averagePredictedRegretPercentagePoints: round(
          decisionComparisons.reduce(
            (sum, comparison) =>
              sum + comparison.predictedRegretPercentagePoints,
            0,
          ) / Math.max(1, decisionComparisons.length),
          2,
        ),
        winProbabilityPolicyOverrides: policyOverrides.length,
        cheatActivations: cheatActivations.length,
        averageTurns: round(total.turns / battleCount, 2),
        averageEngineMs: round(total.durationMs / battleCount, 2),
        wallMs: round(performance.now() - startedAt, 2),
      },
    },
    null,
    2,
  ),
);
