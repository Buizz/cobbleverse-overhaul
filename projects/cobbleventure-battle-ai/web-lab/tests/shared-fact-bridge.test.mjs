import assert from "node:assert/strict";
import test from "node:test";

import {
  analyzeSharedTeamProfileJson,
  deriveBatonPassFactsJson,
  deriveEntryHazardDamageJson,
  deriveHazardLayerFactsJson,
  deriveRecoveryFactsJson,
  deriveResidualPressureJson,
  deriveSaltCureDamageJson,
  deriveSharedSwitchMatchupObservationJson,
  evaluateSharedHitReactionsJson,
  evaluateSharedForcedSwitchJson,
  evaluateSharedSwitchMatchupJson,
  evaluateSharedSearchFieldCombatJson,
  evaluateSharedProjectionDifferentialJson,
  evaluateSharedSwitchPhaseJson,
  generateSharedSearchActionsJson,
  normalizeSharedBattleCommandsJson,
  normalizeSharedBattleStateJson,
  sampleSharedBattleRngJson,
  scoreObservedActionCandidateJson,
  scoreSharedTrainerItemCandidateJson,
  transitionSharedSearchStateJson,
} from "../lib/shared-ai-core.mjs";

const derive = (bridge, input) => JSON.parse(bridge(JSON.stringify(input)));

test("derives deterministic hit reactions through the generated shared core", () => {
  const result = derive(evaluateSharedHitReactionsJson, {
    damage: 40,
    attackerAlive: true,
    defenderAlive: true,
    defenderAbility: "Weak Armor",
    defenderItem: "Rocky Helmet",
    moveId: "Iron Head",
    moveType: "Steel",
    moveCategory: "Physical",
    contactPunishment: true,
    effectiveContact: true,
    resolveRandom: false,
  });

  assert.deepEqual(result.reactions.map((reaction) => reaction.code), ["rockyhelmet", "weakarmor"]);
  assert.equal(result.reactions[0].damageFraction, 1 / 6);
  assert.deepEqual(result.reactions[1].boosts, { defence: -1, speed: 2 });
});

test("derives stateful item and disguise hit reactions through the shared core", () => {
  const result = derive(evaluateSharedHitReactionsJson, {
    damage: 30,
    attackerItem: "Choice Band",
    defenderAbility: "Pickpocket",
    defenderHasIllusion: true,
    contactPunishment: true,
    resolveRandom: false,
  });

  assert.deepEqual(result.reactions.map((reaction) => reaction.code), ["illusion", "pickpocket"]);
  assert.equal(result.reactions[0].clearState, "illusion");
  assert.equal(result.reactions[1].itemAction, "steal_attacker_item");
});

test("compares an observed platform log snapshot with the shared projection", () => {
  const result = derive(evaluateSharedProjectionDifferentialJson, {
    expected: {
      turn: 2,
      active: [1, 0],
      hp: [[100, 88], [100]],
      maxHp: [[100, 100], [100]],
      hazards: [[1, 0, 0, 0], [0, 0, 0, 0]],
      pressures: [[{}, {}], [{}]],
      ranks: [
        [[0, 0, 0, 0, 0], [0, 0, 0, 0, 0]],
        [[-1, 0, 0, 0, 0]],
      ],
    },
    observed: {
      turn: 2,
      sides: [
        { activeHp: 88, activeMaximumHp: 100, hazards: [1, 0, 0, 0], pressure: {}, ranks: [0, 0, 0, 0, 0] },
        { activeHp: 100, activeMaximumHp: 100, hazards: [0, 0, 0, 0], pressure: {}, ranks: [-1, 0, 0, 0, 0] },
      ],
    },
  });

  assert.equal(result.matches, true);
  assert.deepEqual(result.differences, []);
});

test("derives the complete switch phase through the generated shared core", () => {
  const phase = derive(evaluateSharedSwitchPhaseJson, {
    outgoingHp: 30,
    outgoingMaximumHp: 90,
    outgoingAbility: "Regenerator",
    incomingHp: 100,
    incomingMaximumHp: 100,
    incomingAbility: "Intimidate",
    incomingTypes: ["Fire", "Flying"],
    incomingGrounded: false,
    stealthRockLayers: 1,
    spikesLayers: 3,
    opponentAlive: true,
    opponentDefence: 90,
    opponentSpecialDefence: 110,
  });
  const forced = derive(evaluateSharedForcedSwitchJson, {
    activeSlot: 0,
    teamHp: [50, 0, 70, 80],
    preferredSlot: 2,
    randomSelection: true,
    rngState: 1234,
  });

  assert.equal(phase.incomingHp, 50);
  assert.deepEqual(phase.operations.map((operation) => operation.code), [
    "regenerator", "reset_switch_state", "hazard_damage", "entry_boost",
  ]);
  assert.deepEqual(forced.eligibleSlots, [2, 3]);
  assert.equal(forced.selectedSlot, 2);
  assert.equal(forced.rngState, 1234);
});

test("derives platform-dependent entry adapter decisions from raw shared facts", () => {
  const base = {
    incomingHp: 100,
    incomingMaximumHp: 100,
    incomingTypes: ["Normal"],
    opponentAlive: true,
    opponentAbility: "Intimidate",
    opponentItem: "Choice Scarf",
    opponentMoves: [
      { id: "Fissure", type: "Ground", category: "Physical", ohko: true },
      { id: "Close Combat", type: "Fighting", category: "Physical", power: 120 },
    ],
  };
  const adapter = (extra) => derive(evaluateSharedSwitchPhaseJson, { ...base, ...extra })
    .operations.find((operation) => operation.code === "entry_adapter");

  assert.equal(adapter({ incomingAbility: "Trace" }).details.copiedAbility, "intimidate");
  assert.equal(adapter({ incomingAbility: "Forewarn" }).details.moveId, "fissure");
  assert.equal(adapter({ incomingAbility: "Anticipation" }).setState, "anticipation");
  assert.equal(adapter({ incomingAbility: "Frisk" }).details.item, "choicescarf");
  assert.equal(adapter({ incomingAbility: "Imposter" }).setState, "transformed");
  assert.deepEqual(adapter({
    incomingAbility: "Protosynthesis",
    incomingItem: "Booster Energy",
    incomingStats: { speed: 120, attack: 100 },
  }).details, { stat: "speed", source: "boosterenergy" });
  assert.equal(adapter({
    incomingAbility: "Tera Shift",
    incomingSpecies: "Terapagos",
  }).details.form, "terapagosterastal");
  assert.equal(adapter({ incomingAbility: "Forecast", weather: "RainDance" }).details.type, "water");
});

test("selects team roles and an ace through the generated shared core", () => {
  const result = derive(analyzeSharedTeamProfileJson, {
    members: [
      {
        slot: 1,
        pokemonId: "support",
        species: "Support",
        stats: { attack: 60, specialAttack: 60, speed: 110 },
        moveIds: ["batonpass", "agility"],
        catalogRoleScores: { pivot: 3, setupSweeper: 3 },
        catalogTags: ["setupboost", "pivot"],
        hasBatonPassSetupMove: true,
      },
      {
        slot: 2,
        pokemonId: "receiver",
        species: "Receiver",
        stats: { attack: 150, speed: 80 },
        moveIds: ["earthquake"],
        catalogRoleScores: { ace: 2.5 },
      },
    ],
  });

  assert.equal(result.aceCandidates[0].pokemonId, "receiver");
  assert.equal(result.aceCandidates[0].aceProfile.batonPassSupport, true);
  assert.equal(result.aceCandidates[0].aceProfile.estimatedKoCapacity, 2);
});

test("normalizes the same search candidate fixture in the JavaScript core", () => {
  const result = derive(generateSharedSearchActionsJson, [
    { id: "move:1", kind: "MOVE", score: 80, successProbability: 1.4, expectedDamage: 35 },
    { id: "move:1", score: 70 },
    { id: "switch:2", kind: "switch", score: 90, opponentKnockoutBeforeActionProbability: -0.5 },
    { id: "disabled", score: 999, disabled: true },
    { id: "illegal", score: 999, legal: false },
  ]);

  assert.deepEqual(result.map((candidate) => candidate.id), ["switch:2", "move:1"]);
  assert.equal(result[1].kind, "move");
  assert.equal(result[1].successProbability, 1);
  assert.equal(result[0].opponentKnockoutBeforeActionProbability, 0);
});

test("builds candidate scores from the same raw facts in JavaScript", () => {
  const move = derive(scoreObservedActionCandidateJson, {
    difficulty: "expert_search",
    strategy: "aggressive",
    expectedDamage: 80,
    accuracyPercent: 90,
    priority: 1,
    tacticalValue: 5,
    roleValue: 3,
    koChance: "possible",
    adjustments: [{ code: "fixture", weight: -2 }],
  });
  const item = derive(scoreSharedTrainerItemCandidateJson, {
    healing: 50,
    curedStatusValue: 70,
    preventsImmediateKnockout: true,
    incomingDamage: 30,
    futureRoleValue: 20,
    resourceCost: 10,
    strongMoveAvailable: true,
  });

  assert.equal(move.score, 126.9);
  assert.equal(item.score, 205);
});

test("derives the same switch matchup facts in JavaScript", () => {
  const result = derive(evaluateSharedSwitchMatchupJson, {
    currentHpRatio: 0.4,
    targetHpRatio: 0.8,
    currentIncomingDamage: 60,
    targetIncomingDamage: 0,
    currentIncomingDamageRatio: 0.6,
    targetIncomingDamageRatio: 0,
    currentOutgoingDamageRatio: 0.1,
    targetOutgoingDamageRatio: 0.7,
    hazardDamageRatio: 0.125,
    currentCanReachAction: false,
  });

  assert.equal(result.emergencyEscape, true);
  assert.equal(result.noEffectiveMoveEscape, true);
  assert.equal(result.matchupValue, 167.5);
  assert.equal(result.defensiveImprovement, 0.6);
  assert.equal(result.offensiveImprovement, 0.6);
});

test("derives the same serialized switch observation in JavaScript", () => {
  const hazard = derive(deriveEntryHazardDamageJson, {
    currentHp: 160,
    maximumHp: 200,
    stealthRockLayers: 1,
    spikesLayers: 2,
    rockEffectiveness: 2,
  });
  const evaluation = derive(deriveSharedSwitchMatchupObservationJson, {
    currentHp: 40,
    currentMaximumHp: 100,
    targetHp: 160,
    targetMaximumHp: 200,
    opponentHp: 100,
    currentIncomingDamage: 60,
    targetIncomingDamage: 20,
    currentOutgoingDamage: 10,
    targetOutgoingDamage: 70,
    targetHazardDamage: hazard.damage,
    currentSpeed: 80,
    opponentSpeed: 100,
  });

  assert.deepEqual(hazard, {
    damage: 83,
    damageRatio: 0.415,
    hpAfterHazards: 77,
  });
  assert.equal(evaluation.reachability.actsBefore, false);
  assert.equal(evaluation.facts.currentCanReachAction, false);
  assert.equal(evaluation.facts.targetHpRatio, 0.385);
  assert.equal(evaluation.facts.hazardDamageRatio, 0.415);
  assert.equal(evaluation.result.matchupValue, 105.5);
  assert.equal(evaluation.result.emergencyEscape, true);
});

test("transitions the complete timed battlefield state in JavaScript", () => {
  const action = (id, side, extras = {}) => ({
    action: { id, kind: "move", score: 1 },
    side,
    ...extras,
  });
  const next = derive(transitionSharedSearchStateJson, {
    state: {
      turn: 2,
      active: [0, 0],
      hp: [[100], [100]],
      maxHp: [[100], [100]],
      hazards: [[0, 0, 0, 0], [0, 0, 0, 0]],
      pressures: [[{}], [{}]],
      ranks: [[[0, 0, 0, 0, 0]], [[0, 0, 0, 0, 0]]],
      field: {
        weather: { id: "sunnyday", turns: 2 },
        terrain: { id: "grassyterrain", turns: 1 },
        pseudoWeather: { gravity: { id: "gravity", turns: 2 } },
      },
      sideConditions: [
        { reflect: { id: "reflect", turns: 2 } },
        {},
      ],
    },
    sideZeroAction: action("move:field", 0, {
      weather: "raindance",
      terrain: "electricterrain",
      pseudoWeather: "trickroom",
      sideCondition: "tailwind",
    }),
    sideOneAction: action("move:idle", 1),
  });

  assert.equal(next.field.weather.id, "raindance");
  assert.equal(next.field.weather.turns, 5);
  assert.equal(next.field.terrain.id, "electricterrain");
  assert.equal(next.field.pseudoWeather.trickroom.turns, 5);
  assert.equal(next.field.pseudoWeather.gravity.turns, 1);
  assert.equal(next.sideConditions[0].tailwind.turns, 4);
  assert.equal(next.sideConditions[0].reflect.turns, 1);
});

test("projects Imposter combat profile and restores its original profile on switch", () => {
  const profile = (id, ability, types, speed, side, slot) => ({
    id,
    ability,
    types,
    stats: { hp: 100, attack: 80, defence: 80, specialAttack: 80, specialDefence: 80, speed },
    moveSourceSide: side,
    moveSourceSlot: slot,
  });
  const lead = profile("lead", "pressure", ["water"], 70, 0, 0);
  const ditto = profile("ditto", "imposter", ["normal"], 48, 0, 1);
  const target = profile("dragapult", "infiltrator", ["dragon", "ghost"], 142, 1, 0);
  const state = {
    active: [0, 0],
    hp: [[100, 100], [100]],
    maxHp: [[100, 100], [100]],
    hazards: [[0, 0, 0, 0], [0, 0, 0, 0]],
    pressures: [[{}, {}], [{}]],
    ranks: [
      [[0, 0, 0, 0, 0], [0, 0, 0, 0, 0]],
      [[1, 2, 0, 0, 3]],
    ],
    heldItems: [["", "choicescarf"], ["lifeorb"]],
    abilityStates: [[[], []], [[]]],
    baseProfiles: [[lead, ditto], [target]],
    profiles: [[lead, ditto], [target]],
    formProfiles: [[{}, {}], [{}]],
  };
  const idle = { action: { id: "move:splash", kind: "move", score: 0 }, side: 1 };
  const transformed = derive(transitionSharedSearchStateJson, {
    state,
    sideZeroAction: {
      action: { id: "switch:ditto", kind: "switch", score: 1 },
      side: 0,
      switchSlot: 1,
      switchPhase: {
        operations: [{ code: "entry_adapter", source: "imposter", effect: "imposter", setState: "transformed" }],
      },
    },
    sideOneAction: idle,
  });
  assert.equal(transformed.profiles[0][1].id, "dragapult");
  assert.equal(transformed.profiles[0][1].moveSourceSide, 1);
  assert.deepEqual(transformed.ranks[0][1], [1, 2, 0, 0, 3]);

  const restored = derive(transitionSharedSearchStateJson, {
    state: transformed,
    sideZeroAction: {
      action: { id: "switch:lead", kind: "switch", score: 1 },
      side: 0,
      switchSlot: 0,
      switchPhase: { operations: [] },
    },
    sideOneAction: idle,
  });
  assert.equal(restored.profiles[0][1].id, "ditto");
  assert.equal(restored.profiles[0][1].moveSourceSide, 0);
});

test("evaluates all projected field combat modifiers in JavaScript", () => {
  const result = derive(evaluateSharedSearchFieldCombatJson, {
    field: {
      weather: { id: "raindance", turns: 3 },
      terrain: { id: "electricterrain", turns: 3 },
      pseudoWeather: { trickroom: { id: "trickroom", turns: 3 } },
    },
    attackerSideConditions: { tailwind: { id: "tailwind", turns: 2 } },
    defenderSideConditions: { lightscreen: { id: "lightscreen", turns: 2 } },
    moveType: "electric",
    moveCategory: "special",
    attackerTypes: ["electric"],
    attackerAbility: "swiftswim",
    defenderTypes: ["water"],
  });

  assert.deepEqual(result, {
    weatherDamageMultiplier: 1,
    terrainDamageMultiplier: 1.3,
    screenDamageMultiplier: 0.5,
    speedMultiplier: 4,
    trickRoomActive: true,
  });
});

test("exposes the JVM sustainment fixtures through the generated JavaScript core", () => {
  const recovery = derive(deriveRecoveryFactsJson, {
    currentHp: 40,
    maxHp: 100,
    healFraction: 0.5,
    opponentBestDamage: 30,
  });
  const pressure = derive(deriveResidualPressureJson, {
    currentHp: 35,
    maxHp: 160,
    toxicCounter: 3,
  });

  assert.equal(recovery.recoveryAmount, 50);
  assert.equal(recovery.recoveryNetHpChange, 20);
  assert.equal(pressure.toxicNextDamage, 30);
  assert.equal(pressure.toxicTwoTurnLethal, true);
  assert.equal(pressure.urgentSwitchPressure, true);
  assert.deepEqual(derive(deriveHazardLayerFactsJson, {
    conditionId: "spikes",
    currentLayers: 2,
  }), { conditionId: "spikes", maximumLayers: 3, layerDelta: 1 });
  assert.equal(derive(deriveSaltCureDamageJson, {
    maxHp: 200,
    waterOrSteel: true,
  }), 50);
});

test("exposes the JVM Baton Pass projection fixture through the generated JavaScript core", () => {
  const result = derive(deriveBatonPassFactsJson, {
    available: true,
    targetAvailable: true,
    targetSlot: 2,
    targetAce: true,
    currentBoosts: { speed: 1 },
    passedBoosts: { attack: 2, speed: 1 },
    targets: [
      { targetHp: 100, baselineDamage: 70, boostedDamage: 110 },
      { targetHp: 100, baselineDamage: 40, boostedDamage: 80 },
    ],
  });

  assert.equal(result.batonPassBoostTotal, 3);
  assert.equal(result.batonPassAdditionalBoostTotal, 2);
  assert.equal(result.batonPassNewKoTargets, 1);
  assert.equal(result.batonPassTransferValue, 133.8);
});

test("shares the canonical battle state, commands, and RNG with the web adapter", () => {
  const member = {
    id: "pikachu",
    name: "Pikachu",
    stats: { hp: 100, attack: 55, defence: 40, specialAttack: 50, specialDefence: 50, speed: 90 },
    hp: 130,
    boosts: { attack: 9 },
    moves: [{ id: "tackle", name: "Tackle", maxPp: 35, pp: 40 }],
  };
  const state = derive(normalizeSharedBattleStateJson, {
    seed: 4_294_967_297,
    turn: -1,
    sides: [
      { name: "A", team: [member] },
      { name: "B", team: [{ ...member, id: "eevee", name: "Eevee" }] },
    ],
  });
  const commands = JSON.parse(normalizeSharedBattleCommandsJson(
    JSON.stringify(state),
    JSON.stringify({ commands: [{ moveSlot: 1 }, { moveSlot: 1 }] }),
  ));
  const rng = derive(sampleSharedBattleRngJson, { seed: 12_345, draws: 5 });

  assert.equal(state.seed, 1);
  assert.equal(state.turn, 0);
  assert.equal(state.sides[0].team[0].hp, 100);
  assert.equal(state.sides[0].team[0].boosts.attack, 6);
  assert.equal(state.sides[0].team[0].moves[0].pp, 35);
  assert.deepEqual(commands.commands.map((command) => command.side), [0, 1]);
  assert.deepEqual(rng.unsignedValues, [2548642403, 2231655569, 3696820378, 1963845983, 3438003404]);
  assert.equal(rng.state, 3438003404);
});
