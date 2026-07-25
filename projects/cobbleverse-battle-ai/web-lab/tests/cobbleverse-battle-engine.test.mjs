import assert from "node:assert/strict";
import test from "node:test";

import {
  calculateDamageRange,
  chooseSimpleAiCommand,
  createSimpleBattle,
  resolveSimpleTurn,
  runSimpleBattle,
  typeMultiplier,
} from "../lib/cobbleverse-battle-engine.mjs";

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
  assert.equal(
    next.events.find((event) => event.type === "switch" && event.turn === 1)
      .pokemon,
    "Reserve",
  );
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
        event.pokemon === "FireReserve" &&
        event.selection === "matchup_score",
    ),
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
  assert.equal(chooseSimpleAiCommand(state, 1, "expert", "balanced").gimmick, undefined);
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

  assert.equal(
    chooseSimpleAiCommand(state, 0, "expert", "defensive").move,
    2,
  );
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
    { move: 1, gimmick: "dynamax" },
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
  assert.ok(
    psywaved.events.some(
      (event) =>
        event.type === "damage" &&
        event.move === "Psywave" &&
        event.damage === 77,
    ),
  );
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
