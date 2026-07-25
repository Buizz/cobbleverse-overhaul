import { Dex } from "@pkmn/sim";
import { runSimpleBattle } from "./cobbleverse-battle-engine.mjs";
import { resolveShowdownMemberSpecies } from "./showdown-species.mjs";

function statValue(base, level, iv, ev, isHp = false) {
  const core = Math.floor(
    ((2 * base + iv + Math.floor(ev / 4)) * level) / 100,
  );
  return isHp ? core + level + 10 : core + 5;
}

function memberStat(member, key, fallback) {
  const aliases = {
    hp: ["hp"],
    atk: ["attack", "atk"],
    def: ["defence", "defense", "def"],
    spa: ["specialAttack", "special_attack", "spa"],
    spd: [
      "specialDefence",
      "specialDefense",
      "special_defence",
      "special_defense",
      "spd",
    ],
    spe: ["speed", "spe"],
  };
  for (const alias of aliases[key]) {
    const value = Number(member?.[alias]);
    if (Number.isFinite(value)) return value;
  }
  return fallback;
}

function normalizeShowdownSpecies(value) {
  if (!value) return null;
  const raw =
    typeof value === "object"
      ? value.name ??
        value.id ??
        value.fullname ??
        value.species ??
        Object.values(value)[0] ??
        ""
      : value;
  const species = Dex.species.get(String(raw));
  return species.exists
    ? species
    : {
        id: String(raw ?? "").toLowerCase().replace(/[^a-z0-9]+/g, ""),
        name: String(raw ?? "").trim(),
        types: [],
        abilities: {},
        baseStats: {},
        exists: false,
  };
}

function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function displayValue(value) {
  if (!value) return "";
  if (typeof value === "object") {
    return String(
      value.name ?? value.id ?? value.fullname ?? value.species ?? "",
    ).trim();
  }
  return String(value).trim();
}

function nativeEffectSource(event) {
  const source = displayValue(event.source);
  if (!source) return "";
  if (event.cause === "item") return `item: ${source}`;
  if (event.cause === "ability") return `ability: ${source}`;
  if (event.cause === "recoil") return "Recoil";
  if (event.cause === "self_destruct") return `move: ${source}`;
  if (event.cause) return source;
  return "";
}

function hydrateMember(member, path) {
  const resolvedSpecies = resolveShowdownMemberSpecies(member);
  const species = Dex.species.get(resolvedSpecies.showdownName ?? member.species);
  if (!species.exists) {
    throw new Error(`${path}: 자체 엔진에서 알 수 없는 포켓몬입니다: ${member.species}`);
  }
  const level = Number(member.level);
  const ivs = member.ivs ?? {};
  const evs = member.evs ?? {};
  const calculated = (targetSpecies, key, isHp = false) =>
    statValue(
      targetSpecies.baseStats[key],
      level,
      memberStat(ivs, key, 31),
      memberStat(evs, key, 0),
      isHp,
    );
  const moves = member.moveset.map((moveId, moveIndex) => {
    const move = Dex.moves.get(moveId);
    if (!move.exists) {
      throw new Error(
        `${path}.moveset.${moveIndex}: 자체 엔진에서 알 수 없는 기술입니다: ${moveId}`,
      );
    }
    return {
      id: move.id,
      name: move.name,
      type: move.type,
      category: move.category,
      power: move.basePower,
      accuracy: move.accuracy,
      priority: move.priority,
      pp: move.pp,
      target: move.target,
      critRatio: move.critRatio ?? 1,
      status: move.status ?? "",
      volatileStatus: move.volatileStatus ?? "",
      boosts: move.boosts ?? null,
      self: move.self ?? null,
      heal: move.heal ?? null,
      drain: move.drain ?? null,
      recoil: move.recoil ?? null,
      weather: move.weather ?? "",
      terrain: move.terrain ?? "",
      pseudoWeather: move.pseudoWeather ?? "",
      sideCondition: move.sideCondition ?? "",
      slotCondition: move.slotCondition ?? "",
      multihit: move.multihit ?? null,
      multiaccuracy: move.multiaccuracy ?? false,
      willCrit: move.willCrit ?? false,
      selfSwitch: move.selfSwitch ?? false,
      forceSwitch: move.forceSwitch ?? false,
      fixedDamage: move.damage ?? null,
      dynamicDamage: Boolean(move.damageCallback),
      dynamicPower: Boolean(move.basePowerCallback),
      secondary: move.secondary ?? null,
      secondaries: move.secondaries ?? null,
    };
  });
  const heldItem = member.heldItemPath ?? member.heldItem ?? "";
  const itemPath = cleanId(heldItem);
  const showdownItem = Dex.items.get(itemPath);
  const megaSpecies = normalizeShowdownSpecies(showdownItem.megaStone);
  const megaEvolves =
    showdownItem.megaEvolves ??
    (showdownItem.megaStone && typeof showdownItem.megaStone === "object"
      ? Object.keys(showdownItem.megaStone)[0]
      : "");
  const megaStone =
    showdownItem.exists && megaSpecies?.name
      ? {
          item: heldItem,
          evolves: megaEvolves,
          form: megaSpecies.name,
          types: megaSpecies.types ?? [],
          ability:
            megaSpecies.abilities?.["0"] ??
            megaSpecies.abilities?.S ??
            megaSpecies.abilities?.H ??
            "",
          stats: megaSpecies.exists
            ? {
                attack: calculated(megaSpecies, "atk"),
                defence: calculated(megaSpecies, "def"),
                specialAttack: calculated(megaSpecies, "spa"),
                specialDefence: calculated(megaSpecies, "spd"),
                speed: calculated(megaSpecies, "spe"),
              }
            : null,
        }
      : null;
  const zCrystal =
    showdownItem.exists &&
    (showdownItem.zMove || showdownItem.zMoveType || showdownItem.zMoveFrom)
      ? {
          item: heldItem,
          move: showdownItem.zMove ?? "",
          moveType: showdownItem.zMoveType ?? "",
          moveFrom: showdownItem.zMoveFrom ?? "",
        }
      : null;
  return {
    id: species.id,
    name: species.name,
    level,
    types: species.types,
    ability: member.ability ?? "",
    item: heldItem,
    gimmicks: {
      megaStone,
      zCrystal,
      canDynamax: true,
      forceDynamax:
        member.gimmicks?.dynamax === true || member.gimmicks?.gmax === true,
      gigantamax: member.gimmicks?.gmax === true,
      teraType: member.gimmicks?.tera ?? species.types[0] ?? "Normal",
    },
    weightKg: species.weightkg,
    friendship: member.friendship ?? member.happiness ?? 255,
    stats: {
      hp: calculated(species, "hp", true),
      attack: calculated(species, "atk"),
      defence: calculated(species, "def"),
      specialAttack: calculated(species, "spa"),
      specialDefence: calculated(species, "spd"),
      speed: calculated(species, "spe"),
    },
    moves,
  };
}

export function createNativeBattleSetup(scenario) {
  return {
    seed: scenario.seed,
    gimmickProfile:
      scenario.gimmickRules === "all"
        ? "cobbleverse_all"
        : `official_${scenario.gimmickRules ?? "gen9"}`,
    sides: scenario.sides.map((side, sideIndex) => ({
      name: side.name,
      team: side.team.map((member, memberIndex) =>
        hydrateMember(
          member,
          `sides.${sideIndex}.team.${memberIndex}`,
        ),
      ),
    })),
  };
}

function actor(event) {
  if (event.side !== 0 && event.side !== 1) return "";
  return `p${event.side + 1}a: ${event.pokemon ?? ""}`;
}

export function mapNativeEvent(event) {
  const base = { turn: event.turn, type: event.type, actor: actor(event) };
  if (event.type === "turn") {
    return [{ ...base, label: `Turn ${event.turn}` }];
  }
  if (event.type === "switch") {
    const condition =
      Number.isFinite(event.remainingHp) && Number.isFinite(event.maximumHp)
        ? `${event.remainingHp}/${event.maximumHp}${event.status ? ` ${event.status}` : ""}`
        : "";
    return [{ ...base, detail: event.pokemon ?? "", condition }];
  }
  if (event.type === "move") {
    return [{ ...base, detail: event.move ?? "" }];
  }
  if (event.type === "gimmick") {
    const eventType = {
      mega: "mega_evolution",
      zmove: "z_power",
      dynamax: "dynamax_started",
      terastallize: "terastallized",
    }[event.gimmick];
    return [
      {
        ...base,
        type: eventType ?? "activated",
        detail:
          event.gimmick === "terastallize"
            ? event.teraType ?? ""
            : event.gimmick === "dynamax"
              ? event.dynamaxMode ?? ""
              : event.gimmick === "mega"
                ? displayValue(event.megaForm)
                : "",
      },
    ];
  }
  if (event.type === "dynamax_end") {
    return [{ ...base, type: "dynamax_ended" }];
  }
  if (event.type === "damage") {
    const effectiveness =
      event.effectiveness > 1
        ? "super_effective"
        : event.effectiveness === 0
          ? "immune"
          : event.effectiveness < 1
            ? "resisted"
            : null;
    if (event.effectiveness === 0) {
      return effectiveness ? [{ ...base, type: effectiveness }] : [];
    }
    return [
      ...(effectiveness ? [{ ...base, type: effectiveness }] : []),
      {
        ...base,
        type: "damage",
        condition: `${event.remainingHp}/${event.maximumHp}`,
        source: nativeEffectSource(event),
      },
    ];
  }
  if (event.type === "heal") {
    const source = displayValue(event.source);
    return [
      {
        ...base,
        type: "heal",
        condition: `${event.remainingHp}/${event.maximumHp}`,
        detail: source,
        source: event.cause === "item" ? `item: ${source}` : source,
      },
    ];
  }
  if (event.type === "critical") {
    return [{ ...base, type: "critical" }];
  }
  if (event.type === "status" || event.type === "status_cured") {
    return [{ ...base, detail: event.status ?? "" }];
  }
  if (event.type === "cant_move") {
    const statusMessages = {
      flinch: "풀죽어서 행동할 수 없다.",
    };
    return [
      {
        ...base,
        detail: statusMessages[event.status] ?? event.status ?? "",
        condition: event.status ?? "",
      },
    ];
  }
  if (event.type === "stat_change") {
    return [
      {
        ...base,
        type: event.amount > 0 ? "stat_up" : "stat_down",
        detail: event.stat ?? "",
        condition: String(Math.abs(event.amount ?? 0)),
      },
    ];
  }
  if (event.type === "field_start" || event.type === "field_end") {
    if (event.fieldKind === "weather") {
      return [
        {
          ...base,
          type: "weather",
          detail: event.type === "field_start" ? event.effect ?? "" : "",
        },
      ];
    }
    return [
      {
        ...base,
        type: event.type === "field_start" ? "field_started" : "field_ended",
        detail: event.effect ?? "",
        condition: event.fieldKind ?? "",
      },
    ];
  }
  if (
    event.type === "side_condition_start" ||
    event.type === "side_condition_end"
  ) {
    return [
      {
        ...base,
        type:
          event.type === "side_condition_start"
            ? "field_started"
            : "field_ended",
        detail: event.effect ?? "",
        layers: event.layers,
        duration: event.duration,
      },
    ];
  }
  if (event.type === "multi_hit") {
    return [{ ...base, detail: String(event.hits ?? 1) }];
  }
  if (event.type === "dynamic_power") {
    return [
      {
        ...base,
        detail: String(event.power ?? ""),
        condition: event.reason ?? "",
      },
    ];
  }
  if (event.type === "move_blocked") {
    return [{ ...base, type: "failed", detail: event.source ?? "" }];
  }
  if (
    event.type === "unsupported_effect" ||
    event.type === "move_failed"
  ) {
    return [{ ...base, type: "failed", detail: event.move ?? "" }];
  }
  if (event.type === "no_pp") {
    return [{ ...base, type: "failed", detail: "PP 없음" }];
  }
  if (event.type === "win") {
    return [{ ...base, actor: event.winner ?? "" }];
  }
  return [base];
}

export function runNativeScenarioBattle(scenario, options = {}) {
  if ((scenario.battleType ?? "single") !== "single") {
    throw new Error("Cobbleverse 자체 엔진은 현재 싱글 배틀만 지원합니다.");
  }
  const startTime = performance.now();
  const state = runSimpleBattle(createNativeBattleSetup(scenario), {
    maxTurns: options.maxTurns,
    difficulty: scenario.aiDifficulty,
    aiProfiles: scenario.aiProfiles,
  });
  const events = state.events.flatMap(mapNativeEvent);
  const finalState = {
    sides: state.sides.map((side) => ({
      name: side.name,
      active: side.active,
      team: side.team.map((pokemon) => ({
        name: pokemon.name,
        species: pokemon.id || pokemon.name,
        hp: pokemon.hp,
        maxHp: pokemon.stats.hp,
        fainted: pokemon.fainted,
        status: pokemon.status,
      })),
    })),
  };
  return {
    battleId: `${scenario.scenarioId}-native-battle`,
    scenarioId: scenario.scenarioId,
    engine: {
      id: state.engine.id,
      version: state.engine.version,
      format: "single",
      controller: `${scenario.aiDifficulty}-baseline`,
    },
    settings: {
      battleEngine: "cobbleverse",
      aiDifficulty: scenario.aiDifficulty,
      aiProfiles:
        scenario.aiProfiles ??
        [0, 1].map(() => ({
          difficulty: scenario.aiDifficulty,
          strategy: "balanced",
        })),
      battleType: "single",
      gimmickRules: "all",
    },
    seed: scenario.seed,
    status: state.status,
    winner: state.winner,
    turns: state.turn,
    durationMs: Math.round((performance.now() - startTime) * 100) / 100,
    warnings: [
      {
        path: "battleEngine",
        code: "experimental_native_catalog_bridge",
        message:
          "자체 엔진은 현재 포켓몬·기술 원본 데이터만 Showdown 카탈로그에서 읽으며, 전투 판정은 Cobbleverse 엔진이 수행합니다.",
      },
    ],
    finalState,
    aiTrace: state.aiTrace,
    events,
    log: state.events.map((event) => JSON.stringify(event)),
  };
}
