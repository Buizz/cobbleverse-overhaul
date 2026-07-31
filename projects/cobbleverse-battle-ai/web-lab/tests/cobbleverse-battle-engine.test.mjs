import assert from "node:assert/strict";
import test from "node:test";

import {
  applySimpleCheaterKnowledge,
  automaticSwitchCandidates,
  calculateDamageRange,
  calculateMovePreview,
  chooseSimpleAiDecision,
  chooseSimpleAiCommand,
  createSimpleAiDecisionTrace,
  createSimpleBattle,
  estimateSimpleBattleWinProbability,
  isSimpleAbilitySupported,
  resolveSimpleTurn,
  resolveSimpleCheaterDecision,
  runSimpleBattle,
  simulateSimpleTurn,
  typeMultiplier,
} from "../lib/cobbleverse-battle-engine.mjs";

test("reports native ability support for random matchup filtering", () => {
  assert.equal(isSimpleAbilitySupported("pressure"), true);
  assert.equal(isSimpleAbilitySupported("cobblemon:pressure"), true);
  assert.equal(isSimpleAbilitySupported("earlybird"), false);
  assert.equal(isSimpleAbilitySupported(""), true);
});

test("uses trainer battle items and consumes both quantity and use limit", () => {
  let state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Item User",
          bag: [
            { item: "cobblemon:full_restore", quantity: 1 },
            { item: "cobblemon:full_heal", quantity: 1 },
          ],
          maxItemUses: 1,
          team: [pokemon({ name: "Item User" })],
        },
        { name: "Opponent", team: [pokemon({ name: "Opponent" })] },
      ],
    }),
  );
  state.sides[0].team[0].hp = 20;
  state.sides[0].team[0].status = "brn";
  state = resolveSimpleTurn(state, [
    { item: "cobblemon:full_restore" },
    { move: 1 },
  ]);

  assert.equal(state.sides[0].team[0].status, "");
  assert.equal(state.sides[0].bag[0].quantity, 0);
  assert.equal(state.sides[0].itemUsesRemaining, 0);
  assert.ok(state.events.some((event) => event.type === "trainer_item"));
  assert.ok(state.events.some((event) => event.type === "status_cured"));
  const rejected = resolveSimpleTurn(state, [
    { item: "cobblemon:full_heal" },
    { move: 1 },
  ]);
  assert.equal(rejected.sides[0].bag[1].quantity, 1);
  assert.ok(rejected.events.some((event) => event.type === "item_failed"));
});

test("offers a useful trainer item as an explainable AI candidate", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "AI",
          bag: [{ item: "cobblemon:full_restore", quantity: 1 }],
          maxItemUses: 1,
          team: [pokemon({ name: "AI" })],
        },
        { name: "Opponent", team: [pokemon({ name: "Opponent" })] },
      ],
    }),
  );
  state.sides[0].team[0].hp = 25;
  state.sides[0].team[0].status = "tox";
  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const trace = createSimpleAiDecisionTrace(
    state,
    0,
    decision,
    "expert",
    "balanced",
  );

  assert.ok(decision.itemCandidates.some((candidate) => candidate.id === "fullrestore"));
  assert.ok(trace.candidates.some((candidate) => candidate.type === "item"));
});

test("AI values healing a Pokemon that can still finish future matchups", () => {
  const buildState = ({ attack, speed, power }) => {
    const state = createSimpleBattle(
      setup({
        sides: [
          {
            name: "Item User",
            bag: [{ item: "cobblemon:full_restore", quantity: 1 }],
            maxItemUses: 1,
            team: [
              pokemon({
                name: "Recoverable",
                stats: {
                  ...pokemon().stats,
                  hp: 320,
                  attack,
                  speed,
                },
                moves: [
                  {
                    id: "future-hit",
                    name: "Future Hit",
                    type: "Normal",
                    category: "Physical",
                    power,
                    accuracy: 100,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "Opponent",
            team: [
              pokemon({
                name: "Current Opponent",
                stats: {
                  ...pokemon().stats,
                  hp: 180,
                  attack: 85,
                  defence: 90,
                  speed: 70,
                },
              }),
              pokemon({
                name: "Future Target",
                stats: {
                  ...pokemon().stats,
                  hp: 150,
                  attack: 95,
                  defence: 75,
                  speed: 80,
                },
              }),
            ],
          },
        ],
      }),
    );
    state.sides[0].team[0].hp = 30;
    return state;
  };
  const finisherDecision = chooseSimpleAiDecision(
    buildState({ attack: 220, speed: 140, power: 120 }),
    0,
    "expert",
    "balanced",
  );
  const supportDecision = chooseSimpleAiDecision(
    buildState({ attack: 55, speed: 45, power: 40 }),
    0,
    "expert",
    "balanced",
  );
  const finisherItem = finisherDecision.itemCandidates.find(
    (candidate) => candidate.id === "fullrestore",
  );
  const supportItem = supportDecision.itemCandidates.find(
    (candidate) => candidate.id === "fullrestore",
  );

  assert.ok(finisherItem.futureSafeKoTargets.includes("Future Target"));
  assert.ok(finisherItem.futureRoleValue > supportItem.futureRoleValue);
  assert.ok(finisherItem.score > supportItem.score);
  assert.ok(
    finisherItem.reasons.some(
      (reason) => reason.component === "futureKoRole",
    ),
  );
});

test("AI accounts for residual damage after using a healing item", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Item User",
          bag: [{ item: "cobblemon:full_restore", quantity: 1 }],
          maxItemUses: 1,
          team: [
            pokemon({
              name: "Salted",
              stats: { ...pokemon().stats, hp: 320 },
            }),
          ],
        },
        {
          name: "Opponent",
          team: [
            pokemon({
              name: "Passive Opponent",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].hp = 90;
  state.sides[0].team[0].volatiles.saltcure = { id: "saltcure" };

  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const item = decision.itemCandidates.find(
    (candidate) => candidate.id === "fullrestore",
  );

  assert.equal(item.residualDamage, 40);
  assert.equal(item.postTurnHp, 280);
  assert.equal(item.survivesEndOfTurn, true);
  assert.ok(
    item.reasons.some(
      (reason) => reason.component === "residualDamage",
    ),
  );
});

test("AI sacrifices its lowest-value member only when healing the ace raises win probability", () => {
  const weakMove = {
    id: "weakhit",
    name: "Weak Hit",
    type: "Normal",
    category: "Physical",
    power: 35,
    accuracy: 100,
    pp: 30,
  };
  const aceMove = {
    id: "acehit",
    name: "Ace Hit",
    type: "Fighting",
    category: "Physical",
    power: 140,
    accuracy: 100,
    pp: 10,
  };
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Recovery Plan",
          bag: [{ item: "cobblemon:full_restore", quantity: 1 }],
          maxItemUses: 1,
          team: [
            pokemon({
              name: "Current Support",
              aiRole: "notace",
              stats: { ...pokemon().stats, hp: 240, defence: 140 },
              moves: [weakMove],
            }),
            pokemon({
              name: "Low Value Sacrifice",
              aiRole: "notace",
              stats: { ...pokemon().stats, hp: 80, defence: 80 },
              moves: [weakMove],
            }),
            pokemon({
              name: "Damaged Ace",
              aiRole: "ace",
              stats: {
                ...pokemon().stats,
                hp: 320,
                attack: 260,
                speed: 150,
              },
              moves: [aceMove],
            }),
          ],
        },
        {
          name: "Targets",
          team: [
            pokemon({
              name: "Target One",
              types: ["Normal"],
              stats: {
                ...pokemon().stats,
                hp: 220,
                attack: 110,
                defence: 90,
                speed: 80,
              },
              moves: [weakMove],
            }),
            pokemon({
              name: "Target Two",
              types: ["Normal"],
              stats: { ...pokemon().stats, hp: 220, defence: 90 },
              moves: [weakMove],
            }),
            pokemon({
              name: "Target Three",
              types: ["Normal"],
              stats: { ...pokemon().stats, hp: 220, defence: 90 },
              moves: [weakMove],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[2].hp = 20;

  const switchDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "reckless_ace",
  );
  const sacrifice = switchDecision.switchCandidates.find(
    (candidate) => candidate.name.includes("Low Value Sacrifice"),
  );

  assert.equal(
    sacrifice.aceRecoveryPlanEligible,
    true,
    JSON.stringify(sacrifice.aceRecoveryPlan, null, 2),
  );
  assert.equal(switchDecision.command.switch, 2);
  assert.ok(sacrifice.aceRecoveryPlan.winProbabilityDelta >= 0.025);

  const switched = resolveSimpleTurn(state, [
    switchDecision.command,
    { move: 1 },
  ]);
  assert.equal(switched.sides[0].active, 1);
  assert.ok(switched.sides[0].team[1].hp > 0);

  const itemDecision = chooseSimpleAiDecision(
    switched,
    0,
    "expert",
    "reckless_ace",
  );
  assert.equal(itemDecision.command.item, "fullrestore");
  assert.equal(itemDecision.command.itemTarget, 3);

  const healed = resolveSimpleTurn(switched, [
    itemDecision.command,
    { move: 1 },
  ]);
  assert.equal(healed.sides[0].team[2].hp, 320);
  assert.ok(
    healed.events.some(
      (event) =>
        event.type === "trainer_item" &&
        event.pokemon === "Damaged Ace" &&
        event.targetSlot === 3,
    ),
  );
});

test("Baton Pass support sets up safely, then passes boosts to the sole ace", () => {
  const agility = {
    id: "agility",
    name: "Agility",
    type: "Psychic",
    category: "Status",
    accuracy: true,
    pp: 30,
    selfBoosts: { speed: 2 },
  };
  const batonPass = {
    id: "batonpass",
    name: "Baton Pass",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    selfSwitch: true,
  };
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Baton Team",
          team: [
            pokemon({
              name: "Passer",
              aiRole: "notace",
              stats: {
                ...pokemon().stats,
                hp: 240,
                defence: 150,
                speed: 140,
              },
              moves: [agility, batonPass],
            }),
            pokemon({
              name: "Sole Ace",
              aiRole: "ace",
              stats: {
                ...pokemon().stats,
                hp: 300,
                attack: 230,
                speed: 70,
              },
              moves: [
                {
                  id: "closecombat",
                  name: "Close Combat",
                  type: "Fighting",
                  category: "Physical",
                  power: 120,
                  accuracy: 100,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "Opponent",
          team: [
            pokemon({
              name: "Passive Target",
              stats: { ...pokemon().stats, attack: 35, speed: 60 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
            pokemon({
              name: "Future Target",
              types: ["Normal"],
              stats: { ...pokemon().stats, hp: 220, defence: 90 },
            }),
          ],
        },
      ],
    }),
  );

  const setupDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  assert.equal(
    setupDecision.command.move,
    1,
    JSON.stringify(
      {
        command: setupDecision.command,
        moves: setupDecision.moveCandidates.map((candidate) => ({
          id: candidate.id,
          score: candidate.score,
          batonPassTransferValue: candidate.batonPassTransferValue,
          setupFollowupSurvivalProbability:
            candidate.setupFollowupSurvivalProbability,
        })),
        switches: setupDecision.switchCandidates.map((candidate) => ({
          name: candidate.name,
          score: candidate.score,
        })),
      },
      null,
      2,
    ),
  );
  const setupTrace = createSimpleAiDecisionTrace(
    state,
    0,
    setupDecision,
    "expert",
    "balanced",
  );
  assert.ok(
    setupTrace.candidates
      .find((candidate) => candidate.id === "agility")
      .reasons.some(
        (reason) => reason.code === "rule.baton_pass.setup_for_ace",
      ),
  );

  state.sides[0].team[0].boosts.speed = 2;
  state.sides[0].team[0].volatiles.substitute = {
    id: "substitute",
    hp: 45,
  };
  state.sides[0].team[0].hp = 70;
  state.sides[1].team[0].stats.attack = 200;
  Object.assign(state.sides[1].team[0].moves[0], {
    id: "stronghit",
    name: "Strong Hit",
    category: "Physical",
    power: 100,
    accuracy: 100,
    pp: 10,
  });
  const passDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  assert.equal(
    passDecision.command.move,
    2,
    JSON.stringify(
      passDecision.moveCandidates.map((candidate) => ({
        id: candidate.id,
        score: candidate.score,
        boosts: candidate.batonPassCurrentBoostTotal,
        transfer: candidate.batonPassTransferValue,
        survival: candidate.setupFollowupSurvivalProbability,
        beforeKo: candidate.opponentKnockoutBeforeActionProbability,
      })),
      null,
      2,
    ),
  );

  const passed = resolveSimpleTurn(state, [
    passDecision.command,
    { switch: 2 },
  ]);
  assert.equal(passed.sides[0].active, 1);
  assert.equal(passed.sides[0].team[1].boosts.speed, 2);
  assert.equal(passed.sides[0].team[1].volatiles.substitute.hp, 45);
  assert.equal(
    passed.events.filter(
      (event) =>
        event.type === "switch" &&
        event.side === 0 &&
        event.source === "Baton Pass",
    ).length,
    1,
  );
  assert.ok(
    passed.events.some(
      (event) =>
        event.type === "boosts_passed" &&
        event.pokemon === "Sole Ace" &&
        event.boosts?.spe === 2,
    ),
  );
});

test("Baton Pass support takes safe extra boosts before transferring to its ace", () => {
  const tailGlow = {
    id: "tailglow",
    name: "Tail Glow",
    type: "Bug",
    category: "Status",
    accuracy: true,
    pp: 20,
    selfBoosts: { specialAttack: 3 },
  };
  const acidArmor = {
    id: "acidarmor",
    name: "Acid Armor",
    type: "Poison",
    category: "Status",
    accuracy: true,
    pp: 20,
    selfBoosts: { defence: 2 },
  };
  const batonPass = {
    id: "batonpass",
    name: "Baton Pass",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    selfSwitch: true,
  };
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Baton Team",
          team: [
            pokemon({
              name: "Safe Passer",
              aiRole: "notace",
              stats: {
                ...pokemon().stats,
                hp: 340,
                defence: 120,
                specialDefence: 120,
                speed: 110,
              },
              moves: [tailGlow, acidArmor, batonPass],
            }),
            pokemon({
              name: "Special Ace",
              aiRole: "ace",
              stats: {
                ...pokemon().stats,
                hp: 320,
                specialAttack: 210,
                speed: 100,
              },
              moves: [
                {
                  id: "psychic",
                  name: "Psychic",
                  type: "Psychic",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Passive Team",
          team: [
            pokemon({
              name: "Passive Wall",
              stats: {
                ...pokemon().stats,
                hp: 420,
                attack: 70,
                speed: 40,
              },
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
            pokemon({
              name: "Target Two",
              stats: { ...pokemon().stats, hp: 220, specialDefence: 80 },
            }),
            pokemon({
              name: "Target Three",
              stats: { ...pokemon().stats, hp: 220, specialDefence: 80 },
            }),
          ],
        },
      ],
    }),
  );
  const passer = state.sides[0].team[0];

  passer.boosts.specialAttack = 3;
  const finishOffence = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "reckless_ace",
  );
  assert.equal(finishOffence.command.move, 1);

  passer.boosts.specialAttack = 6;
  const addDefence = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "reckless_ace",
  );
  assert.equal(addDefence.command.move, 2);

  passer.boosts.defence = 2;
  const transfer = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "reckless_ace",
  );
  assert.equal(
    transfer.command.move,
    3,
    JSON.stringify(
      transfer.moveCandidates.map((candidate) => ({
        id: candidate.id,
        score: candidate.score,
        sweepBoosts: candidate.batonPassCurrentSweepBoostTotal,
        defensiveBoosts: candidate.batonPassCurrentDefensiveBoostTotal,
        reasons: candidate.reasons
          ?.filter((reason) => reason.code.startsWith("rule.baton_pass"))
          .map((reason) => reason.code),
      })),
      null,
      2,
    ),
  );
});

test("AI switches a Baton Pass supporter into a weak matchup, especially for reckless ace", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Baton Team",
          team: [
            pokemon({
              id: "current-wall",
              name: "Current Wall",
              aiRole: "notace",
              stats: { ...pokemon().stats, hp: 300, attack: 55 },
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
            pokemon({
              id: "baton-support",
              name: "Baton Support",
              aiRole: "notace",
              stats: {
                ...pokemon().stats,
                hp: 320,
                defence: 180,
                specialDefence: 180,
                speed: 125,
              },
              moves: [
                {
                  id: "agility",
                  name: "Agility",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 30,
                  selfBoosts: { speed: 2 },
                },
                {
                  id: "batonpass",
                  name: "Baton Pass",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
            pokemon({
              id: "sole-ace",
              name: "Sole Ace",
              aiRole: "ace",
              stats: {
                ...pokemon().stats,
                hp: 300,
                attack: 220,
                speed: 65,
              },
              moves: [
                {
                  id: "closecombat",
                  name: "Close Combat",
                  type: "Fighting",
                  category: "Physical",
                  power: 120,
                  accuracy: 100,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "Weak Opponent",
          team: [
            pokemon({
              id: "weak-active",
              name: "Weak Active",
              stats: {
                ...pokemon().stats,
                hp: 260,
                attack: 45,
                speed: 70,
              },
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 30,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
            pokemon({
              id: "future-target",
              name: "Future Target",
              types: ["Normal"],
              stats: {
                ...pokemon().stats,
                hp: 280,
                defence: 110,
              },
            }),
          ],
        },
      ],
    }),
  );

  const balanced = automaticSwitchCandidates(
    state,
    0,
    [],
    "expert",
    "balanced",
  ).find((candidate) => candidate.id === "baton-support");
  const reckless = automaticSwitchCandidates(
    state,
    0,
    [],
    "expert",
    "reckless_ace",
  ).find((candidate) => candidate.id === "baton-support");

  assert.equal(
    balanced.targetBatonPassSupport,
    true,
    JSON.stringify(balanced, null, 2),
  );
  assert.equal(balanced.batonPassSetupOpportunity, true);
  assert.equal(balanced.batonPassTargetName, "Sole Ace");
  assert.ok(balanced.batonPassSafeSetupTurns >= 1);
  assert.ok(reckless.score > balanced.score);

  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "reckless_ace",
  );
  assert.equal(
    decision.command.switch,
    2,
    JSON.stringify(
      decision.switchCandidates.map((candidate) => ({
        id: candidate.id,
        score: candidate.score,
        baton: candidate.batonPassSetupOpportunity,
        transfer: candidate.batonPassTransferValue,
      })),
      null,
      2,
    ),
  );

  const dangerousState = structuredClone(state);
  dangerousState.sides[1].team[0].stats.attack = 260;
  dangerousState.sides[1].team[0].moves[0].power = 180;
  const unsafePasser = automaticSwitchCandidates(
    dangerousState,
    0,
    [],
    "expert",
    "reckless_ace",
  ).find((candidate) => candidate.id === "baton-support");
  assert.equal(unsafePasser.batonPassSetupOpportunity, false);
  assert.ok(unsafePasser.score < reckless.score);
});

test("cheater responds to the opponent's committed status move", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Bait",
          team: [
            pokemon({
              name: "Bait",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
                {
                  id: "hyperbeam",
                  name: "Hyper Beam",
                  type: "Normal",
                  category: "Special",
                  power: 150,
                  accuracy: 90,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "Cheater",
          team: [
            pokemon({
              name: "Cheater",
              moves: [
                {
                  id: "suckerpunch",
                  name: "Sucker Punch",
                  type: "Dark",
                  category: "Physical",
                  power: 70,
                  accuracy: 100,
                  priority: 1,
                  pp: 5,
                },
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const baseDecision = chooseSimpleAiDecision(
    state,
    1,
    "cheater",
    "balanced",
  );
  assert.equal(baseDecision.command.move, 1);

  const cheated = applySimpleCheaterKnowledge(
    state,
    1,
    baseDecision,
    { move: 1 },
    { strategy: "balanced" },
  );

  assert.equal(cheated.command.move, 2);
  assert.equal(cheated.diagnostics.cheatActivated, true);
  assert.equal(cheated.diagnostics.cheaterResponseChanged, true);
  assert.deepEqual(cheated.diagnostics.observedOpponentCommand, { move: 1 });
  assert.equal(cheated.diagnostics.policy, "cheater-exact-command-search");
  assert.equal(cheated.diagnostics.searchDepthLimit, 2);
  assert.equal(cheated.diagnostics.opponentCandidateCount, 1);
  assert.deepEqual(cheated.diagnostics.opponentDistribution, [
    {
      id: 'exact:{"move":1,"switch":0,"gimmick":""}',
      command: { move: 1 },
      probability: 1,
    },
  ]);
  assert.equal(cheated.diagnostics.cheaterCandidates, undefined);
  assert.ok(
    cheated.moveCandidates.every(
      (candidate) => !candidate.opponentThreateningMoveId,
    ),
  );
});

test("AI warns about Taunt and Encore while cheater avoids the committed disruption", () => {
  const setupMove = {
    id: "swordsdance",
    name: "Swords Dance",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 20,
    selfBoosts: { attack: 2 },
  };
  const attackMove = {
    id: "slash",
    name: "Slash",
    type: "Normal",
    category: "Physical",
    power: 70,
    accuracy: 100,
    pp: 20,
  };
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Planner",
          team: [
            pokemon({
              name: "Planner",
              stats: { ...pokemon().stats, attack: 140, speed: 80 },
              moves: [setupMove, attackMove],
            }),
          ],
        },
        {
          name: "Disruptor",
          team: [
            pokemon({
              name: "Disruptor",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "taunt",
                  name: "Taunt",
                  type: "Dark",
                  category: "Status",
                  accuracy: 100,
                  pp: 20,
                  volatileStatus: "taunt",
                },
                {
                  id: "encore",
                  name: "Encore",
                  type: "Normal",
                  category: "Status",
                  accuracy: 100,
                  pp: 5,
                  volatileStatus: "encore",
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].lastMove = { id: "swordsdance", name: "Swords Dance" };
  state.sides[0].team[0].lastMoveSucceeded = true;

  const expert = chooseSimpleAiDecision(state, 0, "expert", "balanced");
  const expertTrace = createSimpleAiDecisionTrace(
    state,
    0,
    expert,
    "expert",
    "balanced",
  );
  const setupCandidate = expertTrace.candidates.find(
    (candidate) => candidate.id === "swordsdance",
  );
  assert.ok(
    setupCandidate.reasons.some(
      (reason) => reason.code === "rule.status_disruption.taunt_risk",
    ),
  );
  assert.ok(
    setupCandidate.reasons.some(
      (reason) => reason.code === "rule.status_disruption.encore_risk",
    ),
  );

  for (const [slot, reasonCode] of [
    [1, "rule.status_disruption.exact_taunt"],
    [2, "rule.status_disruption.exact_encore"],
  ]) {
    const cheated = applySimpleCheaterKnowledge(
      state,
      0,
      expert,
      { move: slot },
      { strategy: "balanced" },
    );
    assert.equal(
      cheated.command.move,
      2,
      JSON.stringify(
        cheated.moveCandidates.map((candidate) => ({
          id: candidate.id,
          score: candidate.score,
          exactTauntRisk: candidate.exactTauntRisk,
          exactEncoreRisk: candidate.exactEncoreRisk,
        })),
        null,
        2,
      ),
    );
    const trace = createSimpleAiDecisionTrace(
      state,
      0,
      cheated,
      "cheater",
      "balanced",
    );
    assert.ok(
      trace.candidates
        .find((candidate) => candidate.id === "swordsdance")
        .reasons.some((reason) => reason.code === reasonCode),
    );
  }
});

test("AI reduces Encore concern when three-turn survival is safe, especially after defensive setup", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Setup Side",
          team: [
            pokemon({
              name: "Setup User",
              stats: {
                ...pokemon().stats,
                hp: 320,
                defence: 100,
                attack: 130,
                speed: 80,
              },
              moves: [
                {
                  id: "irondefense",
                  name: "Iron Defense",
                  type: "Steel",
                  category: "Status",
                  accuracy: true,
                  pp: 15,
                  selfBoosts: { defence: 2 },
                },
                {
                  id: "swordsdance",
                  name: "Swords Dance",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  selfBoosts: { attack: 2 },
                },
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: 100,
                  pp: 20,
                },
              ],
            }),
            pokemon({ name: "Bench Escape" }),
          ],
        },
        {
          name: "Encore Side",
          team: [
            pokemon({
              name: "Weak Disruptor",
              stats: { ...pokemon().stats, attack: 70, speed: 150 },
              moves: [
                {
                  id: "encore",
                  name: "Encore",
                  type: "Normal",
                  category: "Status",
                  accuracy: 100,
                  pp: 5,
                  volatileStatus: "encore",
                },
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const trace = createSimpleAiDecisionTrace(
    state,
    0,
    decision,
    "expert",
    "balanced",
  );
  const ironDefense = decision.moveCandidates.find(
    (candidate) => candidate.id === "irondefense",
  );
  const swordsDance = decision.moveCandidates.find(
    (candidate) => candidate.id === "swordsdance",
  );
  const ironEncoreReason = trace.candidates
    .find((candidate) => candidate.id === "irondefense")
    .reasons.find(
    (reason) => reason.code === "rule.status_disruption.encore_risk",
  );
  const swordsEncoreReason = trace.candidates
    .find((candidate) => candidate.id === "swordsdance")
    .reasons.find(
    (reason) => reason.code === "rule.status_disruption.encore_risk",
  );

  assert.equal(ironDefense.disruptionCanSurviveThreeTurns, true);
  assert.equal(ironDefense.disruptionDefensiveSetup, true);
  assert.ok(
    ironDefense.disruptionThreeTurnDamageRatio <
      swordsDance.disruptionThreeTurnDamageRatio,
  );
  assert.ok(ironEncoreReason.weight > swordsEncoreReason.weight);
  assert.match(ironEncoreReason.message, /방어형 랭크업 후 3턴 예상 피해/);

  const benchThreat = structuredClone(state.sides[1].team[0]);
  benchThreat.name = "Bench Breaker";
  benchThreat.stats.attack = 520;
  benchThreat.moves = [
    {
      id: "gigaimpact",
      name: "Giga Impact",
      type: "Normal",
      category: "Physical",
      power: 150,
      accuracy: 100,
      pp: 5,
    },
  ];
  state.sides[1].team.push(benchThreat);

  const threatenedDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const threatenedIronDefense = threatenedDecision.moveCandidates.find(
    (candidate) => candidate.id === "irondefense",
  );
  const threatenedTrace = createSimpleAiDecisionTrace(
    state,
    0,
    threatenedDecision,
    "expert",
    "balanced",
  );
  const threatenedEncoreReason = threatenedTrace.candidates
    .find((candidate) => candidate.id === "irondefense")
    .reasons.find(
      (reason) => reason.code === "rule.status_disruption.encore_risk",
    );

  assert.equal(threatenedIronDefense.disruptionBenchSwitchThreat, true);
  assert.equal(threatenedIronDefense.disruptionCanSurviveThreeTurns, false);
  assert.ok(threatenedEncoreReason.weight < ironEncoreReason.weight);
  assert.match(threatenedEncoreReason.message, /벤치 위협으로 교체/);
});

test("AI uses Taunt and Encore only when their three-turn control creates value", () => {
  const recover = {
    id: "recover",
    name: "Recover",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 10,
    heal: [1, 2],
  };
  const calmMind = {
    id: "calmmind",
    name: "Calm Mind",
    type: "Psychic",
    category: "Status",
    accuracy: true,
    pp: 20,
    selfBoosts: { specialAttack: 1, specialDefence: 1 },
  };
  const weakAttack = {
    id: "confusion",
    name: "Confusion",
    type: "Psychic",
    category: "Special",
    power: 50,
    accuracy: 100,
    pp: 25,
  };
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Controller",
          team: [
            pokemon({
              name: "Controller",
              stats: {
                ...pokemon().stats,
                hp: 360,
                specialDefence: 160,
                speed: 130,
              },
              moves: [
                {
                  id: "taunt",
                  name: "Taunt",
                  type: "Dark",
                  category: "Status",
                  accuracy: 100,
                  pp: 20,
                  volatileStatus: "taunt",
                },
                {
                  id: "encore",
                  name: "Encore",
                  type: "Normal",
                  category: "Status",
                  accuracy: 100,
                  pp: 5,
                  volatileStatus: "encore",
                },
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: 100,
                  pp: 20,
                },
              ],
            }),
          ],
        },
        {
          name: "Passive Setup",
          team: [
            pokemon({
              name: "Passive Setup",
              stats: {
                ...pokemon().stats,
                hp: 380,
                specialAttack: 75,
                speed: 70,
              },
              moves: [recover, calmMind, weakAttack],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].team[0].lastMove = {
    id: "recover",
    name: "Recover",
  };
  state.sides[1].team[0].lastMoveSucceeded = true;

  const controlDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const controlTrace = createSimpleAiDecisionTrace(
    state,
    0,
    controlDecision,
    "expert",
    "balanced",
  );
  const taunt = controlTrace.candidates.find(
    (candidate) => candidate.id === "taunt",
  );
  const encore = controlTrace.candidates.find(
    (candidate) => candidate.id === "encore",
  );
  const tauntLock = taunt.reasons.find(
    (reason) => reason.code === "rule.status_control.taunt_lock",
  );
  const encoreLock = encore.reasons.find(
    (reason) => reason.code === "rule.status_control.encore_status_lock",
  );

  assert.ok(tauntLock.weight > 0);
  assert.ok(encoreLock.weight > tauntLock.weight);
  assert.equal(controlDecision.command.move, 2);
  assert.equal(
    controlDecision.moveCandidates.find(
      (candidate) => candidate.id === "encore",
    ).statusControlCanSurviveThreeTurns,
    true,
  );

  const dangerousMove = {
    id: "hyperbeam",
    name: "Hyper Beam",
    type: "Normal",
    category: "Special",
    power: 150,
    accuracy: 100,
    pp: 5,
  };
  state.sides[1].team[0].moves = [dangerousMove];
  state.sides[1].team[0].stats.specialAttack = 520;
  state.sides[1].team[0].lastMove = {
    id: "hyperbeam",
    name: "Hyper Beam",
  };

  const dangerDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const dangerTrace = createSimpleAiDecisionTrace(
    state,
    0,
    dangerDecision,
    "expert",
    "balanced",
  );
  const dangerTaunt = dangerTrace.candidates.find(
    (candidate) => candidate.id === "taunt",
  );
  const dangerEncore = dangerTrace.candidates.find(
    (candidate) => candidate.id === "encore",
  );

  assert.ok(
    dangerTaunt.reasons.some(
      (reason) => reason.code === "rule.status_control.taunt_no_target",
    ),
  );
  assert.ok(
    dangerEncore.reasons.find(
      (reason) => reason.code === "rule.status_control.encore_attack_lock",
    ).weight < 0,
  );
  assert.ok(
    dangerEncore.reasons.some(
      (reason) => reason.code === "rule.status_control.encore_short_life",
    ),
  );
  assert.equal(dangerDecision.command.move, 3);
});

test("Upper Hand only succeeds against a pending damaging priority move", () => {
  const upperHand = {
    id: "upperhand",
    name: "Upper Hand",
    type: "Fighting",
    category: "Physical",
    power: 65,
    accuracy: 100,
    priority: 3,
    pp: 15,
  };
  const quickAttack = {
    id: "quickattack",
    name: "Quick Attack",
    type: "Normal",
    category: "Physical",
    power: 40,
    accuracy: 100,
    priority: 1,
    pp: 30,
  };
  const scenario = setup({
    sides: [
      {
        name: "Upper Hand",
        team: [pokemon({ name: "Upper Hand user", moves: [upperHand] })],
      },
      {
        name: "Target",
        team: [
          pokemon({ name: "Lead", moves: [quickAttack] }),
          pokemon({ name: "Replacement", moves: [quickAttack] }),
        ],
      },
    ],
  });

  const priorityResult = resolveSimpleTurn(
    createSimpleBattle(scenario),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(priorityResult.sides[1].team[0].hp < pokemon().stats.hp);
  assert.equal(priorityResult.sides[0].team[0].hp, pokemon().stats.hp);
  assert.ok(
    priorityResult.events.some(
      (event) =>
        event.type === "volatile_start" &&
        event.pokemon === "Lead" &&
        event.effect === "flinch",
    ),
  );

  const switchResult = resolveSimpleTurn(
    createSimpleBattle(scenario),
    [{ move: 1 }, { switch: 2 }],
  );
  assert.equal(switchResult.sides[1].team[1].hp, pokemon().stats.hp);
  assert.ok(
    switchResult.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.move === "Upper Hand",
    ),
  );
});

test("cheater scores Upper Hand from the opponent's committed priority move", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Target",
          team: [
            pokemon({
              moves: [
                {
                  id: "quickattack",
                  name: "Quick Attack",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  priority: 1,
                  pp: 30,
                },
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
        {
          name: "Cheater",
          team: [
            pokemon({
              moves: [
                {
                  id: "upperhand",
                  name: "Upper Hand",
                  type: "Fighting",
                  category: "Physical",
                  power: 65,
                  accuracy: 100,
                  priority: 3,
                  pp: 15,
                },
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  const againstPriority = applySimpleCheaterKnowledge(
    state,
    1,
    null,
    { move: 1 },
    { strategy: "balanced" },
  );
  const priorityCandidate = againstPriority.moveCandidates.find(
    (candidate) => candidate.id === "upperhand",
  );
  assert.equal(priorityCandidate.upperHandExactOutcome, "success");
  assert.equal(againstPriority.command.move, 1);

  const againstRegularMove = applySimpleCheaterKnowledge(
    state,
    1,
    null,
    { move: 2 },
    { strategy: "balanced" },
  );
  const failedCandidate = againstRegularMove.moveCandidates.find(
    (candidate) => candidate.id === "upperhand",
  );
  assert.equal(failedCandidate.upperHandExactOutcome, "failure");
  assert.equal(againstRegularMove.command.move, 2);
});

test("cheater activation obeys configured probability and remains seeded", () => {
  const state = createSimpleBattle(setup());
  const heuristicDecision = chooseSimpleAiDecision(
    state,
    1,
    "expert",
    "balanced",
  );
  const searchDecision = chooseSimpleAiDecision(
    state,
    1,
    "expert_search",
    "balanced",
  );
  const never = resolveSimpleCheaterDecision(
    state,
    1,
    { difficulty: "cheater", strategy: "balanced", cheatProbability: 0 },
    { move: 1 },
    heuristicDecision,
  );
  const always = resolveSimpleCheaterDecision(
    state,
    1,
    { difficulty: "cheater", strategy: "balanced", cheatProbability: 1 },
    { move: 1 },
    heuristicDecision,
  );
  const repeated = resolveSimpleCheaterDecision(
    state,
    1,
    { difficulty: "cheater", strategy: "balanced", cheatProbability: 1 },
    { move: 1 },
    heuristicDecision,
  );

  assert.equal(never.diagnostics.cheatActivated, false);
  assert.equal(never.diagnostics.policy, "expectimax-two-turn");
  assert.deepEqual(never.command, searchDecision.command);
  assert.equal(
    createSimpleAiDecisionTrace(
      state,
      1,
      never,
      "cheater",
      "balanced",
    ).selectionPolicy,
    "expectimax-two-turn",
  );
  assert.equal(always.diagnostics.cheatActivated, true);
  assert.equal(always.diagnostics.policy, "cheater-exact-command-search");
  assert.equal(always.diagnostics.cheatRoll, repeated.diagnostics.cheatRoll);
  assert.deepEqual(always.command, repeated.command);
});

test("EvE resolves cheater knowledge after both base commands are committed", () => {
  const battle = runSimpleBattle(
    setup({
      sides: [
        {
          name: "Status user",
          team: [
            pokemon({
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
        {
          name: "Cheater",
          team: [
            pokemon({
              moves: [
                {
                  id: "suckerpunch",
                  name: "Sucker Punch",
                  type: "Dark",
                  category: "Physical",
                  power: 70,
                  accuracy: 100,
                  priority: 1,
                  pp: 5,
                },
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
    {
      maxTurns: 1,
      aiProfiles: [
        { difficulty: "expert", strategy: "balanced" },
        {
          difficulty: "cheater",
          strategy: "balanced",
          cheatProbability: 1,
        },
      ],
    },
  );
  const trace = battle.aiTrace.find((entry) => entry.side === 1);

  assert.equal(trace.selectionPolicy, "cheater-exact-command");
  assert.equal(trace.diagnostics.cheatActivated, true);
  assert.deepEqual(trace.diagnostics.observedOpponentCommand, { move: 1 });
  assert.equal(trace.chosenAction, "Tackle");
});

function pokemon(overrides = {}) {
  return {
    id: "testmon",
    name: "Testmon",
    level: 50,
    types: ["Normal"],
    stats: {
      hp: 120,
      attack: 100,
      defence: 100,
      specialAttack: 100,
      specialDefence: 100,
      speed: 100,
    },
    moves: [
      {
        id: "tackle",
        name: "Tackle",
        type: "Normal",
        category: "Physical",
        power: 40,
        accuracy: 100,
        pp: 35,
      },
    ],
    ...overrides,
  };
}

function setup(overrides = {}) {
  return {
    seed: 1234,
    sides: [
      { name: "Player", team: [pokemon({ name: "PlayerMon" })] },
      { name: "AI", team: [pokemon({ name: "AiMon" })] },
    ],
    ...overrides,
  };
}

test("calculates the built-in type chart without Showdown", () => {
  assert.equal(typeMultiplier("Electric", ["Water", "Flying"]), 4);
  assert.equal(typeMultiplier("Electric", ["Ground"]), 0);
  assert.equal(typeMultiplier("Fire", ["Water"]), 0.5);
});

test("includes inactive bench matchups in the battle win estimate", () => {
  const statusMove = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
  };
  const opponent = pokemon({
    name: "Grass Threat",
    types: ["Grass"],
    stats: { ...pokemon().stats, hp: 180, specialAttack: 140, speed: 90 },
    moves: [
      {
        id: "energyball",
        name: "Energy Ball",
        type: "Grass",
        category: "Special",
        power: 90,
        accuracy: 100,
        pp: 10,
      },
    ],
  });
  const baseSide = pokemon({
    name: "Active Anchor",
    stats: { ...pokemon().stats, hp: 180, speed: 60 },
    moves: [statusMove],
  });
  const strongBench = pokemon({
    name: "Fire Counter",
    types: ["Fire"],
    stats: { ...pokemon().stats, specialAttack: 150, speed: 130 },
    moves: [
      {
        id: "flamethrower",
        name: "Flamethrower",
        type: "Fire",
        category: "Special",
        power: 100,
        accuracy: 100,
        pp: 15,
      },
    ],
  });
  const weakBench = pokemon({
    name: "Weak Reserve",
    types: ["Water"],
    stats: { ...pokemon().stats, specialAttack: 60, speed: 50 },
    moves: [
      {
        id: "watergun",
        name: "Water Gun",
        type: "Water",
        category: "Special",
        power: 40,
        accuracy: 100,
        pp: 25,
      },
    ],
  });
  const stateWithCounter = createSimpleBattle(
    setup({
      sides: [
        { name: "Counter Team", team: [baseSide, strongBench] },
        { name: "Threat Team", team: [opponent] },
      ],
    }),
  );
  const stateWithoutCounter = createSimpleBattle(
    setup({
      sides: [
        { name: "Weak Team", team: [baseSide, weakBench] },
        { name: "Threat Team", team: [opponent] },
      ],
    }),
  );
  const withCounter = estimateSimpleBattleWinProbability(stateWithCounter, 0);
  const withoutCounter = estimateSimpleBattleWinProbability(
    stateWithoutCounter,
    0,
  );

  assert.ok(withCounter.probability > withoutCounter.probability);
  assert.ok(withCounter.components.matchupCoverage > withoutCounter.components.matchupCoverage);
  assert.ok(withCounter.components.safeKoCoverage >= withoutCounter.components.safeKoCoverage);
});

test("reports a deterministic damage range with STAB", () => {
  const attacker = pokemon({
    types: ["Fire"],
    stats: { ...pokemon().stats, specialAttack: 120 },
  });
  const defender = pokemon({ types: ["Grass"] });
  const move = {
    type: "Fire",
    category: "Special",
    power: 80,
  };
  const range = calculateDamageRange(attacker, defender, move);

  assert.equal(range.stab, 1.5);
  assert.equal(range.effectiveness, 2);
  assert.ok(range.minimum > 0);
  assert.ok(range.maximum >= range.minimum);
});

test("normalizes Heat Stamp and uses weight-based power in battle and AI estimates", () => {
  const scenario = setup({
    sides: [
      {
        name: "Heavy",
        team: [
          pokemon({
            name: "Snorlax",
            types: ["Normal"],
            weightKg: 500,
            stats: { ...pokemon().stats, attack: 140 },
            moves: [
              {
                id: "heatstamp",
                name: "Heat Stamp",
                type: "Fire",
                category: "Physical",
                power: 0,
                accuracy: true,
                pp: 10,
                dynamicPower: true,
              },
            ],
          }),
        ],
      },
      {
        name: "Light",
        team: [
          pokemon({
            name: "Target",
            weightKg: 80,
            stats: { ...pokemon().stats, hp: 500 },
            moves: [
              {
                id: "splash",
                name: "Splash",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 40,
              },
            ],
          }),
        ],
      },
    ],
  });
  const created = createSimpleBattle(scenario);
  assert.equal(created.sides[0].team[0].moves[0].id, "heatcrash");
  const resolved = resolveSimpleTurn(created, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    resolved.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Heat Stamp" &&
        event.power === 120,
    ),
  );
  assert.ok(resolved.sides[1].team[0].hp < 500);

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const heatStamp = battle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "heatcrash");
  assert.ok(heatStamp.expectedDamage.value > 0);
});

test("does not offer weight-based moves against a Dynamaxed target", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Attacker",
          team: [
            pokemon({
              name: "Snorlax",
              types: ["Normal"],
              weightKg: 460,
              stats: { ...pokemon().stats, attack: 180 },
              moves: [
                {
                  id: "heatcrash",
                  name: "Heat Crash",
                  type: "Fire",
                  category: "Physical",
                  power: 0,
                  accuracy: true,
                  pp: 10,
                  dynamicPower: true,
                },
                {
                  id: "hammerarm",
                  name: "Hammer Arm",
                  type: "Fighting",
                  category: "Physical",
                  power: 100,
                  accuracy: 90,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Dynamaxed Target",
          team: [
            pokemon({
              name: "Target",
              weightKg: 80,
              stats: { ...pokemon().stats, hp: 300 },
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: true,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const attacker = state.sides[0].team[0];
  const defender = state.sides[1].team[0];
  defender.dynamaxTurns = 2;

  const preview = calculateMovePreview(attacker, defender, attacker.moves[0], {
    state,
    attackerSide: 0,
    defenderSide: 1,
  });
  assert.equal(preview.range.minimum, 0);
  assert.equal(preview.range.maximum, 0);
  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "balanced").move,
    2,
  );

  const failed = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    failed.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.move === "Heat Crash" &&
        event.reason ===
          "Weight-based moves fail against Dynamaxed targets.",
    ),
  );
});

test("resolves speed order, PP and damage using the same seed", () => {
  const battleSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "FastMon",
            stats: { ...pokemon().stats, speed: 150 },
          }),
        ],
      },
      { name: "AI", team: [pokemon({ name: "SlowMon" })] },
    ],
  });
  const first = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1 },
    { move: 1 },
  ]);
  const second = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1 },
    { move: 1 },
  ]);

  assert.deepEqual(second, first);
  assert.equal(
    first.events.find((event) => event.type === "move" && event.turn === 1)
      .pokemon,
    "FastMon",
  );
  assert.equal(first.sides[0].team[0].moves[0].pp, 34);
  assert.ok(first.sides[1].team[0].hp < 120);
});

test("simulates a turn without mutating the source or cloning heavy history", () => {
  const splash = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
  };
  let state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Left",
          team: [pokemon({ name: "Left", moves: [splash] })],
        },
        {
          name: "Right",
          team: [pokemon({ name: "Right", moves: [splash] })],
        },
      ],
    }),
  );
  for (let turn = 0; turn < 5; turn += 1) {
    state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  }
  state.aiTrace = [{ payload: "heavy trace" }];
  state.turnSnapshots = [{ payload: "heavy snapshot" }];
  const sourceBefore = structuredClone(state);
  const expected = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const simulated = simulateSimpleTurn(
    state,
    [{ move: 1 }, { move: 1 }],
    { historyTurns: 3 },
  );
  const withoutHistory = (value) => {
    const result = structuredClone(value);
    result.events = [];
    result.aiTrace = [];
    result.turnSnapshots = [];
    return result;
  };

  assert.deepEqual(state, sourceBefore);
  assert.deepEqual(withoutHistory(simulated), withoutHistory(expected));
  assert.deepEqual(simulated.aiTrace, []);
  assert.deepEqual(simulated.turnSnapshots, []);
  assert.ok(simulated.events.length < expected.events.length);
  assert.ok(
    simulated.events.every((event) => Number(event.turn ?? 0) >= 3),
  );
});

test("prevents Blood Moon from being used on consecutive turns", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Bloodmoon Ursaluna",
              stats: {
                ...pokemon().stats,
                hp: 1000,
                specialAttack: 140,
                speed: 120,
              },
              moves: [
                {
                  id: "bloodmoon",
                  name: "Blood Moon",
                  type: "Normal",
                  category: "Special",
                  power: 140,
                  accuracy: true,
                  pp: 5,
                },
                {
                  id: "earthpower",
                  name: "Earth Power",
                  type: "Ground",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Training Dummy",
              stats: { ...pokemon().stats, hp: 2000, speed: 40 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  const afterFirst = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.equal(afterFirst.sides[0].team[0].moves[0].pp, 4);
  assert.equal(
    chooseSimpleAiCommand(afterFirst, 0, "expert", "balanced").move,
    2,
  );

  const targetHp = afterFirst.sides[1].team[0].hp;
  const afterBlockedRepeat = resolveSimpleTurn(afterFirst, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(afterBlockedRepeat.sides[0].team[0].moves[0].pp, 4);
  assert.equal(afterBlockedRepeat.sides[1].team[0].hp, targetHp);
  assert.ok(
    afterBlockedRepeat.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move_failed" &&
        event.moveId === "bloodmoon" &&
        event.reason === "Blood Moon은(는) 연속해서 사용할 수 없습니다.",
    ),
  );

  const afterCooldown = resolveSimpleTurn(afterBlockedRepeat, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(afterCooldown.sides[0].team[0].moves[0].pp, 3);
  assert.ok(afterCooldown.sides[1].team[0].hp < targetHp);
});

test("two-turn search does not postpone Blood Moon forever", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Blood Moon AI",
          team: [
            pokemon({
              name: "Bloodmoon Ursaluna",
              types: ["Normal", "Ground"],
              stats: {
                ...pokemon().stats,
                hp: 500,
                specialAttack: 220,
                speed: 100,
              },
              moves: [
                {
                  id: "bloodmoon",
                  name: "Blood Moon",
                  type: "Normal",
                  category: "Special",
                  power: 140,
                  accuracy: 100,
                  pp: 5,
                },
                {
                  id: "earthpower",
                  name: "Earth Power",
                  type: "Ground",
                  category: "Special",
                  power: 130,
                  accuracy: 100,
                  pp: 10,
                  secondaries: [
                    {
                      chance: 10,
                      boosts: { specialDefence: -1 },
                    },
                  ],
                },
              ],
            }),
          ],
        },
        {
          name: "Target",
          team: [
            pokemon({
              name: "Durable Target",
              types: ["Normal"],
              stats: {
                ...pokemon().stats,
                hp: 240,
                specialDefence: 180,
                speed: 40,
              },
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert_search",
    "balanced",
  );

  assert.equal(
    decision.command.move,
    1,
    JSON.stringify(decision.diagnostics, null, 2),
  );
  assert.equal(decision.selectedMove.id, "bloodmoon");

  const afterBloodMoon = resolveSimpleTurn(state, [
    decision.command,
    { move: 1 },
  ]);
  const cooldownDecision = chooseSimpleAiDecision(
    afterBloodMoon,
    0,
    "expert_search",
    "balanced",
  );
  assert.equal(cooldownDecision.command.move, 2);

  const afterCooldown = resolveSimpleTurn(afterBloodMoon, [
    cooldownDecision.command,
    { move: 1 },
  ]);
  const availableAgain = chooseSimpleAiDecision(
    afterCooldown,
    0,
    "expert_search",
    "balanced",
  );
  assert.equal(availableAgain.command.move, 1);
});

test("starts the Blood Moon cooldown cycle before Light Screen expires", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Blood Moon AI",
          team: [
            pokemon({
              name: "Bloodmoon Ursaluna",
              types: ["Normal", "Ground"],
              stats: {
                ...pokemon().stats,
                hp: 428,
                specialAttack: 205,
                speed: 80,
              },
              moves: [
                {
                  id: "bloodmoon",
                  name: "Blood Moon",
                  type: "Normal",
                  category: "Special",
                  power: 140,
                  accuracy: 100,
                  pp: 5,
                },
                {
                  id: "earthpower",
                  name: "Earth Power",
                  type: "Ground",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                  secondaries: [
                    {
                      chance: 10,
                      boosts: { specialDefence: -1 },
                    },
                  ],
                },
              ],
            }),
          ],
        },
        {
          name: "Snorlax",
          team: [
            pokemon({
              name: "Snorlax",
              types: ["Normal"],
              stats: {
                ...pokemon().stats,
                hp: 524,
                specialDefence: 210,
                speed: 40,
              },
              moves: [
                {
                  id: "curse",
                  name: "Curse",
                  type: "Ghost",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  boosts: { attack: 1, defence: 1, speed: -1 },
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].conditions.lightscreen = {
    id: "lightscreen",
    turns: 3,
  };

  const firstDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert_search",
    "balanced",
  );
  assert.equal(
    firstDecision.command.move,
    1,
    JSON.stringify(firstDecision.diagnostics, null, 2),
  );

  const afterBloodMoon = resolveSimpleTurn(state, [
    firstDecision.command,
    { move: 1 },
  ]);
  const cooldownDecision = chooseSimpleAiDecision(
    afterBloodMoon,
    0,
    "expert_search",
    "balanced",
  );
  assert.equal(cooldownDecision.command.move, 2);

  const afterCooldown = resolveSimpleTurn(afterBloodMoon, [
    cooldownDecision.command,
    { move: 1 },
  ]);
  const availableBeforeExpiry = chooseSimpleAiDecision(
    afterCooldown,
    0,
    "expert_search",
    "balanced",
  );
  assert.equal(availableBeforeExpiry.command.move, 1);
});

test("applies stat-changing status moves to the battle state", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              moves: [
                {
                  id: "growl",
                  name: "Growl",
                  type: "Normal",
                  category: "Status",
                  accuracy: 100,
                  pp: 40,
                  boosts: { atk: -1 },
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon()] },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[1].team[0].boosts.attack, -1);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "stat_change" &&
        event.pokemon === "Testmon" &&
        event.stat === "atk" &&
        event.amount === -1,
    ),
  );
});

test("applies burn, action penalties and end-of-turn residual damage", () => {
  const state = createSimpleBattle(
    setup({
      seed: 7,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Burner",
              moves: [
                {
                  id: "willowisp",
                  name: "Will-O-Wisp",
                  type: "Fire",
                  category: "Status",
                  accuracy: true,
                  pp: 15,
                  status: "brn",
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "BurnTarget",
              stats: { ...pokemon().stats, hp: 160, speed: 50 },
            }),
          ],
        },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const target = next.sides[1].team[0];

  assert.equal(target.status, "brn");
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "BurnTarget" &&
        event.cause === "status" &&
        event.source === "brn",
    ),
  );
});

test("uses stat stages in later damage calculations", () => {
  const baseSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "Booster",
            moves: [
              {
                id: "swordsdance",
                name: "Swords Dance",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 20,
                target: "self",
                boosts: { atk: 2 },
              },
              {
                id: "slash",
                name: "Slash",
                type: "Normal",
                category: "Physical",
                power: 70,
                accuracy: true,
                pp: 20,
              },
            ],
          }),
        ],
      },
      {
        name: "AI",
        team: [
          pokemon({
            name: "Wall",
            moves: [
              {
                id: "recover",
                name: "Recover",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 10,
                heal: [1, 2],
              },
            ],
          }),
        ],
      },
    ],
  });
  const boosted = resolveSimpleTurn(createSimpleBattle(baseSetup), [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(boosted.sides[0].team[0].boosts.attack, 2);

  const attacked = resolveSimpleTurn(boosted, [{ move: 2 }, { move: 1 }]);
  const damage = attacked.events.find(
    (event) =>
      event.turn === 2 &&
      event.type === "damage" &&
      event.pokemon === "Wall",
  )?.damage;
  assert.ok(damage > 50);
});

test("restores HP with recovery moves and Leftovers", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Healer",
              item: "leftovers",
              moves: [
                {
                  id: "recover",
                  name: "Recover",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  heal: [1, 2],
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Attacker", stats: { ...pokemon().stats, speed: 150 } })] },
      ],
    }),
  );
  state.sides[0].team[0].hp = 40;
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.ok(next.sides[0].team[0].hp > 40);
  assert.ok(
    next.events.some(
      (event) => event.type === "heal" && event.source === "Recover",
    ),
  );
  assert.ok(
    next.events.some(
      (event) => event.type === "heal" && event.source === "Leftovers",
    ),
  );
});

test("uses Rest to cure status, sleep, and fully heal", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "RestUser",
              stats: { ...pokemon().stats, hp: 160, speed: 160 },
              moves: [
                {
                  id: "rest",
                  name: "Rest",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Target",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].hp = 30;
  state.sides[0].team[0].status = "brn";

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const user = next.sides[0].team[0];

  assert.equal(user.hp, 160);
  assert.equal(user.status, "slp");
  assert.equal(user.statusTurns, 2);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "status_cured" &&
        event.pokemon === "RestUser" &&
        event.status === "brn",
    ),
  );
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "heal" &&
        event.pokemon === "RestUser" &&
        event.source === "Rest",
    ),
  );
});

test("resolves a manual switch before the opponent move", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({ name: "Lead" }),
            pokemon({ name: "Reserve", types: ["Rock"] }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Attacker" })] },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [{ switch: 2 }, { move: 1 }]);

  assert.equal(next.sides[0].active, 1);
  assert.equal(next.sides[0].team[0].hp, 120);
  assert.ok(next.sides[0].team[1].hp < 120);
  const switchEvent = next.events.find(
    (event) => event.type === "switch" && event.turn === 1,
  );
  assert.equal(switchEvent.pokemon, "Reserve");
  assert.equal(switchEvent.fromPokemon, "Lead");
  assert.equal(switchEvent.selection, "manual_switch");
  assert.equal(switchEvent.forced, undefined);
});

test("switches out after self-switch moves and keeps entry effects active", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Pivot",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "uturn",
                  name: "U-turn",
                  type: "Bug",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  pp: 20,
                  selfSwitch: true,
                },
              ],
            }),
            pokemon({
              name: "FireReserve",
              types: ["Fire"],
              moves: [
                {
                  id: "ember",
                  name: "Ember",
                  type: "Fire",
                  category: "Special",
                  power: 40,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
            pokemon({
              name: "WaterReserve",
              types: ["Water"],
              moves: [
                {
                  id: "surf",
                  name: "Surf",
                  type: "Water",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "RockTarget",
              types: ["Rock"],
              stats: { ...pokemon().stats, hp: 300, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].conditions.stealthrock = { id: "stealthrock", layers: 1 };

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[0].team[next.sides[0].active].name, "WaterReserve");
  assert.ok(next.sides[0].team[next.sides[0].active].hp < 120);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "switch" &&
        event.source === "U-turn" &&
        event.selection === "self_switch",
    ),
  );
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "WaterReserve" &&
      event.source === "stealthrock",
    ),
  );

  const preferred = resolveSimpleTurn(
    state,
    [{ move: 1, selfSwitchSlot: 2 }, { move: 1 }],
  );
  assert.equal(
    preferred.sides[0].team[preferred.sides[0].active].name,
    "FireReserve",
  );
});

test("forces the target out after phazing moves", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Phazer",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "whirlwind",
                  name: "Whirlwind",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  priority: -6,
                  pp: 20,
                  forceSwitch: true,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({ name: "Lead" }),
            pokemon({
              name: "FireReserve",
              types: ["Fire"],
              moves: [
                {
                  id: "ember",
                  name: "Ember",
                  type: "Fire",
                  category: "Special",
                  power: 40,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
            pokemon({
              name: "WaterReserve",
              types: ["Water"],
              moves: [
                {
                  id: "surf",
                  name: "Surf",
                  type: "Water",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].conditions.stealthrock = { id: "stealthrock", layers: 1 };

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[1].team[next.sides[1].active].name, "WaterReserve");
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "switch" &&
        event.source === "Whirlwind" &&
        event.selection === "force_switch",
    ),
  );
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "WaterReserve" &&
        event.source === "stealthrock",
    ),
  );
});

test("uses Parting Shot as a status pivot after lowering the target", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "PartingUser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "partingshot",
                  name: "Parting Shot",
                  type: "Dark",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  boosts: { attack: -1, specialAttack: -1 },
                  selfSwitch: true,
                },
              ],
            }),
            pokemon({ name: "Reserve" }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[1].team[0].boosts.attack, -1);
  assert.equal(next.sides[1].team[0].boosts.specialAttack, -1);
  assert.equal(next.sides[0].team[next.sides[0].active].name, "Reserve");
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "switch" &&
        event.source === "Parting Shot" &&
        event.selection === "self_switch",
    ),
  );
});

test("applies volatile status moves such as Confuse Ray and Taunt", () => {
  const confusionState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Confuser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "confuseray",
                  name: "Confuse Ray",
                  type: "Ghost",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  volatileStatus: "confusion",
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );
  const confused = resolveSimpleTurn(confusionState, [{ move: 1 }, { move: 1 }]);
  assert.equal(confused.sides[1].team[0].volatiles.confusion.id, "confusion");
  assert.ok(
    confused.events.some(
      (event) =>
        event.type === "volatile_start" &&
        event.pokemon === "Target" &&
        event.effect === "confusion",
    ),
  );

  const tauntState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Taunter",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "taunt",
                  name: "Taunt",
                  type: "Dark",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  volatileStatus: "taunt",
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "RecoverTarget",
              stats: { ...pokemon().stats, hp: 160, speed: 40 },
              moves: [
                {
                  id: "recover",
                  name: "Recover",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  heal: [1, 2],
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  tauntState.sides[1].team[0].hp = 40;
  const taunted = resolveSimpleTurn(tauntState, [{ move: 1 }, { move: 1 }]);
  assert.equal(taunted.sides[1].team[0].hp, 40);
  assert.ok(
    taunted.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.pokemon === "RecoverTarget" &&
        event.source === "taunt",
    ),
  );
});

test("resolves fixed damage, fractional damage, weather recovery, and no-op moves", () => {
  const nightShadeState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "ShadeUser",
              level: 77,
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "nightshade",
                  name: "Night Shade",
                  type: "Ghost",
                  category: "Special",
                  power: 0,
                  accuracy: true,
                  pp: 15,
                  fixedDamage: "level",
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target", types: ["Psychic"] })] },
      ],
    }),
  );
  const shaded = resolveSimpleTurn(nightShadeState, [{ move: 1 }, { move: 1 }]);
  assert.equal(
    shaded.events.find(
      (event) => event.type === "damage" && event.move === "Night Shade",
    )?.damage,
    77,
  );

  const nightShadeAi = runSimpleBattle(
    {
      seed: 1234,
      sides: nightShadeState.sides.map((side) => ({
        name: side.name,
        team: side.team,
      })),
    },
    { maxTurns: 1, difficulty: "expert" },
  );
  const nightShadeCandidate = nightShadeAi.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "nightshade");
  assert.equal(nightShadeCandidate.expectedDamage.value, 77);

  const ruinationState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "RuinUser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "ruination",
                  name: "Ruination",
                  type: "Dark",
                  category: "Special",
                  power: 1,
                  accuracy: true,
                  pp: 10,
                  dynamicDamage: true,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "RuinTarget",
              stats: { ...pokemon().stats, hp: 200, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  const ruined = resolveSimpleTurn(ruinationState, [{ move: 1 }, { move: 1 }]);
  assert.equal(
    ruined.events.find(
      (event) => event.type === "damage" && event.move === "Ruination",
    )?.damage,
    100,
  );

  const moonlightState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "MoonUser",
              stats: { ...pokemon().stats, hp: 180, speed: 160 },
              moves: [
                {
                  id: "moonlight",
                  name: "Moonlight",
                  type: "Fairy",
                  category: "Status",
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Target",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  moonlightState.sides[0].team[0].hp = 30;
  moonlightState.field.weather = { id: "sunnyday", turns: 5 };
  const moonlit = resolveSimpleTurn(moonlightState, [{ move: 1 }, { move: 1 }]);
  assert.equal(moonlit.sides[0].team[0].hp, 150);
  assert.ok(
    moonlit.events.some(
      (event) =>
        event.type === "move" &&
        event.pokemon === "Target" &&
        event.move === "Splash",
    ),
  );
});

test("handles Protect and Sucker Punch timing rules", () => {
  const protectState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Protector",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "protect",
                  name: "Protect",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  priority: 4,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Attacker",
              stats: { ...pokemon().stats, attack: 160, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  const protectedTurn = resolveSimpleTurn(protectState, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(protectedTurn.sides[0].team[0].hp, 120);
  assert.ok(
    protectedTurn.events.some(
      (event) =>
        event.type === "move_blocked" &&
        event.pokemon === "Protector" &&
        event.source === "protect",
    ),
  );
  const consecutiveProtectDecision = chooseSimpleAiDecision(
    protectedTurn,
    0,
    "expert",
    "balanced",
  );
  const consecutiveProtect = consecutiveProtectDecision.moveCandidates.find(
    (candidate) => candidate.id === "protect",
  );
  assert.equal(consecutiveProtect.protectSuccessProbability, 1 / 3);
  const consecutiveProtectTrace = createSimpleAiDecisionTrace(
    protectedTurn,
    0,
    consecutiveProtectDecision,
    "expert",
    "balanced",
  );
  assert.ok(
    consecutiveProtectTrace.candidates
      .find((candidate) => candidate.id === "protect")
      .reasons.some(
        (reason) => reason.code === "rule.protect.consecutive_failure_risk",
      ),
  );

  protectedTurn.sides[0].team[0].protectCounter = 20;
  const failedConsecutiveProtect = resolveSimpleTurn(protectedTurn, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.ok(
    failedConsecutiveProtect.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.pokemon === "Protector" &&
        event.move === "Protect",
    ),
  );
  assert.equal(
    failedConsecutiveProtect.sides[0].team[0].protectCounter,
    0,
  );

  const suckerState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Ambusher",
              stats: { ...pokemon().stats, attack: 160, speed: 40 },
              moves: [
                {
                  id: "suckerpunch",
                  name: "Sucker Punch",
                  type: "Dark",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  priority: 1,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "RecoverTarget",
              stats: { ...pokemon().stats, hp: 160, speed: 30 },
              moves: [
                {
                  id: "recover",
                  name: "Recover",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  heal: [1, 2],
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const failed = resolveSimpleTurn(suckerState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    failed.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.move === "Sucker Punch",
    ),
  );

  suckerState.sides[1].team[0].moves[0] = {
    id: "tackle",
    name: "Tackle",
    type: "Normal",
    category: "Physical",
    power: 40,
    accuracy: true,
    pp: 35,
    priority: 0,
    target: "normal",
    critRatio: 1,
    status: "",
    selfStatus: "",
    volatileStatus: "",
    boosts: {},
    selfBoosts: {},
    heal: null,
    drain: null,
    recoil: null,
    weather: "",
    terrain: "",
    pseudoWeather: "",
    sideCondition: "",
    slotCondition: "",
    multihit: null,
    multiaccuracy: false,
    willCrit: false,
    selfSwitch: false,
    forceSwitch: false,
    fixedDamage: null,
    dynamicDamage: false,
    dynamicPower: false,
    secondaries: [],
  };
  const succeeded = resolveSimpleTurn(suckerState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    succeeded.events.some(
      (event) =>
        event.type === "damage" &&
        event.move === "Sucker Punch" &&
        event.damage > 0,
    ),
  );
});

test("AI stops repeating Sucker Punch after a faster priority attack", () => {
  const scenario = setup({
    sides: [
      {
        name: "Mawile AI",
        team: [
          pokemon({
            id: "mawilemega",
            name: "Mawile-Mega",
            types: ["Steel", "Fairy"],
            ability: "hugepower",
            stats: {
              ...pokemon().stats,
              hp: 304,
              attack: 210,
              defence: 160,
              speed: 50,
            },
            moves: [
              {
                id: "suckerpunch",
                name: "Sucker Punch",
                type: "Dark",
                category: "Physical",
                power: 70,
                accuracy: true,
                priority: 1,
                pp: 5,
              },
              {
                id: "playrough",
                name: "Play Rough",
                type: "Fairy",
                category: "Physical",
                power: 90,
                accuracy: 90,
                pp: 10,
              },
              {
                id: "ironhead",
                name: "Iron Head",
                type: "Steel",
                category: "Physical",
                power: 80,
                accuracy: 100,
                pp: 15,
              },
              {
                id: "irondefense",
                name: "Iron Defense",
                type: "Steel",
                category: "Status",
                accuracy: true,
                pp: 15,
                selfBoosts: { defence: 2 },
              },
            ],
          }),
        ],
      },
      {
        name: "Scizor AI",
        team: [
          pokemon({
            id: "scizor",
            name: "Scizor",
            types: ["Bug", "Steel"],
            stats: {
              ...pokemon().stats,
              hp: 344,
              attack: 200,
              defence: 150,
              speed: 100,
            },
            moves: [
              {
                id: "bulletpunch",
                name: "Bullet Punch",
                type: "Steel",
                category: "Physical",
                power: 40,
                accuracy: true,
                priority: 1,
                pp: 30,
              },
            ],
          }),
        ],
      },
    ],
  });
  const afterFailedSuckerPunch = resolveSimpleTurn(
    createSimpleBattle(scenario),
    [{ move: 1 }, { move: 1 }],
  );

  assert.ok(
    afterFailedSuckerPunch.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.pokemon === "Mawile-Mega" &&
        event.move === "Sucker Punch",
    ),
  );
  assert.notEqual(
    chooseSimpleAiCommand(
      afterFailedSuckerPunch,
      0,
      "expert",
      "balanced",
    ).move,
    1,
  );
});

test("AI adapts to repeated Sucker Punch status bait by seed and failure count", () => {
  const scenario = setup({
    sides: [
      {
        name: "Mawile AI",
        team: [
          pokemon({
            id: "mawilemega",
            name: "Mawile-Mega",
            types: ["Steel", "Fairy"],
            ability: "hugepower",
            stats: {
              ...pokemon().stats,
              hp: 304,
              attack: 210,
              speed: 50,
            },
            moves: [
              {
                id: "suckerpunch",
                name: "Sucker Punch",
                type: "Dark",
                category: "Physical",
                power: 70,
                accuracy: true,
                priority: 1,
                pp: 5,
              },
              {
                id: "ironhead",
                name: "Iron Head",
                type: "Steel",
                category: "Physical",
                power: 80,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
        ],
      },
      {
        name: "Calyrex AI",
        team: [
          pokemon({
            id: "calyrexshadow",
            name: "Calyrex-Shadow",
            types: ["Psychic", "Ghost"],
            stats: {
              ...pokemon().stats,
              hp: 342,
              specialAttack: 230,
              speed: 200,
            },
            moves: [
              {
                id: "nastyplot",
                name: "Nasty Plot",
                type: "Dark",
                category: "Status",
                accuracy: true,
                pp: 20,
                selfBoosts: { specialAttack: 2 },
              },
              {
                id: "astralbarrage",
                name: "Astral Barrage",
                type: "Ghost",
                category: "Special",
                power: 120,
                accuracy: true,
                pp: 5,
              },
            ],
          }),
        ],
      },
    ],
  });
  const stateAfterFailures = (seed, failureCount) => {
    let state = createSimpleBattle({ ...scenario, seed });
    for (let index = 0; index < failureCount; index += 1) {
      state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
    }
    state.sides[0].team[0].megaEvolved = true;
    state.sides[0].gimmickResources.dynamax = "consumed";
    return state;
  };
  const commandAfterFailures = (seed, failureCount) =>
    chooseSimpleAiCommand(
      stateAfterFailures(seed, failureCount),
      0,
      "expert",
      "balanced",
    ).move;
  const seeds = Array.from(
    { length: 80 },
    (_, index) => ((index + 1) * 2_654_435_761) >>> 0,
  );
  const firstFailureChoices = seeds.map((seed) =>
    commandAfterFailures(seed, 1),
  );
  const thirdFailureChoices = seeds.map((seed) =>
    commandAfterFailures(seed, 3),
  );
  const firstFailureAvoidCount = firstFailureChoices.filter(
    (move) => move !== 1,
  ).length;
  const thirdFailureAvoidCount = thirdFailureChoices.filter(
    (move) => move !== 1,
  ).length;

  assert.ok(firstFailureChoices.some((move) => move === 1));
  assert.ok(firstFailureChoices.some((move) => move !== 1));
  assert.ok(thirdFailureAvoidCount > firstFailureAvoidCount);
  assert.equal(
    commandAfterFailures(20260719, 1),
    commandAfterFailures(20260719, 1),
  );
  const maxedSetupDecision = chooseSimpleAiDecision(
    stateAfterFailures(20260719, 4),
    0,
    "expert",
    "balanced",
  );
  const suckerPunchAfterFailedSetup =
    maxedSetupDecision.moveCandidates.find(
      (candidate) => candidate.id === "suckerpunch",
    );
  assert.equal(
    suckerPunchAfterFailedSetup.conditionalPriorityRepeatFailure,
    true,
  );
  assert.equal(
    suckerPunchAfterFailedSetup.conditionalPriorityFailureStreak,
    4,
  );
  assert.notEqual(maxedSetupDecision.command.move, 1);
});

test("AI does not repeat stat boosts that are already maximized", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Mawile AI",
          team: [
            pokemon({
              name: "Mawile-Mega",
              types: ["Steel", "Fairy"],
              stats: {
                ...pokemon().stats,
                hp: 304,
                attack: 210,
              },
              moves: [
                {
                  id: "irondefense",
                  name: "Iron Defense",
                  type: "Steel",
                  category: "Status",
                  accuracy: true,
                  pp: 15,
                  selfBoosts: { defence: 2 },
                },
                {
                  id: "ironhead",
                  name: "Iron Head",
                  type: "Steel",
                  category: "Physical",
                  power: 80,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "Calyrex AI",
          team: [
            pokemon({
              name: "Calyrex-Shadow",
              types: ["Psychic", "Ghost"],
              stats: {
                ...pokemon().stats,
                hp: 342,
                specialAttack: 230,
              },
              moves: [
                {
                  id: "nastyplot",
                  name: "Nasty Plot",
                  type: "Dark",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  selfBoosts: { specialAttack: 2 },
                },
                {
                  id: "astralbarrage",
                  name: "Astral Barrage",
                  type: "Ghost",
                  category: "Special",
                  power: 120,
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].boosts.defence = 6;
  state.sides[1].team[0].boosts.specialAttack = 6;
  state.sides[0].team[0].megaEvolved = true;
  state.sides[0].gimmickResources.dynamax = "consumed";
  state.sides[1].gimmickResources.dynamax = "consumed";

  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "balanced").move,
    2,
  );
  assert.equal(
    chooseSimpleAiCommand(state, 1, "expert", "balanced").move,
    2,
  );
});

test("removes, steals, and burns consumable held items after hits", () => {
  const thiefState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "ThiefUser",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "thief",
                  name: "Thief",
                  type: "Dark",
                  category: "Physical",
                  power: 60,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "ItemTarget", item: "leftovers" })],
        },
      ],
    }),
  );
  const stolen = resolveSimpleTurn(thiefState, [{ move: 1 }, { move: 1 }]);
  assert.equal(stolen.sides[0].team[0].item, "leftovers");
  assert.equal(stolen.sides[1].team[0].item, "");
  assert.ok(stolen.events.some((event) => event.type === "item_stolen"));

  const incinerateState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Burner",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "incinerate",
                  name: "Incinerate",
                  type: "Fire",
                  category: "Special",
                  power: 60,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "BerryTarget", item: "sitrusberry" })],
        },
      ],
    }),
  );
  const burned = resolveSimpleTurn(incinerateState, [{ move: 1 }, { move: 1 }]);
  assert.equal(burned.sides[1].team[0].item, "");
  assert.ok(
    burned.events.some(
      (event) =>
        event.type === "item_removed" &&
        event.source === "Incinerate",
    ),
  );
});

test("handles field cleanup, terrain move bonuses, and OHKO damage", () => {
  const rapidSpinState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Spinner",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "rapidspin",
                  name: "Rapid Spin",
                  type: "Normal",
                  category: "Physical",
                  power: 50,
                  accuracy: true,
                  pp: 40,
                  self: { boosts: { spe: 1 } },
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );
  rapidSpinState.sides[0].conditions.stealthrock = {
    id: "stealthrock",
    layers: 1,
  };
  rapidSpinState.sides[0].conditions.stickyweb = {
    id: "stickyweb",
    layers: 1,
  };
  const spun = resolveSimpleTurn(rapidSpinState, [{ move: 1 }, { move: 1 }]);
  assert.equal(spun.sides[0].conditions.stealthrock, undefined);
  assert.equal(spun.sides[0].conditions.stickyweb, undefined);

  const iceSpinnerState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "IceCleaner",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "icespinner",
                  name: "Ice Spinner",
                  type: "Ice",
                  category: "Physical",
                  power: 80,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "TerrainTarget" })] },
      ],
    }),
  );
  iceSpinnerState.field.terrain = { id: "electricterrain", turns: 5 };
  const spunIce = resolveSimpleTurn(iceSpinnerState, [{ move: 1 }, { move: 1 }]);
  assert.equal(spunIce.field.terrain, null);

  const terrainState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "TerrainAttacker",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 40 },
              moves: [
                {
                  id: "expandingforce",
                  name: "Expanding Force",
                  type: "Psychic",
                  category: "Special",
                  power: 80,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Glider",
              types: ["Grass"],
              stats: { ...pokemon().stats, attack: 160, speed: 20 },
              moves: [
                {
                  id: "grassyglide",
                  name: "Grassy Glide",
                  type: "Grass",
                  category: "Physical",
                  power: 55,
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  terrainState.field.terrain = { id: "grassyterrain", turns: 5 };
  const glided = resolveSimpleTurn(terrainState, [{ move: 1 }, { move: 1 }]);
  assert.equal(
    glided.events.find((event) => event.type === "move" && event.turn === 1)
      ?.move,
    "Grassy Glide",
  );

  terrainState.field.terrain = { id: "psychicterrain", turns: 5 };
  const boosted = resolveSimpleTurn(terrainState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    boosted.events.find(
      (event) => event.type === "damage" && event.move === "Expanding Force",
    )?.damage > glided.events.find(
      (event) => event.type === "damage" && event.move === "Expanding Force",
    )?.damage,
  );

  const fissureState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "FissureUser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "fissure",
                  name: "Fissure",
                  type: "Ground",
                  category: "Physical",
                  power: 0,
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "GroundedTarget", stats: { ...pokemon().stats, hp: 200 } })],
        },
      ],
    }),
  );
  const fissured = resolveSimpleTurn(fissureState, [{ move: 1 }, { move: 1 }]);
  assert.equal(fissured.sides[1].team[0].fainted, true);
});

test("finishes a full battle and returns an explainable event timeline", () => {
  const result = runSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Attacker",
              stats: { ...pokemon().stats, attack: 300, speed: 200 },
              moves: [
                {
                  id: "heavyhit",
                  name: "Heavy Hit",
                  type: "Normal",
                  category: "Physical",
                  power: 200,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FirstTarget",
              stats: { ...pokemon().stats, hp: 40 },
            }),
            pokemon({
              name: "SecondTarget",
              stats: { ...pokemon().stats, hp: 40 },
            }),
          ],
        },
      ],
    }),
  );

  assert.equal(result.status, "completed");
  assert.equal(result.winner, "Player");
  assert.ok(
    result.events.some(
      (event) => event.type === "switch" && event.automatic === true,
    ),
  );
  assert.ok(result.events.some((event) => event.type === "win"));
});

test("chooses an AI replacement by matchup instead of party order", () => {
  const strongMove = (type) => ({
    id: `${type.toLowerCase()}hit`,
    name: `${type} Hit`,
    type,
    category: "Special",
    power: 90,
    accuracy: true,
    pp: 10,
  });
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "GrassTarget",
              types: ["Grass"],
              stats: { ...pokemon().stats, attack: 300, speed: 200 },
              moves: [
                {
                  ...strongMove("Normal"),
                  category: "Physical",
                  power: 200,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FaintedLead",
              stats: { ...pokemon().stats, hp: 20, speed: 20 },
            }),
            pokemon({
              name: "WaterReserve",
              types: ["Water"],
              moves: [strongMove("Water")],
            }),
            pokemon({
              name: "FireReserve",
              types: ["Fire"],
              moves: [strongMove("Fire")],
            }),
          ],
        },
      ],
    }),
  );

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.equal(next.sides[1].team[0].fainted, true);
  assert.equal(next.sides[1].team[next.sides[1].active].name, "FireReserve");
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "switch" &&
        event.fromPokemon === "FaintedLead" &&
        event.pokemon === "FireReserve" &&
        event.forced === true &&
        event.selection === "matchup_score",
    ),
  );
});

test("forces the final replacement onto entry hazards even when it will faint", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Attacker",
          team: [
            pokemon({
              name: "Attacker",
              stats: { ...pokemon().stats, attack: 300, speed: 200 },
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 200,
                  accuracy: true,
                  pp: 35,
                },
              ],
            }),
          ],
        },
        {
          name: "Hazard Side",
          team: [
            pokemon({
              name: "Active",
              stats: { ...pokemon().stats, hp: 40, speed: 20 },
              hp: 1,
            }),
            pokemon({
              name: "Final Bench",
              stats: { ...pokemon().stats, hp: 120 },
              hp: 15,
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].team[0].hp = 1;
  state.sides[1].team[1].hp = 15;
  state.sides[1].conditions.stealthrock = {
    id: "stealthrock",
    layers: 1,
  };

  const result = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(result.sides[1].active, 1);
  assert.equal(result.sides[1].team[1].fainted, true);
  assert.equal(result.status, "completed");
  assert.equal(result.winner, "Attacker");
});

test("avoids a forced replacement that will faint before its first action", () => {
  const attackingMove = ({
    id,
    name,
    type,
    category = "Physical",
    power,
    priority = 0,
  }) => ({
    id,
    name,
    type,
    category,
    power,
    priority,
    accuracy: true,
    pp: 10,
  });
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Mawile-Mega",
              types: ["Steel", "Fairy"],
              stats: {
                ...pokemon().stats,
                attack: 300,
                defence: 180,
                specialDefence: 150,
                speed: 100,
              },
              moves: [
                attackingMove({
                  id: "playrough",
                  name: "Play Rough",
                  type: "Fairy",
                  power: 90,
                }),
                attackingMove({
                  id: "suckerpunch",
                  name: "Sucker Punch",
                  type: "Dark",
                  power: 70,
                  priority: 1,
                }),
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FaintedLead",
              types: ["Dragon"],
              stats: { ...pokemon().stats, hp: 20, speed: 20 },
            }),
            pokemon({
              name: "Calyrex-Shadow",
              types: ["Psychic", "Ghost"],
              stats: {
                ...pokemon().stats,
                hp: 80,
                defence: 60,
                specialAttack: 260,
                speed: 200,
              },
              moves: [
                attackingMove({
                  id: "astralbarrage",
                  name: "Astral Barrage",
                  type: "Ghost",
                  category: "Special",
                  power: 120,
                }),
              ],
            }),
            pokemon({
              name: "Scizor",
              types: ["Bug", "Steel"],
              stats: {
                ...pokemon().stats,
                hp: 300,
                attack: 190,
                defence: 250,
                specialDefence: 150,
                speed: 80,
              },
              moves: [
                attackingMove({
                  id: "ironhead",
                  name: "Iron Head",
                  type: "Steel",
                  power: 80,
                }),
              ],
            }),
          ],
        },
      ],
    }),
  );

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[1].team[0].fainted, true);
  assert.equal(next.sides[1].team[next.sides[1].active].name, "Scizor");
  assert.equal(
    next.events.some(
      (event) =>
        event.type === "switch" &&
        event.fromPokemon === "FaintedLead" &&
        event.pokemon === "Calyrex-Shadow",
    ),
    false,
  );
});

test("changes automatic move selection according to AI difficulty", () => {
  const choiceSetup = setup({
    sides: [0, 1].map((side) => ({
      name: side === 0 ? "Player" : "AI",
      team: [
        pokemon({
          name: side === 0 ? "PlayerMon" : "AiMon",
          moves: [
            {
              id: "weak",
              name: "Weak",
              type: "Normal",
              category: "Physical",
              power: 20,
              accuracy: 100,
              pp: 20,
            },
            {
              id: "strong",
              name: "Strong",
              type: "Normal",
              category: "Physical",
              power: 100,
              accuracy: 100,
              pp: 20,
            },
          ],
        }),
      ],
    })),
  });

  const expert = runSimpleBattle(choiceSetup, {
    difficulty: "expert",
    maxTurns: 1,
  });
  assert.equal(
    expert.events.find((event) => event.type === "move").move,
    "Strong",
  );
});

test("treats an entry Dynamax flag as a forced AI command", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        { name: "Player", team: [pokemon({ name: "PlayerMon" })] },
        {
          name: "AI",
          team: [
            pokemon({
              name: "ForcedDynamax",
              gimmicks: {
                canDynamax: true,
                forceDynamax: true,
                gigantamax: false,
              },
            }),
          ],
        },
      ],
    }),
  );
  const first = chooseSimpleAiCommand(state, 1);
  assert.equal(first.gimmick, "dynamax");

  state.sides[1].gimmickResources.dynamax = "consumed";
  const later = chooseSimpleAiCommand(state, 1);
  assert.equal(later.gimmick, undefined);
});

test("treats Gigantamax as a distinct Dynamax mode and lets Urshifu G-Max moves bypass Protect", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                id: "urshifurapidstrike",
                name: "Urshifu-Rapid-Strike",
                types: ["Fighting", "Water"],
                stats: { ...pokemon().stats, attack: 180, speed: 120 },
                gimmicks: { canDynamax: true },
                moves: [
                  {
                    id: "surgingstrikes",
                    name: "Surging Strikes",
                    type: "Water",
                    category: "Physical",
                    power: 25,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Shield",
                stats: { ...pokemon().stats, hp: 300, speed: 200 },
                moves: [
                  {
                    id: "protect",
                    name: "Protect",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "gigantamax" }, { move: 1 }],
  );

  assert.equal(state.sides[0].team[0].dynamaxMode, "gigantamax");
  assert.equal(state.sides[0].usedGimmicks.dynamax, true);
  assert.equal(state.sides[0].usedGimmicks.gigantamax, true);
  assert.ok(
    state.events.some(
      (event) => event.type === "move" && event.move === "G-Max Rapid Flow",
    ),
  );
  assert.ok(
    state.events.some(
      (event) => event.type === "damage" && event.pokemon === "Shield",
    ),
  );
  assert.ok(
    !state.events.some(
      (event) => event.type === "move_blocked" && event.pokemon === "Shield",
    ),
  );
});

test("delays forced Dynamax for a safe setup turn without Max Knuckle", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "PassiveTarget",
              stats: { ...pokemon().stats, attack: 30 },
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "SetupDynamax",
              gimmicks: { forceDynamax: true },
              moves: [
                {
                  id: "swordsdance",
                  name: "Swords Dance",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  selfBoosts: { atk: 2 },
                },
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 20,
                  accuracy: 100,
                  pp: 20,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  const command = chooseSimpleAiCommand(state, 1, "expert", "setup");
  assert.equal(command.move, 1);
  assert.equal(command.gimmick, undefined);
});

test("AI preserves forced Gigantamax when doubled HP still loses the exchange", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Gigantamax Venusaur",
          team: [
            pokemon({
              name: "Venusaur",
              types: ["Grass", "Poison"],
              stats: {
                ...pokemon().stats,
                hp: 312,
                specialAttack: 400,
                speed: 80,
              },
              moves: [
                {
                  id: "sludgebomb",
                  name: "Sludge Bomb",
                  type: "Poison",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Low HP Urshifu",
          team: [
            pokemon({
              id: "urshifurapidstrike",
              name: "Urshifu-Rapid-Strike",
              types: ["Fighting", "Water"],
              stats: {
                ...pokemon().stats,
                hp: 343,
                attack: 180,
                specialDefence: 100,
                speed: 120,
              },
              gimmicks: {
                canDynamax: true,
                canGigantamax: true,
                forceDynamax: true,
              },
              moves: [
                {
                  id: "closecombat",
                  name: "Close Combat",
                  type: "Fighting",
                  category: "Physical",
                  power: 120,
                  accuracy: 100,
                  pp: 5,
                  selfBoosts: {
                    defence: -1,
                    specialDefence: -1,
                  },
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].dynamaxTurns = 2;
  state.sides[1].team[0].hp = 100;

  const decision = chooseSimpleAiDecision(
    state,
    1,
    "expert",
    "balanced",
  );

  assert.equal(decision.command.gimmick, undefined);
  assert.equal(decision.gimmickCandidate.score, -999);
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) =>
        reason.code === "gimmick.dynamax.cannot_survive_exchange",
    ),
  );
});

test("AI values Swords Dance before Surging Strikes when setup creates a KO line", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            id: "urshifurapidstrike",
            name: "Urshifu-Rapid-Strike",
            types: ["Fighting", "Water"],
            stats: {
              ...pokemon().stats,
              hp: 343,
              attack: 180,
              defence: 120,
              specialDefence: 120,
              speed: 120,
            },
            moves: [
              {
                id: "closecombat",
                name: "Close Combat",
                type: "Fighting",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 5,
                selfBoosts: { def: -1, spd: -1 },
              },
              {
                id: "surgingstrikes",
                name: "Surging Strikes",
                type: "Water",
                category: "Physical",
                power: 25,
                accuracy: 100,
                pp: 5,
              },
              {
                id: "swordsdance",
                name: "Swords Dance",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 20,
                selfBoosts: { atk: 2 },
              },
            ],
          }),
        ],
      },
      {
        name: "Target",
        team: [
          pokemon({
            name: "Garganacl",
            types: ["Rock"],
            stats: {
              ...pokemon().stats,
              hp: 404,
              defence: 160,
              specialDefence: 120,
              speed: 40,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);

  const command = chooseSimpleAiCommand(state, 0, "expert", "tempo");
  assert.equal(command.move, 3);

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "tempo" }],
  });
  const trace = battle.aiTrace.find((entry) => entry.side === 0);
  const surgingStrikes = trace.candidates.find(
    (candidate) => candidate.id === "surgingstrikes",
  );
  const swordsDance = trace.candidates.find(
    (candidate) => candidate.id === "swordsdance",
  );

  assert.ok(surgingStrikes.expectedDamage.value > 150);
  assert.ok(
    swordsDance.reasons.some(
      (reason) => reason.code === "rule.setup.team_sweep_plan",
    ),
  );
});

test("AI prefers multi-hit Surging Strikes over single-hit Close Combat into Sturdy", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            id: "urshifurapidstrike",
            name: "Urshifu-Rapid-Strike",
            level: 100,
            types: ["Fighting", "Water"],
            gimmicks: { gmax: true },
            hp: 116,
            boosts: { attack: 2 },
            stats: {
              ...pokemon().stats,
              hp: 343,
              attack: 900,
              defence: 120,
              specialDefence: 120,
              speed: 120,
            },
            moves: [
              {
                id: "closecombat",
                name: "Close Combat",
                type: "Fighting",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 5,
                selfBoosts: { def: -1, spd: -1 },
              },
              {
                id: "surgingstrikes",
                name: "Surging Strikes",
                type: "Water",
                category: "Physical",
                power: 25,
                accuracy: 100,
                pp: 5,
              },
              {
                id: "aquajet",
                name: "Aqua Jet",
                type: "Water",
                category: "Physical",
                power: 40,
                accuracy: 100,
                priority: 1,
                pp: 20,
              },
            ],
          }),
        ],
      },
      {
        name: "Target",
        team: [
          pokemon({
            name: "Garganacl",
            level: 100,
            types: ["Rock"],
            ability: "sturdy",
            stats: {
              ...pokemon().stats,
              hp: 404,
              defence: 160,
              specialDefence: 120,
              speed: 40,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });

  const state = createSimpleBattle(scenario);
  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");
  assert.equal(command.move, 2);
  assert.equal(command.gimmick, undefined);

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const trace = battle.aiTrace.find((entry) => entry.side === 0);
  const closeCombat = trace.candidates.find((candidate) => candidate.id === "closecombat");
  const surgingStrikes = trace.candidates.find((candidate) => candidate.id === "surgingstrikes");
  const gigantamax = trace.candidates.find((candidate) => candidate.id === "gigantamax");
  assert.equal(surgingStrikes.selected, true);
  assert.equal(gigantamax.selected, false);
  assert.ok(gigantamax.score < 12);
  assert.equal(closeCombat.koChance, "none");
  assert.equal(surgingStrikes.koChance, "guaranteed");
  assert.ok(
    closeCombat.reasons.some(
      (reason) => reason.code === "rule.sturdy.single_hit_blocked",
    ),
  );
  assert.ok(
    surgingStrikes.reasons.some(
      (reason) => reason.code === "rule.sturdy.multi_hit_breaker",
    ),
  );
  assert.ok(
    gigantamax.reasons.some(
      (reason) => reason.code === "gimmick.dynamax.loses_multi_hit_breaker",
    ),
  );
});

test("AI prefers no-drop Surging Strikes over Close Combat when both are KO moves", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            id: "urshifurapidstrike",
            name: "Urshifu-Rapid-Strike",
            level: 100,
            types: ["Fighting", "Water"],
            stats: {
              ...pokemon().stats,
              hp: 343,
              attack: 900,
              defence: 120,
              specialDefence: 120,
              speed: 120,
            },
            moves: [
              {
                id: "closecombat",
                name: "Close Combat",
                type: "Fighting",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 5,
                selfBoosts: { def: -1, spd: -1 },
              },
              {
                id: "surgingstrikes",
                name: "Surging Strikes",
                type: "Water",
                category: "Physical",
                power: 25,
                accuracy: 100,
                pp: 5,
              },
            ],
          }),
        ],
      },
      {
        name: "Target",
        team: [
          pokemon({
            name: "Garganacl",
            level: 100,
            types: ["Rock"],
            stats: {
              ...pokemon().stats,
              hp: 404,
              defence: 160,
              specialDefence: 120,
              speed: 40,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });

  const state = createSimpleBattle(scenario);
  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");
  assert.equal(command.move, 2);

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const trace = battle.aiTrace.find((entry) => entry.side === 0);
  const closeCombat = trace.candidates.find((candidate) => candidate.id === "closecombat");
  const surgingStrikes = trace.candidates.find((candidate) => candidate.id === "surgingstrikes");
  assert.equal(closeCombat.koChance, "guaranteed");
  assert.equal(surgingStrikes.koChance, "guaranteed");
  assert.equal(surgingStrikes.selected, true);
  assert.ok(
    closeCombat.reasons.some(
      (reason) => reason.code === "rule.self_drop.safe_ko_alternative",
    ),
  );
});

test("Focus Sash prevents a full-HP one-hit knockout and informs AI scoring", () => {
  const scenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "Mawile",
            types: ["Steel", "Fairy"],
            stats: { ...pokemon().stats, attack: 220, speed: 70 },
            moves: [
              {
                id: "suckerpunch",
                name: "Sucker Punch",
                type: "Dark",
                category: "Physical",
                power: 70,
                accuracy: true,
                priority: 1,
                pp: 5,
              },
            ],
          }),
        ],
      },
      {
        name: "AI",
        team: [
          pokemon({
            name: "Calyrex-Shadow",
            types: ["Psychic", "Ghost"],
            item: "Focus Sash",
            stats: { ...pokemon().stats, hp: 342, defence: 70, speed: 200 },
            moves: [
              {
                id: "astralbarrage",
                name: "Astral Barrage",
                type: "Ghost",
                category: "Special",
                power: 120,
                accuracy: true,
                pp: 5,
              },
            ],
          }),
        ],
      },
    ],
  });
  const result = resolveSimpleTurn(createSimpleBattle(scenario), [
    { move: 1 },
    { move: 1 },
  ]);
  const defender = result.sides[1].team[0];

  assert.equal(defender.hp, 1);
  assert.equal(defender.fainted, false);
  assert.equal(defender.item, "");
  assert.ok(
    result.events.some(
      (event) =>
        event.type === "damage_prevented" &&
        event.pokemon === "Calyrex-Shadow" &&
        event.source === "Focus Sash",
    ),
  );
  assert.ok(
    result.events.some(
      (event) =>
        event.type === "item_removed" &&
        event.pokemon === "Calyrex-Shadow" &&
        event.item === "focussash",
    ),
  );

  const traced = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "tempo" }],
  });
  const suckerPunch = traced.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "suckerpunch");
  assert.equal(suckerPunch?.koChance, "none");
  assert.ok(
    suckerPunch?.reasons?.some(
      (reason) => reason.code === "rule.focus_sash.single_hit_blocked",
    ),
  );
});

test("values setup as a probabilistic Sucker Punch bait without assuming certainty", () => {
  const makeScenario = (includeSuckerPunch) =>
    setup({
      sides: [
        {
          name: "Setup AI",
          team: [
            pokemon({
              name: "Calyrex-Shadow",
              types: ["Psychic", "Ghost"],
              stats: {
                ...pokemon().stats,
                hp: 250,
                defence: 80,
                specialAttack: 250,
                speed: 200,
              },
              moves: [
                {
                  id: "astralbarrage",
                  name: "Astral Barrage",
                  type: "Ghost",
                  category: "Special",
                  power: 120,
                  accuracy: true,
                  pp: 5,
                },
                {
                  id: "nastyplot",
                  name: "Nasty Plot",
                  type: "Dark",
                  category: "Status",
                  power: 0,
                  accuracy: true,
                  pp: 20,
                  selfBoosts: { specialAttack: 2 },
                },
              ],
            }),
          ],
        },
        {
          name: "Mawile AI",
          team: [
            pokemon({
              name: "Mawile-Mega",
              types: ["Steel", "Fairy"],
              stats: {
                ...pokemon().stats,
                hp: 150,
                attack: 220,
                specialDefence: 70,
                speed: 50,
              },
              moves: [
                includeSuckerPunch
                  ? {
                      id: "suckerpunch",
                      name: "Sucker Punch",
                      type: "Dark",
                      category: "Physical",
                      power: 70,
                      accuracy: true,
                      priority: 1,
                      pp: 5,
                    }
                  : {
                      id: "growl",
                      name: "Growl",
                      type: "Normal",
                      category: "Status",
                      power: 0,
                      accuracy: true,
                      pp: 40,
                      boosts: { attack: -1 },
                    },
                {
                  id: "weakironhead",
                  name: "Weak Iron Head",
                  type: "Steel",
                  category: "Physical",
                  power: 20,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
      ],
    });
  const traceFor = (scenario) =>
    runSimpleBattle(scenario, {
      maxTurns: 1,
      aiProfiles: [
        { difficulty: "expert", strategy: "balanced" },
        { difficulty: "expert", strategy: "balanced" },
      ],
    }).aiTrace.find((trace) => trace.side === 0);

  const baitCandidate = traceFor(makeScenario(true)).candidates.find(
    (candidate) => candidate.id === "nastyplot",
  );
  const controlCandidate = traceFor(makeScenario(false)).candidates.find(
    (candidate) => candidate.id === "nastyplot",
  );
  const baitReason = baitCandidate.reasons.find(
    (reason) => reason.code === "rule.setup.conditional_priority_bait",
  );

  assert.ok(baitReason);
  assert.ok(baitReason.weight > 0);
  assert.ok(baitCandidate.opponentConditionalPriorityLikelihood >= 0.25);
  assert.ok(baitCandidate.opponentConditionalPriorityLikelihood <= 0.85);
  assert.ok(baitCandidate.score > controlCandidate.score);
});

test("lets a non-Mega fallback use Dynamax after the configured Dynamax Pokémon faints", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        { name: "Player", team: [pokemon({ name: "PlayerMon" })] },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FaintedDynamax",
              gimmicks: { dynamax: true },
            }),
            pokemon({
              name: "BackupDynamax",
            }),
            pokemon({
              name: "MegaBackup",
              item: "testite",
              gimmicks: {
                megaStone: {
                  item: "testite",
                  evolves: "testmon",
                  form: "Testmon-Mega",
                },
              },
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].team[0].fainted = true;
  state.sides[1].team[0].hp = 0;

  state.sides[1].active = 1;
  assert.equal(chooseSimpleAiCommand(state, 1, "expert", "balanced").gimmick, "dynamax");

  state.sides[1].active = 2;
  assert.equal(chooseSimpleAiCommand(state, 1, "expert", "balanced").gimmick, "mega");
});

test("does not use fallback Dynamax while another configured Dynamax Pokémon is alive", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "PlayerMon",
              stats: { ...pokemon().stats, attack: 220 },
              moves: [
                {
                  id: "megaton",
                  name: "Megaton",
                  type: "Normal",
                  category: "Physical",
                  power: 160,
                  accuracy: 100,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FaintedDynamax",
              gimmicks: { dynamax: true },
            }),
            pokemon({
              name: "LivingDynamax",
              gimmicks: { dynamax: true },
            }),
            pokemon({
              name: "PikachuFallback",
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].team[0].fainted = true;
  state.sides[1].team[0].hp = 0;
  state.sides[1].active = 2;

  assert.equal(chooseSimpleAiCommand(state, 1, "expert", "balanced").gimmick, undefined);
});

test("scores recovery and setup as real AI actions", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "TacticalMon",
              moves: [
                {
                  id: "weak",
                  name: "Weak",
                  type: "Normal",
                  category: "Physical",
                  power: 20,
                  accuracy: 100,
                  pp: 20,
                },
                {
                  id: "recover",
                  name: "Recover",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  heal: [1, 2],
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Opponent" })] },
      ],
    }),
  );
  state.sides[0].team[0].hp = 20;

  const recoveryDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "defensive",
  );
  assert.equal(
    recoveryDecision.command.move,
    2,
    JSON.stringify(recoveryDecision.moveCandidates),
  );
});

test("AI rejects recovery when the same-turn incoming damage exceeds healing", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Recover AI",
          team: [
            pokemon({
              name: "Articuno-Galar",
              types: ["Psychic", "Flying"],
              hp: 125,
              stats: {
                ...pokemon().stats,
                hp: 340,
                specialAttack: 120,
                specialDefence: 100,
                speed: 95,
              },
              moves: [
                {
                  id: "recover",
                  name: "Recover",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  heal: [1, 2],
                },
                {
                  id: "freezingglare",
                  name: "Freezing Glare",
                  type: "Psychic",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Opponent",
          team: [
            pokemon({
              name: "Magnezone",
              types: ["Electric", "Steel"],
              stats: {
                ...pokemon().stats,
                specialAttack: 400,
                speed: 60,
              },
              moves: [
                {
                  id: "thunderbolt",
                  name: "Thunderbolt",
                  type: "Electric",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].hp = 125;

  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const recover = decision.moveCandidates.find(
    (candidate) => candidate.id === "recover",
  );

  assert.ok(
    recover.recoveryExpectedIncomingDamage > recover.recoveryAmount,
    JSON.stringify(recover),
  );
  assert.ok(recover.recoveryNetHpChange < 0);
  assert.notEqual(decision.command.move, 1);
});

test("AI counts the use turn and two sleeping turns when evaluating Rest", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Rest AI",
          team: [
            pokemon({
              name: "Snorlax",
              hp: 224,
              stats: {
                ...pokemon().stats,
                hp: 524,
                attack: 150,
                specialDefence: 100,
                speed: 30,
              },
              moves: [
                {
                  id: "rest",
                  name: "Rest",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
                {
                  id: "bodyslam",
                  name: "Body Slam",
                  type: "Normal",
                  category: "Physical",
                  power: 85,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "Opponent",
          team: [
            pokemon({
              name: "Special Attacker",
              stats: {
                ...pokemon().stats,
                specialAttack: 400,
                speed: 100,
              },
              moves: [
                {
                  id: "psychic",
                  name: "Psychic",
                  type: "Psychic",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].hp = 224;

  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const rest = decision.moveCandidates.find(
    (candidate) => candidate.id === "rest",
  );

  assert.equal(rest.recoveryExposureTurns, 3);
  assert.ok(rest.recoveryExpectedIncomingDamage > rest.recoveryAmount);
  assert.ok(rest.recoveryNetHpChange < 0);
  assert.notEqual(decision.command.move, 1);
});

test("AI avoids non-urgent Rest into an opposing setup sweeper", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Snorlax",
              types: ["Normal"],
              hp: 459,
              stats: {
                ...pokemon().stats,
                hp: 524,
                attack: 130,
                defence: 95,
                specialDefence: 160,
                speed: 30,
              },
              moves: [
                {
                  id: "rest",
                  name: "Rest",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
                {
                  id: "curse",
                  name: "Curse",
                  type: "Ghost",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  boosts: { atk: 1, def: 1, spe: -1 },
                  selfBoosts: { attack: 1, defence: 1, speed: -1 },
                },
                {
                  id: "heatstamp",
                  name: "Heat Stamp",
                  type: "Fire",
                  category: "Physical",
                  power: 80,
                  accuracy: true,
                  pp: 10,
                },
                {
                  id: "hammerarm",
                  name: "Hammer Arm",
                  type: "Fighting",
                  category: "Physical",
                  power: 100,
                  accuracy: 90,
                  pp: 10,
                  selfBoosts: { speed: -1 },
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Calyrex-Shadow",
              types: ["Psychic", "Ghost"],
              stats: {
                ...pokemon().stats,
                hp: 342,
                defence: 100,
                specialAttack: 220,
                specialDefence: 120,
                speed: 200,
              },
              boosts: { specialAttack: 1 },
              moves: [
                {
                  id: "nastyplot",
                  name: "Nasty Plot",
                  type: "Dark",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  boosts: { spa: 2 },
                  selfBoosts: { specialAttack: 2 },
                },
                {
                  id: "astralbarrage",
                  name: "Astral Barrage",
                  type: "Ghost",
                  category: "Special",
                  power: 120,
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  assert.notEqual(chooseSimpleAiCommand(state, 0, "expert", "balanced").move, 1);
});

test("AI trace marks the same top-scored move used for command selection", () => {
  const state = runSimpleBattle(
    setup({
      sides: [
        {
          name: "AI",
          team: [
            pokemon({
              name: "SaltSetter",
              stats: { ...pokemon().stats, hp: 400, attack: 120, defence: 160 },
              moves: [
                {
                  id: "saltcure",
                  name: "Salt Cure",
                  type: "Rock",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 15,
                },
                {
                  id: "earthquake",
                  name: "Earthquake",
                  type: "Ground",
                  category: "Physical",
                  power: 100,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Target",
          team: [
            pokemon({
              name: "Porygon2",
              stats: { ...pokemon().stats, hp: 374, defence: 130, speed: 40 },
              moves: [
                {
                  id: "weakhit",
                  name: "Weak Hit",
                  type: "Normal",
                  category: "Physical",
                  power: 35,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
      ],
    }),
    { maxTurns: 1, difficulty: "expert" },
  );
  const trace = state.aiTrace.find((entry) => entry.side === 0);
  const selected = trace.candidates.find((candidate) => candidate.selected);
  const topScore = Math.max(...trace.candidates.map((candidate) => candidate.score));

  assert.equal(selected.score, topScore);
});

test("applies weather and terrain damage modifiers", () => {
  const attacker = pokemon({ types: ["Water"] });
  const defender = pokemon({ types: ["Normal"] });
  const waterMove = {
    type: "Water",
    category: "Special",
    power: 80,
  };
  const normal = calculateDamageRange(attacker, defender, waterMove);
  const rainState = createSimpleBattle(setup());
  rainState.field.weather = { id: "raindance", turns: 5 };
  const rain = calculateDamageRange(attacker, defender, waterMove, {
    state: rainState,
    attackerSide: 0,
    defenderSide: 1,
  });
  rainState.field.terrain = { id: "electricterrain", turns: 5 };
  const electric = calculateDamageRange(
    pokemon({ types: ["Electric"] }),
    defender,
    { ...waterMove, type: "Electric" },
    { state: rainState, attackerSide: 0, defenderSide: 1 },
  );

  assert.equal(rain.fieldModifier, 1.5);
  assert.ok(rain.maximum > normal.maximum);
  assert.equal(electric.fieldModifier, 1.3);
});

test("applies native callbacks for weather and screen utility moves", () => {
  const weatherBallState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "WeatherUser",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "weatherball",
                  name: "Weather Ball",
                  type: "Normal",
                  category: "Special",
                  power: 50,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FireTarget",
              types: ["Fire"],
              stats: { ...pokemon().stats, hp: 300, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  weatherBallState.field.weather = { id: "raindance", turns: 5 };
  const weatherBallHit = resolveSimpleTurn(weatherBallState, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(
    weatherBallHit.events.find(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "FireTarget" &&
        event.move === "Weather Ball",
    )?.effectiveness,
    2,
  );

  const blizzardState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "SnowCaster",
              types: ["Ice"],
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "blizzard",
                  name: "Blizzard",
                  type: "Ice",
                  category: "Special",
                  power: 110,
                  accuracy: 1,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "SnowTarget" })] },
      ],
    }),
  );
  blizzardState.field.weather = { id: "snow", turns: 5 };
  const blizzardHit = resolveSimpleTurn(blizzardState, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.ok(
    blizzardHit.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "SnowTarget" &&
        event.move === "Blizzard" &&
        event.damage > 0,
    ),
  );

  const clearSmogState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "SmogUser",
              types: ["Poison"],
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "clearsmog",
                  name: "Clear Smog",
                  type: "Poison",
                  category: "Special",
                  power: 50,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "BoostedTarget" })] },
      ],
    }),
  );
  clearSmogState.sides[1].team[0].boosts.attack = 4;
  clearSmogState.sides[1].team[0].boosts.defence = -2;
  const cleared = resolveSimpleTurn(clearSmogState, [{ move: 1 }, { move: 1 }]);
  assert.equal(cleared.sides[1].team[0].boosts.attack, 0);
  assert.equal(cleared.sides[1].team[0].boosts.defence, 0);
  assert.ok(
    cleared.events.some(
      (event) =>
        event.type === "stat_reset" &&
        event.pokemon === "BoostedTarget" &&
        event.source === "Clear Smog",
    ),
  );

  const brickBreakState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Breaker",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "brickbreak",
                  name: "Brick Break",
                  type: "Fighting",
                  category: "Physical",
                  power: 75,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "ScreenTarget" })] },
      ],
    }),
  );
  brickBreakState.sides[1].conditions.reflect = { id: "reflect", turns: 5 };
  brickBreakState.sides[1].conditions.lightscreen = {
    id: "lightscreen",
    turns: 5,
  };
  const broken = resolveSimpleTurn(brickBreakState, [{ move: 1 }, { move: 1 }]);
  assert.equal(broken.sides[1].conditions.reflect, undefined);
  assert.equal(broken.sides[1].conditions.lightscreen, undefined);
  assert.equal(
    broken.events.filter(
      (event) =>
        event.type === "side_condition_end" &&
        event.source === "Brick Break",
    ).length,
    2,
  );
});

test("applies native callbacks for item, weather accuracy, type, and boost utility moves", () => {
  const knockOffState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Knocker",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "knockoff",
                  name: "Knock Off",
                  type: "Dark",
                  category: "Physical",
                  power: 65,
                  accuracy: true,
                  pp: 20,
                  dynamicPower: true,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "ItemHolder",
              item: "leftovers",
              stats: { ...pokemon().stats, hp: 300, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  const knocked = resolveSimpleTurn(knockOffState, [{ move: 1 }, { move: 1 }]);
  assert.equal(knocked.sides[1].team[0].item, "");
  assert.equal(
    knocked.events.find((event) => event.type === "dynamic_power")?.power,
    97,
  );
  assert.equal(
    knocked.events.find((event) => event.type === "item_removed")?.item,
    "leftovers",
  );

  const thunderState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "RainCaster",
              types: ["Electric"],
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "thunder",
                  name: "Thunder",
                  type: "Electric",
                  category: "Special",
                  power: 110,
                  accuracy: 1,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "RainTarget" })] },
      ],
    }),
  );
  thunderState.field.weather = { id: "raindance", turns: 5 };
  const thunderHit = resolveSimpleTurn(thunderState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    thunderHit.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "RainTarget" &&
        event.move === "Thunder",
    ),
  );

  const freezeDry = calculateDamageRange(
    pokemon({ types: ["Ice"] }),
    pokemon({ types: ["Water"] }),
    {
      id: "freezedry",
      name: "Freeze-Dry",
      type: "Ice",
      category: "Special",
      power: 70,
    },
  );
  assert.equal(freezeDry.effectiveness, 2);

  const psychicFangsState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "FangUser",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "psychicfangs",
                  name: "Psychic Fangs",
                  type: "Psychic",
                  category: "Physical",
                  power: 85,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "ScreenTarget" })] },
      ],
    }),
  );
  psychicFangsState.sides[1].conditions.auroraveil = {
    id: "auroraveil",
    turns: 5,
  };
  const fangs = resolveSimpleTurn(psychicFangsState, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(fangs.sides[1].conditions.auroraveil, undefined);
  assert.equal(
    fangs.events.find((event) => event.type === "side_condition_end")?.source,
    "Psychic Fangs",
  );

  const hazeState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Hazer",
              boosts: { attack: 2, defence: -1 },
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "haze",
                  name: "Haze",
                  type: "Ice",
                  category: "Status",
                  accuracy: true,
                  pp: 30,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "BoostedTarget",
              boosts: { specialAttack: 3, speed: 2 },
            }),
          ],
        },
      ],
    }),
  );
  const hazed = resolveSimpleTurn(hazeState, [{ move: 1 }, { move: 1 }]);
  assert.equal(hazed.sides[0].team[0].boosts.attack, 0);
  assert.equal(hazed.sides[0].team[0].boosts.defence, 0);
  assert.equal(hazed.sides[1].team[0].boosts.specialAttack, 0);
  assert.equal(hazed.sides[1].team[0].boosts.speed, 0);

  const growthState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "SunGrower",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "growth",
                  name: "Growth",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  target: "self",
                  boosts: { attack: 1, specialAttack: 1 },
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );
  growthState.field.weather = { id: "sunnyday", turns: 5 };
  const grown = resolveSimpleTurn(growthState, [{ move: 1 }, { move: 1 }]);
  assert.equal(grown.sides[0].team[0].boosts.attack, 2);
  assert.equal(grown.sides[0].team[0].boosts.specialAttack, 2);
});

test("reverses equal-priority speed order while Trick Room is active", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "FastMon",
              stats: { ...pokemon().stats, speed: 180 },
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "SlowMon",
              stats: { ...pokemon().stats, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  state.field.pseudoWeather.trickroom = { id: "trickroom", turns: 5 };
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const firstMove = next.events.find(
    (event) => event.type === "move" && event.turn === 1,
  );

  assert.equal(firstMove.pokemon, "SlowMon");
});

test("starts timed field effects and blocks sleep on Electric Terrain", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "TerrainSetter",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "electricterrain",
                  name: "Electric Terrain",
                  type: "Electric",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  terrain: "ElectricTerrain",
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Sleeper",
              stats: { ...pokemon().stats, speed: 40 },
              moves: [
                {
                  id: "hypnosis",
                  name: "Hypnosis",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  status: "slp",
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.field.terrain.id, "electricterrain");
  assert.equal(next.field.terrain.turns, 4);
  assert.equal(next.sides[0].team[0].status, "");
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "field_start" &&
        event.effect === "electricterrain",
    ),
  );
});

test("sets entry hazards and applies them to the next switch-in", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "HazardSetter",
              stats: { ...pokemon().stats, speed: 150 },
              moves: [
                {
                  id: "stealthrock",
                  name: "Stealth Rock",
                  type: "Rock",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  target: "foeSide",
                  sideCondition: "stealthrock",
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({ name: "Lead" }),
            pokemon({
              name: "FireFlying",
              types: ["Fire", "Flying"],
              stats: { ...pokemon().stats, hp: 160 },
            }),
          ],
        },
      ],
    }),
  );
  const hazardsSet = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const switched = resolveSimpleTurn(hazardsSet, [
    { move: 1 },
    { switch: 2 },
  ]);

  assert.equal(switched.sides[1].team[1].hp, 80);
  assert.ok(
    switched.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "FireFlying" &&
        event.source === "stealthrock",
    ),
  );
});

test("AI avoids selecting already maxed entry hazards", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "AI",
          team: [
            pokemon({
              name: "HazardSetter",
              stats: { ...pokemon().stats, speed: 150, attack: 130 },
              moves: [
                {
                  id: "stealthrock",
                  name: "Stealth Rock",
                  type: "Rock",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  target: "foeSide",
                  sideCondition: "stealthrock",
                },
                {
                  id: "rockslide",
                  name: "Rock Slide",
                  type: "Rock",
                  category: "Physical",
                  power: 75,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Player",
          team: [pokemon({ name: "Target" })],
        },
      ],
    }),
  );
  state.sides[1].conditions.stealthrock = { id: "stealthrock", layers: 1 };

  const command = chooseSimpleAiCommand(state, 0, "expert", "hazard");

  assert.equal(command.move, 2);
});

test("AI uses Salt Cure before Stealth Rock into a likely first-turn setup threat", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Garganacl",
            types: ["Rock"],
            stats: { ...pokemon().stats, hp: 404, attack: 110, defence: 180, speed: 80 },
            moves: [
              {
                id: "stealthrock",
                name: "Stealth Rock",
                type: "Rock",
                category: "Status",
                accuracy: true,
                pp: 20,
                sideCondition: "stealthrock",
              },
              {
                id: "saltcure",
                name: "Salt Cure",
                type: "Rock",
                category: "Physical",
                power: 40,
                accuracy: 100,
                pp: 15,
              },
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
      {
        name: "SetupThreat",
        team: [
          pokemon({
            name: "SetupSweeper",
            types: ["Dragon"],
            stats: {
              ...pokemon().stats,
              hp: 420,
              attack: 170,
              defence: 180,
              speed: 120,
            },
            moves: [
              {
                id: "swordsdance",
                name: "Swords Dance",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 20,
                selfBoosts: { attack: 2 },
              },
              {
                id: "slash",
                name: "Slash",
                type: "Normal",
                category: "Physical",
                power: 70,
                accuracy: 100,
                pp: 20,
              },
            ],
          }),
        ],
      },
    ],
  });

  const state = createSimpleBattle(scenario);
  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");
  assert.equal(command.move, 2);

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const trace = battle.aiTrace.find((entry) => entry.side === 0);
  const saltCure = trace.candidates.find((candidate) => candidate.id === "saltcure");
  const stealthRock = trace.candidates.find((candidate) => candidate.id === "stealthrock");
  assert.equal(saltCure.selected, true);
  assert.ok(saltCure.opponentSetupFirstTurnLikelihood >= 0.65);
  assert.equal(stealthRock.setupThreatEvaluation.opponentCanSetup, true);
  assert.ok(stealthRock.opponentSetupSweepRisk >= 0.4);
  assert.ok(
    stealthRock.reasons.some(
      (reason) => reason.code === "rule.setup_threat.free_hazard_turn",
    ),
  );
  assert.ok(saltCure.score > stealthRock.score);
});

test("AI keeps Stealth Rock ahead of Salt Cure into Trick Room support", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Garganacl",
            types: ["Rock"],
            stats: { ...pokemon().stats, hp: 404, attack: 110, defence: 180, speed: 80 },
            moves: [
              {
                id: "stealthrock",
                name: "Stealth Rock",
                type: "Rock",
                category: "Status",
                accuracy: true,
                pp: 20,
                sideCondition: "stealthrock",
              },
              {
                id: "saltcure",
                name: "Salt Cure",
                type: "Rock",
                category: "Physical",
                power: 40,
                accuracy: 100,
                pp: 15,
              },
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
      {
        name: "TrickRoomSupport",
        team: [
          pokemon({
            name: "Porygon2",
            item: "eviolite",
            types: ["Normal"],
            stats: {
              ...pokemon().stats,
              hp: 374,
              defence: 130,
              specialAttack: 120,
              speed: 40,
            },
            moves: [
              {
                id: "icebeam",
                name: "Ice Beam",
                type: "Ice",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
              {
                id: "thunderbolt",
                name: "Thunderbolt",
                type: "Electric",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 15,
              },
              {
                id: "trickroom",
                name: "Trick Room",
                type: "Psychic",
                category: "Status",
                accuracy: true,
                priority: -7,
                pp: 5,
                pseudoWeather: "trickroom",
              },
            ],
          }),
          pokemon({ name: "Bench1" }),
          pokemon({ name: "Bench2" }),
          pokemon({ name: "Bench3" }),
          pokemon({ name: "Bench4" }),
          pokemon({ name: "Bench5" }),
        ],
      },
    ],
  });

  const state = createSimpleBattle(scenario);
  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");
  assert.equal(command.move, 1);

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const trace = battle.aiTrace.find((entry) => entry.side === 0);
  const saltCure = trace.candidates.find((candidate) => candidate.id === "saltcure");
  const stealthRock = trace.candidates.find((candidate) => candidate.id === "stealthrock");
  assert.equal(stealthRock.selected, true);
  assert.equal(saltCure.opponentSetupMoveCount, 0);
  assert.equal(saltCure.opponentSetupFirstTurnLikelihood, 0);
  assert.ok(stealthRock.score > saltCure.score);
});

test("AI values Trick Room when slow attackers can sweep after the setter survives", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "AI",
          team: [
            pokemon({
              name: "Porygon2",
              stats: {
                ...pokemon().stats,
                hp: 220,
                defence: 140,
                specialDefence: 140,
                specialAttack: 75,
                speed: 55,
              },
              moves: [
                {
                  id: "trickroom",
                  name: "Trick Room",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  priority: -7,
                  pp: 5,
                  target: "all",
                  pseudoWeather: "trickroom",
                },
                {
                  id: "icebeam",
                  name: "Ice Beam",
                  type: "Ice",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
            pokemon({
              name: "Mawile",
              species: "Mawile",
              ability: "hugepower",
              stats: { ...pokemon().stats, attack: 120, speed: 50 },
              moves: [
                {
                  id: "playrough",
                  name: "Play Rough",
                  type: "Fairy",
                  category: "Physical",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Player",
          team: [
            pokemon({
              name: "FastTarget",
              stats: { ...pokemon().stats, hp: 280, attack: 80, speed: 170 },
              moves: [
                {
                  id: "quickhit",
                  name: "Quick Hit",
                  type: "Normal",
                  category: "Physical",
                  power: 45,
                  accuracy: 100,
                  pp: 30,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");

  assert.equal(command.move, 1);
});

test("automatic replacement prefers Pokemon that benefit from active weather", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Attacker",
              stats: { ...pokemon().stats, attack: 220, speed: 200 },
              moves: [
                {
                  id: "knockout",
                  name: "Knock Out",
                  type: "Normal",
                  category: "Physical",
                  power: 160,
                  accuracy: 100,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "FaintedLead",
              stats: { ...pokemon().stats, hp: 60, defence: 40, speed: 40 },
            }),
            pokemon({
              name: "RainSweeper",
              ability: "swiftswim",
              types: ["Water"],
              stats: { ...pokemon().stats, attack: 85, speed: 80 },
              moves: [
                {
                  id: "aquajetless",
                  name: "Water Hit",
                  type: "Water",
                  category: "Physical",
                  power: 45,
                  accuracy: 100,
                  pp: 20,
                },
              ],
            }),
            pokemon({
              name: "NeutralPower",
              stats: { ...pokemon().stats, attack: 110, speed: 90 },
              moves: [
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: 100,
                  pp: 20,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.field.weather = { id: "raindance", turns: 5 };

  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[1].active, 1);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "switch" &&
        event.side === 1 &&
        event.pokemon === "RainSweeper" &&
        event.automatic === true,
    ),
  );
});

test("resolves fixed multi-hit moves and guaranteed critical hits per hit", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "MultiAttacker",
              stats: { ...pokemon().stats, attack: 80, speed: 150 },
              moves: [
                {
                  id: "surgingstrikes",
                  name: "Surging Strikes",
                  type: "Water",
                  category: "Physical",
                  power: 25,
                  accuracy: true,
                  pp: 5,
                  multihit: [3, 3],
                  willCrit: true,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "MultiTarget",
              stats: { ...pokemon().stats, hp: 400, speed: 20 },
            }),
          ],
        },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const damageEvents = next.events.filter(
    (event) =>
      event.type === "damage" &&
      event.pokemon === "MultiTarget" &&
      event.move === "Surging Strikes",
  );

  assert.equal(damageEvents.length, 3);
  assert.ok(damageEvents.every((event) => event.critical));
  assert.equal(
    next.events.find((event) => event.type === "multi_hit")?.hits,
    3,
  );
});

test("applies Loaded Dice to variable multi-hit moves", () => {
  const base = setup({
    seed: 42,
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "DiceUser",
            item: "loadeddice",
            stats: { ...pokemon().stats, attack: 80, speed: 150 },
            moves: [
              {
                id: "bulletseed",
                name: "Bullet Seed",
                type: "Grass",
                category: "Physical",
                power: 25,
                accuracy: true,
                pp: 30,
                multihit: [2, 5],
              },
            ],
          }),
        ],
      },
      {
        name: "AI",
        team: [
          pokemon({
            name: "DiceTarget",
            stats: { ...pokemon().stats, hp: 800, defence: 160, speed: 20 },
          }),
        ],
      },
    ],
  });
  for (let seed = 1; seed <= 12; seed += 1) {
    const state = resolveSimpleTurn(
      createSimpleBattle({ ...base, seed }),
      [{ move: 1 }, { move: 1 }],
    );
    const hits = state.events.find((event) => event.type === "multi_hit")?.hits;
    assert.ok(hits === 4 || hits === 5, `Loaded Dice should roll 4-5 hits, got ${hits}`);
  }
});

test("does not treat immune zero-damage hits as landed damage", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "NormalAttacker",
                stats: { ...pokemon().stats, attack: 120, speed: 150 },
                moves: [
                  {
                    id: "doubleslap",
                    name: "Double Slap",
                    type: "Normal",
                    category: "Physical",
                    power: 15,
                    accuracy: true,
                    pp: 10,
                    multihit: [2, 5],
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "GhostTarget",
                types: ["Ghost"],
                stats: { ...pokemon().stats, hp: 200, speed: 20 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );

  assert.equal(state.sides[1].team[0].hp, 200);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "GhostTarget" &&
        event.damage === 0 &&
        event.effectiveness === 0,
    ),
  );
  assert.equal(
    state.events.some((event) => event.type === "multi_hit"),
    false,
  );
});

test("reserves and consumes each side gimmick resource explicitly", () => {
  let state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "PlayerMon",
              item: "testite",
              gimmicks: {
                megaStone: {
                  item: "testite",
                  evolves: "testmon",
                  form: "Testmon-Mega",
                },
              },
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "AiMon" })] },
      ],
    }),
  );
  assert.deepEqual(state.sides[0].gimmickResources, {
    mega: "available",
    zmove: "available",
    dynamax: "available",
    terastallize: "available",
  });

  state = resolveSimpleTurn(state, [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);
  assert.equal(state.sides[0].gimmickResources.mega, "consumed");
  assert.equal(state.sides[0].gimmickResources.dynamax, "available");
  assert.equal(state.sides[0].usedGimmicks.mega, true);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_reserved" &&
        event.side === 0 &&
        event.gimmick === "mega",
    ),
  );
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_consumed" &&
        event.side === 0 &&
        event.gimmick === "mega",
    ),
  );

  const speedAfterMega = state.sides[0].team[0].stats.speed;
  state = resolveSimpleTurn(state, [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);
  assert.equal(state.sides[0].team[0].stats.speed, speedAfterMega);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.gimmick === "mega" &&
        event.reason === "resource_unavailable",
    ),
  );
});

test("recalculates move order after a pre-move Mega activation", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "MegaCandidate",
              item: "testite",
              gimmicks: {
                megaStone: {
                  item: "testite",
                  evolves: "testmon",
                  form: "Testmon-Mega",
                },
              },
              stats: { ...pokemon().stats, speed: 100 },
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "InitiallyFaster",
              stats: { ...pokemon().stats, speed: 105 },
            }),
          ],
        },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);

  assert.deepEqual(
    next.events
      .filter((event) => event.turn === 1 && event.type === "move")
      .map((event) => event.pokemon),
    ["Testmon-Mega", "InitiallyFaster"],
  );
  assert.equal(next.sides[0].team[0].stats.speed, 110);
});

test("applies Mega Evolution type changes before damage is resolved", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Charizard",
              types: ["Fire", "Flying"],
              item: "charizarditex",
              gimmicks: {
                megaStone: {
                  item: "charizarditex",
                  evolves: "testmon",
                  form: "Charizard-Mega-X",
                  types: ["Fire", "Dragon"],
                },
              },
              moves: [
                {
                  id: "dragonclaw",
                  name: "Dragon Claw",
                  type: "Dragon",
                  category: "Physical",
                  power: 80,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "NeutralTarget",
              types: ["Normal"],
              stats: { ...pokemon().stats, hp: 240, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  const next = resolveSimpleTurn(state, [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);
  const nonMegaRange = calculateDamageRange(
    pokemon({
      name: "Charizard",
      types: ["Fire", "Flying"],
      moves: state.sides[0].team[0].moves,
    }),
    state.sides[1].team[0],
    state.sides[0].team[0].moves[0],
  );
  const megaRange = calculateDamageRange(
    next.sides[0].team[0],
    next.sides[1].team[0],
    next.sides[0].team[0].moves[0],
  );

  assert.deepEqual(next.sides[0].team[0].types, ["Fire", "Dragon"]);
  assert.deepEqual(next.sides[0].team[0].originalTypes, ["Fire", "Dragon"]);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "gimmick_activated" &&
        event.megaForm === "Charizard-Mega-X",
    ),
  );
  assert.equal(nonMegaRange.stab, 1);
  assert.equal(megaRange.stab, 1.5);
  assert.ok(megaRange.maximum > nonMegaRange.maximum);
});

test("releases a reserved Z-Power resource when the user cannot act", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "PlayerMon",
              item: "normaliumz",
              gimmicks: {
                zCrystal: {
                  item: "normaliumz",
                  moveType: "Normal",
                },
              },
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "AiMon" })] },
      ],
    }),
  );
  state.sides[0].team[0].status = "slp";
  state.sides[0].team[0].statusTurns = 1;

  const next = resolveSimpleTurn(state, [
    { move: 1, gimmick: "zmove" },
    { move: 1 },
  ]);

  assert.equal(next.sides[0].gimmickResources.zmove, "available");
  assert.equal(next.sides[0].usedGimmicks.zmove, false);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "gimmick_released" &&
        event.gimmick === "zmove" &&
        event.reason === "action_not_executed",
    ),
  );
  assert.equal(
    next.events.some(
      (event) => event.type === "gimmick" && event.gimmick === "zmove",
    ),
    false,
  );
});

test("ends Dynamax immediately when the active Pokémon switches out", () => {
  let state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "DynamaxUser",
              gimmicks: { canDynamax: true, gigantamax: true },
            }),
            pokemon({ name: "Replacement" }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "AiMon" })] },
      ],
    }),
  );
  state = resolveSimpleTurn(state, [
    { move: 1, gimmick: "gigantamax" },
    { move: 1 },
  ]);
  assert.equal(state.sides[0].team[0].stats.hp, 240);
  assert.equal(state.sides[0].team[0].dynamaxTurns, 2);
  assert.equal(state.sides[0].team[0].dynamaxMode, "gigantamax");

  state = resolveSimpleTurn(state, [{ switch: 2 }, { move: 1 }]);
  const previousActive = state.sides[0].team[0];
  assert.equal(previousActive.stats.hp, 120);
  assert.equal(previousActive.dynamaxTurns, 0);
  assert.equal(previousActive.dynamaxMode, null);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "dynamax_end" &&
        event.pokemon === "DynamaxUser" &&
        event.reason === "switch",
    ),
  );
});

test("Dynamax Max Move effects replace the source move side effects", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                id: "urshifurapidstrike",
                name: "Urshifu-Rapid-Strike",
                types: ["Fighting", "Water"],
                stats: { ...pokemon().stats, attack: 150, speed: 120 },
                gimmicks: { canDynamax: true, gigantamax: true },
                moves: [
                  {
                    id: "closecombat",
                    name: "Close Combat",
                    type: "Fighting",
                    category: "Physical",
                    power: 120,
                    accuracy: 100,
                    pp: 5,
                    selfBoosts: { defence: -1, specialDefence: -1 },
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Target",
                stats: { ...pokemon().stats, hp: 240, defence: 130, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );

  const user = state.sides[0].team[0];
  assert.equal(user.boosts.attack, 1);
  assert.equal(user.boosts.defence, 0);
  assert.equal(user.boosts.specialDefence, 0);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "move" &&
        event.side === 0 &&
        event.move === "Max Knuckle",
    ),
  );
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "stat_change" &&
        event.pokemon === "Urshifu-Rapid-Strike" &&
        event.stat === "atk" &&
        event.amount === 1 &&
        event.source === "Max Knuckle",
    ),
  );
  assert.equal(
    state.events.some(
      (event) =>
        event.type === "stat_change" &&
        event.pokemon === "Urshifu-Rapid-Strike" &&
        ["def", "spd"].includes(event.stat) &&
        event.amount < 0,
    ),
    false,
  );
});

test("damaging Max Moves apply weather, terrain, and guaranteed stat effects", () => {
  const cases = [
    {
      source: {
        id: "flamethrower",
        name: "Flamethrower",
        type: "Fire",
        category: "Special",
        power: 90,
        accuracy: 100,
        pp: 15,
      },
      fieldKind: "weather",
      effect: "sunnyday",
      maxMove: "Max Flare",
    },
    {
      source: {
        id: "surf",
        name: "Surf",
        type: "Water",
        category: "Special",
        power: 90,
        accuracy: 100,
        pp: 15,
      },
      fieldKind: "weather",
      effect: "raindance",
      maxMove: "Max Geyser",
    },
    {
      source: {
        id: "thunderbolt",
        name: "Thunderbolt",
        type: "Electric",
        category: "Special",
        power: 90,
        accuracy: 100,
        pp: 15,
      },
      fieldKind: "terrain",
      effect: "electricterrain",
      maxMove: "Max Lightning",
    },
    {
      source: {
        id: "energyball",
        name: "Energy Ball",
        type: "Grass",
        category: "Special",
        power: 90,
        accuracy: 100,
        pp: 10,
      },
      fieldKind: "terrain",
      effect: "grassyterrain",
      maxMove: "Max Overgrowth",
    },
  ];

  for (const entry of cases) {
    const state = resolveSimpleTurn(
      createSimpleBattle(
        setup({
          sides: [
            {
              name: "Player",
              team: [
                pokemon({
                  name: "DynamaxUser",
                  types: [entry.source.type],
                  stats: { ...pokemon().stats, specialAttack: 140, speed: 120 },
                  gimmicks: { canDynamax: true },
                  moves: [entry.source],
                }),
              ],
            },
            {
              name: "AI",
              team: [
                pokemon({
                  name: "Target",
                  stats: {
                    ...pokemon().stats,
                    hp: 500,
                    specialDefence: 140,
                    speed: 40,
                  },
                }),
              ],
            },
          ],
        }),
      ),
      [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
    );

    assert.equal(state.field[entry.fieldKind]?.id, entry.effect);
    assert.ok(
      state.events.some(
        (event) =>
          event.type === "field_start" &&
          event.fieldKind === entry.fieldKind &&
          event.effect === entry.effect &&
          event.source === entry.maxMove,
      ),
    );
  }

  const maxStrikeState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "DynamaxUser",
                gimmicks: { canDynamax: true },
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Target",
                stats: { ...pokemon().stats, hp: 500, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );
  assert.equal(maxStrikeState.sides[1].team[0].boosts.speed, -1);
});

test("a Max Move does not create its field effect when the target is immune", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "DynamaxUser",
                types: ["Electric"],
                stats: { ...pokemon().stats, specialAttack: 140, speed: 120 },
                gimmicks: { canDynamax: true },
                moves: [
                  {
                    id: "thunderbolt",
                    name: "Thunderbolt",
                    type: "Electric",
                    category: "Special",
                    power: 90,
                    accuracy: 100,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "GroundTarget",
                types: ["Ground"],
                stats: { ...pokemon().stats, hp: 500, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );

  assert.equal(state.field.terrain, null);
  assert.equal(
    state.events.some(
      (event) =>
        event.type === "field_start" && event.effect === "electricterrain",
    ),
    false,
  );
});

test("Max Darkness does not inherit Sucker Punch priority", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "SlowDynamax",
                types: ["Dark"],
                stats: { ...pokemon().stats, speed: 40 },
                gimmicks: { canDynamax: true },
                moves: [
                  {
                    id: "suckerpunch",
                    name: "Sucker Punch",
                    type: "Dark",
                    category: "Physical",
                    power: 70,
                    accuracy: 100,
                    priority: 1,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "FastTarget",
                stats: { ...pokemon().stats, hp: 240, speed: 120 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );
  const moveEvents = state.events.filter(
    (event) => event.turn === 1 && event.type === "move",
  );

  assert.deepEqual(
    moveEvents.map((event) => event.pokemon),
    ["FastTarget", "SlowDynamax"],
  );
  assert.equal(moveEvents[1].move, "Max Darkness");
});

test("Max Guard keeps its own priority instead of the source status move", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "SlowDynamax",
                stats: { ...pokemon().stats, speed: 40 },
                gimmicks: { canDynamax: true },
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    priority: 0,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "FastTarget",
                stats: { ...pokemon().stats, speed: 120 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );
  const moveEvents = state.events.filter(
    (event) => event.turn === 1 && event.type === "move",
  );

  assert.equal(moveEvents[0].pokemon, "SlowDynamax");
  assert.equal(moveEvents[0].move, "Max Guard");
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "move_blocked" &&
        event.pokemon === "SlowDynamax" &&
        event.move === "Tackle" &&
        event.source === "Max Guard",
    ),
  );
});

test("does not apply Max Move boosts when the attack has no effect", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                id: "urshifurapidstrike",
                name: "Urshifu-Rapid-Strike",
                types: ["Fighting", "Water"],
                stats: { ...pokemon().stats, attack: 150, speed: 120 },
                gimmicks: { canDynamax: true },
                moves: [
                  {
                    id: "closecombat",
                    name: "Close Combat",
                    type: "Fighting",
                    category: "Physical",
                    power: 120,
                    accuracy: 100,
                    pp: 5,
                    selfBoosts: { defence: -1, specialDefence: -1 },
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "GhostTarget",
                types: ["Ghost", "Psychic"],
                stats: { ...pokemon().stats, hp: 240, defence: 130, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );

  const user = state.sides[0].team[0];
  assert.equal(user.boosts.attack, 0);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "damage" &&
        event.move === "Max Knuckle" &&
        event.effectiveness === 0,
    ),
  );
  assert.equal(
    state.events.some(
      (event) =>
        event.type === "stat_change" &&
        event.pokemon === "Urshifu-Rapid-Strike" &&
        event.source === "Max Knuckle",
    ),
    false,
  );
});

test("rejects Mega Evolution and Z-Power without a compatible held item", () => {
  let state = resolveSimpleTurn(createSimpleBattle(setup()), [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.reason === "mega_stone_required",
    ),
  );
  assert.equal(state.sides[0].gimmickResources.mega, "available");

  state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                item: "firiumz",
                gimmicks: {
                  zCrystal: { item: "firiumz", moveType: "Fire" },
                },
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "AiMon" })] },
        ],
      }),
    ),
    [{ move: 1, gimmick: "zmove" }, { move: 1 }],
  );
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.reason === "z_crystal_incompatible",
    ),
  );
  assert.equal(state.sides[0].gimmickResources.zmove, "available");
});

test("blocks Mega Evolution and Dynamax from stacking", () => {
  const battleSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            id: "mawile",
            name: "Mawile",
            item: "mawilite",
            gimmicks: {
              forceDynamax: true,
              megaStone: {
                item: "mawilite",
                evolves: "mawile",
                form: "Mega Mawile",
                ability: "hugepower",
              },
            },
            moves: [
              {
                id: "ironhead",
                name: "Iron Head",
                type: "Steel",
                category: "Physical",
                power: 80,
                accuracy: true,
                pp: 15,
              },
            ],
          }),
        ],
      },
      {
        name: "AI",
        team: [
          pokemon({
            name: "Dummy",
            moves: [
              {
                id: "splash",
                name: "Splash",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 40,
              },
            ],
          }),
        ],
      },
    ],
  });

  const megaState = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);
  assert.equal(megaState.sides[0].team[0].megaEvolved, true);
  assert.equal(
    chooseSimpleAiCommand(megaState, 0, "expert", "balanced").gimmick,
    undefined,
  );

  const dynamaxAttempt = resolveSimpleTurn(megaState, [
    { move: 1, gimmick: "dynamax" },
    { move: 1 },
  ]);
  assert.equal(dynamaxAttempt.sides[0].team[0].dynamaxTurns, 0);
  assert.ok(
    dynamaxAttempt.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.gimmick === "dynamax" &&
        event.reason === "dynamax_blocked_by_mega",
    ),
  );

  const dynamaxState = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1, gimmick: "dynamax" },
    { move: 1 },
  ]);
  assert.equal(dynamaxState.sides[0].team[0].dynamaxTurns, 2);
  const megaAttempt = resolveSimpleTurn(dynamaxState, [
    { move: 1, gimmick: "mega" },
    { move: 1 },
  ]);
  assert.notEqual(megaAttempt.sides[0].team[0].megaEvolved, true);
  assert.ok(
    megaAttempt.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.gimmick === "mega" &&
        event.reason === "mega_blocked_by_dynamax",
    ),
  );

  const teraAttempt = resolveSimpleTurn(dynamaxState, [
    { move: 1, gimmick: "terastallize" },
    { move: 1 },
  ]);
  assert.notEqual(teraAttempt.sides[0].team[0].terastallized, true);
  assert.ok(
    teraAttempt.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.gimmick === "terastallize" &&
        event.reason === "tera_blocked_by_dynamax",
    ),
  );
});

test("keeps the selected priority move when Mega Evolution is chosen", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "AI",
          team: [
            pokemon({
              id: "mawile",
              name: "Mawile",
              types: ["Steel", "Fairy"],
              item: "mawilite",
              stats: {
                ...pokemon().stats,
                hp: 304,
                attack: 220,
                defence: 160,
                speed: 50,
              },
              gimmicks: {
                megaStone: {
                  item: "mawilite",
                  evolves: "mawile",
                  form: "Mawile-Mega",
                  ability: "hugepower",
                },
              },
              moves: [
                {
                  id: "suckerpunch",
                  name: "Sucker Punch",
                  type: "Dark",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  priority: 1,
                  pp: 5,
                },
                {
                  id: "playrough",
                  name: "Play Rough",
                  type: "Fairy",
                  category: "Physical",
                  power: 90,
                  accuracy: 90,
                  pp: 10,
                },
                {
                  id: "ironhead",
                  name: "Iron Head",
                  type: "Steel",
                  category: "Physical",
                  power: 80,
                  accuracy: 100,
                  pp: 15,
                },
                {
                  id: "irondefense",
                  name: "Iron Defense",
                  type: "Steel",
                  category: "Status",
                  accuracy: true,
                  pp: 15,
                  selfBoosts: { defence: 2 },
                },
              ],
            }),
          ],
        },
        {
          name: "Opponent",
          team: [
            pokemon({
              id: "calyrexshadow",
              name: "Calyrex-Shadow",
              types: ["Psychic", "Ghost"],
              stats: {
                ...pokemon().stats,
                hp: 300,
                defence: 70,
                specialAttack: 1000,
                speed: 200,
              },
              moves: [
                {
                  id: "astralbarrage",
                  name: "Astral Barrage",
                  type: "Ghost",
                  category: "Special",
                  power: 120,
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );

  assert.deepEqual(
    chooseSimpleAiCommand(state, 0, "expert", "balanced"),
    { move: 1, gimmick: "mega" },
  );
});

test("uses the configured Tera Type and rejects a command-side override", () => {
  const battleSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            gimmicks: { teraType: "Water" },
          }),
        ],
      },
      { name: "AI", team: [pokemon({ name: "AiMon" })] },
    ],
  });
  let state = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1, gimmick: "terastallize", teraType: "Fire" },
    { move: 1 },
  ]);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.reason === "tera_type_mismatch",
    ),
  );
  assert.equal(state.sides[0].team[0].terastallized, false);

  state = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1, gimmick: "terastallize" },
    { move: 1 },
  ]);
  assert.equal(state.sides[0].team[0].terastallized, true);
  assert.equal(state.sides[0].team[0].teraType, "Water");
  assert.deepEqual(state.sides[0].team[0].types, ["Water"]);

  const lowerCaseState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [pokemon({ gimmicks: { teraType: "electric" } })],
          },
          { name: "AI", team: [pokemon({ name: "AiMon" })] },
        ],
      }),
    ),
    [{ move: 1, gimmick: "terastallize" }, { move: 1 }],
  );
  assert.equal(lowerCaseState.sides[0].team[0].teraType, "Electric");
  assert.deepEqual(lowerCaseState.sides[0].team[0].types, ["Electric"]);
});

test("changes each Ogerpon mask into its fixed Tera form and Embody Aspect", () => {
  const cases = [
    {
      id: "ogerpon",
      name: "Ogerpon",
      item: "",
      types: ["Grass"],
      teraType: "Grass",
      teraId: "ogerpontealtera",
      ability: "embodyaspectteal",
      stat: "speed",
    },
    {
      id: "ogerponwellspring",
      name: "Ogerpon-Wellspring",
      item: "wellspringmask",
      types: ["Grass", "Water"],
      teraType: "Water",
      teraId: "ogerponwellspringtera",
      ability: "embodyaspectwellspring",
      stat: "specialDefence",
    },
    {
      id: "ogerponhearthflame",
      name: "Ogerpon-Hearthflame",
      item: "hearthflamemask",
      types: ["Grass", "Fire"],
      teraType: "Fire",
      teraId: "ogerponhearthflametera",
      ability: "embodyaspecthearthflame",
      stat: "attack",
    },
    {
      id: "ogerponcornerstone",
      name: "Ogerpon-Cornerstone",
      item: "cornerstonemask",
      types: ["Grass", "Rock"],
      teraType: "Rock",
      teraId: "ogerponcornerstonetera",
      ability: "embodyaspectcornerstone",
      stat: "defence",
    },
  ];

  for (const entry of cases) {
    const formName = `${entry.name === "Ogerpon" ? "Ogerpon-Teal" : entry.name}-Tera`;
    const state = createSimpleBattle(
      setup({
        sides: [
          {
            name: "Ogerpon",
            team: [
              pokemon({
                id: entry.id,
                name: entry.name,
                baseSpecies: "Ogerpon",
                item: entry.item,
                ability: "defiant",
                types: entry.types,
                gimmicks: { teraType: "Dragon" },
                speciesForms: {
                  tera: {
                    id: entry.teraId,
                    name: formName,
                    types: entry.types,
                    ability: entry.ability,
                    weightKg: 39.8,
                    stats: pokemon().stats,
                  },
                },
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    power: 0,
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
          {
            name: "Target",
            team: [
              pokemon({
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    power: 0,
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    );
    const result = resolveSimpleTurn(
      state,
      [{ move: 1, gimmick: "terastallize" }, { move: 1 }],
    );
    const ogerpon = result.sides[0].team[0];

    assert.equal(ogerpon.id, entry.teraId);
    assert.equal(ogerpon.teraType, entry.teraType);
    assert.deepEqual(ogerpon.types, [entry.teraType]);
    assert.equal(ogerpon.ability, entry.ability);
    assert.equal(ogerpon.boosts[entry.stat], 1);
    assert.ok(
      result.events.some(
        (event) =>
          event.type === "ability_activate" &&
          event.ability === entry.ability,
      ),
    );
  }
});

test("applies Ogerpon mask power, Water Absorb, Mold Breaker, and Defiant", () => {
  const attack = {
    id: "ivycudgel",
    name: "Ivy Cudgel",
    type: "Grass",
    category: "Physical",
    power: 100,
    accuracy: true,
    pp: 10,
  };
  const plain = pokemon({
    id: "ogerpon",
    baseSpecies: "Ogerpon",
    types: ["Grass"],
    moves: [attack],
  });
  const masked = {
    ...plain,
    id: "ogerponwellspring",
    item: "wellspringmask",
  };
  const defender = pokemon({ types: ["Normal"] });
  assert.equal(
    calculateDamageRange(masked, defender, attack).itemModifier,
    1.2,
  );
  assert.ok(
    calculateDamageRange(masked, defender, attack).maximum >
      calculateDamageRange(plain, defender, attack).maximum,
  );

  const waterAbsorbState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Water",
          team: [
            pokemon({
              moves: [{
                id: "surf",
                name: "Surf",
                type: "Water",
                category: "Special",
                power: 90,
                accuracy: true,
                pp: 15,
              }],
            }),
          ],
        },
        {
          name: "Ogerpon",
          team: [
            pokemon({
              ability: "waterabsorb",
              stats: { ...pokemon().stats, hp: 200 },
            }),
          ],
        },
      ],
    }),
  );
  waterAbsorbState.sides[1].team[0].hp = 100;
  const absorbed = resolveSimpleTurn(
    waterAbsorbState,
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(absorbed.sides[1].team[0].hp, 150);

  const lowered = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Intimidate",
          team: [pokemon({ ability: "intimidate" })],
        },
        {
          name: "Defiant",
          team: [pokemon({ ability: "defiant" })],
        },
      ],
    }),
  );
  assert.equal(lowered.sides[1].team[0].boosts.attack, 1);

  const sturdy = {
    ...pokemon({ ability: "sturdy", stats: { ...pokemon().stats, hp: 120 } }),
    hp: 120,
  };
  const moldBreaker = {
    ...pokemon({
      ability: "moldbreaker",
      stats: { ...pokemon().stats, attack: 500 },
    }),
    originalTypes: ["Normal"],
  };
  assert.ok(calculateDamageRange(moldBreaker, sturdy, attack).maximum >= 120);
});

test("runs Terapagos Tera Shift, Tera Shell, and Stellar form conversion", () => {
  const splash = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 40,
  };
  const forms = {
    terastal: {
      id: "terapagosterastal",
      name: "Terapagos-Terastal",
      types: ["Normal"],
      ability: "terashell",
      weightKg: 16,
      stats: {
        hp: 95,
        attack: 95,
        defence: 110,
        specialAttack: 105,
        specialDefence: 110,
        speed: 85,
      },
    },
    stellar: {
      id: "terapagosstellar",
      name: "Terapagos-Stellar",
      types: ["Normal"],
      ability: "teraformzero",
      weightKg: 77,
      stats: {
        hp: 160,
        attack: 105,
        defence: 110,
        specialAttack: 130,
        specialDefence: 110,
        speed: 85,
      },
    },
  };
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Terapagos",
          team: [
            pokemon({
              id: "terapagos",
              name: "Terapagos",
              baseSpecies: "Terapagos",
              ability: "terashift",
              gimmicks: { teraType: "Fire" },
              stats: {
                hp: 90,
                attack: 65,
                defence: 85,
                specialAttack: 65,
                specialDefence: 85,
                speed: 60,
              },
              speciesForms: forms,
              moves: [splash],
            }),
          ],
        },
        { name: "Target", team: [pokemon({ moves: [splash] })] },
      ],
    }),
  );
  const terastal = state.sides[0].team[0];
  assert.equal(terastal.id, "terapagosterastal");
  assert.equal(terastal.name, "Terapagos-Terastal");
  assert.equal(terastal.ability, "terashell");
  assert.equal(terastal.stats.hp, 95);
  assert.equal(terastal.hp, 95);
  assert.equal(terastal.configuredTeraType, "Stellar");

  const fightingMove = {
    id: "closecombat",
    name: "Close Combat",
    type: "Fighting",
    category: "Physical",
    power: 120,
    accuracy: true,
    pp: 5,
  };
  assert.equal(
    calculateDamageRange(pokemon(), terastal, fightingMove).effectiveness,
    0.5,
  );
  terastal.hp = 70;
  assert.equal(
    calculateDamageRange(pokemon(), terastal, fightingMove).effectiveness,
    2,
  );
  assert.equal(
    calculateDamageRange(
      pokemon({ ability: "moldbreaker" }),
      { ...terastal, hp: terastal.stats.hp },
      fightingMove,
    ).effectiveness,
    2,
  );

  state.field.weather = { id: "raindance", turns: 5 };
  state.field.terrain = { id: "electricterrain", turns: 5 };
  const stellarState = resolveSimpleTurn(
    state,
    [{ move: 1, gimmick: "terastallize" }, { move: 1 }],
  );
  const stellar = stellarState.sides[0].team[0];
  assert.equal(stellar.id, "terapagosstellar");
  assert.equal(stellar.name, "Terapagos-Stellar");
  assert.equal(stellar.ability, "teraformzero");
  assert.equal(stellar.teraType, "Stellar");
  assert.deepEqual(stellar.types, ["Normal"]);
  assert.equal(stellar.stats.hp, 160);
  assert.equal(stellar.hp, 135);
  assert.equal(stellarState.field.weather, null);
  assert.equal(stellarState.field.terrain, null);
  assert.ok(
    stellarState.events.some(
      (event) =>
        event.type === "ability_activate" &&
        event.ability === "teraformzero",
    ),
  );
});

test("keeps Tera Shell resistance through every hit of a multi-hit move", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Attacker",
          team: [
            pokemon({
              stats: { ...pokemon().stats, speed: 150 },
              moves: [{
                id: "doublehit",
                name: "Double Hit",
                type: "Normal",
                category: "Physical",
                power: 35,
                accuracy: true,
                pp: 10,
                multihit: [2, 2],
              }],
            }),
          ],
        },
        {
          name: "Terapagos",
          team: [
            pokemon({
              id: "terapagosterastal",
              name: "Terapagos-Terastal",
              baseSpecies: "Terapagos",
              ability: "terashell",
              stats: { ...pokemon().stats, hp: 500 },
              moves: [{
                id: "splash",
                name: "Splash",
                type: "Normal",
                category: "Status",
                power: 0,
                accuracy: true,
                pp: 40,
              }],
            }),
          ],
        },
      ],
    }),
  );
  const result = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const hits = result.events.filter(
    (event) =>
      event.type === "damage" &&
      event.pokemon === "Terapagos-Terastal" &&
      event.move === "Double Hit",
  );
  assert.equal(hits.length, 2);
  assert.deepEqual(hits.map((event) => event.effectiveness), [0.5, 0.5]);
});

test("preserves original STAB and applies same-type Tera STAB", () => {
  const defender = pokemon({ types: ["Normal"] });
  const move = {
    id: "flamethrower",
    name: "Flamethrower",
    type: "Fire",
    category: "Special",
    power: 90,
    accuracy: 100,
    pp: 15,
  };
  const differentTera = {
    ...pokemon({ types: ["Grass"] }),
    originalTypes: ["Fire", "Flying"],
    terastallized: true,
    teraType: "Grass",
  };
  const sameTypeTera = {
    ...differentTera,
    types: ["Fire"],
    teraType: "Fire",
  };
  const adaptableTera = {
    ...sameTypeTera,
    ability: "adaptability",
  };

  assert.equal(calculateDamageRange(differentTera, defender, move).stab, 1.5);
  assert.equal(calculateDamageRange(sameTypeTera, defender, move).stab, 2);
  assert.equal(calculateDamageRange(adaptableTera, defender, move).stab, 2.25);
});

test("uses transformed Tera Blast and the Tera power floor in previews", () => {
  const attacker = {
    ...pokemon({
      stats: {
        ...pokemon().stats,
        attack: 180,
        specialAttack: 80,
      },
    }),
    originalTypes: ["Normal"],
    types: ["Electric"],
    terastallized: true,
    teraType: "Electric",
  };
  const defender = pokemon({ types: ["Water"] });
  const teraBlast = {
    id: "terablast",
    name: "Tera Blast",
    type: "Normal",
    category: "Special",
    power: 80,
    accuracy: 100,
    pp: 10,
  };
  const lowPowerMove = {
    id: "electroweb",
    name: "Electroweb",
    type: "Electric",
    category: "Special",
    power: 55,
    accuracy: 95,
    pp: 15,
  };

  const teraBlastPreview = calculateMovePreview(attacker, defender, teraBlast);
  const lowPowerPreview = calculateMovePreview(attacker, defender, lowPowerMove);

  assert.equal(teraBlastPreview.move.type, "Electric");
  assert.equal(teraBlastPreview.move.category, "Physical");
  assert.equal(teraBlastPreview.range.effectiveness, 2);
  assert.equal(lowPowerPreview.move.power, 60);
});

test("keeps defensive typing and consumes Stellar boosts by move type", () => {
  const flamethrower = {
    id: "flamethrower",
    name: "Flamethrower",
    type: "Fire",
    category: "Special",
    power: 90,
    accuracy: 100,
    pp: 15,
  };
  let state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Stellar",
          team: [
            pokemon({
              name: "Stellar User",
              types: ["Fire", "Flying"],
              gimmicks: { teraType: "Stellar" },
              moves: [flamethrower],
            }),
          ],
        },
        {
          name: "Target",
          team: [
            pokemon({
              name: "Target",
              stats: { ...pokemon().stats, hp: 500 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  power: 0,
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state = resolveSimpleTurn(state, [
    { move: 1, gimmick: "terastallize" },
    { move: 1 },
  ]);
  const attacker = state.sides[0].team[0];
  const defender = state.sides[1].team[0];

  assert.deepEqual(attacker.types, ["Fire", "Flying"]);
  assert.deepEqual(attacker.stellarBoostedTypes, ["Fire"]);
  assert.equal(calculateDamageRange(attacker, defender, flamethrower).stab, 1.5);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "stellar_boost_consumed" &&
        event.moveType === "Fire",
    ),
  );
});

test("treats Stellar attacks as super effective only into Terastallized targets", () => {
  const attacker = {
    ...pokemon(),
    originalTypes: ["Normal"],
    terastallized: true,
    teraType: "Stellar",
    stellarBoostedTypes: [],
  };
  const stellarMove = {
    id: "terablast",
    name: "Tera Blast",
    type: "Stellar",
    category: "Special",
    power: 100,
    accuracy: 100,
    pp: 10,
    teraResolved: true,
  };
  const normalTarget = pokemon({ types: ["Ghost"] });
  const teraTarget = {
    ...normalTarget,
    types: ["Water"],
    originalTypes: ["Ghost"],
    terastallized: true,
    teraType: "Water",
  };

  assert.equal(
    calculateDamageRange(attacker, normalTarget, stellarMove).effectiveness,
    1,
  );
  assert.equal(
    calculateDamageRange(attacker, teraTarget, stellarMove).effectiveness,
    2,
  );
});

test("AI scores defensive Terastallization through the projected one-turn state", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Tera Candidate",
            types: ["Electric"],
            gimmicks: { teraType: "Flying" },
            stats: {
              ...pokemon().stats,
              hp: 180,
              specialAttack: 180,
              speed: 80,
            },
            moves: [
              {
                id: "icebeam",
                name: "Ice Beam",
                type: "Ice",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Ground Threat",
            types: ["Ground"],
            stats: {
              ...pokemon().stats,
              hp: 240,
              attack: 320,
              speed: 160,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const decision = chooseSimpleAiDecision(
    createSimpleBattle(scenario),
    0,
    "expert",
    "balanced",
  );

  assert.equal(decision.command.gimmick, "terastallize");
  assert.equal(decision.gimmickCandidate.id, "terastallize");
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "simulation.gimmick_one_turn_state",
    ),
  );
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "gimmick.tera.active_damage_change",
    ),
  );
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "gimmick.tera.prevents_active_ko",
    ),
  );
  assert.ok(decision.gimmickCandidate.oneTurnEvaluation);
});

test("AI preserves Terastallization when defensive Tera still faints immediately", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Fragile Tera Candidate",
            types: ["Electric"],
            gimmicks: { teraType: "Grass" },
            stats: {
              ...pokemon().stats,
              hp: 180,
              specialAttack: 100,
              speed: 80,
            },
            moves: [
              {
                id: "icebeam",
                name: "Ice Beam",
                type: "Ice",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Overwhelming Ground Threat",
            types: ["Ground"],
            stats: {
              ...pokemon().stats,
              hp: 500,
              attack: 600,
              speed: 160,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const decision = chooseSimpleAiDecision(
    createSimpleBattle(scenario),
    0,
    "expert",
    "balanced",
  );

  assert.equal(decision.command.gimmick, undefined);
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) =>
        reason.code === "gimmick.tera.fails_to_survive_active_hit",
    ),
    JSON.stringify(decision.gimmickCandidate, null, 2),
  );
  assert.ok(
    decision.gimmickCandidate.score <
      decision.gimmickCandidate.activationThreshold,
  );
});

test("AI evaluates remaining opposing matchups before spending Terastallization", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Tera Candidate",
            types: ["Electric"],
            gimmicks: { teraType: "Flying" },
            moves: [
              {
                id: "icebeam",
                name: "Ice Beam",
                type: "Ice",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Ground Threat",
            types: ["Ground"],
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          pokemon({
            name: "Ice Backline",
            types: ["Ice"],
            moves: [
              {
                id: "icebeam",
                name: "Ice Beam",
                type: "Ice",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const decision = chooseSimpleAiDecision(
    createSimpleBattle(scenario),
    0,
    "expert",
    "balanced",
  );
  const remainingReason = decision.gimmickCandidate.reasons.find(
    (reason) => reason.code === "gimmick.tera.remaining_matchups",
  );

  assert.ok(remainingReason);
  assert.equal(remainingReason.value[0].opponent, "Ice Backline");
  assert.deepEqual(remainingReason.value[0].types, ["Ice"]);
  assert.ok(remainingReason.value[0].after > remainingReason.value[0].before);
  assert.ok(remainingReason.weight < 0);
});

test("reserves Tera for the configured member until that member faints", () => {
  const reservedScenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Fallback",
            types: ["Normal"],
            gimmicks: {
              teraType: "Electric",
              teraConfigured: false,
            },
            stats: {
              ...pokemon().stats,
              attack: 180,
              specialAttack: 70,
            },
            moves: [
              {
                id: "terablast",
                name: "Tera Blast",
                type: "Normal",
                category: "Special",
                power: 80,
                accuracy: 100,
                pp: 10,
                dynamicPower: true,
              },
            ],
          }),
          pokemon({
            name: "Configured",
            gimmicks: {
              teraType: "Water",
              teraConfigured: true,
            },
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            types: ["Water"],
            moves: [
              {
                id: "splash",
                name: "Splash",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 40,
              },
            ],
          }),
        ],
      },
    ],
  });
  const reserved = resolveSimpleTurn(
    createSimpleBattle(reservedScenario),
    [{ move: 1, gimmick: "terastallize" }, { move: 1 }],
  );

  assert.equal(reserved.sides[0].team[0].terastallized, false);
  assert.ok(
    reserved.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.reason === "tera_reserved_for_configured_pokemon",
    ),
  );

  const fallbackState = createSimpleBattle(reservedScenario);
  fallbackState.sides[0].team[1].hp = 0;
  fallbackState.sides[0].team[1].fainted = true;
  const fallback = resolveSimpleTurn(
    fallbackState,
    [{ move: 1, gimmick: "terastallize" }, { move: 1 }],
  );

  assert.equal(fallback.sides[0].team[0].terastallized, true);
  assert.equal(fallback.sides[0].team[0].teraType, "Electric");

  const aiReservedState = createSimpleBattle(reservedScenario);
  aiReservedState.sides[0].gimmickResources.mega = "consumed";
  aiReservedState.sides[0].gimmickResources.zmove = "consumed";
  aiReservedState.sides[0].gimmickResources.dynamax = "consumed";
  const reservedDecision = chooseSimpleAiDecision(
    aiReservedState,
    0,
    "expert",
    "balanced",
  );
  assert.notEqual(reservedDecision.command.gimmick, "terastallize");

  aiReservedState.sides[0].team[1].hp = 0;
  aiReservedState.sides[0].team[1].fainted = true;
  const fallbackDecision = chooseSimpleAiDecision(
    aiReservedState,
    0,
    "expert",
    "balanced",
  );
  assert.equal(fallbackDecision.command.gimmick, "terastallize");
  assert.equal(fallbackDecision.gimmickCandidate.selectedMove.type, "Electric");
});

test("AI evaluates Tera Blast with its transformed type and category", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Tera Blast User",
            types: ["Normal"],
            gimmicks: { teraType: "Electric" },
            stats: {
              ...pokemon().stats,
              attack: 180,
              specialAttack: 70,
            },
            moves: [
              {
                id: "terablast",
                name: "Tera Blast",
                type: "Normal",
                category: "Special",
                power: 80,
                accuracy: 100,
                pp: 10,
                dynamicPower: true,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Water Target",
            types: ["Water"],
            moves: [
              {
                id: "watergun",
                name: "Water Gun",
                type: "Water",
                category: "Special",
                power: 40,
                accuracy: 100,
                pp: 25,
              },
            ],
          }),
        ],
      },
    ],
  });
  const decision = chooseSimpleAiDecision(
    createSimpleBattle(scenario),
    0,
    "expert",
    "balanced",
  );

  assert.equal(decision.command.gimmick, "terastallize");
  assert.equal(decision.gimmickCandidate.selectedMove.type, "Electric");
  assert.equal(decision.gimmickCandidate.selectedMove.category, "Physical");
});

test("AI preserves Terastallization when it does not improve the projected action", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Neutral Tera",
            gimmicks: { teraType: "Grass" },
            moves: [
              {
                id: "waterpulse",
                name: "Water Pulse",
                type: "Water",
                category: "Special",
                power: 60,
                accuracy: 100,
                pp: 20,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Neutral Opponent",
            moves: [
              {
                id: "confusion",
                name: "Confusion",
                type: "Psychic",
                category: "Special",
                power: 50,
                accuracy: 100,
                pp: 25,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.dynamax = "consumed";
  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );

  assert.equal(decision.command.gimmick, undefined);
  assert.equal(decision.gimmickCandidate.id, "terastallize");
  assert.ok(
    decision.gimmickCandidate.score <
      decision.gimmickCandidate.activationThreshold,
  );
});

test("AI preserves Tera on a safe guaranteed KO when a stronger reserve candidate remains", () => {
  const closeCombat = {
    id: "closecombat",
    name: "Close Combat",
    type: "Fighting",
    category: "Physical",
    power: 120,
    accuracy: 100,
    pp: 5,
    selfBoosts: { defence: -1, specialDefence: -1 },
  };
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            id: "koraidon",
            name: "Koraidon",
            types: ["Fighting", "Dragon"],
            gimmicks: { teraType: "Fire" },
            stats: {
              ...pokemon().stats,
              attack: 280,
              speed: 160,
            },
            moves: [closeCombat],
          }),
          pokemon({
            id: "reserve",
            name: "Reserve Sweeper",
            types: ["Water"],
            gimmicks: { teraType: "Water" },
            stats: {
              ...pokemon().stats,
              specialAttack: 240,
            },
            moves: [
              {
                id: "hydropump",
                name: "Hydro Pump",
                type: "Water",
                category: "Special",
                power: 110,
                accuracy: 80,
                pp: 5,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Snorlax",
            types: ["Normal"],
            stats: {
              ...pokemon().stats,
              hp: 120,
              defence: 80,
              speed: 40,
            },
          }),
          pokemon({
            name: "Fire Backline",
            types: ["Fire"],
            moves: [
              {
                id: "flamethrower",
                name: "Flamethrower",
                type: "Fire",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.mega = "consumed";
  state.sides[0].gimmickResources.zmove = "consumed";
  state.sides[0].gimmickResources.dynamax = "consumed";
  const decision = chooseSimpleAiDecision(state, 0, "expert", "balanced");

  assert.equal(decision.selectedMove.id, "closecombat");
  assert.equal(decision.selectedMove.koChance, "guaranteed");
  assert.equal(decision.command.gimmick, undefined);
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "gimmick.tera.safe_ko_preservation",
    ),
    JSON.stringify(decision.gimmickCandidate, null, 2),
  );
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "gimmick.tera.better_reserve_candidate",
    ),
  );
});

test("AI compares living team candidates before spending a low-value Tera", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            id: "garganacl",
            name: "Garganacl",
            types: ["Rock"],
            gimmicks: { teraType: "Water" },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          pokemon({
            name: "Electric Reserve",
            types: ["Electric"],
            gimmicks: { teraType: "Electric" },
            moves: [
              {
                id: "thunderbolt",
                name: "Thunderbolt",
                type: "Electric",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Neutral Target",
            types: ["Normal"],
            stats: { ...pokemon().stats, hp: 300 },
            moves: [
              {
                id: "psychic",
                name: "Psychic",
                type: "Psychic",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.mega = "consumed";
  state.sides[0].gimmickResources.zmove = "consumed";
  state.sides[0].gimmickResources.dynamax = "consumed";
  const decision = chooseSimpleAiDecision(state, 0, "expert", "balanced");

  assert.equal(decision.command.gimmick, undefined);
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "gimmick.tera.better_reserve_candidate",
    ),
    JSON.stringify(decision.gimmickCandidate, null, 2),
  );
});

test("AI preserves same-type Tera when it only slightly improves Salt Cure damage", () => {
  const scenario = setup({
    sides: [
      {
        name: "Porygon Team",
        team: [
          pokemon({
            id: "porygon2",
            name: "Porygon2",
            types: ["Normal"],
            stats: {
              ...pokemon().stats,
              hp: 374,
              specialAttack: 170,
              speed: 70,
            },
            moves: [
              {
                id: "icebeam",
                name: "Ice Beam",
                type: "Ice",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          pokemon({ id: "blissey", name: "Blissey", types: ["Normal"] }),
          pokemon({ id: "slowbro", name: "Slowbro", types: ["Water", "Psychic"] }),
        ],
      },
      {
        name: "Garganacl Team",
        team: [
          pokemon({
            id: "garganacl",
            name: "Garganacl",
            types: ["Rock"],
            gimmicks: { teraType: "Rock" },
            stats: {
              ...pokemon().stats,
              hp: 404,
              attack: 120,
              specialDefence: 130,
              speed: 35,
            },
            moves: [
              {
                id: "saltcure",
                name: "Salt Cure",
                type: "Rock",
                category: "Physical",
                power: 40,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
          pokemon({
            id: "charizard",
            name: "Charizard",
            types: ["Fire", "Flying"],
            gimmicks: { teraType: "Fire" },
            moves: [
              {
                id: "fireblast",
                name: "Fire Blast",
                type: "Fire",
                category: "Special",
                power: 110,
                accuracy: 85,
                pp: 5,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.turn = 2;
  state.sides[1].team[0].hp = 253;
  state.sides[1].gimmickResources.mega = "consumed";
  state.sides[1].gimmickResources.zmove = "consumed";
  state.sides[1].gimmickResources.dynamax = "consumed";
  const decision = chooseSimpleAiDecision(state, 1, "expert", "balanced");

  assert.equal(decision.selectedMove.id, "saltcure");
  assert.equal(
    decision.command.gimmick,
    undefined,
    JSON.stringify(decision.gimmickCandidate, null, 2),
  );
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "gimmick.tera.marginal_gain",
    ),
    JSON.stringify(decision.gimmickCandidate, null, 2),
  );
});

test("AI records projected one-turn state reasons for Mega Evolution", () => {
  const scenario = setup({
    sides: [
      {
        name: "AI",
        team: [
          pokemon({
            name: "Mega Candidate",
            item: "testite",
            gimmicks: {
              megaStone: {
                item: "testite",
                evolves: "testmon",
                form: "Testmon-Mega",
                ability: "hugepower",
                stats: {
                  attack: 180,
                  defence: 130,
                  specialAttack: 100,
                  specialDefence: 120,
                  speed: 120,
                },
              },
            },
          }),
        ],
      },
      { name: "Opponent", team: [pokemon({ name: "Target" })] },
    ],
  });
  const decision = chooseSimpleAiDecision(
    createSimpleBattle(scenario),
    0,
    "expert",
    "balanced",
  );

  assert.equal(decision.command.gimmick, "mega");
  assert.equal(decision.gimmickCandidate.id, "mega");
  assert.ok(
    decision.gimmickCandidate.reasons.some(
      (reason) => reason.code === "simulation.gimmick_one_turn_state",
    ),
  );
  assert.ok(decision.gimmickCandidate.oneTurnEvaluation);
});

test("supports first-turn Fake Out flinch and later failure", () => {
  const battleSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "Flincher",
            stats: { ...pokemon().stats, speed: 160 },
            moves: [
              {
                id: "fakeout",
                name: "Fake Out",
                type: "Normal",
                category: "Physical",
                power: 40,
                accuracy: true,
                priority: 3,
                pp: 10,
              },
            ],
          }),
        ],
      },
      { name: "AI", team: [pokemon({ name: "Target" })] },
    ],
  });
  let state = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(state.sides[0].team[0].hp, 120);
  assert.equal(state.sides[1].team[0].moves[0].pp, 35);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "cant_move" &&
        event.pokemon === "Target" &&
        event.status === "flinch",
    ),
  );

  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    state.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move_failed" &&
        event.move === "Fake Out",
    ),
  );
});

test("keeps Fake Out available after switching in and removes it from later AI choices", () => {
  const fakeOut = {
    id: "fakeout",
    name: "Fake Out",
    type: "Normal",
    category: "Physical",
    power: 200,
    accuracy: true,
    priority: 3,
    pp: 10,
  };
  const battleSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({ name: "Lead" }),
          pokemon({
            name: "Flincher",
            stats: { ...pokemon().stats, speed: 160 },
            moves: [
              fakeOut,
              {
                id: "tackle",
                name: "Tackle",
                type: "Normal",
                category: "Physical",
                power: 20,
                accuracy: 100,
                pp: 35,
              },
            ],
          }),
        ],
      },
      {
        name: "AI",
        team: [
          pokemon({
            name: "Target",
            moves: [
              {
                id: "tackle",
                name: "Tackle",
                type: "Normal",
                category: "Physical",
                power: 20,
                accuracy: 100,
                pp: 35,
              },
            ],
          }),
        ],
      },
    ],
  });

  let state = resolveSimpleTurn(createSimpleBattle(battleSetup), [
    { switch: 2 },
    { move: 1 },
  ]);
  assert.equal(state.sides[0].team[1].activeTurns, 0);

  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    state.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "cant_move" &&
        event.pokemon === "Target" &&
        event.status === "flinch",
    ),
  );

  const aiState = createSimpleBattle(battleSetup);
  aiState.sides[0].active = 1;
  aiState.sides[0].team[0].hp = 0;
  aiState.sides[0].team[0].fainted = true;
  aiState.sides[0].team[1].activeTurns = 1;
  assert.equal(chooseSimpleAiCommand(aiState, 0, "expert").move, 2);
});

test("rejects unsupported move effects in strict validation mode", () => {
  const state = createSimpleBattle(
    setup({
      strictMoveEffectValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "UnsupportedUser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "unknownnativeeffect",
                  name: "Unknown Native Effect",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );

  assert.throws(
    () => resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]),
    /Unsupported move effect.*Unknown Native Effect/,
  );
});

test("supports Belly Drum, Leech Seed, Yawn, and Aurora Veil conditions", () => {
  const bellyDrumState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Drummer",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "bellydrum",
                    name: "Belly Drum",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Passive",
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(bellyDrumState.sides[0].team[0].hp, 60);
  assert.equal(bellyDrumState.sides[0].team[0].boosts.attack, 6);

  const leechSeedState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Seeder",
              stats: { ...pokemon().stats, hp: 160, speed: 160 },
              moves: [
                {
                  id: "leechseed",
                  name: "Leech Seed",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Seeded",
              types: ["Water"],
              stats: { ...pokemon().stats, hp: 160, speed: 40 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  leechSeedState.sides[0].team[0].hp = 80;
  const seeded = resolveSimpleTurn(leechSeedState, [{ move: 1 }, { move: 1 }]);
  assert.equal(seeded.sides[1].team[0].hp, 140);
  assert.equal(seeded.sides[0].team[0].hp, 100);
  assert.ok(
    seeded.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "Seeded" &&
        event.source === "Leech Seed",
    ),
  );

  let yawnState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Yawner",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "yawn",
                    name: "Yawn",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Sleepy",
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(yawnState.sides[1].team[0].status, "");
  yawnState = resolveSimpleTurn(yawnState, [{ move: 2 }, { move: 1 }]);
  assert.equal(yawnState.sides[1].team[0].status, "slp");

  const auroraSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "VeilSetter",
            stats: { ...pokemon().stats, speed: 160 },
            moves: [
              {
                id: "auroraveil",
                name: "Aurora Veil",
                type: "Ice",
                category: "Status",
                accuracy: true,
                pp: 20,
                target: "allySide",
                sideCondition: "auroraveil",
              },
            ],
          }),
        ],
      },
      { name: "AI", team: [pokemon({ name: "Target" })] },
    ],
  });
  const noSnow = resolveSimpleTurn(createSimpleBattle(auroraSetup), [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(noSnow.sides[0].conditions.auroraveil, undefined);

  const snowState = createSimpleBattle(auroraSetup);
  snowState.field.weather = { id: "snow", turns: 5 };
  const withSnow = resolveSimpleTurn(snowState, [{ move: 1 }, { move: 1 }]);
  assert.equal(withSnow.sides[0].conditions.auroraveil.id, "auroraveil");
});

test("supports Poltergeist item checks and crash damage on protected jump moves", () => {
  const noItem = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Ghost",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "poltergeist",
                    name: "Poltergeist",
                    type: "Ghost",
                    category: "Physical",
                    power: 110,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "NoItemTarget", types: ["Psychic"] })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    noItem.events.some(
      (event) =>
        event.type === "move_failed" &&
        event.move === "Poltergeist" &&
        event.reason.includes("hold an item"),
    ),
  );

  const withItem = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Ghost",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "poltergeist",
                    name: "Poltergeist",
                    type: "Ghost",
                    category: "Physical",
                    power: 110,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "ItemTarget",
                item: "leftovers",
                types: ["Psychic"],
                stats: { ...pokemon().stats, hp: 240 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(withItem.sides[1].team[0].hp < 240);

  const crashed = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Jumper",
                stats: { ...pokemon().stats, speed: 40 },
                moves: [
                  {
                    id: "highjumpkick",
                    name: "High Jump Kick",
                    type: "Fighting",
                    category: "Physical",
                    power: 130,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Protector",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "protect",
                    name: "Protect",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                    target: "self",
                    volatileStatus: "protect",
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(crashed.sides[0].team[0].hp, 60);
  assert.ok(
    crashed.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "Jumper" &&
        event.cause === "crash",
    ),
  );
});

test("supports two-turn charge attacks and weather exceptions", () => {
  let state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Phantom",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "phantomforce",
                    name: "Phantom Force",
                    type: "Ghost",
                    category: "Physical",
                    power: 90,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "Target", types: ["Psychic"] })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(state.sides[1].team[0].hp, 120);
  assert.equal(state.sides[0].team[0].moves[0].pp, 9);
  assert.ok(
    state.events.some(
      (event) => event.type === "charge_start" && event.move === "Phantom Force",
    ),
  );

  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.ok(state.sides[1].team[0].hp < 120);
  assert.equal(state.sides[0].team[0].moves[0].pp, 9);

  const sunState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "SunCaster",
              types: ["Grass"],
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "solarbeam",
                  name: "Solar Beam",
                  type: "Grass",
                  category: "Special",
                  power: 120,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "WaterTarget", types: ["Water"], stats: { ...pokemon().stats, hp: 240 } })],
        },
      ],
    }),
  );
  sunState.field.weather = { id: "sunnyday", turns: 5 };
  const sunny = resolveSimpleTurn(sunState, [{ move: 1 }, { move: 1 }]);
  assert.ok(sunny.sides[1].team[0].hp < 240);
  assert.equal(
    sunny.events.some((event) => event.type === "charge_start"),
    false,
  );

  const sandState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "SandCaster",
              types: ["Grass"],
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "solarbeam",
                  name: "Solar Beam",
                  type: "Grass",
                  category: "Special",
                  power: 120,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "SandTarget", types: ["Water"], stats: { ...pokemon().stats, hp: 240 } })],
        },
      ],
    }),
  );
  sandState.field.weather = { id: "sandstorm", turns: 5 };
  const charging = resolveSimpleTurn(sandState, [{ move: 1 }, { move: 1 }]);
  const sandHit = resolveSimpleTurn(charging, [{ move: 1 }, { move: 1 }]);
  const solarDamage = sandHit.events.find(
    (event) => event.type === "damage" && event.move === "Solar Beam",
  )?.damage;
  assert.ok(solarDamage > 0);

  let meteor = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Meteor",
                stats: { ...pokemon().stats, specialAttack: 120, speed: 160 },
                moves: [
                  {
                    id: "meteorbeam",
                    name: "Meteor Beam",
                    type: "Rock",
                    category: "Special",
                    power: 120,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "MeteorTarget", types: ["Fire"], stats: { ...pokemon().stats, hp: 240 } })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(meteor.sides[0].team[0].boosts.specialAttack, 1);
  meteor = resolveSimpleTurn(meteor, [{ move: 1 }, { move: 1 }]);
  assert.ok(meteor.sides[1].team[0].hp < 240);
});

test("supports Substitute absorbing attacks and blocking status moves", () => {
  let state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "DollUser",
                stats: { ...pokemon().stats, hp: 160, speed: 160 },
                moves: [
                  {
                    id: "substitute",
                    name: "Substitute",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                    target: "self",
                    volatileStatus: "substitute",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "WeakAttacker",
                stats: { ...pokemon().stats, attack: 40, speed: 40 },
                moves: [
                  {
                    id: "tackle",
                    name: "Tackle",
                    type: "Normal",
                    category: "Physical",
                    power: 20,
                    accuracy: true,
                    pp: 35,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(state.sides[0].team[0].hp, 120);
  assert.ok(state.sides[0].team[0].volatiles.substitute.hp > 0);

  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.equal(state.sides[0].team[0].hp, 120);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "damage" &&
        event.cause === "substitute" &&
        event.pokemon === "DollUser",
    ),
  );

  const blocked = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "DollUser",
                stats: { ...pokemon().stats, hp: 160, speed: 160 },
                moves: [
                  {
                    id: "substitute",
                    name: "Substitute",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                    target: "self",
                    volatileStatus: "substitute",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "StatusUser",
                stats: { ...pokemon().stats, speed: 40 },
                moves: [
                  {
                    id: "willowisp",
                    name: "Will-O-Wisp",
                    type: "Fire",
                    category: "Status",
                    accuracy: true,
                    pp: 15,
                    status: "brn",
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(blocked.sides[0].team[0].status, "");
  assert.ok(
    blocked.events.some(
      (event) => event.type === "move_blocked" && event.source === "substitute",
    ),
  );
});

test("supports Destiny Bond and Curse battle effects", () => {
  const bonded = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "BondUser",
                stats: { ...pokemon().stats, hp: 80, speed: 160 },
                moves: [
                  {
                    id: "destinybond",
                    name: "Destiny Bond",
                    type: "Ghost",
                    category: "Status",
                    accuracy: true,
                    pp: 5,
                    target: "self",
                    volatileStatus: "destinybond",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Finisher",
                stats: { ...pokemon().stats, attack: 240, speed: 40 },
                moves: [
                  {
                    id: "crunch",
                    name: "Crunch",
                    type: "Dark",
                    category: "Physical",
                    power: 120,
                    accuracy: true,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(bonded.sides[0].team[0].fainted, true);
  assert.equal(bonded.sides[1].team[0].fainted, true);
  assert.ok(
    bonded.events.some(
      (event) =>
        event.type === "volatile_activate" &&
        event.effect === "destinybond",
    ),
  );

  const ghostCurse = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "GhostCurser",
                types: ["Ghost"],
                stats: { ...pokemon().stats, hp: 160, speed: 160 },
                moves: [
                  {
                    id: "curse",
                    name: "Curse",
                    type: "Ghost",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "CursedTarget",
                stats: { ...pokemon().stats, hp: 160, speed: 40 },
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(ghostCurse.sides[0].team[0].hp, 80);
  assert.equal(ghostCurse.sides[0].team[0].boosts.attack, 0);
  assert.equal(ghostCurse.sides[0].team[0].boosts.defence, 0);
  assert.equal(ghostCurse.sides[0].team[0].boosts.speed, 0);
  assert.equal(ghostCurse.sides[1].team[0].hp, 120);
  assert.equal(ghostCurse.sides[1].team[0].volatiles.curse.id, "curse");

  const normalCurse = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Curser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "curse",
                    name: "Curse",
                    type: "Ghost",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "Target" })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(normalCurse.sides[0].team[0].boosts.attack, 1);
  assert.equal(normalCurse.sides[0].team[0].boosts.defence, 1);
  assert.equal(normalCurse.sides[0].team[0].boosts.speed, -1);
  assert.equal(normalCurse.sides[0].team[0].volatiles.curse, undefined);
  assert.equal(normalCurse.sides[1].team[0].volatiles.curse, undefined);
});

test("Destiny Bond expires on the next action and fails on consecutive use", () => {
  const destinyBond = {
    id: "destinybond",
    name: "Destiny Bond",
    type: "Ghost",
    category: "Status",
    accuracy: true,
    pp: 5,
    target: "self",
    volatileStatus: "destinybond",
  };
  const first = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "BondUser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [destinyBond],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Observer",
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(first.sides[0].team[0].volatiles.destinybond.id, "destinybond");

  const repeated = resolveSimpleTurn(first, [{ move: 1 }, { move: 1 }]);
  assert.equal(repeated.sides[0].team[0].volatiles.destinybond, undefined);
  assert.ok(
    repeated.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move_failed" &&
        event.move === "Destiny Bond",
    ),
  );
});

test("Grudge depletes the knockout move PP and expires after the user's next move", () => {
  const grudge = {
    id: "grudge",
    name: "Grudge",
    type: "Ghost",
    category: "Status",
    accuracy: true,
    pp: 5,
    target: "self",
    volatileStatus: "grudge",
  };
  const knockout = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "GrudgeUser",
                stats: { ...pokemon().stats, hp: 80, speed: 160 },
                moves: [grudge],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Finisher",
                stats: { ...pokemon().stats, attack: 240, speed: 40 },
                moves: [
                  {
                    id: "crunch",
                    name: "Crunch",
                    type: "Dark",
                    category: "Physical",
                    power: 120,
                    accuracy: true,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(knockout.sides[1].team[0].moves[0].pp, 0);
  assert.ok(
    knockout.events.some(
      (event) =>
        event.type === "pp_depleted" &&
        event.move === "Crunch" &&
        event.source === "Grudge",
    ),
  );

  const expiryBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "GrudgeUser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                grudge,
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Observer" })] },
      ],
    }),
  );
  const grudged = resolveSimpleTurn(expiryBase, [{ move: 1 }, { move: 1 }]);
  const expired = resolveSimpleTurn(grudged, [{ move: 2 }, { move: 1 }]);
  assert.equal(expired.sides[0].team[0].volatiles.grudge, undefined);
  assert.ok(
    expired.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "volatile_end" &&
        event.effect === "grudge",
    ),
  );
});

test("supports Belch only after the user has eaten a Berry", () => {
  const failed = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Belcher",
                moves: [
                  {
                    id: "belch",
                    name: "Belch",
                    type: "Poison",
                    category: "Special",
                    power: 120,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "Target", types: ["Grass"] })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    failed.events.some(
      (event) => event.type === "move_failed" && event.move === "Belch",
    ),
  );

  const succeeded = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Belcher",
                ateBerry: true,
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "belch",
                    name: "Belch",
                    type: "Poison",
                    category: "Special",
                    power: 120,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "Target", types: ["Grass"], stats: { ...pokemon().stats, hp: 240 } })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(succeeded.sides[1].team[0].hp < 240);
});

test("supports binding move residual damage", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Binder",
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "firespin",
                    name: "Fire Spin",
                    type: "Fire",
                    category: "Special",
                    power: 35,
                    accuracy: true,
                    pp: 15,
                    volatileStatus: "firespin",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Trapped",
                stats: { ...pokemon().stats, hp: 300, speed: 40 },
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );

  assert.equal(state.sides[1].team[0].volatiles.firespin.id, "firespin");
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "Trapped" &&
        event.cause === "volatile" &&
        event.source === "Fire Spin",
    ),
  );
});

test("supports Focus Punch failing after the user is hit first", () => {
  const interrupted = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Puncher",
                stats: { ...pokemon().stats, attack: 180, speed: 40 },
                moves: [
                  {
                    id: "focuspunch",
                    name: "Focus Punch",
                    type: "Fighting",
                    category: "Physical",
                    power: 150,
                    accuracy: true,
                    priority: -3,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Interrupter",
                stats: { ...pokemon().stats, attack: 120, hp: 220, speed: 160 },
                moves: [
                  {
                    id: "tackle",
                    name: "Tackle",
                    type: "Normal",
                    category: "Physical",
                    power: 40,
                    accuracy: true,
                    pp: 35,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );

  assert.ok(
    interrupted.events.some(
      (event) =>
        event.type === "move_failed" && event.move === "Focus Punch",
    ),
  );
  assert.equal(interrupted.sides[1].team[0].hp, 220);

  const connected = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Puncher",
                stats: { ...pokemon().stats, attack: 180, speed: 160 },
                moves: [
                  {
                    id: "focuspunch",
                    name: "Focus Punch",
                    type: "Fighting",
                    category: "Physical",
                    power: 150,
                    accuracy: true,
                    priority: -3,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Passive",
                stats: { ...pokemon().stats, hp: 220, speed: 40 },
                moves: [
                  {
                    id: "splash",
                    name: "Splash",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(connected.sides[1].team[0].hp < 220);
});

test("supports Detect, Endure, False Swipe, and Axe Kick crash rules", () => {
  const detected = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Guard",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "detect",
                    name: "Detect",
                    type: "Fighting",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 5,
                    volatileStatus: "protect",
                    target: "self",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Attacker",
                moves: [
                  {
                    id: "tackle",
                    name: "Tackle",
                    type: "Normal",
                    category: "Physical",
                    power: 40,
                    accuracy: true,
                    pp: 35,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    detected.events.some(
      (event) => event.type === "move_blocked" && event.source === "protect",
    ),
  );

  const endured = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Endurer",
                stats: { ...pokemon().stats, hp: 120, speed: 160 },
                moves: [
                  {
                    id: "endure",
                    name: "Endure",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                    volatileStatus: "endure",
                    target: "self",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Crusher",
                stats: { ...pokemon().stats, attack: 260, speed: 40 },
                moves: [
                  {
                    id: "megapunch",
                    name: "Mega Punch",
                    type: "Normal",
                    category: "Physical",
                    power: 160,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(endured.sides[0].team[0].hp, 1);

  const swiped = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Swiper",
                stats: { ...pokemon().stats, attack: 260, speed: 160 },
                moves: [
                  {
                    id: "falseswipe",
                    name: "False Swipe",
                    type: "Normal",
                    category: "Physical",
                    power: 200,
                    accuracy: true,
                    pp: 40,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Target",
                stats: { ...pokemon().stats, hp: 80, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(swiped.sides[1].team[0].hp, 1);

  const crashed = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Kicker",
                stats: { ...pokemon().stats, hp: 200, speed: 40 },
                moves: [
                  {
                    id: "axekick",
                    name: "Axe Kick",
                    type: "Fighting",
                    category: "Physical",
                    power: 120,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Shield",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "protect",
                    name: "Protect",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                    volatileStatus: "protect",
                    target: "self",
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(crashed.sides[0].team[0].hp, 100);
  assert.ok(
    crashed.events.some(
      (event) => event.type === "damage" && event.cause === "crash",
    ),
  );
});

test("supports First Impression, Dream Eater, and Endeavor conditions", () => {
  let impression = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Ambusher",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "firstimpression",
                    name: "First Impression",
                    type: "Bug",
                    category: "Physical",
                    power: 90,
                    accuracy: true,
                    priority: 2,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Target",
                stats: { ...pokemon().stats, hp: 300, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(impression.sides[1].team[0].hp < 300);
  impression = resolveSimpleTurn(impression, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    impression.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move_failed" &&
        event.move === "First Impression",
    ),
  );

  const dreamFailed = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Dreamer",
                moves: [
                  {
                    id: "dreameater",
                    name: "Dream Eater",
                    type: "Psychic",
                    category: "Special",
                    power: 100,
                    accuracy: true,
                    pp: 15,
                    drain: [1, 2],
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "AwakeTarget" })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    dreamFailed.events.some(
      (event) => event.type === "move_failed" && event.move === "Dream Eater",
    ),
  );

  const dreamBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Dreamer",
              hp: 80,
              stats: { ...pokemon().stats, hp: 200, specialAttack: 180, speed: 160 },
              moves: [
                {
                  id: "dreameater",
                  name: "Dream Eater",
                  type: "Psychic",
                  category: "Special",
                  power: 100,
                  accuracy: true,
                  pp: 15,
                  drain: [1, 2],
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "SleepingTarget", stats: { ...pokemon().stats, hp: 220 } })],
        },
      ],
    }),
  );
  dreamBattle.sides[0].team[0].hp = 80;
  dreamBattle.sides[1].team[0].status = "slp";
  const dreamHit = resolveSimpleTurn(dreamBattle, [{ move: 1 }, { move: 1 }]);
  assert.ok(dreamHit.sides[0].team[0].hp > 80);
  assert.ok(dreamHit.sides[1].team[0].hp < 220);

  const endeavorBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "LowHp",
              stats: { ...pokemon().stats, hp: 200, speed: 160 },
              moves: [
                {
                  id: "endeavor",
                  name: "Endeavor",
                  type: "Normal",
                  category: "Physical",
                  power: 0,
                  accuracy: true,
                  pp: 5,
                  dynamicDamage: true,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "HighHp", stats: { ...pokemon().stats, hp: 180, speed: 40 } })],
        },
      ],
    }),
  );
  endeavorBattle.sides[0].team[0].hp = 50;
  const endeavored = resolveSimpleTurn(endeavorBattle, [{ move: 1 }, { move: 1 }]);
  assert.equal(endeavored.sides[1].team[0].hp, 50);
});

test("supports ability-changing utility moves", () => {
  const acid = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "AcidUser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "gastroacid",
                    name: "Gastro Acid",
                    type: "Poison",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                    volatileStatus: "gastroacid",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "AbilityTarget", ability: "levitate" })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(acid.sides[1].team[0].volatiles.gastroacid.id, "gastroacid");
  assert.ok(
    acid.events.some(
      (event) =>
        event.type === "ability_suppressed" &&
        event.pokemon === "AbilityTarget" &&
        event.ability === "levitate",
    ),
  );

  const worry = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Seeder",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "worryseed",
                    name: "Worry Seed",
                    type: "Grass",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "Target", ability: "pressure" })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(worry.sides[1].team[0].ability, "insomnia");

  const rolePlayed = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Actor",
                ability: "runaway",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "roleplay",
                    name: "Role Play",
                    type: "Psychic",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "Muse", ability: "adaptability" })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(rolePlayed.sides[0].team[0].ability, "adaptability");
});

test("doubles physical Attack for Huge Power and Pure Power", () => {
  const target = pokemon({
    name: "Target",
    types: ["Normal"],
    stats: { ...pokemon().stats, hp: 300, defence: 120 },
  });
  const playRough = {
    id: "playrough",
    name: "Play Rough",
    type: "Fairy",
    category: "Physical",
    power: 90,
    accuracy: 100,
    pp: 10,
  };
  const megaMawile = pokemon({
    name: "Mega Mawile",
    types: ["Steel", "Fairy"],
    ability: "hugepower",
    stats: { ...pokemon().stats, attack: 105 },
  });
  const ordinaryMawile = pokemon({
    name: "Mawile",
    types: ["Steel", "Fairy"],
    ability: "intimidate",
    stats: { ...pokemon().stats, attack: 105 },
  });
  const purePowerUser = pokemon({
    name: "PurePowerUser",
    types: ["Fairy"],
    ability: "purepower",
    stats: { ...pokemon().stats, attack: 105 },
  });
  const suppressedMegaMawile = {
    ...megaMawile,
    volatiles: { gastroacid: { id: "gastroacid" } },
  };

  const ordinary = calculateDamageRange(ordinaryMawile, target, playRough);
  const hugePower = calculateDamageRange(megaMawile, target, playRough);
  const purePower = calculateDamageRange(purePowerUser, target, playRough);
  const suppressed = calculateDamageRange(suppressedMegaMawile, target, playRough);

  assert.ok(hugePower.maximum > ordinary.maximum * 1.8);
  assert.ok(purePower.maximum > ordinary.maximum * 1.8);
  assert.equal(suppressed.maximum, ordinary.maximum);
});

test("boosts contact move damage for Tough Claws", () => {
  const target = pokemon({
    name: "Target",
    types: ["Normal"],
    stats: { ...pokemon().stats, hp: 300, defence: 120 },
  });
  const contactMove = {
    id: "dragonclaw",
    name: "Dragon Claw",
    type: "Dragon",
    category: "Physical",
    power: 80,
    accuracy: 100,
    pp: 15,
    contact: true,
  };
  const nonContactMove = {
    ...contactMove,
    id: "earthquake",
    name: "Earthquake",
    type: "Ground",
    contact: false,
  };
  const ordinary = pokemon({
    name: "Ordinary",
    ability: "intimidate",
    stats: { ...pokemon().stats, attack: 130 },
  });
  const toughClaws = pokemon({
    name: "ToughClaws",
    ability: "toughclaws",
    stats: { ...pokemon().stats, attack: 130 },
  });

  const ordinaryContact = calculateDamageRange(ordinary, target, contactMove);
  const toughContact = calculateDamageRange(toughClaws, target, contactMove);
  const ordinaryNonContact = calculateDamageRange(ordinary, target, nonContactMove);
  const toughNonContact = calculateDamageRange(toughClaws, target, nonContactMove);

  assert.ok(toughContact.maximum > ordinaryContact.maximum * 1.2);
  assert.equal(toughNonContact.maximum, ordinaryNonContact.maximum);
});

test("applies common stat-drop immunity and Snow Warning entry abilities", () => {
  const clearBodyState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [pokemon({ name: "Intimidator", ability: "intimidate" })],
        },
        {
          name: "AI",
          team: [pokemon({ name: "Clear Body", ability: "clearbody" })],
        },
      ],
    }),
  );
  assert.equal(clearBodyState.sides[1].team[0].boosts.attack, 0);
  assert.ok(
    clearBodyState.events.some(
      (event) =>
        event.type === "ability_activate" && event.ability === "clearbody",
    ),
  );

  const innerFocusState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [pokemon({ name: "Intimidator", ability: "intimidate" })],
        },
        {
          name: "AI",
          team: [pokemon({ name: "Inner Focus", ability: "innerfocus" })],
        },
      ],
    }),
  );
  assert.equal(innerFocusState.sides[1].team[0].boosts.attack, 0);

  const snowState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [pokemon({ name: "Snow Setter", ability: "snowwarning" })],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );
  assert.equal(snowState.field.weather.id, "snow");
});

test("prevents flinching with Inner Focus", () => {
  const fakeOut = {
    id: "fakeout",
    name: "Fake Out",
    type: "Normal",
    category: "Physical",
    power: 40,
    accuracy: true,
    priority: 3,
    pp: 10,
    contact: true,
    volatileStatus: "flinch",
  };
  const tackle = {
    id: "tackle",
    name: "Tackle",
    type: "Normal",
    category: "Physical",
    power: 40,
    accuracy: true,
    pp: 35,
  };
  const result = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [pokemon({ name: "Fake Out User", moves: [fakeOut] })],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Inner Focus Target",
                ability: "innerfocus",
                moves: [tackle],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    result.events.some(
      (event) =>
        event.type === "move" && event.pokemon === "Inner Focus Target",
    ),
  );
  assert.ok(
    !result.events.some(
      (event) =>
        event.type === "cant_move" &&
        event.pokemon === "Inner Focus Target" &&
        event.status === "flinch",
    ),
  );
});

test("applies first-batch offensive ability damage modifiers", () => {
  const target = pokemon({
    name: "Target",
    types: ["Normal"],
    stats: { ...pokemon().stats, hp: 400, defence: 120 },
  });
  const ordinary = pokemon({
    name: "Ordinary",
    stats: { ...pokemon().stats, attack: 140 },
  });
  const flaggedMove = (flag) => ({
    id: `${flag}move`,
    name: `${flag} move`,
    type: "Normal",
    category: "Physical",
    power: 80,
    accuracy: 100,
    pp: 10,
    flags: { [flag]: true },
  });
  const abilityDamage = (ability, flag) =>
    calculateDamageRange(
      { ...ordinary, ability },
      target,
      flaggedMove(flag),
    ).maximum;
  const ordinaryDamage = (flag) =>
    calculateDamageRange(ordinary, target, flaggedMove(flag)).maximum;

  assert.ok(abilityDamage("ironfist", "punch") > ordinaryDamage("punch"));
  assert.ok(abilityDamage("strongjaw", "bite") > ordinaryDamage("bite") * 1.4);
  assert.ok(abilityDamage("sharpness", "slicing") > ordinaryDamage("slicing") * 1.4);

  const waterMove = {
    ...flaggedMove("contact"),
    id: "waterfall",
    name: "Waterfall",
    type: "Water",
  };
  const torrentUser = {
    ...ordinary,
    ability: "torrent",
    hp: Math.floor(ordinary.stats.hp / 3),
  };
  assert.ok(
    calculateDamageRange(torrentUser, target, waterMove).maximum >
      calculateDamageRange(ordinary, target, waterMove).maximum * 1.4,
  );

  const resistedTarget = { ...target, types: ["Rock"] };
  assert.ok(
    calculateDamageRange(
      { ...ordinary, ability: "tintedlens" },
      resistedTarget,
      flaggedMove("contact"),
    ).maximum >
      calculateDamageRange(
        ordinary,
        resistedTarget,
        flaggedMove("contact"),
      ).maximum *
        1.8,
  );
  assert.equal(
    calculateDamageRange(
      {
        ...ordinary,
        ability: "tintedlens",
        volatiles: { gastroacid: { id: "gastroacid" } },
      },
      resistedTarget,
      flaggedMove("contact"),
    ).maximum,
    calculateDamageRange(
      ordinary,
      resistedTarget,
      flaggedMove("contact"),
    ).maximum,
  );
});

test("applies defensive ability modifiers, Swift Swim, and Moxie", () => {
  const fightingMove = {
    id: "closecombat",
    name: "Close Combat",
    type: "Fighting",
    category: "Physical",
    power: 120,
    accuracy: true,
    pp: 5,
  };
  const attacker = pokemon({
    name: "Attacker",
    types: ["Fighting"],
    stats: { ...pokemon().stats, attack: 150 },
  });
  const ordinaryTarget = pokemon({
    name: "Ordinary Target",
    types: ["Normal"],
    stats: { ...pokemon().stats, hp: 500, defence: 140 },
  });
  const ordinaryDamage = calculateDamageRange(
    attacker,
    ordinaryTarget,
    fightingMove,
  ).maximum;
  for (const ability of ["filter", "solidrock", "prismarmor"]) {
    const reduced = calculateDamageRange(
      attacker,
      { ...ordinaryTarget, ability },
      fightingMove,
    ).maximum;
    assert.ok(reduced < ordinaryDamage * 0.8);
  }
  assert.equal(
    calculateDamageRange(
      { ...attacker, ability: "moldbreaker" },
      { ...ordinaryTarget, ability: "filter" },
      fightingMove,
    ).maximum,
    ordinaryDamage,
  );

  const guaranteedCritical = {
    ...fightingMove,
    id: "wickedblow",
    name: "Wicked Blow",
    type: "Dark",
    willCrit: true,
  };
  const armorResult = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Critical User",
                stats: { ...pokemon().stats, speed: 150 },
                moves: [guaranteedCritical],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Shell Armor",
                ability: "shellarmor",
                moves: [{ ...fightingMove, power: 1 }],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    !armorResult.events.some(
      (event) => event.type === "critical" && event.pokemon === "Shell Armor",
    ),
  );

  const rainBattle = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Swift Swimmer",
              ability: "swiftswim",
              stats: { ...pokemon().stats, speed: 80 },
              moves: [fightingMove],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Faster Target",
              stats: { ...pokemon().stats, speed: 120 },
              moves: [fightingMove],
            }),
          ],
        },
      ],
    }),
  );
  rainBattle.field.weather = { id: "raindance", turns: 5 };
  const rainState = resolveSimpleTurn(
    rainBattle,
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(
    rainState.events.find((event) => event.type === "move")?.pokemon,
    "Swift Swimmer",
  );

  const moxieResult = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Moxie User",
                ability: "moxie",
                stats: { ...pokemon().stats, attack: 200, speed: 150 },
                moves: [fightingMove],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Low HP Target",
                hp: 1,
                stats: { ...pokemon().stats, hp: 100 },
                moves: [fightingMove],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(moxieResult.sides[0].team[0].boosts.attack, 1);
});

test("supports common trainer abilities required by strict native scenarios", () => {
  const passiveMove = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 40,
  };

  const downloadState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Porygon2",
              ability: "download",
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "DownloadTarget",
              stats: { ...pokemon().stats, defence: 70, specialDefence: 120 },
            }),
          ],
        },
      ],
    }),
  );
  assert.equal(downloadState.sides[0].team[0].boosts.attack, 1);

  const intrepidState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Zacian-Crowned",
              ability: "intrepidsword",
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );
  assert.equal(intrepidState.sides[0].team[0].boosts.attack, 1);

  const competitiveState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Intimidator",
              ability: "intimidate",
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Articuno-Galar",
              ability: "competitive",
            }),
          ],
        },
      ],
    }),
  );
  assert.equal(competitiveState.sides[1].team[0].boosts.attack, -1);
  assert.equal(competitiveState.sides[1].team[0].boosts.specialAttack, 2);

  const competitiveMoveDropState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Growler",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "growl",
                    name: "Growl",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                    boosts: { attack: -1 },
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "CompetitiveTarget",
                ability: "competitive",
                stats: { ...pokemon().stats, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(competitiveMoveDropState.sides[1].team[0].boosts.attack, -1);
  assert.equal(
    competitiveMoveDropState.sides[1].team[0].boosts.specialAttack,
    2,
  );

  const speedBoostState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Blaziken",
                ability: "speedboost",
                stats: { ...pokemon().stats, speed: 80 },
                moves: [passiveMove],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ moves: [passiveMove] })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(speedBoostState.sides[0].team[0].boosts.speed, 1);

  const punch = {
    id: "machpunch",
    name: "Mach Punch",
    type: "Fighting",
    category: "Physical",
    power: 40,
    accuracy: true,
    pp: 30,
    contact: true,
  };
  const ghostTarget = pokemon({
    name: "GhostTarget",
    types: ["Ghost"],
    stats: { ...pokemon().stats, hp: 200, defence: 100 },
  });
  assert.equal(
    calculateDamageRange(pokemon({ ability: "mindseye" }), ghostTarget, punch)
      .effectiveness,
    1,
  );
  assert.equal(
    calculateDamageRange(pokemon({ ability: "technician" }), pokemon(), punch)
      .abilityModifier,
    1.5,
  );

  const overgrowUser = pokemon({
    ability: "overgrow",
    types: ["Grass"],
    hp: 39,
    stats: { ...pokemon().stats, hp: 120, specialAttack: 140 },
  });
  const overgrowGrass = {
    id: "energyball",
    name: "Energy Ball",
    type: "Grass",
    category: "Special",
    power: 90,
    accuracy: true,
    pp: 10,
  };
  assert.equal(
    calculateDamageRange(overgrowUser, pokemon(), overgrowGrass).abilityModifier,
    1.5,
  );

  const sturdyState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Attacker",
                stats: { ...pokemon().stats, attack: 300, speed: 160 },
                moves: [
                  {
                    id: "superhit",
                    name: "Super Hit",
                    type: "Normal",
                    category: "Physical",
                    power: 250,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SturdyTarget",
                ability: "sturdy",
                stats: { ...pokemon().stats, hp: 120, defence: 40, speed: 40 },
                moves: [passiveMove],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(sturdyState.sides[1].team[0].hp, 1);

  const teravoltState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Zekrom",
                ability: "teravolt",
                stats: { ...pokemon().stats, attack: 300, speed: 160 },
                moves: [
                  {
                    id: "fusionbolt",
                    name: "Fusion Bolt",
                    type: "Electric",
                    category: "Physical",
                    power: 250,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SturdyTarget",
                ability: "sturdy",
                stats: { ...pokemon().stats, hp: 120, defence: 40, speed: 40 },
                moves: [passiveMove],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(teravoltState.sides[1].team[0].fainted, true);

  const recoilMove = {
    id: "headsmash",
    name: "Head Smash",
    type: "Rock",
    category: "Physical",
    power: 120,
    accuracy: true,
    pp: 5,
    recoil: [1, 2],
  };
  const rockHeadState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Aerodactyl",
                ability: "rockhead",
                stats: { ...pokemon().stats, attack: 180, speed: 160 },
                moves: [recoilMove],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "RecoilTarget", stats: { ...pokemon().stats, hp: 300, speed: 40 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    !rockHeadState.events.some(
      (event) => event.pokemon === "Aerodactyl" && event.cause === "recoil",
    ),
  );

  let staticState = null;
  for (let seed = 1; seed <= 20 && !staticState; seed += 1) {
    const next = resolveSimpleTurn(
      createSimpleBattle(
        setup({
          seed,
          strictAbilityValidation: true,
          sides: [
            {
              name: "Player",
              team: [
                pokemon({
                  name: "ContactUser",
                  stats: { ...pokemon().stats, attack: 140, speed: 160 },
                  moves: [punch],
                }),
              ],
            },
            {
              name: "AI",
              team: [
                pokemon({
                  name: "Pikachu",
                  ability: "static",
                  stats: { ...pokemon().stats, hp: 240, speed: 40 },
                }),
              ],
            },
          ],
        }),
      ),
      [{ move: 1 }, { move: 1 }],
    );
    if (next.sides[0].team[0].status === "par") staticState = next;
  }
  assert.ok(staticState, "Static should be able to paralyze a contact attacker");
  assert.ok(
    staticState.events.some(
      (event) =>
        event.type === "ability_activate" &&
        event.ability === "static" &&
        event.pokemon === "Pikachu",
    ),
  );

  assert.doesNotThrow(() =>
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({ ability: "unseenfist" }),
              pokemon({ ability: "pickpocket" }),
              pokemon({ ability: "lightmetal" }),
              pokemon({ ability: "technician" }),
              pokemon({ ability: "mindseye" }),
              pokemon({ ability: "teravolt" }),
              pokemon({ ability: "static" }),
              pokemon({ ability: "rockhead" }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({ ability: "download" }),
              pokemon({ ability: "sturdy" }),
              pokemon({ ability: "speedboost" }),
              pokemon({ ability: "competitive" }),
              pokemon({ ability: "overgrow" }),
              pokemon({ ability: "intrepidsword" }),
            ],
          },
        ],
      }),
    ),
  );
});

test("supports terrain, weather, knockout, and powder immunity abilities", () => {
  const passiveMove = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 40,
  };
  const specialMove = {
    id: "dragonpulse",
    name: "Dragon Pulse",
    type: "Dragon",
    category: "Special",
    power: 85,
    accuracy: true,
    pp: 10,
  };
  const physicalMove = {
    id: "bodyslam",
    name: "Body Slam",
    type: "Normal",
    category: "Physical",
    power: 85,
    accuracy: true,
    pp: 15,
  };

  const electricSurgeState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [pokemon({ name: "Raichu-Mega", ability: "electricsurge" })],
        },
        { name: "AI", team: [pokemon()] },
      ],
    }),
  );
  assert.equal(electricSurgeState.field.terrain.id, "electricterrain");

  const hadronState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Miraidon",
              ability: "hadronengine",
              stats: { ...pokemon().stats, specialAttack: 160 },
              moves: [specialMove],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ moves: [passiveMove] })] },
      ],
    }),
  );
  const hadronUser = hadronState.sides[0].team[0];
  const hadronTarget = hadronState.sides[1].team[0];
  const hadronDamage = calculateDamageRange(
    hadronUser,
    hadronTarget,
    hadronUser.moves[0],
    { state: hadronState, attackerSide: 0, defenderSide: 1 },
  );
  const ordinarySpecialDamage = calculateDamageRange(
    { ...hadronUser, ability: "" },
    hadronTarget,
    hadronUser.moves[0],
    { state: hadronState, attackerSide: 0, defenderSide: 1 },
  );
  assert.equal(hadronState.field.terrain.id, "electricterrain");
  assert.ok(hadronDamage.maximum > ordinarySpecialDamage.maximum * 1.3);

  const orichalcumState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Koraidon",
              ability: "orichalcumpulse",
              stats: { ...pokemon().stats, attack: 160 },
              moves: [physicalMove],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ moves: [passiveMove] })] },
      ],
    }),
  );
  const orichalcumUser = orichalcumState.sides[0].team[0];
  const orichalcumTarget = orichalcumState.sides[1].team[0];
  const orichalcumDamage = calculateDamageRange(
    orichalcumUser,
    orichalcumTarget,
    orichalcumUser.moves[0],
    { state: orichalcumState, attackerSide: 0, defenderSide: 1 },
  );
  const ordinaryPhysicalDamage = calculateDamageRange(
    { ...orichalcumUser, ability: "" },
    orichalcumTarget,
    orichalcumUser.moves[0],
    { state: orichalcumState, attackerSide: 0, defenderSide: 1 },
  );
  assert.equal(orichalcumState.field.weather.id, "sunnyday");
  assert.ok(orichalcumDamage.maximum > ordinaryPhysicalDamage.maximum * 1.3);

  const beastBoostState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Naganadel",
                ability: "beastboost",
                stats: {
                  ...pokemon().stats,
                  specialAttack: 180,
                  speed: 160,
                },
                moves: [{ ...specialMove, power: 250 }],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "KnockoutTarget",
                stats: { ...pokemon().stats, hp: 80, speed: 40 },
                moves: [passiveMove],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(beastBoostState.sides[0].team[0].boosts.specialAttack, 1);
  assert.ok(
    beastBoostState.events.some(
      (event) =>
        event.type === "ability_activate" &&
        event.ability === "beastboost",
    ),
  );

  const overcoatState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "PowderUser",
                ability: "sandstream",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "spore",
                    name: "Spore",
                    type: "Grass",
                    category: "Status",
                    accuracy: true,
                    pp: 15,
                    status: "slp",
                    flags: { powder: true },
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Kommo-o",
                ability: "overcoat",
                stats: { ...pokemon().stats, hp: 180, speed: 40 },
                moves: [passiveMove],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(overcoatState.sides[1].team[0].status, "");
  assert.equal(overcoatState.sides[1].team[0].hp, 180);
  assert.ok(
    overcoatState.events.some(
      (event) =>
        event.type === "move_blocked" &&
        event.source === "overcoat" &&
        event.pokemon === "Kommo-o",
    ),
  );
});

test("rejects unsupported abilities in strict validation mode", () => {
  assert.throws(
    () =>
      createSimpleBattle(
        setup({
          strictAbilityValidation: true,
          sides: [
            { name: "Player", team: [pokemon({ name: "MysteryMon" })] },
            {
              name: "AI",
              team: [
                pokemon({
                  name: "UnknownAbilityMon",
                  ability: "notarealability",
                }),
              ],
            },
          ],
        }),
      ),
    /Unsupported ability.*notarealability/,
  );
});

test("boosts Calyrex rider abilities after scoring a knockout", () => {
  const cases = [
    {
      ability: "asonespectrier",
      move: {
        id: "astralbarrage",
        name: "Astral Barrage",
        type: "Ghost",
        category: "Special",
        power: 120,
        accuracy: true,
        pp: 5,
      },
      stat: "specialAttack",
      eventStat: "spa",
    },
    {
      ability: "asoneglastrier",
      move: {
        id: "glaciallance",
        name: "Glacial Lance",
        type: "Ice",
        category: "Physical",
        power: 120,
        accuracy: true,
        pp: 5,
      },
      stat: "attack",
      eventStat: "atk",
    },
    {
      ability: "chillingneigh",
      move: {
        id: "iciclecrash",
        name: "Icicle Crash",
        type: "Ice",
        category: "Physical",
        power: 120,
        accuracy: true,
        pp: 10,
      },
      stat: "attack",
      eventStat: "atk",
    },
    {
      ability: "grimneigh",
      move: {
        id: "shadowball",
        name: "Shadow Ball",
        type: "Ghost",
        category: "Special",
        power: 120,
        accuracy: true,
        pp: 15,
      },
      stat: "specialAttack",
      eventStat: "spa",
    },
  ];

  for (const entry of cases) {
    const state = resolveSimpleTurn(
      createSimpleBattle(
        setup({
          strictAbilityValidation: true,
          sides: [
            {
              name: "Player",
              team: [
                pokemon({
                  name: `Sweeper-${entry.ability}`,
                  ability: entry.ability,
                  stats: {
                    ...pokemon().stats,
                    attack: 220,
                    specialAttack: 220,
                    speed: 160,
                  },
                  moves: [entry.move],
                }),
              ],
            },
            {
              name: "AI",
              team: [
                pokemon({
                  name: "KnockoutTarget",
                  types: ["Psychic"],
                  stats: {
                    ...pokemon().stats,
                    hp: 40,
                    defence: 40,
                    specialDefence: 40,
                    speed: 40,
                  },
                }),
              ],
            },
          ],
        }),
      ),
      [{ move: 1 }, { move: 1 }],
    );

    const sweeper = state.sides[0].team[0];
    assert.equal(state.sides[1].team[0].fainted, true);
    assert.equal(sweeper.boosts[entry.stat], 1);
    assert.ok(
      state.events.some(
        (event) =>
          event.type === "ability_activate" &&
          event.pokemon === sweeper.name &&
          event.ability === entry.ability,
      ),
    );
    assert.ok(
      state.events.some(
        (event) =>
          event.type === "stat_change" &&
          event.pokemon === sweeper.name &&
          event.stat === entry.eventStat &&
          event.amount === 1 &&
          event.source === entry.ability,
      ),
    );
  }
});

test("applies simple status immunity abilities", () => {
  const cases = [
    {
      ability: "limber",
      status: "par",
      move: {
        id: "thunderwave",
        name: "Thunder Wave",
        type: "Electric",
        category: "Status",
        accuracy: true,
        pp: 20,
        status: "par",
      },
    },
    {
      ability: "waterveil",
      status: "brn",
      move: {
        id: "willowisp",
        name: "Will-O-Wisp",
        type: "Fire",
        category: "Status",
        accuracy: true,
        pp: 15,
        status: "brn",
      },
    },
    {
      ability: "immunity",
      status: "tox",
      move: {
        id: "toxic",
        name: "Toxic",
        type: "Poison",
        category: "Status",
        accuracy: true,
        pp: 10,
        status: "tox",
      },
    },
    {
      ability: "insomnia",
      status: "slp",
      move: {
        id: "sleeppowder",
        name: "Sleep Powder",
        type: "Grass",
        category: "Status",
        accuracy: true,
        pp: 15,
        status: "slp",
      },
    },
    {
      ability: "vitalspirit",
      status: "slp",
      move: {
        id: "sleeppowder",
        name: "Sleep Powder",
        type: "Grass",
        category: "Status",
        accuracy: true,
        pp: 15,
        status: "slp",
      },
    },
  ];

  for (const entry of cases) {
    const state = resolveSimpleTurn(
      createSimpleBattle(
        setup({
          strictAbilityValidation: true,
          sides: [
            {
              name: "Player",
              team: [
                pokemon({
                  name: "StatusUser",
                  stats: { ...pokemon().stats, speed: 160 },
                  moves: [entry.move],
                }),
              ],
            },
            {
              name: "AI",
              team: [
                pokemon({
                  name: "ImmuneTarget",
                  ability: entry.ability,
                  stats: { ...pokemon().stats, speed: 40 },
                }),
              ],
            },
          ],
        }),
      ),
      [{ move: 1 }, { move: 1 }],
    );
    assert.equal(
      state.sides[1].team[0].status,
      "",
      `${entry.ability} should block ${entry.status}`,
    );
  }
});

test("applies simple defensive and entry abilities", () => {
  const iceBeam = {
    id: "icebeam",
    name: "Ice Beam",
    type: "Ice",
    category: "Special",
    power: 90,
    accuracy: 100,
    pp: 10,
  };
  const baseRange = calculateDamageRange(
    pokemon({ stats: { ...pokemon().stats, specialAttack: 140 } }),
    pokemon({ hp: 200, stats: { ...pokemon().stats, hp: 200 } }),
    iceBeam,
  );
  const thickFatRange = calculateDamageRange(
    pokemon({ stats: { ...pokemon().stats, specialAttack: 140 } }),
    pokemon({
      ability: "thickfat",
      hp: 200,
      stats: { ...pokemon().stats, hp: 200 },
    }),
    iceBeam,
  );
  const multiscaleRange = calculateDamageRange(
    pokemon({ stats: { ...pokemon().stats, attack: 140 } }),
    pokemon({
      ability: "multiscale",
      hp: 200,
      stats: { ...pokemon().stats, hp: 200 },
    }),
    {
      id: "closecombat",
      name: "Close Combat",
      type: "Fighting",
      category: "Physical",
      power: 120,
      accuracy: 100,
      pp: 5,
    },
  );
  const chippedMultiscaleRange = calculateDamageRange(
    pokemon({ stats: { ...pokemon().stats, attack: 140 } }),
    pokemon({
      ability: "multiscale",
      hp: 199,
      stats: { ...pokemon().stats, hp: 200 },
    }),
    {
      id: "closecombat",
      name: "Close Combat",
      type: "Fighting",
      category: "Physical",
      power: 120,
      accuracy: 100,
      pp: 5,
    },
  );
  const shadowShieldRange = calculateDamageRange(
    pokemon({ stats: { ...pokemon().stats, attack: 140 } }),
    pokemon({
      ability: "shadowshield",
      hp: 200,
      stats: { ...pokemon().stats, hp: 200 },
    }),
    {
      id: "closecombat",
      name: "Close Combat",
      type: "Fighting",
      category: "Physical",
      power: 120,
      accuracy: 100,
      pp: 5,
    },
  );

  assert.ok(thickFatRange.maximum < baseRange.maximum);
  assert.ok(multiscaleRange.maximum < chippedMultiscaleRange.maximum);
  assert.equal(shadowShieldRange.maximum, multiscaleRange.maximum);

  const entryState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Lead",
              ability: "intimidate",
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "PhysicalTarget" })] },
      ],
    }),
  );
  assert.equal(entryState.sides[1].team[0].boosts.attack, -1);
  assert.ok(
    entryState.events.some(
      (event) =>
        event.type === "ability_activate" &&
        event.ability === "intimidate" &&
        event.target === "PhysicalTarget",
    ),
  );

  const simpleState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Debuffer",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "growl",
                    name: "Growl",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                    boosts: { atk: -1 },
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SimpleTarget",
                ability: "simple",
                stats: { ...pokemon().stats, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(simpleState.sides[1].team[0].boosts.attack, -2);
});

test("applies Pressure PP drain and Own Tempo confusion immunity", () => {
  const pressured = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Attacker",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "PressureWall",
                ability: "pressure",
                stats: { ...pokemon().stats, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(pressured.sides[0].team[0].moves[0].pp, 8);
  assert.ok(
    pressured.events.some(
      (event) => event.type === "pp_reduced" && event.source === "pressure",
    ),
  );

  const confused = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Confuser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "confuseray",
                    name: "Confuse Ray",
                    type: "Ghost",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                    volatileStatus: "confusion",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SteadyTarget",
                ability: "owntempo",
                stats: { ...pokemon().stats, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(confused.sides[1].team[0].volatiles.confusion, undefined);
});

test("supports Ingrain, Wish, Heal Pulse, and Charge utility flow", () => {
  let rooted = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Rooted",
              stats: { ...pokemon().stats, hp: 160, speed: 160 },
              moves: [
                {
                  id: "ingrain",
                  name: "Ingrain",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  volatileStatus: "ingrain",
                  target: "self",
                },
              ],
            }),
            pokemon({ name: "Reserve" }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Passive",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  rooted.sides[0].team[0].hp = 80;
  rooted = resolveSimpleTurn(rooted, [{ move: 1 }, { move: 1 }]);
  assert.equal(rooted.sides[0].team[0].volatiles.ingrain.id, "ingrain");
  assert.ok(rooted.sides[0].team[0].hp > 80);
  assert.throws(
    () => resolveSimpleTurn(rooted, [{ switch: 2 }, { move: 1 }]),
    /trapped/,
  );

  let wished = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Wisher",
              stats: { ...pokemon().stats, hp: 200, speed: 160 },
              moves: [
                {
                  id: "wish",
                  name: "Wish",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                  slotCondition: "wish",
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Passive" })] },
      ],
    }),
  );
  wished.sides[0].team[0].hp = 60;
  wished = resolveSimpleTurn(wished, [{ move: 1 }, { move: 1 }]);
  assert.equal(wished.sides[0].conditions.wish.turns, 1);
  wished = resolveSimpleTurn(wished, [{ move: 1 }, { move: 1 }]);
  assert.ok(wished.sides[0].team[0].hp > 60);
  assert.equal(wished.sides[0].conditions.wish, undefined);

  const healedTarget = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Cleric",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "healpulse",
                  name: "Heal Pulse",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "Patient", stats: { ...pokemon().stats, hp: 200 } })],
        },
      ],
    }),
  );
  healedTarget.sides[1].team[0].hp = 70;
  const pulsed = resolveSimpleTurn(healedTarget, [{ move: 1 }, { move: 1 }]);
  assert.ok(pulsed.sides[1].team[0].hp > 70);

  let charged = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Charger",
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "charge",
                    name: "Charge",
                    type: "Electric",
                    category: "Status",
                    accuracy: true,
                    pp: 20,
                    volatileStatus: "charge",
                    target: "self",
                  },
                  {
                    id: "thunderbolt",
                    name: "Thunderbolt",
                    type: "Electric",
                    category: "Special",
                    power: 90,
                    accuracy: true,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "Target", stats: { ...pokemon().stats, hp: 300, speed: 40 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const baseline = calculateDamageRange(
    charged.sides[0].team[0],
    charged.sides[1].team[0],
    charged.sides[0].team[0].moves[1],
    { state: charged, attackerSide: 0, defenderSide: 1 },
  ).maximum;
  charged = resolveSimpleTurn(charged, [{ move: 2 }, { move: 1 }]);
  const damage = charged.events.find(
    (event) =>
      event.turn === 2 &&
      event.type === "damage" &&
      event.pokemon === "Target",
  )?.damage;
  assert.ok(damage > baseline);
});

test("supports trapping moves preventing manual switches", () => {
  let trapped = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Trapper",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "block",
                    name: "Block",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 5,
                    volatileStatus: "trapped",
                  },
                  {
                    id: "jawlock",
                    name: "Jaw Lock",
                    type: "Dark",
                    category: "Physical",
                    power: 80,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({ name: "Target" }),
              pokemon({ name: "Reserve" }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(trapped.sides[1].team[0].volatiles.trapped);
  assert.throws(
    () => resolveSimpleTurn(trapped, [{ move: 1 }, { switch: 2 }]),
    /trapped/,
  );

  trapped = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "JawUser",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "jawlock",
                    name: "Jaw Lock",
                    type: "Dark",
                    category: "Physical",
                    power: 80,
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
              pokemon({ name: "ReserveA" }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({ name: "JawTarget" }),
              pokemon({ name: "ReserveB" }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(trapped.sides[0].team[0].volatiles.jawlock);
  assert.ok(trapped.sides[1].team[0].volatiles.jawlock);
});

test("supports Counter, Mirror Coat, and Metal Burst retaliation damage", () => {
  const countered = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "CounterUser",
                stats: { ...pokemon().stats, hp: 260, speed: 40 },
                moves: [
                  {
                    id: "counter",
                    name: "Counter",
                    type: "Fighting",
                    category: "Physical",
                    power: 0,
                    accuracy: true,
                    priority: -5,
                    pp: 20,
                    dynamicDamage: true,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "PhysicalAttacker",
                stats: { ...pokemon().stats, hp: 260, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const physicalDamage = countered.events.find(
    (event) => event.type === "damage" && event.pokemon === "CounterUser",
  )?.damage;
  const counterDamage = countered.events.find(
    (event) => event.type === "damage" && event.pokemon === "PhysicalAttacker",
  )?.damage;
  assert.equal(counterDamage, physicalDamage * 2);

  const mirrored = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "MirrorUser",
                stats: { ...pokemon().stats, hp: 260, speed: 40 },
                moves: [
                  {
                    id: "mirrorcoat",
                    name: "Mirror Coat",
                    type: "Psychic",
                    category: "Special",
                    power: 0,
                    accuracy: true,
                    priority: -5,
                    pp: 20,
                    dynamicDamage: true,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SpecialAttacker",
                stats: { ...pokemon().stats, hp: 260, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "waterpulse",
                    name: "Water Pulse",
                    type: "Water",
                    category: "Special",
                    power: 60,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const specialDamage = mirrored.events.find(
    (event) => event.type === "damage" && event.pokemon === "MirrorUser",
  )?.damage;
  const mirrorDamage = mirrored.events.find(
    (event) => event.type === "damage" && event.pokemon === "SpecialAttacker",
  )?.damage;
  assert.equal(mirrorDamage, specialDamage * 2);

  const bursted = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "BurstUser",
                stats: { ...pokemon().stats, hp: 260, speed: 40 },
                moves: [
                  {
                    id: "metalburst",
                    name: "Metal Burst",
                    type: "Steel",
                    category: "Physical",
                    power: 0,
                    accuracy: true,
                    pp: 10,
                    dynamicDamage: true,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "BurstTarget",
                stats: { ...pokemon().stats, hp: 260, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "waterpulse",
                    name: "Water Pulse",
                    type: "Water",
                    category: "Special",
                    power: 60,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const burstTaken = bursted.events.find(
    (event) => event.type === "damage" && event.pokemon === "BurstUser",
  )?.damage;
  const burstDamage = bursted.events.find(
    (event) => event.type === "damage" && event.pokemon === "BurstTarget",
  )?.damage;
  assert.equal(burstDamage, Math.floor(burstTaken * 1.5));
});

test("supports cleric, self-cost, random power, and poison-gated utility moves", () => {
  const clericState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Cleric",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "aromatherapy",
                  name: "Aromatherapy",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 5,
                },
                {
                  id: "junglehealing",
                  name: "Jungle Healing",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
            pokemon({ name: "BenchedAlly" }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Passive",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  clericState.sides[0].team[0].status = "brn";
  clericState.sides[0].team[1].status = "par";
  const cured = resolveSimpleTurn(clericState, [{ move: 1 }, { move: 1 }]);
  assert.equal(cured.sides[0].team[0].status, "");
  assert.equal(cured.sides[0].team[1].status, "");

  const jungleState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "JungleUser",
              stats: { ...pokemon().stats, hp: 200, speed: 160 },
              moves: [
                {
                  id: "junglehealing",
                  name: "Jungle Healing",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Passive" })] },
      ],
    }),
  );
  jungleState.sides[0].team[0].hp = 80;
  jungleState.sides[0].team[0].status = "psn";
  const healed = resolveSimpleTurn(jungleState, [{ move: 1 }, { move: 1 }]);
  assert.equal(healed.sides[0].team[0].status, "");
  assert.ok(healed.sides[0].team[0].hp > 80);

  const selfCost = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "BeamUser",
                stats: { ...pokemon().stats, hp: 200, specialAttack: 180, speed: 160 },
                moves: [
                  {
                    id: "steelbeam",
                    name: "Steel Beam",
                    type: "Steel",
                    category: "Special",
                    power: 140,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "BeamTarget", stats: { ...pokemon().stats, hp: 300 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(selfCost.sides[0].team[0].hp <= 100);
  assert.ok(
    selfCost.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "BeamUser" &&
        event.cause === "hp_cost" &&
        event.damage === 100 &&
        event.source === "Steel Beam",
    ),
  );

  const magnitude = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "MagnitudeUser",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "magnitude",
                    name: "Magnitude",
                    type: "Ground",
                    category: "Physical",
                    power: 1,
                    accuracy: true,
                    pp: 30,
                    dynamicPower: true,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "GroundedTarget", stats: { ...pokemon().stats, hp: 300 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    magnitude.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Magnitude" &&
        event.reason.startsWith("magnitude_"),
    ),
  );

  const venom = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Drencher",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "venomdrench",
                  name: "Venom Drench",
                  type: "Poison",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  boosts: { attack: -1, specialAttack: -1, speed: -1 },
                },
                {
                  id: "teeterdance",
                  name: "Teeter Dance",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  volatileStatus: "confusion",
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "PoisonedTarget" })] },
      ],
    }),
  );
  venom.sides[1].team[0].status = "psn";
  const drenched = resolveSimpleTurn(venom, [{ move: 1 }, { move: 1 }]);
  assert.equal(drenched.sides[1].team[0].boosts.attack, -1);
  assert.equal(drenched.sides[1].team[0].boosts.specialAttack, -1);
  assert.equal(drenched.sides[1].team[0].boosts.speed, -1);

  const danced = resolveSimpleTurn(drenched, [{ move: 2 }, { move: 1 }]);
  assert.equal(danced.sides[1].team[0].volatiles.confusion.id, "confusion");
});

test("supports Sleep Talk, Transform, and Pursuit switch interception", () => {
  const sleepTalkBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Sleeper",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "sleeptalk",
                  name: "Sleep Talk",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
                {
                  id: "waterpulse",
                  name: "Water Pulse",
                  type: "Water",
                  category: "Special",
                  power: 60,
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Target", stats: { ...pokemon().stats, hp: 220 } })] },
      ],
    }),
  );
  sleepTalkBattle.sides[0].team[0].status = "slp";
  sleepTalkBattle.sides[0].team[0].statusTurns = 2;
  const talked = resolveSimpleTurn(sleepTalkBattle, [{ move: 1 }, { move: 1 }]);
  assert.ok(talked.sides[1].team[0].hp < 220);
  assert.ok(
    talked.events.some(
      (event) =>
        event.type === "called_move" &&
        event.source === "Sleep Talk" &&
        event.move === "Water Pulse",
    ),
  );

  const transformed = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "DittoLike",
                types: ["Normal"],
                ability: "limber",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "transform",
                    name: "Transform",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                id: "dragonmuse",
                name: "DragonMuse",
                types: ["Dragon", "Flying"],
                ability: "multiscale",
                stats: {
                  hp: 200,
                  attack: 150,
                  defence: 120,
                  specialAttack: 90,
                  specialDefence: 110,
                  speed: 80,
                },
                moves: [
                  {
                    id: "dragonclaw",
                    name: "Dragon Claw",
                    type: "Dragon",
                    category: "Physical",
                    power: 80,
                    accuracy: true,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const transformedUser = transformed.sides[0].team[0];
  assert.equal(transformedUser.name, "DragonMuse");
  assert.deepEqual(transformedUser.types, ["Dragon", "Flying"]);
  assert.equal(transformedUser.ability, "multiscale");
  assert.equal(transformedUser.stats.attack, 150);
  assert.equal(transformedUser.stats.hp, 120);
  assert.equal(transformedUser.moves[0].id, "dragonclaw");
  assert.equal(transformedUser.moves[0].pp, 5);

  const pursuitBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Pursuer",
              stats: { ...pokemon().stats, attack: 160, speed: 40 },
              moves: [
                {
                  id: "pursuit",
                  name: "Pursuit",
                  type: "Dark",
                  category: "Physical",
                  power: 40,
                  accuracy: true,
                  pp: 20,
                  dynamicPower: true,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({ name: "FleeingTarget", stats: { ...pokemon().stats, hp: 300, speed: 160 } }),
            pokemon({ name: "Reserve" }),
          ],
        },
      ],
    }),
  );
  const intercepted = resolveSimpleTurn(pursuitBattle, [
    { move: 1 },
    { switch: 2 },
  ]);
  const pursuitDamage = intercepted.events.find(
    (event) =>
      event.type === "damage" &&
      event.move === "Pursuit" &&
      event.pokemon === "FleeingTarget",
  )?.damage;
  assert.ok(pursuitDamage > 0);
  assert.ok(
    intercepted.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Pursuit" &&
        event.reason === "target_switching",
    ),
  );
  assert.equal(intercepted.events.find((event) => event.type === "move")?.move, "Pursuit");
  assert.equal(intercepted.sides[1].active, 1);
});

test("supports protective variants with contact-style punishments", () => {
  const kingShielded = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "ShieldUser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "kingsshield",
                    name: "King's Shield",
                    type: "Steel",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                    volatileStatus: "protect",
                    target: "self",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "ContactAttacker",
                stats: { ...pokemon().stats, attack: 160, speed: 40 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(kingShielded.sides[0].team[0].hp, 120);
  assert.equal(kingShielded.sides[1].team[0].boosts.attack, -1);
  assert.ok(
    kingShielded.events.some(
      (event) =>
        event.type === "move_blocked" &&
        event.source === "King's Shield",
    ),
  );

  const burned = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "BulwarkUser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "burningbulwark",
                    name: "Burning Bulwark",
                    type: "Fire",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                    volatileStatus: "protect",
                    target: "self",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "BurnableAttacker",
                stats: { ...pokemon().stats, attack: 160, speed: 40 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(burned.sides[0].team[0].hp, 120);
  assert.equal(burned.sides[1].team[0].status, "brn");
  assert.ok(
    burned.events.some(
      (event) =>
        event.type === "move_blocked" &&
        event.source === "Burning Bulwark",
    ),
  );
});

test("supports special attack callbacks for hazards, burn cures, category shifts, and retaliations", () => {
  const sparkling = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Singer",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "sparklingaria",
                  name: "Sparkling Aria",
                  type: "Water",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "BurnedTarget", stats: { ...pokemon().stats, hp: 260 } })],
        },
      ],
    }),
  );
  sparkling.sides[1].team[0].status = "brn";
  const aria = resolveSimpleTurn(sparkling, [{ move: 1 }, { move: 1 }]);
  assert.equal(aria.sides[1].team[0].status, "");
  assert.ok(
    aria.events.some(
      (event) =>
        event.type === "status_cured" &&
        event.source === "Sparkling Aria" &&
        event.status === "brn",
    ),
  );

  const hazardState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "HazardCutter",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "ceaselessedge",
                    name: "Ceaseless Edge",
                    type: "Dark",
                    category: "Physical",
                    power: 65,
                    accuracy: true,
                    pp: 15,
                  },
                  {
                    id: "stoneaxe",
                    name: "Stone Axe",
                    type: "Rock",
                    category: "Physical",
                    power: 65,
                    accuracy: true,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "HazardTarget", stats: { ...pokemon().stats, hp: 260 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(hazardState.sides[1].conditions.spikes.layers, 1);
  const rocked = resolveSimpleTurn(hazardState, [{ move: 2 }, { move: 1 }]);
  assert.equal(rocked.sides[1].conditions.stealthrock.layers, 1);

  const photonState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "PhotonUser",
                stats: {
                  ...pokemon().stats,
                  attack: 180,
                  specialAttack: 80,
                  speed: 160,
                },
                moves: [
                  {
                    id: "photongeyser",
                    name: "Photon Geyser",
                    type: "Psychic",
                    category: "Special",
                    power: 100,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "ReflectTarget",
                stats: { ...pokemon().stats, hp: 360, defence: 80, specialDefence: 220 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const photonDamage = photonState.events.find(
    (event) => event.type === "damage" && event.move === "Photon Geyser",
  )?.damage;
  assert.ok(photonDamage > 50);

  const furyState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "FuryUser",
                stats: { ...pokemon().stats, attack: 160, speed: 40 },
                moves: [
                  {
                    id: "hyperspacefury",
                    name: "Hyperspace Fury",
                    type: "Dark",
                    category: "Physical",
                    power: 100,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Protector",
                stats: { ...pokemon().stats, hp: 300, speed: 160 },
                moves: [
                  {
                    id: "protect",
                    name: "Protect",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    priority: 4,
                    pp: 10,
                    volatileStatus: "protect",
                    target: "self",
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(furyState.sides[1].team[0].hp < 300);
  assert.equal(furyState.sides[0].team[0].boosts.defence, -1);
  assert.ok(!furyState.events.some((event) => event.type === "move_blocked"));

  const firstTurn = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Fodder",
                stats: { ...pokemon().stats, hp: 80, speed: 40 },
              }),
              pokemon({
                name: "Avenger",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "retaliate",
                    name: "Retaliate",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 5,
                    dynamicPower: true,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Knocker",
                stats: { ...pokemon().stats, attack: 220, speed: 160 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 140,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(firstTurn.sides[0].active, 1);
  const retaliated = resolveSimpleTurn(firstTurn, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    retaliated.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Retaliate" &&
        event.reason === "ally_fainted_previous_turn",
    ),
  );
});

test("supports move copying and target type/status utility callbacks", () => {
  const mimicBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "MimicUser",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "mimic",
                  name: "Mimic",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "MoveTeacher",
              moves: [
                {
                  id: "flamethrower",
                  name: "Flamethrower",
                  type: "Fire",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const copied = resolveSimpleTurn(mimicBattle, [{ move: 1 }, { move: 1 }]);
  assert.equal(copied.sides[0].team[0].moves[0].id, "flamethrower");
  assert.equal(copied.sides[0].team[0].moves[0].pp, 5);
  assert.ok(
    copied.events.some(
      (event) =>
        event.type === "move_copied" &&
        event.source === "Mimic" &&
        event.move === "Flamethrower",
    ),
  );

  const typeBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "TypeMage",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "soak",
                  name: "Soak",
                  type: "Water",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "forestscurse",
                  name: "Forest's Curse",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "trickortreat",
                  name: "Trick-or-Treat",
                  type: "Ghost",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "TypeTarget", types: ["Fire"] })] },
      ],
    }),
  );
  const soaked = resolveSimpleTurn(typeBattle, [{ move: 1 }, { move: 1 }]);
  assert.deepEqual(soaked.sides[1].team[0].types, ["Water"]);
  const cursed = resolveSimpleTurn(soaked, [{ move: 2 }, { move: 1 }]);
  assert.deepEqual(cursed.sides[1].team[0].types, ["Water", "Grass"]);
  const haunted = resolveSimpleTurn(cursed, [{ move: 3 }, { move: 1 }]);
  assert.deepEqual(haunted.sides[1].team[0].types, ["Water", "Grass", "Ghost"]);

  const confuseBattle = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "ConfuseMage",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "flatter",
                    name: "Flatter",
                    type: "Dark",
                    category: "Status",
                    accuracy: true,
                    pp: 15,
                    boosts: { specialAttack: 1 },
                    volatileStatus: "confusion",
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "FlatteredTarget" })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(confuseBattle.sides[1].team[0].boosts.specialAttack, 1);
  assert.equal(confuseBattle.sides[1].team[0].volatiles.confusion.id, "confusion");
}
);

test("supports additional native dynamic power callbacks", () => {
  const brineState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Briner",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "brine",
                  name: "Brine",
                  type: "Water",
                  category: "Special",
                  power: 65,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "HalfHpTarget", stats: { ...pokemon().stats, hp: 260 } })] },
      ],
    }),
  );
  brineState.sides[1].team[0].hp = 120;
  const brined = resolveSimpleTurn(brineState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    brined.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Brine" &&
        event.reason === "target_half_hp_or_less",
    ),
  );

  const drifted = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Drifter",
                types: ["Electric"],
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "electrodrift",
                    name: "Electro Drift",
                    type: "Electric",
                    category: "Special",
                    power: 100,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "WaterTarget", types: ["Water"], stats: { ...pokemon().stats, hp: 320 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    drifted.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Electro Drift" &&
        event.reason === "super_effective",
    ),
  );

  const fusioned = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "BoltUser",
                stats: { ...pokemon().stats, attack: 160, speed: 40 },
                moves: [
                  {
                    id: "fusionbolt",
                    name: "Fusion Bolt",
                    type: "Electric",
                    category: "Physical",
                    power: 100,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "FlareUser",
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "fusionflare",
                    name: "Fusion Flare",
                    type: "Fire",
                    category: "Special",
                    power: 100,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    fusioned.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Fusion Bolt" &&
        event.reason === "fusion_flare_used_this_turn",
    ),
  );

  const echoedFirst = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "EchoUser",
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "echoedvoice",
                    name: "Echoed Voice",
                    type: "Normal",
                    category: "Special",
                    power: 40,
                    accuracy: true,
                    pp: 15,
                    dynamicPower: true,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "EchoTarget", stats: { ...pokemon().stats, hp: 400 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const echoedSecond = resolveSimpleTurn(echoedFirst, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    echoedSecond.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Echoed Voice" &&
        event.power === 80,
    ),
  );
});

test("supports disabling, locking, clearing, swapping, and post-KO utility callbacks", () => {
  const disableBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Disabler",
              stats: { ...pokemon().stats, speed: 40 },
              moves: [
                {
                  id: "disable",
                  name: "Disable",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Target",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "ember",
                  name: "Ember",
                  type: "Fire",
                  category: "Special",
                  power: 40,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const disabled = resolveSimpleTurn(disableBase, [{ move: 1 }, { move: 1 }]);
  assert.equal(disabled.sides[1].team[0].volatiles.disable.moveId, "slash");
  const afterDisable = resolveSimpleTurn(disabled, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    afterDisable.events.some(
      (event) => event.turn === 2 && event.type === "move" && event.move === "Ember",
    ),
  );

  const encoreBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "EncoreUser",
              stats: { ...pokemon().stats, speed: 40 },
              moves: [
                {
                  id: "encore",
                  name: "Encore",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "LockedTarget",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "ember",
                  name: "Ember",
                  type: "Fire",
                  category: "Special",
                  power: 40,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const encored = resolveSimpleTurn(encoreBase, [{ move: 1 }, { move: 1 }]);
  assert.equal(encored.sides[1].team[0].volatiles.encore.moveId, "slash");
  const afterEncore = resolveSimpleTurn(encored, [{ move: 1 }, { move: 2 }]);
  assert.ok(
    afterEncore.events.some(
      (event) => event.turn === 2 && event.type === "move" && event.move === "Slash",
    ),
  );

  const utilityState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "UtilityUser",
              stats: { ...pokemon().stats, hp: 200, speed: 160 },
              moves: [
                {
                  id: "defog",
                  name: "Defog",
                  type: "Flying",
                  category: "Status",
                  accuracy: true,
                  pp: 15,
                },
                {
                  id: "powerswap",
                  name: "Power Swap",
                  type: "Psychic",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
                {
                  id: "strengthsap",
                  name: "Strength Sap",
                  type: "Grass",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "UtilityTarget",
              stats: { ...pokemon().stats, attack: 150, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  utilityState.sides[0].team[0].hp = 70;
  utilityState.sides[0].team[0].boosts.attack = -2;
  utilityState.sides[1].team[0].boosts.attack = 3;
  utilityState.sides[0].conditions.stealthrock = { id: "stealthrock", layers: 1 };
  utilityState.sides[1].conditions.spikes = { id: "spikes", layers: 2 };
  const defogged = resolveSimpleTurn(utilityState, [{ move: 1 }, { move: 1 }]);
  assert.equal(defogged.sides[0].conditions.stealthrock, undefined);
  assert.equal(defogged.sides[1].conditions.spikes, undefined);

  const swapped = resolveSimpleTurn(utilityState, [{ move: 2 }, { move: 1 }]);
  assert.equal(swapped.sides[0].team[0].boosts.attack, 3);
  assert.equal(swapped.sides[1].team[0].boosts.attack, -2);

  const sapped = resolveSimpleTurn(utilityState, [{ move: 3 }, { move: 1 }]);
  assert.ok(sapped.sides[0].team[0].hp > 70);
  assert.equal(sapped.sides[1].team[0].boosts.attack, 2);

  const postHitState = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "PostHitUser",
                types: ["Electric"],
                stats: { ...pokemon().stats, attack: 240, speed: 160 },
                moves: [
                  {
                    id: "doubleshock",
                    name: "Double Shock",
                    type: "Electric",
                    category: "Physical",
                    power: 120,
                    accuracy: true,
                    pp: 5,
                  },
                  {
                    id: "fellstinger",
                    name: "Fell Stinger",
                    type: "Bug",
                    category: "Physical",
                    power: 50,
                    accuracy: true,
                    pp: 25,
                  },
                  {
                    id: "smackdown",
                    name: "Smack Down",
                    type: "Rock",
                    category: "Physical",
                    power: 50,
                    accuracy: true,
                    pp: 15,
                    volatileStatus: "smackdown",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "WeakTarget",
                types: ["Water"],
                stats: { ...pokemon().stats, hp: 500, defence: 60, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.deepEqual(postHitState.sides[0].team[0].types, ["Normal"]);

  const fellState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Stinger",
              stats: { ...pokemon().stats, attack: 240, speed: 160 },
              moves: [
                {
                  id: "fellstinger",
                  name: "Fell Stinger",
                  type: "Bug",
                  category: "Physical",
                  power: 50,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [pokemon({ name: "LowHpTarget", stats: { ...pokemon().stats, hp: 40, defence: 40, speed: 40 } })],
        },
      ],
    }),
  );
  const stung = resolveSimpleTurn(fellState, [{ move: 1 }, { move: 1 }]);
  assert.equal(stung.sides[0].team[0].boosts.attack, 3);
});

test("supports item exchange, heal blocking, imprison, no-retreat, and special utility conditions", () => {
  const tricked = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Tricker",
                item: "choicescarf",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "trick",
                    name: "Trick",
                    type: "Psychic",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [pokemon({ name: "ItemTarget", item: "leftovers" })],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(tricked.sides[0].team[0].item, "leftovers");
  assert.equal(tricked.sides[1].team[0].item, "choicescarf");
  assert.ok(tricked.events.some((event) => event.type === "items_swapped"));

  const recycledBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Recycler",
              item: "",
              usedItem: "sitrusberry",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "recycle",
                  name: "Recycle",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon()] },
      ],
    }),
  );
  const recycled = resolveSimpleTurn(recycledBase, [{ move: 1 }, { move: 1 }]);
  assert.equal(recycled.sides[0].team[0].item, "sitrusberry");
  assert.ok(recycled.events.some((event) => event.type === "item_restored"));

  const healBlocked = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Blocker",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "healblock",
                    name: "Heal Block",
                    type: "Psychic",
                    category: "Status",
                    accuracy: true,
                    pp: 15,
                    volatileStatus: "healblock",
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "HealerTarget",
                stats: { ...pokemon().stats, hp: 200, speed: 40 },
                moves: [
                  {
                    id: "recover",
                    name: "Recover",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                    heal: [1, 2],
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  healBlocked.sides[1].team[0].hp = 40;
  const recoverBlocked = resolveSimpleTurn(healBlocked, [{ move: 1 }, { move: 1 }]);
  assert.equal(recoverBlocked.sides[1].team[0].hp, 40);
  assert.ok(recoverBlocked.events.some((event) => event.type === "heal_blocked"));

  const imprisoned = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Jailer",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "imprison",
                    name: "Imprison",
                    type: "Psychic",
                    category: "Status",
                    accuracy: true,
                    pp: 10,
                  },
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SharedMoveTarget",
                stats: { ...pokemon().stats, speed: 40 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const imprisonedNext = resolveSimpleTurn(imprisoned, [{ move: 2 }, { move: 1 }]);
  assert.ok(
    imprisonedNext.events.some(
      (event) => event.type === "move_failed" && event.source === "imprison",
    ),
  );

  const noRetreat = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Retreater",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "noretreat",
                    name: "No Retreat",
                    type: "Fighting",
                    category: "Status",
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon()] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(noRetreat.sides[0].team[0].boosts.attack, 1);
  assert.equal(noRetreat.sides[0].team[0].boosts.speed, 1);
  assert.equal(noRetreat.sides[0].team[0].volatiles.noretreat.id, "noretreat");

  const utility = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "EyeUser",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "miracleeye",
                    name: "Miracle Eye",
                    type: "Psychic",
                    category: "Status",
                    accuracy: true,
                    pp: 40,
                    volatileStatus: "miracleeye",
                  },
                  {
                    id: "telekinesis",
                    name: "Telekinesis",
                    type: "Psychic",
                    category: "Status",
                    accuracy: true,
                    pp: 15,
                    volatileStatus: "telekinesis",
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "FloatTarget" })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(utility.sides[1].team[0].volatiles.miracleeye.id, "miracleeye");
  const lifted = resolveSimpleTurn(utility, [{ move: 2 }, { move: 1 }]);
  assert.equal(lifted.sides[1].team[0].volatiles.telekinesis.id, "telekinesis");

  const lastResortBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Closer",
              stats: { ...pokemon().stats, attack: 220, speed: 160 },
              moves: [
                {
                  id: "quickattack",
                  name: "Quick Attack",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: true,
                  pp: 30,
                },
                {
                  id: "lastresort",
                  name: "Last Resort",
                  type: "Normal",
                  category: "Physical",
                  power: 140,
                  accuracy: true,
                  pp: 5,
                },
              ],
            }),
          ],
        },
        { name: "AI", team: [pokemon({ name: "Wall", stats: { ...pokemon().stats, hp: 500 } })] },
      ],
    }),
  );
  const failedLastResort = resolveSimpleTurn(lastResortBase, [{ move: 2 }, { move: 1 }]);
  assert.ok(
    failedLastResort.events.some(
      (event) => event.type === "move_failed" && event.move === "Last Resort",
    ),
  );
  const usedOther = resolveSimpleTurn(lastResortBase, [{ move: 1 }, { move: 1 }]);
  const succeededLastResort = resolveSimpleTurn(usedOther, [{ move: 2 }, { move: 1 }]);
  assert.ok(
    succeededLastResort.events.some(
      (event) => event.type === "damage" && event.move === "Last Resort",
    ),
  );

  const psywaved = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "PsywaveUser",
                level: 77,
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "psywave",
                    name: "Psywave",
                    type: "Psychic",
                    category: "Special",
                    power: 0,
                    accuracy: true,
                    pp: 15,
                    dynamicDamage: true,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "PsywaveTarget", stats: { ...pokemon().stats, hp: 200 } })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const psywaveDamage = psywaved.events.find(
    (event) => event.type === "damage" && event.move === "Psywave",
  )?.damage;
  assert.ok(psywaveDamage >= 38);
  assert.ok(psywaveDamage <= 115);
});

test("supports called moves, redirection markers, perish song, beak blast, and pledge power", () => {
  const copyBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Copier",
              stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
              moves: [
                {
                  id: "copycat",
                  name: "Copycat",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "naturepower",
                  name: "Nature Power",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "metronome",
                  name: "Metronome",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Teacher",
              stats: { ...pokemon().stats, speed: 40 },
              moves: [
                {
                  id: "ember",
                  name: "Ember",
                  type: "Fire",
                  category: "Special",
                  power: 40,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const learned = resolveSimpleTurn(copyBase, [{ move: 3 }, { move: 1 }]);
  assert.ok(
    learned.events.some(
      (event) => event.type === "called_move" && event.source === "Metronome",
    ),
  );
  const copied = resolveSimpleTurn(learned, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    copied.events.some(
      (event) => event.type === "called_move" && event.source === "Copycat",
    ),
  );
  const natured = resolveSimpleTurn(copyBase, [{ move: 2 }, { move: 1 }]);
  assert.ok(
    natured.events.some(
      (event) =>
        event.type === "called_move" &&
        event.source === "Nature Power" &&
        event.move === "Tri Attack",
    ),
  );

  const mirrorBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "MirrorUser",
              stats: { ...pokemon().stats, attack: 160, speed: 40 },
              moves: [
                {
                  id: "mirrormove",
                  name: "Mirror Move",
                  type: "Flying",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Target",
              stats: { ...pokemon().stats, speed: 160 },
              moves: [
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const mirrored = resolveSimpleTurn(mirrorBase, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    mirrored.events.some(
      (event) => event.type === "called_move" && event.source === "Mirror Move",
    ),
  );

  const meFirst = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "FastCaller",
                stats: { ...pokemon().stats, attack: 160, speed: 200 },
                moves: [
                  {
                    id: "mefirst",
                    name: "Me First",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "SlowAttacker",
                stats: { ...pokemon().stats, attack: 160, speed: 40 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    meFirst.events.some(
      (event) => event.type === "called_move" && event.source === "Me First",
    ),
  );

  const redirected = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Redirector",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "followme",
                    name: "Follow Me",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 20,
                    volatileStatus: "followme",
                  },
                  {
                    id: "ragepowder",
                    name: "Rage Powder",
                    type: "Bug",
                    category: "Status",
                    accuracy: true,
                    pp: 20,
                    volatileStatus: "ragepowder",
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon()] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    redirected.events.some(
      (event) =>
        event.type === "volatile_start" &&
        event.pokemon === "Redirector" &&
        event.effect === "followme",
    ),
  );

  const perishStarted = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Singer",
                stats: { ...pokemon().stats, speed: 160 },
                moves: [
                  {
                    id: "perishsong",
                    name: "Perish Song",
                    type: "Normal",
                    category: "Status",
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "Listener" })] },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(perishStarted.sides[0].team[0].volatiles.perishsong.count, 2);

  const beak = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Puncher",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "slash",
                    name: "Slash",
                    type: "Normal",
                    category: "Physical",
                    power: 70,
                    accuracy: true,
                    pp: 20,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "Blaster",
                stats: { ...pokemon().stats, attack: 160, speed: 40 },
                moves: [
                  {
                    id: "beakblast",
                    name: "Beak Blast",
                    type: "Flying",
                    category: "Physical",
                    power: 100,
                    accuracy: true,
                    priority: -3,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(beak.sides[0].team[0].status, "brn");

  const pledge = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Pledger",
                stats: { ...pokemon().stats, specialAttack: 160, speed: 160 },
                moves: [
                  {
                    id: "grasspledge",
                    name: "Grass Pledge",
                    type: "Grass",
                    category: "Special",
                    power: 80,
                    accuracy: true,
                    pp: 10,
                    dynamicPower: true,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "PartnerPledger",
                stats: { ...pokemon().stats, specialAttack: 160, speed: 40 },
                moves: [
                  {
                    id: "waterpledge",
                    name: "Water Pledge",
                    type: "Water",
                    category: "Special",
                    power: 80,
                    accuracy: true,
                    pp: 10,
                    dynamicPower: true,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    pledge.events.some(
      (event) =>
        event.type === "dynamic_power" &&
        event.move === "Water Pledge" &&
        event.reason === "pledge_combo",
    ),
  );
});

test("enforces forced move locks from rampage moves, rolling moves, and choice items", () => {
  const outrageBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Rampager",
              stats: { ...pokemon().stats, attack: 120, speed: 160 },
              moves: [
                {
                  id: "outrage",
                  name: "Outrage",
                  type: "Dragon",
                  category: "Physical",
                  power: 120,
                  accuracy: true,
                  pp: 10,
                },
                {
                  id: "thunderbolt",
                  name: "Thunderbolt",
                  type: "Electric",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "Target",
              stats: { ...pokemon().stats, hp: 600, defence: 160, speed: 40 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const afterOutrage = resolveSimpleTurn(outrageBase, [{ move: 1 }, { move: 1 }]);
  assert.equal(afterOutrage.sides[0].team[0].lockedMove.id, "outrage");
  assert.ok(
    [2, 3].includes(afterOutrage.sides[0].team[0].lockedMove.maximum),
    `Outrage lock should last 2-3 turns, got ${afterOutrage.sides[0].team[0].lockedMove.maximum}`,
  );
  assert.equal(chooseSimpleAiCommand(afterOutrage, 0, "expert", "balanced").move, 1);
  const forcedOutrage = resolveSimpleTurn(afterOutrage, [{ move: 2 }, { move: 1 }]);
  assert.ok(
    forcedOutrage.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move" &&
        event.pokemon === "Rampager" &&
        event.move === "Outrage",
    ),
  );
  assert.ok(
    !forcedOutrage.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move" &&
        event.pokemon === "Rampager" &&
        event.move === "Thunderbolt",
    ),
  );

  const rolloutBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Roller",
              stats: { ...pokemon().stats, attack: 120, speed: 160 },
              moves: [
                {
                  id: "rollout",
                  name: "Rollout",
                  type: "Rock",
                  category: "Physical",
                  power: 30,
                  accuracy: true,
                  pp: 20,
                },
              ],
            }),
            pokemon({ name: "Reserve" }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "TrainingDummy",
              stats: { ...pokemon().stats, hp: 600, defence: 160, speed: 40 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const afterRollout = resolveSimpleTurn(rolloutBase, [{ move: 1 }, { move: 1 }]);
  assert.throws(
    () => resolveSimpleTurn(afterRollout, [{ switch: 2 }, { move: 1 }]),
    /cannot switch while locked into Rollout/,
  );

  const choiceBase = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "ChoiceUser",
              item: "choiceband",
              stats: { ...pokemon().stats, attack: 120, speed: 160 },
              moves: [
                {
                  id: "slash",
                  name: "Slash",
                  type: "Normal",
                  category: "Physical",
                  power: 70,
                  accuracy: true,
                  pp: 20,
                },
                {
                  id: "ember",
                  name: "Ember",
                  type: "Fire",
                  category: "Special",
                  power: 40,
                  accuracy: true,
                  pp: 25,
                },
              ],
            }),
            pokemon({ name: "ChoiceReserve" }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "ChoiceTarget",
              stats: { ...pokemon().stats, hp: 600, defence: 160, speed: 40 },
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const afterChoice = resolveSimpleTurn(choiceBase, [{ move: 1 }, { move: 1 }]);
  assert.equal(afterChoice.sides[0].team[0].choiceLock.id, "slash");
  assert.equal(chooseSimpleAiCommand(afterChoice, 0, "expert", "balanced").move, 1);
  const forcedChoice = resolveSimpleTurn(afterChoice, [{ move: 2 }, { move: 1 }]);
  assert.ok(
    forcedChoice.events.some(
      (event) =>
        event.turn === 2 &&
        event.type === "move" &&
        event.pokemon === "ChoiceUser" &&
        event.move === "Slash",
    ),
  );
  const switchedChoice = resolveSimpleTurn(forcedChoice, [{ switch: 2 }, { move: 1 }]);
  assert.equal(switchedChoice.sides[0].active, 1);
  assert.equal(switchedChoice.sides[0].team[0].choiceLock, null);
});

test("rejects offensive setup when the boosted user cannot survive the turn", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              id: "scizormega",
              name: "Mega Scizor",
              types: ["Bug", "Steel"],
              weightKg: 125,
              ability: "technician",
              stats: {
                hp: 344,
                attack: 170,
                defence: 140,
                specialAttack: 65,
                specialDefence: 110,
                speed: 95,
              },
              moves: [
                {
                  id: "swordsdance",
                  name: "Swords Dance",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 20,
                  target: "self",
                  boosts: { atk: 2 },
                },
                {
                  id: "bugbite",
                  name: "Bug Bite",
                  type: "Bug",
                  category: "Physical",
                  power: 60,
                  accuracy: 100,
                  pp: 20,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              id: "snorlax",
              name: "Snorlax",
              types: ["Normal"],
              weightKg: 460,
              stats: {
                hp: 524,
                attack: 130,
                defence: 95,
                specialAttack: 65,
                specialDefence: 130,
                speed: 50,
              },
              moves: [
                {
                  id: "heatcrash",
                  name: "Heat Crash",
                  type: "Fire",
                  category: "Physical",
                  power: 0,
                  accuracy: 100,
                  pp: 10,
                  dynamicPower: true,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[0].team[0].hp = 130;
  state.sides[1].team[0].hp = 269;
  state.sides[0].gimmickResources.dynamax = "consumed";

  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "aggressive").move,
    2,
  );
});

test("uses low-HP opponents as setup opportunities only when the enemy backline benefits", () => {
  const setupUser = () =>
    pokemon({
      name: "Setup Ace",
      types: ["Normal"],
      boosts: { attack: 2 },
      stats: {
        ...pokemon().stats,
        hp: 320,
        attack: 145,
        defence: 130,
        speed: 130,
      },
      moves: [
        {
          id: "slash",
          name: "Slash",
          type: "Normal",
          category: "Physical",
          power: 70,
          accuracy: true,
          pp: 20,
        },
        {
          id: "swordsdance",
          name: "Swords Dance",
          type: "Normal",
          category: "Status",
          accuracy: true,
          pp: 20,
          selfBoosts: { attack: 2 },
        },
      ],
    });
  const harmlessLead = () =>
    pokemon({
      name: "Harmless Lead",
      stats: { ...pokemon().stats, hp: 1, defence: 80, speed: 30 },
      moves: [
        {
          id: "splash",
          name: "Splash",
          type: "Normal",
          category: "Status",
          accuracy: true,
          pp: 40,
        },
      ],
    });

  const lastOpponentScenario = setup({
    sides: [
      { name: "Player", team: [setupUser()] },
      { name: "Opponent", team: [harmlessLead()] },
    ],
  });
  const lastOpponentState = createSimpleBattle(lastOpponentScenario);
  lastOpponentState.sides[0].gimmickResources.dynamax = "consumed";
  assert.equal(
    chooseSimpleAiCommand(lastOpponentState, 0, "expert", "balanced").move,
    1,
  );
  const lastOpponentBattle = runSimpleBattle(lastOpponentScenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const unnecessarySwordsDance = lastOpponentBattle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "swordsdance");
  assert.ok(
    unnecessarySwordsDance.reasons.some(
      (reason) => reason.code === "rule.setup.no_matchup_gain",
    ),
  );

  const backlineScenario = setup({
    sides: [
      { name: "Player", team: [setupUser()] },
      {
        name: "Opponent",
        team: [
          harmlessLead(),
          pokemon({
            name: "Backline Ace",
            stats: {
              ...pokemon().stats,
              hp: 620,
              attack: 180,
              defence: 230,
              specialDefence: 180,
              speed: 115,
            },
          }),
        ],
      },
    ],
  });
  const backlineState = createSimpleBattle(backlineScenario);
  backlineState.sides[0].gimmickResources.dynamax = "consumed";
  assert.equal(
    chooseSimpleAiCommand(backlineState, 0, "expert", "balanced").move,
    2,
  );

  const battle = runSimpleBattle(backlineScenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const swordsDance = battle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "swordsdance");
  assert.equal(swordsDance.setupFutureTargetCount, 1);
  assert.ok(swordsDance.setupFuturePressureGain > 0);
  assert.ok(
    swordsDance.reasons.some(
      (reason) => reason.code === "rule.setup.team_sweep_plan",
    ),
  );

  const safeLowHpState = createSimpleBattle(backlineScenario);
  safeLowHpState.sides[0].team[0].hp = 1;
  safeLowHpState.sides[0].gimmickResources.dynamax = "consumed";
  assert.equal(
    chooseSimpleAiCommand(safeLowHpState, 0, "expert", "balanced").move,
    2,
  );

  const dangerousLowHpScenario = setup({
    sides: [
      { name: "Player", team: [setupUser()] },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Dangerous Lead",
            stats: { ...pokemon().stats, hp: 1, attack: 120, speed: 30 },
            moves: [
              {
                id: "quickattack",
                name: "Quick Attack",
                type: "Normal",
                category: "Physical",
                power: 40,
                accuracy: 100,
                priority: 1,
                pp: 30,
              },
            ],
          }),
          backlineScenario.sides[1].team[1],
        ],
      },
    ],
  });
  const dangerousLowHpState = createSimpleBattle(dangerousLowHpScenario);
  dangerousLowHpState.sides[0].team[0].hp = 1;
  dangerousLowHpState.sides[0].gimmickResources.dynamax = "consumed";
  assert.equal(
    chooseSimpleAiCommand(
      dangerousLowHpState,
      0,
      "expert",
      "balanced",
    ).move,
    1,
  );

  const dangerousTraceScenario = {
    ...dangerousLowHpScenario,
    sides: dangerousLowHpScenario.sides.map((side, sideIndex) => ({
      ...side,
      team: side.team.map((member, memberIndex) =>
        sideIndex === 0 && memberIndex === 0
          ? { ...member, stats: { ...member.stats, hp: 1 } }
          : member,
      ),
    })),
  };
  const dangerousBattle = runSimpleBattle(dangerousTraceScenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const rejectedSetup = dangerousBattle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "swordsdance");
  assert.equal(rejectedSetup.setupFollowupSurvivalProbability, 0);
  assert.ok(
    rejectedSetup.reasons.some(
      (reason) => reason.code === "rule.setup.cannot_reach_followup",
    ),
  );

  const sashSetupScenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "Focus Sash Sweeper",
            item: "focussash",
            ability: "speedboost",
            stats: {
              ...pokemon().stats,
              hp: 300,
              attack: 145,
              defence: 90,
              specialDefence: 90,
              speed: 100,
            },
            moves: [
              {
                id: "slash",
                name: "Slash",
                type: "Normal",
                category: "Physical",
                power: 70,
                accuracy: 100,
                pp: 20,
              },
              {
                id: "swordsdance",
                name: "Swords Dance",
                type: "Normal",
                category: "Status",
                power: 0,
                accuracy: true,
                pp: 20,
                selfBoosts: { attack: 2 },
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Faster One HP Attacker",
            stats: {
              ...pokemon().stats,
              hp: 1,
              specialAttack: 800,
              speed: 200,
            },
            moves: [
              {
                id: "psychic",
                name: "Psychic",
                type: "Psychic",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          backlineScenario.sides[1].team[1],
        ],
      },
    ],
  });
  const sashSetupState = createSimpleBattle(sashSetupScenario);
  sashSetupState.sides[0].gimmickResources.dynamax = "consumed";
  const sashBattle = runSimpleBattle(sashSetupScenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const rejectedSashSetup = sashBattle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "swordsdance");
  assert.equal(
    chooseSimpleAiCommand(sashSetupState, 0, "expert", "balanced").move,
    1,
    JSON.stringify(rejectedSashSetup),
  );
  assert.equal(rejectedSashSetup.setupGuardConsumptionProbability, 1);
  assert.equal(rejectedSashSetup.setupFollowupActsBeforeThreat, false);
  assert.equal(rejectedSashSetup.setupFollowupSurvivalProbability, 0);
});

test("uses priority instead of choosing an attack that cannot act before a KO", () => {
  const threatenedAttacker = pokemon({
    name: "Threatened Attacker",
    stats: {
      ...pokemon().stats,
      hp: 80,
      attack: 180,
      speed: 40,
    },
    moves: [
      {
        id: "megapunch",
        name: "Mega Punch",
        type: "Normal",
        category: "Physical",
        power: 120,
        accuracy: 100,
        priority: 0,
        pp: 10,
      },
      {
        id: "quickattack",
        name: "Quick Attack",
        type: "Normal",
        category: "Physical",
        power: 40,
        accuracy: 100,
        priority: 1,
        pp: 30,
      },
    ],
  });
  const fasterThreat = pokemon({
    name: "Faster Threat",
    stats: {
      ...pokemon().stats,
      hp: 500,
      attack: 500,
      speed: 200,
    },
    moves: [
      {
        id: "bodyslam",
        name: "Body Slam",
        type: "Normal",
        category: "Physical",
        power: 120,
        accuracy: 100,
        priority: 0,
        pp: 10,
      },
    ],
  });
  const scenario = setup({
    sides: [
      { name: "Player", team: [threatenedAttacker] },
      { name: "Opponent", team: [fasterThreat] },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.dynamax = "consumed";

  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "balanced").move,
    2,
  );

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
  });
  const trace = battle.aiTrace.find((entry) => entry.side === 0);
  const delayedAttack = trace?.candidates.find(
    (candidate) => candidate.id === "megapunch",
  );
  const priorityAttack = trace?.candidates.find(
    (candidate) => candidate.id === "quickattack",
  );
  assert.equal(delayedAttack.opponentKnockoutBeforeActionProbability, 1);
  assert.equal(priorityAttack.opponentKnockoutBeforeActionProbability, 0);
  assert.ok(
    delayedAttack.reasons.some(
      (reason) => reason.code === "rule.action.ko_before_acting",
    ),
  );
});

test("does not suppress a slower attack when Focus Sash guarantees an action", () => {
  const scenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "Sashed Attacker",
            item: "focussash",
            stats: {
              ...pokemon().stats,
              hp: 80,
              attack: 180,
              speed: 40,
            },
            moves: [
              {
                id: "megapunch",
                name: "Mega Punch",
                type: "Normal",
                category: "Physical",
                power: 120,
                accuracy: 100,
                priority: 0,
                pp: 10,
              },
              {
                id: "quickattack",
                name: "Quick Attack",
                type: "Normal",
                category: "Physical",
                power: 40,
                accuracy: 100,
                priority: 1,
                pp: 30,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            name: "Faster Threat",
            stats: {
              ...pokemon().stats,
              hp: 500,
              attack: 500,
              speed: 200,
            },
            moves: [
              {
                id: "bodyslam",
                name: "Body Slam",
                type: "Normal",
                category: "Physical",
                power: 120,
                accuracy: 100,
                priority: 0,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.dynamax = "consumed";

  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "balanced").move,
    1,
  );
});

test("takes a guaranteed KO boost instead of spending Focus Sash on setup", () => {
  const scenario = setup({
    sides: [
      {
        name: "Calyrex",
        team: [
          pokemon({
            id: "calyrexshadow",
            name: "Calyrex-Shadow",
            types: ["Psychic", "Ghost"],
            item: "focussash",
            ability: "asonespectrier",
            stats: {
              ...pokemon().stats,
              hp: 342,
              specialAttack: 400,
              speed: 200,
            },
            moves: [
              {
                id: "astralbarrage",
                name: "Astral Barrage",
                type: "Ghost",
                category: "Special",
                power: 120,
                accuracy: 100,
                pp: 5,
              },
              {
                id: "nastyplot",
                name: "Nasty Plot",
                type: "Dark",
                category: "Status",
                power: 0,
                accuracy: true,
                pp: 20,
                selfBoosts: { specialAttack: 2 },
              },
            ],
          }),
        ],
      },
      {
        name: "Urshifu",
        team: [
          pokemon({
            id: "urshifurapidstrike",
            name: "Urshifu-Rapid-Strike",
            types: ["Fighting", "Water"],
            stats: {
              ...pokemon().stats,
              hp: 103,
              attack: 800,
              speed: 120,
            },
            moves: [
              {
                id: "liquidation",
                name: "G-Max Rapid Flow",
                type: "Water",
                category: "Physical",
                power: 180,
                accuracy: 100,
                pp: 5,
              },
            ],
          }),
          pokemon({
            name: "Backline Wall",
            stats: {
              ...pokemon().stats,
              hp: 600,
              specialDefence: 220,
            },
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.dynamax = "consumed";
  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "tempo").move,
    1,
  );

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "tempo" }],
  });
  const nastyPlot = battle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find((candidate) => candidate.id === "nastyplot");
  assert.equal(nastyPlot.setupGuardConsumptionProbability, 1);
  assert.equal(nastyPlot.reliableKoAlternative, true);
  assert.deepEqual(nastyPlot.knockoutBoostAlternative, { specialAttack: 1 });
  assert.ok(
    nastyPlot.reasons.some(
      (reason) => reason.code === "rule.setup.foregoes_ko_boost",
    ),
  );
});

test("prefers a reliable no-recoil finisher without overvaluing excess damage", () => {
  const finisherSetup = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            name: "Finisher",
            types: ["Fire"],
            stats: { ...pokemon().stats, attack: 160, speed: 160 },
            moves: [
              {
                id: "flareblitz",
                name: "Flare Blitz",
                type: "Fire",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 15,
                recoil: [1, 3],
              },
              {
                id: "flamecharge",
                name: "Flame Charge",
                type: "Fire",
                category: "Physical",
                power: 50,
                accuracy: 100,
                pp: 20,
              },
            ],
          }),
        ],
      },
      {
        name: "AI",
        team: [
          pokemon({
            name: "OneHpTarget",
            stats: { ...pokemon().stats, hp: 400, speed: 40 },
          }),
        ],
      },
    ],
  });
  const reliableState = createSimpleBattle(finisherSetup);
  reliableState.sides[0].team[0].hp = 1;
  reliableState.sides[1].team[0].hp = 1;
  reliableState.sides[0].gimmickResources.dynamax = "consumed";

  assert.equal(
    chooseSimpleAiCommand(reliableState, 0, "expert", "balanced").move,
    2,
  );

  const inaccurateState = createSimpleBattle(finisherSetup);
  inaccurateState.sides[0].team[0].hp = 1;
  inaccurateState.sides[1].team[0].hp = 1;
  inaccurateState.sides[0].gimmickResources.dynamax = "consumed";
  inaccurateState.sides[0].team[0].moves[1] = {
    ...inaccurateState.sides[0].team[0].moves[1],
    id: "inferno",
    name: "Inferno",
    category: "Special",
    power: 100,
    accuracy: 50,
  };

  assert.equal(
    chooseSimpleAiCommand(inaccurateState, 0, "expert", "balanced").move,
    1,
  );
});

test("allows a player Dynamax command independently of the entry AI flag", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                gimmicks: { canDynamax: false, gigantamax: false },
              }),
            ],
          },
          { name: "AI", team: [pokemon({ name: "AiMon" })] },
        ],
      }),
    ),
    [{ move: 1, gimmick: "dynamax" }, { move: 1 }],
  );
  assert.equal(state.sides[0].team[0].dynamaxMode, "dynamax");
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "gimmick_activated" &&
        event.dynamaxMode === "dynamax",
    ),
  );
});

test("records Max Move candidates while a Pokémon remains Dynamaxed", () => {
  const state = runSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "PlayerMon",
              stats: { ...pokemon().stats, hp: 400, specialAttack: 100, speed: 80 },
              gimmicks: { canDynamax: true, forceDynamax: true },
              moves: [
                {
                  id: "thunderbolt",
                  name: "Thunderbolt",
                  type: "Electric",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "AiMon",
              stats: { ...pokemon().stats, hp: 400, specialAttack: 80, speed: 60 },
              moves: [
                {
                  id: "watergun",
                  name: "Water Gun",
                  type: "Water",
                  category: "Special",
                  power: 40,
                  accuracy: 100,
                  pp: 25,
                },
              ],
            }),
          ],
        },
      ],
    }),
    { maxTurns: 2, aiProfiles: [{ difficulty: "expert", strategy: "balanced" }] },
  );

  const secondTurnTrace = state.aiTrace.find(
    (trace) => trace.turn === 2 && trace.side === 0,
  );
  assert.equal(secondTurnTrace.candidates[0].id, "maxlightning");
  assert.equal(secondTurnTrace.candidates[0].name, "Max Lightning");
});

test("makes self-destructing moves faint the user after activation", () => {
  const state = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Bomber",
                stats: { ...pokemon().stats, attack: 180, speed: 160 },
                moves: [
                  {
                    id: "explosion",
                    name: "Explosion",
                    type: "Normal",
                    category: "Physical",
                    power: 250,
                    accuracy: true,
                    pp: 5,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "BlastWall",
                stats: { ...pokemon().stats, hp: 500, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );

  assert.equal(state.sides[0].team[0].fainted, true);
  assert.equal(state.sides[0].team[0].hp, 0);
  assert.ok(state.sides[1].team[0].hp < 500);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "damage" &&
        event.pokemon === "Bomber" &&
        event.cause === "self_destruct" &&
        event.source === "Explosion",
    ),
  );
});

test("applies Salt Cure residual damage with Water and Steel bonus", () => {
  const baseBattle = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Garganacl",
              stats: { ...pokemon().stats, attack: 160, speed: 160 },
              moves: [
                {
                  id: "saltcure",
                  name: "Salt Cure",
                  type: "Rock",
                  category: "Physical",
                  power: 40,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "AI",
          team: [
            pokemon({
              name: "SteelTarget",
              types: ["Steel"],
              stats: { ...pokemon().stats, hp: 200, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  const steelHit = resolveSimpleTurn(baseBattle, [{ move: 1 }, { move: 1 }]);
  const steelResidual = steelHit.events.find(
    (event) =>
      event.type === "damage" &&
      event.pokemon === "SteelTarget" &&
      event.cause === "volatile" &&
      event.source === "Salt Cure",
  );

  assert.equal(steelHit.sides[1].team[0].volatiles.saltcure.id, "saltcure");
  assert.equal(steelResidual.damage, 50);

  const normalHit = resolveSimpleTurn(
    createSimpleBattle(
      setup({
        sides: [
          {
            name: "Player",
            team: [
              pokemon({
                name: "Garganacl",
                stats: { ...pokemon().stats, attack: 160, speed: 160 },
                moves: [
                  {
                    id: "saltcure",
                    name: "Salt Cure",
                    type: "Rock",
                    category: "Physical",
                    power: 40,
                    accuracy: true,
                    pp: 15,
                  },
                ],
              }),
            ],
          },
          {
            name: "AI",
            team: [
              pokemon({
                name: "NormalTarget",
                types: ["Normal"],
                stats: { ...pokemon().stats, hp: 200, speed: 40 },
              }),
            ],
          },
        ],
      }),
    ),
    [{ move: 1 }, { move: 1 }],
  );
  const normalResidual = normalHit.events.find(
    (event) =>
      event.type === "damage" &&
      event.pokemon === "NormalTarget" &&
      event.cause === "volatile" &&
      event.source === "Salt Cure",
  );

  assert.equal(normalResidual.damage, 25);
});

test("AI voluntarily switches to a safe counter and reports switch scores", () => {
  const scenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            id: "electric-lead",
            name: "Electric Lead",
            types: ["Electric"],
            stats: { ...pokemon().stats, hp: 180, speed: 70 },
            moves: [
              {
                id: "thunderbolt",
                name: "Thunderbolt",
                type: "Electric",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
          pokemon({
            id: "flying-counter",
            name: "Flying Counter",
            types: ["Water", "Flying"],
            stats: {
              ...pokemon().stats,
              hp: 240,
              specialAttack: 180,
              speed: 140,
            },
            moves: [
              {
                id: "surf",
                name: "Surf",
                type: "Water",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            id: "ground-threat",
            name: "Ground Threat",
            types: ["Ground"],
            stats: {
              ...pokemon().stats,
              hp: 220,
              attack: 240,
              specialDefence: 90,
              speed: 100,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  assert.deepEqual(
    chooseSimpleAiCommand(state, 0, "expert", "balanced"),
    { switch: 2 },
  );

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
  });
  const trace = battle.aiTrace.find((candidate) => candidate.side === 0);
  const switchCandidate = trace.candidates.find(
    (candidate) => candidate.type === "switch" && candidate.slot === 2,
  );
  assert.equal(trace.kind, "switch");
  assert.equal(switchCandidate.selected, true);
  assert.ok(Number.isFinite(switchCandidate.score));
  assert.ok(
    switchCandidate.reasons.some(
      (reason) => reason.code === "switch.emergency_escape",
    ),
  );
  assert.ok(
    switchCandidate.reasons.some(
      (reason) => reason.code === "simulation.one_turn_state_value",
    ),
  );
});

test("marks a bench Pokemon as the unique counter for a future ace", () => {
  const scenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            id: "current",
            name: "Current",
            types: ["Grass"],
            stats: { ...pokemon().stats, hp: 220, speed: 80 },
          }),
          pokemon({
            id: "future-counter",
            name: "Future Counter",
            types: ["Fairy"],
            stats: {
              ...pokemon().stats,
              hp: 220,
              attack: 230,
              specialDefence: 120,
              speed: 100,
            },
            moves: [
              {
                id: "playrough",
                name: "Play Rough",
                type: "Fairy",
                category: "Physical",
                power: 90,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          pokemon({
            id: "ordinary-bench",
            name: "Ordinary Bench",
            types: ["Normal"],
            stats: { ...pokemon().stats, hp: 220, speed: 90 },
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            id: "current-fire",
            name: "Current Fire",
            types: ["Fire"],
            stats: {
              ...pokemon().stats,
              hp: 200,
              specialAttack: 180,
              speed: 110,
            },
            moves: [
              {
                id: "flamethrower",
                name: "Flamethrower",
                type: "Fire",
                category: "Special",
                power: 90,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
          pokemon({
            id: "future-dragon-ace",
            name: "Future Dragon Ace",
            aiRole: "ace",
            types: ["Dragon"],
            stats: {
              ...pokemon().stats,
              hp: 180,
              attack: 190,
              speed: 125,
            },
            moves: [
              {
                id: "dragondance",
                name: "Dragon Dance",
                type: "Dragon",
                category: "Status",
                power: 0,
                accuracy: true,
                pp: 20,
                boosts: { attack: 1, speed: 1 },
              },
              {
                id: "outrage",
                name: "Outrage",
                type: "Dragon",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  const candidates = automaticSwitchCandidates(
    state,
    0,
    [],
    "expert",
    "balanced",
  );
  const futureCounter = candidates.find(
    (candidate) => candidate.id === "future-counter",
  );

  assert.equal(futureCounter.mustPreserveResource, true);
  assert.deepEqual(futureCounter.mustPreserveFor, ["Future Dragon Ace"]);
  assert.equal(futureCounter.preservationTargetIsCurrent, false);
});

test("AI lowers stay-in actions when switching clears Yawn, Salt Cure, or Toxic pressure", () => {
  const sleepTalk = {
    id: "sleeptalk",
    name: "Sleep Talk",
    type: "Normal",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 10,
  };
  const scenario = setup({
    sides: [
      {
        name: "Pressured",
        team: [
          pokemon({
            id: "pressured-active",
            name: "Pressured Active",
            stats: { ...pokemon().stats, hp: 300, speed: 90 },
            moves: [
              {
                id: "bodyslam",
                name: "Body Slam",
                type: "Normal",
                category: "Physical",
                power: 85,
                accuracy: 100,
                pp: 15,
              },
            ],
          }),
          pokemon({
            id: "healthy-bench",
            name: "Healthy Bench",
            stats: { ...pokemon().stats, hp: 300, speed: 80 },
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            id: "bulky-opponent",
            name: "Bulky Opponent",
            stats: {
              ...pokemon().stats,
              hp: 500,
              defence: 160,
              speed: 70,
            },
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  const active = state.sides[0].team[0];

  active.volatiles.yawn = { id: "yawn", turns: 1 };
  const yawnDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const yawnMove = yawnDecision.moveCandidates.find(
    (candidate) => candidate.id === "bodyslam",
  );
  assert.equal(yawnMove.yawnSwitchPressure, 220);
  assert.equal(yawnDecision.command.switch, 2);
  assert.ok(
    yawnMove.score <
      yawnMove.expectedDamage,
  );

  active.moves.push({
    ...sleepTalk,
    boosts: {},
    selfBoosts: {},
    secondaries: [],
  });
  const sleepTalkDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  assert.equal(sleepTalkDecision.moveCandidates[0].sleepExploitable, true);
  assert.equal(sleepTalkDecision.moveCandidates[0].yawnSwitchPressure, 0);

  delete active.volatiles.yawn;
  active.moves.pop();
  active.status = "tox";
  active.toxicCounter = 1;
  const earlyToxicDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const earlyToxicPenalty =
    earlyToxicDecision.moveCandidates[0].toxicSwitchPressure;
  active.toxicCounter = 6;
  const lateToxicDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  assert.ok(
    lateToxicDecision.moveCandidates[0].toxicSwitchPressure >
      earlyToxicPenalty,
  );

  active.status = "";
  active.toxicCounter = 0;
  active.volatiles.saltcure = { id: "saltcure", source: "Salt Cure" };
  active.hp = active.stats.hp;
  const fullHpSaltDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const fullHpSaltPenalty =
    fullHpSaltDecision.moveCandidates[0].saltCureSwitchPressure;
  active.hp = Math.floor(active.stats.hp / 3);
  const lowHpSaltDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  assert.ok(
    lowHpSaltDecision.moveCandidates[0].saltCureSwitchPressure >
      fullHpSaltPenalty,
  );
});

test("expert search values the free turn caused by Yawn sleep after a KO", () => {
  const scenario = setup({
    sides: [
      {
        name: "Yawn Target",
        team: [
          pokemon({
            id: "setup-attacker",
            name: "Setup Attacker",
            stats: {
              ...pokemon().stats,
              hp: 340,
              attack: 150,
              speed: 110,
            },
            moves: [
              {
                id: "strong-hit",
                name: "Strong Hit",
                type: "Normal",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          pokemon({
            id: "healthy-switch",
            name: "Healthy Switch",
            stats: { ...pokemon().stats, hp: 360, speed: 90 },
          }),
        ],
      },
      {
        name: "Yawn User",
        team: [
          pokemon({
            id: "weakened-yawner",
            name: "Weakened Yawner",
            stats: {
              ...pokemon().stats,
              hp: 420,
              defence: 130,
              speed: 60,
            },
          }),
          pokemon({
            id: "healthy-follow-up",
            name: "Healthy Follow Up",
            stats: {
              ...pokemon().stats,
              hp: 360,
              attack: 145,
              speed: 120,
            },
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  const attacker = state.sides[0].team[0];
  attacker.boosts.attack = 2;
  attacker.volatiles.yawn = { id: "yawn", turns: 1 };
  state.sides[1].team[0].hp = 40;

  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert_search",
    "balanced",
  );

  assert.equal(decision.command.switch, 2);
  assert.equal(decision.diagnostics.policy, "expectimax-two-turn");

  const awakeState = structuredClone(state);
  delete awakeState.sides[0].team[0].volatiles.yawn;
  const awakeProbability = estimateSimpleBattleWinProbability(awakeState, 0);
  awakeState.sides[0].team[0].status = "slp";
  awakeState.sides[0].team[0].statusTurns = 1;
  const shortSleepProbability = estimateSimpleBattleWinProbability(
    awakeState,
    0,
  );
  awakeState.sides[0].team[0].statusTurns = 3;
  const longSleepProbability = estimateSimpleBattleWinProbability(
    awakeState,
    0,
  );
  assert.ok(shortSleepProbability.probability < awakeProbability.probability);
  assert.ok(longSleepProbability.probability < shortSleepProbability.probability);
});

test("marks a hazard lead as role-complete after its hazard is established", () => {
  const scenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            id: "hazard-lead",
            name: "Hazard Lead",
            types: ["Rock"],
            stats: {
              ...pokemon().stats,
              hp: 240,
              attack: 130,
              speed: 60,
            },
            moves: [
              {
                id: "stealthrock",
                name: "Stealth Rock",
                type: "Rock",
                category: "Status",
                power: 0,
                accuracy: true,
                pp: 20,
                sideCondition: "stealthrock",
              },
              {
                id: "explosion",
                name: "Explosion",
                type: "Normal",
                category: "Physical",
                power: 250,
                accuracy: 100,
                pp: 5,
                selfDestruct: true,
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            id: "ordinary-opponent",
            name: "Ordinary Opponent",
            stats: { ...pokemon().stats, hp: 260, speed: 70 },
          }),
          pokemon({
            id: "ordinary-bench",
            name: "Ordinary Bench",
            stats: { ...pokemon().stats, hp: 260, speed: 70 },
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  const beforeDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const beforeExplosion = beforeDecision.moveCandidates.find(
    (candidate) => candidate.id === "explosion",
  );
  assert.equal(beforeExplosion.roleComplete, false);

  state.sides[1].conditions.stealthrock = { layers: 1 };
  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert",
    "balanced",
  );
  const explosion = decision.moveCandidates.find(
    (candidate) => candidate.id === "explosion",
  );

  assert.equal(explosion.roleComplete, true);
  assert.equal(explosion.expendableResource, true);
  assert.ok(explosion.completedRoles.includes("hazardControl"));
});

test("AI does not sacrifice an ace that cannot act after taking the switch-in hit", () => {
  const scenario = setup({
    sides: [
      {
        name: "Player",
        team: [
          pokemon({
            id: "current-wall",
            name: "Current Wall",
            types: ["Rock"],
            stats: {
              ...pokemon().stats,
              hp: 300,
              attack: 120,
              defence: 150,
              speed: 30,
            },
            moves: [
              {
                id: "earthquake",
                name: "Earthquake",
                type: "Ground",
                category: "Physical",
                power: 100,
                accuracy: 100,
                pp: 10,
              },
            ],
          }),
          pokemon({
            id: "bench-ace",
            name: "Bench Ace",
            types: ["Dragon", "Electric"],
            stats: {
              ...pokemon().stats,
              hp: 342,
              attack: 300,
              defence: 120,
              speed: 80,
            },
            moves: [
              {
                id: "outrage",
                name: "Outrage",
                type: "Dragon",
                category: "Physical",
                power: 120,
                accuracy: 100,
                pp: 10,
              },
              {
                id: "dragondance",
                name: "Dragon Dance",
                type: "Dragon",
                category: "Status",
                power: 0,
                accuracy: true,
                pp: 20,
                selfBoosts: { attack: 1, speed: 1 },
              },
            ],
          }),
        ],
      },
      {
        name: "Opponent",
        team: [
          pokemon({
            id: "rapid-strike-threat",
            name: "Rapid Strike Threat",
            types: ["Water", "Fighting"],
            stats: {
              ...pokemon().stats,
              hp: 343,
              attack: 650,
              defence: 100,
              speed: 100,
            },
            moves: [
              {
                id: "surgingstrikes",
                name: "Surging Strikes",
                type: "Water",
                category: "Physical",
                power: 25,
                accuracy: 100,
                pp: 5,
                multihit: 3,
                willCrit: true,
              },
            ],
          }),
        ],
      },
    ],
  });
  const state = createSimpleBattle(scenario);
  state.sides[0].gimmickResources.dynamax = "consumed";

  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");

  const battle = runSimpleBattle(scenario, {
    maxTurns: 1,
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
  });
  const aceSwitch = battle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.find(
      (candidate) => candidate.type === "switch" && candidate.slot === 2,
    );
  assert.equal(
    command.switch,
    undefined,
    JSON.stringify({ command, aceSwitch }),
  );
  assert.equal(aceSwitch.canReachNextAction, false);
  assert.ok(
    aceSwitch.reasons.some(
      (reason) => reason.code === "rule.switch.no_action_opportunity",
    ),
  );
});

test("AI keeps a safe guaranteed KO instead of switching", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Player",
          team: [
            pokemon({
              name: "Current Attacker",
              types: ["Grass"],
              stats: { ...pokemon().stats, attack: 220, speed: 160 },
              moves: [
                {
                  id: "leafblade",
                  name: "Leaf Blade",
                  type: "Grass",
                  category: "Physical",
                  power: 90,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
            pokemon({
              name: "Bench Attacker",
              types: ["Electric"],
              stats: { ...pokemon().stats, specialAttack: 240, speed: 170 },
              moves: [
                {
                  id: "thunderbolt",
                  name: "Thunderbolt",
                  type: "Electric",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "Opponent",
          team: [
            pokemon({
              name: "Low HP Water",
              types: ["Water"],
              stats: { ...pokemon().stats, hp: 300, speed: 40 },
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].team[0].hp = 1;

  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "balanced").move,
    1,
  );
});

test("AI uses Fake Out instead of immediately switching a fresh replacement into damage", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Snorlax Side",
          team: [
            pokemon({
              name: "Snorlax",
              types: ["Normal"],
              weightKg: 460,
              stats: {
                hp: 524,
                attack: 175,
                defence: 145,
                specialAttack: 100,
                specialDefence: 180,
                speed: 70,
              },
              moves: [
                {
                  id: "hammerarm",
                  name: "Hammer Arm",
                  type: "Fighting",
                  category: "Physical",
                  power: 100,
                  accuracy: 90,
                  pp: 10,
                },
                {
                  id: "heatcrash",
                  name: "Heat Crash",
                  type: "Fire",
                  category: "Physical",
                  power: 0,
                  accuracy: 100,
                  pp: 10,
                  dynamicPower: true,
                },
              ],
            }),
          ],
        },
        {
          name: "Drake",
          team: [
            pokemon({
              name: "Fainted Ursaluna",
              hp: 0,
              fainted: true,
            }),
            pokemon({
              name: "Weavile",
              types: ["Dark", "Ice"],
              weightKg: 34,
              stats: {
                hp: 282,
                attack: 220,
                defence: 120,
                specialAttack: 80,
                specialDefence: 130,
                speed: 240,
              },
              moves: [
                {
                  id: "fakeout",
                  name: "Fake Out",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: true,
                  priority: 3,
                  pp: 10,
                  volatileStatus: "flinch",
                },
                {
                  id: "nightslash",
                  name: "Night Slash",
                  type: "Dark",
                  category: "Physical",
                  power: 70,
                  accuracy: 100,
                  pp: 15,
                },
              ],
            }),
            pokemon({
              name: "Calyrex-Shadow",
              types: ["Psychic", "Ghost"],
              weightKg: 53.6,
              hp: 342,
              stats: {
                hp: 342,
                attack: 100,
                defence: 120,
                specialAttack: 400,
                specialDefence: 140,
                speed: 260,
              },
              moves: [
                {
                  id: "astralbarrage",
                  name: "Astral Barrage",
                  type: "Ghost",
                  category: "Special",
                  power: 120,
                  accuracy: 100,
                  pp: 5,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.turn = 7;
  state.sides[0].team[0].hp = 316;
  state.sides[1].active = 1;
  state.events.push({
    turn: 7,
    type: "switch",
    side: 1,
    fromPokemon: "Fainted Ursaluna",
    pokemon: "Weavile",
    forced: true,
    selection: "matchup_score",
  });

  const switchCandidates = automaticSwitchCandidates(
    state,
    1,
    [
      {
        id: "fakeout",
        name: "Fake Out",
        score: 80,
        expectedDamage: 40,
        accuracy: true,
        actionBeforeThreatProbability: 1,
        opponentKnockoutBeforeActionProbability: 0,
        volatileStatus: "flinch",
      },
    ],
    "expert",
    "balanced",
  );
  const calyrexSwitch = switchCandidates.find(
    (candidate) => candidate.name === "Calyrex-Shadow(으)로 교체",
  );
  assert.equal(calyrexSwitch.emergencyEscape, false);
  assert.equal(calyrexSwitch.safeActionDenialAvailable, true);
  assert.equal(calyrexSwitch.forcedReplacement, true);
  assert.ok(calyrexSwitch.switchInDamageRatio > 0);
  assert.deepEqual(chooseSimpleAiCommand(state, 1, "expert", "balanced"), {
    move: 1,
  });
});

test("AI recognizes boosted Urshifu pressure before switching in ace Zekrom", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Drake",
          team: [
            pokemon({
              id: "garganacl",
              name: "Garganacl",
              types: ["Rock"],
              stats: {
                hp: 404,
                attack: 212,
                defence: 300,
                specialAttack: 100,
                specialDefence: 216,
                speed: 106,
              },
              moves: [
                {
                  id: "earthquake",
                  name: "Earthquake",
                  type: "Ground",
                  category: "Physical",
                  power: 100,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
            pokemon({
              id: "zekrom",
              name: "Zekrom",
              level: 100,
              types: ["Dragon", "Electric"],
              stats: {
                hp: 342,
                attack: 438,
                defence: 276,
                specialAttack: 248,
                specialDefence: 236,
                speed: 279,
              },
              moves: [
                {
                  id: "boltstrike",
                  name: "Bolt Strike",
                  type: "Electric",
                  category: "Physical",
                  power: 130,
                  accuracy: 85,
                  pp: 5,
                },
                {
                  id: "outrage",
                  name: "Outrage",
                  type: "Dragon",
                  category: "Physical",
                  power: 120,
                  accuracy: 100,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        {
          name: "Urshifu Side",
          team: [
            pokemon({
              id: "urshifurapidstrike",
              name: "Urshifu-Rapid-Strike",
              level: 100,
              types: ["Water", "Fighting"],
              stats: {
                hp: 343,
                attack: 394,
                defence: 236,
                specialAttack: 145,
                specialDefence: 156,
                speed: 322,
              },
              moves: [
                {
                  id: "surgingstrikes",
                  name: "Surging Strikes",
                  type: "Water",
                  category: "Physical",
                  power: 25,
                  accuracy: 100,
                  pp: 5,
                  multihit: [3, 3],
                  willCrit: true,
                },
                {
                  id: "closecombat",
                  name: "Close Combat",
                  type: "Fighting",
                  category: "Physical",
                  power: 120,
                  accuracy: 100,
                  pp: 5,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  state.sides[1].team[0].boosts.attack = 2;

  const zekrom = automaticSwitchCandidates(
    state,
    0,
    [],
    "expert",
    "balanced",
  ).find((candidate) => candidate.slot === 2);

  assert.equal(zekrom.opponentOffensiveBoosts, 2);
  assert.equal(zekrom.targetAceQualified, true);
  assert.ok(zekrom.switchInDamageRatio >= 0.2);
  const command = chooseSimpleAiCommand(state, 0, "expert", "balanced");
  assert.notEqual(
    command.switch,
    2,
    JSON.stringify({ command, zekrom, aiTrace: state.aiTrace }),
  );
});

test("AI evaluates guaranteed KO independently for every move candidate", () => {
  const battle = runSimpleBattle(
    setup({
      sides: [
        {
          name: "Attacker",
          team: [
            pokemon({
              name: "Four Move Attacker",
              types: ["Normal", "Ground"],
              stats: {
                ...pokemon().stats,
                specialAttack: 300,
                speed: 160,
              },
              moves: [
                {
                  id: "moonblast",
                  name: "Moonblast",
                  type: "Fairy",
                  category: "Special",
                  power: 95,
                  accuracy: 100,
                  pp: 15,
                },
                {
                  id: "bloodmoon",
                  name: "Blood Moon",
                  type: "Normal",
                  category: "Special",
                  power: 140,
                  accuracy: 100,
                  pp: 5,
                },
                {
                  id: "earthpower",
                  name: "Earth Power",
                  type: "Ground",
                  category: "Special",
                  power: 90,
                  accuracy: 100,
                  pp: 10,
                },
                {
                  id: "vacuumwave",
                  name: "Vacuum Wave",
                  type: "Fighting",
                  category: "Special",
                  power: 40,
                  accuracy: 100,
                  priority: 1,
                  pp: 30,
                },
              ],
            }),
          ],
        },
        {
          name: "Low HP Target",
          team: [
            pokemon({
              name: "Low HP Target",
              stats: {
                ...pokemon().stats,
                hp: 40,
                specialDefence: 80,
                speed: 40,
              },
            }),
          ],
        },
      ],
    }),
    {
      maxTurns: 1,
      aiProfiles: [{ difficulty: "expert", strategy: "balanced" }],
    },
  );
  const moveCandidates = battle.aiTrace
    .find((trace) => trace.side === 0)
    ?.candidates.filter((candidate) => candidate.type === "move");

  assert.equal(moveCandidates.length, 4);
  assert.ok(
    moveCandidates.every((candidate) => candidate.koChance === "guaranteed"),
  );
  assert.ok(
    moveCandidates.every((candidate) =>
      candidate.reasons.some((reason) => reason.code === "ko.guaranteed"),
    ),
  );
});

test("supports new weather, offensive, defensive, and speed abilities", () => {
  const rain = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Rain",
          team: [pokemon({ name: "Pelipper", ability: "drizzle" })],
        },
        {
          name: "Sand",
          team: [pokemon({ name: "Tyranitar", ability: "sandstream" })],
        },
      ],
    }),
  );
  assert.equal(rain.field.weather.id, "sandstorm");
  assert.ok(
    rain.events.some(
      (event) => event.type === "ability_activate" && event.ability === "drizzle",
    ),
  );
  assert.ok(
    rain.events.some(
      (event) =>
        event.type === "ability_activate" && event.ability === "sandstream",
    ),
  );

  const baseAttacker = pokemon({
    types: ["Fire"],
    stats: { ...pokemon().stats, specialAttack: 150 },
  });
  const blazeAttacker = {
    ...baseAttacker,
    ability: "blaze",
    hp: 40,
  };
  const defender = pokemon({ types: ["Grass"] });
  const fireMove = { type: "Fire", category: "Special", power: 80 };
  assert.ok(
    calculateDamageRange(blazeAttacker, defender, fireMove).maximum >
      calculateDamageRange(baseAttacker, defender, fireMove).maximum,
  );

  const salted = {
    ...pokemon({ ability: "purifyingsalt", types: ["Psychic"] }),
    hp: 120,
    boosts: {},
    volatiles: {},
  };
  const ghostMove = { type: "Ghost", category: "Special", power: 80 };
  assert.ok(
    calculateDamageRange(baseAttacker, salted, ghostMove).maximum <
      calculateDamageRange(
        baseAttacker,
        pokemon({ types: ["Psychic"] }),
        ghostMove,
      ).maximum,
  );
});

test("applies Regenerator and Magnet Pull to manual and AI switching", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Steel",
          team: [
            pokemon({ name: "Steel target", types: ["Steel"] }),
            pokemon({ name: "Bench" }),
          ],
        },
        {
          name: "Trap",
          team: [pokemon({ name: "Magnezone", ability: "magnetpull" })],
        },
      ],
    }),
  );
  assert.throws(
    () => resolveSimpleTurn(state, [{ switch: 2 }, { move: 1 }]),
    /cannot switch while trapped/,
  );

  const regen = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Regen",
          team: [
            pokemon({ name: "Slowking", ability: "regenerator" }),
            pokemon({ name: "Bench" }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );
  regen.sides[0].team[0].hp = 40;
  const switched = resolveSimpleTurn(regen, [{ switch: 2 }, { move: 1 }]);
  assert.equal(switched.sides[0].team[0].hp, 80);
  assert.ok(
    switched.events.some(
      (event) =>
        event.type === "ability_activate" && event.ability === "regenerator",
    ),
  );
});

test("AI prefers a useful Regenerator switch when the active Pokemon is low on HP", () => {
  const createRegeneratorState = (ability, hp) => {
    const state = createSimpleBattle(
      setup({
        sides: [
          {
            name: "Regenerator AI",
            team: [
              pokemon({
                id: "regenerator-active",
                name: "Regenerator Active",
                ability,
                types: ["Water"],
                stats: { ...pokemon().stats, hp: 120, specialDefence: 125 },
              }),
              pokemon({
                id: "safe-bench",
                name: "Safe Bench",
                types: ["Rock"],
                stats: { ...pokemon().stats, hp: 180, defence: 145 },
              }),
            ],
          },
          {
            name: "Opponent",
            team: [
              pokemon({
                id: "weak-opponent",
                name: "Weak Opponent",
                types: ["Flying"],
                stats: { ...pokemon().stats, attack: 80, speed: 80 },
                moves: [
                  {
                    id: "wingattack",
                    name: "Wing Attack",
                    type: "Flying",
                    category: "Physical",
                    power: 60,
                    accuracy: 100,
                    pp: 35,
                  },
                ],
              }),
            ],
          },
        ],
      }),
    );
    state.sides[0].team[0].hp = hp;
    return state;
  };
  const switchCandidate = (ability, hp) =>
    automaticSwitchCandidates(
      createRegeneratorState(ability, hp),
      0,
      [],
      "expert",
      "balanced",
    ).find((candidate) => candidate.slot === 2);

  const lowHpRegenerator = switchCandidate("regenerator", 30);
  const lowHpOrdinary = switchCandidate("", 30);
  const healthyRegenerator = switchCandidate("regenerator", 90);
  const healthyOrdinary = switchCandidate("", 90);

  assert.equal(lowHpRegenerator.currentAbility, "regenerator");
  assert.equal(lowHpRegenerator.regeneratorRecoveryHp, 40);
  assert.ok(lowHpRegenerator.score > lowHpOrdinary.score);
  assert.equal(healthyRegenerator.score, healthyOrdinary.score);
});

test("supports Iron Barbs, Natural Cure, and Unaware for defensive teams", () => {
  const contactState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Contact",
          team: [
            pokemon({
              name: "Contact Attacker",
              moves: [
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                  contact: true,
                },
              ],
            }),
          ],
        },
        {
          name: "Barbs",
          team: [
            pokemon({
              name: "Ferrothorn",
              ability: "ironbarbs",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  power: 0,
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const afterContact = resolveSimpleTurn(contactState, [
    { move: 1 },
    { move: 1 },
  ]);
  assert.equal(afterContact.sides[0].team[0].hp, 105);
  assert.ok(
    afterContact.events.some(
      (event) =>
        event.type === "ability_activate" && event.ability === "ironbarbs",
    ),
  );

  const naturalCureState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Natural Cure",
          team: [
            pokemon({ name: "Blissey", ability: "naturalcure" }),
            pokemon({ name: "Bench" }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );
  naturalCureState.sides[0].team[0].status = "tox";
  const afterSwitch = resolveSimpleTurn(naturalCureState, [
    { switch: 2 },
    { move: 1 },
  ]);
  assert.equal(afterSwitch.sides[0].team[0].status, "");
  assert.ok(
    afterSwitch.events.some(
      (event) =>
        event.type === "status_cured" && event.source === "naturalcure",
    ),
  );

  const attackMove = {
    id: "bodyslam",
    name: "Body Slam",
    type: "Normal",
    category: "Physical",
    power: 85,
    accuracy: 100,
    pp: 15,
  };
  const boostedAttacker = pokemon({
    name: "Boosted",
    stats: { ...pokemon().stats, attack: 160 },
    boosts: { attack: 4 },
  });
  const neutralAttacker = {
    ...boostedAttacker,
    boosts: { attack: 0 },
  };
  const unawareDefender = pokemon({
    name: "Dondozo",
    ability: "unaware",
    stats: { ...pokemon().stats, defence: 150 },
  });
  const ordinaryDefender = {
    ...unawareDefender,
    ability: "",
  };
  assert.equal(
    calculateDamageRange(boostedAttacker, unawareDefender, attackMove).maximum,
    calculateDamageRange(neutralAttacker, unawareDefender, attackMove).maximum,
  );
  assert.ok(
    calculateDamageRange(boostedAttacker, ordinaryDefender, attackMove).maximum >
      calculateDamageRange(
        boostedAttacker,
        unawareDefender,
        attackMove,
      ).maximum,
  );
});

test("applies one-time entry boosts and Hyper Cutter protection", () => {
  const shieldState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Shield",
          team: [pokemon({ name: "Zamazenta", ability: "dauntlessshield" })],
        },
        {
          name: "Target",
          team: [pokemon()],
        },
      ],
    }),
  );
  assert.equal(shieldState.sides[0].team[0].boosts.defence, 1);

  const cutterState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Cutter",
          team: [pokemon({ name: "Mawile", ability: "hypercutter" })],
        },
        {
          name: "Intimidate",
          team: [pokemon({ name: "Arcanine", ability: "intimidate" })],
        },
      ],
    }),
  );
  assert.equal(cutterState.sides[0].team[0].boosts.attack, 0);
  assert.ok(
    cutterState.events.some(
      (event) =>
        event.type === "ability_activate" && event.ability === "hypercutter",
    ),
  );
});

test("applies Stamina, Toxic Debris, Rough Skin, and Flame Body after hits", () => {
  const physicalMove = {
    id: "scratch",
    name: "Scratch",
    type: "Normal",
    category: "Physical",
    power: 40,
    accuracy: true,
    pp: 35,
    flags: { contact: true },
  };
  const reactionState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Attacker",
          team: [pokemon({ moves: [physicalMove] })],
        },
        {
          name: "Stamina",
          team: [pokemon({ ability: "stamina", stats: { ...pokemon().stats, hp: 500 } })],
        },
      ],
    }),
  );
  const staminaResult = resolveSimpleTurn(
    reactionState,
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(staminaResult.sides[1].team[0].boosts.defence, 1);

  const debrisState = createSimpleBattle(
    setup({
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [physicalMove] })] },
        {
          name: "Debris",
          team: [pokemon({ ability: "toxicdebris", stats: { ...pokemon().stats, hp: 500 } })],
        },
      ],
    }),
  );
  const debrisResult = resolveSimpleTurn(
    debrisState,
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(debrisResult.sides[0].conditions.toxicspikes.layers, 1);

  const skinState = createSimpleBattle(
    setup({
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [physicalMove] })] },
        {
          name: "Skin",
          team: [pokemon({ ability: "roughskin", stats: { ...pokemon().stats, hp: 500 } })],
        },
      ],
    }),
  );
  const skinResult = resolveSimpleTurn(skinState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    skinResult.events.some(
      (event) => event.type === "damage" && event.source === "roughskin",
    ),
  );

  let burned = false;
  for (let seed = 1; seed <= 50 && !burned; seed += 1) {
    const flameState = createSimpleBattle({
      ...setup(),
      seed,
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [physicalMove] })] },
        {
          name: "Flame",
          team: [pokemon({ ability: "flamebody", stats: { ...pokemon().stats, hp: 500 } })],
        },
      ],
    });
    const result = resolveSimpleTurn(flameState, [{ move: 1 }, { move: 1 }]);
    burned = result.sides[0].team[0].status === "brn";
  }
  assert.equal(burned, true);
});

test("supports Lightning Rod, Good as Gold, and Magic Bounce", () => {
  const electricMove = {
    id: "thunderbolt",
    name: "Thunderbolt",
    type: "Electric",
    category: "Special",
    power: 90,
    accuracy: true,
    pp: 15,
  };
  const rodState = createSimpleBattle(
    setup({
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [electricMove] })] },
        { name: "Rod", team: [pokemon({ ability: "lightningrod" })] },
      ],
    }),
  );
  const rodResult = resolveSimpleTurn(rodState, [{ move: 1 }, { move: 1 }]);
  assert.equal(rodResult.sides[1].team[0].hp, 120);
  assert.equal(rodResult.sides[1].team[0].boosts.specialAttack, 1);

  const toxic = {
    id: "toxic",
    name: "Toxic",
    type: "Poison",
    category: "Status",
    accuracy: true,
    pp: 10,
    status: "tox",
    target: "normal",
  };
  const goldState = createSimpleBattle(
    setup({
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [toxic] })] },
        { name: "Gold", team: [pokemon({ ability: "goodasgold" })] },
      ],
    }),
  );
  const goldResult = resolveSimpleTurn(goldState, [{ move: 1 }, { move: 1 }]);
  assert.equal(goldResult.sides[1].team[0].status, "");

  const bounceState = createSimpleBattle(
    setup({
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [toxic] })] },
        { name: "Bounce", team: [pokemon({ ability: "magicbounce" })] },
      ],
    }),
  );
  const bounceResult = resolveSimpleTurn(
    bounceState,
    [{ move: 1 }, { move: 1 }],
  );
  assert.equal(bounceResult.sides[0].team[0].status, "tox");
  assert.equal(bounceResult.sides[1].team[0].status, "");
});

test("supports Bad Dreams and paradox stat boosts", () => {
  const dreamState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Sleeper",
          team: [pokemon({ status: "slp" })],
        },
        {
          name: "Darkrai",
          team: [pokemon({ ability: "baddreams" })],
        },
      ],
    }),
  );
  dreamState.sides[0].team[0].status = "slp";
  dreamState.sides[0].team[0].statusTurns = 2;
  const dreamResult = resolveSimpleTurn(
    dreamState,
    [{ move: 1 }, { move: 1 }],
  );
  assert.ok(
    dreamResult.events.some(
      (event) => event.type === "damage" && event.source === "baddreams",
    ),
  );

  const paradoxState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Quark",
          team: [
            pokemon({
              ability: "quarkdrive",
              item: "boosterenergy",
              stats: { ...pokemon().stats, specialAttack: 180 },
              moves: [
                {
                  id: "psychic",
                  name: "Psychic",
                  type: "Psychic",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 10,
                },
              ],
            }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );
  assert.equal(paradoxState.sides[0].team[0].item, "");
  assert.equal(
    paradoxState.sides[0].team[0].abilityState.paradoxStat,
    "specialAttack",
  );
});

test("supports Gale Wings, Armor Tail, and Liquid Voice in battle and AI previews", () => {
  const flyingMove = {
    id: "acrobatics",
    name: "Acrobatics",
    type: "Flying",
    category: "Physical",
    power: 55,
    accuracy: true,
    pp: 15,
  };
  const galeState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Gale",
          team: [
            pokemon({
              name: "Talonflame",
              ability: "galewings",
              types: ["Flying"],
              stats: { ...pokemon().stats, speed: 50 },
              moves: [flyingMove],
            }),
          ],
        },
        {
          name: "Fast",
          team: [pokemon({ stats: { ...pokemon().stats, speed: 150 } })],
        },
      ],
    }),
  );
  const galeResult = resolveSimpleTurn(galeState, [{ move: 1 }, { move: 1 }]);
  const firstMove = galeResult.events.find((event) => event.type === "move");
  assert.equal(firstMove.pokemon, "Talonflame");

  const tailState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Priority",
          team: [
            pokemon({
              moves: [{ ...flyingMove, id: "quickattack", type: "Normal", priority: 1 }],
            }),
          ],
        },
        { name: "Tail", team: [pokemon({ ability: "armortail" })] },
      ],
    }),
  );
  const tailResult = resolveSimpleTurn(tailState, [{ move: 1 }, { move: 1 }]);
  assert.ok(
    tailResult.events.some(
      (event) => event.type === "move_blocked" && event.source === "armortail",
    ),
  );

  const voiceState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Voice",
          team: [
            pokemon({
              ability: "liquidvoice",
              moves: [
                {
                  id: "hypervoice",
                  name: "Hyper Voice",
                  type: "Normal",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 10,
                  flags: { sound: true },
                },
              ],
            }),
          ],
        },
        { name: "Fire", team: [pokemon({ types: ["Fire"] })] },
      ],
    }),
  );
  const voice = voiceState.sides[0].team[0];
  const target = voiceState.sides[1].team[0];
  const preview = calculateMovePreview(voice, target, voice.moves[0], {
    state: voiceState,
    attackerSide: 0,
    defenderSide: 1,
  });
  assert.equal(preview.move.type, "Water");
  assert.equal(preview.range.effectiveness, 2);
});

test("applies Chlorophyll, Sand Rush, Protosynthesis, and Supreme Overlord", () => {
  for (const [ability, weather] of [
    ["chlorophyll", "sunnyday"],
    ["sandrush", "sandstorm"],
  ]) {
    const speedState = createSimpleBattle(
      setup({
        sides: [
          {
            name: "Weather runner",
            team: [
              pokemon({
                name: ability,
                ability,
                stats: { ...pokemon().stats, speed: 60 },
              }),
            ],
          },
          {
            name: "Fast target",
            team: [
              pokemon({
                name: "Fast target",
                stats: { ...pokemon().stats, speed: 100 },
              }),
            ],
          },
        ],
      }),
    );
    speedState.field.weather = { id: weather, turns: 5 };
    const result = resolveSimpleTurn(
      speedState,
      [{ move: 1 }, { move: 1 }],
    );
    assert.equal(
      result.events.find((event) => event.type === "move").pokemon,
      ability,
    );
  }

  const protoState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Proto",
          team: [
            pokemon({
              ability: "protosynthesis",
              stats: { ...pokemon().stats, attack: 180 },
            }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );
  protoState.field.weather = { id: "sunnyday", turns: 5 };
  const proto = protoState.sides[0].team[0];
  const protoTarget = protoState.sides[1].team[0];
  const boosted = calculateDamageRange(proto, protoTarget, proto.moves[0], {
    state: protoState,
    attackerSide: 0,
    defenderSide: 1,
  }).maximum;
  proto.ability = "";
  const unboosted = calculateDamageRange(proto, protoTarget, proto.moves[0], {
    state: protoState,
    attackerSide: 0,
    defenderSide: 1,
  }).maximum;
  assert.ok(boosted > unboosted);

  const overlordState = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Overlord",
          team: [
            pokemon({ ability: "supremeoverlord" }),
            pokemon({ name: "Fainted 1" }),
            pokemon({ name: "Fainted 2" }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );
  overlordState.sides[0].team[1].hp = 0;
  overlordState.sides[0].team[1].fainted = true;
  overlordState.sides[0].team[2].hp = 0;
  overlordState.sides[0].team[2].fainted = true;
  const overlord = overlordState.sides[0].team[0];
  const overlordTarget = overlordState.sides[1].team[0];
  const powered = calculateDamageRange(
    overlord,
    overlordTarget,
    overlord.moves[0],
    { state: overlordState, attackerSide: 0, defenderSide: 1 },
  ).maximum;
  overlord.ability = "";
  const plain = calculateDamageRange(
    overlord,
    overlordTarget,
    overlord.moves[0],
    { state: overlordState, attackerSide: 0, defenderSide: 1 },
  ).maximum;
  assert.ok(powered > plain);
});

test("Purifying Salt blocks status and Hospitality is explicit singles-only support", () => {
  const toxic = {
    id: "toxic",
    name: "Toxic",
    type: "Poison",
    category: "Status",
    accuracy: true,
    pp: 10,
    status: "tox",
    target: "normal",
  };
  const state = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        { name: "Attacker", team: [pokemon({ moves: [toxic] })] },
        {
          name: "Salt",
          team: [pokemon({ ability: "purifyingsalt", types: ["Rock"] })],
        },
      ],
    }),
  );
  const result = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.equal(result.sides[1].team[0].status, "");

  assert.doesNotThrow(() =>
    createSimpleBattle(
      setup({
        strictAbilityValidation: true,
        sides: [
          { name: "Hospitality", team: [pokemon({ ability: "hospitality" })] },
          { name: "Target", team: [pokemon()] },
        ],
      }),
    ),
  );
});

test("keeps heuristic and win-probability expert policies separate in traces", () => {
  const battleSetup = setup({
    sides: [
      { name: "Heuristic", team: [pokemon({ name: "Left" })] },
      { name: "Win rate", team: [pokemon({ name: "Right" })] },
    ],
  });
  const battle = runSimpleBattle(battleSetup, {
    maxTurns: 1,
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert_winrate", strategy: "balanced" },
    ],
  });

  assert.equal(battle.aiTrace[0].difficulty, "expert");
  assert.equal(battle.aiTrace[0].selectionPolicy, "heuristic");
  assert.equal(battle.aiTrace[1].difficulty, "expert_winrate");
  assert.equal(battle.aiTrace[1].selectionPolicy, "win-probability");

  const decision = chooseSimpleAiDecision(
    createSimpleBattle(battleSetup),
    1,
    "expert_winrate",
    "balanced",
  );
  assert.equal(
    decision.diagnostics.policy,
    "win-probability-simulated",
  );
  assert.ok(
    decision.diagnostics.simulationNodes +
      decision.diagnostics.simulationCacheHits >
      0,
  );
  assert.ok(
    [...decision.moveCandidates, ...decision.switchCandidates].some(
      (candidate) => candidate.winRateSimulation?.outcomes?.length > 0,
    ),
  );
});

test("evaluates two-turn search against a bounded opponent distribution", () => {
  const battleSetup = setup({
    sides: [
      {
        name: "Search",
        team: [
          pokemon({
            name: "Searcher",
            moves: [
              {
                id: "tackle",
                name: "Tackle",
                type: "Normal",
                category: "Physical",
                power: 40,
                accuracy: 100,
                pp: 35,
              },
              {
                id: "swordsdance",
                name: "Swords Dance",
                type: "Normal",
                category: "Status",
                accuracy: true,
                pp: 20,
                selfBoosts: { attack: 2 },
              },
            ],
          }),
        ],
      },
      {
        name: "Heuristic",
        team: [
          pokemon({
            name: "Opponent",
            moves: [
              {
                id: "tackle",
                name: "Tackle",
                type: "Normal",
                category: "Physical",
                power: 40,
                accuracy: 100,
                pp: 35,
              },
              {
                id: "growl",
                name: "Growl",
                type: "Normal",
                category: "Status",
                accuracy: 100,
                pp: 40,
                boosts: { attack: -1 },
              },
            ],
          }),
        ],
      },
    ],
  });
  const battle = runSimpleBattle(battleSetup, {
    maxTurns: 1,
    aiProfiles: [
      { difficulty: "expert_search", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
  });
  const trace = battle.aiTrace[0];

  assert.equal(trace.difficulty, "expert_search");
  assert.equal(trace.selectionPolicy, "expectimax-two-turn");
  assert.equal(trace.diagnostics.searchDepthTurns, 2);
  assert.ok(trace.diagnostics.searchNodes >= 2);
  assert.ok(trace.diagnostics.searchNodes <= 10);
  assert.equal(trace.diagnostics.searchBudget, 10);
  assert.equal(trace.diagnostics.searchDepthLimit, 2);
  assert.ok(trace.diagnostics.opponentCandidateCount >= 2);
  assert.ok(
    trace.candidates.some(
      (candidate) =>
        Number.isFinite(
          candidate.searchEvaluation?.expectedWinProbability,
        ),
    ),
  );
  assert.ok(
    trace.candidates.some((candidate) =>
      candidate.searchEvaluation?.outcomes?.some(
        (outcome) => outcome.continuation?.ownCommand,
      ),
    ),
  );

  const cachedBattle = runSimpleBattle(battleSetup, {
    maxTurns: 1,
    aiProfiles: [
      { difficulty: "expert_search", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
  });
  assert.deepEqual(
    cachedBattle.aiTrace[0].diagnostics.searchCommand,
    trace.diagnostics.searchCommand,
  );
  assert.ok(cachedBattle.aiTrace[0].diagnostics.searchCacheHits > 0);
});

test("Hydration cures major status in rain before residual status damage", () => {
  const splash = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
  };
  let state = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Rain",
          team: [
            pokemon({
              name: "Manaphy",
              ability: "hydration",
              moves: [splash],
            }),
          ],
        },
        {
          name: "Target",
          team: [pokemon({ name: "Target", moves: [splash] })],
        },
      ],
    }),
  );
  state.field.weather = { id: "raindance", turns: 3 };
  state.sides[0].team[0].status = "brn";
  const hpBefore = state.sides[0].team[0].hp;

  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(state.sides[0].team[0].status, "");
  assert.equal(state.sides[0].team[0].hp, hpBefore);
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "ability_activate" &&
        event.ability === "hydration" &&
        event.pokemon === "Manaphy",
    ),
  );
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "status_cured" &&
        event.source === "hydration" &&
        event.status === "brn",
    ),
  );
});

test("Multitype applies an Arceus plate type when battle state is created", () => {
  const state = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Arceus",
          team: [
            pokemon({
              id: "arceus",
              name: "Arceus",
              types: ["Normal"],
              ability: "multitype",
              item: "cobblemon:pixie_plate",
            }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );

  assert.deepEqual(state.sides[0].team[0].types, ["Fairy"]);
  assert.deepEqual(state.sides[0].team[0].originalTypes, ["Fairy"]);
  assert.equal(isSimpleAbilitySupported("multitype"), true);
  assert.equal(isSimpleAbilitySupported("hydration"), true);
});

test("Vessel of Ruin reduces incoming special damage", () => {
  const attacker = pokemon({
    stats: { ...pokemon().stats, specialAttack: 160 },
  });
  const specialMove = {
    id: "psychic",
    name: "Psychic",
    type: "Psychic",
    category: "Special",
    power: 90,
    accuracy: 100,
    pp: 10,
  };
  const ordinaryRange = calculateDamageRange(attacker, pokemon(), specialMove);
  const vesselRange = calculateDamageRange(
    attacker,
    pokemon({ name: "Ting-Lu", ability: "vesselofruin" }),
    specialMove,
  );

  assert.ok(vesselRange.maximum < ordinaryRange.maximum);
  assert.equal(isSimpleAbilitySupported("vesselofruin"), true);
});

test("accuracy abilities affect both actual attacks and AI candidate accuracy", () => {
  const inaccurateMove = {
    id: "dynamicpunch",
    name: "Dynamic Punch",
    type: "Fighting",
    category: "Physical",
    power: 100,
    accuracy: 50,
    pp: 5,
  };
  const state = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "No Guard",
          team: [
            pokemon({
              name: "Machamp",
              ability: "noguard",
              moves: [inaccurateMove],
            }),
          ],
        },
        {
          name: "Target",
          team: [
            pokemon({
              name: "Target",
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const decision = chooseSimpleAiDecision(state, 0, "expert", "balanced");
  assert.equal(decision.moveCandidates[0].accuracy, 100);
  const result = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.ok(result.sides[1].team[0].hp < result.sides[1].team[0].stats.hp);

  const compoundState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Compound Eyes",
          team: [
            pokemon({
              ability: "compoundeyes",
              moves: [inaccurateMove],
            }),
          ],
        },
        { name: "Target", team: [pokemon()] },
      ],
    }),
  );
  const compoundDecision = chooseSimpleAiDecision(
    compoundState,
    0,
    "expert",
    "balanced",
  );
  assert.equal(compoundDecision.moveCandidates[0].accuracy, 65);
});

test("type absorption abilities are visible to damage previews and activate in battle", () => {
  const cases = [
    ["voltabsorb", "Electric"],
    ["stormdrain", "Water"],
    ["dryskin", "Water"],
    ["flashfire", "Fire"],
    ["wellbakedbody", "Fire"],
    ["sapsipper", "Grass"],
    ["eartheater", "Ground"],
    ["soundproof", "Normal", { sound: true }],
  ];
  for (const [ability, type, flags = {}] of cases) {
    const attacker = pokemon();
    const defender = pokemon({ ability });
    const move = {
      id: `${ability}test`,
      name: `${ability} test`,
      type,
      category: "Special",
      power: 80,
      accuracy: true,
      pp: 10,
      flags,
    };
    assert.equal(calculateDamageRange(attacker, defender, move).effectiveness, 0);
    assert.equal(isSimpleAbilitySupported(ability), true);
  }

  const state = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Electric",
          team: [
            pokemon({
              moves: [
                {
                  id: "thunderbolt",
                  name: "Thunderbolt",
                  type: "Electric",
                  category: "Special",
                  power: 90,
                  accuracy: true,
                  pp: 15,
                },
              ],
            }),
          ],
        },
        {
          name: "Absorb",
          team: [pokemon({ name: "Jolteon", ability: "voltabsorb" })],
        },
      ],
    }),
  );
  state.sides[1].team[0].hp = 50;
  const result = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.ok(result.sides[1].team[0].hp > 50);
  assert.ok(
    result.events.some(
      (event) =>
        event.type === "ability_activate" && event.ability === "voltabsorb",
    ),
  );
});

test("Scrappy, Wonder Guard, and Infiltrator change otherwise blocked matchups", () => {
  const fightingMove = {
    id: "closecombat",
    name: "Close Combat",
    type: "Fighting",
    category: "Physical",
    power: 120,
    accuracy: true,
    pp: 5,
  };
  const ghost = pokemon({ types: ["Ghost"] });
  assert.equal(
    calculateDamageRange(pokemon(), ghost, fightingMove).effectiveness,
    0,
  );
  assert.ok(
    calculateDamageRange(
      pokemon({ ability: "scrappy" }),
      ghost,
      fightingMove,
    ).maximum > 0,
  );

  const shedinja = pokemon({
    ability: "wonderguard",
    types: ["Bug", "Ghost"],
  });
  const neutralMove = {
    ...fightingMove,
    id: "waterpulse",
    name: "Water Pulse",
    type: "Water",
    category: "Special",
    power: 60,
  };
  const superEffectiveMove = {
    ...neutralMove,
    id: "flamethrower",
    name: "Flamethrower",
    type: "Fire",
    power: 90,
  };
  assert.equal(
    calculateDamageRange(pokemon(), shedinja, neutralMove).maximum,
    0,
  );
  assert.ok(
    calculateDamageRange(pokemon(), shedinja, superEffectiveMove).maximum > 0,
  );

  const screenState = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        {
          name: "Infiltrator",
          team: [pokemon({ ability: "infiltrator" })],
        },
        { name: "Screen", team: [pokemon()] },
      ],
    }),
  );
  screenState.sides[1].conditions.reflect = { id: "reflect", turns: 5 };
  const infiltrator = screenState.sides[0].team[0];
  const screenTarget = screenState.sides[1].team[0];
  const bypassed = calculateDamageRange(
    infiltrator,
    screenTarget,
    infiltrator.moves[0],
    { state: screenState, attackerSide: 0, defenderSide: 1 },
  ).maximum;
  infiltrator.ability = "";
  const screened = calculateDamageRange(
    infiltrator,
    screenTarget,
    infiltrator.moves[0],
    { state: screenState, attackerSide: 0, defenderSide: 1 },
  ).maximum;
  assert.ok(bypassed > screened);
});

test("damage reduction abilities and Sheer Force alter damage consistently", () => {
  const contactMove = {
    id: "bodyslam",
    name: "Body Slam",
    type: "Normal",
    category: "Physical",
    power: 85,
    accuracy: true,
    pp: 15,
    flags: { contact: true },
    secondaries: [{ chance: 100, status: "par" }],
  };
  const attacker = pokemon();
  const ordinary = calculateDamageRange(attacker, pokemon(), contactMove).maximum;
  for (const ability of ["fluffy", "furcoat"]) {
    const reduced = calculateDamageRange(
      attacker,
      pokemon({ ability }),
      contactMove,
    ).maximum;
    assert.ok(reduced < ordinary);
  }
  const fireMove = {
    ...contactMove,
    id: "flamethrower",
    name: "Flamethrower",
    type: "Fire",
    category: "Special",
    flags: {},
    secondaries: [],
  };
  assert.ok(
    calculateDamageRange(attacker, pokemon({ ability: "heatproof" }), fireMove)
      .maximum <
      calculateDamageRange(attacker, pokemon(), fireMove).maximum,
  );

  const sheerForceAttacker = pokemon({
    ability: "sheerforce",
    item: "lifeorb",
    moves: [contactMove],
  });
  assert.ok(
    calculateDamageRange(sheerForceAttacker, pokemon(), contactMove).maximum >
      ordinary,
  );
  const state = createSimpleBattle(
    setup({
      strictAbilityValidation: true,
      sides: [
        { name: "Sheer Force", team: [sheerForceAttacker] },
        {
          name: "Target",
          team: [
            pokemon({
              moves: [
                {
                  id: "splash",
                  name: "Splash",
                  type: "Normal",
                  category: "Status",
                  accuracy: true,
                  pp: 40,
                },
              ],
            }),
          ],
        },
      ],
    }),
  );
  const hpBefore = state.sides[0].team[0].hp;
  const result = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.equal(result.sides[0].team[0].hp, hpBefore);
  assert.equal(result.sides[1].team[0].status, "");
});

test("Sand Stream weather expires after five turns and reports remaining turns", () => {
  const splash = {
    id: "splash",
    name: "Splash",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
  };
  let state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Sand",
          team: [
            pokemon({
              name: "Hippowdon",
              ability: "sandstream",
              types: ["Ground"],
              moves: [splash],
            }),
          ],
        },
        {
          name: "Rock",
          team: [
            pokemon({
              name: "Rock Target",
              types: ["Rock"],
              moves: [splash],
            }),
          ],
        },
      ],
    }),
  );

  assert.equal(state.field.weather.turns, 5);
  for (let turn = 0; turn < 5; turn += 1) {
    state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  }

  assert.equal(state.field.weather, null);
  assert.deepEqual(
    state.events
      .filter(
        (event) =>
          event.type === "field_tick" && event.effect === "sandstorm",
      )
      .map((event) => event.remainingTurns),
    [4, 3, 2, 1],
  );
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "field_end" &&
        event.fieldKind === "weather" &&
        event.effect === "sandstorm",
    ),
  );
});

test("AI never selects Haze when the opponent has no positive stat ranks", () => {
  const state = createSimpleBattle(
    setup({
      sides: [
        {
          name: "Haze User",
          team: [
            pokemon({
              name: "Haze User",
              moves: [
                {
                  id: "haze",
                  name: "Haze",
                  type: "Ice",
                  category: "Status",
                  accuracy: true,
                  pp: 30,
                },
                {
                  id: "tackle",
                  name: "Tackle",
                  type: "Normal",
                  category: "Physical",
                  power: 40,
                  accuracy: 100,
                  pp: 35,
                },
              ],
            }),
          ],
        },
        { name: "Target", team: [pokemon({ name: "Target" })] },
      ],
    }),
  );
  const decision = chooseSimpleAiDecision(
    state,
    0,
    "expert_search",
    "balanced",
  );
  const haze = decision.moveCandidates.find(
    (candidate) => candidate.id === "haze",
  );
  const trace = createSimpleAiDecisionTrace(
    state,
    0,
    decision,
    "expert_search",
    "balanced",
  );

  assert.equal(haze.disabled, true);
  assert.equal(decision.command.move, 2);
  assert.ok(
    trace.candidates
      .find((candidate) => candidate.id === "haze")
      .reasons.some(
        (reason) => reason.code === "rule.haze.no_opponent_boosts",
      ),
  );

  state.sides[1].team[0].boosts.attack = 2;
  const boostedDecision = chooseSimpleAiDecision(
    state,
    0,
    "expert_search",
    "balanced",
  );
  assert.equal(
    boostedDecision.moveCandidates.find(
      (candidate) => candidate.id === "haze",
    ).disabled,
    false,
  );
});
