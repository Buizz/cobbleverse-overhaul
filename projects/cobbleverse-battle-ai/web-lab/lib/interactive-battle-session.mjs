import { randomUUID } from "node:crypto";
import { BattleStreams, Dex } from "@pkmn/sim";

import {
  requireBattleFormat,
  showdownFormatId,
} from "./battle-formats.mjs";
import {
  battleEvents,
  convertScenarioTeams,
  megaSpeciesFromProtocol,
} from "./showdown-battle-runner.mjs";

const SESSION_TTL_MS = 30 * 60 * 1000;
const sessions = new Map();

function toGen5Seed(seed, salt = 0) {
  let state = (Number(seed) ^ salt ^ 0x9e3779b9) >>> 0;
  const result = [];
  for (let index = 0; index < 4; index += 1) {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    result.push(state & 0xffff);
  }
  return result;
}

function cleanCondition(condition) {
  const text = String(condition ?? "");
  const [health, ...statusParts] = text.split(" ");
  const [currentText, maximumText] = health.split("/");
  const current = Number(currentText);
  const maximum = Number(maximumText);
  return {
    text,
    current: Number.isFinite(current) ? current : null,
    maximum: Number.isFinite(maximum) ? maximum : null,
    percent:
      Number.isFinite(current) && Number.isFinite(maximum) && maximum > 0
        ? Math.max(0, Math.round((current / maximum) * 1000) / 10)
        : text.endsWith(" fnt")
          ? 0
          : null,
    status: statusParts.find((part) => part !== "fnt") ?? null,
    fainted: text.endsWith(" fnt"),
  };
}

function publicPokemon(pokemon, slot, configured = null) {
  const details = String(pokemon?.details ?? "");
  const species = details.split(",")[0] || `Slot ${slot}`;
  const dexSpecies = Dex.species.get(species);
  const terastallized = String(pokemon?.terastallized ?? "");
  return {
    slot,
    ident: String(pokemon?.ident ?? ""),
    species,
    ability: String(pokemon?.ability ?? configured?.ability ?? "") || null,
    item:
      String(pokemon?.item ?? pokemon?.heldItem ?? configured?.heldItem ?? "") ||
      null,
    heldItem:
      String(pokemon?.heldItem ?? pokemon?.item ?? configured?.heldItem ?? "") ||
      null,
    stats:
      pokemon?.stats && typeof pokemon.stats === "object"
        ? { ...pokemon.stats }
        : null,
    types: terastallized
      ? [terastallized]
      : dexSpecies.exists
        ? dexSpecies.types
        : [],
    teraType: String(pokemon?.teraType ?? ""),
    terastallized,
    details,
    condition: cleanCondition(pokemon?.condition),
    active: pokemon?.active === true,
  };
}

function moveEffectiveness(moveType, targetSpecies) {
  const target = Dex.species.get(targetSpecies);
  if (!target.exists) return "unknown";
  if (!Dex.getImmunity(moveType, target)) return "immune";
  const stage = Dex.getEffectiveness(moveType, target);
  if (stage > 0) return "super";
  if (stage < 0) return "resisted";
  return "neutral";
}

function activeSpeciesFromLog(log, side) {
  const active = new Map();
  for (const line of log) {
    const parts = line.split("|");
    if (line.startsWith("|switch|") || line.startsWith("|drag|")) {
      const ident = parts[2] ?? "";
      if (!ident.startsWith(side)) continue;
      active.set(ident.slice(0, 3), (parts[3] ?? "").split(",")[0]);
      continue;
    }
    if (!line.startsWith("|-mega|")) continue;
    const ident = parts[2] ?? "";
    if (!ident.startsWith(side)) continue;
    active.set(
      ident.slice(0, 3),
      megaSpeciesFromProtocol(ident, parts[3] ?? "", parts[4] ?? ""),
    );
  }
  return [...active.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([, species]) => species);
}

function publicActiveRequest(activeRequest, activePokemon, opponentSpecies = "") {
  const zMoves = Array.isArray(activeRequest?.canZMove)
    ? activeRequest.canZMove.map((move) =>
        move
          ? { move: String(move.move ?? ""), target: String(move.target ?? "") }
          : null,
      )
    : [];
  const maxMoves = Array.isArray(activeRequest?.maxMoves?.maxMoves)
    ? activeRequest.maxMoves.maxMoves.map((move) => {
        const id = String(move.move ?? "");
        const dexMove = Dex.moves.get(id);
        return {
          id,
          move: dexMove.exists ? dexMove.name : id,
          target: String(move.target ?? ""),
        };
      })
    : [];
  const moves = Array.isArray(activeRequest?.moves)
    ? activeRequest.moves.map((move, index) => {
        const moveId = String(move.id ?? "");
        const dexMove = Dex.moves.get(moveId);
        return {
          slot: index + 1,
          id: moveId,
          name: String(move.move ?? move.id ?? `Move ${index + 1}`),
          pp: Number(move.pp ?? 0),
          maxPp: Number(move.maxpp ?? 0),
          target: String(move.target ?? ""),
          disabled: move.disabled === true,
          type: dexMove.exists ? dexMove.type : "Normal",
          category: dexMove.exists ? dexMove.category : "Status",
          power: dexMove.exists ? dexMove.basePower : 0,
          accuracy: dexMove.exists ? dexMove.accuracy : true,
          priority: dexMove.exists ? dexMove.priority : 0,
          effectiveness: dexMove.exists
            ? dexMove.category === "Status"
              ? "not_applicable"
              : moveEffectiveness(dexMove.type, opponentSpecies)
            : "unknown",
        };
      })
    : [];
  return {
    active: activePokemon ?? null,
    moves,
    gimmicks: {
      canMegaEvo:
        activeRequest?.canMegaEvo === true ||
        activeRequest?.canMegaEvoX === true ||
        activeRequest?.canMegaEvoY === true,
      megaVariant: activeRequest?.canMegaEvoX
        ? "megax"
        : activeRequest?.canMegaEvoY
          ? "megay"
          : "mega",
      zMoves,
      canDynamax: activeRequest?.canDynamax === true,
      maxMoves,
      gigantamax: String(activeRequest?.maxMoves?.gigantamax ?? ""),
      canTerastallize: String(activeRequest?.canTerastallize ?? ""),
    },
    trapped: activeRequest?.trapped === true,
  };
}

function configuredPokemonForSpecies(team, species) {
  const targetId = Dex.toID(species);
  return (
    team.find((pokemon) => Dex.toID(pokemon.resolvedSpecies ?? pokemon.species) === targetId) ??
    team.find((pokemon) => Dex.toID(pokemon.species) === targetId) ??
    null
  );
}

function publicConfiguredPokemonDetails(pokemon) {
  if (!pokemon) return {};
  return {
    level: pokemon.level,
    gender: pokemon.gender,
    nature: pokemon.nature,
    ability: pokemon.ability,
    heldItem: pokemon.heldItem,
    item: pokemon.heldItem,
    aspects: pokemon.aspects,
    gimmicks: pokemon.gimmicks,
    moveset: pokemon.moveset,
    ivs: pokemon.ivs,
    evs: pokemon.evs,
  };
}

function publicRequest(request, opponentSpecies = [], aiTrace = [], scenario = null) {
  if (!request) return null;
  const configuredPlayerTeam = scenario?.sides?.[0]?.team ?? [];
  const configuredOpponentTeam = scenario?.sides?.[1]?.team ?? [];
  const team = Array.isArray(request.side?.pokemon)
    ? request.side.pokemon.map((pokemon, index) =>
        publicPokemon(pokemon, index + 1, configuredPlayerTeam[index]),
      )
    : [];
  const forceSwitch = Array.isArray(request.forceSwitch)
    ? request.forceSwitch.some(Boolean)
    : false;
  const activeRequests = Array.isArray(request.active) ? request.active : [];
  const activePokemon = team.filter((pokemon) => pokemon.active);
  const activeSlotCount = Math.max(
    activeRequests.length,
    Array.isArray(request.forceSwitch) ? request.forceSwitch.length : 0,
  );
  const activeSlots = Array.from({ length: activeSlotCount }, (_, index) => ({
      position: index + 1,
      ...publicActiveRequest(
        activeRequests[index],
        activePokemon[index],
        opponentSpecies[index] ?? opponentSpecies[0] ?? "",
      ),
    }));
  const primary = activeSlots[0] ?? publicActiveRequest(null, null);
  const activeRequest = activeRequests[0] ?? null;
  const canSwitch =
    forceSwitch || (activeRequest && activeRequest.trapped !== true);
  const switches = canSwitch
    ? team.filter((pokemon) => !pokemon.active && !pokemon.condition.fainted)
    : [];
  const opponentDecision = aiTrace.findLast(
    (entry) =>
      entry.kind === "move" &&
      opponentSpecies.some(
        (species) => Dex.toID(entry.species) === Dex.toID(species),
      ),
  );

  return {
    requestId: Number(request.rqid ?? 0),
    kind: forceSwitch ? "force_switch" : "move",
    active: primary.active,
    activeSlots,
    forceSwitch: Array.isArray(request.forceSwitch)
      ? request.forceSwitch.map(Boolean)
      : [],
    team,
    moves: primary.moves,
    gimmicks: primary.gimmicks,
    switches,
    trapped: primary.trapped,
    opponents: opponentSpecies.map((species, index) => ({
      position: index + 1,
      species,
      types: Dex.species.get(species).types,
      ...publicConfiguredPokemonDetails(
        configuredPokemonForSpecies(configuredOpponentTeam, species),
      ),
    })),
    opponent: opponentSpecies[0]
      ? {
          species: opponentSpecies[0],
          types: Dex.species.get(opponentSpecies[0]).types,
          ...publicConfiguredPokemonDetails(
            configuredPokemonForSpecies(configuredOpponentTeam, opponentSpecies[0]),
          ),
          moves: opponentDecision?.candidates ?? [],
          decision: opponentDecision
            ? {
                strategy: opponentDecision.strategy,
                chosenAction: opponentDecision.chosenAction,
                reason: opponentDecision.reason,
              }
            : null,
        }
      : null,
  };
}

function choiceTarget(request, active, action, activeIndex) {
  const target = Number(action?.target);
  const move = active.moves?.[Number(action?.slot) - 1];
  const targetType = String(move?.target ?? "");
  if (!["normal", "any", "adjacentFoe"].includes(targetType)) {
    return "";
  }
  const activeCount = request.active?.length ?? 1;
  if (activeCount <= 1 && (!Number.isInteger(target) || target === 0)) return "";
  if (!Number.isInteger(target) || target < 1 || target > activeCount) {
    throw new Error("공격 대상을 선택해 주세요.");
  }
  if (
    activeCount >= 3 &&
    targetType !== "any" &&
    Math.abs(target - (activeCount - activeIndex)) > 1
  ) {
    throw new Error("선택한 대상은 현재 포켓몬과 인접하지 않습니다.");
  }
  return ` ${target}`;
}

function buildSlotChoice(request, action, activeIndex = 0) {
  const actionType = String(action?.type ?? "");
  const slot = Number(action?.slot);
  if (!Number.isInteger(slot) || slot < 1 || slot > 6) {
    throw new Error("올바른 기술 또는 교체 슬롯을 선택해 주세요.");
  }

  if (Array.isArray(request.forceSwitch) && request.forceSwitch[activeIndex]) {
    if (actionType !== "switch") {
      throw new Error("쓰러진 포켓몬을 대신할 포켓몬을 선택해야 합니다.");
    }
    const pokemon = request.side?.pokemon?.[slot - 1];
    if (!pokemon || pokemon.active || String(pokemon.condition).endsWith(" fnt")) {
      throw new Error("현재 교체할 수 없는 포켓몬입니다.");
    }
    return `switch ${slot}`;
  }

  const active = request.active?.[activeIndex];
  if (!active) {
    throw new Error("현재 기술을 선택할 수 있는 포켓몬이 없습니다.");
  }
  if (actionType === "move") {
    const move = active.moves?.[slot - 1];
    if (!move || move.disabled) {
      throw new Error("현재 사용할 수 없는 기술입니다.");
    }
    const gimmick = String(action?.gimmick ?? "");
    if (gimmick === "mega") {
      if (!active.canMegaEvo && !active.canMegaEvoX && !active.canMegaEvoY) {
        throw new Error("현재 포켓몬은 메가진화할 수 없습니다.");
      }
      const megaVariant = active.canMegaEvoX
        ? "megax"
        : active.canMegaEvoY
          ? "megay"
          : "mega";
      return `move ${slot}${choiceTarget(request, active, action, activeIndex)} ${megaVariant}`;
    }
    if (gimmick === "zmove") {
      if (!active.canZMove?.[slot - 1]) {
        throw new Error("선택한 기술은 Z기술로 사용할 수 없습니다.");
      }
      return `move ${slot}${choiceTarget(request, active, action, activeIndex)} zmove`;
    }
    if (gimmick === "dynamax") {
      if (!active.canDynamax) {
        throw new Error("현재 포켓몬은 다이맥스할 수 없습니다.");
      }
      return `move ${slot}${choiceTarget(request, active, action, activeIndex)} dynamax`;
    }
    if (gimmick === "terastallize") {
      if (!active.canTerastallize) {
        throw new Error("현재 포켓몬은 테라스탈할 수 없습니다.");
      }
      return `move ${slot}${choiceTarget(request, active, action, activeIndex)} terastallize`;
    }
    return `move ${slot}${choiceTarget(request, active, action, activeIndex)}`;
  }
  if (actionType === "switch") {
    if (active.trapped) {
      throw new Error("현재 포켓몬은 교체할 수 없습니다.");
    }
    const pokemon = request.side?.pokemon?.[slot - 1];
    if (!pokemon || pokemon.active || String(pokemon.condition).endsWith(" fnt")) {
      throw new Error("현재 교체할 수 없는 포켓몬입니다.");
    }
    return `switch ${slot}`;
  }
  throw new Error("지원하지 않는 전투 행동입니다.");
}

function buildChoice(request, action) {
  const activeCount = Math.max(
    request.active?.length ?? 0,
    request.forceSwitch?.length ?? 0,
  );
  if (activeCount <= 1 && action?.type !== "multi") {
    return buildSlotChoice(request, action, 0);
  }
  const actions = Array.isArray(action?.actions) ? action.actions : [];
  if (actions.length !== activeCount) {
    throw new Error(`출전 중인 포켓몬 ${activeCount}마리의 행동을 모두 선택해 주세요.`);
  }
  const choices = actions.map((slotAction, index) => {
    if (request.forceSwitch?.length && !request.forceSwitch[index]) return "pass";
    return buildSlotChoice(request, slotAction, index);
  });
  const switchSlots = choices
    .filter((choice) => choice.startsWith("switch "))
    .map((choice) => choice.split(" ")[1]);
  if (new Set(switchSlots).size !== switchSlots.length) {
    throw new Error("같은 포켓몬으로 두 슬롯을 동시에 교체할 수 없습니다.");
  }
  return choices.join(", ");
}

class ReplayPlayer extends BattleStreams.BattlePlayer {
  constructor(stream, actions, pause) {
    super(stream);
    this.actions = actions;
    this.actionIndex = 0;
    this.pause = pause;
    this.lastError = null;
  }

  receiveRequest(request) {
    if (request.wait) return;
    if (request.teamPreview) {
      this.choose("default");
      return;
    }
    if (this.actionIndex < this.actions.length) {
      const action = this.actions[this.actionIndex];
      this.actionIndex += 1;
      this.choose(buildChoice(request, action));
      return;
    }
    this.pause(request);
  }

  receiveError(error) {
    this.lastError = error.message;
  }
}

function moveCandidate(move, index) {
  const id = String(move?.id ?? "");
  const dexMove = Dex.moves.get(id);
  return {
    slot: index + 1,
    id,
    name: String(move?.move ?? move?.id ?? `Move ${index + 1}`),
    pp: Number(move?.pp ?? 0),
    maxPp: Number(move?.maxpp ?? 0),
    disabled: move?.disabled === true,
    type: dexMove.exists ? dexMove.type : "Normal",
    category: dexMove.exists ? dexMove.category : "Status",
    power: dexMove.exists ? dexMove.basePower : 0,
    accuracy: dexMove.exists ? dexMove.accuracy : true,
    priority: dexMove.exists ? dexMove.priority : 0,
    selected: false,
  };
}

class TracedOpponentAI extends BattleStreams.BattlePlayer {
  constructor(stream, seed, trace, difficulty = "standard", configuredTeam = []) {
    super(stream);
    this.state = (Number(seed) ^ 0x5032 ^ 0x9e3779b9) >>> 0;
    this.trace = trace;
    this.decision = 0;
    this.difficulty = difficulty;
    this.configuredTeam = configuredTeam;
  }

  nextIndex(length) {
    this.state ^= this.state << 13;
    this.state ^= this.state >>> 17;
    this.state ^= this.state << 5;
    this.state >>>= 0;
    return length > 0 ? this.state % length : 0;
  }

  selectMove(available) {
    if (this.difficulty === "novice") {
      return available[this.nextIndex(available.length)];
    }
    const score = (move) => {
      const accuracy = move.accuracy === true ? 1 : move.accuracy / 100;
      const priorityWeight =
        this.difficulty === "expert" || this.difficulty === "cheater"
          ? 12
          : 5;
      return move.power * accuracy + move.priority * priorityWeight;
    };
    const ranked = [...available].sort(
      (left, right) =>
        score(right) - score(left) || left.slot - right.slot,
    );
    if (this.difficulty === "standard" && ranked.length > 1) {
      return ranked[this.nextIndex(4) === 0 ? 1 : 0];
    }
    return ranked[0];
  }

  receiveRequest(request) {
    if (request.wait) return;
    if (request.teamPreview) {
      this.choose("default");
      return;
    }

    const team = Array.isArray(request.side?.pokemon)
      ? request.side.pokemon
      : [];
    const activePokemon = team.filter((pokemon) => pokemon.active);
    const forceSwitch = Array.isArray(request.forceSwitch)
      ? request.forceSwitch.some(Boolean)
      : false;

    if (forceSwitch) {
      const switches = team
        .map((pokemon, index) => ({ pokemon, slot: index + 1 }))
        .filter(
          ({ pokemon }) =>
            !pokemon.active && !String(pokemon.condition).endsWith(" fnt"),
        );
      const used = new Set();
      const choices = request.forceSwitch.map((required, activeIndex) => {
        if (!required) return "pass";
        const selected = switches.find(({ slot }) => !used.has(slot));
        if (!selected) return "default";
        used.add(selected.slot);
        const species =
          String(activePokemon[activeIndex]?.details ?? "").split(",")[0] ||
          "Opponent";
        this.trace.push({
          turn: this.decision,
          actor: "AI",
          species,
          kind: "switch",
          strategy: `${this.difficulty}-baseline`,
          chosenAction: `교체 ${selected.slot}`,
          reason: "기절 후 교체 가능한 포켓몬 중 중복되지 않는 후보를 선택했습니다.",
          candidates: [],
        });
        return `switch ${selected.slot}`;
      });
      if (choices.some((choice) => choice === "default")) {
        this.choose("default");
        return;
      }
      this.choose(choices.join(", "));
      return;
    }

    const choices = request.active.map((activeRequest, activeIndex) => {
      const candidates = Array.isArray(activeRequest?.moves)
        ? activeRequest.moves.map(moveCandidate)
        : [];
      const available = candidates.filter(
        (move) => !move.disabled && move.pp > 0,
      );
      if (available.length === 0) return "default";
      const selected = this.selectMove(available);
      const activeTeamIndex = team.findIndex(
        (pokemon) => pokemon === activePokemon[activeIndex],
      );
      const configured = this.configuredTeam[activeTeamIndex];
      let gimmick = "";
      if (activeRequest.canMegaEvo) gimmick = "mega";
      else if (activeRequest.canMegaEvoX) gimmick = "megax";
      else if (activeRequest.canMegaEvoY) gimmick = "megay";
      else if (activeRequest.canZMove?.[selected.slot - 1]) gimmick = "zmove";
      else if (activeRequest.canDynamax && configured?.gimmicks?.dynamax) {
        gimmick = "dynamax";
      } else if (activeRequest.canTerastallize && configured?.gimmicks?.tera) {
        gimmick = "terastallize";
      }
      const tracedCandidates = candidates.map((move) => ({
        ...move,
        selected: move.slot === selected.slot,
      }));
      const species =
        String(activePokemon[activeIndex]?.details ?? "").split(",")[0] ||
        "Opponent";
      this.decision += 1;
      this.trace.push({
        turn: this.decision,
        actor: "AI",
        species,
        kind: "move",
        strategy: `${this.difficulty}-baseline`,
        chosenAction: selected.name,
        gimmick,
        reason:
          this.difficulty === "novice"
            ? `초급 AI가 사용 가능한 기술 ${available.length}개 중 ${selected.name}을 선택했습니다.`
            : `${this.difficulty === "standard" ? "보통" : this.difficulty === "advanced" ? "상급" : "전문가"} AI가 위력·명중률·우선도를 비교해 ${selected.name}을 선택했습니다.`,
        candidates: tracedCandidates,
      });
      const targetType = String(activeRequest.moves?.[selected.slot - 1]?.target);
      const target = ["normal", "any", "adjacentFoe"].includes(targetType)
        ? ` ${Math.max(1, request.active.length - activeIndex)}`
        : "";
      return `move ${selected.slot}${target}${gimmick ? ` ${gimmick}` : ""}`;
    });
    if (choices.some((choice) => choice === "default")) {
      this.choose("default");
      return;
    }
    this.choose(choices.join(", "));
  }
}

async function replayBattle(session) {
  const format = requireBattleFormat(session.scenario.battleType ?? "single");
  const showdownFormat = showdownFormatId(format, session.scenario.gimmickRules);
  const { packedTeams } = convertScenarioTeams(session.scenario);
  const streams = BattleStreams.getPlayerStreams(
    new BattleStreams.BattleStream(),
  );
  const chunks = [];
  const aiTrace = [];
  let pendingRequest = null;
  let pauseResolved = false;
  let resolvePause;
  const pausePromise = new Promise((resolve) => {
    resolvePause = resolve;
  });
  const player = new ReplayPlayer(streams.p1, session.actions, (request) => {
    if (pauseResolved) return;
    pauseResolved = true;
    pendingRequest = request;
    resolvePause("choice");
  });
  const opponent = new TracedOpponentAI(
    streams.p2,
    session.scenario.seed,
    aiTrace,
    session.scenario.aiDifficulty,
    session.scenario.sides[1].team,
  );

  const playerTask = player.start();
  const opponentTask = opponent.start();
  const playerFailure = playerTask.then(
    () => new Promise(() => {}),
    (error) => ({ kind: "stream_error", error }),
  );
  const opponentFailure = opponentTask.then(
    () => new Promise(() => {}),
    (error) => ({ kind: "stream_error", error }),
  );
  const logTask = (async () => {
    for await (const chunk of streams.omniscient) {
      chunks.push(chunk);
    }
    return "finished";
  })();

  const battleSeed = toGen5Seed(session.scenario.seed, 0x42415454);
  void streams.omniscient.write(
    `>start ${JSON.stringify({ formatid: showdownFormat, seed: battleSeed })}\n` +
      `>player p1 ${JSON.stringify({ name: session.scenario.sides[0].name, team: packedTeams[0] })}\n` +
      `>player p2 ${JSON.stringify({ name: session.scenario.sides[1].name, team: packedTeams[1] })}`,
  );

  const outcome = await Promise.race([
    pausePromise,
    logTask,
    playerFailure,
    opponentFailure,
  ]);
  if (outcome?.kind === "stream_error") {
    void streams.omniscient.write(">forcetie");
    await Promise.allSettled([logTask, playerTask, opponentTask]);
    throw outcome.error;
  }
  if (outcome === "choice") {
    await Promise.resolve();
    const visibleChunks = [...chunks];
    void streams.omniscient.write(">forcetie");
    await logTask;
    await Promise.allSettled([playerTask, opponentTask]);
    return {
      request: pendingRequest,
      error: player.lastError,
      aiTrace,
      log: visibleChunks.join("\n").split(/\r?\n/).filter(Boolean),
    };
  }

  await Promise.allSettled([playerTask, opponentTask]);
  return {
    request: null,
    error: player.lastError,
    aiTrace,
    log: chunks.join("\n").split(/\r?\n/).filter(Boolean),
  };
}

function sessionSnapshot(session, replay) {
  const winLine = replay.log.findLast((line) => line.startsWith("|win|"));
  const tied = replay.log.some((line) => line === "|tie|");
  const turns = replay.log
    .filter((line) => line.startsWith("|turn|"))
    .map((line) => Number(line.slice("|turn|".length)))
    .reduce((highest, turn) => Math.max(highest, turn), 0);
  const finished = replay.request === null;
  const opponentSpecies = activeSpeciesFromLog(replay.log, "p2");

  return {
    sessionId: session.id,
    scenarioId: session.scenario.scenarioId,
    status: finished ? (tied ? "tie" : "completed") : "awaiting_choice",
    winner: winLine ? winLine.slice("|win|".length) : null,
    turns,
    sides: session.scenario.sides.map((side) => ({
      name: side.name,
      team: side.team.map((member) => ({
        ...member,
      })),
    })),
    request: finished
      ? null
      : publicRequest(replay.request, opponentSpecies, replay.aiTrace, session.scenario),
    aiTrace: replay.aiTrace,
    warnings: session.warnings,
    error: replay.error,
    events: battleEvents(replay.log),
    log: replay.log,
  };
}

function removeExpiredSessions() {
  const cutoff = Date.now() - SESSION_TTL_MS;
  for (const [id, session] of sessions) {
    if (session.updatedAt < cutoff) sessions.delete(id);
  }
}

export async function startInteractiveBattle(scenario) {
  if (scenario.mode !== "pve") {
    throw new Error("직접 조작 전투는 현재 PvE 모드에서만 지원합니다.");
  }
  const format = requireBattleFormat(scenario.battleType ?? "single");
  if (!format.supportsInteractive) {
    throw new Error("선택한 배틀 형식은 직접 조작을 지원하지 않습니다.");
  }
  removeExpiredSessions();
  const { warnings } = convertScenarioTeams(scenario);
  const session = {
    id: randomUUID(),
    scenario,
    actions: [],
    warnings,
    updatedAt: Date.now(),
  };
  sessions.set(session.id, session);
  return sessionSnapshot(session, await replayBattle(session));
}

export async function chooseInteractiveBattleAction(sessionId, action) {
  removeExpiredSessions();
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("전투 세션을 찾을 수 없습니다. 전투를 다시 시작해 주세요.");
  }
  session.actions.push(action);
  session.updatedAt = Date.now();
  try {
    return sessionSnapshot(session, await replayBattle(session));
  } catch (error) {
    session.actions.pop();
    throw error;
  }
}

export async function forfeitInteractiveBattle(sessionId) {
  const session = sessions.get(String(sessionId ?? ""));
  if (!session) {
    throw new Error("전투 세션을 찾을 수 없습니다.");
  }
  sessions.delete(session.id);
  return {
    sessionId: session.id,
    scenarioId: session.scenario.scenarioId,
    status: "completed",
    winner: session.scenario.sides[1].name,
    turns: session.actions.length,
    sides: session.scenario.sides.map((side) => ({
      name: side.name,
      team: side.team,
    })),
    request: null,
    aiTrace: [],
    warnings: session.warnings,
    error: null,
    events: [],
    log: [],
  };
}

export function clearInteractiveBattleSessions() {
  sessions.clear();
}
