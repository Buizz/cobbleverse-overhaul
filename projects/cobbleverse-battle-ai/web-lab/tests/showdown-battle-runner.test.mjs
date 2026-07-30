import assert from "node:assert/strict";
import test from "node:test";

import {
  battleEvents,
  convertScenarioTeams,
  runAutomaticBattle,
} from "../lib/showdown-battle-runner.mjs";

const scenario = {
  scenarioId: "eve-test",
  schemaVersion: 1,
  mode: "eve",
  seed: 12345,
  battleType: "single",
  sides: [
    {
      source: "custom",
      trainerId: null,
      name: "Red",
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
      name: "Blue",
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

test("converts scenario teams to packed Showdown teams", () => {
  const result = convertScenarioTeams(scenario);
  assert.equal(result.packedTeams.length, 2);
  assert.ok(result.packedTeams[0].includes("Pikachu"));
  assert.ok(result.packedTeams[1].includes("Squirtle"));
  assert.deepEqual(result.warnings, []);
});

test("uses the same seeded original Tera Type for virtual Showdown teams", () => {
  const altered = structuredClone(scenario);
  altered.seed = 1;
  altered.sides[0].team[0] = {
    ...altered.sides[0].team[0],
    species: "garchomp",
    moveset: ["earthquake"],
  };
  const first = convertScenarioTeams(altered);
  const repeated = convertScenarioTeams(altered);
  const nextSeed = convertScenarioTeams({ ...altered, seed: 2 });

  assert.equal(first.packedTeams[0], repeated.packedTeams[0]);
  assert.notEqual(first.packedTeams[0], nextSeed.packedTeams[0]);
});

test("recognizes the rapid-strike Urshifu form used by the official entry", () => {
  const altered = structuredClone(scenario);
  altered.sides[0].team[0] = {
    ...altered.sides[0].team[0],
    species: "urshifu-rapidstrike",
    ability: "unseenfist",
    moveset: ["surgingstrikes"],
  };

  const result = convertScenarioTeams(altered);

  assert.ok(result.packedTeams[0].includes("Urshifu-Rapid-Strike"));
  assert.deepEqual(result.warnings, []);
});

test("packs an aspect-based regional form as the resolved Showdown species", () => {
  const altered = structuredClone(scenario);
  altered.sides[0].team[0] = {
    ...altered.sides[0].team[0],
    species: "articuno",
    aspects: ["galarian"],
    ability: "competitive",
    moveset: ["freezingglare"],
  };

  const result = convertScenarioTeams(altered);

  assert.ok(result.packedTeams[0].includes("Articuno-Galar"));
  assert.deepEqual(result.warnings, []);
});

test("runs a deterministic automatic battle to completion", async () => {
  const first = await runAutomaticBattle(scenario, { timeoutMs: 5_000 });
  const second = await runAutomaticBattle(scenario, { timeoutMs: 5_000 });

  assert.equal(first.status, "completed");
  assert.equal(first.winner, "Red");
  assert.ok(first.turns > 0);
  assert.ok(first.events.some((event) => event.type === "move"));
  assert.ok(first.log.some((line) => line === "|win|Red"));
  assert.equal(second.winner, first.winner);
  assert.equal(second.turns, first.turns);
  assert.deepEqual(second.events, first.events);
});

test("runs each EVE side with an independent AI profile and decision trace", async () => {
  const profiled = {
    ...structuredClone(scenario),
    aiProfiles: [
      { difficulty: "expert", strategy: "aggressive" },
      { difficulty: "standard", strategy: "unpredictable" },
    ],
  };
  const battle = await runAutomaticBattle(profiled, { timeoutMs: 5_000 });

  assert.deepEqual(battle.settings.aiProfiles, profiled.aiProfiles);
  assert.match(battle.engine.controller, /expert-aggressive-vs-standard-unpredictable/);
  assert.ok(battle.aiTrace.some((entry) => entry.side === 0));
  assert.ok(battle.aiTrace.some((entry) => entry.side === 1));
  assert.ok(
    battle.aiTrace.every(
      (entry) => entry.reason && Array.isArray(entry.candidates),
    ),
  );
  assert.ok(
    battle.aiTrace.some((entry) =>
      entry.candidates.some((candidate) => Number.isFinite(candidate.score)),
    ),
  );
});

test("runs double and triple battles through their separated Showdown formats", async () => {
  const species = [
    ["pikachu", "static", ["thunderbolt", "quickattack"]],
    ["bulbasaur", "overgrow", ["razorleaf", "tackle"]],
    ["charmander", "blaze", ["ember", "scratch"]],
    ["squirtle", "torrent", ["watergun", "tackle"]],
    ["eevee", "runaway", ["quickattack", "tackle"]],
    ["meowth", "pickup", ["fakeout", "scratch"]],
  ];
  const makeMember = ([name, ability, moveset], slot) => ({
    slot,
    species: name,
    level: 50,
    nature: "hardy",
    ability,
    heldItem: "",
    moveset,
  });
  const createMultiScenario = (battleType) => ({
    ...structuredClone(scenario),
    scenarioId: `${battleType}-test`,
    battleType,
    sides: [
      {
        ...scenario.sides[0],
        team: species.slice(0, 3).map(makeMember),
      },
      {
        ...scenario.sides[1],
        team: species.slice(3).map(makeMember),
      },
    ],
  });

  const doubleBattle = await runAutomaticBattle(createMultiScenario("double"), {
    timeoutMs: 5_000,
  });
  const tripleBattle = await runAutomaticBattle(createMultiScenario("triple"), {
    timeoutMs: 5_000,
  });

  assert.equal(doubleBattle.engine.format, "gen9doublescustomgame");
  assert.equal(doubleBattle.settings.battleType, "double");
  assert.ok(doubleBattle.log.some((line) => line.startsWith("|switch|p1b:")));
  assert.equal(tripleBattle.engine.format, "gen9triples");
  assert.equal(tripleBattle.settings.battleType, "triple");
  assert.ok(tripleBattle.log.some((line) => line.startsWith("|switch|p1c:")));
});

test("reports and omits unsupported optional data", () => {
  const altered = structuredClone(scenario);
  altered.sides[0].team[0].heldItem = "not_a_real_item";
  altered.sides[0].team[0].ability = "not_a_real_ability";

  const result = convertScenarioTeams(altered);
  assert.equal(result.warnings.length, 2);
  assert.ok(
    result.warnings.some(
      (entry) => entry.code === "showdown_item_unavailable",
    ),
  );
  assert.ok(result.warnings.some((entry) => entry.code === "unknown_ability"));
});

test("parses detailed Showdown battle events", () => {
  const events = battleEvents([
    "|turn|3",
    "|move|p1a: Pikachu|Thunderbolt|p2a: Squirtle",
    "|-supereffective|p2a: Squirtle",
    "|-crit|p2a: Squirtle",
    "|-unboost|p2a: Squirtle|spd|1",
    "|-item|p2a: Squirtle|Oran Berry",
  ]);

  assert.deepEqual(
    events.map((event) => event.type),
    ["turn", "move", "super_effective", "critical", "stat_down", "item"],
  );
  assert.deepEqual(events[4], {
    turn: 3,
    type: "stat_down",
    actor: "p2a: Squirtle",
    detail: "spd",
    condition: "1",
  });
});

test("preserves item and residual effect sources for damage explanations", () => {
  const events = battleEvents([
    "|turn|5",
    "|-damage|p1a: Weavile|123/281|[from] item: Life Orb",
    "|-damage|p1a: Urshifu|88/301|[from] item: Rocky Helmet|[of] p2a: Ferrothorn",
    "|-damage|p2a: Garganacl|201/404|[from] Salt Cure",
    "|-heal|p2a: Garganacl|226/404|[from] item: Leftovers",
  ]);

  assert.deepEqual(events[1], {
    turn: 5,
    type: "damage",
    actor: "p1a: Weavile",
    condition: "123/281",
    source: "item: Life Orb",
  });
  assert.equal(events[2].source, "item: Rocky Helmet");
  assert.equal(events[2].sourceActor, "p2a: Ferrothorn");
  assert.equal(events[3].source, "Salt Cure");
  assert.equal(events[4].source, "item: Leftovers");
});

test("preserves weather, terrain, and side effect names for the battle field UI", () => {
  const events = battleEvents([
    "|turn|4",
    "|-weather|RainDance",
    "|-fieldstart|move: Electric Terrain",
    "|-sidestart|p1: player|move: Stealth Rock",
    "|-sideend|p1: player|move: Stealth Rock",
    "|-fieldend|move: Electric Terrain",
  ]);

  assert.deepEqual(events.slice(1), [
    { turn: 4, type: "weather", detail: "RainDance" },
    {
      turn: 4,
      type: "field_started",
      actor: "",
      detail: "move: Electric Terrain",
    },
    {
      turn: 4,
      type: "field_started",
      actor: "p1: player",
      detail: "move: Stealth Rock",
    },
    {
      turn: 4,
      type: "field_ended",
      actor: "p1: player",
      detail: "move: Stealth Rock",
    },
    {
      turn: 4,
      type: "field_ended",
      actor: "",
      detail: "move: Electric Terrain",
    },
  ]);
});
