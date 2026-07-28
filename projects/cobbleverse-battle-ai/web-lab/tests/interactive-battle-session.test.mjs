import assert from "node:assert/strict";
import test from "node:test";

import {
  chooseInteractiveBattleAction,
  clearInteractiveBattleSessions,
  exportInteractiveBattleSave,
  loadInteractiveBattleSlot,
  resumeInteractiveBattle,
  saveInteractiveBattleSlot,
  startInteractiveBattle,
  undoInteractiveBattleTurn,
} from "../lib/interactive-battle-session.mjs";

const scenario = {
  scenarioId: "interactive-test",
  schemaVersion: 1,
  mode: "pve",
  seed: 777,
  battleType: "single",
  sides: [
    {
      source: "custom",
      trainerId: null,
      name: "Player",
      team: [
        {
          slot: 1,
          species: "pikachu",
          level: 50,
          gender: "M",
          nature: "jolly",
          ability: "static",
          heldItem: "lightball",
          moveset: ["thunderbolt", "quickattack"],
        },
      ],
    },
    {
      source: "custom",
      trainerId: null,
      name: "Opponent",
      team: [
        {
          slot: 1,
          species: "squirtle",
          level: 50,
          gender: "M",
          nature: "bold",
          ability: "torrent",
          heldItem: "oranberry",
          moveset: ["watergun", "tackle"],
        },
      ],
    },
  ],
};

test("plays an interactive PvE battle through player move choices", async () => {
  let battle = await startInteractiveBattle(scenario);
  assert.equal(battle.status, "awaiting_choice");
  assert.ok(battle.request.moves.some((move) => !move.disabled));
  assert.deepEqual(
    {
      type: battle.request.moves[0].type,
      category: battle.request.moves[0].category,
      power: battle.request.moves[0].power,
      accuracy: battle.request.moves[0].accuracy,
      effectiveness: battle.request.moves[0].effectiveness,
    },
    {
      type: "Electric",
      category: "Special",
      power: 90,
      accuracy: 100,
      effectiveness: "super",
    },
  );
  assert.deepEqual(battle.request.active.types, ["Electric"]);
  assert.equal(battle.request.opponent.species, "Squirtle");
  assert.deepEqual(battle.request.opponent.types, ["Water"]);
  assert.deepEqual(
    battle.request.opponent.moves.map((move) => move.id),
    ["watergun", "tackle"],
  );
  assert.equal(
    battle.request.opponent.moves.filter((move) => move.selected).length,
    1,
  );
  assert.equal(
    battle.request.opponent.decision.strategy,
    "standard-baseline",
  );
  assert.match(
    battle.request.opponent.decision.reason,
    /위력·명중률·우선도/,
  );
  assert.equal(battle.aiTrace.length, 1);
  assert.equal(battle.aiTrace[0].kind, "move");
  assert.equal(battle.aiTrace[0].strategy, "standard-baseline");

  for (let decision = 0; decision < 20 && battle.request; decision += 1) {
    const move = battle.request.moves.find((entry) => !entry.disabled);
    const action = move
      ? { type: "move", slot: move.slot }
      : { type: "switch", slot: battle.request.switches[0].slot };
    battle = await chooseInteractiveBattleAction(battle.sessionId, action);
  }

  assert.equal(battle.status, "completed");
  assert.equal(battle.winner, "Player");
  assert.ok(battle.events.some((event) => event.type === "move"));
  assert.equal(battle.request, null);
  clearInteractiveBattleSessions();
});

test("saves, loads, and rewinds Showdown PvE battle choices", async () => {
  const checkpointScenario = structuredClone(scenario);
  checkpointScenario.scenarioId = "interactive-checkpoints";
  checkpointScenario.sides[1].team[0] = {
    ...checkpointScenario.sides[1].team[0],
    species: "blissey",
    ability: "naturalcure",
    heldItem: "leftovers",
    moveset: ["splash"],
  };

  const started = await startInteractiveBattle(checkpointScenario);
  const initialTurn = started.turns;
  const savedStart = await saveInteractiveBattleSlot(started.sessionId, 1);
  assert.equal(savedStart.controls.saveSlots[0].turn, initialTurn);

  const advanced = await chooseInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
  });
  assert.ok(advanced.turns > 0);
  assert.equal(advanced.controls.canUndo, true);
  const savedTurn = await saveInteractiveBattleSlot(started.sessionId, 2);
  assert.equal(savedTurn.controls.saveSlots[1].turn, advanced.turns);
  const portableSave = exportInteractiveBattleSave(started.sessionId);
  assert.equal(portableSave.battleEngine, "showdown");

  const loadedStart = await loadInteractiveBattleSlot(started.sessionId, 1);
  assert.equal(loadedStart.turns, initialTurn);

  const loadedTurn = await loadInteractiveBattleSlot(started.sessionId, 2);
  assert.equal(loadedTurn.turns, advanced.turns);

  const rewound = await undoInteractiveBattleTurn(started.sessionId);
  assert.equal(rewound.turns, initialTurn);

  clearInteractiveBattleSessions();
  const resumed = await resumeInteractiveBattle(portableSave);
  assert.equal(resumed.turns, advanced.turns);
  assert.equal(resumed.controls.canUndo, true);
  const resumedRewind = await undoInteractiveBattleTurn(resumed.sessionId);
  assert.equal(resumedRewind.turns, initialTurn);
  clearInteractiveBattleSessions();
});

test("collects and executes slot-based commands in a double battle", async () => {
  const doubleScenario = structuredClone(scenario);
  doubleScenario.scenarioId = "interactive-double";
  doubleScenario.battleType = "double";
  doubleScenario.sides[0].team.push({
    ...doubleScenario.sides[0].team[0],
    slot: 2,
    species: "charmander",
    ability: "blaze",
    heldItem: "",
    moveset: ["ember", "scratch"],
  });
  doubleScenario.sides[1].team.push({
    ...doubleScenario.sides[1].team[0],
    slot: 2,
    species: "bulbasaur",
    ability: "overgrow",
    heldItem: "",
    moveset: ["vinewhip", "tackle"],
  });

  let battle = await startInteractiveBattle(doubleScenario);
  assert.equal(battle.request.activeSlots.length, 2);
  assert.equal(battle.request.opponents.length, 2);
  assert.deepEqual(
    battle.request.activeSlots.map((slot) => slot.active.species),
    ["Pikachu", "Charmander"],
  );

  battle = await chooseInteractiveBattleAction(battle.sessionId, {
    type: "multi",
    actions: [
      { type: "move", slot: 1, target: 1 },
      { type: "move", slot: 1, target: 2 },
    ],
  });
  assert.ok(battle.turns >= 1);
  assert.ok(
    battle.events.filter(
      (event) => event.type === "move" && event.actor?.startsWith("p1"),
    ).length >= 2,
  );
  clearInteractiveBattleSessions();
});

test("collects three sequential commands in a triple battle", async () => {
  const tripleScenario = structuredClone(scenario);
  tripleScenario.scenarioId = "interactive-triple";
  tripleScenario.battleType = "triple";
  for (const [sideIndex, species] of [
    [0, ["charmander", "bulbasaur"]],
    [1, ["eevee", "meowth"]],
  ]) {
    species.forEach((name, index) => {
      tripleScenario.sides[sideIndex].team.push({
        ...tripleScenario.sides[sideIndex].team[0],
        slot: index + 2,
        species: name,
        ability: "",
        heldItem: "",
        moveset: ["tackle"],
      });
    });
  }

  let battle = await startInteractiveBattle(tripleScenario);
  assert.equal(battle.request.activeSlots.length, 3);
  assert.equal(battle.request.opponents.length, 3);
  await assert.rejects(
    chooseInteractiveBattleAction(battle.sessionId, {
      type: "multi",
      actions: [1, 2, 3].map((target) => ({
        type: "move",
        slot: 1,
        target,
      })),
    }),
    /인접하지 않습니다/,
  );
  battle = await chooseInteractiveBattleAction(battle.sessionId, {
    type: "multi",
    actions: [3, 2, 1].map((target) => ({
      type: "move",
      slot: 1,
      target,
    })),
  });
  assert.ok(battle.turns >= 1);
  assert.ok(
    battle.events.filter(
      (event) => event.type === "move" && event.actor?.startsWith("p1"),
    ).length >= 3,
  );
  clearInteractiveBattleSessions();
});

test("executes Mega Evolution, Z-Power, Dynamax, and Terastallization commands", async () => {
  const cases = [
    {
      gimmickRules: "gen9",
      gimmick: "mega",
      eventType: "mega_evolution",
      member: {
        species: "charizard",
        heldItem: "mega_showdown:charizardite_x",
        moveset: ["flamethrower"],
        gimmicks: { mega: true },
      },
      available: (request) => request.gimmicks.canMegaEvo,
    },
    {
      gimmickRules: "gen9",
      gimmick: "zmove",
      eventType: "z_power",
      member: {
        species: "charizard",
        heldItem: "mega_showdown:firium_z",
        moveset: ["flamethrower"],
        gimmicks: {},
      },
      available: (request) => Boolean(request.gimmicks.zMoves[0]),
    },
    {
      gimmickRules: "gen8",
      gimmick: "dynamax",
      eventType: "dynamax_started",
      member: {
        species: "charizard",
        heldItem: "",
        moveset: ["flamethrower"],
        gimmicks: { dynamax: true, gmax: true },
      },
      available: (request) =>
        request.gimmicks.canDynamax &&
        request.gimmicks.gigantamax === "G-Max Wildfire",
    },
    {
      gimmickRules: "gen9",
      gimmick: "terastallize",
      eventType: "terastallized",
      member: {
        species: "charizard",
        heldItem: "",
        moveset: ["flamethrower"],
        gimmicks: { tera: "dragon" },
      },
      available: (request) =>
        request.gimmicks.canTerastallize === "Dragon",
    },
  ];

  for (const entry of cases) {
    const gimmickScenario = structuredClone(scenario);
    gimmickScenario.scenarioId = `interactive-${entry.gimmick}`;
    gimmickScenario.gimmickRules = entry.gimmickRules;
    gimmickScenario.sides[0].team[0] = {
      ...gimmickScenario.sides[0].team[0],
      ...entry.member,
      ability: "blaze",
    };
    gimmickScenario.sides[1].team[0] = {
      ...gimmickScenario.sides[1].team[0],
      species: "blissey",
      ability: "naturalcure",
      heldItem: "",
      moveset: ["splash"],
      gimmicks: {},
    };

    let battle = await startInteractiveBattle(gimmickScenario);
    assert.equal(entry.available(battle.request), true, entry.gimmick);
    battle = await chooseInteractiveBattleAction(battle.sessionId, {
      type: "move",
      slot: 1,
      gimmick: entry.gimmick,
    });
    assert.ok(
      battle.events.some(
        (event) =>
          event.type === entry.eventType && event.actor?.startsWith("p1"),
      ),
      entry.gimmick,
    );
    clearInteractiveBattleSessions();
  }
});
