import assert from "node:assert/strict";
import test from "node:test";

import {
  createSimpleBattle,
  resolveSimpleTurn,
} from "../lib/cobbleverse-battle-engine.mjs";
import {
  DYNAMAX_BLOCKED_WEIGHT_MOVES,
  resolveDynamicPostHit,
  resolveDynamicPower,
} from "../lib/native-dynamic-power.mjs";

function combatant(overrides = {}) {
  return {
    hp: 200,
    stats: { hp: 200 },
    boosts: {},
    status: "",
    item: "",
    ability: "",
    friendship: 255,
    weightKg: 100,
    turnState: { acted: false, damageTaken: 0 },
    ...overrides,
  };
}

function resolve(id, power, overrides = {}) {
  const attacker = combatant(overrides.attacker);
  const defender = combatant(overrides.defender);
  return resolveDynamicPower(
    { id, power, ...overrides.move },
    {
      state: overrides.state ?? { sides: [{ team: [attacker] }, { team: [defender] }] },
      attackerSide: 0,
      defenderSide: 1,
      attacker,
      defender,
      attackerSpeed: overrides.attackerSpeed ?? 100,
      defenderSpeed: overrides.defenderSpeed ?? 100,
      hit: overrides.hit ?? 1,
    },
  );
}

test("calculates HP, speed, weight, status, and rank based power", () => {
  assert.equal(resolve("eruption", 150).power, 150);
  assert.equal(
    resolve("eruption", 150, { attacker: { hp: 100 } }).power,
    75,
  );
  assert.equal(
    resolve("electroball", 1, {
      attackerSpeed: 400,
      defenderSpeed: 100,
    }).power,
    150,
  );
  assert.equal(
    resolve("lowkick", 1, { defender: { weightKg: 220 } }).power,
    120,
  );
  assert.equal(
    resolve("heavyslam", 1, {
      attacker: { weightKg: 500 },
      defender: { weightKg: 80 },
    }).power,
    120,
  );
  assert.equal(
    resolve("hex", 65, { defender: { status: "brn" } }).power,
    130,
  );
  assert.equal(
    resolve("venoshock", 65, { defender: { status: "psn" } }).power,
    130,
  );
  assert.equal(
    resolve("facade", 70, { attacker: { status: "brn" } }).power,
    140,
  );
  assert.equal(
    resolve("knockoff", 65, { defender: { item: "leftovers" } }).power,
    97,
  );
  assert.equal(
    resolve("storedpower", 20, {
      attacker: { boosts: { specialAttack: 2, speed: 1, defence: -1 } },
    }).power,
    80,
  );
  assert.equal(
    resolve("ragefist", 50, { attacker: { timesHit: 3 } }).power,
    200,
  );
});

test("changes power per hit for Triple Axel", () => {
  assert.deepEqual(
    [1, 2, 3].map(
      (hit) => resolve("tripleaxel", 20, { hit }).power,
    ),
    [20, 40, 60],
  );
});

test("calculates consecutive, failed-action, PP, form, and Tera based power", () => {
  assert.equal(
    resolve("furycutter", 40, {
      attacker: { consecutiveMove: { id: "furycutter", count: 2 } },
    }).power,
    160,
  );
  assert.equal(
    resolve("rollout", 30, {
      attacker: { consecutiveMove: { id: "rollout", count: 9 } },
    }).power,
    480,
  );
  assert.equal(
    resolve("rollout", 30, {
      attacker: {
        consecutiveMove: { id: "rollout", count: 9 },
        volatiles: { defensecurl: true },
      },
    }).power,
    960,
  );
  assert.equal(
    resolve("iceball", 30, {
      attacker: {
        consecutiveMove: { id: "iceball", count: 1 },
        volatiles: { defensecurl: true },
      },
    }).power,
    120,
  );
  assert.equal(
    resolve("stompingtantrum", 75, {
      attacker: { lastMoveSucceeded: false },
    }).power,
    150,
  );
  assert.equal(
    resolve("temperflare", 75, {
      attacker: { lastMoveSucceeded: true },
    }).power,
    75,
  );
  assert.equal(
    resolve("trumpcard", 1, { move: { pp: 0 } }).power,
    200,
  );
  assert.equal(
    resolve("trumpcard", 1, { move: { pp: 3 } }).power,
    50,
  );
  assert.equal(
    resolve("terablast", 80, {
      attacker: { terastallized: true, teraType: "Stellar" },
    }).power,
    100,
  );
  assert.equal(
    resolve("watershuriken", 15, {
      attacker: { id: "greninjaash", ability: "battlebond" },
    }).power,
    20,
  );
});

test("declares weight restrictions and status cures for special hits", () => {
  assert.equal(DYNAMAX_BLOCKED_WEIGHT_MOVES.has("heatcrash"), true);
  assert.equal(
    resolveDynamicPostHit({ id: "wakeupslap" }, { status: "slp" }),
    "slp",
  );
  assert.equal(
    resolveDynamicPostHit({ id: "smellingsalts" }, { status: "par" }),
    "par",
  );
});

test("tracks successful streaks and previous move failures between turns", () => {
  const pokemon = (name, speed, moves) => ({
    id: name.toLowerCase(),
    name,
    level: 50,
    types: ["Bug"],
    stats: {
      hp: 1_000,
      attack: 100,
      defence: 100,
      specialAttack: 100,
      specialDefence: 100,
      speed,
    },
    moves,
  });
  const furyCutter = {
    id: "furycutter",
    name: "Fury Cutter",
    type: "Bug",
    category: "Physical",
    power: 40,
    accuracy: true,
    pp: 20,
    dynamicPower: true,
  };
  const failedMove = {
    id: "failedmove",
    name: "Failed Move",
    type: "Normal",
    category: "Physical",
    power: 40,
    accuracy: 0,
    pp: 10,
  };
  const stompingTantrum = {
    id: "stompingtantrum",
    name: "Stomping Tantrum",
    type: "Ground",
    category: "Physical",
    power: 75,
    accuracy: true,
    pp: 10,
    dynamicPower: true,
  };
  const idleMove = {
    id: "idle",
    name: "Idle",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    selfBoosts: { defence: 1 },
  };
  let streakState = createSimpleBattle({
    seed: 21,
    sides: [
      { name: "Streak", team: [pokemon("Scizor", 150, [furyCutter])] },
      { name: "Target", team: [pokemon("Target", 50, [idleMove])] },
    ],
  });
  streakState = resolveSimpleTurn(streakState, [{ move: 1 }, { move: 1 }]);
  streakState = resolveSimpleTurn(streakState, [{ move: 1 }, { move: 1 }]);
  assert.deepEqual(
    streakState.events
      .filter((event) => event.type === "dynamic_power")
      .map((event) => event.power),
    [40, 80],
  );

  let failureState = createSimpleBattle({
    seed: 22,
    sides: [
      {
        name: "Failure",
        team: [pokemon("Tantrum", 150, [failedMove, stompingTantrum])],
      },
      { name: "Target", team: [pokemon("Target", 50, [idleMove])] },
    ],
  });
  failureState = resolveSimpleTurn(failureState, [{ move: 1 }, { move: 1 }]);
  failureState = resolveSimpleTurn(failureState, [{ move: 2 }, { move: 1 }]);
  assert.equal(
    failureState.events.find(
      (event) =>
        event.type === "dynamic_power" && event.move === "Stomping Tantrum",
    )?.power,
    150,
  );

  const teraBlast = {
    id: "terablast",
    name: "Tera Blast",
    type: "Normal",
    category: "Special",
    power: 80,
    accuracy: true,
    pp: 10,
    dynamicPower: true,
  };
  let teraState = createSimpleBattle({
    seed: 23,
    sides: [
      {
        name: "Tera",
        team: [
          {
            ...pokemon("TeraUser", 150, [teraBlast]),
            gimmicks: { teraType: "Stellar" },
          },
        ],
      },
      { name: "Target", team: [pokemon("Target", 50, [idleMove])] },
    ],
  });
  teraState = resolveSimpleTurn(teraState, [
    { move: 1, gimmick: "terastallize" },
    { move: 1 },
  ]);
  assert.equal(
    teraState.events.find(
      (event) => event.type === "dynamic_power" && event.move === "Tera Blast",
    )?.power,
    100,
  );
  assert.equal(teraState.sides[0].team[0].boosts.attack, -1);
  assert.equal(teraState.sides[0].team[0].boosts.specialAttack, -1);
});

test("locks Rollout for five uses, preserves PP, and applies Defense Curl", () => {
  const defenseCurl = {
    id: "defensecurl",
    name: "Defense Curl",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    target: "self",
    boosts: { defence: 1 },
    volatileStatus: "defensecurl",
  };
  const rollout = {
    id: "rollout",
    name: "Rollout",
    type: "Rock",
    category: "Physical",
    power: 30,
    accuracy: true,
    pp: 20,
    dynamicPower: true,
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
  const idle = {
    id: "idle",
    name: "Idle",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    target: "self",
    boosts: { defence: 1 },
  };
  const pokemon = (name, speed, moves) => ({
    id: name.toLowerCase(),
    name,
    level: 50,
    types: ["Normal"],
    stats: {
      hp: 2_000,
      attack: 100,
      defence: 200,
      specialAttack: 100,
      specialDefence: 200,
      speed,
    },
    moves,
  });
  let state = createSimpleBattle({
    seed: 31,
    sides: [
      {
        name: "Player",
        team: [pokemon("Roller", 150, [defenseCurl, rollout, tackle])],
      },
      { name: "Target", team: [pokemon("Target", 50, [idle])] },
    ],
  });

  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  assert.equal(state.sides[0].team[0].volatiles.defensecurl, true);

  state = resolveSimpleTurn(state, [{ move: 2 }, { move: 1 }]);
  for (let turn = 0; turn < 4; turn += 1) {
    state = resolveSimpleTurn(state, [{ move: 3 }, { move: 1 }]);
  }

  const roller = state.sides[0].team[0];
  assert.deepEqual(
    state.events
      .filter((event) => event.type === "dynamic_power")
      .map((event) => event.power),
    [60, 120, 240, 480, 960],
  );
  assert.equal(roller.moves[1].pp, 19);
  assert.equal(roller.lockedMove, null);
  assert.deepEqual(roller.consecutiveMove, { id: "", count: 0 });
  assert.equal(
    state.events.filter(
      (event) => event.type === "move" && event.pokemon === "Roller",
    ).at(-1)?.move,
    "Rollout",
  );

  state = resolveSimpleTurn(state, [{ move: 3 }, { move: 1 }]);
  assert.equal(
    state.events.filter(
      (event) => event.type === "move" && event.pokemon === "Roller",
    ).at(-1)?.move,
    "Tackle",
  );
});

test("ends the Rollout lock when a forced use misses", () => {
  const rollout = {
    id: "rollout",
    name: "Rollout",
    type: "Rock",
    category: "Physical",
    power: 30,
    accuracy: true,
    pp: 20,
    dynamicPower: true,
  };
  const idle = {
    id: "idle",
    name: "Idle",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    target: "self",
    boosts: { defence: 1 },
  };
  const pokemon = (name, speed, moves) => ({
    id: name.toLowerCase(),
    name,
    level: 50,
    types: ["Normal"],
    stats: {
      hp: 500,
      attack: 100,
      defence: 100,
      specialAttack: 100,
      specialDefence: 100,
      speed,
    },
    moves,
  });
  let state = createSimpleBattle({
    seed: 32,
    sides: [
      { name: "Player", team: [pokemon("Roller", 150, [rollout])] },
      { name: "Target", team: [pokemon("Target", 50, [idle])] },
    ],
  });
  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  state.sides[0].team[0].moves[0].accuracy = 0;
  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(state.sides[0].team[0].lockedMove, null);
  assert.deepEqual(state.sides[0].team[0].consecutiveMove, {
    id: "",
    count: 0,
  });
  assert.ok(
    state.events.some(
      (event) =>
        event.type === "move_lock_end" &&
        event.pokemon === "Roller" &&
        event.reason === "interrupted",
    ),
  );
});

test("forces Ice Ball while its chain is active", () => {
  const member = (name, speed, moves) => ({
    id: name.toLowerCase(),
    name,
    level: 50,
    types: ["Ice"],
    stats: {
      hp: 500,
      attack: 100,
      defence: 100,
      specialAttack: 100,
      specialDefence: 100,
      speed,
    },
    moves,
  });
  const iceBall = {
    id: "iceball",
    name: "Ice Ball",
    type: "Ice",
    category: "Physical",
    power: 30,
    accuracy: true,
    pp: 20,
    dynamicPower: true,
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
  const idle = {
    id: "idle",
    name: "Idle",
    type: "Normal",
    category: "Status",
    accuracy: true,
    pp: 40,
    target: "self",
    boosts: { defence: 1 },
  };
  let state = createSimpleBattle({
    seed: 33,
    sides: [
      { name: "Player", team: [member("IceUser", 150, [iceBall, tackle])] },
      { name: "Target", team: [member("Target", 50, [idle])] },
    ],
  });
  state = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  state = resolveSimpleTurn(state, [{ move: 2 }, { move: 1 }]);

  assert.deepEqual(
    state.events
      .filter((event) => event.type === "dynamic_power")
      .map((event) => event.power),
    [30, 60],
  );
  assert.equal(state.sides[0].team[0].moves[0].pp, 19);
  assert.deepEqual(state.sides[0].team[0].lockedMove, {
    id: "iceball",
    slot: 1,
  });
});

test("uses current action order when resolving dynamic power in battle", () => {
  const pokemon = (name, speed, move) => ({
    id: name.toLowerCase(),
    name,
    level: 50,
    types: ["Water"],
    stats: {
      hp: 500,
      attack: 100,
      defence: 100,
      specialAttack: 100,
      specialDefence: 100,
      speed,
    },
    moves: [move],
  });
  const state = createSimpleBattle({
    seed: 9,
    sides: [
      {
        name: "Fast",
        team: [
          pokemon("RendUser", 150, {
            id: "fishiousrend",
            name: "Fishious Rend",
            type: "Water",
            category: "Physical",
            power: 85,
            accuracy: true,
            pp: 10,
            dynamicPower: true,
          }),
        ],
      },
      {
        name: "Slow",
        team: [
          pokemon("PaybackUser", 50, {
            id: "payback",
            name: "Payback",
            type: "Dark",
            category: "Physical",
            power: 50,
            accuracy: true,
            pp: 10,
            dynamicPower: true,
          }),
        ],
      },
    ],
  });
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);
  const powers = Object.fromEntries(
    next.events
      .filter((event) => event.type === "dynamic_power")
      .map((event) => [event.move, event.power]),
  );

  assert.equal(powers["Fishious Rend"], 170);
  assert.equal(powers.Payback, 100);
});

test("checks accuracy separately for multiaccuracy moves", () => {
  const state = createSimpleBattle({
    seed: 3,
    sides: [
      {
        name: "Player",
        team: [
          {
            id: "triple",
            name: "Triple",
            level: 50,
            types: ["Ice"],
            stats: {
              hp: 200,
              attack: 100,
              defence: 100,
              specialAttack: 100,
              specialDefence: 100,
              speed: 150,
            },
            moves: [
              {
                id: "tripleaxel",
                name: "Triple Axel",
                type: "Ice",
                category: "Physical",
                power: 20,
                accuracy: 0,
                pp: 10,
                multihit: 3,
                multiaccuracy: true,
                dynamicPower: true,
              },
            ],
          },
        ],
      },
      {
        name: "Target",
        team: [
          {
            id: "target",
            name: "Target",
            level: 50,
            types: ["Normal"],
            stats: {
              hp: 200,
              attack: 100,
              defence: 100,
              specialAttack: 100,
              specialDefence: 100,
              speed: 50,
            },
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
          },
        ],
      },
    ],
  });
  const next = resolveSimpleTurn(state, [{ move: 1 }, { move: 1 }]);

  assert.equal(next.sides[1].team[0].hp, 200);
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "miss" &&
        event.move === "Triple Axel" &&
        event.hit === 1,
    ),
  );
});
