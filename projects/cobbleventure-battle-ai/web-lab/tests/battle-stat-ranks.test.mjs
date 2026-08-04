import assert from "node:assert/strict";
import test from "node:test";

import { activeStatRanks } from "../lib/battle-stat-ranks.mjs";

test("keeps passed ranks on a Baton Pass switch", () => {
  const ranks = activeStatRanks(
    [
      { type: "stat_up", actor: "p1a: Scolipede", detail: "atk", condition: "2" },
      { type: "stat_up", actor: "p1a: Scolipede", detail: "atk", condition: "2" },
      { type: "stat_up", actor: "p1a: Scolipede", detail: "spe", condition: "1" },
      { type: "stat_up", actor: "p1a: Scolipede", detail: "spe", condition: "1" },
      { type: "switch", actor: "p1a: Annihilape", source: "Baton Pass" },
    ],
    "p1",
  );

  assert.deepEqual(ranks, [["atk", 4], ["spe", 2]]);
});

test("clears both sides' displayed ranks after Haze", () => {
  const events = [
    { type: "stat_up", actor: "p1a: Annihilape", detail: "atk", condition: "4" },
    { type: "stat_up", actor: "p2a: Toxapex", detail: "def", condition: "2" },
    { type: "stat_reset_all", actor: "" },
  ];

  assert.deepEqual(activeStatRanks(events, "p1"), []);
  assert.deepEqual(activeStatRanks(events, "p2"), []);
});
