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
  startNativeInteractiveBattle,
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
    { id: "gmaxvoltcrash", name: "G-Max Volt Crash" },
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
      { id: "pikachu", gimmicks: { gigantamax: true } },
      { type: "Normal", category: "Status" },
    ),
    { id: "maxguard", name: "Max Guard", volatileStatus: "protect" },
  );
});

test("forces the computer to Dynamax when its entry requests it", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-ai-forced-dynamax",
    mode: "pve",
    sides: [
      scenario.sides[0],
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
        species: "charizard",
        heldItem: "",
        moveset: ["flamethrower"],
        gimmicks: { dynamax: true, gmax: true },
      },
      available: (request) =>
        request.gimmicks.canDynamax &&
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
            species: "pikachu",
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
    ["Pikachu"],
  );

  const turnBeforeReplacement = fainted.turns;
  const eventsBeforeReplacement = fainted.events.length;
  const replaced = chooseNativeInteractiveBattleAction(fainted.sessionId, {
    type: "switch",
    slot: 2,
  });
  assert.equal(replaced.turns, turnBeforeReplacement);
  assert.equal(replaced.request.kind, "move");
  assert.equal(replaced.request.active.species, "Pikachu");
  assert.equal(replaced.events.length, eventsBeforeReplacement + 1);
  assert.equal(replaced.events.at(-1).type, "switch");
  assert.match(replaced.events.at(-1).condition ?? "", /^[1-9]\d*\/[1-9]\d*/);
  assert.notEqual(replaced.events.at(-1).condition, "0 fnt");
});
