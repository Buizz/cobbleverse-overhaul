const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../web/app.js"), "utf8");
const functions = source.slice(source.indexOf("function pokemonLevelOverride("), source.indexOf("function pokemonCardLevelLabel("));

function fixture() {
  const notices = [];
  const context = vm.createContext({ toast: (message) => notices.push(message) });
  vm.runInContext(functions, context);
  const settings = { level_overrides: [
    { species: "cobblemon:pidgey", min_level: 2, max_level: 5, level_weights: { 2: 10, 3: 35, 4: 4, 5: 1 } },
    { species: "cobblemon:rattata", min_level: 2, max_level: 4 }
  ] };
  return { context, settings, notices };
}

test("reapplying the same level range preserves FireRed weights", () => {
  const { context, settings, notices } = fixture();
  context.updatePokemonLevelRange(settings, "cobblemon:pidgey", 2, 5);
  assert.deepEqual(settings.level_overrides.find(e => e.species === "cobblemon:pidgey").level_weights,
    { 2: 10, 3: 35, 4: 4, 5: 1 });
  assert.equal(notices.length, 0);
  assert.equal(settings.level_overrides.length, 2);
});

test("changing the range clears obsolete weights with an explicit notice", () => {
  const { context, settings, notices } = fixture();
  context.updatePokemonLevelRange(settings, "cobblemon:pidgey", 2, 3);
  const entry = settings.level_overrides.find(e => e.species === "cobblemon:pidgey");
  assert.equal(entry.max_level, 3);
  assert.equal(entry.level_weights, undefined);
  assert.equal(notices.length, 1);
  assert.equal(settings.level_overrides.find(e => e.species === "cobblemon:rattata").max_level, 4);
});
