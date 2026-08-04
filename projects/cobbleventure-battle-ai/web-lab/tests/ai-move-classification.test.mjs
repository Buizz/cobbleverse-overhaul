import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const catalog = JSON.parse(
  await readFile(
    new URL("../../data/ai/ai-move-role-classification.json", import.meta.url),
    "utf8",
  ),
);

function move(id) {
  const entry = catalog.moves[id];
  assert.ok(entry, `${id} should exist`);
  return entry;
}

test("classifies common battle roles for AI scoring", () => {
  assert.ok(move("stealthrock").tags.includes("HAZARD_SET"));
  assert.ok(move("stealthrock").roleScores.hazardControl >= 4);

  assert.ok(move("swordsdance").tags.includes("SETUP_BOOST"));
  assert.ok(move("swordsdance").roleScores.setupSweeper >= 4);

  assert.ok(move("recover").tags.includes("RECOVERY"));
  assert.ok(move("recover").roleScores.wall >= 4);

  assert.ok(move("uturn").tags.includes("PIVOT"));
  assert.ok(move("uturn").roleScores.pivot >= 4);

  assert.ok(move("rapidspin").tags.includes("HAZARD_REMOVE"));
  assert.ok(move("rapidspin").roleScores.hazardControl >= 4);

  assert.ok(move("taunt").tags.includes("DISRUPT"));
  assert.ok(move("taunt").roleScores.disruptor >= 4);

  assert.ok(move("extremespeed").tags.includes("PRIORITY"));
  assert.ok(move("extremespeed").roleScores.revengeKiller >= 3);
});
