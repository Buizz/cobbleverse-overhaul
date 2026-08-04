import assert from "node:assert/strict";
import test from "node:test";

import {
  BATTLE_STATUSES,
  healthFromCondition,
  normalizeBattleStatus,
  statusByPokemon,
  statusFromCondition,
} from "../lib/battle-status.mjs";

test("normalizes every persistent Pokémon status", () => {
  assert.deepEqual(Object.keys(BATTLE_STATUSES), [
    "brn",
    "par",
    "slp",
    "frz",
    "psn",
    "tox",
  ]);
  assert.equal(normalizeBattleStatus("PAR"), "par");
  assert.equal(normalizeBattleStatus("confusion"), null);
});

test("separates HP and status from Showdown condition strings", () => {
  assert.equal(statusFromCondition("143/200 par"), "par");
  assert.equal(statusFromCondition("52/100 tox"), "tox");
  assert.equal(statusFromCondition("0 fnt"), null);
  assert.equal(healthFromCondition("143/200 par"), "143/200");
});

test("tracks status application and recovery per Pokémon", () => {
  const applied = statusByPokemon([
    {
      type: "status",
      actor: "p1a: Pikachu",
      detail: "par",
    },
    {
      type: "damage",
      actor: "p2a: Snorlax",
      condition: "201/300 psn",
    },
  ]);
  assert.equal(applied.get("pikachu"), "par");
  assert.equal(applied.get("snorlax"), "psn");

  const cured = statusByPokemon([
    {
      type: "status",
      actor: "p1a: Pikachu",
      detail: "par",
    },
    {
      type: "status_cured",
      actor: "p1a: Pikachu",
      detail: "par",
    },
  ]);
  assert.equal(cured.has("pikachu"), false);
});

test("keeps Toxic visible after native residual damage without a status suffix", () => {
  const statuses = statusByPokemon([
    {
      type: "status",
      actor: "p2a: Ho-Oh",
      detail: "tox",
    },
    {
      type: "damage",
      actor: "p2a: Ho-Oh",
      condition: "150/200",
      source: "tox",
    },
  ]);

  assert.equal(statuses.get("hooh"), "tox");
});
