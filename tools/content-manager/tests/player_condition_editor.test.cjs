const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
require("../web/player-condition-editor.js");
const conditions = globalThis.PlayerConditionEditor;

test("quest saves preserve hidden legacy settings and omit them from new documents", () => {
  const source = fs.readFileSync(path.join(__dirname, "../web/quest-editor.js"), "utf8");
  const serializer = source.slice(source.indexOf("function documentFromForm()"), source.indexOf("function fill("));
  const legacy = {
    guidance: { required_tools: [{ type: "field_move", id: "surf", reason: { ko_kr: "물길 이동" } }] },
    next_quests: ["cv:quest/main/next"]
  };
  const fields = {
    id: "cv:quest/main/test", nameKo: "수정한 이름", nameEn: "", summaryKo: "설명",
    summaryEn: "", category: "main", acceptMode: "all", completionMode: "automatic"
  };
  const context = vm.createContext({
    state: { document: structuredClone(legacy) },
    form: { elements: {
      ...Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, { value }])),
      enabled: { checked: true }
    } },
    conditionEditor: { read: () => [] }, acceptEditor: {}, objectivesFromEditor: () => []
  });
  vm.runInContext(serializer, context);
  const saved = vm.runInContext("documentFromForm()", context);
  assert.deepEqual(saved.guidance, legacy.guidance);
  assert.deepEqual(saved.next_quests, legacy.next_quests);
  assert.equal(saved.display_name.ko_kr, "수정한 이름");
  assert.equal(saved.completion.mode, "automatic");
  context.state.document = null;
  const created = vm.runInContext("documentFromForm()", context);
  assert.equal(Object.hasOwn(created, "guidance"), false);
  assert.equal(Object.hasOwn(created, "next_quests"), false);
});

// Minimal host surface: exercise the shared editor's real listeners and serializer.
function editorFixture(initial) {
  const listeners = {};
  const list = { innerHTML: "" };
  const editor = {
    dataset: {},
    querySelector: () => list,
    addEventListener: (type, listener) => { listeners[type] = listener; }
  };
  conditions.initialize(editor, { includeAlways: true });
  conditions.render(editor, initial);
  return {
    editor, list,
    change(index, field, value, type = "input") {
      listeners[type]({ type, target: {
        value, dataset: { gateConditionField: field },
        closest: () => ({ dataset: { gateConditionIndex: String(index) } }),
        matches: () => false
      } });
    },
    select(index, selector, value) {
      listeners.change({ type: "change", target: {
        value, dataset: {},
        closest: () => ({ dataset: { gateConditionIndex: String(index) } }),
        matches: candidate => candidate === selector
      } });
    },
    click(action, index = 0) {
      listeners.click({ target: {
        closest: selector => selector === `[data-gate-condition-${action}]`
          ? { closest: () => ({ dataset: { gateConditionIndex: String(index) } }) } : null
      } });
    }
  };
}

test("round trips all supported condition types, legacy values, and negation", () => {
  const initial = [
    { type: "always" },
    { type: "flag", key: "cv:flag/number", value: 3 },
    { type: "flag", key: "cv:flag/boolean", value: false },
    { type: "flag_equals", key: "cv:flag/legacy", value: "chapter2" },
    { type: "has_item", item: "minecraft:stick", count: 2 },
    { type: "item", item: "minecraft:diamond", count: 5, negate: true },
    { type: "badge", badge: "cv:badge/boulder", negate: true },
    { type: "pokemon", species: "cobblemon:pikachu", negate: true },
    { type: "party_count", operator: ">=", value: 3 },
    { type: "variable", source: "persistent_data", key: "chapter", operator: "==", value: 2 }
  ];
  const fixture = editorFixture(initial);
  assert.deepEqual(conditions.read(fixture.editor), initial);
  fixture.change(1, "value", "7");
  assert.equal(conditions.read(fixture.editor)[1].value, 7);
  assert.equal(initial[1].value, 3, "editing must not mutate the loaded document");
  fixture.change(2, "value", "true", "change");
  assert.equal(conditions.read(fixture.editor)[2].value, true);
  fixture.change(3, "value", "chapter3");
  assert.equal(conditions.read(fixture.editor)[3].value, "chapter3");
  fixture.change(5, "count", "9");
  assert.equal(conditions.read(fixture.editor)[5].count, 9, "save sees input without blur");
});

test("add, select condition type, change flag value kind, and remove", () => {
  const fixture = editorFixture([]);
  fixture.click("add");
  assert.equal(conditions.read(fixture.editor)[0].type, "item");
  fixture.select(0, "[data-gate-condition-type]", "flag");
  fixture.select(0, "[data-gate-condition-value-type]", "number");
  fixture.change(0, "value", "4");
  assert.equal(conditions.read(fixture.editor)[0].value, 4);
  fixture.select(0, "[data-gate-condition-type]", "always");
  assert.deepEqual(conditions.read(fixture.editor), [{ type: "always" }]);
  assert.match(fixture.list.innerHTML, /항상 만족/);
  fixture.click("remove");
  assert.deepEqual(conditions.read(fixture.editor), []);
});

test("catalog selections, existing always type, and HTML escaping", () => {
  conditions.configure({ getBadges: () => [{ id: "cv:badge/one", generation: 1, order: 1, display_name: { ko_kr: "돌 배지" } }] });
  assert.equal(conditions.defaultCondition("badge").badge, "cv:badge/one");
  assert.match(conditions.badgeOptions("cv:badge/missing"), /cv:badge\/missing/);
  assert.match(conditions.badgeOptions("cv:badge/one"), /돌 배지/);
  assert.match(conditions.typeOptions("always"), /value="always" selected/);
  const fixture = editorFixture([{ type: "flag_equals", key: "cv:flag/test", value: '\"><script>bad</script>' }]);
  assert.ok(!fixture.list.innerHTML.includes("<script>"));
});

test("invalid IDs and party counts are rejected before save", () => {
  assert.throws(() => conditions.validate([{ type: "badge", badge: "invalid" }]));
  assert.throws(() => conditions.validate([{ type: "party_count", operator: ">=", value: 7 }]));
  assert.throws(() => conditions.validate([{ type: "unknown" }]));
});
