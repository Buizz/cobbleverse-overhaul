import { BattleStreams, Dex, Teams } from "@pkmn/sim";
import {
  requireBattleFormat,
  showdownFormatId,
} from "./battle-formats.mjs";
import {
  createShowdownPlayerAI,
  showdownControllerId,
} from "./showdown-player-ai.mjs";
import { resolveShowdownMemberSpecies } from "./showdown-species.mjs";
import {
  explicitTeraType,
  seededNativeTeraType,
} from "./virtual-tera-policy.mjs";

const ENGINE_ID = "pokemon-showdown";
const ENGINE_VERSION = "0.10.11";
const MAX_TURNS = 200;
const TIMEOUT_MS = 10_000;

function warning(path, code, message) {
  return { path, code, message };
}

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

function identSpecies(ident) {
  return String(ident ?? "").replace(/^p[12][a-z]?: /, "").trim();
}

function normalizedSpeciesName(value) {
  const species = Dex.species.get(String(value ?? ""));
  return species.exists ? species.name : "";
}

export function megaSpeciesFromProtocol(actor, detail, condition) {
  const values = [condition, detail].filter(Boolean);
  for (const value of values) {
    const item = Dex.items.get(String(value));
    if (!item.exists || !item.megaStone) continue;
    return normalizedSpeciesName(item.megaStone) || String(item.megaStone);
  }

  for (const value of [detail, condition]) {
    const species = Dex.species.get(String(value ?? ""));
    if (species.exists && species.id.includes("mega")) return species.name;
  }

  const baseSpecies =
    normalizedSpeciesName(detail) || normalizedSpeciesName(identSpecies(actor));
  if (baseSpecies) {
    const guessed = normalizedSpeciesName(`${baseSpecies}-Mega`);
    if (guessed) return guessed;
  }
  return String(detail || identSpecies(actor) || "").trim();
}

function toStats(raw, fallback) {
  const stats = {
    hp: raw?.hp,
    atk: raw?.attack ?? raw?.atk,
    def: raw?.defence ?? raw?.defense ?? raw?.def,
    spa: raw?.specialAttack ?? raw?.special_attack ?? raw?.spa,
    spd: raw?.specialDefence ?? raw?.special_defence ?? raw?.special_defense ?? raw?.spd,
    spe: raw?.speed ?? raw?.spe,
  };
  return Object.fromEntries(
    Object.entries(stats).map(([key, value]) => [
      key,
      Number.isFinite(Number(value)) ? Number(value) : fallback,
    ]),
  );
}

function convertMember(member, path, warnings, teraContext = {}) {
  const resolvedSpecies = resolveShowdownMemberSpecies(member);
  const species = Dex.species.get(resolvedSpecies.showdownName ?? member.species);
  if (!species.exists) {
    throw new Error(`${path}: Pokémon Showdown에 없는 포켓몬입니다: ${member.species}`);
  }

  const moves = [];
  for (const [index, moveId] of member.moveset.entries()) {
    const move = Dex.moves.get(moveId);
    if (!move.exists) {
      warnings.push(
        warning(
          `${path}.moveset.${index}`,
          "unknown_move",
          `지원하지 않는 기술을 제외했습니다: ${moveId}`,
        ),
      );
      continue;
    }
    moves.push(move.name);
  }
  if (moves.length === 0) {
    throw new Error(`${path}: 실행 가능한 기술이 한 개 이상 필요합니다.`);
  }

  let ability = Dex.abilities.get(member.ability ?? "");
  if (!ability.exists) {
    if (member.ability) {
      warnings.push(
        warning(
          `${path}.ability`,
          "unknown_ability",
          `지원하지 않는 특성을 기본 특성으로 교체했습니다: ${member.ability}`,
        ),
      );
    }
    ability = Dex.abilities.get(species.abilities["0"]);
  }

  const cobblemonItemId = member.heldItem ?? "";
  const showdownItemId = cobblemonItemId.includes(":")
    ? cobblemonItemId.split(":", 2)[1]
    : cobblemonItemId;
  const item = Dex.items.get(showdownItemId);
  if (member.heldItem && !item.exists) {
    warnings.push(
      warning(
        `${path}.heldItem`,
        "showdown_item_unavailable",
        `Cobblemon 도구를 Showdown 호환 엔진에서 표현할 수 없어 제외했습니다: ${member.heldItem}`,
      ),
    );
  }

  const nature = Dex.natures.get(member.nature ?? "");
  if (member.nature && !nature.exists) {
    warnings.push(
      warning(
        `${path}.nature`,
        "unknown_nature",
        `지원하지 않는 성격을 Serious로 교체했습니다: ${member.nature}`,
      ),
    );
  }

  const gender =
    member.gender === "MALE" || member.gender === "M"
      ? "M"
      : member.gender === "FEMALE" || member.gender === "F"
        ? "F"
        : "";
  const requiredTeraType =
    species.baseSpecies === "Terapagos"
      ? "Stellar"
      : species.baseSpecies === "Ogerpon"
        ? species.requiredTeraType ?? species.types.at(-1)
        : "";
  const requestedTeraType = Dex.types.get(
    requiredTeraType ||
      explicitTeraType(member) ||
      seededNativeTeraType(
        species.types,
        teraContext.seed,
        teraContext.sideIndex,
        teraContext.memberIndex,
      ),
  );

  return {
    name: species.name,
    species: species.name,
    item: item.exists ? item.name : "",
    ability: ability.name,
    moves,
    nature: nature.exists ? nature.name : "Serious",
    gender,
    evs: toStats(member.evs, 0),
    ivs: toStats(member.ivs, 31),
    level: member.level,
    happiness: 255,
    teraType: requestedTeraType.exists
      ? requestedTeraType.name
      : species.types[0],
    gigantamax: member.gimmicks?.gmax === true,
  };
}

export function convertScenarioTeams(scenario) {
  const warnings = [];
  const teams = scenario.sides.map((side, sideIndex) =>
    side.team.map((member, memberIndex) =>
      convertMember(
        member,
        `sides.${sideIndex}.team.${memberIndex}`,
        warnings,
        {
          seed: scenario.seed,
          sideIndex,
          memberIndex,
        },
      ),
    ),
  );
  return {
    packedTeams: teams.map((team) => Teams.pack(team)),
    warnings,
  };
}

export function battleEvents(logLines) {
  const events = [];
  let currentTurn = 0;
  const effectMetadata = (parts) => {
    const from = parts.find((part) => part.startsWith("[from] "));
    const of = parts.find((part) => part.startsWith("[of] "));
    return {
      ...(from ? { source: from.slice("[from] ".length) } : {}),
      ...(of ? { sourceActor: of.slice("[of] ".length) } : {}),
    };
  };
  for (const line of logLines) {
    const parts = line.split("|");
    const type = parts[1];
    if (type === "turn") {
      currentTurn = Number(parts[2]);
      events.push({ turn: currentTurn, type: "turn", label: `Turn ${currentTurn}` });
    } else if (type === "switch" || type === "drag") {
      events.push({
        turn: currentTurn,
        type: "switch",
        actor: parts[2] ?? "",
        detail: parts[3]?.split(",")[0] ?? "",
        condition: parts[4] ?? "",
      });
    } else if (type === "move") {
      events.push({
        turn: currentTurn,
        type: "move",
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
        target: parts[4] ?? "",
      });
    } else if (type === "-damage" || type === "-heal") {
      events.push({
        turn: currentTurn,
        type: type === "-damage" ? "damage" : "heal",
        actor: parts[2] ?? "",
        condition: parts[3] ?? "",
        ...effectMetadata(parts.slice(4)),
      });
    } else if (type === "faint") {
      events.push({ turn: currentTurn, type: "faint", actor: parts[2] ?? "" });
    } else if (type === "-status" || type === "-curestatus") {
      events.push({
        turn: currentTurn,
        type: type === "-status" ? "status" : "status_cured",
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
      });
    } else if (
      type === "-supereffective" ||
      type === "-resisted" ||
      type === "-immune" ||
      type === "-crit" ||
      type === "-miss" ||
      type === "-fail"
    ) {
      const eventTypes = {
        "-supereffective": "super_effective",
        "-resisted": "resisted",
        "-immune": "immune",
        "-crit": "critical",
        "-miss": "miss",
        "-fail": "failed",
      };
      events.push({
        turn: currentTurn,
        type: eventTypes[type],
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
      });
    } else if (
      type === "-boost" ||
      type === "-unboost" ||
      type === "-setboost"
    ) {
      events.push({
        turn: currentTurn,
        type:
          type === "-boost"
            ? "stat_up"
            : type === "-unboost"
              ? "stat_down"
              : "stat_set",
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
        condition: parts[4] ?? "",
      });
    } else if (
      type === "-ability" ||
      type === "-item" ||
      type === "-enditem" ||
      type === "-activate"
    ) {
      const eventTypes = {
        "-ability": "ability",
        "-item": "item",
        "-enditem": "item_consumed",
        "-activate": "activated",
      };
      events.push({
        turn: currentTurn,
        type: eventTypes[type],
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
      });
    } else if (type === "cant") {
      events.push({
        turn: currentTurn,
        type: "cannot_move",
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
        target: parts[4] ?? "",
      });
    } else if (type === "-mega") {
      const megaSpecies = megaSpeciesFromProtocol(
        parts[2] ?? "",
        parts[3] ?? "",
        parts[4] ?? "",
      );
      events.push({
        turn: currentTurn,
        type: "mega_evolution",
        actor: parts[2] ?? "",
        detail: megaSpecies,
        condition: parts[4] ?? "",
      });
    } else if (type === "-zpower") {
      events.push({
        turn: currentTurn,
        type: "z_power",
        actor: parts[2] ?? "",
      });
    } else if (type === "-terastallize") {
      events.push({
        turn: currentTurn,
        type: "terastallized",
        actor: parts[2] ?? "",
        detail: parts[3] ?? "",
      });
    } else if (
      (type === "-start" || type === "-end") &&
      parts[3] === "Dynamax"
    ) {
      events.push({
        turn: currentTurn,
        type: type === "-start" ? "dynamax_started" : "dynamax_ended",
        actor: parts[2] ?? "",
        detail: parts[4] ?? "",
      });
    } else if (type === "-weather") {
      events.push({
        turn: currentTurn,
        type: "weather",
        detail: parts[2] ?? "",
      });
    } else if (
      type === "-fieldstart" ||
      type === "-fieldend" ||
      type === "-sidestart" ||
      type === "-sideend"
    ) {
      const isSideEffect = type.startsWith("-side");
      events.push({
        turn: currentTurn,
        type: type.endsWith("start") ? "field_started" : "field_ended",
        actor: isSideEffect ? (parts[2] ?? "") : "",
        detail: isSideEffect ? (parts[3] ?? "") : (parts[2] ?? ""),
      });
    } else if (type === "win") {
      events.push({ turn: currentTurn, type: "win", actor: parts[2] ?? "" });
    } else if (type === "tie") {
      events.push({ turn: currentTurn, type: "tie" });
    }
  }
  return events;
}

export async function runAutomaticBattle(scenario, options = {}) {
  const maxTurns = options.maxTurns ?? MAX_TURNS;
  const timeoutMs = options.timeoutMs ?? TIMEOUT_MS;
  const { packedTeams, warnings } = convertScenarioTeams(scenario);
  const streams = BattleStreams.getPlayerStreams(new BattleStreams.BattleStream());
  const difficulty = scenario.aiDifficulty ?? "standard";
  const aiProfiles = [0, 1].map((index) => ({
    difficulty: scenario.aiProfiles?.[index]?.difficulty ?? difficulty,
    strategy: scenario.aiProfiles?.[index]?.strategy ?? "balanced",
  }));
  const aiTrace = [];
  const format = requireBattleFormat(scenario.battleType ?? "single");
  const showdownFormat = showdownFormatId(format, scenario.gimmickRules);
  const p1 = createShowdownPlayerAI(streams.p1, {
    seed: scenario.seed ^ 0x5031,
    difficulty: aiProfiles[0].difficulty,
    battleType: format.id,
    team: scenario.sides[0].team,
    strategy: aiProfiles[0].strategy,
    trace: aiTrace,
    side: 0,
    sideName: scenario.sides[0].name,
  });
  const p2 = createShowdownPlayerAI(streams.p2, {
    seed: scenario.seed ^ 0x5032,
    difficulty: aiProfiles[1].difficulty,
    battleType: format.id,
    team: scenario.sides[1].team,
    strategy: aiProfiles[1].strategy,
    trace: aiTrace,
    side: 1,
    sideName: scenario.sides[1].name,
  });
  const aiErrors = [];
  const startTime = performance.now();
  let turnLimitReached = false;
  let timedOut = false;
  let highestTurn = 0;
  const chunks = [];

  const stopOnError = (error) => {
    aiErrors.push(error);
    void streams.omniscient.write(">forcetie");
  };
  void p1.start().catch(stopOnError);
  void p2.start().catch(stopOnError);

  const timeout = setTimeout(() => {
    timedOut = true;
    void streams.omniscient.write(">forcetie");
  }, timeoutMs);

  const readLog = (async () => {
    for await (const chunk of streams.omniscient) {
      chunks.push(chunk);
      for (const match of chunk.matchAll(/\|turn\|(\d+)/g)) {
        highestTurn = Math.max(highestTurn, Number(match[1]));
      }
      if (!turnLimitReached && highestTurn >= maxTurns) {
        turnLimitReached = true;
        void streams.omniscient.write(">forcetie");
      }
    }
  })();

  const battleSeed = toGen5Seed(scenario.seed, 0x42415454);
  void streams.omniscient.write(
    `>start ${JSON.stringify({ formatid: showdownFormat, seed: battleSeed })}\n` +
      `>player p1 ${JSON.stringify({ name: scenario.sides[0].name, team: packedTeams[0] })}\n` +
      `>player p2 ${JSON.stringify({ name: scenario.sides[1].name, team: packedTeams[1] })}`,
  );

  await readLog;
  clearTimeout(timeout);
  if (aiErrors.length > 0) {
    throw aiErrors[0];
  }

  const log = chunks.join("\n").split(/\r?\n/).filter(Boolean);
  const winLine = log.findLast((line) => line.startsWith("|win|"));
  const tied = log.some((line) => line === "|tie|") || turnLimitReached || timedOut;
  const winner = winLine ? winLine.slice("|win|".length) : null;

  return {
    battleId: `${scenario.scenarioId}-battle`,
    scenarioId: scenario.scenarioId,
    engine: {
      id: ENGINE_ID,
      version: ENGINE_VERSION,
      format: showdownFormat,
      controller:
        aiProfiles[0].difficulty === aiProfiles[1].difficulty &&
        aiProfiles[0].strategy === aiProfiles[1].strategy
          ? showdownControllerId(format.id, aiProfiles[0].difficulty)
          : `${aiProfiles[0].difficulty}-${aiProfiles[0].strategy}-vs-${aiProfiles[1].difficulty}-${aiProfiles[1].strategy}`,
    },
    settings: {
      battleEngine: "showdown",
      aiDifficulty: scenario.aiDifficulty ?? "standard",
      aiProfiles,
      battleType: format.id,
      gimmickRules: scenario.gimmickRules ?? "gen9",
    },
    seed: scenario.seed,
    status: timedOut
      ? "timeout"
      : turnLimitReached
        ? "turn_limit"
        : tied
          ? "tie"
          : "completed",
    winner,
    turns: highestTurn,
    durationMs: Math.round((performance.now() - startTime) * 100) / 100,
    warnings,
    aiTrace,
    events: battleEvents(log),
    log,
  };
}
