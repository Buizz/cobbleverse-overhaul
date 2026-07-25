import assert from "node:assert/strict";
import test from "node:test";

import {
  getMoveRoleEntry,
  getMoveRoleScore,
  loadMoveRoleCatalog,
} from "../lib/ai-api-bridge/move-role-catalog.mjs";
import { toAiApiObservationDraft } from "../lib/ai-api-bridge/observation-adapter.mjs";

test("loads shared AI move role catalog through the web bridge", async () => {
  const catalog = await loadMoveRoleCatalog();
  assert.ok(getMoveRoleEntry(catalog, "Stealth Rock").tags.includes("HAZARD_SET"));
  assert.ok(getMoveRoleScore(catalog, "Swords Dance", "setupSweeper") >= 4);
});

test("converts web battle state into an ai-api-like observation draft", () => {
  const observation = toAiApiObservationDraft({
    battleId: "demo",
    seed: "seed-1",
    format: "singles",
    engine: "cobbleverse",
    side: "player",
    turn: 3,
    activePokemon: {
      species: "Porygon2",
      hp: 120,
      maxHp: 192,
      types: ["Normal"],
      boosts: { spa: 1 },
      item: "eviolite",
      ability: "download",
    },
    opponentPokemon: {
      species: "Mawile",
      hp: 88,
      maxHp: 140,
      types: ["Steel", "Fairy"],
    },
    candidates: [{ type: "move", moveId: "triattack", name: "트라이어택" }],
  });

  assert.equal(observation.source, "web-lab");
  assert.equal(observation.activePokemon.item, "eviolite");
  assert.equal(observation.candidates[0].id, "triattack");
});
