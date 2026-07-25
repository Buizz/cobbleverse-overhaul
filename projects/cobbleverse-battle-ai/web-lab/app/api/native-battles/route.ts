import { runSimpleBattle } from "../../../lib/cobbleverse-battle-engine.mjs";

export async function POST(request: Request) {
  try {
    const setup = await request.json();
    const battle = runSimpleBattle(setup);
    return Response.json({ ok: true, battle }, { status: 201 });
  } catch (error) {
    return Response.json(
      {
        ok: false,
        issues: [
          {
            path: "$",
            code: "invalid_native_battle",
            message:
              error instanceof Error
                ? error.message
                : "자체 엔진 전투 입력을 처리하지 못했습니다.",
          },
        ],
      },
      { status: 422 },
    );
  }
}

