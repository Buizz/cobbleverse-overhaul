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
  battleEngine: "cobbleventure",
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

test("hydrates scenario members for the Cobbleventure engine", () => {
  const setup = createNativeBattleSetup(scenario);

  assert.equal(setup.sides[0].team[0].name, "Pikachu");
  assert.equal(setup.gimmickProfile, "official_gen9");
  assert.deepEqual(setup.sides[0].team[0].types, ["Electric"]);
  assert.equal(setup.sides[0].team[0].moves[0].name, "Thunderbolt");
  assert.ok(setup.sides[0].team[0].stats.speed > 0);
});

test("hydrates Z-A Dragalgite and lets the computer Mega Evolve Dragalge", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-dragalge-mega",
    mode: "pve",
    levelMode: "level-100",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            slot: 1,
            species: "shuckle",
            level: 100,
            ability: "sturdy",
            heldItem: "leftovers",
            moveset: ["withdraw", "rest", "toxic", "protect"],
            ivs: {},
            evs: {},
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            slot: 1,
            species: "dragalge",
            level: 100,
            ability: "adaptability",
            heldItem: "zamega:dragalgite",
            moveset: [
              "dracometeor",
              "sludgebomb",
              "flipturn",
              "toxicspikes",
            ],
            ivs: {},
            evs: {},
          },
        ],
      },
    ],
  };
  const setup = createNativeBattleSetup(battleScenario);
  const dragalge = setup.sides[1].team[0];

  assert.equal(dragalge.gimmicks.megaStone.form, "Dragalge-Mega");
  assert.equal(dragalge.gimmicks.megaStone.ability, "Regenerator");
  assert.deepEqual(dragalge.gimmicks.megaStone.types, ["Poison", "Dragon"]);

  const started = startNativeInteractiveBattle(battleScenario);
  const next = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
  });

  assert.equal(next.aiTrace[0].gimmick, "mega");
  assert.ok(
    next.events.some(
      (event) =>
        event.type === "mega_evolution" &&
        event.detail === "Dragalge-Mega",
    ),
  );
  assert.equal(next.request.opponent.species, "Dragalge-Mega");
});

test("hydrates only RCT-designated members as Tera candidates", () => {
  const setup = createNativeBattleSetup({
    ...scenario,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            gimmicks: {
              tera: "electric",
              teraEligible: false,
            },
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            gimmicks: {
              teraEligible: true,
            },
          },
        ],
      },
    ],
  });

  assert.equal(setup.sides[0].team[0].gimmicks.teraConfigured, false);
  assert.equal(setup.sides[1].team[0].gimmicks.teraConfigured, true);
  assert.equal(setup.sides[1].team[0].gimmicks.teraType, "Water");
});

test("hydrates Ogerpon and Terapagos with their species-specific Tera forms", () => {
  const setup = createNativeBattleSetup({
    ...scenario,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "ogerpon-wellspring",
            ability: "waterabsorb",
            item: "wellspringmask",
            moveset: ["ivycudgel"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "terapagos",
            ability: "terashift",
            moveset: ["terastarstorm"],
          },
        ],
      },
    ],
  });
  const ogerpon = setup.sides[0].team[0];
  const terapagos = setup.sides[1].team[0];

  assert.equal(ogerpon.baseSpecies, "Ogerpon");
  assert.equal(ogerpon.gimmicks.teraType, "Water");
  assert.equal(ogerpon.speciesForms.tera.name, "Ogerpon-Wellspring-Tera");
  assert.equal(
    ogerpon.speciesForms.tera.ability,
    "Embody Aspect (Wellspring)",
  );

  assert.equal(terapagos.baseSpecies, "Terapagos");
  assert.equal(terapagos.gimmicks.teraType, "Stellar");
  assert.equal(terapagos.speciesForms.terastal.name, "Terapagos-Terastal");
  assert.equal(terapagos.speciesForms.stellar.name, "Terapagos-Stellar");
  assert.equal(
    terapagos.speciesForms.stellar.stats.hp -
      terapagos.speciesForms.terastal.stats.hp,
    65,
  );
});

test("hydrates both Aegislash forms for Stance Change", () => {
  const setup = createNativeBattleSetup({
    ...scenario,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "aegislash",
            ability: "stancechange",
            moveset: ["kingsshield", "shadowball"],
          },
        ],
      },
      scenario.sides[1],
    ],
  });
  const aegislash = setup.sides[0].team[0];

  assert.equal(aegislash.speciesForms.shield.name, "Aegislash");
  assert.equal(aegislash.speciesForms.blade.name, "Aegislash-Blade");
  assert.ok(
    aegislash.speciesForms.blade.stats.attack >
      aegislash.speciesForms.shield.stats.attack,
  );
  assert.ok(
    aegislash.speciesForms.shield.stats.defence >
      aegislash.speciesForms.blade.stats.defence,
  );
});

test("hydrates both Galarian Darmanitan forms for Zen Mode", () => {
  const setup = createNativeBattleSetup({
    ...scenario,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "darmanitangalar",
            ability: "zenmode",
            moveset: ["iciclecrash"],
          },
        ],
      },
      scenario.sides[1],
    ],
  });
  const darmanitan = setup.sides[0].team[0];

  assert.equal(darmanitan.speciesForms.base.name, "Darmanitan-Galar");
  assert.equal(darmanitan.speciesForms.zen.name, "Darmanitan-Galar-Zen");
  assert.deepEqual(darmanitan.speciesForms.base.types, ["Ice"]);
  assert.deepEqual(darmanitan.speciesForms.zen.types, ["Ice", "Fire"]);
  assert.ok(
    darmanitan.speciesForms.zen.stats.attack >
      darmanitan.speciesForms.base.stats.attack,
  );
});

test("chooses a seeded original Tera Type when virtual data omits one", () => {
  const dualTypeScenario = {
    ...scenario,
    seed: 1,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "garchomp",
            moveset: ["earthquake"],
          },
        ],
      },
      scenario.sides[1],
    ],
  };
  const first = createNativeBattleSetup(dualTypeScenario);
  const repeated = createNativeBattleSetup(dualTypeScenario);
  const nextSeed = createNativeBattleSetup({
    ...dualTypeScenario,
    seed: 2,
  });

  assert.equal(
    first.sides[0].team[0].gimmicks.teraType,
    repeated.sides[0].team[0].gimmicks.teraType,
  );
  assert.ok(
    ["Dragon", "Ground"].includes(
      first.sides[0].team[0].gimmicks.teraType,
    ),
  );
  assert.notEqual(
    first.sides[0].team[0].gimmicks.teraType,
    nextSeed.sides[0].team[0].gimmicks.teraType,
  );
});

test("runs the selected native engine and records AI settings", () => {
  const battle = runNativeScenarioBattle(scenario);

  assert.equal(battle.engine.id, "cobbleventure-simple");
  assert.equal(battle.engine.controller, "expert-baseline");
  assert.deepEqual(battle.settings, {
    battleEngine: "cobbleventure",
    aiDifficulty: "expert",
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      { difficulty: "expert", strategy: "balanced" },
    ],
    battleType: "single",
    gimmickRules: "all",
    itemRules: {
      source: "trainer",
      items: [],
      maxUses: null,
    },
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

test("filters unsupported JSON bag items without reporting the old unsupported warning", () => {
  const configured = {
    ...scenario,
    sides: scenario.sides.map((side, index) => ({
      ...side,
      bag:
        index === 1
          ? [{ item: "cobblemon:hyper_potion", quantity: 2 }]
          : [],
      battleRules: { maxItemUses: 2 },
    })),
  };
  const setup = createNativeBattleSetup(configured);
  const battle = runNativeScenarioBattle(configured);

  assert.deepEqual(setup.sides[1].bag, []);
  assert.equal(setup.sides[1].maxItemUses, 2);
  assert.equal(
    battle.warnings.some(
      (warning) =>
        warning.path === "sides.1.bag" &&
        warning.code === "native_trainer_bag_items_unsupported",
    ),
    false,
  );
});

test("applies virtual global item rules equally to both native sides", () => {
  const setup = createNativeBattleSetup({
    ...scenario,
    itemRules: {
      source: "global",
      items: ["cobblemon:full_restore", "cobblemon:full_heal"],
      maxUses: 2,
    },
  });

  for (const side of setup.sides) {
    assert.equal(side.maxItemUses, 2);
    assert.deepEqual(side.bag, [
      { item: "cobblemon:full_restore", quantity: 2 },
      { item: "cobblemon:full_heal", quantity: 2 },
    ]);
  }
});

test("omits heavy traces and snapshots from summary-only native battles", () => {
  const battle = runNativeScenarioBattle(scenario, { includeDetails: false });

  assert.equal(battle.status, "completed");
  assert.equal(battle.winner, "Red");
  assert.ok(battle.turns > 0);
  assert.equal("aiTrace" in battle, false);
  assert.equal("turnSnapshots" in battle, false);
  assert.equal("events" in battle, false);
  assert.equal("log" in battle, false);
  assert.equal("finalState" in battle, false);
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
      turn: 2,
      type: "stat_reset",
      side: 1,
      pokemon: "Boosted Target",
      source: "Haze",
    }),
    [
      {
        turn: 2,
        type: "stat_reset",
        actor: "p2a: Boosted Target",
        detail: "Haze",
      },
    ],
  );

  assert.deepEqual(
    mapNativeEvent({
      turn: 1,
      type: "move",
      side: 0,
      pokemon: "Attacker",
      move: "Flamethrower",
      moveType: "Fire",
      moveCategory: "Special",
    }),
    [
      {
        turn: 1,
        type: "move",
        actor: "p1a: Attacker",
        detail: "Flamethrower",
        moveType: "Fire",
        moveCategory: "Special",
      },
    ],
  );

  assert.deepEqual(
    mapNativeEvent({
      turn: 2,
      type: "boosts_passed",
      side: 0,
      pokemon: "Special Ace",
      source: "Baton Pass",
      boosts: { spa: 6, def: 2 },
    }),
    [
      {
        turn: 2,
        type: "boosts_passed",
        actor: "p1a: Special Ace",
        detail: "Baton Pass",
        boosts: { spa: 6, def: 2 },
      },
    ],
  );

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
        cause: "self_destruct",
      },
    ],
  );

  assert.deepEqual(
    mapNativeEvent({
      turn: 2,
      type: "damage",
      side: 1,
      pokemon: "Target",
      source: "Salt Cure",
      cause: "volatile",
      remainingHp: 90,
      maximumHp: 120,
      effectiveness: 1,
    })[0],
    {
      turn: 2,
      type: "damage",
      actor: "p2a: Target",
      condition: "90/120",
      source: "Salt Cure",
      cause: "volatile",
    },
  );

  assert.deepEqual(
    mapNativeEvent({
      turn: 1,
      type: "damage",
      side: 0,
      pokemon: "Attacker",
      source: "Defender",
      move: "Tackle",
      moveType: "Normal",
      remainingHp: 50,
      maximumHp: 120,
      effectiveness: 1,
    })[0],
    {
      turn: 1,
      type: "damage",
      actor: "p1a: Attacker",
      condition: "50/120",
      source: "",
      moveType: "Normal",
    },
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

test("runs a player-controlled PvE turn through the Cobbleventure engine", () => {
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
    switched.request.opponent.moves.every(
      (move) =>
        ["Physical", "Special", "Status"].includes(move.category) &&
        !["move", "switch", "item", "gimmick"].includes(move.type),
    ),
    "the opponent move panel must only receive complete move records",
  );
  assert.ok(
    switched.aiTrace[0].candidates.some((candidate) =>
      candidate.reasons.some((reason) => reason.code.startsWith("damage.")),
    ),
  );
  assert.ok(switched.events.some((event) => event.type === "switch"));
  assert.ok(switched.events.some((event) => event.type === "move"));
  assert.equal(
    switched.reproduction.schema,
    "cobbleventure-native-pve-reproduction",
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

test("uses the configured Tera Type when it differs from the original type", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-pve-off-type-tera-test",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "scolipede",
            moveset: ["protect"],
            gimmicks: { tera: "Water", teraEligible: true },
          },
        ],
      },
      scenario.sides[1],
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.gimmicks.canTerastallize, "Water");

  const next = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
    gimmick: "terastallize",
  });

  assert.ok(
    next.events.some(
      (event) =>
        event.type === "terastallized" &&
        event.actor === "p1a: Scolipede" &&
        event.detail === "Water",
    ),
  );
  assert.ok(
    !next.events.some(
      (event) =>
        event.type === "gimmick_rejected" &&
        event.actor === "p1a: Scolipede",
    ),
  );
});

test("gives the PvE player the shared item list with two total uses", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-pve-player-items",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "pikachu",
            moveset: ["splash"],
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
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.itemUsesRemaining, 2);
  assert.deepEqual(
    started.request.items.map((item) => [item.id, item.quantity]),
    [
      ["fullrestore", 2],
      ["potion", 2],
      ["fullheal", 2],
    ],
  );

  const damaged = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
  });
  assert.ok(damaged.request.active.condition.current < damaged.request.active.condition.maximum);
  assert.equal(
    damaged.request.items.find(
      (item) => item.id === "fullrestore",
    ).disabled,
    false,
  );

  const healed = chooseNativeInteractiveBattleAction(damaged.sessionId, {
    type: "item",
    item: "cobblemon:full_restore",
  });
  assert.equal(healed.request.itemUsesRemaining, 1);
  assert.equal(
    healed.request.items.find(
      (item) => item.id === "fullrestore",
    ).quantity,
    1,
  );
  assert.ok(
    healed.events.some(
      (event) =>
        event.type === "trainer_item" &&
        event.actor?.startsWith("p1"),
    ),
  );
});

test("native PvE cheater reads the committed player command at 100 percent", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-pve-cheater",
    mode: "pve",
    aiDifficulty: "cheater",
    aiProfiles: [
      { difficulty: "expert", strategy: "balanced" },
      {
        difficulty: "cheater",
        strategy: "balanced",
        cheatProbability: 1,
      },
    ],
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "shuckle",
            ability: "sturdy",
            moveset: ["withdraw"],
            gimmicks: {},
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "mawile",
            ability: "intimidate",
            moveset: ["suckerpunch", "tackle"],
            gimmicks: {},
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  const next = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
  });

  assert.equal(next.aiTrace[0].selectionPolicy, "cheater-exact-command");
  assert.equal(next.aiTrace[0].diagnostics.cheatActivated, true);
  assert.deepEqual(
    next.aiTrace[0].diagnostics.observedOpponentCommand,
    { move: 1 },
  );
  assert.equal(next.reproduction.turns[0].aiCommand.move, 2);
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
  assert.equal(portableSave.battleEngine, "cobbleventure");

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

test("ignores an invalid Gigantamax flag on Ho-Oh", () => {
  const setup = createNativeBattleSetup({
    ...scenario,
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            species: "ho-oh",
            resolvedSpecies: "Ho-Oh",
            moveset: ["sacredfire", "bravebird"],
            gimmicks: { gmax: true },
          },
        ],
      },
      scenario.sides[1],
    ],
  });
  const hoOh = setup.sides[0].team[0];

  assert.equal(hoOh.gimmicks.canGigantamax, false);
  assert.equal(hoOh.gimmicks.gigantamax, false);
  assert.equal(hoOh.gimmicks.forceDynamax, false);
});

test("offers each Cobbleventure gimmick only from its configured Pokémon data", () => {
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

test("offers namespaced Normalium Z for Facade and Snorlium Z only for Giga Impact", () => {
  const requestFor = (heldItem, moveset) => {
    clearNativeInteractiveBattleSessions();
    return startNativeInteractiveBattle({
      ...scenario,
      scenarioId: `native-z-${heldItem}`,
      mode: "pve",
      gimmickRules: "all",
      sides: [
        {
          ...scenario.sides[0],
          team: [
            {
              ...scenario.sides[0].team[0],
              species: "snorlax",
              heldItem,
              moveset,
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
    }).request;
  };

  const normaliumRequest = requestFor(
    "mega_showdown:normalium_z",
    ["facade", "hammerarm", "curse", "rest"],
  );
  assert.equal(normaliumRequest.gimmicks.zMoves[0]?.move, "Breakneck Blitz");
  assert.equal(normaliumRequest.gimmicks.zMoves[1], null);
  assert.equal(normaliumRequest.gimmicks.zMoves[2], null);

  const snorliumRequest = requestFor(
    "mega_showdown:snorlium_z",
    ["facade", "gigaimpact", "curse", "rest"],
  );
  assert.equal(snorliumRequest.gimmicks.zMoves[0], null);
  assert.equal(
    snorliumRequest.gimmicks.zMoves[1]?.move,
    "Pulverizing Pancake",
  );

  const incompatibleSnorliumRequest = requestFor(
    "mega_showdown:snorlium_z",
    ["facade", "hammerarm", "curse", "rest"],
  );
  assert.equal(incompatibleSnorliumRequest.gimmicks.zMoves.some(Boolean), false);
  assert.match(
    incompatibleSnorliumRequest.gimmicks.zMoveReason,
    /gigaimpact/,
  );
});

test("shows only native gimmicks compatible with the active Pokemon", () => {
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
  assert.equal(mega.request.gimmicks.canTerastallize, "fire");

  const switched = chooseNativeInteractiveBattleAction(mega.sessionId, {
    type: "switch",
    slot: 2,
  });
  assert.equal(switched.request.active.species, "Raichu");
  assert.equal(switched.request.gimmicks.canDynamax, true);
  assert.ok(switched.request.gimmicks.maxMoves[0]);

  const teraStarted = startNativeInteractiveBattle({
    ...battleScenario,
    scenarioId: "native-tera-blocks-dynamax",
  });
  const terastallized = chooseNativeInteractiveBattleAction(
    teraStarted.sessionId,
    {
      type: "move",
      slot: 1,
      gimmick: "terastallize",
    },
  );
  assert.ok(
    terastallized.events.some((event) => event.type === "terastallized"),
  );
  assert.equal(terastallized.request.gimmicks.canDynamax, false);
  assert.equal(terastallized.request.gimmicks.canGigantamax, false);
  assert.deepEqual(terastallized.request.gimmicks.maxMoves, []);
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

test("uses the player's chosen Baton Pass target instead of the AI-preferred ace", () => {
  clearNativeInteractiveBattleSessions();
  const battleScenario = {
    ...scenario,
    scenarioId: "native-player-self-switch",
    mode: "pve",
    sides: [
      {
        ...scenario.sides[0],
        team: [
          {
            ...scenario.sides[0].team[0],
            slot: 1,
            species: "scizor",
            level: 50,
            moveset: ["batonpass"],
          },
          {
            ...scenario.sides[0].team[0],
            slot: 2,
            species: "magikarp",
            level: 50,
            moveset: ["splash"],
          },
          {
            ...scenario.sides[0].team[0],
            slot: 3,
            species: "annihilape",
            level: 50,
            moveset: ["ragefist", "drainpunch", "bulkup"],
          },
        ],
      },
      {
        ...scenario.sides[1],
        team: [
          {
            ...scenario.sides[1].team[0],
            species: "shuckle",
            level: 50,
            moveset: ["withdraw"],
          },
        ],
      },
    ],
  };

  const started = startNativeInteractiveBattle(battleScenario);
  assert.equal(started.request.moves[0].selfSwitch, true);
  assert.throws(
    () =>
      chooseNativeInteractiveBattleAction(started.sessionId, {
        type: "move",
        slot: 1,
      }),
    /교체할 포켓몬을 선택/,
  );

  const switched = chooseNativeInteractiveBattleAction(started.sessionId, {
    type: "move",
    slot: 1,
    selfSwitchSlot: 2,
  });
  assert.equal(switched.request.active.species, "Magikarp");
  assert.ok(
    switched.events.some(
      (event) =>
        event.type === "switch" &&
        event.selection === "self_switch" &&
        event.actor?.includes("Magikarp"),
    ),
  );
});
