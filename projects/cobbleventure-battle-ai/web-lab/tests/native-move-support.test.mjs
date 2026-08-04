import assert from "node:assert/strict";
import test from "node:test";

import {
  createNativeMoveSupportIndex,
  findNativeMoveSupportWarnings,
} from "../lib/native-move-support.mjs";

const coverage = {
  moves: [
    { id: "tackle", name: "Tackle", status: "SUPPORTED" },
    {
      id: "destinybond",
      name: "Destiny Bond",
      status: "PARTIAL",
      requirements: ["volatileStatus"],
    },
    {
      id: "sketch",
      name: "Sketch",
      status: "UNSUPPORTED",
      callbacks: ["onHit"],
    },
  ],
};

function scenario(battleEngine = "cobbleventure") {
  return {
    battleEngine,
    sides: [
      {
        name: "Player",
        team: [
          {
            species: "Gengar",
            resolvedSpecies: "Gengar",
            moveset: ["tackle", "destinybond", "sketch", "missingmove"],
          },
        ],
      },
      { name: "Opponent", team: [] },
    ],
  };
}

test("indexes native move support by canonical move id", () => {
  const index = createNativeMoveSupportIndex(coverage);
  assert.equal(index.get("destinybond").status, "PARTIAL");
});

test("warns for partial, unsupported, and unknown native moves", () => {
  const warnings = findNativeMoveSupportWarnings(scenario(), coverage);
  assert.deepEqual(
    warnings.map((warning) => warning.status),
    ["PARTIAL", "UNSUPPORTED", "UNKNOWN"],
  );
  assert.match(warnings[0].message, /Destiny Bond/);
  assert.match(warnings[0].message, /volatileStatus/);
  assert.equal(warnings[2].moveId, "missingmove");
});

test("does not warn for Showdown scenarios", () => {
  assert.deepEqual(
    findNativeMoveSupportWarnings(scenario("showdown"), coverage),
    [],
  );
});
