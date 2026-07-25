import { randomUUID } from "node:crypto";

import {
  calculateDamageRange,
  chooseSimpleAiCommand,
  createSimpleBattle,
  replaceFaintedPokemon,
  resolveSimpleTurn,
} from "./cobbleverse-battle-engine.mjs";
import {
  createNativeBattleSetup,
  mapNativeEvent,
} from "./native-scenario-runner.mjs";
import { resolveNativeMaxMove } from "./native-max-moves.mjs";

const SESSION_TTL_MS = 30 * 60 * 1000;
const sessions = new Map();

function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function condition(pokemon) {
  return {
    text: pokemon.fainted
      ? "0 fnt"
      : `${pokemon.hp}/${pokemon.stats.hp}`,
    current: pokemon.hp,
    maximum: pokemon.stats.hp,
    percent: Math.max(
      0,
      Math.round((pokemon.hp / pokemon.stats.hp) * 1000) / 10,
    ),
    status: pokemon.status || null,
    fainted: pokemon.fainted,
  };
}

function publicPokemon(pokemon, slot, active, side) {
  return {
    slot,
    ident: `p${side + 1}a: ${pokemon.name}`,
    species: pokemon.name,
    types: pokemon.types,
    teraType: pokemon.teraType ?? "",
    terastallized: pokemon.terastallized ? pokemon.teraType : "",
    details: `${pokemon.name}, L${pokemon.level}`,
    condition: condition(pokemon),
    active,
  };
}

function effectivenessLabel(value) {
  if (value === 0) return "immune";
  if (value > 1) return "super";
  if (value < 1) return "resisted";
  return "neutral";
}

function publicMoves(active, opponent) {
  return active.moves.map((move, index) => {
    const range = calculateDamageRange(active, opponent, move);
    return {
      slot: index + 1,
      id: move.id,
      name: move.name,
      pp: move.pp,
      maxPp: move.maxPp,
      target: "normal",
      disabled: move.pp <= 0,
      type: move.type,
      category: move.category,
      power: move.power,
      accuracy: move.accuracy,
      priority: move.priority,
      effectiveness:
        move.category === "Status"
          ? "not_applicable"
          : effectivenessLabel(range.effectiveness),
    };
  });
}

function availableSwitches(side, sideIndex) {
  return side.team
    .map((pokemon, index) =>
      publicPokemon(pokemon, index + 1, index === side.active, sideIndex),
    )
    .filter((pokemon) => !pokemon.active && !pokemon.condition.fainted);
}

function aiDecision(state, command) {
  const side = state.sides[1];
  const active = side.team[side.active];
  const opponent = state.sides[0].team[state.sides[0].active];
  const candidates = publicMoves(active, opponent).map((move) => ({
    ...move,
    selected: move.slot === command.move,
  }));
  const selected = candidates.find((move) => move.selected);
  return {
    turn: state.turn + 1,
    actor: "AI",
    species: active.name,
    kind: "move",
    strategy: "cobbleverse-damage-baseline",
    chosenAction: selected?.name ?? "기술 선택",
    gimmick: command.gimmick ?? "",
    reason:
      command.gimmick === "dynamax"
        ? "엔트리의 다이맥스 강제 지시에 따라 이 턴에 다이맥스를 사용합니다."
        : "Cobbleverse 엔진이 예상 피해량과 KO 가능성뿐 아니라 회복, 상태이상, 랭크 변화의 전술 가치도 함께 비교했습니다.",
    candidates,
  };
}

function snapshot(session) {
  const { state, scenario } = session;
  const playerSide = state.sides[0];
  const opponentSide = state.sides[1];
  const player = playerSide.team[playerSide.active];
  const opponent = opponentSide.team[opponentSide.active];
  const playerTeam = playerSide.team.map((pokemon, index) =>
    publicPokemon(pokemon, index + 1, index === playerSide.active, 0),
  );
  const opponentTeam = opponentSide.team.map((pokemon, index) =>
    publicPokemon(pokemon, index + 1, index === opponentSide.active, 1),
  );
  const running = state.status === "running";
  const requiresReplacement =
    running &&
    player.fainted &&
    playerSide.team.some((pokemon) => !pokemon.fainted);
  const latestDecision = session.aiTrace.at(-1) ?? null;
  const usedGimmicks = playerSide.usedGimmicks ?? {};
  const megaStone = player.gimmicks?.megaStone;
  const playerSpeciesIds = new Set([cleanId(player.id), cleanId(player.name)]);
  const canMegaEvolve =
    cleanId(megaStone?.item) === cleanId(player.item) &&
    (!megaStone?.evolves || playerSpeciesIds.has(cleanId(megaStone.evolves)));
  const zCrystal = player.gimmicks?.zCrystal;
  const zMoves = player.moves.map((move) => {
    if (
      move.category === "Status" ||
      zCrystal?.item !== player.item ||
      (zCrystal.moveType && zCrystal.moveType !== move.type.toLowerCase()) ||
      (zCrystal.moveFrom && zCrystal.moveFrom !== move.id)
    ) {
      return null;
    }
    return {
      move: zCrystal.move || `Z-${move.name}`,
      target: "normal",
    };
  });
  // Cobblemon의 플레이어는 엔트리 플래그와 무관하게 전투당 한 번
  // 다이맥스를 직접 선택할 수 있다. 엔트리의 dynamax/gmax 값은
  // 컴퓨터 AI의 강제 발동 지시와 거다이맥스 개체 판정에만 사용한다.
  const canDynamax = true;
  const gigantamax = player.gimmicks?.gigantamax === true;
  const configuredTeraType = player.configuredTeraType || "";

  return {
    sessionId: session.id,
    scenarioId: scenario.scenarioId,
    engine: state.engine,
    settings: {
      battleEngine: "cobbleverse",
      battleType: "single",
      gimmickRules: "all",
      aiDifficulty: scenario.aiDifficulty,
      aiProfiles: scenario.aiProfiles,
    },
    status: running ? "awaiting_choice" : state.status,
    winner: state.winner,
    turns: state.turn,
    sides: [
      {
        name: playerSide.name,
        team: playerTeam.map(({ slot, species }) => ({ slot, species })),
      },
      {
        name: opponentSide.name,
        team: opponentTeam.map(({ slot, species }) => ({ slot, species })),
      },
    ],
    request: running
      ? {
          requestId: state.turn + 1,
          kind: requiresReplacement ? "force_switch" : "move",
          active: playerTeam[playerSide.active],
          team: playerTeam,
          moves: requiresReplacement ? [] : publicMoves(player, opponent),
          gimmicks: {
            canMegaEvo:
              !requiresReplacement && !usedGimmicks.mega && canMegaEvolve,
            megaVariant: "mega",
            zMoves:
              requiresReplacement || usedGimmicks.zmove ? [] : zMoves,
            canDynamax:
              !requiresReplacement && !usedGimmicks.dynamax && canDynamax,
            maxMoves:
              requiresReplacement || usedGimmicks.dynamax || !canDynamax
              ? []
              : player.moves.map((move) => {
                  const maxMove = resolveNativeMaxMove(player, move);
                  return {
                    id: maxMove.id,
                    move: maxMove.name,
                    target: "normal",
                  };
                }),
            gigantamax: gigantamax ? "gigantamax" : "",
            canTerastallize:
              requiresReplacement ||
              usedGimmicks.terastallize ||
              !configuredTeraType
              ? ""
              : configuredTeraType,
          },
          switches: availableSwitches(playerSide, 0),
          trapped: false,
          opponent: {
            species: opponent.name,
            types: opponent.types,
            moves: latestDecision?.candidates ?? publicMoves(opponent, player),
            decision: latestDecision
              ? {
                  strategy: latestDecision.strategy,
                  chosenAction: latestDecision.chosenAction,
                  reason: latestDecision.reason,
                }
              : null,
          },
        }
      : null,
    aiTrace: session.aiTrace,
    warnings: [
      {
        path: "battleEngine",
        code: "experimental_native_interactive",
        message:
          "Cobbleverse 자체 엔진 PvE는 상태 전이 기반 개발 버전입니다. 효과 카탈로그에 없는 기술은 전투 로그에 명시됩니다.",
      },
    ],
    error: null,
    events: state.events.flatMap(mapNativeEvent),
    log: state.events.map((event) => JSON.stringify(event)),
  };
}

function removeExpiredSessions() {
  const cutoff = Date.now() - SESSION_TTL_MS;
  for (const [id, session] of sessions) {
    if (session.updatedAt < cutoff) sessions.delete(id);
  }
}

function playerCommand(state, action) {
  const type = String(action?.type ?? "");
  const slot = Number(action?.slot);
  if (!Number.isInteger(slot)) {
    throw new Error("올바른 기술 또는 교체 슬롯을 선택해 주세요.");
  }
  if (type === "move") {
    const active = state.sides[0].team[state.sides[0].active];
    const move = active.moves[slot - 1];
    if (!move || move.pp <= 0) {
      throw new Error("현재 사용할 수 없는 기술입니다.");
    }
    const gimmick = String(action?.gimmick ?? "");
    return {
      move: slot,
      ...(gimmick ? { gimmick } : {}),
      ...(gimmick === "terastallize"
        ? { teraType: active.teraType || active.types[0] || "Normal" }
        : {}),
    };
  }
  if (type === "switch") return { switch: slot };
  throw new Error("지원하지 않는 전투 행동입니다.");
}

export function startNativeInteractiveBattle(scenario) {
  if (scenario.mode !== "pve") {
    throw new Error("직접 조작 전투는 PvE 모드에서만 지원합니다.");
  }
  if ((scenario.battleType ?? "single") !== "single") {
    throw new Error("Cobbleverse 자체 엔진 PvE는 현재 싱글 배틀만 지원합니다.");
  }
  removeExpiredSessions();
  const session = {
    id: `native-${randomUUID()}`,
    scenario,
    state: createSimpleBattle({
      ...createNativeBattleSetup(scenario),
      manualFaintSwitchSides: [0],
    }),
    aiTrace: [],
    updatedAt: Date.now(),
  };
  sessions.set(session.id, session);
  return snapshot(session);
}

export function chooseNativeInteractiveBattleAction(sessionId, action) {
  removeExpiredSessions();
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("자체 엔진 전투 세션을 찾을 수 없습니다. 다시 시작해 주세요.");
  }
  if (session.state.status !== "running") {
    throw new Error("이미 종료된 전투입니다.");
  }
  const playerSide = session.state.sides[0];
  if (playerSide.team[playerSide.active].fainted) {
    if (String(action?.type ?? "") !== "switch") {
      throw new Error("쓰러지지 않은 포켓몬을 선택해 교체해 주세요.");
    }
    session.state = replaceFaintedPokemon(session.state, 0, Number(action?.slot));
    session.updatedAt = Date.now();
    return snapshot(session);
  }
  const aiCommand = chooseSimpleAiCommand(
    session.state,
    1,
    session.scenario.aiProfiles?.[1]?.difficulty ??
      session.scenario.aiDifficulty,
    session.scenario.aiProfiles?.[1]?.strategy ?? "balanced",
  );
  session.aiTrace.push(aiDecision(session.state, aiCommand));
  try {
    session.state = resolveSimpleTurn(session.state, [
      playerCommand(session.state, action),
      aiCommand,
    ]);
    session.updatedAt = Date.now();
    return snapshot(session);
  } catch (error) {
    session.aiTrace.pop();
    throw error;
  }
}

export function forfeitNativeInteractiveBattle(sessionId) {
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) throw new Error("자체 엔진 전투 세션을 찾을 수 없습니다.");
  sessions.delete(session.id);
  session.state.status = "completed";
  session.state.winner = session.state.sides[1].name;
  session.state.events.push({
    turn: session.state.turn,
    type: "win",
    winner: session.state.winner,
  });
  return snapshot(session);
}

export function clearNativeInteractiveBattleSessions() {
  sessions.clear();
}
