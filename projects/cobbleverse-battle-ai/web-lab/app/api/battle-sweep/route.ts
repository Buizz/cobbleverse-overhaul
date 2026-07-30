import trainerIndex from "../../../public/data/trainers.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import { createBattleScenario } from "../../../lib/battle-scenario.mjs";
import { createCobblemonItemResolver } from "../../../lib/cobblemon-item-catalog.mjs";
import { runNativeScenarioBattle } from "../../../lib/native-scenario-runner.mjs";
import { runAutomaticBattle } from "../../../lib/showdown-battle-runner.mjs";

const itemResolver = createCobblemonItemResolver(itemCatalog);
const difficulties = new Set([
  "novice",
  "standard",
  "advanced",
  "expert",
  "expert_winrate",
  "expert_search",
  "cheater",
]);
const strategies = new Set([
  "balanced",
  "aggressive",
  "defensive",
  "ace_check",
  "reckless_ace",
  "setup",
  "hazard",
  "tempo",
  "unpredictable",
]);

type SweepProfile = {
  difficulty: string;
  strategy: string;
};

type SweepJob = {
  seed: number;
  aiProfiles: SweepProfile[];
};

function validJob(value: unknown): value is SweepJob {
  if (!value || typeof value !== "object") return false;
  const job = value as Partial<SweepJob>;
  return (
    Number.isInteger(job.seed) &&
    Array.isArray(job.aiProfiles) &&
    job.aiProfiles.length === 2 &&
    job.aiProfiles.every(
      (profile) =>
        profile &&
        difficulties.has(profile.difficulty) &&
        strategies.has(profile.strategy),
    )
  );
}

export async function POST(request: Request) {
  let payload: { scenario?: unknown; jobs?: unknown };
  try {
    payload = await request.json();
  } catch {
    return Response.json(
      {
        ok: false,
        issues: [
          {
            path: "$",
            code: "invalid_json",
            message: "올바른 JSON 요청이 아닙니다.",
          },
        ],
      },
      { status: 400 },
    );
  }

  const validation = createBattleScenario(
    payload.scenario,
    trainerIndex.trainers,
    itemResolver,
  );
  if (!validation.ok) {
    return Response.json(validation, { status: 422 });
  }
  if (
    !Array.isArray(payload.jobs) ||
    payload.jobs.length < 1 ||
    payload.jobs.length > 1_600 ||
    !payload.jobs.every(validJob)
  ) {
    return Response.json(
      {
        ok: false,
        issues: [
          {
            path: "jobs",
            code: "invalid_sweep_jobs",
            message:
              "반복 전투 작업은 1~1600개의 올바른 AI 설정이어야 합니다.",
          },
        ],
      },
      { status: 422 },
    );
  }

  try {
    const results = [];
    for (const job of payload.jobs) {
      const scenario = {
        ...validation.scenario,
        seed: job.seed,
        aiDifficulty: job.aiProfiles[0].difficulty,
        aiProfiles: job.aiProfiles,
      };
      const battle =
        scenario.battleEngine === "cobbleverse"
          ? runNativeScenarioBattle(scenario, { includeDetails: false })
          : await runAutomaticBattle(scenario);
      results.push({
        seed: battle.seed,
        status: battle.status,
        winner: battle.winner,
        turns: battle.turns,
        durationMs: battle.durationMs,
      });
    }
    return Response.json(
      {
        ok: true,
        results,
        parallelism: 1,
        executionMode: "sequential",
      },
      { status: 201 },
    );
  } catch (error) {
    return Response.json(
      {
        ok: false,
        issues: [
          {
            path: "$",
            code: "battle_sweep_failed",
            message:
              error instanceof Error
                ? error.message
                : "반복 전투를 완료하지 못했습니다.",
          },
        ],
      },
      { status: 422 },
    );
  }
}
