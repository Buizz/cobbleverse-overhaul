import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { createBattleScenario } from "../lib/battle-scenario.mjs";
import { createCobblemonItemResolver } from "../lib/cobblemon-item-catalog.mjs";

const index = JSON.parse(
  await readFile(new URL("../public/data/trainers.json", import.meta.url), "utf8"),
);
const itemCatalog = JSON.parse(
  await readFile(
    new URL("../public/data/cobblemon-battle-items.json", import.meta.url),
    "utf8",
  ),
);
const itemResolver = createCobblemonItemResolver(itemCatalog);

test("creates a deterministic PvE scenario from a custom party and preset", () => {
  const input = {
    mode: "pve",
    seed: 1234,
    sides: [
      {
        source: "custom",
        name: "Player",
        team: [
          {
            species: "garchomp",
            level: 50,
            heldItem: "leftovers",
            gimmicks: {
              dynamax: true,
              gmax: false,
              tera: "ground",
            },
            moves: ["earthquake"],
          },
        ],
      },
      { source: "preset", trainerId: "kanto_brock" },
    ],
  };

  const first = createBattleScenario(input, index.trainers, itemResolver);
  const second = createBattleScenario(input, index.trainers, itemResolver);

  assert.equal(first.ok, true);
  assert.deepEqual(first, second);
  assert.match(first.scenario.scenarioId, /^pve-000004d2-[0-9a-f]{8}$/);
  assert.equal(first.scenario.sides[0].team[0].moveset[0], "earthquake");
  assert.equal(
    first.scenario.sides[0].team[0].heldItem,
    "cobblemon:leftovers",
  );
  assert.equal(first.scenario.sides[1].name, "Brock");
  assert.equal(first.scenario.sides[1].team[0].gimmicks.dynamax, false);
  assert.equal(first.scenario.battleType, "single");
  assert.equal(first.scenario.battleEngine, "showdown");
  assert.equal(first.scenario.aiDifficulty, "standard");
  assert.deepEqual(first.scenario.sides[0].team[0].gimmicks, {
    mega: false,
    dynamax: true,
    gmax: false,
    tera: "ground",
    teraEligible: true,
  });
  assert.equal(first.scenario.sides[1].bag[0].item, "cobblemon:full_restore");
  assert.equal(first.scenario.sides[1].battleRules.maxItemUses, 99);
  assert.deepEqual(first.scenario.itemRules, {
    source: "trainer",
    items: [],
    maxUses: null,
  });
});

test("stores the virtual battle-wide trainer item rules", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 2468,
      battleEngine: "cobbleventure",
      itemRules: {
        source: "global",
        items: [
          "cobblemon:full_restore",
          "cobblemon:potion",
          "cobblemon:full_heal",
          "cobblemon:hyper_potion",
        ],
        maxUses: 2,
      },
      sides: [
        {
          source: "custom",
          name: "Player",
          team: [{ species: "pikachu", level: 50, moves: ["thunderbolt"] }],
        },
        {
          source: "custom",
          name: "Opponent",
          team: [{ species: "eevee", level: 50, moves: ["quickattack"] }],
        },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  assert.deepEqual(result.scenario.itemRules, {
    source: "global",
    items: [
      "cobblemon:full_restore",
      "cobblemon:potion",
      "cobblemon:full_heal",
    ],
    maxUses: 2,
  });
});

test("accepts an independently edited custom PvE opponent", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 4321,
      battleEngine: "cobbleventure",
      sides: [
        {
          source: "custom",
          name: "Player Entry",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        {
          source: "custom",
          name: "Computer Entry",
          team: [
            {
              species: "eevee",
              level: 50,
              ability: "adaptability",
              moves: ["quickattack"],
            },
          ],
        },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  assert.equal(result.scenario.sides[0].name, "Player Entry");
  assert.equal(result.scenario.sides[1].source, "custom");
  assert.equal(result.scenario.sides[1].name, "Computer Entry");
  assert.equal(result.scenario.sides[1].team[0].species, "eevee");
  assert.equal(result.scenario.sides[1].team[0].moveset[0], "quickattack");
});

test("keeps custom entries available when switching to EvE", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 9876,
      battleEngine: "cobbleventure",
      sides: [
        {
          source: "custom",
          name: "Engine A Entry",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        {
          source: "custom",
          name: "Engine B Entry",
          team: [
            {
              species: "eevee",
              level: 50,
              moves: ["quickattack"],
            },
          ],
        },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  assert.deepEqual(
    result.scenario.sides.map((side) => [side.source, side.name]),
    [
      ["custom", "Engine A Entry"],
      ["custom", "Engine B Entry"],
    ],
  );
});

test("applies the RCT Tera target policy to the designated member", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 1234,
      battleEngine: "cobbleventure",
      sides: [
        {
          source: "custom",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        { source: "preset", trainerId: "kanto_league_lance" },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  const opponent = result.scenario.sides[1];
  assert.equal(opponent.ai.data.canTera, true);
  assert.equal(opponent.ai.data.teraTarget, "dragonite");
  assert.deepEqual(
    opponent.team
      .filter((member) => member.gimmicks.teraEligible)
      .map((member) => member.species),
    ["dragonite"],
  );
  assert.equal(
    opponent.team.find((member) => member.species === "dragonite").gimmicks.tera,
    "normal",
  );
});

test("does not require the in-game canTera flag for virtual battles", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 1234,
      battleEngine: "cobbleventure",
      sides: [
        {
          source: "custom",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        { source: "preset", trainerId: "hoenn_league_drake" },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  const calyrex = result.scenario.sides[1].team.find(
    (member) => member.species === "calyrex",
  );
  assert.equal(calyrex.gimmicks.tera, "fairy");
  assert.equal(calyrex.gimmicks.teraEligible, true);
  assert.ok(
    result.scenario.sides[1].team.every(
      (member) => member.gimmicks.teraEligible === true,
    ),
  );
});

test("reads Cobblemon-style Tera Type properties into virtual scenarios", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 1234,
      battleEngine: "cobbleventure",
      sides: [
        {
          source: "custom",
          team: [
            {
              species: "garchomp",
              level: 50,
              teraType: "Steel",
              moves: ["earthquake"],
            },
          ],
        },
        { source: "preset", trainerId: "kanto_brock" },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  assert.equal(result.scenario.sides[0].team[0].gimmicks.tera, "steel");
});

test("applies a preset team order to the scenario and lead slot", () => {
  const brock = index.trainers.find((trainer) => trainer.id === "kanto_brock");
  const reversedOrder = [...brock.team].reverse().map((member) => member.slot);
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 1234,
      sides: [
        {
          source: "custom",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        {
          source: "preset",
          trainerId: "kanto_brock",
          teamOrder: reversedOrder,
        },
      ],
    },
    index.trainers,
    itemResolver,
  );

  assert.equal(result.ok, true);
  assert.equal(
    result.scenario.sides[1].team[0].species,
    brock.team.at(-1).species,
  );
  assert.deepEqual(
    result.scenario.sides[1].team.map((member) => member.slot),
    brock.team.map((_, index) => index + 1),
  );
});

test("applies preset team order when source slots are string values", () => {
  const brock = index.trainers.find((trainer) => trainer.id === "kanto_brock");
  const trainer = {
    ...brock,
    id: "test_string_slot_trainer",
    team: brock.team.map((member) => ({
      ...member,
      slot: String(member.slot),
    })),
  };
  const reversedOrder = [...trainer.team].reverse().map((member) => member.slot);
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 1234,
      sides: [
        {
          source: "custom",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        {
          source: "preset",
          trainerId: trainer.id,
          teamOrder: reversedOrder,
        },
      ],
    },
    [...index.trainers, trainer],
    itemResolver,
  );

  assert.equal(result.ok, true);
  assert.equal(
    result.scenario.sides[1].team[0].species,
    brock.team.at(-1).species,
  );
});

test("preserves a generated preset team order when revalidating a scenario", () => {
  const brock = index.trainers.find((trainer) => trainer.id === "kanto_brock");
  const reversedOrder = [...brock.team].reverse().map((member) => member.slot);
  const generated = createBattleScenario(
    {
      mode: "pve",
      seed: 1234,
      sides: [
        {
          source: "custom",
          team: [
            {
              species: "pikachu",
              level: 50,
              moves: ["thunderbolt"],
            },
          ],
        },
        {
          source: "preset",
          trainerId: "kanto_brock",
          teamOrder: reversedOrder,
        },
      ],
    },
    index.trainers,
    itemResolver,
  );
  assert.equal(generated.ok, true);

  const revalidated = createBattleScenario(
    generated.scenario,
    index.trainers,
    itemResolver,
  );

  assert.equal(revalidated.ok, true);
  assert.equal(
    revalidated.scenario.sides[1].team[0].species,
    generated.scenario.sides[1].team[0].species,
  );
  assert.notEqual(
    revalidated.scenario.sides[1].team[0].species,
    brock.team[0].species,
  );
});

test("rejects incomplete custom party members", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 1,
      sides: [
        { source: "custom", team: [{ species: "pikachu", level: 101, moves: [] }] },
        { source: "preset", trainerId: "kanto_brock" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, false);
  assert.ok(result.issues.some((entry) => entry.path === "sides.0.team.0.level"));
  assert.ok(result.issues.some((entry) => entry.path === "sides.0.team.0.moveset"));
});

test("applies the selected level mode to both parties", () => {
  const baseInput = {
    mode: "eve",
    seed: 42,
    sides: [
      { source: "preset", trainerId: "kanto_brock" },
      { source: "preset", trainerId: "kanto_misty" },
    ],
  };

  const level50 = createBattleScenario(
    { ...baseInput, levelMode: "level-50" },
    index.trainers,
  );
  const level100 = createBattleScenario(
    { ...baseInput, levelMode: "level-100" },
    index.trainers,
  );

  assert.equal(level50.ok, true);
  assert.equal(level100.ok, true);
  assert.equal(level50.scenario.levelMode, "level-50");
  assert.ok(
    level50.scenario.sides.every((side) =>
      side.team.every((member) => member.level === 50),
    ),
  );
  assert.ok(
    level100.scenario.sides.every((side) =>
      side.team.every((member) => member.level === 100),
    ),
  );
});

test("preserves the selected AI difficulty and battle engine", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 42,
      battleEngine: "cobbleventure",
      aiDifficulty: "expert",
      aiProfiles: [
        { difficulty: "expert", strategy: "ace_check" },
        { difficulty: "novice", strategy: "tempo" },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, true);
  assert.equal(result.scenario.battleEngine, "cobbleventure");
  assert.equal(result.scenario.gimmickRules, "all");
  assert.equal(result.scenario.aiDifficulty, "expert");
  assert.deepEqual(result.scenario.aiProfiles, [
    { difficulty: "expert", strategy: "ace_check" },
    { difficulty: "novice", strategy: "tempo" },
  ]);
});

test("accepts the separate win-probability expert policy", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 43,
      battleEngine: "cobbleventure",
      aiDifficulty: "expert_winrate",
      aiProfiles: [
        { difficulty: "expert", strategy: "balanced" },
        { difficulty: "expert_winrate", strategy: "balanced" },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, true);
  assert.equal(result.scenario.aiDifficulty, "expert_winrate");
  assert.equal(result.scenario.aiProfiles[0].difficulty, "expert");
  assert.equal(result.scenario.aiProfiles[1].difficulty, "expert_winrate");
});

test("accepts the separate two-turn search expert policy", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 44,
      battleEngine: "cobbleventure",
      aiDifficulty: "expert_search",
      aiProfiles: [
        { difficulty: "expert", strategy: "balanced" },
        { difficulty: "expert_search", strategy: "balanced" },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, true);
  assert.equal(result.scenario.aiDifficulty, "expert_search");
  assert.equal(result.scenario.aiProfiles[0].difficulty, "expert");
  assert.equal(result.scenario.aiProfiles[1].difficulty, "expert_search");
});

test("preserves and validates per-side cheater activation probability", () => {
  const valid = createBattleScenario(
    {
      mode: "eve",
      seed: 44,
      battleEngine: "cobbleventure",
      aiDifficulty: "cheater",
      aiProfiles: [
        {
          difficulty: "cheater",
          strategy: "balanced",
          cheatProbability: 0.25,
        },
        {
          difficulty: "expert",
          strategy: "tempo",
        },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );
  assert.equal(valid.ok, true);
  assert.equal(valid.scenario.aiProfiles[0].cheatProbability, 0.25);
  assert.equal(valid.scenario.aiProfiles[1].cheatProbability, undefined);

  const invalid = createBattleScenario(
    {
      mode: "eve",
      seed: 45,
      battleEngine: "cobbleventure",
      aiDifficulty: "cheater",
      aiProfiles: [
        {
          difficulty: "cheater",
          strategy: "balanced",
          cheatProbability: 1.2,
        },
        {
          difficulty: "expert",
          strategy: "balanced",
        },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );
  assert.equal(invalid.ok, false);
  assert.ok(
    invalid.issues.some(
      (entry) =>
        entry.path === "aiProfiles.0.cheatProbability" &&
        entry.code === "invalid",
    ),
  );

  const bothCheaters = createBattleScenario(
    {
      mode: "eve",
      seed: 46,
      battleEngine: "cobbleventure",
      aiProfiles: [
        {
          difficulty: "cheater",
          strategy: "balanced",
          cheatProbability: 0.5,
        },
        {
          difficulty: "cheater",
          strategy: "tempo",
          cheatProbability: 0.5,
        },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );
  assert.equal(bothCheaters.ok, false);
  assert.ok(
    bothCheaters.issues.some(
      (entry) => entry.path === "aiProfiles" && entry.code === "conflict",
    ),
  );
});

test("preserves a supported multi battle type and validates active members", () => {
  const result = createBattleScenario(
    {
      mode: "pve",
      seed: 42,
      battleType: "double",
      sides: [
        {
          source: "custom",
          team: [{ species: "pikachu", level: 50, moves: ["thunderbolt"] }],
        },
        { source: "preset", trainerId: "kanto_brock" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, false);
  assert.ok(
    result.issues.some(
      (entry) =>
        entry.path === "sides.0.team" &&
        entry.code === "insufficient_active_members",
    ),
  );
});

test("rejects multi battles requested through the singles-only native engine", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 42,
      battleType: "triple",
      battleEngine: "cobbleventure",
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, false);
  assert.ok(
    result.issues.some(
      (entry) =>
        entry.path === "battleType" &&
        entry.code === "unsupported_engine_format",
    ),
  );
});

test("rejects an EvE mirror using the same trainer definition", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 42,
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_brock" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, false);
  assert.ok(result.issues.some((entry) => entry.code === "duplicate"));
});

test("rejects unknown trainer IDs instead of trusting client data", () => {
  const result = createBattleScenario(
    {
      mode: "eve",
      seed: 42,
      sides: [
        { source: "preset", trainerId: "missing-one" },
        { source: "preset", trainerId: "missing-two" },
      ],
    },
    index.trainers,
  );

  assert.equal(result.ok, false);
  assert.equal(
    result.issues.filter((entry) => entry.code === "unknown_trainer").length,
    2,
  );
});
