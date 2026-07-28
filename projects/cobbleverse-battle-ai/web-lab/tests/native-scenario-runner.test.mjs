import assert from "node:assert/strict";
import test from "node:test";

import {
  createNativeBattleSetup,
  mapNativeEvent,
  runNativeScenarioBattle,
} from "../lib/native-scenario-runner.mjs";
import {
  chooseNativeInteractiveBattleAction,
  clearNativeInteractiveBattleSessions,
  exportNativeInteractiveBattleSave,
  loadNativeInteractiveBattleSlot,
  resumeNativeInteractiveBattle,
  saveNativeInteractiveBattleSlot,
  startNativeInteractiveBattle,
  undoNativeInteractiveBattleTurn,
} from "../lib/native-interactive-battle-session.mjs";
import { resolveNativeMaxMove } from "../lib/native-max-moves.mjs";

const scenario = {
  scenarioId: "native-test",
  schemaVersion: 1,
  mode: "eve",
  seed: 20260725,
  levelMode: "level-50",
  battleEngine: "cobbleverse",
  aiDifficulty: "expert",
  sides: [
    {
      source: "preset",
      trainerId: "red",
      name: "Red",
      team: [
        {
          slot: 1,
          species: "pikachu",
          level: 50,
          moveset: ["thunderbolt", "quickattack"],
          ivs: {},
          evs: {},
        },
      ],
    },
    {
      source: "preset",
      trainerId: "blue",
      name: "Blue",
      team: [
        {
          slot: 1,
          species: "squirtle",
          level: 50,
          moveset: ["watergun", "tackle"],
          ivs: {},
          evs: {},
        },
      ],
    },
  ],
};

test("hydrates scenario members for the Cobbleverse engine", () => {
  const setup = createNativeBattleSetup(scenario);

  assert.equal(setup.sides[0].team[0].name, "Pikachu");
  assert.equal(setup.gimmickProfile, "official_gen9");
  assert.deepEqual(setup.sides[0].team[0].types, ["Electric"]);
  assert.equal(setup.sides[0].team[0].moves[0].name, "Thunderbolt");
  assert.ok(setup.sides[0].team[0].stats.speed > 0);
});

test("runs the selected native engine and records AI settings", () => {
  const battle = runNativeScenarioBattle(scenario);

  assert.equal(battle.engine.id, "cobbleverse-simple");
  assert.equal(battle.engine.controller, "expert-baseline");
  assert.deepEqual(battle.settings, {
    battleEngine: "cobbleverse",
    aiDifficulty: "expert",
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
    battleType: "single",
    gimmickRules: "all",
  });
  assert.equal(battle.status, "completed");
  assert.equal(battle.winner, "Red");
  assert.ok(battle.events.some((event) => event.type === "super_effective"));
  assert.ok(battle.events.some((event) => event.type === "win"));
  assert.ok(
    battle.aiTrace.every((entry) =>
      entry.candidates.every((candidate) => Array.isArray(candidate.reasons)),
    ),
  );
  assert.equal(battle.turnSnapshots[0].turn, 0);
  assert.equal(battle.turnSnapshots.at(-1).turn, battle.turns);
  assert.equal(
    battle.turnSnapshots[0].sides[0].team[0].hp,
    battle.turnSnapshots[0].sides[0].team[0].maxHp,
  );
});

test("includes HP on initial native switch events and separates side-condition layers", () => {
  const battle = runNativeScenarioBattle(
    {
      ...scenario,
      sides: [
        {
          ...scenario.sides[0],
          team: [
            {
              ...scenario.sides[0].team[0],
              moveset: ["stealthrock"],
            },
          ],
        },
        scenario.sides[1],
      ],
    },
    { maxTurns: 1 },
  );
  const initialSwitch = battle.events.find(
    (event) => event.turn === 0 && event.type === "switch" && event.actor === "p1a: Pikachu",
  );
  const rocks = battle.events.find(
    (event) => event.type === "field_started" && event.detail === "stealthrock",
  );

  assert.match(initialSwitch.condition, /^\d+\/\d+/);
  assert.equal(rocks.layers, 1);
  assert.equal(rocks.condition, undefined);
  assert.ok(battle.finalState.sides[0].team[0].maxHp > 0);
  assert.ok(battle.finalState.sides[1].team[0].maxHp > 0);
});

test("preserves native damage and heal causes for Showdown-like logs", () => {
  assert.deepEqual(
    mapNativeEvent({
      turn: 1,
      type: "damage",
      side: 0,
      pokemon: "Bomber",
      source: "Explosion",
      cause: "self_destruct",
      remainingHp: 0,
      maximumHp: 120,
      effectiveness: 1,
    }),
    [
      {
        turn: 1,
        type: "damage",
        actor: "p1a: Bomber",
        condition: "0/120",
        source: "move: Explosion",
      },
    ],
  );

  assert.equal(
    mapNativeEvent({
      turn: 1,
      type: "damage",
      side: 0,
      pokemon: "Attacker",
      source: "Defender",
      move: "Tackle",
      remainingHp: 50,
      maximumHp: 120,
      effectiveness: 1,
    })[0].source,
    "",
  );

  assert.deepEqual(
    mapNativeEvent({
      turn: 1,
      type: "damage",
      side: 1,
      pokemon: "Mega Mawile",
      source: "Attacker",
      move: "Dragon Claw",
      damage: 0,
      remainingHp: 200,
      maximumHp: 200,
      effectiveness: 0,
    }),
    [
      {
        turn: 1,
        type: "immune",
        actor: "p2a: Mega Mawile",
      },
    ],
  );

  assert.equal(
    mapNativeEvent({
      turn: 1,
      type: "heal",
      side: 0,
      pokemon: "Holder",
      source: "Leftovers",
      cause: "item",
      remainingHp: 80,
      maximumHp: 120,
    })[0].source,
    "item: Leftovers",
  );

  assert.deepEqual(
    mapNativeEvent({
      turn: 1,
      type: "cant_move",
      side: 1,
      pokemon: "Target",
      status: "flinch",
      source: "flinch",
    }),
    [
      {
        turn: 1,
        type: "cant_move",
        actor: "p2a: Target",
        detail: "풀죽어서 행동할 수 없다.",
        condition: "flinch",
      },
    ],
  );
});

test("preserves voluntary and faint replacement switch reasons for reports", () => {
  assert.deepEqual(
    mapNativeEvent({
      turn: 3,
      type: "switch",
      side: 1,
      fromPokemon: "Garganacl",
      pokemon: "Ursaluna-Bloodmoon",
      remainingHp: 428,
      maximumHp: 428,
      automatic: false,
      selection: "manual_switch",
    }),
    [
      {
        turn: 3,
        type: "switch",
        actor: "p2a: Ursaluna-Bloodmoon",
        detail: "Ursaluna-Bloodmoon",
        condition: "428/428",
        fromActor: "p2a: Garganacl",
        automatic: false,
        forced: false,
        selection: "manual_switch",
        source: "",
      },
    ],
  );

  const faintReplacement = mapNativeEvent({
    turn: 4,
    type: "switch",
    side: 0,
    fromPokemon: "Mawile",
    pokemon: "Blaziken",
    remainingHp: 303,
    maximumHp: 303,
    automatic: true,
    forced: true,
    selection: "matchup_score",
  })[0];
  assert.equal(faintReplacement.fromActor, "p1a: Mawile");
  assert.equal(faintReplacement.forced, true);
  assert.equal(faintReplacement.selection, "matchup_score");
});

test("shows a readable flinch message for native Fake Out", () => {
  const battle = runNativeScenarioBattle({
    ...scenario,
    seed: 1,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "meowth",
            moveset: ["fakeout"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "squirtle",
            moveset: ["tackle"],
          },
        ],
      },
    ],
  });

  assert.ok(
    battle.events.some(
      (event) =>
        event.type === "cant_move" &&
        event.detail === "풀죽어서 행동할 수 없다." &&
        event.condition === "flinch",
    ),
  );
});

test("runs a player-controlled PvE turn through the Cobbleverse engine", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-pve-test",
    mode: "pve",
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert", strategy: "hazard" },
    ],
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "raichu",
            moveset: ["thunderbolt", "quickattack"],
            gimmicks: { dynamax: false },
          },
          {
            ...scenario.sides[0].team[0],
            slot: 2,
            species: "charmander",
            moveset: ["ember", "scratch"],
          },
        ],
      },
      scenario.sides[1],
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.status, "awaiting_choice");
  assert.equal(started.request.moves[0].id, "thunderbolt");
  assert.equal(started.request.switches[0].species, "Charmander");
  assert.equal(started.request.gimmicks.canDynamax, true);
  assert.equal(started.request.gimmicks.maxMoves.length, 2);
  assert.equal(started.request.gimmicks.maxMoves[0].id, "maxlightning");
  assert.equal(started.request.gimmicks.maxMoves[0].move, "Max Lightning");
  assert.equal(started.request.gimmicks.gigantamax, "");
  assert.equal(started.request.gimmicks.canTerastallize, "Electric");
  assert.ok(started.sessionId.startsWith("native-"));

  const switched = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "switch",
    slot: 2,
  });
  assert.equal(switched.turns, 1);
  assert.equal(switched.request.active.species, "Charmander");
  assert.equal(switched.aiTrace.length, 1);
  assert.equal(switched.aiTrace[0].strategy, "hazard");
  assert.ok(
    switched.aiTrace[0].candidates.some((candidate) =>
      candidate.reasons.some((reason) => reason.code.startsWith("damage.")),
    ),
  );
  assert.ok(switched.events.some((event) => event.type === "switch"));
  assert.ok(switched.events.some((event) => event.type === "move"));
  assert.equal(
    switched.reproduction.schema,
    "cobbleverse-native-pve-reproduction",
  );
  assert.equal(switched.reproduction.scenario.scenarioId, "native-pve-test");
  assert.equal(switched.reproduction.turns.length, 1);
  assert.deepEqual(switched.reproduction.turns[0].playerCommand, { switch: 2 });
  assert.deepEqual(
    switched.reproduction.turns[0].aiCommand,
    switched.reproduction.turns[0].aiDecision.command,
  );
  assert.equal(
    switched.aiTrace[0].diagnostics.selectionSource,
    switched.reproduction.turns[0].aiDecision.trace.diagnostics.selectionSource,
  );
  assert.equal(
    switched.aiTrace[0].candidates.find(
      (candidate) => candidate.selected && candidate.type === "move",
    )?.slot,
    switched.reproduction.turns[0].aiCommand.move,
  );
});

test("saves, loads, and rewinds native PvE battle checkpoints", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-pve-checkpoints",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "raichu",
            moveset: ["thunderbolt", "quickattack"],
          },
          {
            ...scenario.sides[0].team[0],
            slot: 2,
            species: "charmander",
            moveset: ["ember", "scratch"],
          },
        ],
      },
      scenario.sides[1],
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  const savedStart = saveNativeInteractiveBattleSlot(started.sessionId, 1);
  assert.equal(savedStart.controls.saveSlots[0].turn, 0);

  const switched = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "switch",
    slot: 2,
  });
  assert.equal(switched.turns, 1);
  assert.equal(switched.controls.canUndo, true);
  const savedTurn = saveNativeInteractiveBattleSlot(started.sessionId, 2);
  assert.equal(savedTurn.controls.saveSlots[1].turn, 1);
  const portableSave = exportNativeInteractiveBattleSave(started.sessionId);
  assert.equal(portableSave.battleEngine, "cobbleverse");

  const loadedStart = loadNativeInteractiveBattleSlot(started.sessionId, 1);
  assert.equal(loadedStart.turns, 0);
  assert.equal(loadedStart.request.active.species, "Raichu");

  const loadedTurn = loadNativeInteractiveBattleSlot(started.sessionId, 2);
  assert.equal(loadedTurn.turns, 1);
  assert.equal(loadedTurn.request.active.species, "Charmander");

  const rewound = undoNativeInteractiveBattleTurn(started.sessionId);
  assert.equal(rewound.turns, 0);
  assert.equal(rewound.request.active.species, "Raichu");
  assert.equal(rewound.reproduction.turns.length, 0);

  clearNativeInteractiveBattleSessions();
  const resumed = resumeNativeInteractiveBattle(portableSave);
  assert.equal(resumed.turns, 1);
  assert.equal(resumed.request.active.species, "Charmander");
  assert.equal(resumed.controls.canUndo, true);
  const resumedRewind = undoNativeInteractiveBattleTurn(resumed.sessionId);
  assert.equal(resumedRewind.turns, 0);
  assert.equal(resumedRewind.request.active.species, "Raichu");
  clearNativeInteractiveBattleSessions();
});

test("shows dynamic power move effectiveness against the current opponent", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-dynamic-power-preview",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "snorlax",
            moveset: ["heatcrash"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "garganacl",
            moveset: ["earthquake"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.moves[0].power, 40);
  assert.equal(started.request.moves[0].effectiveness, "resisted");
});

test("disables Blood Moon in the player request after a successful use", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-blood-moon-cooldown",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "ursaluna-bloodmoon",
            moveset: ["bloodmoon", "earthpower"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "shuckle",
            moveset: ["withdraw"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.moves[0].id, "bloodmoon");
  assert.equal(started.request.moves[0].disabled, false);

  const afterBloodMoon = chooseNativeInteractiveBattleAction(
    started.sessionId,
    {
      type: "move",
      slot: 1,
    },
  );
  assert.equal(afterBloodMoon.request.moves[0].disabled, true);
  assert.equal(afterBloodMoon.request.moves[1].disabled, false);
});

test("keeps native player moves displayed as Max Moves while Dynamax is active", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-player-dynamax-display",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "raichu",
            level: 50,
            moveset: ["thunderbolt", "quickattack"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "blissey",
            level: 50,
            moveset: ["pound"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  const next = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
    gimmick: "dynamax",
  });

  assert.equal(next.status, "awaiting_choice");
  assert.equal(next.request.gimmicks.canMegaEvo, false);
  assert.equal(next.request.gimmicks.canDynamax, false);
  assert.equal(next.request.gimmicks.canTerastallize, "");
  assert.equal(next.request.moves[0].id, "maxlightning");
  assert.equal(next.request.moves[0].name, "Max Lightning");
  assert.equal(next.request.moves[1].id, "maxstrike");
  assert.equal(next.request.moves[1].name, "Max Strike");
  assert.equal(next.request.gimmicks.maxMoves[0].id, "maxlightning");
});

test("maps original moves to standard and Gigantamax move identities", () => {
  assert.deepEqual(
    resolveNativeMaxMove(
      { id: "pikachu", gimmicks: { gigantamax: false } },
      { type: "Electric", category: "Special" },
    ),
    { id: "maxlightning", name: "Max Lightning", terrain: "electricterrain" },
  );
  assert.deepEqual(
    resolveNativeMaxMove(
      { id: "pikachu", gimmicks: { gigantamax: true } },
      { type: "Electric", category: "Physical" },
    ),
    { id: "gmaxvoltcrash", name: "G-Max Volt Crash", type: "electric" },
  );
  assert.deepEqual(
    resolveNativeMaxMove(
      { id: "pikachu", gimmicks: { gigantamax: true } },
      { type: "Water", category: "Special" },
    ),
    { id: "maxgeyser", name: "Max Geyser", weather: "raindance" },
  );
  assert.deepEqual(
    resolveNativeMaxMove(
      { id: "urshifu-rapidstrike", gimmicks: { gigantamax: true } },
      { type: "Water", category: "Physical" },
    ),
    {
      id: "gmaxrapidflow",
      name: "G-Max Rapid Flow",
      type: "water",
      bypassProtect: true,
    },
  );
  assert.deepEqual(
    resolveNativeMaxMove(
      { id: "pikachu", gimmicks: { gigantamax: true } },
      { type: "Normal", category: "Status" },
    ),
    { id: "maxguard", name: "Max Guard", volatileStatus: "protect" },
  );
});

test("offers Urshifu Gigantamax through Max controls in native PvE", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-player-gigantamax-urshifu",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "urshifu-rapidstrike",
            resolvedSpecies: "Urshifu-Rapid-Strike",
            level: 50,
            ability: "unseenfist",
            gimmicks: {},
            moveset: ["surgingstrikes", "closecombat"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "blissey",
            level: 50,
            moveset: ["protect"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.gimmicks.canDynamax, false);
  assert.equal(started.request.gimmicks.canGigantamax, true);
  assert.equal(started.request.gimmicks.gigantamax, "gigantamax");
  assert.equal(started.request.gimmicks.maxMoves[0].id, "gmaxrapidflow");

  const next = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
    gimmick: "gigantamax",
  });
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "dynamax_started" && event.detail === "gigantamax",
    ),
  );
  assert.ok(
    next.events.some(
      (event) => event.type === "move" && event.detail === "G-Max Rapid Flow",
    ),
  );
});

test("forces the computer to Dynamax when its entry requests it", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-ai-forced-dynamax",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "snorlax",
            moveset: ["heatcrash", "tackle"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            gimmicks: { dynamax: true, gmax: false },
          },
        ],
      },
    ],
  };

  let battle = startNativeInteractiveBattle(battleScenario);
  battle = chooseNativeInteractiveBattleAction(battle.sessionId, {
    type: "move",
    slot: 1,
  });

  assert.ok(
    battle.events.some(
      (event) =>
        event.type === "dynamax_started" && event.actor.startsWith("p2"),
    ),
  );
  assert.equal(battle.aiTrace[0].gimmick, "dynamax");
  assert.equal(
    battle.request.moves.find((move) => move.id === "heatcrash")?.disabled,
    true,
  );
});

test("forces the computer to Gigantamax when its entry requests G-Max", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-ai-forced-gigantamax",
    mode: "pve",
    sides: [
      scenario.sides[0],
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "urshifu-rapidstrike",
            resolvedSpecies: "Urshifu-Rapid-Strike",
            ability: "unseenfist",
            moveset: ["surgingstrikes"],
            gimmicks: { dynamax: true, gmax: true },
          },
        ],
      },
    ],
  };

  let battle = startNativeInteractiveBattle(battleScenario);
  battle = chooseNativeInteractiveBattleAction(battle.sessionId, {
    type: "move",
    slot: 1,
  });

  assert.equal(battle.aiTrace[0].gimmick, "gigantamax");
  assert.ok(
    battle.events.some(
      (event) =>
        event.type === "dynamax_started" &&
        event.actor.startsWith("p2") &&
        event.detail === "gigantamax",
    ),
  );
  assert.ok(
    battle.events.some(
      (event) => event.type === "move" && event.detail === "G-Max Rapid Flow",
    ),
  );
});

test("offers each Cobbleverse gimmick only from its configured Pokémon data", () => {
  const cases = [
    {
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
      gimmick: "zmove",
      eventType: "z_power",
      member: {
        species: "pikachu",
        heldItem: "mega_showdown:electrium_z",
        moveset: ["thunderbolt"],
        gimmicks: {},
      },
      available: (request) => Boolean(request.gimmicks.zMoves[0]),
    },
    {
      gimmick: "dynamax",
      eventType: "dynamax_started",
      member: {
        species: "raichu",
        heldItem: "",
        moveset: ["thunderbolt"],
        gimmicks: { dynamax: true, gmax: false },
      },
      available: (request) => request.gimmicks.canDynamax,
    },
    {
      gimmick: "gigantamax",
      eventType: "dynamax_started",
      member: {
        species: "charizard",
        heldItem: "",
        moveset: ["flamethrower"],
        gimmicks: { dynamax: true, gmax: true },
      },
      available: (request) =>
        request.gimmicks.canGigantamax &&
        request.gimmicks.gigantamax === "gigantamax",
    },
    {
      gimmick: "terastallize",
      eventType: "terastallized",
      member: {
        species: "pikachu",
        heldItem: "",
        moveset: ["thunderbolt"],
        gimmicks: { tera: "electric" },
      },
      available: (request) =>
        request.gimmicks.canTerastallize === "electric",
    },
  ];

  for (const entry of cases) {
    const battleScenario = {
      ...scenario,
      scenarioId: `native-${entry.gimmick}`,
      mode: "pve",
      gimmickRules: "all",
      sides: [
        {
          ...scenario.sides[0],
          team: [
            {
              ...scenario.sides[0].team[0],
              ...entry.member,
            },
          ],
        },
        {
          ...scenario.sides[1],
          team: [
            {
              ...scenario.sides[1].team[0],
              species: "shuckle",
              moveset: ["withdraw"],
            },
          ],
        },
      ],
    };

    clearNativeInteractiveBattleSessions();
    let battle = startNativeInteractiveBattle(battleScenario);
    assert.equal(entry.available(battle.request), true, entry.gimmick);
    battle = chooseNativeInteractiveBattleAction(battle.sessionId, {
      type: "move",
      slot: 1,
      gimmick: entry.gimmick,
    });
    assert.ok(
      battle.events.some((event) => event.type === entry.eventType),
      `${entry.gimmick} should emit ${entry.eventType}`,
    );
  }
});

test("hides Dynamax choices after native player Mega Evolution", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-mega-blocks-dynamax",
    mode: "pve",
    gimmickRules: "all",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "charizard",
            heldItem: "mega_showdown:charizardite_x",
            moveset: ["flamethrower"],
            gimmicks: { mega: true, tera: "fire" },
          },
          {
            ...scenario.sides[0].team[0],
            slot: 2,
            species: "raichu",
            heldItem: "",
            moveset: ["thunderbolt"],
            gimmicks: { dynamax: true },
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "shuckle",
            moveset: ["withdraw"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.gimmicks.canMegaEvo, true);
  assert.equal(started.request.gimmicks.canDynamax, false);
  assert.equal(started.request.gimmicks.canGigantamax, true);
  assert.equal(started.request.gimmicks.canTerastallize, "fire");

  const mega = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
    gimmick: "mega",
  });
  assert.ok(mega.events.some((event) => event.type === "mega_evolution"));
  assert.equal(mega.request.gimmicks.canMegaEvo, false);
  assert.equal(mega.request.gimmicks.canDynamax, false);
  assert.deepEqual(mega.request.gimmicks.maxMoves, []);
  assert.deepEqual(mega.request.gimmicks.zMoves, []);
  assert.equal(mega.request.gimmicks.canTerastallize, "");

  const switched = chooseNativeInteractiveBattleAction(mega.sessionId, {
    type: "switch",
    slot: 2,
  });
  assert.equal(switched.request.active.species, "Raichu");
  assert.equal(switched.request.gimmicks.canDynamax, true);
  assert.ok(switched.request.gimmicks.maxMoves[0]);
});

test("waits for the player to choose a replacement after fainting", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-forced-switch",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            slot: 1,
            species: "magikarp",
            level: 1,
            moveset: ["splash"],
          },
          {
            ...scenario.sides[0].team[0],
            slot: 2,
            species: "raichu",
            level: 50,
            moveset: ["thunderbolt"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "mewtwo",
            level: 100,
            moveset: ["psychic"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  const fainted = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
  });
  assert.equal(fainted.request.kind, "force_switch");
  assert.equal(fainted.request.active.species, "Magikarp");
  assert.equal(fainted.request.active.condition.fainted, true);
  assert.deepEqual(
    fainted.request.switches.map((pokemon) => pokemon.species),
    ["Raichu"],
  );

  const turnBeforeReplacement = fainted.turns;
  const eventsBeforeReplacement = fainted.events.length;
  const replaced = chooseNativeInteractiveBattleAction(fainted.sessionId, {
    type: "switch",
    slot: 2,
  });
  assert.equal(replaced.turns, turnBeforeReplacement);
  assert.equal(replaced.request.kind, "move");
  assert.equal(replaced.request.active.species, "Raichu");
  assert.equal(replaced.events.length, eventsBeforeReplacement + 1);
  assert.equal(replaced.events.at(-1).type, "switch");
  assert.match(replaced.events.at(-1).condition ?? "", /^[1-9]\d*\/[1-9]\d*/);
  assert.notEqual(replaced.events.at(-1).condition, "0 fnt");
});
