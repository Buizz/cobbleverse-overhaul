import trainerIndex from "../../../public/data/trainers.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import { createBattleScenario } from "../../../lib/battle-scenario.mjs";
import { createCobblemonItemResolver } from "../../../lib/cobblemon-item-catalog.mjs";
import {
  chooseInteractiveBattleAction,
  forfeitInteractiveBattle,
  startInteractiveBattle,
} from "../../../lib/interactive-battle-session.mjs";
import {
  chooseNativeInteractiveBattleAction,
  forfeitNativeInteractiveBattle,
  startNativeInteractiveBattle,
} from "../../../lib/native-interactive-battle-session.mjs";

const itemResolver = createCobblemonItemResolver(itemCatalog);

function failure(message: string, code = "interactive_battle_failed") {
  return Response.json(
    {
      ok: false,
      issues: [{ path: "$", code, message }],
    },
    { status: 422 },
  );
}

export async function POST(request: Request) {
  let payload: unknown;
  try {
    payload = await request.json();
  } catch {
    return failure("올바른 JSON 요청이 아닙니다.", "invalid_json");
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return failure("올바른 전투 요청 객체가 아닙니다.", "invalid_payload");
  }
  const body = payload as Record<string, unknown>;

  try {
    if (body.operation === "start") {
      const validation = createBattleScenario(
        body.scenario,
        trainerIndex.trainers,
        itemResolver,
      );
      if (!validation.ok) {
        return Response.json(validation, { status: 422 });
      }
      const battle =
        validation.scenario.battleEngine === "cobbleverse"
          ? startNativeInteractiveBattle(validation.scenario)
          : await startInteractiveBattle(validation.scenario);
      return Response.json({ ok: true, battle }, { status: 201 });
    }
    if (body.operation === "choose") {
      const battle = String(body.sessionId ?? "").startsWith("native-")
        ? chooseNativeInteractiveBattleAction(body.sessionId, body.action)
        : await chooseInteractiveBattleAction(body.sessionId, body.action);
      return Response.json({ ok: true, battle });
    }
    if (body.operation === "forfeit") {
      const battle = String(body.sessionId ?? "").startsWith("native-")
        ? forfeitNativeInteractiveBattle(body.sessionId)
        : await forfeitInteractiveBattle(body.sessionId);
      return Response.json({ ok: true, battle });
    }
    return failure("지원하지 않는 대화형 전투 작업입니다.", "unsupported_operation");
  } catch (error) {
    return failure(
      error instanceof Error ? error.message : "전투 요청을 처리하지 못했습니다.",
    );
  }
}
