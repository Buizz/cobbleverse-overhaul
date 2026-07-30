import { parentPort } from "node:worker_threads";

import trainerIndex from "../public/data/trainers.json" with { type: "json" };
import itemCatalog from "../public/data/cobblemon-battle-items.json" with {
  type: "json",
};
import { createBattleScenario } from "../lib/battle-scenario.mjs";
import { createCobblemonItemResolver } from "../lib/cobblemon-item-catalog.mjs";
import { runNativeScenarioBattle } from "../lib/native-scenario-runner.mjs";
import { runAutomaticBattle } from "../lib/showdown-battle-runner.mjs";

const itemResolver = createCobblemonItemResolver(itemCatalog);

function battleSummary(battle, index) {
  return {
    index,
    seed: battle.seed,
    status: battle.status,
    winner: battle.winner,
    turns: battle.turns,
    durationMs: battle.durationMs,
  };
}

parentPort.on("message", async ({ scenario: rawScenario, jobs }) => {
  try {
    const validation = createBattleScenario(
      rawScenario,
      trainerIndex.trainers,
      itemResolver,
    );
    if (!validation.ok) {
      parentPort.postMessage({ ok: false, issues: validation.issues });
      return;
    }

    const results = [];
    for (const job of jobs) {
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
      results.push(battleSummary(battle, job.index));
    }
    parentPort.postMessage({ ok: true, results });
  } catch (error) {
    parentPort.postMessage({
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
    });
  }
});
