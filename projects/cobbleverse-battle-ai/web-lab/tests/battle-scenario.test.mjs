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
  });
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
      battleEngine: "cobbleverse",
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
  assert.equal(result.scenario.battleEngine, "cobbleverse");
  assert.equal(result.scenario.gimmickRules, "all");
  assert.equal(result.scenario.aiDifficulty, "expert");
  assert.deepEqual(result.scenario.aiProfiles, [
    { difficulty: "expert", strategy: "ace_check" },
    { difficulty: "novice", strategy: "tempo" },
  ]);
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
      battleEngine: "cobbleverse",
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
