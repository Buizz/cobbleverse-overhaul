import trainerIndex from "../../../public/data/trainers.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import { createBattleScenario } from "../../../lib/battle-scenario.mjs";
import { createCobblemonItemResolver } from "../../../lib/cobblemon-item-catalog.mjs";

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

  const result = createBattleScenario(payload, trainerIndex.trainers, itemResolver);
  return Response.json(result, { status: result.ok ? 201 : 422 });
}
