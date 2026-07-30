import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import {
  applyPokemonBuildSample,
  normalizePokemonBuildSample,
} from "../lib/pokemon-build-samples.mjs";

const sampleRoot = new URL("../public/data/pokemon-samples/", import.meta.url);

test("sample index points to valid individual JSON builds", async () => {
  const index = JSON.parse(
    await readFile(new URL("index.json", sampleRoot), "utf8"),
  );
  assert.ok(index.samples.length >= 60);

  const samples = await Promise.all(
    index.samples.map(async (path) =>
      normalizePokemonBuildSample(
        JSON.parse(await readFile(new URL(path, sampleRoot), "utf8")),
      ),
    ),
  );
  assert.ok(samples.every(Boolean));
  assert.ok(samples.some((sample) => sample.format === "sv"));
  assert.ok(samples.some((sample) => sample.format === "champions"));
  assert.ok(
    samples.some(
      (sample) =>
        sample.species === "Hippowdon" && sample.moves.includes("Yawn"),
    ),
  );
});

test("applying a sample replaces the whole battle build", () => {
  const current = {
    species: "",
    level: 100,
    nature: "",
    ability: "",
    heldItem: "",
    ivs: {},
    evs: {},
    tera: "",
    dynamax: true,
    gmax: true,
    moves: ["", "", "", ""],
  };
  const sample = {
    id: "test",
    species: "Garchomp",
    level: 50,
    nature: "Jolly",
    ability: "Rough Skin",
    heldItem: "Rocky Helmet",
    tera: "steel",
    ivs: { hp: 31, atk: 31, def: 31, spa: 0, spd: 31, spe: 31 },
    evs: { hp: 4, atk: 252, def: 0, spa: 0, spd: 0, spe: 252 },
    moves: ["Earthquake", "Dragon Claw", "Swords Dance", "Protect"],
  };

  const applied = applyPokemonBuildSample(current, sample);
  assert.equal(applied.species, "Garchomp");
  assert.equal(applied.nature, "Jolly");
  assert.equal(applied.moves[2], "Swords Dance");
  assert.equal(applied.evs.atk, 252);
  assert.equal(applied.dynamax, false);
  assert.equal(applied.gmax, false);
});
