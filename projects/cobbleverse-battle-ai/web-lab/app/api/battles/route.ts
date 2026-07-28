import trainerIndex from "../../../public/data/trainers.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import nativeMoveCoverage from "../../../public/data/native-mechanics-coverage.json";
import { createBattleScenario } from "../../../lib/battle-scenario.mjs";
import { createCobblemonItemResolver } from "../../../lib/cobblemon-item-catalog.mjs";
import { findNativeMoveSupportWarnings } from "../../../lib/native-move-support.mjs";
import { runNativeScenarioBattle } from "../../../lib/native-scenario-runner.mjs";
import { runAutomaticBattle } from "../../../lib/showdown-battle-runner.mjs";

const itemResolver = createCobblemonItemResolver(itemCatalog);

export async function POST(request: Request) {
  let payload: unknown;
  try {
    payload = await request.json();
  } catch {
    return Response.json(
      {
        ok: false,
        issues: [{ path: "$", code: "invalid_json", message: "올바른 JSON 요청이 아닙니다." }],
      },
      { status: 400 },
    );
  }

  const validation = createBattleScenario(
    payload,
    trainerIndex.trainers,
    itemResolver,
  );
  if (!validation.ok) {
    return Response.json(validation, { status: 422 });
  }

  try {
    const moveSupportWarnings = findNativeMoveSupportWarnings(
      validation.scenario,
      nativeMoveCoverage,
    );
    const battle =
      validation.scenario.battleEngine === "cobbleverse"
        ? runNativeScenarioBattle(validation.scenario)
        : await runAutomaticBattle(validation.scenario);
    return Response.json(
      {
        ok: true,
        scenario: validation.scenario,
        battle: {
          ...battle,
          warnings: [...(battle.warnings ?? []), ...moveSupportWarnings],
        },
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
            code: "battle_execution_failed",
            message:
              error instanceof Error
                ? error.message
                : "전투 시뮬레이터가 실행을 완료하지 못했습니다.",
          },
        ],
      },
      { status: 422 },
    );
  }
}
