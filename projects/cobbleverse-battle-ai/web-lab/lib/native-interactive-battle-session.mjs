import { randomUUID } from "node:crypto";

import {
  calculateMovePreview,
  canPokemonUseTerastallization,
  createSimpleAiDecisionTrace,
  createSimpleBattle,
  isMoveBlockedByDynamaxTarget,
  isMoveTemporarilyDisabled,
  replaceFaintedPokemon,
  resolveSimpleCheaterDecision,
  resolveSimpleTurn,
} from "./cobbleverse-battle-engine.mjs";
import {
  createNativeBattleSetup,
  mapNativeEvent,
} from "./native-scenario-runner.mjs";
import {
  isNativeGigantamaxSpecies,
  resolveNativeMaxMove,
} from "./native-max-moves.mjs";
import {
  scoreAiMoveCandidate,
} from "./common-battle-ai.mjs";

const SESSION_TTL_MS = 30 * 60 * 1000;
const SAVE_SLOT_COUNT = 5;
const MAX_CHECKPOINTS = 100;
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
    ability: pokemon.ability || null,
    item: pokemon.item || null,
    heldItem: pokemon.item || null,
    stats: { ...pokemon.stats },
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

function publicMoveData(pokemon, move) {
  if (pokemon.dynamaxTurns <= 0) return move;
  const maxMove = resolveNativeMaxMove(pokemon, move);
  const isStatus = move.category === "Status";
  return {
    ...move,
    id: maxMove.id,
    name: maxMove.name,
    accuracy: true,
    priority: 0,
    power: isStatus ? 0 : Math.max(90, Math.min(150, move.power * 1.35)),
    target: isStatus ? "self" : move.target,
  };
}

function publicMoves(
  active,
  opponent,
  difficulty = "standard",
  strategy = "balanced",
  state = null,
  attackerSide = 0,
  defenderSide = 1,
) {
  return active.moves.map((move, index) => {
    const selectedMove = publicMoveData(active, move);
    const preview = calculateMovePreview(active, opponent, selectedMove, {
      state,
      attackerSide,
      defenderSide,
    });
    const displayMove = preview.move;
    const range = preview.range;
    const accuracy =
      displayMove.accuracy === true ? 1 : Number(displayMove.accuracy ?? 100) / 100;
    const expectedDamage = ((range.minimum + range.maximum) / 2) * accuracy;
    const koChance =
      range.maximum < opponent.hp
        ? "none"
        : range.minimum >= opponent.hp
          ? "guaranteed"
          : "possible";
    return {
      slot: index + 1,
      id: displayMove.id,
      name: displayMove.name,
      pp: move.pp,
      maxPp: move.maxPp,
      target: "normal",
      disabled:
        move.pp <= 0 ||
        isMoveBlockedByDynamaxTarget(displayMove, opponent) ||
        (active.dynamaxTurns <= 0 &&
          isMoveTemporarilyDisabled(active, move)),
      type: displayMove.type,
      category: displayMove.category,
      power: displayMove.power,
      accuracy: displayMove.accuracy,
      priority: displayMove.priority,
      effectiveness:
        displayMove.category === "Status"
          ? "not_applicable"
          : effectivenessLabel(range.effectiveness),
      expectedDamage,
      koChance,
      score: Math.round(
        scoreAiMoveCandidate(
          {
            ...displayMove,
            expectedDamage,
            koChance,
          },
          difficulty,
          strategy,
        ) * 100,
      ) / 100,
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

function diagnosticBattleState(state) {
  const battleState = structuredClone(state);
  delete battleState.events;
  delete battleState.aiTrace;
  delete battleState.turnSnapshots;
  return battleState;
}

function reproductionLog(session) {
  return {
    schema: "cobbleverse-native-pve-reproduction",
    version: 1,
    engine: session.state.engine,
    scenario: structuredClone(session.scenario),
    currentState: diagnosticBattleState(session.state),
    turns: structuredClone(session.reproductionFrames),
    events: structuredClone(session.state.events),
    aiTrace: structuredClone(session.aiTrace),
  };
}

function sessionCheckpoint(session) {
  return {
    turn: session.state.turn,
    state: structuredClone(session.state),
    aiTrace: structuredClone(session.aiTrace),
    reproductionFrames: structuredClone(session.reproductionFrames),
  };
}

function restoreSessionCheckpoint(session, checkpoint) {
  session.state = structuredClone(checkpoint.state);
  session.aiTrace = structuredClone(checkpoint.aiTrace);
  session.reproductionFrames = structuredClone(checkpoint.reproductionFrames);
}

function pushSessionCheckpoint(session, checkpoint) {
  session.history.push(checkpoint);
  if (session.history.length > MAX_CHECKPOINTS) {
    session.history.splice(0, session.history.length - MAX_CHECKPOINTS);
  }
}

function saveSlotMetadata(session) {
  return Array.from({ length: SAVE_SLOT_COUNT }, (_, index) => {
    const slot = index + 1;
    const saved = session.saveSlots.get(slot);
    return {
      slot,
      occupied: Boolean(saved),
      turn: saved?.checkpoint.turn ?? null,
      savedAt: saved?.savedAt ?? null,
    };
  });
}

function requireSaveSlot(slot) {
  const normalized = Number(slot);
  if (
    !Number.isInteger(normalized) ||
    normalized < 1 ||
    normalized > SAVE_SLOT_COUNT
  ) {
    throw new Error(`저장 슬롯은 1~${SAVE_SLOT_COUNT} 사이여야 합니다.`);
  }
  return normalized;
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
  const playerDynamaxed = player.dynamaxTurns > 0;
  const playerHasGimmickForm =
    player.megaEvolved === true ||
    playerDynamaxed ||
    player.terastallized === true;
  const megaStone = player.gimmicks?.megaStone;
  const playerSpeciesIds = new Set([cleanId(player.id), cleanId(player.name)]);
  const canMegaEvolve =
    cleanId(megaStone?.item) === cleanId(player.item) &&
    (!megaStone?.evolves || playerSpeciesIds.has(cleanId(megaStone.evolves)));
  const zCrystal = player.gimmicks?.zCrystal;
  const equippedZCrystal =
    zCrystal && cleanId(zCrystal.item) === cleanId(player.item)
      ? zCrystal
      : null;
  const zCrystalUserCompatible =
    !equippedZCrystal?.users?.length ||
    equippedZCrystal.users.some((species) => playerSpeciesIds.has(cleanId(species)));
  const zMoves = player.moves.map((move) => {
    if (
      move.category === "Status" ||
      !equippedZCrystal ||
      !zCrystalUserCompatible ||
      (equippedZCrystal.moveType &&
        cleanId(equippedZCrystal.moveType) !== cleanId(move.type)) ||
      (equippedZCrystal.moveFrom &&
        cleanId(equippedZCrystal.moveFrom) !== cleanId(move.id))
    ) {
      return null;
    }
    return {
      move: equippedZCrystal.move || `Z-${move.name}`,
      target: "normal",
    };
  });
  const zMoveReason = (() => {
    if (!equippedZCrystal) {
      return "현재 포켓몬이 Z크리스탈을 지니고 있지 않습니다.";
    }
    if (!zCrystalUserCompatible) {
      return `${equippedZCrystal.itemName || "이 Z크리스탈"}은(는) 현재 포켓몬이 사용할 수 없습니다.`;
    }
    if (equippedZCrystal.moveFrom) {
      return `${equippedZCrystal.itemName || "전용 Z크리스탈"}을 사용하려면 ${equippedZCrystal.moveFrom} 기술이 필요합니다.`;
    }
    if (equippedZCrystal.moveType) {
      return `${equippedZCrystal.itemName || "Z크리스탈"}과 같은 타입의 공격 기술이 필요합니다.`;
    }
    return "현재 기술 구성으로 사용할 수 있는 Z기술이 없습니다.";
  })();
  // Cobblemon의 플레이어는 엔트리 플래그와 무관하게 전투당 한 번
  // 다이맥스를 직접 선택할 수 있다. 거다이맥스 버튼은 엔트리 설정이
  // 아니라 실제 종이 거다이맥스 가능한지로 판정한다.
  const canDynamax = player.megaEvolved !== true;
  const gigantamax =
    player.gimmicks?.canGigantamax === true || isNativeGigantamaxSpecies(player);
  const canGigantamax = canDynamax && gigantamax;
  const configuredTeraType = player.configuredTeraType || "";
  const canUseTerastallization = canPokemonUseTerastallization(
    state,
    0,
    player,
  );

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
        team: playerTeam,
      },
      {
        name: opponentSide.name,
        team: opponentTeam,
      },
    ],
    request: running
      ? {
          requestId: state.turn + 1,
          kind: requiresReplacement ? "force_switch" : "move",
          active: playerTeam[playerSide.active],
          team: playerTeam,
          moves: requiresReplacement
            ? []
            : publicMoves(
                player,
                opponent,
                "standard",
                "balanced",
                state,
                0,
                1,
              ),
          gimmicks: {
            canMegaEvo:
              !requiresReplacement &&
              !playerDynamaxed &&
              player.terastallized !== true &&
              !usedGimmicks.mega &&
              canMegaEvolve,
            megaVariant: "mega",
            zMoves:
              requiresReplacement || playerHasGimmickForm || usedGimmicks.zmove
                ? []
                : zMoves,
            zCrystalName: equippedZCrystal?.itemName ?? "",
            zMoveReason,
            canDynamax:
              !requiresReplacement &&
              !usedGimmicks.dynamax &&
              canDynamax &&
              !gigantamax,
            canGigantamax:
              !requiresReplacement &&
              !usedGimmicks.dynamax &&
              canGigantamax,
            maxMoves:
              requiresReplacement ||
              (!playerDynamaxed && (usedGimmicks.dynamax || !canDynamax))
              ? []
              : player.moves.map((move) => {
                  const maxMove = resolveNativeMaxMove(
                    canGigantamax
                      ? { ...player, dynamaxMode: "gigantamax" }
                      : player,
                    move,
                  );
                  return {
                    id: maxMove.id,
                    move: maxMove.name,
                    target: "normal",
                  };
                }),
            gigantamax: gigantamax ? "gigantamax" : "",
            canTerastallize:
              requiresReplacement ||
              playerDynamaxed ||
              player.megaEvolved === true ||
              usedGimmicks.terastallize ||
              !canUseTerastallization ||
              !configuredTeraType
              ? ""
              : configuredTeraType,
          },
          switches: availableSwitches(playerSide, 0),
          trapped: false,
          opponent: {
            species: opponent.name,
            types: opponent.types,
            moves:
              latestDecision?.candidates ??
              publicMoves(
                opponent,
                player,
                "standard",
                "balanced",
                state,
                1,
                0,
              ),
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
    controls: {
      canUndo: session.history.some(
        (checkpoint) => checkpoint.turn < session.state.turn,
      ),
      saveSlots: saveSlotMetadata(session),
    },
    reproduction: reproductionLog(session),
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
    if (
      !move ||
      move.pp <= 0 ||
      (active.dynamaxTurns <= 0 &&
        isMoveTemporarilyDisabled(active, move))
    ) {
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
    reproductionFrames: [],
    history: [],
    saveSlots: new Map(),
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
    const before = diagnosticBattleState(session.state);
    const checkpoint = sessionCheckpoint(session);
    const eventStart = session.state.events.length;
    session.state = replaceFaintedPokemon(session.state, 0, Number(action?.slot));
    session.reproductionFrames.push({
      turn: session.state.turn,
      kind: "forced-replacement",
      before,
      playerAction: structuredClone(action),
      playerCommand: { switch: Number(action?.slot) },
      aiDecision: null,
      aiCommand: null,
      emittedEvents: structuredClone(session.state.events.slice(eventStart)),
      after: diagnosticBattleState(session.state),
    });
    pushSessionCheckpoint(session, checkpoint);
    session.updatedAt = Date.now();
    return snapshot(session);
  }
  const difficulty =
    session.scenario.aiProfiles?.[1]?.difficulty ??
    session.scenario.aiDifficulty;
  const strategy =
    session.scenario.aiProfiles?.[1]?.strategy ?? "balanced";
  const before = diagnosticBattleState(session.state);
  const checkpoint = sessionCheckpoint(session);
  const eventStart = session.state.events.length;
  const normalizedPlayerCommand = playerCommand(session.state, action);
  const aiDecision = resolveSimpleCheaterDecision(
    session.state,
    1,
    {
      difficulty,
      strategy,
      cheatProbability:
        session.scenario.aiProfiles?.[1]?.cheatProbability ?? 0.5,
    },
    normalizedPlayerCommand,
  );
  const aiCommand = aiDecision.command;
  const trace = createSimpleAiDecisionTrace(
    session.state,
    1,
    aiDecision,
    difficulty,
    strategy,
  );
  session.aiTrace.push(trace);
  try {
    session.state = resolveSimpleTurn(session.state, [
      normalizedPlayerCommand,
      aiCommand,
    ]);
    session.reproductionFrames.push({
      turn: session.state.turn,
      kind: "turn",
      before,
      playerAction: structuredClone(action),
      playerCommand: structuredClone(normalizedPlayerCommand),
      aiDecision: {
        command: structuredClone(aiCommand),
        diagnostics: structuredClone(aiDecision.diagnostics ?? null),
        trace: structuredClone(trace),
      },
      aiCommand: structuredClone(aiCommand),
      emittedEvents: structuredClone(session.state.events.slice(eventStart)),
      after: diagnosticBattleState(session.state),
    });
    pushSessionCheckpoint(session, checkpoint);
    session.updatedAt = Date.now();
    return snapshot(session);
  } catch (error) {
    session.aiTrace.pop();
    throw error;
  }
}

export function saveNativeInteractiveBattleSlot(sessionId, slot) {
  removeExpiredSessions();
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("자체 엔진 전투 세션을 찾을 수 없습니다. 다시 시작해 주세요.");
  }
  const normalizedSlot = requireSaveSlot(slot);
  session.saveSlots.set(normalizedSlot, {
    checkpoint: sessionCheckpoint(session),
    history: structuredClone(session.history),
    savedAt: new Date().toISOString(),
  });
  session.updatedAt = Date.now();
  return snapshot(session);
}

export function exportNativeInteractiveBattleSave(sessionId) {
  removeExpiredSessions();
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("전투 세션을 찾을 수 없습니다. 전투를 다시 시작해 주세요.");
  }
  return {
    schema: "cobbleverse-pve-battle-save",
    version: 1,
    battleEngine: "cobbleverse",
    scenario: structuredClone(session.scenario),
    turn: session.state.turn,
    savedAt: new Date().toISOString(),
    payload: {
      checkpoint: sessionCheckpoint(session),
      history: structuredClone(session.history.slice(-20)),
    },
  };
}

export function resumeNativeInteractiveBattle(save) {
  if (
    save?.schema !== "cobbleverse-pve-battle-save" ||
    save?.version !== 1 ||
    save?.battleEngine !== "cobbleverse" ||
    !save?.scenario ||
    !save?.payload?.checkpoint?.state
  ) {
    throw new Error("올바른 Cobbleverse PvE 전투 저장 데이터가 아닙니다.");
  }
  removeExpiredSessions();
  const session = {
    id: `native-${randomUUID()}`,
    scenario: structuredClone(save.scenario),
    state: structuredClone(save.payload.checkpoint.state),
    aiTrace: structuredClone(save.payload.checkpoint.aiTrace ?? []),
    reproductionFrames: structuredClone(
      save.payload.checkpoint.reproductionFrames ?? [],
    ),
    history: structuredClone(save.payload.history ?? []),
    saveSlots: new Map(),
    updatedAt: Date.now(),
  };
  sessions.set(session.id, session);
  return snapshot(session);
}

export function loadNativeInteractiveBattleSlot(sessionId, slot) {
  removeExpiredSessions();
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("자체 엔진 전투 세션을 찾을 수 없습니다. 다시 시작해 주세요.");
  }
  const normalizedSlot = requireSaveSlot(slot);
  const saved = session.saveSlots.get(normalizedSlot);
  if (!saved) {
    throw new Error(`${normalizedSlot}번 저장 슬롯이 비어 있습니다.`);
  }
  restoreSessionCheckpoint(session, saved.checkpoint);
  session.history = structuredClone(saved.history);
  session.updatedAt = Date.now();
  return snapshot(session);
}

export function undoNativeInteractiveBattleTurn(sessionId) {
  removeExpiredSessions();
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("자체 엔진 전투 세션을 찾을 수 없습니다. 다시 시작해 주세요.");
  }
  const checkpointIndex = session.history.findLastIndex(
    (checkpoint) => checkpoint.turn < session.state.turn,
  );
  if (checkpointIndex < 0) {
    throw new Error("돌아갈 이전 턴이 없습니다.");
  }
  const [checkpoint] = session.history.splice(checkpointIndex);
  restoreSessionCheckpoint(session, checkpoint);
  session.updatedAt = Date.now();
  return snapshot(session);
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
