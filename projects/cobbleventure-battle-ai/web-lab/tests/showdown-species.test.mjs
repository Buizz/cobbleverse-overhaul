import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveShowdownMemberSpecies,
  resolveShowdownSpecies,
  showdownSpriteId,
} from "../lib/showdown-species.mjs";

test("resolves base species and namespace-separated Cobblemon IDs", () => {
  assert.equal(showdownSpriteId("Pikachu"), "pikachu");
  assert.equal(showdownSpriteId("cobblemon:pikachu"), "pikachu");
});

test("uses Showdown metadata for regional and alternate-form sprites", () => {
  const cases = {
    "urshifu-rapid-strike": "urshifu-rapidstrike",
    "urshifu-single-strike": "urshifu",
    "rotom-wash": "rotom-wash",
    "darmanitan-galar-zen": "darmanitan-galarzen",
    "ogerpon-wellspring": "ogerpon-wellspring",
    "terapagos-terastal": "terapagos-terastal",
    "tauros-paldea-aqua": "tauros-paldeaaqua",
  };

  for (const [source, expected] of Object.entries(cases)) {
    const species = resolveShowdownSpecies(source);
    assert.equal(species.exists, true, source);
    assert.equal(species.spriteId, expected, source);
  }
});

test("resolves Cobblemon aspect-based forms through Showdown metadata", () => {
  const cases = [
    [{ species: "articuno", aspects: ["galarian"] }, "Articuno-Galar"],
    [{ species: "darmanitan", aspects: ["galarian", "zen"] }, "Darmanitan-Galar-Zen"],
    [{ species: "urshifu", aspects: ["rapid_strike-style"] }, "Urshifu-Rapid-Strike"],
    [{ species: "ursaluna", aspects: ["bloodmoon"] }, "Ursaluna-Bloodmoon"],
    [{ species: "calyrex", aspects: ["shadow-rider"] }, "Calyrex-Shadow"],
    [{ species: "kyurem", aspects: ["black-fusion"] }, "Kyurem-Black"],
    [{ species: "giratina", aspects: ["origin-forme"] }, "Giratina-Origin"],
    [{ species: "rotom", aspects: ["wash-appliance"] }, "Rotom-Wash"],
  ];

  for (const [member, expected] of cases) {
    assert.equal(
      resolveShowdownMemberSpecies(member).showdownName,
      expected,
      JSON.stringify(member),
    );
  }
});

test("returns a stable fallback for species missing from Showdown data", () => {
  assert.deepEqual(resolveShowdownSpecies("custom_missing_form"), {
    exists: false,
    source: "custom-missing-form",
    showdownId: null,
    showdownName: null,
    spriteId: "custom-missing-form",
    baseSpecies: null,
    forme: null,
  });
});

test("uses the base sprite for custom Mega forms missing from Showdown", () => {
  const resolved = resolveShowdownSpecies("Dragalge-Mega");

  assert.equal(resolved.spriteId, "dragalge");
  assert.equal(showdownSpriteId("Dragalge-Mega"), "dragalge");
});
