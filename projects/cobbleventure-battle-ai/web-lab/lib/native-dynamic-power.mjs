function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function boundedPower(value, maximum = Number.POSITIVE_INFINITY) {
  return Math.max(1, Math.min(maximum, Math.floor(value)));
}

function weightPower(weight) {
  if (weight >= 200) return 120;
  if (weight >= 100) return 100;
  if (weight >= 50) return 80;
  if (weight >= 25) return 60;
  if (weight >= 10) return 40;
  return 20;
}

function weightRatioPower(attackerWeight, defenderWeight) {
  const ratio = attackerWeight / Math.max(0.1, defenderWeight);
  if (ratio >= 5) return 120;
  if (ratio >= 4) return 100;
  if (ratio >= 3) return 80;
  if (ratio >= 2) return 60;
  return 40;
}

function flailPower(pokemon) {
  const scale = Math.floor((48 * pokemon.hp) / pokemon.stats.hp);
  if (scale <= 1) return 200;
  if (scale <= 4) return 150;
  if (scale <= 9) return 100;
  if (scale <= 16) return 80;
  if (scale <= 32) return 40;
  return 20;
}

function positiveBoostTotal(pokemon) {
  return Object.values(pokemon.boosts ?? {}).reduce(
    (total, stage) => total + Math.max(0, Number(stage) || 0),
    0,
  );
}

function speedRatioPower(attackerSpeed, defenderSpeed) {
  const ratio = attackerSpeed / Math.max(1, defenderSpeed);
  if (ratio >= 4) return 150;
  if (ratio >= 3) return 120;
  if (ratio >= 2) return 80;
  if (ratio >= 1) return 60;
  return 40;
}

function consecutivePower(attacker, move, maximum) {
  const moveId = cleanId(move.id);
  const previousUses =
    attacker.consecutiveMove?.id === moveId
      ? Math.max(0, Number(attacker.consecutiveMove.count) || 0)
      : 0;
  return Math.min(maximum, move.power * 2 ** previousUses);
}

function rollingPower(attacker, move) {
  const consecutive = consecutivePower(attacker, move, move.power * 16);
  return attacker.volatiles?.defensecurl ? consecutive * 2 : consecutive;
}

function naturalGiftData(item) {
  const id = cleanId(item);
  const table = {
    occaberry: ["Fire", 80],
    passhoberry: ["Water", 80],
    wacanberry: ["Electric", 80],
    rindoberry: ["Grass", 80],
    yacheberry: ["Ice", 80],
    chopleberry: ["Fighting", 80],
    kebiaberry: ["Poison", 80],
    shucaberry: ["Ground", 80],
    cobaberry: ["Flying", 80],
    payapaberry: ["Psychic", 80],
    tangaberry: ["Bug", 80],
    chartiberry: ["Rock", 80],
    kasibberry: ["Ghost", 80],
    habanberry: ["Dragon", 80],
    colburberry: ["Dark", 80],
    babiriberry: ["Steel", 80],
    chilanberry: ["Normal", 80],
    roselliberry: ["Fairy", 80],
  };
  const [type = "Normal", power = 80] = table[id] ?? [];
  return { type, power };
}

const HANDLERS = {
  acrobatics: ({ attacker, move }) => ({
    power: attacker.item ? move.power : move.power * 2,
    reason: attacker.item ? "held_item" : "no_held_item",
  }),
  assurance: ({ defender, move }) => ({
    power: defender.turnState?.damageTaken > 0 ? move.power * 2 : move.power,
    reason:
      defender.turnState?.damageTaken > 0
        ? "target_damaged_this_turn"
        : "base_power",
  }),
  avalanche: ({ attacker, move }) => ({
    power: attacker.turnState?.damageTaken > 0 ? move.power * 2 : move.power,
    reason:
      attacker.turnState?.damageTaken > 0
        ? "user_damaged_this_turn"
        : "base_power",
  }),
  beatup: ({ state, attackerSide }) => ({
    power: Math.max(
      5,
      state.sides[attackerSide].team.filter(
        (pokemon) => !pokemon.fainted && !pokemon.status,
      ).length * 10,
    ),
    reason: "healthy_party_members",
  }),
  barbbarrage: ({ defender, move }) => ({
    power: ["psn", "tox"].includes(defender.status)
      ? move.power * 2
      : move.power,
    reason: ["psn", "tox"].includes(defender.status)
      ? "poisoned_target"
      : "base_power",
  }),
  boltbeak: ({ defender, move }) => ({
    power: defender.turnState?.acted ? move.power : move.power * 2,
    reason: defender.turnState?.acted ? "target_acted" : "target_not_acted",
  }),
  brine: ({ defender, move }) => ({
    power: defender.hp <= Math.floor(defender.stats.hp / 2) ? move.power * 2 : move.power,
    reason:
      defender.hp <= Math.floor(defender.stats.hp / 2)
        ? "target_half_hp_or_less"
        : "base_power",
  }),
  collisioncourse: ({ effectiveness, move }) => ({
    power: effectiveness > 1 ? Math.floor(move.power * 5461 / 4096) : move.power,
    reason: effectiveness > 1 ? "super_effective" : "base_power",
  }),
  crushgrip: ({ defender }) => ({
    power: boundedPower((120 * defender.hp) / defender.stats.hp, 120),
    reason: "target_hp_ratio",
  }),
  dragonenergy: ({ attacker }) => ({
    power: boundedPower((150 * attacker.hp) / attacker.stats.hp, 150),
    reason: "user_hp_ratio",
  }),
  echoedvoice: ({ attacker, move }) => ({
    power: consecutivePower(attacker, move, 200),
    reason: "consecutive_successful_uses",
  }),
  electrodrift: ({ effectiveness, move }) => ({
    power: effectiveness > 1 ? Math.floor(move.power * 5461 / 4096) : move.power,
    reason: effectiveness > 1 ? "super_effective" : "base_power",
  }),
  electroball: ({ attackerSpeed, defenderSpeed }) => ({
    power: speedRatioPower(attackerSpeed, defenderSpeed),
    reason: "speed_ratio",
  }),
  eruption: ({ attacker }) => ({
    power: boundedPower((150 * attacker.hp) / attacker.stats.hp, 150),
    reason: "user_hp_ratio",
  }),
  fishiousrend: ({ defender, move }) => ({
    power: defender.turnState?.acted ? move.power : move.power * 2,
    reason: defender.turnState?.acted ? "target_acted" : "target_not_acted",
  }),
  ficklebeam: ({ move, rng }) => {
    const doubled = (rng?.next ? rng.next() : 0.5) < 0.3;
    return {
      power: doubled ? move.power * 2 : move.power,
      reason: doubled ? "fickle_double_power" : "base_power",
    };
  },
  firepledge: ({ state, move }) => {
    const boosted = state?.turnMoves?.some((entry) =>
      ["grasspledge", "waterpledge"].includes(cleanId(entry.id)),
    );
    return {
      power: boosted ? 150 : move.power,
      reason: boosted ? "pledge_combo" : "base_power",
    };
  },
  flail: ({ attacker }) => ({
    power: flailPower(attacker),
    reason: "user_hp_threshold",
  }),
  frustration: ({ attacker }) => ({
    power: boundedPower((255 - attacker.friendship) / 2.5, 102),
    reason: "friendship_inverse",
  }),
  furycutter: ({ attacker, move }) => ({
    power: consecutivePower(attacker, move, 160),
    reason: "consecutive_successful_uses",
  }),
  fusionbolt: ({ state, move }) => {
    const boosted = state?.turnMoves?.some(
      (entry) => cleanId(entry.id) === "fusionflare",
    );
    return {
      power: boosted ? move.power * 2 : move.power,
      reason: boosted ? "fusion_flare_used_this_turn" : "base_power",
    };
  },
  fusionflare: ({ state, move }) => {
    const boosted = state?.turnMoves?.some(
      (entry) => cleanId(entry.id) === "fusionbolt",
    );
    return {
      power: boosted ? move.power * 2 : move.power,
      reason: boosted ? "fusion_bolt_used_this_turn" : "base_power",
    };
  },
  grasspledge: ({ state, move }) => {
    const boosted = state?.turnMoves?.some(
      (entry) =>
        ["firepledge", "waterpledge"].includes(cleanId(entry.id)),
    );
    return {
      power: boosted ? 150 : move.power,
      reason: boosted ? "pledge_combo" : "base_power",
    };
  },
  grassknot: ({ defender }) => ({
    power: weightPower(defender.weightKg),
    reason: "target_weight",
  }),
  gravapple: ({ state, move }) => {
    const gravity = Boolean(state.field?.pseudoWeather?.gravity);
    return {
      power: gravity ? Math.floor(move.power * 1.5) : move.power,
      reason: gravity ? "gravity" : "base_power",
    };
  },
  gyroball: ({ attackerSpeed, defenderSpeed }) => ({
    power: boundedPower((25 * defenderSpeed) / Math.max(1, attackerSpeed) + 1, 150),
    reason: "inverse_speed_ratio",
  }),
  hardpress: ({ defender }) => ({
    power: boundedPower((100 * defender.hp) / defender.stats.hp, 100),
    reason: "target_hp_ratio",
  }),
  facade: ({ attacker, move }) => ({
    power: ["brn", "par", "psn", "tox"].includes(attacker.status)
      ? move.power * 2
      : move.power,
    reason: ["brn", "par", "psn", "tox"].includes(attacker.status)
      ? "user_statused"
      : "base_power",
  }),
  fling: ({ attacker, move }) => {
    const item = cleanId(attacker.item);
    const powers = {
      ironball: 130,
      hardstone: 100,
      thickclub: 90,
      assaultvest: 80,
      lifeorb: 30,
      choiceband: 10,
      choicescarf: 10,
      choicespecs: 10,
      leftovers: 10,
      focussash: 10,
    };
    return {
      power: item ? (powers[item] ?? Math.max(10, move.power || 30)) : 0,
      reason: item ? "flung_held_item" : "no_held_item",
    };
  },
  heatcrash: ({ attacker, defender }) => ({
    power: weightRatioPower(attacker.weightKg, defender.weightKg),
    reason: "weight_ratio",
  }),
  heavyslam: ({ attacker, defender }) => ({
    power: weightRatioPower(attacker.weightKg, defender.weightKg),
    reason: "weight_ratio",
  }),
  hex: ({ defender, move }) => ({
    power: defender.status ? move.power * 2 : move.power,
    reason: defender.status ? "target_statused" : "base_power",
  }),
  venoshock: ({ defender, move }) => ({
    power: ["psn", "tox"].includes(defender.status) ? move.power * 2 : move.power,
    reason: ["psn", "tox"].includes(defender.status)
      ? "poisoned_target"
      : "base_power",
  }),
  infernalparade: ({ defender, move }) => ({
    power: defender.status ? move.power * 2 : move.power,
    reason: defender.status ? "target_statused" : "base_power",
  }),
  iceball: ({ attacker, move }) => ({
    power: rollingPower(attacker, move),
    reason: attacker.volatiles?.defensecurl
      ? "consecutive_successful_uses_and_defense_curl"
      : "consecutive_successful_uses",
  }),
  lastrespects: ({ state, attackerSide }) => ({
    power: Math.min(
      5050,
      50 +
        state.sides[attackerSide].team.filter((pokemon) => pokemon.fainted)
          .length *
          50,
    ),
    reason: "fainted_allies",
  }),
  lashout: ({ attacker, move }) => ({
    power: attacker.turnState?.statsLowered ? move.power * 2 : move.power,
    reason: attacker.turnState?.statsLowered ? "user_stats_lowered" : "base_power",
  }),
  knockoff: ({ defender, move }) => ({
    power: defender.item ? move.power * 1.5 : move.power,
    reason: defender.item ? "target_holding_item" : "base_power",
  }),
  lowkick: ({ defender }) => ({
    power: weightPower(defender.weightKg),
    reason: "target_weight",
  }),
  magnitude: ({ rng }) => {
    const roll = rng?.next ? rng.next() : 0.5;
    const table = [
      { threshold: 0.05, magnitude: 4, power: 10 },
      { threshold: 0.15, magnitude: 5, power: 30 },
      { threshold: 0.35, magnitude: 6, power: 50 },
      { threshold: 0.65, magnitude: 7, power: 70 },
      { threshold: 0.85, magnitude: 8, power: 90 },
      { threshold: 0.95, magnitude: 9, power: 110 },
      { threshold: 1, magnitude: 10, power: 150 },
    ];
    const result = table.find((entry) => roll < entry.threshold) ?? table[3];
    return {
      power: result.power,
      reason: `magnitude_${result.magnitude}`,
    };
  },
  mistyexplosion: ({ state, move }) => {
    const terrain = cleanId(state.field?.terrain?.id);
    return {
      power: terrain === "mistyterrain" ? Math.floor(move.power * 1.5) : move.power,
      reason: terrain === "mistyterrain" ? "misty_terrain" : "base_power",
    };
  },
  naturalgift: ({ attacker }) => ({
    power: naturalGiftData(attacker.item).power,
    reason: "held_berry",
  }),
  spitup: ({ attacker }) => ({
    power:
      Math.max(
        1,
        Math.min(3, Number(attacker.volatiles?.stockpile?.count ?? 0)),
      ) * 100,
    reason: "stockpile_count",
  }),
  payback: ({ defender, move }) => ({
    power: defender.turnState?.acted ? move.power * 2 : move.power,
    reason: defender.turnState?.acted ? "target_acted" : "target_not_acted",
  }),
  pikapapow: ({ attacker }) => ({
    power: boundedPower((attacker.friendship * 10) / 25, 102),
    reason: "friendship",
  }),
  powertrip: ({ attacker }) => ({
    power: 20 + positiveBoostTotal(attacker) * 20,
    reason: "positive_stat_stages",
  }),
  punishment: ({ defender }) => ({
    power: Math.min(200, 60 + positiveBoostTotal(defender) * 20),
    reason: "target_positive_stat_stages",
  }),
  present: ({ move, rng }) => {
    const roll = rng?.next ? rng.next() : 0.5;
    if (roll < 0.4) return { power: 40, reason: "present_40" };
    if (roll < 0.7) return { power: 80, reason: "present_80" };
    if (roll < 0.8) return { power: 120, reason: "present_120" };
    return { power: move.power || 40, reason: "present_heal_placeholder" };
  },
  psyblade: ({ state, move }) => {
    const terrain = cleanId(state.field?.terrain?.id);
    return {
      power: terrain === "electricterrain" ? Math.floor(move.power * 1.5) : move.power,
      reason: terrain === "electricterrain" ? "electric_terrain" : "base_power",
    };
  },
  pursuit: ({ state, defenderSide, move }) => {
    const targetSwitching = state?.currentActions?.some(
      (action) => action.side === defenderSide && action.kind === "switch",
    );
    return {
      power: targetSwitching ? move.power * 2 : move.power,
      reason: targetSwitching ? "target_switching" : "base_power",
    };
  },
  retaliate: ({ state, attackerSide, move }) => ({
    power:
      state?.sides?.[attackerSide]?.lastFaintedTurn === state.turn - 1
        ? move.power * 2
        : move.power,
    reason:
      state?.sides?.[attackerSide]?.lastFaintedTurn === state.turn - 1
        ? "ally_fainted_previous_turn"
        : "base_power",
  }),
  revenge: ({ attacker, move }) => ({
    power: attacker.turnState?.damageTaken > 0 ? move.power * 2 : move.power,
    reason:
      attacker.turnState?.damageTaken > 0
        ? "user_damaged_this_turn"
        : "base_power",
  }),
  rollout: ({ attacker, move }) => ({
    power: rollingPower(attacker, move),
    reason: attacker.volatiles?.defensecurl
      ? "consecutive_successful_uses_and_defense_curl"
      : "consecutive_successful_uses",
  }),
  reversal: ({ attacker }) => ({
    power: flailPower(attacker),
    reason: "user_hp_threshold",
  }),
  return: ({ attacker }) => ({
    power: boundedPower(attacker.friendship / 2.5, 102),
    reason: "friendship",
  }),
  risingvoltage: ({ state, defender, move }) => {
    const terrain = cleanId(state.field?.terrain?.id);
    const grounded =
      !defender.types.includes("Flying") &&
      defender.ability !== "levitate" &&
      defender.item !== "airballoon";
    return {
      power:
        terrain === "electricterrain" && grounded
          ? move.power * 2
          : move.power,
      reason:
        terrain === "electricterrain" && grounded
          ? "grounded_target_on_electric_terrain"
          : "base_power",
    };
  },
  round: ({ state, attackerSide, move }) => {
    const boosted = state?.turnMoves?.some(
      (entry) => entry.side !== attackerSide && cleanId(entry.id) === "round",
    );
    return {
      power: boosted ? move.power * 2 : move.power,
      reason: boosted ? "round_used_this_turn" : "base_power",
    };
  },
  waterpledge: ({ state, move }) => {
    const boosted = state?.turnMoves?.some(
      (entry) =>
        ["firepledge", "grasspledge"].includes(cleanId(entry.id)),
    );
    return {
      power: boosted ? 150 : move.power,
      reason: boosted ? "pledge_combo" : "base_power",
    };
  },
  storedpower: ({ attacker }) => ({
    power: 20 + positiveBoostTotal(attacker) * 20,
    reason: "positive_stat_stages",
  }),
  ragefist: ({ attacker }) => ({
    power: Math.min(350, 50 + attacker.timesHit * 50),
    reason: "times_hit",
  }),
  smellingsalts: ({ defender, move }) => ({
    power: defender.status === "par" ? move.power * 2 : move.power,
    reason: defender.status === "par" ? "paralyzed_target" : "base_power",
  }),
  stompingtantrum: ({ attacker, move }) => ({
    power: attacker.lastMoveSucceeded === false ? move.power * 2 : move.power,
    reason:
      attacker.lastMoveSucceeded === false
        ? "previous_move_failed"
        : "base_power",
  }),
  temperflare: ({ attacker, move }) => ({
    power: attacker.lastMoveSucceeded === false ? move.power * 2 : move.power,
    reason:
      attacker.lastMoveSucceeded === false
        ? "previous_move_failed"
        : "base_power",
  }),
  terablast: ({ attacker, move }) => ({
    power:
      attacker.terastallized && cleanId(attacker.teraType) === "stellar"
        ? 100
        : move.power,
    reason:
      attacker.terastallized && cleanId(attacker.teraType) === "stellar"
        ? "stellar_terastallization"
        : "base_power",
  }),
  tripleaxel: ({ hit, move }) => ({
    power: move.power * Math.max(1, hit),
    reason: "successive_hit",
  }),
  triplekick: ({ hit, move }) => ({
    power: move.power * Math.max(1, hit),
    reason: "successive_hit",
  }),
  trumpcard: ({ move }) => {
    const remainingPp = Math.max(0, Number(move.pp) || 0);
    const powerByPp = [200, 80, 60, 50];
    return {
      power: powerByPp[remainingPp] ?? 40,
      reason: "remaining_pp",
    };
  },
  veeveevolley: ({ attacker }) => ({
    power: boundedPower((attacker.friendship * 10) / 25, 102),
    reason: "friendship",
  }),
  waterspout: ({ attacker }) => ({
    power: boundedPower((150 * attacker.hp) / attacker.stats.hp, 150),
    reason: "user_hp_ratio",
  }),
  wakeupslap: ({ defender, move }) => ({
    power: defender.status === "slp" ? move.power * 2 : move.power,
    reason: defender.status === "slp" ? "sleeping_target" : "base_power",
  }),
  watershuriken: ({ attacker, move }) => {
    const ashGreninja =
      cleanId(attacker.id) === "greninjaash" &&
      cleanId(attacker.ability) === "battlebond";
    return {
      power: ashGreninja ? 20 : move.power,
      reason: ashGreninja ? "ash_greninja_battle_bond" : "base_power",
    };
  },
  wringout: ({ defender }) => ({
    power: boundedPower((120 * defender.hp) / defender.stats.hp, 120),
    reason: "target_hp_ratio",
  }),
};

export const SUPPORTED_DYNAMIC_POWER_MOVES = new Set(
  Object.keys(HANDLERS),
);

export const DYNAMAX_BLOCKED_WEIGHT_MOVES = new Set([
  "grassknot",
  "heatcrash",
  "heavyslam",
  "lowkick",
]);

export const SUPPORTED_MOVE_CALLBACKS = new Map([
  ["afteryou", new Set(["onHit"])],
  ["allyswitch", new Set(["onHit", "onPrepareHit"])],
  ["assist", new Set(["onHit"])],
  ["aurawheel", new Set(["onModifyType", "onTry"])],
  ["bestow", new Set(["onHit"])],
  ["bleakwindstorm", new Set(["onModifyMove"])],
  ["clangoroussoul", new Set(["onHit", "onTry", "onTryHit"])],
  ["comeuppance", new Set(["onModifyTarget", "onTry"])],
  ["darkvoid", new Set(["onTry"])],
  ["doodle", new Set(["onHit"])],
  ["doomdesire", new Set(["onTry"])],
  ["filletaway", new Set(["onHit", "onTry", "onTryHit"])],
  ["flowershield", new Set(["onHitField"])],
  ["gearup", new Set(["onHitSide"])],
  ["geomancy", new Set(["onTryMove"])],
  ["gmaxsnooze", new Set(["onAfterSubDamage", "onHit"])],
  ["grudge", new Set([])],
  ["holdback", new Set(["onDamage"])],
  ["instruct", new Set(["onHit"])],
  ["lightthatburnsthesky", new Set(["onModifyMove"])],
  ["magneticflux", new Set(["onHitSide"])],
  ["naturalgift", new Set(["onModifyType", "onPrepareHit"])],
  ["orderup", new Set(["onAfterMoveSecondarySelf"])],
  ["polarflare", new Set(["onAfterMoveSecondarySelf"])],
  ["pollenpuff", new Set(["onHit", "onTryHit", "onTryMove"])],
  ["present", new Set(["onModifyMove"])],
  ["quash", new Set(["onHit"])],
  ["ragingbull", new Set(["onModifyType", "onTryHit"])],
  ["relicsong", new Set(["onAfterMoveSecondarySelf"])],
  ["rototiller", new Set(["onHitField"])],
  ["sandsearstorm", new Set(["onModifyMove"])],
  ["sappyseed", new Set(["onHit"])],
  ["secretpower", new Set(["onModifyMove"])],
  ["shelltrap", new Set(["onTryMove", "priorityChargeCallback"])],
  ["sketch", new Set(["onHit"])],
  ["splinteredstormshards", new Set(["onAfterSubDamage", "onHit"])],
  ["struggle", new Set(["onModifyMove"])],
  ["stuffcheeks", new Set(["onDisableMove", "onHit", "onTry"])],
  ["teatime", new Set(["onHitField"])],
  ["thousandarrows", new Set(["onEffectiveness"])],
  ["wildboltstorm", new Set(["onModifyMove"])],
  ["aquaring", new Set([])],
  ["attract", new Set(["onTryImmunity"])],
  ["banefulbunker", new Set(["onHit", "onPrepareHit"])],
  ["barbbarrage", new Set(["onBasePower"])],
  ["bind", new Set(["onHit"])],
  ["blizzard", new Set(["onModifyMove"])],
  ["bounce", new Set(["onTryMove"])],
  ["brickbreak", new Set(["onTryHit"])],
  ["bugbite", new Set(["onHit"])],
  ["auroraveil", new Set(["onTry"])],
  ["axekick", new Set(["onMoveFail"])],
  ["acupressure", new Set(["onHit"])],
  ["autotomize", new Set(["onHit", "onTryHit"])],
  ["belch", new Set(["onDisableMove", "onTry"])],
  ["beakblast", new Set(["onAfterMove", "priorityChargeCallback"])],
  ["beatup", new Set(["onModifyMove"])],
  ["bellydrum", new Set(["onHit"])],
  ["bide", new Set(["beforeMoveCallback"])],
  ["batonpass", new Set(["onHit"])],
  ["block", new Set(["onHit"])],
  ["brine", new Set(["onBasePower"])],
  ["burnup", new Set(["onTryMove"])],
  ["camouflage", new Set(["onHit"])],
  ["burningbulwark", new Set(["onHit", "onPrepareHit"])],
  ["charge", new Set(["onHit"])],
  ["chillyreception", new Set(["priorityChargeCallback"])],
  ["clearsmog", new Set(["onHit"])],
  ["clamp", new Set(["onHit"])],
  ["counter", new Set(["beforeTurnCallback", "onTry"])],
  ["craftyshield", new Set(["onTry"])],
  ["curse", new Set(["onHit", "onModifyMove", "onTryHit"])],
  ["captivate", new Set(["onTryImmunity"])],
  ["celebrate", new Set(["onTryHit"])],
  ["collisioncourse", new Set(["onBasePower"])],
  ["copycat", new Set(["onHit"])],
  ["coreenforcer", new Set(["onAfterSubDamage", "onHit"])],
  ["covet", new Set(["onAfterHit"])],
  ["corrosivegas", new Set(["onHit"])],
  ["courtchange", new Set(["onHitField"])],
  ["conversion", new Set(["onHit"])],
  ["conversion2", new Set(["onHit"])],
  ["destinybond", new Set(["onPrepareHit"])],
  ["dig", new Set(["onTryMove"])],
  ["detect", new Set(["onHit", "onPrepareHit"])],
  ["defog", new Set(["onHit"])],
  ["disable", new Set(["onTryHit"])],
  ["dive", new Set(["onTryMove"])],
  ["doubleshock", new Set(["onTryMove"])],
  ["dreameater", new Set(["onTryImmunity"])],
  ["dragoncheer", new Set([])],
  ["electrify", new Set(["onTryHit"])],
  ["embargo", new Set([])],
  ["electroshot", new Set(["onTryMove"])],
  ["entrainment", new Set(["onHit", "onTryHit"])],
  ["endeavor", new Set(["onTryImmunity"])],
  ["echoedvoice", new Set(["onTryMove"])],
  ["electrodrift", new Set(["onBasePower"])],
  ["encore", new Set([])],
  ["endure", new Set(["onHit", "onPrepareHit"])],
  ["facade", new Set(["onBasePower"])],
  ["ficklebeam", new Set(["onBasePower"])],
  ["flatter", new Set(["onHit"])],
  ["expandingforce", new Set(["onBasePower", "onModifyMove"])],
  ["fakeout", new Set(["onTry"])],
  ["falseswipe", new Set(["onDamage"])],
  ["fellstinger", new Set(["onAfterMoveSecondarySelf"])],
  ["firespin", new Set(["onHit"])],
  ["firepledge", new Set(["onModifyMove", "onPrepareHit"])],
  ["firstimpression", new Set(["onTry"])],
  ["fissure", new Set(["onTryHit"])],
  ["fly", new Set(["onTryMove"])],
  ["flyingpress", new Set(["onEffectiveness"])],
  ["foresight", new Set(["onTryHit"])],
  ["focuspunch", new Set(["beforeMoveCallback", "priorityChargeCallback"])],
  ["followme", new Set(["onTry"])],
  ["fling", new Set(["onPrepareHit"])],
  ["floralhealing", new Set(["onHit"])],
  ["forestscurse", new Set(["onHit"])],
  ["freezeshock", new Set(["onTryMove"])],
  ["freezyfrost", new Set(["onHit"])],
  ["freezedry", new Set(["onEffectiveness"])],
  ["futuresight", new Set(["onTry"])],
  ["finalgambit", new Set([])],
  ["fusionbolt", new Set(["onBasePower"])],
  ["fusionflare", new Set(["onBasePower"])],
  ["gastroacid", new Set(["onTryHit"])],
  ["aromatherapy", new Set(["onHit"])],
  ["grassyglide", new Set(["onModifyPriority"])],
  ["grassknot", new Set(["onTryHit"])],
  ["grasspledge", new Set(["onModifyMove", "onPrepareHit"])],
  ["gravapple", new Set(["onBasePower"])],
  ["guardianofalola", new Set([])],
  ["growth", new Set(["onModifyMove"])],
  ["guardsplit", new Set(["onHit"])],
  ["guardswap", new Set(["onHit"])],
  ["haze", new Set(["onHitField"])],
  ["happyhour", new Set(["onTryHit"])],
  ["healbell", new Set(["onHit"])],
  ["healblock", new Set([])],
  ["highjumpkick", new Set(["onMoveFail"])],
  ["heatcrash", new Set(["onTryHit"])],
  ["healpulse", new Set(["onHit"])],
  ["helpinghand", new Set(["onTryHit"])],
  ["healingwish", new Set(["onTryHit"])],
  ["heartswap", new Set(["onHit"])],
  ["heavyslam", new Set(["onTryHit"])],
  ["hurricane", new Set(["onModifyMove"])],
  ["ceaselessedge", new Set(["onAfterHit", "onAfterSubDamage"])],
  ["flameburst", new Set(["onAfterSubDamage", "onHit"])],
  ["hyperspacefury", new Set(["onTry"])],
  ["iceburn", new Set(["onTryMove"])],
  ["iceball", new Set(["onAfterMove", "onModifyMove"])],
  ["icespinner", new Set(["onAfterHit", "onAfterSubDamage"])],
  ["incinerate", new Set(["onHit"])],
  ["ingrain", new Set(["onHit"])],
  ["imprison", new Set([])],
  ["infestation", new Set(["onHit"])],
  ["ivycudgel", new Set(["onModifyType", "onPrepareHit"])],
  ["jawlock", new Set(["onHit"])],
  ["judgment", new Set(["onModifyType"])],
  ["junglehealing", new Set(["onHit"])],
  ["jumpkick", new Set(["onMoveFail"])],
  ["kingsshield", new Set(["onHit", "onPrepareHit"])],
  ["knockoff", new Set(["onAfterHit", "onBasePower"])],
  ["leechseed", new Set(["onTryImmunity"])],
  ["lastresort", new Set(["onTry"])],
  ["laserfocus", new Set([])],
  ["lockon", new Set(["onHit", "onTryHit"])],
  ["lashout", new Set(["onBasePower"])],
  ["lowkick", new Set(["onTryHit"])],
  ["lunardance", new Set(["onTryHit"])],
  ["magiccoat", new Set([])],
  ["lunarblessing", new Set(["onHit"])],
  ["magicpowder", new Set(["onHit"])],
  ["magnetrise", new Set(["onTry"])],
  ["magmastorm", new Set(["onHit"])],
  ["magnitude", new Set(["onModifyMove", "onUseMoveMessage"])],
  ["matblock", new Set(["onTry"])],
  ["multiattack", new Set(["onModifyType"])],
  ["maxguard", new Set(["onHit", "onPrepareHit"])],
  ["mefirst", new Set(["onTryHit"])],
  ["metalburst", new Set(["onModifyTarget", "onTry"])],
  ["metronome", new Set(["onHit"])],
  ["meteorbeam", new Set(["onTryMove"])],
  ["mimic", new Set(["onHit"])],
  ["mindreader", new Set(["onHit", "onTryHit"])],
  ["mindblown", new Set(["onAfterMove"])],
  ["miracleeye", new Set(["onTryHit"])],
  ["minimize", new Set([])],
  ["mirrormove", new Set(["onTryHit"])],
  ["mistyexplosion", new Set(["onBasePower"])],
  ["mirrorcoat", new Set(["beforeTurnCallback", "onTry"])],
  ["moonlight", new Set(["onHit"])],
  ["meanlook", new Set(["onHit"])],
  ["nightmare", new Set([])],
  ["noretreat", new Set(["onTry"])],
  ["naturesmadness", new Set([])],
  ["naturepower", new Set(["onTryHit"])],
  ["obstruct", new Set(["onHit", "onPrepareHit"])],
  ["octolock", new Set(["onTryImmunity"])],
  ["odorsleuth", new Set(["onTryHit"])],
  ["mortalspin", new Set(["onAfterHit", "onAfterSubDamage"])],
  ["morningsun", new Set(["onHit"])],
  ["partingshot", new Set(["onHit"])],
  ["painsplit", new Set(["onHit"])],
  ["perishsong", new Set(["onHitField"])],
  ["phantomforce", new Set(["onTryMove"])],
  ["photongeyser", new Set(["onModifyMove"])],
  ["poltergeist", new Set(["onTry", "onTryHit"])],
  ["pluck", new Set(["onHit"])],
  ["powder", new Set([])],
  ["powershift", new Set([])],
  ["powersplit", new Set(["onHit"])],
  ["powertrick", new Set([])],
  ["powerswap", new Set(["onHit"])],
  ["psyblade", new Set(["onBasePower"])],
  ["psychicfangs", new Set(["onTryHit"])],
  ["quickguard", new Set(["onHitSide", "onTry"])],
  ["protect", new Set(["onHit", "onPrepareHit"])],
  ["psychoshift", new Set(["onTryHit"])],
  ["psychup", new Set(["onHit"])],
  ["purify", new Set(["onHit"])],
  ["psywave", new Set([])],
  ["pursuit", new Set(["beforeTurnCallback", "onModifyMove"])],
  ["rapidspin", new Set(["onAfterHit", "onAfterSubDamage"])],
  ["ragepowder", new Set(["onTry"])],
  ["razorwind", new Set(["onTryMove"])],
  ["rest", new Set(["onHit", "onTry"])],
  ["refresh", new Set(["onHit"])],
  ["reflecttype", new Set(["onHit"])],
  ["revelationdance", new Set(["onModifyType"])],
  ["recycle", new Set(["onHit"])],
  ["retaliate", new Set(["onBasePower"])],
  ["revivalblessing", new Set(["onTryHit"])],
  ["roleplay", new Set(["onHit", "onTryHit"])],
  ["ruination", new Set(["onTryHit"])],
  ["rollout", new Set(["onAfterMove", "onModifyMove"])],
  ["round", new Set(["onTry"])],
  ["sandtomb", new Set(["onHit"])],
  ["shadowforce", new Set(["onTryMove"])],
  ["shedtail", new Set(["onHit", "onTryHit"])],
  ["shoreup", new Set(["onHit"])],
  ["silktrap", new Set(["onHit", "onPrepareHit"])],
  ["simplebeam", new Set(["onHit", "onTryHit"])],
  ["skillswap", new Set(["onHit"])],
  ["speedswap", new Set(["onHit"])],
  ["skullbash", new Set(["onTryMove"])],
  ["skydrop", new Set(["onHit", "onModifyMove", "onMoveFail", "onTry", "onTryHit"])],
  ["smackdown", new Set([])],
  ["sleeptalk", new Set(["onHit", "onTry"])],
  ["snatch", new Set([])],
  ["snore", new Set(["onTry"])],
  ["steelroller", new Set(["onAfterSubDamage", "onHit", "onTry"])],
  ["skyattack", new Set(["onTryMove"])],
  ["smellingsalts", new Set(["onHit"])],
  ["shellsidearm", new Set(["onAfterSubDamage", "onHit", "onModifyMove", "onPrepareHit"])],
  ["snaptrap", new Set(["onHit"])],
  ["soak", new Set(["onHit"])],
  ["solarbeam", new Set(["onBasePower", "onTryMove"])],
  ["solarblade", new Set(["onBasePower", "onTryMove"])],
  ["splash", new Set(["onTry", "onTryHit"])],
  ["spotlight", new Set(["onTryHit"])],
  ["spiderweb", new Set(["onHit"])],
  ["spitup", new Set(["onAfterMove", "onTry"])],
  ["spikyshield", new Set(["onHit", "onPrepareHit"])],
  ["sparklingaria", new Set(["onAfterMove"])],
  ["spite", new Set(["onHit"])],
  ["steelbeam", new Set(["onAfterMove"])],
  ["strengthsap", new Set(["onHit"])],
  ["stoneaxe", new Set(["onAfterHit", "onAfterSubDamage"])],
  ["substitute", new Set(["onHit", "onTryHit"])],
  ["swallow", new Set(["onHit", "onTry"])],
  ["stockpile", new Set(["onTry"])],
  ["synchronoise", new Set(["onTryImmunity"])],
  ["supercellslam", new Set(["onMoveFail"])],
  ["synthesis", new Set(["onHit"])],
  ["suckerpunch", new Set(["onTry"])],
  ["tarshot", new Set([])],
  ["takeheart", new Set(["onHit"])],
  ["technoblast", new Set(["onModifyType"])],
  ["terastarstorm", new Set(["onModifyMove", "onModifyType"])],
  ["terrainpulse", new Set(["onModifyMove", "onModifyType"])],
  ["teeterdance", new Set(["onHit"])],
  [
    "terablast",
    new Set(["onModifyMove", "onModifyType", "onPrepareHit"]),
  ],
  ["thunder", new Set(["onModifyMove"])],
  ["thundercage", new Set(["onHit"])],
  ["thousandwaves", new Set(["onHit"])],
  ["thief", new Set(["onAfterHit"])],
  ["tidyup", new Set(["onHit"])],
  ["transform", new Set(["onHit"])],
  ["trickortreat", new Set(["onHit"])],
  ["trick", new Set(["onHit", "onTryImmunity"])],
  ["switcheroo", new Set(["onHit", "onTryImmunity"])],
  ["telekinesis", new Set(["onTry"])],
  ["teleport", new Set(["onTry"])],
  ["thunderclap", new Set(["onTry"])],
  ["torment", new Set([])],
  ["topsyturvy", new Set(["onHit"])],
  ["upperhand", new Set(["onTry"])],
  ["uproar", new Set(["onTryHit"])],
  ["venomdrench", new Set(["onHit"])],
  ["venoshock", new Set(["onBasePower"])],
  ["wakeupslap", new Set(["onHit"])],
  ["weatherball", new Set(["onModifyMove", "onModifyType"])],
  ["waterpledge", new Set(["onModifyMove", "onPrepareHit"])],
  ["wideguard", new Set(["onHitSide", "onTry"])],
  ["whirlpool", new Set(["onHit"])],
  ["wrap", new Set(["onHit"])],
  ["wish", new Set(["onTry"])],
  ["worryseed", new Set(["onHit", "onTryHit", "onTryImmunity"])],
  ["yawn", new Set(["onTryHit"])],
]);

export const SUPPORTED_MOVE_REQUIREMENTS = new Map([
  ["comeuppance", new Set(["dynamicDamage"])],
  ["grudge", new Set(["volatileStatus"])],
  ["naturalgift", new Set(["dynamicPower"])],
  ["present", new Set(["dynamicPower"])],
  ["thousandarrows", new Set(["volatileStatus"])],
  ["aquaring", new Set(["volatileStatus"])],
  ["attract", new Set(["volatileStatus"])],
  ["banefulbunker", new Set(["volatileStatus"])],
  ["barbbarrage", new Set(["dynamicPower"])],
  ["batonpass", new Set(["selfSwitch"])],
  ["bind", new Set(["volatileStatus"])],
  ["block", new Set(["volatileStatus"])],
  ["charge", new Set(["volatileStatus"])],
  ["chillyreception", new Set(["weather", "selfSwitch"])],
  ["burningbulwark", new Set(["volatileStatus"])],
  ["beatup", new Set(["dynamicPower"])],
  ["bide", new Set(["volatileStatus"])],
  ["circlethrow", new Set(["forceSwitch"])],
  ["clamp", new Set(["volatileStatus"])],
  ["confuseray", new Set(["volatileStatus"])],
  ["counter", new Set(["dynamicDamage"])],
  ["craftyshield", new Set(["sideCondition"])],
  ["curse", new Set(["volatileStatus"])],
  ["defensecurl", new Set(["volatileStatus"])],
  ["destinybond", new Set(["volatileStatus"])],
  ["detect", new Set(["volatileStatus"])],
  ["disable", new Set(["volatileStatus"])],
  ["dragonrage", new Set(["fixedDamage"])],
  ["dragontail", new Set(["forceSwitch"])],
  ["dragoncheer", new Set(["volatileStatus"])],
  ["electrify", new Set(["volatileStatus"])],
  ["embargo", new Set(["volatileStatus"])],
  ["echoedvoice", new Set(["dynamicPower"])],
  ["endeavor", new Set(["dynamicDamage"])],
  ["encore", new Set(["volatileStatus"])],
  ["endure", new Set(["volatileStatus"])],
  ["firespin", new Set(["volatileStatus"])],
  ["finalgambit", new Set(["dynamicDamage"])],
  ["firepledge", new Set(["dynamicPower"])],
  ["flipturn", new Set(["selfSwitch"])],
  ["fissure", new Set(["ohko"])],
  ["guillotine", new Set(["ohko"])],
  ["horndrill", new Set(["ohko"])],
  ["flatter", new Set(["volatileStatus"])],
  ["ficklebeam", new Set(["dynamicPower"])],
  ["followme", new Set(["volatileStatus"])],
  ["foresight", new Set(["volatileStatus"])],
  ["focusenergy", new Set(["volatileStatus"])],
  ["helpinghand", new Set(["volatileStatus"])],
  ["gastroacid", new Set(["volatileStatus"])],
  ["healblock", new Set(["volatileStatus"])],
  ["healingwish", new Set(["slotCondition"])],
  ["infestation", new Set(["volatileStatus"])],
  ["imprison", new Set(["volatileStatus"])],
  ["ingrain", new Set(["volatileStatus"])],
  ["jawlock", new Set(["volatileStatus"])],
  ["kingsshield", new Set(["volatileStatus"])],
  ["leechseed", new Set(["volatileStatus"])],
  ["laserfocus", new Set(["volatileStatus"])],
  ["lashout", new Set(["dynamicPower"])],
  ["lockon", new Set(["volatileStatus"])],
  ["lunardance", new Set(["slotCondition"])],
  ["magiccoat", new Set(["volatileStatus"])],
  ["magmastorm", new Set(["volatileStatus"])],
  ["magnitude", new Set(["dynamicPower"])],
  ["grasspledge", new Set(["dynamicPower"])],
  ["gravapple", new Set(["dynamicPower"])],
  ["guardianofalola", new Set(["dynamicDamage"])],
  ["fling", new Set(["dynamicPower"])],
  ["meanlook", new Set(["volatileStatus"])],
  ["magnetrise", new Set(["volatileStatus"])],
  ["metalburst", new Set(["dynamicDamage"])],
  ["matblock", new Set(["sideCondition"])],
  ["mindreader", new Set(["volatileStatus"])],
  ["mirrorcoat", new Set(["dynamicDamage"])],
  ["miracleeye", new Set(["volatileStatus"])],
  ["minimize", new Set(["volatileStatus"])],
  ["maxguard", new Set(["volatileStatus"])],
  ["mistyexplosion", new Set(["dynamicPower"])],
  ["nightshade", new Set(["fixedDamage"])],
  ["naturesmadness", new Set(["dynamicDamage"])],
  ["nightmare", new Set(["volatileStatus"])],
  ["noretreat", new Set(["volatileStatus"])],
  ["obstruct", new Set(["volatileStatus"])],
  ["octolock", new Set(["volatileStatus"])],
  ["odorsleuth", new Set(["volatileStatus"])],
  ["partingshot", new Set(["selfSwitch"])],
  ["perishsong", new Set(["volatileStatus"])],
  ["pursuit", new Set(["dynamicPower"])],
  ["retaliate", new Set(["dynamicPower"])],
  ["round", new Set(["dynamicPower"])],
  ["roar", new Set(["forceSwitch"])],
  ["ruination", new Set(["dynamicDamage"])],
  ["sandtomb", new Set(["volatileStatus"])],
  ["seismictoss", new Set(["fixedDamage"])],
  ["sheercold", new Set(["ohko"])],
  ["snatch", new Set(["volatileStatus"])],
  ["snaptrap", new Set(["volatileStatus"])],
  ["smackdown", new Set(["volatileStatus"])],
  ["sonicboom", new Set(["fixedDamage"])],
  ["spiderweb", new Set(["volatileStatus"])],
  ["spotlight", new Set(["volatileStatus"])],
  ["spikyshield", new Set(["volatileStatus"])],
  ["spitup", new Set(["dynamicPower"])],
  ["stockpile", new Set(["volatileStatus"])],
  ["substitute", new Set(["volatileStatus"])],
  ["shedtail", new Set(["volatileStatus", "selfSwitch"])],
  ["silktrap", new Set(["volatileStatus"])],
  ["superfang", new Set(["dynamicDamage"])],
  ["swagger", new Set(["volatileStatus"])],
  ["sweetkiss", new Set(["volatileStatus"])],
  ["supersonic", new Set(["volatileStatus"])],
  ["protect", new Set(["volatileStatus"])],
  ["powder", new Set(["volatileStatus"])],
  ["powershift", new Set(["volatileStatus"])],
  ["powertrick", new Set(["volatileStatus"])],
  ["psyblade", new Set(["dynamicPower"])],
  ["psywave", new Set(["dynamicDamage"])],
  ["quickguard", new Set(["sideCondition"])],
  ["ragepowder", new Set(["volatileStatus"])],
  ["revivalblessing", new Set(["slotCondition", "selfSwitch"])],
  ["taunt", new Set(["volatileStatus"])],
  ["tarshot", new Set(["volatileStatus"])],
  ["teeterdance", new Set(["volatileStatus"])],
  ["thundercage", new Set(["volatileStatus"])],
  ["telekinesis", new Set(["volatileStatus"])],
  ["teleport", new Set(["selfSwitch"])],
  ["torment", new Set(["volatileStatus"])],
  ["uproar", new Set(["volatileStatus"])],
  ["wideguard", new Set(["sideCondition"])],
  ["uturn", new Set(["selfSwitch"])],
  ["voltswitch", new Set(["selfSwitch"])],
  ["whirlpool", new Set(["volatileStatus"])],
  ["whirlwind", new Set(["forceSwitch"])],
  ["wrap", new Set(["volatileStatus"])],
  ["wish", new Set(["slotCondition"])],
  ["waterpledge", new Set(["dynamicPower"])],
  ["yawn", new Set(["volatileStatus"])],
]);

export function resolveDynamicPower(move, context) {
  const id = cleanId(move.id);
  const handler = HANDLERS[id];
  if (!handler) {
    return {
      power: move.power,
      reason: "unsupported_dynamic_power",
      supported: false,
    };
  }
  const result = handler({ ...context, move });
  return {
    power: boundedPower(result.power),
    reason: result.reason,
    supported: true,
  };
}

export function resolveDynamicPostHit(move, defender) {
  const id = cleanId(move.id);
  if (id === "sparklingaria" && defender.status === "brn") return "brn";
  if (id === "wakeupslap" && defender.status === "slp") return "slp";
  if (id === "smellingsalts" && defender.status === "par") return "par";
  return "";
}
