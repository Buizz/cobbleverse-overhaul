import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const coveragePath = new URL(
  "../public/data/native-mechanics-coverage.json",
  import.meta.url,
);

test("reports native mechanics coverage for the full move catalog", async () => {
  const report = JSON.parse(await readFile(coveragePath, "utf8"));
  const byId = new Map(report.moves.map((move) => [move.id, move]));

  assert.equal(report.trainerCount, 217);
  assert.equal(report.totalMoveCount, report.moves.length);
  assert.ok(report.totalMoveCount >= 900);
  assert.equal(report.trainerUsedMoveCount, 564);
  assert.equal(report.statusCounts.UNKNOWN, 0);
  assert.equal(byId.get("surgingstrikes").status, "SUPPORTED");
  assert.equal(byId.get("surgingstrikes").usedByTrainers, true);
  assert.equal(byId.get("stealthrock").status, "SUPPORTED");
  assert.equal(byId.get("trickroom").status, "SUPPORTED");
  assert.equal(byId.get("heatcrash").status, "SUPPORTED");
  assert.ok(byId.get("heatcrash").requirements.includes("dynamicPower"));
  assert.equal(byId.get("acrobatics").status, "SUPPORTED");
  assert.ok(
    report.sourceReferences.some(
      (source) => source.id === "smogon-champions-moves",
    ),
  );
});
