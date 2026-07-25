import assert from "node:assert/strict";
import test from "node:test";

import {
  getMoveRoleEntry,
  getMoveRoleScore,
  loadMoveRoleCatalog,
} from "../lib/ai-api-bridge/move-role-catalog.mjs";
import {
  showdownRequestToAiBattleObservation,
  simpleStateToAiBattleObservation,
  toAiBattleObservation,
  toAiApiObservationDraft,
} from "../lib/ai-api-bridge/observation-adapter.mjs";
import {
  createAiMoveTrace,
  createAiSwitchTrace,
  scoreAiDynamaxCandidate,
  scoreAiSwitchCandidate,
  moveRoleValue,
  scoreAiMoveCandidate,
  selectAiMoveCandidate,
} from "../lib/common-battle-ai.mjs";

test("loads shared AI move role catalog through the web bridge", async () => {
  const catalog = await loadMoveRoleCatalog();
  assert.ok(getMoveRoleEntry(catalog, "Stealth Rock").tags.includes("HAZARD_SET"));
  assert.ok(getMoveRoleScore(catalog, "Swords Dance", "setupSweeper") >= 4);
});

test("converts web battle state into an ai-api-like observation draft", () => {
  const observation = toAiApiObservationDraft({
    battleId: "demo",
    seed: "seed-1",
    format: "singles",
    engine: "cobbleverse",
    side: "player",
    turn: 3,
    activePokemon: {
      species: "Porygon2",
      hp: 120,
      maxHp: 192,
      types: ["Normal"],
      boosts: { spa: 1 },
      item: "eviolite",
      ability: "download",
    },
    opponentPokemon: {
      species: "Mawile",
      hp: 88,
      maxHp: 140,
      types: ["Steel", "Fairy"],
    },
    candidates: [{ type: "move", moveId: "triattack", name: "트라이어택" }],
  });

  assert.equal(observation.source, "web-lab");
  assert.equal(observation.activePokemon.item, "eviolite");
  assert.equal(observation.candidates[0].id, "triattack");
  assert.equal(observation.battleType.mode, "single");
  assert.equal(observation.legalActions[0].id, "triattack");
});

test("builds a common AI battle observation with public legal actions", () => {
  const observation = toAiBattleObservation({
    battleId: "eve-1",
    format: "doubles",
    engine: "showdown",
    side: "p2",
    turn: 7,
    activePokemon: [{ species: "Raichu", hp: 80, maxHp: 120 }],
    benchPokemon: [{ species: "Snorlax", hp: 210, maxHp: 260 }],
    opponentActivePokemon: [{ species: "Gyarados", hp: 130, maxHp: 170 }],
    opponentBenchKnownInfo: [{ species: "Mawile" }],
    legalActions: [
      { type: "move", moveId: "thunderbolt", name: "Thunderbolt", slot: 1 },
      { type: "switch", switchId: "snorlax", name: "Snorlax", slot: 2 },
    ],
    revealedInfo: {
      opponentSpecies: ["Gyarados", "Mawile"],
      opponentMoves: { gyarados: ["dragondance"] },
    },
  });

  assert.equal(observation.battleType.mode, "double");
  assert.equal(observation.battleType.activeSlotsPerSide, 2);
  assert.equal(observation.activePokemon[0].name, "Raichu");
  assert.equal(observation.legalActions[0].type, "move");
  assert.equal(observation.legalActions[1].type, "switch");
  assert.deepEqual(observation.revealedInfo.opponentSpecies, ["Gyarados", "Mawile"]);
});

test("records normalized AI action candidates with decision reasons", () => {
  const trace = createAiMoveTrace({
    turn: 1,
    side: 0,
    sideName: "AI",
    species: "Raichu",
    difficulty: "expert",
    strategy: "aggressive",
    selected: { slot: 1 },
    candidates: [
      {
        slot: 1,
        id: "thunderbolt",
        name: "Thunderbolt",
        category: "Special",
        power: 90,
        accuracy: 100,
        priority: 0,
        expectedDamage: 96,
        koChance: "possible",
      },
      {
        slot: 2,
        id: "nuzzle",
        name: "Nuzzle",
        category: "Physical",
        power: 20,
        accuracy: 100,
        priority: 0,
      },
    ],
  });

  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.equal(selected.type, "move");
  assert.equal(selected.legal, true);
  assert.equal(selected.expectedDamage.value, 96);
  assert.ok(selected.reasons.some((reason) => reason.code === "damage.expected"));
  assert.ok(selected.reasons.some((reason) => reason.code === "ko.possible"));
});

test("weights move role classification according to AI strategy", () => {
  const swordsDance = {
    slot: 1,
    id: "swordsdance",
    name: "Swords Dance",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
  };
  const tackle = {
    slot: 2,
    id: "tackle",
    name: "Tackle",
    category: "Physical",
    power: 20,
    accuracy: 100,
    pp: 35,
  };
  const stealthRock = {
    slot: 3,
    id: "stealthrock",
    name: "Stealth Rock",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
  };

  assert.ok(moveRoleValue(swordsDance, "setup") > moveRoleValue(tackle, "setup"));
  assert.ok(moveRoleValue(stealthRock, "hazard") > moveRoleValue(swordsDance, "hazard"));
  assert.ok(
    scoreAiMoveCandidate(swordsDance, "expert", "setup") >
      scoreAiMoveCandidate(tackle, "expert", "setup"),
  );
  assert.equal(
    selectAiMoveCandidate([tackle, swordsDance], {
      difficulty: "expert",
      strategy: "setup",
    }).id,
    "swordsdance",
  );

  const trace = createAiMoveTrace({
    turn: 1,
    side: 0,
    sideName: "AI",
    species: "Scizor",
    difficulty: "expert",
    strategy: "setup",
    selected: { slot: 1 },
    candidates: [swordsDance, tackle],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(selected.roleValue > 0);
  assert.ok(selected.reasons.some((reason) => reason.code === "role.strategy_fit"));
});

test("applies RunAndBun-inspired setup and hazard scoring rules", () => {
  const haze = {
    slot: 1,
    id: "haze",
    name: "Haze",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 30,
    setupThreatTier: "tier_3",
  };
  const recover = {
    slot: 2,
    id: "recover",
    name: "Recover",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 10,
    setupThreatTier: "tier_3",
  };
  const stealthRock = {
    slot: 3,
    id: "stealthrock",
    name: "Stealth Rock",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
    actsBeforeOpponent: true,
    livingOpponents: 5,
  };

  assert.ok(
    scoreAiMoveCandidate(haze, "expert", "ace_check") >
      scoreAiMoveCandidate(recover, "expert", "ace_check"),
  );
  assert.ok(
    scoreAiMoveCandidate(stealthRock, "expert", "hazard") >
      scoreAiMoveCandidate(recover, "expert", "hazard"),
  );

  const selected = selectAiMoveCandidate([recover, haze], {
    difficulty: "expert",
    strategy: "ace_check",
  });
  assert.equal(selected.id, "haze");

  const trace = createAiMoveTrace({
    turn: 4,
    side: 1,
    sideName: "AI",
    species: "Toxapex",
    difficulty: "expert",
    strategy: "ace_check",
    selected: { slot: 1 },
    candidates: [haze, recover],
  });
  const chosen = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(
    chosen.reasons.some((reason) => reason.code === "rule.setup_disruption.boost_reset"),
  );
});

test("applies RunAndBun-inspired switch matchup scoring rules", () => {
  const boostedCurrentMonSwitch = {
    slot: 2,
    name: "Slow wall",
    hpPercent: 0.8,
    expectedDamage: 10,
    currentIncomingDamageRatio: 0.3,
    targetIncomingDamageRatio: 0.25,
    currentOutgoingDamageRatio: 0.55,
    targetOutgoingDamageRatio: 0.3,
    currentPositiveBoosts: 2,
  };
  const safeCounter = {
    slot: 3,
    name: "Priority check",
    hpPercent: 0.8,
    expectedDamage: 10,
    currentIncomingDamageRatio: 0.9,
    targetIncomingDamageRatio: 0.4,
    currentOutgoingDamageRatio: 0.2,
    targetOutgoingDamageRatio: 0.95,
    speedAdvantage: true,
    currentStatus: "par",
  };

  assert.ok(
    scoreAiSwitchCandidate(safeCounter) >
      scoreAiSwitchCandidate(boostedCurrentMonSwitch),
  );

  const trace = createAiSwitchTrace({
    turn: 6,
    side: 1,
    sideName: "AI",
    species: "Raichu",
    selected: { slot: 3 },
    candidates: [boostedCurrentMonSwitch, safeCounter],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(
    selected.reasons.some((reason) => reason.code === "rule.switch.defensive_improvement"),
  );
  assert.ok(
    selected.reasons.some((reason) => reason.code === "rule.switch.safe_counter_ko"),
  );
});

test("applies RunAndBun-inspired recovery, pivot, and immediate KO rules", () => {
  const finishingMove = {
    slot: 1,
    id: "quickattack",
    name: "Quick Attack",
    category: "Physical",
    power: 40,
    accuracy: 100,
    priority: 1,
    expectedDamage: 40,
    koChance: "guaranteed",
    safeImmediateKoAvailable: true,
  };
  const recover = {
    slot: 2,
    id: "recover",
    name: "Recover",
    category: "Status",
    power: 0,
    accuracy: true,
    hpPercent: 0.8,
    safeImmediateKoAvailable: true,
  };
  const partingShot = {
    slot: 3,
    id: "partingshot",
    name: "Parting Shot",
    category: "Status",
    power: 0,
    accuracy: 100,
    actsBeforeOpponent: true,
    hasLivingBench: true,
  };

  assert.ok(
    scoreAiMoveCandidate(finishingMove, "expert", "aggressive") >
      scoreAiMoveCandidate(recover, "expert", "defensive"),
  );
  assert.ok(scoreAiMoveCandidate(partingShot, "expert", "tempo") > 0);

  const trace = createAiMoveTrace({
    turn: 5,
    side: 1,
    sideName: "AI",
    species: "Incineroar",
    difficulty: "expert",
    strategy: "tempo",
    selected: { slot: 3 },
    candidates: [finishingMove, recover, partingShot],
  });
  const pivot = trace.candidates.find((candidate) => candidate.slot === 3);
  const recovery = trace.candidates.find((candidate) => candidate.slot === 2);
  assert.ok(pivot.reasons.some((reason) => reason.code === "rule.pivot.safe_pivot"));
  assert.ok(
    recovery.reasons.some((reason) => reason.code === "rule.immediate_ko_dominance"),
  );
});

test("applies RunAndBun-inspired lethal, repeated, and Dynamax switch penalties", () => {
  const riskySwitch = {
    slot: 2,
    name: "Glass counter",
    hpPercent: 0.45,
    expectedDamage: 20,
    targetIncomingDamageRatio: 0.7,
    switchedLastTurn: true,
    immediateReturn: true,
    dynamaxActive: true,
    dynamaxRemainingTurns: 2,
    safeImmediateKoAvailable: true,
  };
  const stableSwitch = {
    slot: 3,
    name: "Stable wall",
    hpPercent: 0.9,
    expectedDamage: 10,
    targetIncomingDamageRatio: 0.2,
  };

  assert.ok(scoreAiSwitchCandidate(stableSwitch) > scoreAiSwitchCandidate(riskySwitch));

  const trace = createAiSwitchTrace({
    turn: 7,
    side: 1,
    sideName: "AI",
    species: "Dragonite",
    selected: { slot: 2 },
    candidates: [riskySwitch, stableSwitch],
  });
  const risky = trace.candidates.find((candidate) => candidate.slot === 2);
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.lethal_switch_in"));
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.repeated_switch"));
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.dynamax_turn_cost"));
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.guaranteed_ko_penalty"));
});

test("scores Dynamax activation against setup opportunity cost", () => {
  const setupMove = {
    slot: 1,
    id: "swordsdance",
    name: "Swords Dance",
    category: "Status",
    hpPercent: 0.9,
    incomingDamageRatio: 0.12,
  };
  const normalAttack = {
    slot: 2,
    id: "slash",
    name: "Slash",
    type: "Normal",
    category: "Physical",
    power: 70,
    expectedDamage: 35,
  };
  const delayed = scoreAiDynamaxCandidate({
    active: { canDynamax: true, hpPercent: 0.9, incomingDamageRatio: 0.12 },
    configured: { gimmicks: { dynamax: true } },
    selectedMove: setupMove,
    moveCandidates: [setupMove, normalAttack],
    forceDynamax: true,
  });
  assert.ok(delayed.score < 12);
  assert.ok(
    delayed.reasons.some((reason) => reason.code === "gimmick.dynamax.delay_for_setup"),
  );

  const withMaxKnuckle = scoreAiDynamaxCandidate({
    active: { canDynamax: true, hpPercent: 0.9, incomingDamageRatio: 0.12 },
    configured: { gimmicks: { dynamax: true } },
    selectedMove: normalAttack,
    moveCandidates: [
      setupMove,
      { ...normalAttack, id: "brickbreak", type: "Fighting" },
    ],
    forceDynamax: true,
  });
  assert.ok(withMaxKnuckle.score >= 12);
  assert.ok(
    withMaxKnuckle.reasons.some((reason) => reason.code === "gimmick.dynamax.max_knuckle"),
  );
});

test("records normalized AI switch candidates with decision reasons", () => {
  const trace = createAiSwitchTrace({
    turn: 2,
    side: 1,
    sideName: "AI",
    species: "Raichu",
    selected: { slot: 3 },
    candidates: [
      { slot: 2, name: "Porygon2", hpPercent: 0.4, matchupValue: 12 },
      { slot: 3, name: "Snorlax", hpPercent: 0.9, expectedDamage: 30 },
    ],
  });

  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.equal(selected.type, "switch");
  assert.equal(selected.action.type, "switch");
  assert.ok(selected.reasons.some((reason) => reason.code === "switch.hp_remaining"));
});

test("converts a Showdown request into the common AI observation model", () => {
  const observation = showdownRequestToAiBattleObservation({
    battleId: "showdown-request",
    seed: 42,
    side: "p1",
    turn: 2,
    request: {
      active: [
        {
          moves: [
            { id: "thunderbolt", move: "Thunderbolt", pp: 15, target: "normal" },
            { id: "quickattack", move: "Quick Attack", pp: 30, target: "normal" },
          ],
        },
      ],
      side: {
        pokemon: [
          {
            ident: "p1a: Pikachu",
            details: "Pikachu, L50, M",
            condition: "88/120 par",
            active: true,
            moves: ["thunderbolt", "quickattack"],
          },
          {
            ident: "p1: Snorlax",
            details: "Snorlax, L50, M",
            condition: "220/260",
            active: false,
          },
        ],
      },
    },
  });

  assert.equal(observation.battleType.engine, "showdown");
  assert.equal(observation.activePokemon[0].name, "Pikachu");
  assert.equal(observation.activePokemon[0].status, "par");
  assert.equal(observation.benchPokemon[0].name, "Snorlax");
  assert.deepEqual(
    observation.legalActions.map((action) => action.id),
    ["thunderbolt", "quickattack"],
  );
});

test("converts a Cobbleverse simple state into the common AI observation model", () => {
  const observation = simpleStateToAiBattleObservation({
    sideIndex: 0,
    state: {
      seed: 7,
      turn: 4,
      weather: "RainDance",
      sides: [
        {
          name: "Red",
          active: 0,
          sideConditions: { reflect: { turns: 3 } },
          team: [
            {
              id: "pikachu",
              name: "Pikachu",
              hp: 90,
              stats: { hp: 120 },
              types: ["Electric"],
              moves: [
                { id: "thunderbolt", name: "Thunderbolt", pp: 15, priority: 0 },
              ],
            },
            {
              id: "snorlax",
              name: "Snorlax",
              hp: 220,
              stats: { hp: 260 },
              types: ["Normal"],
              moves: [],
            },
          ],
        },
        {
          name: "Blue",
          active: 0,
          sideConditions: {},
          team: [
            {
              id: "squirtle",
              name: "Squirtle",
              hp: 80,
              stats: { hp: 110 },
              types: ["Water"],
              moves: [],
            },
          ],
        },
      ],
      events: [{ turn: 4, type: "turn" }],
    },
  });

  assert.equal(observation.battleType.engine, "cobbleverse-simple");
  assert.equal(observation.side, "p1");
  assert.equal(observation.weather.id, "RainDance");
  assert.equal(observation.activePokemon[0].active, true);
  assert.equal(observation.opponentActivePokemon[0].name, "Squirtle");
  assert.deepEqual(
    observation.legalActions.map((action) => action.type),
    ["move", "switch"],
  );
});
