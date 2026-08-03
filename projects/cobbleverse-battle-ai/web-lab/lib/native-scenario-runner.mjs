import { Dex } from "@pkmn/sim";
import { runSimpleBattle } from "./cobbleverse-battle-engine.mjs";
import { isNativeGigantamaxSpecies } from "./native-max-moves.mjs";
import { resolveShowdownMemberSpecies } from "./showdown-species.mjs";
import {
  explicitTeraType,
  seededNativeTeraType,
} from "./virtual-tera-policy.mjs";

const GENERIC_Z_MOVE_NAMES = {
  normal: "Breakneck Blitz",
  fighting: "All-Out Pummeling",
  flying: "Supersonic Skystrike",
  poison: "Acid Downpour",
  ground: "Tectonic Rage",
  rock: "Continental Crush",
  bug: "Savage Spin-Out",
  ghost: "Never-Ending Nightmare",
  steel: "Corkscrew Crash",
  fire: "Inferno Overdrive",
  water: "Hydro Vortex",
  grass: "Bloom Doom",
  electric: "Gigavolt Havoc",
  psychic: "Shattered Psyche",
  ice: "Subzero Slammer",
  dragon: "Devastating Drake",
  dark: "Black Hole Eclipse",
  fairy: "Twinkle Tackle",
};

const CUSTOM_MEGA_STONES = {
  dragalgite: {
    evolves: "Dragalge",
    form: "Dragalge-Mega",
    types: ["Poison", "Dragon"],
    ability: "regenerator",
    baseStats: {
      hp: 65,
      atk: 85,
      def: 105,
      spa: 132,
      spd: 163,
      spe: 44,
    },
  },
};

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

function hydrateMember(member, path, teraContext = {}) {
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
  const hydratedForm = (formName) => {
    const form = Dex.species.get(formName);
    if (!form.exists) return null;
    return {
      id: form.id,
      name: form.name,
      types: form.types,
      ability:
        form.abilities?.["0"] ??
        form.abilities?.S ??
        form.abilities?.H ??
        "",
      weightKg: form.weightkg,
      stats: {
        hp: calculated(form, "hp", true),
        attack: calculated(form, "atk"),
        defence: calculated(form, "def"),
        specialAttack: calculated(form, "spa"),
        specialDefence: calculated(form, "spd"),
        speed: calculated(form, "spe"),
      },
    };
  };
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
  const customMegaStone = CUSTOM_MEGA_STONES[itemPath] ?? null;
  const megaSpecies = normalizeShowdownSpecies(showdownItem.megaStone);
  const megaEvolves =
    showdownItem.megaEvolves ??
    (showdownItem.megaStone && typeof showdownItem.megaStone === "object"
      ? Object.keys(showdownItem.megaStone)[0]
      : "");
  const showdownMegaStone =
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
  const megaStone =
    showdownMegaStone ??
    (customMegaStone
      ? {
          item: heldItem,
          evolves: customMegaStone.evolves,
          form: customMegaStone.form,
          types: customMegaStone.types,
          ability: customMegaStone.ability,
          stats: {
            attack: calculated(customMegaStone, "atk"),
            defence: calculated(customMegaStone, "def"),
            specialAttack: calculated(customMegaStone, "spa"),
            specialDefence: calculated(customMegaStone, "spd"),
            speed: calculated(customMegaStone, "spe"),
          },
        }
      : null);
  const zCrystal =
    showdownItem.exists &&
    (showdownItem.zMove || showdownItem.zMoveType || showdownItem.zMoveFrom)
      ? {
          item: heldItem,
          itemName: showdownItem.name ?? heldItem,
          move:
            typeof showdownItem.zMove === "string"
              ? showdownItem.zMove
              : GENERIC_Z_MOVE_NAMES[cleanId(showdownItem.zMoveType)] ?? "",
          moveType: showdownItem.zMoveType ?? "",
          moveFrom: showdownItem.zMoveFrom ?? "",
          users: Array.isArray(showdownItem.itemUser)
            ? showdownItem.itemUser.map(cleanId)
            : [],
        }
      : null;
  const baseSpeciesName = species.baseSpecies || species.name;
  const isOgerpon = baseSpeciesName === "Ogerpon";
  const isTerapagos = baseSpeciesName === "Terapagos";
  const ogerponTeraFormName =
    species.name === "Ogerpon"
      ? "Ogerpon-Teal-Tera"
      : isOgerpon
        ? `${species.name.replace(/-Tera$/, "")}-Tera`
        : "";
  const speciesForms = {
    ...(isOgerpon
      ? { tera: hydratedForm(ogerponTeraFormName) }
      : {}),
    ...(isTerapagos
      ? {
          terastal: hydratedForm("Terapagos-Terastal"),
          stellar: hydratedForm("Terapagos-Stellar"),
        }
      : {}),
  };
  const requiredTeraType = isTerapagos
    ? "Stellar"
    : isOgerpon
      ? species.requiredTeraType ?? species.types.at(-1) ?? "Grass"
      : explicitTeraType(member) ||
        seededNativeTeraType(
          species.types,
          teraContext.seed,
          teraContext.sideIndex,
          teraContext.memberIndex,
        );
  const configuredAbility =
    displayValue(member.ability) ||
    ((isOgerpon || isTerapagos)
      ? species.abilities?.["0"] ??
        species.abilities?.S ??
        species.abilities?.H ??
        ""
      : "");
  const canGigantamax = isNativeGigantamaxSpecies(species.id);
  const configuredGigantamax =
    member.gimmicks?.gmax === true && canGigantamax;
  return {
    id: species.id,
    name: species.name,
    baseSpecies: baseSpeciesName,
    level,
    types: species.types,
    gender: member.gender ?? null,
    ability: configuredAbility,
    item: heldItem,
    speciesForms,
    gimmicks: {
      megaStone,
      zCrystal,
      canDynamax: true,
      forceDynamax:
        member.gimmicks?.dynamax === true || configuredGigantamax,
      canGigantamax,
      gigantamax: configuredGigantamax,
      teraConfigured:
        teraContext.teraConfigured ??
        member.gimmicks?.teraEligible ??
        (explicitTeraType(member) !== ""),
      teraType: requiredTeraType,
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
  const supportedItems = new Set([
    "cobblemon:full_restore",
    "cobblemon:potion",
    "cobblemon:full_heal",
  ]);
  const globalItemRules = scenario.itemRules?.source === "global";
  return {
    seed: scenario.seed,
    strictAbilityValidation: true,
    gimmickProfile:
      scenario.gimmickRules === "all"
        ? "cobbleverse_all"
        : `official_${scenario.gimmickRules ?? "gen9"}`,
    sides: scenario.sides.map((side, sideIndex) => {
      const hasExplicitTeraEligibility = side.team.some(
        (member) => member.gimmicks?.teraEligible != null,
      );
      return {
        name: side.name,
        bag: globalItemRules
          ? (scenario.itemRules.items ?? []).map((item) => ({
              item,
              quantity: Number(scenario.itemRules.maxUses ?? 0),
            }))
          : (side.bag ?? []).filter((entry) => supportedItems.has(entry.item)),
        maxItemUses: globalItemRules
          ? Number(scenario.itemRules.maxUses ?? 0)
          : Math.max(0, Number(side.battleRules?.maxItemUses ?? 0)),
        team: side.team.map((member, memberIndex) =>
          hydrateMember(
            member,
            `sides.${sideIndex}.team.${memberIndex}`,
            {
              seed: scenario.seed,
              sideIndex,
              memberIndex,
              teraConfigured: hasExplicitTeraEligibility
                ? member.gimmicks?.teraEligible === true
                : true,
            },
          ),
        ),
      };
    }),
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
  if (event.type === "trainer_item") {
    return [{
      ...base,
      type: "trainer_item",
      detail: displayValue(event.itemName || event.item),
      condition: String(event.usesRemaining ?? ""),
    }];
  }
  if (event.type === "item_failed") {
    return [{
      ...base,
      type: "failed",
      detail: displayValue(event.item),
    }];
  }
  if (event.type === "switch") {
    const condition =
      Number.isFinite(event.remainingHp) && Number.isFinite(event.maximumHp)
        ? `${event.remainingHp}/${event.maximumHp}${event.status ? ` ${event.status}` : ""}`
        : "";
    return [{
      ...base,
      detail: event.pokemon ?? "",
      condition,
      fromActor:
        event.side === 0 || event.side === 1
          ? `p${event.side + 1}a: ${event.fromPokemon ?? ""}`
          : "",
      automatic: event.automatic === true,
      forced: event.forced === true,
      selection: event.selection ?? "",
      source: displayValue(event.source),
    }];
  }
  if (event.type === "move") {
    return [{
      ...base,
      detail: event.move ?? "",
      ...(event.moveType ? { moveType: event.moveType } : {}),
      ...(event.moveCategory ? { moveCategory: event.moveCategory } : {}),
    }];
  }
  if (event.type === "gimmick") {
    const eventType = {
      mega: "mega_evolution",
      zmove: "z_power",
      dynamax: "dynamax_started",
      gigantamax: "dynamax_started",
      terastallize: "terastallized",
    }[event.gimmick];
    return [
      {
        ...base,
        type: eventType ?? "activated",
        detail:
          event.gimmick === "terastallize"
            ? event.teraType ?? ""
            : event.gimmick === "dynamax" || event.gimmick === "gigantamax"
              ? event.dynamaxMode ?? ""
              : event.gimmick === "mega"
                ? displayValue(event.megaForm)
                : "",
      },
    ];
  }
  if (event.type === "form_change") {
    return [{
      ...base,
      type: "formechange",
      detail: event.pokemon ?? "",
      source: displayValue(event.source),
      condition:
        Number.isFinite(event.remainingHp) &&
        Number.isFinite(event.maximumHp)
          ? `${event.remainingHp}/${event.maximumHp}`
          : "",
    }];
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
        ...(event.cause ? { cause: event.cause } : {}),
        ...(event.moveType ? { moveType: event.moveType } : {}),
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
  if (event.type === "damage_prevented") {
    return [
      {
        ...base,
        detail: displayValue(event.source),
        condition: Number.isFinite(event.remainingHp)
          ? String(event.remainingHp)
          : "",
      },
    ];
  }
  if (event.type === "item_removed") {
    return [
      {
        ...base,
        detail: displayValue(event.item),
        source: displayValue(event.source),
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
  if (event.type === "stat_reset") {
    return [
      {
        ...base,
        type: "stat_reset",
        detail: displayValue(event.source),
      },
    ];
  }
  if (event.type === "boosts_passed") {
    return [
      {
        ...base,
        type: "boosts_passed",
        detail: displayValue(event.source),
        boosts: { ...(event.boosts ?? {}) },
      },
    ];
  }
  if (event.type === "field_start" || event.type === "field_end") {
    if (event.fieldKind === "weather") {
      return [
        {
          ...base,
          type: "weather",
          detail: event.type === "field_start" ? event.effect ?? "" : "none",
          condition:
            event.type === "field_start"
              ? String(event.duration ?? "")
              : event.effect ?? "",
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
  if (event.type === "field_tick") {
    return [
      {
        ...base,
        type: "field_active",
        detail: event.effect ?? "",
        condition: String(event.remainingTurns ?? ""),
        source: event.fieldKind ?? "",
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
    return [
      {
        ...base,
        type: "failed",
        detail: event.reason ?? event.move ?? "",
      },
    ];
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
  const includeDetails = options.includeDetails !== false;
  const state = runSimpleBattle(createNativeBattleSetup(scenario), {
    maxTurns: options.maxTurns,
    difficulty: scenario.aiDifficulty,
    aiProfiles: scenario.aiProfiles,
    captureAiTrace: includeDetails,
    captureTurnSnapshots: includeDetails,
  });
  const finalState = includeDetails ? {
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
  } : undefined;
  const result = {
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
      itemRules: scenario.itemRules ?? {
        source: "trainer",
        items: [],
        maxUses: null,
      },
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
  };
  if (!includeDetails) return result;
  return {
    ...result,
    finalState,
    turnSnapshots: state.turnSnapshots,
    aiTrace: state.aiTrace,
    events: state.events.flatMap(mapNativeEvent),
    log: state.events.map((event) => JSON.stringify(event)),
  };
}
