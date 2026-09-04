const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../web/app.js"), "utf8");
const functions = source.slice(
  source.indexOf("function cavePreviewCanvasCoordinates("),
  source.indexOf("function cavePreviewTargetPriority("),
);
const context = vm.createContext({});
vm.runInContext(functions, context);

test("dungeon cave entrances are selectable without becoming draggable", () => {
  assert.match(source, /context === "dungeon" \? "select-entrance" : "move"/);
  assert.match(source, /target\.mode === "select-path" \|\| target\.mode === "select-entrance"/);
});

test("cave pointer coordinates account for a cover-cropped canvas", () => {
  const canvas = { width: 960, height: 560 };
  const bounds = { left: 200, top: 100, width: 400, height: 560 };

  assert.deepEqual(
    JSON.parse(JSON.stringify(context.cavePreviewCanvasCoordinates(canvas, bounds, 400, 380, "cover"))),
    { x: 480, y: 280 },
  );
  assert.deepEqual(
    JSON.parse(JSON.stringify(context.cavePreviewCanvasCoordinates(canvas, bounds, 200, 100, "cover"))),
    { x: 280, y: 0 },
  );
});

test("cave room hit testing follows the full rendered ellipse", () => {
  const room = { mode: "move", x: 100, y: 100, radius: 1, hitRadiusX: 43, hitRadiusY: 13 };

  assert.ok(context.cavePreviewHitDistance(room, { x: 142, y: 100 }) <= room.radius);
  assert.ok(context.cavePreviewHitDistance(room, { x: 100, y: 112 }) <= room.radius);
  assert.ok(context.cavePreviewHitDistance(room, { x: 144, y: 100 }) > room.radius);
  assert.ok(context.cavePreviewHitDistance(room, { x: 100, y: 114 }) > room.radius);
});

test("cave path and circular marker hit testing remain unchanged", () => {
  const pathTarget = { mode: "select-path", x1: 10, y1: 10, x2: 30, y2: 10, radius: 9 };
  const marker = { mode: "move", x: 50, y: 50, radius: 10 };

  assert.equal(context.cavePreviewHitDistance(pathTarget, { x: 20, y: 16 }), 6);
  assert.equal(context.cavePreviewHitDistance(marker, { x: 56, y: 58 }), 10);
});
