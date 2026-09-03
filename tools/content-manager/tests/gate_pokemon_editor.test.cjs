const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
require("../web/player-condition-editor.js");

function fixture(overrides = {}) {
  const values = {
    species: "cobblemon:snorlax", level: "30", pose: "sleep", scale: "1",
    width: "3", height: "2", depth: "4", completion_flag: "", event_binding: "", activation_item: "", ...overrides
  };
  const activation = { dataset: {}, querySelector: () => ({ innerHTML: "" }), addEventListener() {} };
  globalThis.PlayerConditionEditor.initialize(activation);
  globalThis.PlayerConditionEditor.render(activation, [{ type: "flag", key: "cv:flag/flute", value: 1 }]);
  const root = { querySelector(selector) {
    if (selector === "[data-pokemon-activation]") return activation;
    const key = selector.match(/data-pokemon-field="([^"]+)"/)[1];
    return { value: values[key] };
  } };
  const context = vm.createContext({ PlayerConditionEditor: globalThis.PlayerConditionEditor, root });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../web/gate-pokemon-editor.js"), "utf8"), context);
  return () => JSON.parse(JSON.stringify(vm.runInContext("GatePokemonEditor.read(root)", context)));
}

test("sleeping gate serializes shared activation conditions without inventing completion keys", () => {
  const pokemon = fixture()();
  assert.equal(pokemon.species, "cobblemon:snorlax");
  assert.equal(pokemon.pose, "sleep");
  assert.deepEqual(pokemon.collision, { width: 3, height: 2, depth: 4 });
  assert.deepEqual(pokemon.activation_conditions, [{ type: "flag", key: "cv:flag/flute", value: 1 }]);
  assert.equal(Object.hasOwn(pokemon, "completion_flag"), false);
});

test("standing Sudowoodo preserves explicit event binding and completion key", () => {
  const pokemon = fixture({ species: "cobblemon:sudowoodo", pose: "stand",
    event_binding: "cv:story/sudowoodo", completion_flag: "cv:flag/sudowoodo_cleared" })();
  assert.equal(pokemon.pose, "stand");
  assert.equal(pokemon.event_binding, "cv:story/sudowoodo");
  assert.equal(pokemon.completion_flag, "cv:flag/sudowoodo_cleared");
});

test("tool activation preserves the registered item independently of possession conditions", () => {
  const pokemon = fixture({ activation_item: "cobbleventure_bootstrap:poke_flute" })();
  assert.equal(pokemon.activation_item, "cobbleventure_bootstrap:poke_flute");
  assert.equal(pokemon.activation_conditions.length, 1);
  assert.equal(Object.hasOwn(fixture()(), "activation_item"), false);
  assert.throws(fixture({ activation_item: "invalid item!" }));
});

test("invalid species, pose, fractional level, and collision sizes are rejected", () => {
  for (const overrides of [{ species: "snorlax" }, { pose: "fly" }, { level: "30.5" },
    { level: "101" }, { height: "0" }, { width: "NaN" }, { depth: "17" }, { scale: "" },
    { completion_flag: "bad key" }]) assert.throws(fixture(overrides));
});
